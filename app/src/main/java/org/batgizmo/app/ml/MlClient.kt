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
import android.os.Process
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Outcome of ML analysis for one submitted audio buffer.
 *
 * [observedAtEpochSec] is the Unix-epoch time (seconds) of the start of the
 * analysed window. [completedAtEpochSec] is when [MlProcessor] finished this
 * chunk (used for live UI age styling).
 */
data class MlResult(
    val detections: List<MlDetection> = emptyList(),
    val observedAtEpochSec: Double = 0.0,
    val completedAtEpochSec: Double = 0.0,
)

data class MlDetection(
    val label: String,
    val confidence: Float,
)

/**
 * One row in the Auto Id summary overlay, including when processing completed
 * (Unix epoch seconds) for age-based styling.
 */
data class MlSummaryEntry(
    val label: String,
    val confidence: Float,
    val lastSeenAtEpochSec: Double,
)

/**
 * Windowing parameters taken from the active [MlModelDescriptor].
 */
data class MlWindowing(
    val modelSampleRateHz: Int,
    val windowSamples: Int,
    val overlapFraction: Float,
    val minSampleRateHz: Int,
) {
    init {
        require(modelSampleRateHz > 0) { "modelSampleRateHz must be > 0" }
        require(windowSamples > 0) { "windowSamples must be > 0" }
        require(overlapFraction in 0f..0.95f) { "overlapFraction out of range" }
        require(minSampleRateHz > 0) { "minSampleRateHz must be > 0" }
    }

    /** Hop between chunk starts in samples at the audio (input) rate after scaling. */
    fun hopSizeForChunk(chunkSize: Int): Int {
        val overlap = (chunkSize * overlapFraction).toInt()
        return (chunkSize - overlap).coerceAtLeast(1)
    }

    companion object {
        fun from(descriptor: MlModelDescriptor) = MlWindowing(
            modelSampleRateHz = descriptor.sampleRateHz,
            windowSamples = descriptor.windowSamples,
            overlapFraction = descriptor.overlapFraction,
            minSampleRateHz = descriptor.minSampleRateHz,
        )
    }
}

/**
 * Client for ML analysis of raw audio via [MlProcessor].
 *
 * Call [reset] with the sample rate before any [submit]. Chunk length is scaled
 * so window duration matches the active model; overlap comes from [windowing].
 * Pass [submit]'s [isLast] when the stream ends so a partial final chunk is
 * zero-padded (after discarding the newest overlap) and processed.
 *
 * Chunk ownership transfers into the processor on enqueue so the caller is not
 * blocked on inference. Resampling runs on a small thread pool; LiteRT stays on
 * one thread.
 *
 * @param onResult invoked when analysis of a submitted buffer completes
 */
class MlClient(
    context: Context,
    val modelId: String,
    descriptor: MlModelDescriptor,
    private val onResult: (MlResult) -> Unit,
) {
    private class Chunk(
        val buffer: ShortArray,
        var filled: Int = 0,
        /** Unix-epoch seconds at the first sample written into this chunk. */
        var startEpochSec: Double = 0.0,
    )

    private var windowing: MlWindowing = MlWindowing.from(descriptor)

    /** Sample rate set by the last [reset]; 0 until [reset] has been called. */
    private var sampleRateHz: Int = 0

    /**
     * Samples per chunk at the current [sampleRateHz]; 0 until [reset].
     */
    var chunkSize: Int = 0
        private set

    /** Hop between chunk starts; 0 until [reset]. */
    private var hopSize: Int = 0

    private val activeChunks = ArrayDeque<Chunk>()

    private val processor = MlProcessor(context.applicationContext, modelId).also {
        it.setWindowing(windowing)
    }

    /** True while the processor has queued or in-flight Auto Id work. */
    val isBusy = processor.isBusy

    private val mutableBufferingFlow = MutableStateFlow(false)
    /**
     * True while [submit] is ingesting samples, or while incomplete chunk(s) are
     * held waiting to reach window size for [MlProcessor].
     */
    val isBuffering: StateFlow<Boolean> = mutableBufferingFlow.asStateFlow()

    private val mutableWillAcceptAudioFlow = MutableStateFlow(false)
    /**
     * True when [reset] has set a sample rate at or above [MlWindowing.minSampleRateHz]
     * so [submit] will ingest rather than drop audio.
     */
    val willAcceptAudioFlow: StateFlow<Boolean> = mutableWillAcceptAudioFlow.asStateFlow()

    /**
     * Whether the current [sampleRateHz] is high enough for this model to use
     * submitted audio. False until [reset], or when below [MlWindowing.minSampleRateHz].
     */
    fun willAcceptAudio(): Boolean =
        sampleRateHz > 0 && sampleRateHz >= windowing.minSampleRateHz

    private val resampleParallelism = MlProcessor.defaultResampleParallelism()

    private val resampleThreadIndex = AtomicInteger(0)

    private val resampleDispatcher: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(resampleParallelism) { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "MlResample-${resampleThreadIndex.getAndIncrement()}")
        }.asCoroutineDispatcher()

    private val inferDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "MlInfer")
        }.asCoroutineDispatcher()

    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("MlClient")
    )
    private val runJob: Job = scope.launch {
        processor.run(
            resampleDispatcher = resampleDispatcher,
            inferDispatcher = inferDispatcher,
            resampleParallelism = resampleParallelism,
        ) { result ->
            onResult(result)
        }
    }

    /** Live vs viewer enqueue policy; forwarded to [MlProcessor]. */
    fun setOverflowPolicy(policy: MlOverflowPolicy) {
        processor.setOverflowPolicy(policy)
    }

    /** Species-name language index for the ML panel; forwarded to [MlProcessor]. */
    fun setLabelLanguage(languageIndex: Int) {
        processor.setLabelLanguage(languageIndex)
    }

    /** User suppressions (label key → ignore); forwarded to [MlProcessor]. */
    fun setSuppressions(suppressions: Map<String, Boolean>) {
        processor.setSuppressions(suppressions)
    }

    /**
     * BirdNET geo location/week (first snapshot wins; see [MlProcessor.setGeoSnapshot]).
     */
    fun setGeoSnapshot(snapshot: BirdNetModel.GeoSnapshot?) {
        processor.setGeoSnapshot(snapshot)
    }

    /** Clear geo snapshot/mask so a new location can be applied. */
    fun clearGeoSnapshot() {
        processor.clearGeoSnapshot()
    }

    /** Discard queued chunks (not the in-flight one). */
    fun clearQueue() {
        processor.clearQueue()
    }

    /**
     * Replace windowing parameters (e.g. after switching Auto Id models).
     * Caller should [reset] afterward.
     */
    fun updateWindowing(windowing: MlWindowing) {
        this.windowing = windowing
        processor.setWindowing(windowing)
        publishWillAcceptAudio()
    }

    /**
     * Set the sample rate, recompute [chunkSize], and clear accumulated audio state.
     * Must be called before [submit], and again whenever the rate changes.
     */
    fun reset(sampleRateHz: Int) {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        processor.clearQueue()
        this.sampleRateHz = sampleRateHz
        chunkSize = chunkSizeForSampleRate(sampleRateHz, windowing)
        hopSize = windowing.hopSizeForChunk(chunkSize)
        activeChunks.clear()
        activeChunks.addLast(Chunk(ShortArray(chunkSize)))
        publishBuffering()
        publishWillAcceptAudio()
    }

    /**
     * Submit mono 16-bit PCM samples for analysis.
     *
     * Requires a prior [reset]. The caller's buffer is not retained after this
     * call returns; the caller may reuse it. [offset] and [count] select the
     * slice within [buffer].
     *
     * [observedAtEpochSec] is the Unix-epoch time (seconds) of [buffer]\[[offset]\];
     * later samples are stamped by advancing at [sampleRateHz].
     *
     * Samples are written into all in-flight overlapping chunks. After the
     * first [hopSize] samples, two chunks are filled in parallel. When any
     * chunk reaches [chunkSize], it is enqueued for analysis.
     *
     * When [isLast] is true, after absorbing [count] samples the newest overlapping
     * chunk is discarded and the currently fullest remaining chunk is zero-padded
     * to [chunkSize] and enqueued.
     */
    fun submit(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        observedAtEpochSec: Double,
        isLast: Boolean = false,
    ) {
        check(sampleRateHz > 0 && chunkSize > 0 && hopSize > 0) {
            "reset(sampleRateHz) must be called before submit"
        }
        require(offset >= 0) { "offset must be >= 0" }
        require(count >= 0) { "count must be >= 0" }
        require(offset + count <= buffer.size) {
            "offset + count exceeds buffer size"
        }

        // Hold true for the whole ingest so viewer page submits keep the sparkle
        // pulsing until all samples have been accepted into this client.
        mutableBufferingFlow.value = true
        try {
            if (!willAcceptAudio()) {
                // Below the model's useful rate; drop this batch and any partial chunks.
                activeChunks.clear()
                activeChunks.addLast(Chunk(ShortArray(chunkSize)))
                return
            }

            val size = chunkSize
            val hop = hopSize
            val secPerSample = 1.0 / sampleRateHz
            var srcOffset = offset
            var remaining = count

            while (remaining > 0) {
                if (activeChunks.isEmpty()) {
                    activeChunks.addLast(Chunk(ShortArray(size)))
                }

                // Largest run we can write without missing a hop boundary or overflow.
                var toCopy = remaining
                for (chunk in activeChunks) {
                    toCopy = minOf(toCopy, size - chunk.filled)
                }
                val last = activeChunks.last()
                if (last.filled < hop) {
                    toCopy = minOf(toCopy, hop - last.filled)
                }
                check(toCopy > 0) { "internal error: no progress filling overlapping chunks" }

                val writeStartEpoch =
                    observedAtEpochSec + (srcOffset - offset) * secPerSample
                for (chunk in activeChunks) {
                    if (chunk.filled == 0) {
                        chunk.startEpochSec = writeStartEpoch
                    }
                    System.arraycopy(buffer, srcOffset, chunk.buffer, chunk.filled, toCopy)
                    chunk.filled += toCopy
                }
                srcOffset += toCopy
                remaining -= toCopy

                // Drain completed chunks, then open the next overlap when the newest
                // reaches hopSize filled (may happen immediately after a completion).
                while (activeChunks.isNotEmpty()) {
                    when {
                        activeChunks.first().filled == size -> {
                            val completed = activeChunks.removeFirst()
                            enqueueChunk(
                                completed.buffer,
                                0,
                                size,
                                completed.startEpochSec,
                            )
                        }
                        activeChunks.last().filled == hop -> {
                            activeChunks.addLast(Chunk(ShortArray(size)))
                        }
                        else -> break
                    }
                }
            }

            if (isLast) {
                flushFinalChunk()
            }
        } finally {
            publishBuffering()
        }
    }

    /**
     * End-of-stream: drop the newest overlapping chunk, zero-pad the fullest
     * remaining chunk to [chunkSize], and enqueue it. Clears in-flight state.
     */
    private fun flushFinalChunk() {
        if (activeChunks.isEmpty()) return

        // Newest overlap is incomplete relative to the older in-flight chunk.
        if (activeChunks.size > 1) {
            activeChunks.removeLast()
        }

        val fullest = activeChunks.maxBy { it.filled }
        activeChunks.clear()

        if (fullest.filled <= 0) return

        val size = chunkSize
        if (fullest.filled < size) {
            fullest.buffer.fill(0, fullest.filled, size)
        }
        enqueueChunk(fullest.buffer, 0, size, fullest.startEpochSec)
        publishBuffering()
    }

    private fun publishBuffering() {
        mutableBufferingFlow.value = activeChunks.any { it.filled > 0 }
    }

    private fun publishWillAcceptAudio() {
        mutableWillAcceptAudioFlow.value = willAcceptAudio()
    }

    /** Non-blocking hand-off; ownership transfers to [MlProcessor] on success. */
    private fun enqueueChunk(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        observedAtEpochSec: Double,
    ) {
        processor.tryEnqueue(buffer, offset, count, sampleRateHz, observedAtEpochSec)
    }

    /** Release background resources. */
    fun shutdown() {
        processor.close()
        runJob.cancel()
        scope.cancel()
        resampleDispatcher.close()
        inferDispatcher.close()
    }

    companion object {
        /**
         * Chunk length in samples for [audioSampleRateHz], scaled so duration matches
         * [MlWindowing.windowSamples] at [MlWindowing.modelSampleRateHz].
         */
        fun chunkSizeForSampleRate(
            audioSampleRateHz: Int,
            windowing: MlWindowing,
        ): Int {
            require(audioSampleRateHz > 0) { "audioSampleRateHz must be > 0" }
            val scaled =
                (windowing.windowSamples.toLong() * audioSampleRateHz +
                    windowing.modelSampleRateHz - 1) /
                    windowing.modelSampleRateHz
            return scaled.toInt().coerceAtLeast(1)
        }
    }
}
