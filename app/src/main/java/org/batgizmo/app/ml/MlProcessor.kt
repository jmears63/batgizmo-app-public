/*
 * Copyright (c) 2025-2026 John Mears
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.batgizmo.app.ml

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.batgizmo.app.ml.MlProcessor.Companion.CHUNK_SIZE_AT_REQUIRED_RATE
import org.batgizmo.app.ml.MlProcessor.Companion.MAX_LIVE_QUEUED_CHUNKS
import org.batgizmo.app.ml.MlProcessor.Companion.REQUIRED_SAMPLE_RATE_HZ
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * How [MlProcessor.tryEnqueue] behaves when many chunks are already waiting.
 *
 * - [DropIfFull]: live audio — never queue more than [MlProcessor.MAX_LIVE_QUEUED_CHUNKS];
 *   drop new chunks so inference stays near real time.
 * - [QueueAll]: file viewer — accept every chunk (unlimited channel); process in order
 *   no matter how long it takes.
 */
enum class MlOverflowPolicy {
    DropIfFull,
    QueueAll,
}

/**
 * ML processing backend.
 *
 * Raw chunks go through a pool of resample workers (r8brain, multi-core), then a
 * single infer worker owns [BattyBirdNET] (LiteRT is not thread-safe).
 *
 * Under [MlOverflowPolicy.DropIfFull], [tryEnqueue] drops when [pendingCount]
 * already reaches [MAX_LIVE_QUEUED_CHUNKS].
 */
class MlProcessor(
    context: Context,
) {
    /**
     * One owned PCM chunk awaiting analysis.
     * [buffer] is fully owned; [offset] / [count] select the live region.
     */
    data class ChunkSubmission(
        val buffer: ShortArray,
        val offset: Int,
        val count: Int,
        val sampleRateHz: Int,
        /** Unix-epoch seconds at the first sample of this chunk. */
        val observedAtEpochSec: Double,
    )

    private data class ResampledJob(
        val window: FloatArray,
        val chunkId: Int,
        val epoch: Long,
        val observedAtEpochSec: Double,
    )

    companion object {
        /** Sample rate expected by the model. */
        const val REQUIRED_SAMPLE_RATE_HZ = BattyBirdNET.SAMPLE_RATE_HZ

        /** Samples per chunk at [REQUIRED_SAMPLE_RATE_HZ]. */
        const val CHUNK_SIZE_AT_REQUIRED_RATE = BattyBirdNET.SIG_SAMPLES

        /**
         * Soft cap on queued chunks in live mode ([MlOverflowPolicy.DropIfFull]).
         * Viewer mode ([MlOverflowPolicy.QueueAll]) ignores this.
         */
        const val MAX_LIVE_QUEUED_CHUNKS = 10

        /**
         * Parallel resample workers. Caps at 4 to limit memory/bandwidth contention.
         */
        fun defaultResampleParallelism(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        /** Omit weak scores from the UI summary (show only confidence > 70%). */
        private const val MIN_CONFIDENCE = 0.7f

        init {
            System.loadLibrary("batgizmo-native")
        }

        /**
         * Chunk length in samples for [sampleRateHz], scaled so duration matches
         * [CHUNK_SIZE_AT_REQUIRED_RATE] samples at [REQUIRED_SAMPLE_RATE_HZ].
         */
        fun chunkSizeForSampleRate(sampleRateHz: Int): Int {
            require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
            // Ceiling division so a fractional sample count rounds up.
            val scaled =
                (CHUNK_SIZE_AT_REQUIRED_RATE.toLong() * sampleRateHz +
                    REQUIRED_SAMPLE_RATE_HZ - 1) /
                    REQUIRED_SAMPLE_RATE_HZ
            return scaled.toInt().coerceAtLeast(1)
        }

        /**
         * Resample mono 16-bit PCM to [REQUIRED_SAMPLE_RATE_HZ] via r8brain (native).
         * Returns a newly allocated buffer, or null on failure.
         * Safe to call from multiple threads (oneshot resampler per call).
         */
        @JvmStatic
        private external fun nativeResampleToRequiredRate(
            input: ShortArray,
            offset: Int,
            count: Int,
            inputSampleRateHz: Int,
        ): ShortArray?

        /** Convert int16 PCM to float in roughly [-1, 1], pad/truncate to [SIG_SAMPLES]. */
        private fun pcmToModelWindow(pcm: ShortArray): FloatArray {
            val window = FloatArray(CHUNK_SIZE_AT_REQUIRED_RATE)
            val n = min(pcm.size, CHUNK_SIZE_AT_REQUIRED_RATE)
            for (i in 0 until n) {
                window[i] = pcm[i] / 32768f
            }
            return window
        }
    }

    private val appContext = context.applicationContext
    /** Unlimited so viewer mode can queue an entire page; live drops via [pendingCount]. */
    private val rawQueue = Channel<ChunkSubmission>(capacity = Channel.UNLIMITED)
    /** Resampled windows waiting for the single infer thread. */
    private val inferQueue = Channel<ResampledJob>(capacity = Channel.UNLIMITED)
    private val pendingCount = AtomicInteger(0)
    private val chunkIdSeq = AtomicInteger(0)
    /** Bumped by [clearQueue] so in-flight resample results are dropped. */
    private val epoch = AtomicLong(0)

    @Volatile
    private var overflowPolicy: MlOverflowPolicy = MlOverflowPolicy.DropIfFull

    /** Species-name language index from settings; out-of-range falls back to 0. */
    @Volatile
    private var labelLanguageIndex: Int = BattyBirdNET.DEFAULT_LANGUAGE_INDEX

    /**
     * Latin label keys the user chose to ignore ([Settings.bbnSuppresions] where
     * value is true). Matched against [BattyBirdNET.labelKeys].
     */
    @Volatile
    private var suppressedLabelKeys: Set<String> = emptySet()

    private var model: BattyBirdNET? = null

    /** Switch live drop-vs-viewer queue-all behaviour. Safe to call from any thread. */
    fun setOverflowPolicy(policy: MlOverflowPolicy) {
        overflowPolicy = policy
    }

    /** Update display-name language index for subsequent detections. Safe from any thread. */
    fun setLabelLanguage(languageIndex: Int) {
        labelLanguageIndex = languageIndex
    }

    /**
     * Update user suppressions for subsequent detections. Keys with value `true`
     * are ignored (in addition to labels JSON `discard`). Safe from any thread.
     */
    fun setBbnSuppressions(suppressions: Map<String, Boolean>) {
        suppressedLabelKeys = suppressions.filterValues { it }.keys
    }

    /**
     * Discard queued raw and resampled work. In-flight resample/infer may still
     * finish; stale resampled jobs are ignored via [epoch].
     */
    fun clearQueue() {
        val newEpoch = epoch.incrementAndGet()
        var dropped = 0
        while (rawQueue.tryReceive().isSuccess) {
            dropped++
            pendingCount.decrementAndGet()
        }
        while (inferQueue.tryReceive().isSuccess) {
            dropped++
        }
        pendingCount.set(0)
        if (dropped > 0) {
            Timber.i("MlProcessor: cleared $dropped queued job(s) (epoch=$newEpoch)")
        }
    }

    /**
     * Offer a chunk for background processing without blocking.
     *
     * On success, ownership of [buffer] transfers to this processor (or of a
     * copied slice when [offset]/[count] is not the whole array). On failure
     * (live depth limit, or closed), the caller retains ownership and may discard it.
     *
     * @return true if the chunk was queued
     */
    fun tryEnqueue(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        sampleRateHz: Int,
        observedAtEpochSec: Double,
    ): Boolean {
        require(offset >= 0) { "offset must be >= 0" }
        require(count >= 0) { "count must be >= 0" }
        require(offset + count <= buffer.size) { "offset + count exceeds buffer size" }
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        if (count == 0) return true

        val owned =
            if (offset == 0 && count == buffer.size) buffer
            else buffer.copyOfRange(offset, offset + count)

        val submission = ChunkSubmission(
            buffer = owned,
            offset = 0,
            count = owned.size,
            sampleRateHz = sampleRateHz,
            observedAtEpochSec = observedAtEpochSec,
        )

        if (overflowPolicy == MlOverflowPolicy.DropIfFull) {
            while (true) {
                val pending = pendingCount.get()
                if (pending >= MAX_LIVE_QUEUED_CHUNKS) {
                    Timber.w(
                        "MlProcessor live queue depth $pending: chunk dropped (${owned.size} samples)"
                    )
                    return false
                }
                if (pendingCount.compareAndSet(pending, pending + 1)) break
            }
        } else {
            pendingCount.incrementAndGet()
        }

        return try {
            val result = rawQueue.trySend(submission)
            if (!result.isSuccess) {
                pendingCount.decrementAndGet()
                Timber.w("MlProcessor queue closed: chunk not accepted (${owned.size} samples)")
            }
            result.isSuccess
        } catch (_: ClosedSendChannelException) {
            pendingCount.decrementAndGet()
            false
        }
    }

    /**
     * Run resample workers and a single infer worker until [close].
     *
     * Resample is the CPU heavy task so is distributed across a thread pool.
     * ML inference is not so heavy, and a single Interpreter instance only supports one
     * thread. Interpreters are also memory intensive. So, there is only one Interpreter thread.
     *
     * @param resampleDispatcher multi-thread dispatcher for r8brain
     * @param inferDispatcher single-thread dispatcher for LiteRT
     * @param resampleParallelism number of concurrent resample coroutines
     */
    suspend fun run(
        resampleDispatcher: CoroutineDispatcher,
        inferDispatcher: CoroutineDispatcher,
        resampleParallelism: Int,
        onResult: (MlResult) -> Unit,
    ) {
        require(resampleParallelism >= 1) { "resampleParallelism must be >= 1" }
        try {
            coroutineScope {
                val resampleJobs = List(resampleParallelism) { index ->
                    // Multiple concurrent resample jobs:
                    launch(resampleDispatcher + CoroutineName("MlResample-$index")) {
                        resampleLoop()
                    }
                }
                val inferJob = launch(inferDispatcher + CoroutineName("MlInfer")) {
                    // A single inference job to enforce single thread:
                    inferLoop(onResult)
                }
                resampleJobs.joinAll()
                inferQueue.close()
                inferJob.join()
            }
        } catch (_: CancellationException) {
            // Normal on shutdown.
        } finally {
            model?.close()
            model = null
        }
    }

    /** Stop accepting chunks; resample workers exit, then infer drains and exits. */
    fun close() {
        rawQueue.close()
    }

    private suspend fun resampleLoop() {
        try {
            for (submission in rawQueue) {
                pendingCount.decrementAndGet()
                val jobEpoch = epoch.get()
                val chunkId = chunkIdSeq.incrementAndGet()
                val window = resampleToWindow(submission, chunkId) ?: continue
                if (jobEpoch != epoch.get()) continue
                try {
                    inferQueue.send(
                        ResampledJob(window, chunkId, jobEpoch, submission.observedAtEpochSec)
                    )
                } catch (_: ClosedSendChannelException) {
                    break
                }
            }
        } catch (_: CancellationException) {
            // Normal on shutdown.
        }
    }

    private suspend fun inferLoop(onResult: (MlResult) -> Unit) {
        try {
            for (job in inferQueue) {
                if (job.epoch != epoch.get()) continue
                onResult(inferWindow(job))
            }
        } catch (_: CancellationException) {
            // Normal on shutdown.
        }
    }

    private fun ensureModel(): BattyBirdNET? {
        model?.let { return it }
        return try {
            BattyBirdNET(appContext.assets).also { model = it }
        } catch (e: Exception) {
            Timber.e(e, "MlProcessor: failed to load BattyBirdNET")
            null
        }
    }

    private fun resampleToWindow(submission: ChunkSubmission, chunkId: Int): FloatArray? {
        val resampled = nativeResampleToRequiredRate(
            submission.buffer,
            submission.offset,
            submission.count,
            submission.sampleRateHz,
        )
        if (resampled == null) {
            Timber.e("MlProcessor: resample to $REQUIRED_SAMPLE_RATE_HZ Hz failed")
            return null
        }

        if (resampled.size != CHUNK_SIZE_AT_REQUIRED_RATE) {
            Timber.w(
                "MlProcessor: chunk #$chunkId resampled length ${resampled.size} " +
                    "(expected $CHUNK_SIZE_AT_REQUIRED_RATE); pad/truncate"
            )
        }
        return pcmToModelWindow(resampled)
    }

    private fun inferWindow(job: ResampledJob): MlResult {
        val bbn = ensureModel() ?: return MlResult(observedAtEpochSec = job.observedAtEpochSec)

        return try {
            val scores = bbn.predict(job.window)
            val displayLabels = bbn.labelsFor(labelLanguageIndex)
            val detections = ArrayList<MlDetection>()
            for (i in scores.indices) {
                val score = scores[i]
                if (bbn.labelDiscard[i] ||
                    bbn.labelKeys[i] in suppressedLabelKeys ||
                    score <= MIN_CONFIDENCE
                ) {
                    continue
                }
                detections.add(
                    MlDetection(
                        label = displayLabels[i],
                        confidence = score,
                    )
                )
            }
            detections.sortByDescending { it.confidence }
            if (detections.isEmpty()) {
                Timber.i("MlProcessor: chunk #${job.chunkId} detections: (none)")
            } else {
                val listed = detections.joinToString { d ->
                    "${d.label}=${"%.0f".format(d.confidence * 100)}%"
                }
                Timber.i("MlProcessor: chunk #${job.chunkId} detections: $listed")
            }
            MlResult(
                detections = detections,
                observedAtEpochSec = job.observedAtEpochSec,
            )
        } catch (e: Exception) {
            Timber.e(e, "MlProcessor: inference failed on chunk #${job.chunkId}")
            MlResult(observedAtEpochSec = job.observedAtEpochSec)
        }
    }
}
