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
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * single infer worker owns the active [MlModelBase] (LiteRT is not thread-safe).
 *
 * Under [MlOverflowPolicy.DropIfFull], [tryEnqueue] drops when [pendingCount]
 * already reaches [MAX_LIVE_QUEUED_CHUNKS].
 */
class MlProcessor(
    context: Context,
    private val modelId: String,
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
        val chunkId: Int,
        /** [SystemClock.elapsedRealtime] when the client queued this chunk. */
        val queuedAtElapsedRealtimeMs: Long,
    )

    private data class ResampledJob(
        val window: FloatArray,
        val chunkId: Int,
        val epoch: Long,
        val observedAtEpochSec: Double,
        val queuedAtElapsedRealtimeMs: Long,
        /** Wall time spent waiting in the raw chunk queue before resample started. */
        val queueWaitMs: Long,
        /** Wall time spent resampling this chunk. */
        val resampleMs: Long,
        /** [SystemClock.elapsedRealtime] when this job was offered to the infer queue. */
        val inferQueuedAtElapsedRealtimeMs: Long,
        /** Infer-queue depth when this job was added (includes this job). */
        val inferQueueLengthWhenQueued: Int,
    )

    companion object {
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

        init {
            System.loadLibrary("batgizmo-native")
        }

        /**
         * Resample mono 16-bit PCM to [outputSampleRateHz] via r8brain (native).
         * Returns a newly allocated buffer, or null on failure.
         * Safe to call from multiple threads (oneshot resampler per call).
         */
        @JvmStatic
        private external fun nativeResample(
            input: ShortArray,
            offset: Int,
            count: Int,
            inputSampleRateHz: Int,
            outputSampleRateHz: Int,
        ): ShortArray?
    }

    private val appContext = context.applicationContext
    /** Unlimited so viewer mode can queue an entire page; live drops via [pendingCount]. */
    private val rawQueue = Channel<ChunkSubmission>(capacity = Channel.UNLIMITED)
    /** Resampled windows waiting for the single infer thread. */
    private val inferQueue = Channel<ResampledJob>(capacity = Channel.UNLIMITED)
    private val pendingCount = AtomicInteger(0)
    /** Jobs accepted into [inferQueue] but not yet taken by [inferLoop]. */
    private val inferPendingCount = AtomicInteger(0)
    private val chunkIdSeq = AtomicInteger(0)
    /** Bumped by [clearQueue] so in-flight resample results are dropped. */
    private val epoch = AtomicLong(0)

    /**
     * Chunks accepted for processing that have not yet finished (infer done,
     * or abandoned after resample/epoch drop / queue drain).
     */
    private val activeWorkCount = AtomicInteger(0)
    private val mutableBusyFlow = MutableStateFlow(false)
    /** True while queued or in-flight Auto Id work remains. */
    val isBusy: StateFlow<Boolean> = mutableBusyFlow.asStateFlow()

    @Volatile
    private var overflowPolicy: MlOverflowPolicy = MlOverflowPolicy.DropIfFull

    /** Species-name language index from settings; out-of-range falls back to 0. */
    @Volatile
    private var labelLanguageIndex: Int = 0

    /**
     * Label keys the user chose to ignore (settings suppressions where value is
     * true). Matched against [MlModelBase.labelKeys].
     */
    @Volatile
    private var suppressedLabelKeys: Set<String> = emptySet()

    /**
     * Optional BirdNET species-range query captured for this processor session.
     * Applied once when a [BirdNetModel] is first opened.
     */
    @Volatile
    private var geoSnapshot: BirdNetModel.GeoSnapshot? = null

    @Volatile
    private var windowing: MlWindowing? = null

    private var model: MlModelBase? = null

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
    fun setSuppressions(suppressions: Map<String, Boolean>) {
        suppressedLabelKeys = suppressions.filterValues { it }.keys
    }

    /**
     * Set the BirdNET geo filter query. First non-null snapshot wins so the
     * species-range mask is computed once per session and does not track later
     * GPS updates; use [clearGeoSnapshot] before applying a new file/live
     * location. Applied when the model is first loaded (or immediately if
     * already loaded and not yet resolved).
     */
    fun setGeoSnapshot(snapshot: BirdNetModel.GeoSnapshot?) {
        if (snapshot == null) return
        if (geoSnapshot != null) return
        geoSnapshot = snapshot
        val opened = model
        if (opened is BirdNetModel) {
            opened.setGeoSnapshot(snapshot)
        }
    }

    /**
     * Drop any stored geo snapshot and reset a loaded [BirdNetModel] geo filter
     * so a new location can be applied (viewer file change / live restart).
     */
    fun clearGeoSnapshot() {
        geoSnapshot = null
        val opened = model
        if (opened is BirdNetModel) {
            opened.resetGeoFilter()
        }
    }

    /** Apply windowing from the selected model descriptor (before [run]). */
    fun setWindowing(windowing: MlWindowing) {
        this.windowing = windowing
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
            markWorkFinished()
        }
        while (inferQueue.tryReceive().isSuccess) {
            dropped++
            inferPendingCount.decrementAndGet()
            markWorkFinished()
        }
        pendingCount.set(0)
        inferPendingCount.set(0)
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

        val chunkId = chunkIdSeq.incrementAndGet()
        val queuedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val submission = ChunkSubmission(
            buffer = owned,
            offset = 0,
            count = owned.size,
            sampleRateHz = sampleRateHz,
            observedAtEpochSec = observedAtEpochSec,
            chunkId = chunkId,
            queuedAtElapsedRealtimeMs = queuedAtElapsedRealtimeMs,
        )

        if (overflowPolicy == MlOverflowPolicy.DropIfFull) {
            while (true) {
                val pending = pendingCount.get()
                if (pending >= MAX_LIVE_QUEUED_CHUNKS) {
                    Timber.w(
                        "MlProcessor live queue depth $pending: chunk #$chunkId dropped (${owned.size} samples)"
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
                Timber.w(
                    "MlProcessor queue closed: chunk #$chunkId not accepted (${owned.size} samples)"
                )
            } else {
                markWorkStarted()
                Timber.i(
                    "MlProcessor: queued chunk #$chunkId (${owned.size} samples), " +
                        "queue length ${pendingCount.get()}"
                )
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
                    launch(resampleDispatcher + CoroutineName("MlResample-$index")) {
                        resampleLoop()
                    }
                }
                val inferJob = launch(inferDispatcher + CoroutineName("MlInfer")) {
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
            activeWorkCount.set(0)
            mutableBusyFlow.value = false
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
                val dequeuedAtMs = SystemClock.elapsedRealtime()
                val queueWaitMs = dequeuedAtMs - submission.queuedAtElapsedRealtimeMs
                val jobEpoch = epoch.get()
                val chunkId = submission.chunkId
                val window = resampleToWindow(submission, chunkId)
                val resampleMs = SystemClock.elapsedRealtime() - dequeuedAtMs
                if (window == null) {
                    markWorkFinished()
                    continue
                }
                if (jobEpoch != epoch.get()) {
                    markWorkFinished()
                    continue
                }
                try {
                    val inferQueuedAtMs = SystemClock.elapsedRealtime()
                    val inferQueueLength = inferPendingCount.incrementAndGet()
                    inferQueue.send(
                        ResampledJob(
                            window,
                            chunkId,
                            jobEpoch,
                            submission.observedAtEpochSec,
                            submission.queuedAtElapsedRealtimeMs,
                            queueWaitMs,
                            resampleMs,
                            inferQueuedAtMs,
                            inferQueueLength,
                        )
                    )
                } catch (_: ClosedSendChannelException) {
                    inferPendingCount.decrementAndGet()
                    markWorkFinished()
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
                inferPendingCount.decrementAndGet()
                if (job.epoch != epoch.get()) {
                    markWorkFinished()
                    continue
                }
                try {
                    onResult(inferWindow(job))
                } finally {
                    markWorkFinished()
                }
            }
        } catch (_: CancellationException) {
            // Normal on shutdown.
        }
    }

    private fun markWorkStarted() {
        activeWorkCount.incrementAndGet()
        mutableBusyFlow.value = true
    }

    private fun markWorkFinished() {
        val remaining = activeWorkCount.decrementAndGet()
        if (remaining <= 0) {
            activeWorkCount.set(0)
            mutableBusyFlow.value = false
        }
    }

    private fun ensureModel(): MlModelBase? {
        model?.let { return it }
        return try {
            MlCatalog.ensureInitialized(appContext)
            MlCatalog.openModel(appContext.assets, modelId).also { opened ->
                model = opened
                windowing = MlWindowing.from(opened.descriptor)
                if (opened is BirdNetModel) {
                    geoSnapshot?.let { opened.setGeoSnapshot(it) }
                }
                Timber.i(
                    "MlProcessor: loaded Auto Id model ${opened.id} " +
                        "(${opened.sampleRateHz} Hz, ${opened.windowSamples} samples)"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "MlProcessor: failed to load Auto Id model $modelId")
            null
        }
    }

    private fun activeWindowing(): MlWindowing? =
        windowing ?: model?.descriptor?.let { MlWindowing.from(it) }

    private fun resampleToWindow(submission: ChunkSubmission, chunkId: Int): FloatArray? {
        val win = activeWindowing()
        if (win == null) {
            // Force model load on infer thread only; resample still needs rates.
            // Use catalog metadata without opening TFLite.
            val descriptor = try {
                MlCatalog.ensureInitialized(appContext)
                MlCatalog.resolveDescriptor(appContext.assets, modelId)
            } catch (e: Exception) {
                Timber.e(e, "MlProcessor: no descriptor for $modelId")
                return null
            }
            windowing = MlWindowing.from(descriptor)
        }
        val w = activeWindowing() ?: return null

        val resampled = nativeResample(
            submission.buffer,
            submission.offset,
            submission.count,
            submission.sampleRateHz,
            w.modelSampleRateHz,
        )
        if (resampled == null) {
            Timber.e("MlProcessor: resample to ${w.modelSampleRateHz} Hz failed")
            return null
        }

        if (resampled.size != w.windowSamples) {
            Timber.w(
                "MlProcessor: chunk #$chunkId resampled length ${resampled.size} " +
                    "(expected ${w.windowSamples}); pad/truncate"
            )
        }
        return pcmToModelWindow(resampled, w.windowSamples)
    }

    private fun pcmToModelWindow(pcm: ShortArray, windowSamples: Int): FloatArray {
        val window = FloatArray(windowSamples)
        val n = min(pcm.size, windowSamples)
        for (i in 0 until n) {
            window[i] = pcm[i] / 32768f
        }
        return window
    }

    private fun inferWindow(job: ResampledJob): MlResult {
        fun completedResult(
            detections: List<MlDetection> = emptyList(),
        ): MlResult = MlResult(
            detections = detections,
            observedAtEpochSec = job.observedAtEpochSec,
            completedAtEpochSec = System.currentTimeMillis() / 1000.0,
        )

        val autoId = ensureModel() ?: return completedResult()
        val detectionThreshold = autoId.detectionThreshold

        return try {
            val inferStartedAtMs = SystemClock.elapsedRealtime()
            val scores = autoId.predict(job.window)
            // Hoist once — previously each loop iteration rebuilt these lists
            // via interface getters (O(n²) on BirdNET's ~6.5k classes).
            val labelDiscard = autoId.labelDiscard
            val labelKeys = autoId.labelKeys
            // Defer labelsFor() until a score passes the filters.
            var displayLabels: List<String>? = null
            val detections = ArrayList<MlDetection>()
            for (i in scores.indices) {
                val score = scores[i]
                if (score <= detectionThreshold ||
                    labelDiscard[i] ||
                    labelKeys[i] in suppressedLabelKeys
                ) {
                    continue
                }
                val labels = displayLabels
                    ?: autoId.labelsFor(labelLanguageIndex).also { displayLabels = it }
                detections.add(
                    MlDetection(
                        label = labels[i],
                        confidence = score,
                    )
                )
            }
            detections.sortByDescending { it.confidence }
            val inferMs = SystemClock.elapsedRealtime() - inferStartedAtMs
            val inferQueueWaitMs = inferStartedAtMs - job.inferQueuedAtElapsedRealtimeMs
            val totalMs = SystemClock.elapsedRealtime() - job.queuedAtElapsedRealtimeMs
            val timing =
                "queue ${job.queueWaitMs} ms, resample ${job.resampleMs} ms, " +
                    "infer-queue ${job.inferQueueLengthWhenQueued} deep / $inferQueueWaitMs ms, " +
                    "infer $inferMs ms, total $totalMs ms"
            if (detections.isEmpty()) {
                Timber.i(
                    "MlProcessor: chunk #${job.chunkId} detections: (none) ($timing)"
                )
            } else {
                val listed = detections.joinToString { d ->
                    "${d.label}=${"%.0f".format(d.confidence * 100)}%"
                }
                Timber.i(
                    "MlProcessor: chunk #${job.chunkId} detections: $listed ($timing)"
                )
            }
            completedResult(detections)
        } catch (e: Exception) {
            val totalMs = SystemClock.elapsedRealtime() - job.queuedAtElapsedRealtimeMs
            Timber.e(
                e,
                "MlProcessor: inference failed on chunk #${job.chunkId} " +
                    "(queue ${job.queueWaitMs} ms, resample ${job.resampleMs} ms, " +
                    "total $totalMs ms)"
            )
            completedResult()
        }
    }
}
