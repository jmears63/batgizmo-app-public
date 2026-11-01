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

package org.batgizmo.app

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.batgizmo.app.pipeline.AbstractPipeline
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Orchestrates auto-tuned and smart heterodyne. Both smooth an observed
 * frequency and apply LO = observation − 500 Hz. Auto observes the activity
 * tracker; smart observes BatCNN FmaxE at each presence peak. Either way the
 * worker channel carries one request: the resulting reference in kHz.
 *
 * Live smart heterodyne applies each peak when inference returns. Viewer
 * playback holds peaks and applies them when the playhead reaches their time,
 * so a whole-buffer analysis does not run the LO ahead of the audio.
 *
 * This class owns session state and async updates while audio output is active.
 */
class AutoHeterodyneController(
    private val scope: CoroutineScope,
    private val pipeline: () -> AbstractPipeline?,
    private val settings: () -> Settings,
    private val isAudioOutputActive: () -> Boolean,
    private val applyHeterodyneLo: suspend (refkHz: Int) -> Unit,
) {
    companion object {
        /** Auto-het reference EWMA time constant (seconds). */
        private const val REF_TAU_S = 0.3f
        private const val OFFSET_HZ = 500f
        private const val DEFAULT_HZ = 50_000f
    }

    private val mutableRefkHz = MutableStateFlow<Int?>(null)
    /** Tracked LO in kHz for the spectrogram cursor (null when inactive). */
    val refkHzFlow: StateFlow<Int?> = mutableRefkHz.asStateFlow()

    private var smoothedHz: Float? = null
    private val activity = AutoHeterodyneActivityTracker()
    /** Last spectrogram time bucket processed by the activity tracker. */
    private var lastProcessedBucket: Int? = null
    /** Epoch seconds of the last BatCNN FmaxE applied for smart heterodyne. */
    private var lastFmaxeEpochSec: Double? = null
    /**
     * Bumped in [resetTracker] so an in-flight peak or playhead update from the
     * previous audio session cannot move the LO.
     */
    @Volatile private var trackingGeneration = 0
    /**
     * Viewer smart heterodyne: FmaxE times are seconds from [playheadOriginSample].
     * Peaks stay in [heldPeaks] until the playhead reaches them, including across
     * a loop rewind. Written under [mutex]; the audio thread reads it to decide
     * whether to enqueue.
     */
    @Volatile private var playheadGate = false
    private var playheadOriginSample = 0
    private var playheadRateHz = 0
    private var lastPlayheadSec: Double? = null
    private val playheadSample = AtomicInteger(0)
    private val playheadApplyQueued = AtomicBoolean(false)
    private val heldPeaks = ArrayList<FmaxePeak>()
    /** Viewer: skip enqueue until the playhead enters a new time bucket. */
    private var lastEnqueuedBucket: Int? = null
    private var viewerStride: Int? = null
    private var viewerTimeBucketCount: Int? = null
    /** Heterodyne reference to apply, in kHz. */
    private data class ReferenceRequest(val refkHz: Int)
    private val workChannel = Channel<ReferenceRequest>(Channel.BUFFERED)
    private var workerJob: Job? = null
    /** Serializes activity/reference updates. */
    private val mutex = Mutex()

    /** One BatCNN presence-peak FmaxE, at an absolute epoch time. */
    data class FmaxePeak(val epochSec: Double, val hz: Float)

    fun setDisplayedRefkHz(kHz: Int) {
        mutableRefkHz.value = kHz
    }

    suspend fun resetTracker() {
        mutex.withLock {
            trackingGeneration++
            clearPlayheadGateLocked()
            smoothedHz = null
            mutableRefkHz.value = null
            lastProcessedBucket = null
            lastFmaxeEpochSec = null
            lastEnqueuedBucket = null
            activity.reset()
        }
    }

    /**
     * Viewer smart heterodyne. [originSample] is the page-buffer index of the
     * first sample submitted to BatCNN. Peak epochs are seconds after that sample.
     */
    suspend fun beginPlayheadGatedPeaks(originSample: Int, sampleRateHz: Int) {
        mutex.withLock {
            playheadGate = true
            playheadOriginSample = originSample
            playheadRateHz = sampleRateHz
            lastPlayheadSec = null
            lastFmaxeEpochSec = null
            heldPeaks.clear()
            playheadSample.set(originSample)
        }
    }

    /** Drop viewer peak holding without clearing the current LO. */
    suspend fun clearPlayheadGate() {
        mutex.withLock {
            trackingGeneration++
            clearPlayheadGateLocked()
        }
    }

    /** Clear activity state after FFT/zoom changes without dropping the current LO. */
    suspend fun resetActivityState() {
        mutex.withLock {
            lastProcessedBucket = null
            lastEnqueuedBucket = null
            activity.reset()
        }
    }

    suspend fun refreshViewerCache() {
        val mapping = pipeline()?.spectrogramTimeMapping() ?: return
        viewerStride = mapping.fftStride
        viewerTimeBucketCount = mapping.timeBucketCount
    }

    fun initialRefkHz(): Int {
        val defaultLo = ((DEFAULT_HZ - OFFSET_HZ) / 1000f).roundToInt()
        val maxkHz = ((pipeline()?.sampleRateHz() ?: 384_000) / 2000) - 1
        return defaultLo.coerceAtMost(maxkHz)
    }

    fun startWorker() {
        if (workerJob?.isActive == true)
            return
        workerJob = scope.launch(
            Dispatchers.Default + CoroutineName("autoHetWorker")
        ) {
            for (request in workChannel) {
                applyHeterodyneLo(request.refkHz)
            }
        }
    }

    fun stopWorker() {
        workerJob?.cancel()
        workerJob = null
        drainChannel(workChannel)
        lastEnqueuedBucket = null
        viewerStride = null
        viewerTimeBucketCount = null
    }

    /**
     * Live path: after each spectrogram slice, update the auto-tuned LO from new time buckets.
     */
    fun onLiveSpectrumBuckets(buckets: IntRange) {
        if (!isAudioOutputActive())
            return
        val sampleRateHz = pipeline()?.sampleRateHz() ?: return
        if (!settings().isAutoTunedHeterodynePlayback(sampleRateHz))
            return
        if (buckets.isEmpty())
            return

        enqueueReference { referenceFromBuckets(buckets) }
    }

    /**
     * Viewer path: drive auto-tuned LO from the spectrogram column under the playhead.
     */
    fun updateAtRawSample(sampleIndex: Int) {
        if (!isAudioOutputActive())
            return
        val sampleRateHz = pipeline()?.sampleRateHz() ?: return
        if (!settings().isAutoTunedHeterodynePlayback(sampleRateHz))
            return

        val bucket = viewerBucketForSample(sampleIndex)
        if (bucket != null) {
            if (bucket == lastEnqueuedBucket)
                return
            lastEnqueuedBucket = bucket
        }
        enqueueReference {
            val pl = pipeline() ?: return@enqueueReference null
            val column = pl.timeBucketForRawSampleIndex(sampleIndex) ?: return@enqueueReference null
            val last = lastProcessedBucket
            val range = when {
                last != null && column < last -> column..column
                last != null && column > last -> (last + 1)..column
                else -> column..column
            }
            referenceFromBuckets(range)
        }
    }

    /**
     * Smart heterodyne: set the reference from BatCNN presence-peak FmaxE
     * values, using the same smoothing and 500 Hz offset as auto heterodyne.
     * During viewer playback the peaks are held until [onViewerPlayhead].
     */
    fun onFmaxePeaks(peaks: List<FmaxePeak>) {
        if (!isAudioOutputActive() || peaks.isEmpty())
            return
        val sampleRateHz = pipeline()?.sampleRateHz() ?: return
        if (!settings().isSmartHeterodynePlayback(sampleRateHz))
            return
        val generation = trackingGeneration
        enqueueReference {
            if (generation != trackingGeneration)
                return@enqueueReference null
            if (playheadGate) {
                heldPeaks.addAll(peaks)
                heldPeaks.sortBy { it.epochSec }
                referenceUpToPlayhead()
            } else {
                referenceFromFmaxe(peaks)
            }
        }
    }

    /**
     * Viewer playback position, as an index into the page buffer (the same
     * coordinate as audio progress). No-op unless [beginPlayheadGatedPeaks]
     * is active.
     */
    fun onViewerPlayhead(sampleIndex: Int) {
        if (sampleIndex < 0)
            return
        playheadSample.set(sampleIndex)
        if (!playheadGate)
            return
        if (!playheadApplyQueued.compareAndSet(false, true))
            return
        val generation = trackingGeneration
        enqueueReference {
            playheadApplyQueued.set(false)
            if (!playheadGate || generation != trackingGeneration)
                return@enqueueReference null
            referenceUpToPlayhead()
        }
    }

    /**
     * Derive a reference on a background coroutine, then queue that one request.
     * [derive] runs while [mutex] is held.
     */
    private fun enqueueReference(derive: suspend () -> Int?) {
        scope.launch(Dispatchers.Default) {
            mutex.withLock {
                val refkHz = derive() ?: return@withLock
                workChannel.trySend(ReferenceRequest(refkHz))
            }
        }
    }

    private fun viewerBucketForSample(sampleIndex: Int): Int? {
        val stride = viewerStride ?: return null
        val count = viewerTimeBucketCount ?: return null
        // sampleIndex is page-local (audio progress into the page buffer).
        return (sampleIndex / stride).coerceIn(0, count - 1)
    }

    private fun activitySearchBandHz(): Pair<Float, Float> =
        Settings.AUTO_HET_LO_LIMIT_MIN_KHZ * 1000f to
            Settings.AUTO_HET_LO_LIMIT_MAX_KHZ * 1000f

    private fun spanFilterBandHz(): Pair<Float, Float> {
        val (minKhz, maxKhz) = settings().normalizedAutoHeterodyneLoRange()
        return minKhz * 1000f to maxKhz * 1000f
    }

    private suspend fun referenceFromBuckets(buckets: IntRange): Int? {
        val pl = pipeline() ?: return null
        val dt = pl.transformedTimeIntervalSeconds() ?: return null
        if (dt <= 0f)
            return null
        val (minHz, maxHz) = activitySearchBandHz()
        val (filterMinHz, filterMaxHz) = spanFilterBandHz()
        val bucketList = buckets.toList()
        if (bucketList.isEmpty())
            return null

        val geo = pl.frequencyBandGeometry(minHz, maxHz) ?: return null
        activity.ensureGeometry(
            geo.bandBins, geo.minFreqBucket, geo.dfHz, dt
        )
        val band = activity.bandScratch(geo.bandBins)

        var lastRef: Int? = null
        for (bucket in bucketList) {
            val lastBucket = lastProcessedBucket
            if (lastBucket != null && bucket < lastBucket) {
                // Looped viewer (or other rewind): tracker state must match column data.
                activity.reset()
                lastProcessedBucket = null
                lastEnqueuedBucket = null
            } else if (lastBucket != null && bucket <= lastBucket) {
                continue
            }

            pl.fillFrequencyBand(bucket, minHz, maxHz, band) ?: continue
            val obs = activity.processColumn(
                band, dt, settings().autoHeterodyneMode, filterMinHz, filterMaxHz
            )
            lastProcessedBucket = bucket
            if (obs != null)
                lastRef = applyObservation(obs.hz, dt)
        }
        return lastRef
    }

    private fun clearPlayheadGateLocked() {
        playheadGate = false
        playheadOriginSample = 0
        playheadRateHz = 0
        lastPlayheadSec = null
        heldPeaks.clear()
        playheadSample.set(0)
        playheadApplyQueued.set(false)
    }

    /**
     * Apply held viewer peaks whose time is at or before the playhead.
     * A backward playhead (loop) starts smoothing again from the peaks
     * up to the new position. Caller holds [mutex].
     */
    private fun referenceUpToPlayhead(): Int? {
        val rate = playheadRateHz
        if (!playheadGate || rate <= 0)
            return null
        val playheadSec =
            (playheadSample.get() - playheadOriginSample).toDouble() / rate
        val previousPlayhead = lastPlayheadSec
        if (previousPlayhead != null &&
            playheadSec < previousPlayhead &&
            settings().loopedAudioPlayback
        ) {
            smoothedHz = null
            lastFmaxeEpochSec = null
        }
        lastPlayheadSec = playheadSec
        val appliedThrough = lastFmaxeEpochSec
        val due = heldPeaks.filter { peak ->
            peak.epochSec <= playheadSec &&
                (appliedThrough == null || peak.epochSec > appliedThrough)
        }
        if (due.isEmpty())
            return null
        return referenceFromFmaxe(due)
    }

    /**
     * Presence-peak FmaxE values in time order. A peak older than the last
     * one applied is ignored. Caller holds [mutex].
     */
    private fun referenceFromFmaxe(peaks: List<FmaxePeak>): Int? {
        val sampleRateHz = pipeline()?.sampleRateHz() ?: return null
        if (!settings().isSmartHeterodynePlayback(sampleRateHz))
            return null
        val nyquistHz = sampleRateHz / 2f
        var lastRef: Int? = null
        for (peak in peaks.sortedBy { it.epochSec }) {
            if (!peak.hz.isFinite() || peak.hz <= 0f || peak.hz >= nyquistHz)
                continue
            val previous = lastFmaxeEpochSec
            if (previous != null && peak.epochSec < previous)
                continue
            val dt = if (previous == null) 0f else (peak.epochSec - previous).toFloat()
            lastFmaxeEpochSec = peak.epochSec
            val ref = applyObservation(peak.hz, dt)
            if (ref != null)
                lastRef = ref
        }
        return lastRef
    }

    /**
     * EWMA reference from an observation (τ = [REF_TAU_S]).
     * LO = reference − 500 Hz. Caller must hold [mutex]. Returns LO kHz.
     */
    private fun applyObservation(observationHz: Float, dtSeconds: Float): Int? {
        val prev = smoothedHz
        val smoothed =
            if (prev == null)
                observationHz
            else {
                val alpha = (1.0 - exp((-dtSeconds / REF_TAU_S).toDouble())).toFloat()
                    .coerceIn(0f, 1f)
                alpha * observationHz + (1f - alpha) * prev
            }
        smoothedHz = smoothed

        val maxkHz = ((pipeline()?.sampleRateHz() ?: 384_000) / 2000) - 1
        val refkHz = ((smoothed - OFFSET_HZ) / 1000f).roundToInt()
            .coerceAtMost(maxkHz)

        mutableRefkHz.value = refkHz
        return refkHz
    }

    private fun <T> drainChannel(channel: Channel<T>) {
        while (true) {
            val result = channel.tryReceive()
            if (!result.isSuccess)
                break
        }
    }
}
