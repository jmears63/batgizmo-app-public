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
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Orchestrates auto-tuned heterodyne: activity tracking, LO EWMA smoothing,
 * live/viewer work queue, and applying the resulting LO via [applyHeterodyneLo].
 *
 * Algorithm details live in [AutoHeterodyneActivityTracker]; this class owns
 * session state and async updates while audio output is active.
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
        private const val REF_TAU_S = 0.5f
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
    /** Viewer: skip enqueue until the playhead enters a new time bucket. */
    private var lastEnqueuedBucket: Int? = null
    private var viewerStride: Int? = null
    private var viewerRawOffset: Int? = null
    private var viewerTimeBucketCount: Int? = null
    private val workChannel = Channel<Work>(Channel.BUFFERED)
    private var workerJob: Job? = null
    /** Serializes activity/reference updates. */
    private val mutex = Mutex()

    private sealed interface Work {
        data class TimeBuckets(val range: IntRange) : Work
        data class ViewerSample(val sampleIndex: Int) : Work
    }

    fun setDisplayedRefkHz(kHz: Int) {
        mutableRefkHz.value = kHz
    }

    suspend fun resetTracker() {
        mutex.withLock {
            smoothedHz = null
            mutableRefkHz.value = null
            lastProcessedBucket = null
            lastEnqueuedBucket = null
            activity.reset()
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
        viewerRawOffset = mapping.rawOffsetToPage
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
            for (work in workChannel) {
                when (work) {
                    is Work.TimeBuckets ->
                        processTimeBuckets(work.range)
                    is Work.ViewerSample -> {
                        val pl = pipeline() ?: continue
                        val bucket =
                            pl.timeBucketForRawSampleIndex(work.sampleIndex) ?: continue
                        val buckets = mutex.withLock {
                            val last = lastProcessedBucket
                            when {
                                last != null && bucket < last -> bucket..bucket
                                last != null && bucket > last -> (last + 1)..bucket
                                else -> bucket..bucket
                            }
                        }
                        processTimeBuckets(buckets)
                    }
                }
            }
        }
    }

    fun stopWorker() {
        workerJob?.cancel()
        workerJob = null
        drainChannel(workChannel)
        lastEnqueuedBucket = null
        viewerStride = null
        viewerRawOffset = null
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

        workChannel.trySend(Work.TimeBuckets(buckets))
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
        workChannel.trySend(Work.ViewerSample(sampleIndex))
    }

    private fun viewerBucketForSample(sampleIndex: Int): Int? {
        val stride = viewerStride ?: return null
        val offset = viewerRawOffset ?: return null
        val count = viewerTimeBucketCount ?: return null
        return ((sampleIndex - offset) / stride).coerceIn(0, count - 1)
    }

    private fun activitySearchBandHz(): Pair<Float, Float> =
        Settings.AUTO_HET_LO_LIMIT_MIN_KHZ * 1000f to
            Settings.AUTO_HET_LO_LIMIT_MAX_KHZ * 1000f

    private fun spanFilterBandHz(): Pair<Float, Float> {
        val (minKhz, maxKhz) = settings().normalizedAutoHeterodyneLoRange()
        return minKhz * 1000f to maxKhz * 1000f
    }

    private suspend fun processTimeBuckets(buckets: IntRange) {
        val refkHz = mutex.withLock {
            val pl = pipeline() ?: return
            val dt = pl.transformedTimeIntervalSeconds() ?: return
            if (dt <= 0f)
                return
            val (minHz, maxHz) = activitySearchBandHz()
            val (filterMinHz, filterMaxHz) = spanFilterBandHz()
            val bucketList = buckets.toList()
            if (bucketList.isEmpty())
                return

            val geo = pl.frequencyBandGeometry(minHz, maxHz) ?: return
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
            lastRef
        } ?: return

        applyHeterodyneLo(refkHz)
    }

    /**
     * EWMA reference from activity-span observation (τ = [REF_TAU_S]).
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
