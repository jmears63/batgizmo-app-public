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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Outcome of ML analysis for one analysed window.
 *
 * [observedAtEpochSec] is the Unix-epoch time (seconds) of the start of the
 * analysed window. [completedAtEpochSec] is when [MlProcessor] finished this
 * window (used for live UI age styling).
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

    /** Hop between window starts in samples at the model rate. */
    fun hopSamples(): Int {
        val overlap = (windowSamples * overlapFraction).toInt()
        return (windowSamples - overlap).coerceAtLeast(1)
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
 * Thin client for ML analysis of raw audio via [MlProcessor].
 *
 * Call [reset] with the sample rate before any [submit]. [submit] copies PCM
 * into the processor; overlapping windows and resampling happen on the
 * processor's worker threads. Pass [isLast] when the stream ends so a partial
 * final window is zero-padded and processed.
 *
 * @param onResult invoked when analysis of a window completes
 */
class MlClient(
    context: Context,
    val modelId: String,
    descriptor: MlModelDescriptor,
    onResult: (MlResult) -> Unit,
) {
    private var windowing: MlWindowing = MlWindowing.from(descriptor)

    /** Sample rate set by the last [reset]; 0 until [reset] has been called. */
    private var sampleRateHz: Int = 0

    private val processor = MlProcessor(context.applicationContext, modelId).also {
        it.setWindowing(windowing)
        it.start(onResult)
    }

    /**
     * True while the processor has outstanding work (queued or in-flight).
     * Combined with [isBuffering] for the Auto Id sparkle.
     */
    val isBusy = processor.isBusy

    /**
     * True while raw PCM is queued or a partial model-rate window is held.
     * Infer-only work is [isBusy], not buffering.
     */
    val isBuffering: StateFlow<Boolean> = processor.isBuffering

    private val mutableWillAcceptAudioFlow = MutableStateFlow(false)
    /**
     * True when [reset] has set a sample rate at or above [MlWindowing.minSampleRateHz]
     * so [submit] will ingest rather than drop audio.
     */
    val willAcceptAudioFlow: StateFlow<Boolean> = mutableWillAcceptAudioFlow.asStateFlow()

    /**
     * Whether the current [sampleRateHz] is high enough for this model to use
     * submitted audio. False until [reset], or when below [MlWindowing.minSampleRateHz].
     *
     * [MlProcessor] also enforces [MlWindowing.minSampleRateHz] (defense in depth
     * for model switches and flush-only [isLast] submits).
     */
    fun willAcceptAudio(): Boolean =
        sampleRateHz > 0 && sampleRateHz >= windowing.minSampleRateHz

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

    /** Discard queued work and reset the resample stream. */
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
     * Set the sample rate and clear stream/queue state.
     * Must be called before [submit], and again whenever the rate changes.
     */
    fun reset(sampleRateHz: Int) {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        this.sampleRateHz = sampleRateHz
        processor.reset(sampleRateHz)
        publishWillAcceptAudio()
    }

    /**
     * Submit mono 16-bit PCM samples for analysis.
     *
     * Requires a prior [reset]. The caller's buffer is not retained after this
     * call returns; the caller may reuse it. [offset] and [count] select the
     * slice within [buffer].
     *
     * [observedAtEpochSec] is the Unix-epoch time (seconds) of the first submitted sample.
     *
     * When [isLast] is true, the processor flushes the resampler and zero-pads
     * any partial final window.
     */
    fun submit(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        observedAtEpochSec: Double,
        isLast: Boolean = false,
    ) {
        check(sampleRateHz > 0) { "reset(sampleRateHz) must be called before submit" }
        require(offset >= 0) { "offset must be >= 0" }
        require(count >= 0) { "count must be >= 0" }
        require(offset + count <= buffer.size) {
            "offset + count exceeds buffer size"
        }

        if (!willAcceptAudio()) {
            if (isLast) {
                // Still flush any prior stream state held in the processor.
                processor.tryEnqueue(
                    ShortArray(0),
                    0,
                    0,
                    sampleRateHz,
                    observedAtEpochSec,
                    isLast = true,
                )
            }
            return
        }

        // Always copy so live USB / shared page buffers can be reused immediately.
        val owned =
            if (count == 0) ShortArray(0)
            else buffer.copyOfRange(offset, offset + count)
        processor.tryEnqueue(
            owned,
            0,
            owned.size,
            sampleRateHz,
            observedAtEpochSec,
            isLast = isLast,
        )
    }

    /** Release background resources. */
    fun shutdown() {
        processor.close()
    }

    private fun publishWillAcceptAudio() {
        mutableWillAcceptAudioFlow.value = willAcceptAudio()
    }
}
