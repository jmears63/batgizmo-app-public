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

package org.batgizmo.app.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.FloatRange
import org.batgizmo.app.HORange
import org.batgizmo.app.PipelineParameters
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.pipeline.ColourMapStep.Companion.dbRangeMax
import timber.log.Timber
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

abstract class AbstractPipeline(
    val pipelineParametersSnapshot: PipelineParameters,
    protected val scope: CoroutineScope,
    protected val context: Context,
    protected val model: UIModel,
    protected val spectrogramBitmapHolder: BitmapHolder,
    protected val amplitudeBitmapHolder: BitmapHolder,
    protected val mutableXAxisRangeFlow: MutableStateFlow<FloatRange>,
    protected val mutableYAxisRangeFlow: MutableStateFlow<FloatRange>,
    protected val mutableDetailsTextFlow: MutableStateFlow<String?>,
    protected val sampleRate: Int,
    protected val sampleCount: Int,
    protected val numChannels: Int,
    protected val bitsPerSample: Int,
    protected val preserveRawDataBuffer: Boolean,
    private val showCursor: Boolean,
    protected val onTrigger: () -> Unit = {}
) {
    fun sampleRateHz(): Int = sampleRate

    abstract fun createDataSourceStep(
        pipeline: AbstractPipeline,
        transformStep: TransformStep,
        rangedRawDataBuffer: RangedShortDataBuffer
    ): DataSourceStep

    data class FftParameters(
        val windowSamples: Int,
        val windowOverlap: Int
    )

    companion object {
        const val CANARY_ENTRIES = 1
        const val CANARY_VALUE = 0xFACE.toShort()

        /**
         * Live spectrogram slice size at [NOMINAL_SLICE_REFERENCE_SAMPLE_RATE_HZ].
         * About 26 ms of audio, matching the USB streaming update cadence.
         */
        private const val NOMINAL_SLICE_ENTRIES_AT_REFERENCE = 10000
        private const val NOMINAL_SLICE_REFERENCE_SAMPLE_RATE_HZ = 384_000

        /**
         * Raw samples per pipeline slice, scaled so update interval is similar at any sample rate.
         */
        fun nominalSliceEntriesForSampleRate(sampleRate: Int, minEntries: Int): Int {
            val scaled = (
                NOMINAL_SLICE_ENTRIES_AT_REFERENCE.toLong() * sampleRate
                    / NOMINAL_SLICE_REFERENCE_SAMPLE_RATE_HZ
                ).toInt()
            return maxOf(scaled, minEntries)
        }

        /** Smallest power of two that is greater than or equal to [n] (with a floor of 1). */
        fun nextPowerOfTwo(n: Int): Int {
            if (n <= 1) return 1
            val highest = Integer.highestOneBit(n)
            return if (highest == n) n else highest shl 1
        }

        external fun nativeFindBnCRange(
            xMin: Int, xMax: Int,
            yMin: Int, yMax: Int,
            frequencyBuckets: Int,
            transformedDataBuffer: FloatArray,
            noiseBaselineDb: FloatArray?,
        ): FloatArray?

        external fun nativeFindNoiseBaseline(
            xMin: Int, xMax: Int,
            frequencyBuckets: Int,
            transformedDataBuffer: FloatArray,
            noiseBaselineBuffer: FloatArray
        )

        /**
         * Calculate the FFT window size and overlap we are going to use, based on user settings
         * and screen factors.
         */
        fun calculateFftParameters(
            pipelineParameters: PipelineParameters,
            screenFactors: ScreenFactors,
            sampleRate: Int
        ) : FftParameters {

            var fftWindowSamples: Int = pipelineParameters.nFft
            if (fftWindowSamples == PipelineParameters.NFftOptions.NFFT_AUTO.value) {
                /*
                    Find a window size that results in roughly square transformed data points as
                    viewed on the screen:
                */

                // toFloat() to avoid Int overflows.
                val fftSamplesSquared =
                    (sampleRate.toFloat() * sampleRate.toFloat()) * screenFactors.aspectFactor
                val fftSamples = (sqrt(fftSamplesSquared.toDouble()) + 0.5).toInt()

                // Round to the nearest factor of 2:
                var calculatedWindowSamples =
                    2.0.pow((log2(fftSamples.toDouble()) + 0.5).toInt()).toInt()
                calculatedWindowSamples *= 2  // Subjectively, this looks better.

                // Limit the range of windows sizes we support:
                fftWindowSamples = PipelineParameters.coerceNFft(calculatedWindowSamples)
            }

            /*
                Now we know what FFT window size we are going to use, we can figure out
                what window overlap we want.
            */

            var overlapPercentage: Int = pipelineParameters.fftOverlapPercent

            if (PipelineParameters.isAutoOverlap(pipelineParameters.fftOverlapPercent)) {

                /*
                    Find a window overlap size that gives us no more than half a data point per
                    screen Dp:
                 */

                val fftWindowTime: Float = fftWindowSamples.toFloat() / sampleRate
                val fftWindowPixels: Float = screenFactors.pixelsPerSecond * fftWindowTime
                val multiplier: Float = 2f / fftWindowPixels
                val calculatedOverlapPercentage = 100f / multiplier
                val maxOverlap: Float =
                    if (pipelineParameters.fftOverlapPercent == Settings.FftOverlapOptions.OVERLAP_AUTO75.value)
                        75f else 90f
                overlapPercentage = calculatedOverlapPercentage.coerceIn(0f, maxOverlap).toInt()
            }

            var windowOverlap: Int = (overlapPercentage * fftWindowSamples / 100f + 0.5).toInt()
            windowOverlap = windowOverlap.coerceIn(1, fftWindowSamples)

            return FftParameters(
                windowSamples = fftWindowSamples,
                windowOverlap = windowOverlap
            )
        }

        /**
         * Get some values needed by the auto FFT calculations.
         */
        fun calcScreenFactors(canvasSize: DpSize, xAxisSpan: Float, yAxisSpan: Float)
                : ScreenFactors {

            val aspectFactor =
                (canvasSize.height.value * xAxisSpan) / (canvasSize.width.value * yAxisSpan)
            val pixelsPerSecond = canvasSize.width.value / xAxisSpan

            return ScreenFactors(aspectFactor = aspectFactor, pixelsPerSecond = pixelsPerSecond)
        }
    }

    open class RangedDataBufferBase(var assignedRange: HORange = HORange.EMPTY) {
        fun update(range: HORange) {
            // Keep track of the contiguous range of raw data that we have populated:
            val ar = assignedRange
            // Extend the existing range to include the current range, limiting to the
            // size of the raw data buffer for sanity:
            assignedRange = HORange(
                minOf(ar.start, range.start),
                maxOf(ar.exclusiveEnd, range.exclusiveEnd)
            )
        }

        open fun reset() {
            assignedRange = HORange.EMPTY
        }
    }

    /**
     * A raw data buffer and its associated range of indexes which are
     * have been assigned. A null value means that no data has yet been written to the
     * buffer.
     */
    class RangedShortDataBuffer(val buffer: ShortArray, assignedRange: HORange = HORange.EMPTY)
        : RangedDataBufferBase(assignedRange) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RangedShortDataBuffer

            if (!buffer.contentEquals(other.buffer)) return false
            if (assignedRange != other.assignedRange) return false

            return true
        }

        override fun hashCode(): Int {
            var result = buffer.contentHashCode()
            result = 31 * result + (assignedRange?.hashCode() ?: 0)
            return result
        }

        override fun reset() {
            super.reset()
            buffer.fill(0)
            buffer[buffer.size - 1] = CANARY_VALUE
        }

    }

    /**
     * As above - an array that maintains the range of data values that have been assigned.
     * I avoid using kotlin generics for this as basic data types get boxed into Arrays, adding
     * overhead and complexity in the native layer.
     */
    class RangedFloatDataBuffer(val buffer: FloatArray, assignedRange: HORange = HORange.EMPTY)
        : RangedDataBufferBase(assignedRange) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RangedFloatDataBuffer

            if (!buffer.contentEquals(other.buffer)) return false
            if (assignedRange != other.assignedRange) return false

            return true
        }

        override fun hashCode(): Int {
            var result = buffer.contentHashCode()
            result = 31 * result + (assignedRange?.hashCode() ?: 0)
            return result
        }

        override fun reset() {
            super.reset()
            buffer.fill(dbRangeMax.start)
            buffer[buffer.size - 1] = CANARY_VALUE.toFloat()
        }
    }

    data class NoiseBaselineHolder(var noiseBaselineDb: FloatArray? = null) {

        fun reset() {
            noiseBaselineDb = null
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NoiseBaselineHolder

            if (!noiseBaselineDb.contentEquals(other.noiseBaselineDb)) return false

            return true
        }

        override fun hashCode(): Int {
            return noiseBaselineDb?.contentHashCode() ?: 0
        }
    }

    data class PipelineData(
        val calcs: CalculatedParams,
        val dataSourceStep: DataSourceStep,
        val transformStep: TransformStep,
        val colourMapStep: ColourMapStep,
        val rangedRawDataBuffer: RangedShortDataBuffer,
        val noiseBaselineHolder: NoiseBaselineHolder,
        val transformedDataBuffer: RangedFloatDataBuffer
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PipelineData

            if (calcs != other.calcs) return false
            if (dataSourceStep != other.dataSourceStep) return false
            if (transformStep != other.transformStep) return false
            if (colourMapStep != other.colourMapStep) return false
            if (rangedRawDataBuffer != other.rangedRawDataBuffer) return false
            if (noiseBaselineHolder != other.noiseBaselineHolder) return false
            if (transformedDataBuffer != other.transformedDataBuffer) return false

            return true
        }

        override fun hashCode(): Int {
            var result = calcs.hashCode()
            result = 31 * result + dataSourceStep.hashCode()
            result = 31 * result + transformStep.hashCode()
            result = 31 * result + colourMapStep.hashCode()
            result = 31 * result + rangedRawDataBuffer.hashCode()
            result = 31 * result + noiseBaselineHolder.hashCode()
            result = 31 * result + transformedDataBuffer.hashCode()
            return result
        }
    }

    data class CalculatedParams(
        val rawTotalDataLength: Int,
        val rawSampleRate: Int,
        val rawTimeInterval: Float,
        val rawPagedDataLength: Int,
        val rawOffsetToPage: Int,
        val fftWindowSize: Int,
        val fftStride: Int,
        val fftOverlap: Int,
        val transformedTimeInterval: Float,
        val transformedFrequencyInterval: Float,
        val transformedFrequencyBucketCount: Int,
        val transformedTimeBucketCount: Int,
        val rawSliceEntries: Int,
        val sliceTransformedTimeBucketCount: Int,
        val rawSliceOverlap: Int,
    )

    /*
        This class's private data must only be accessed within an synchronized/mutex block.
        All public methods must therefore contain a synchronized block for safe data access.
    */
    protected var pipelineData: PipelineData? = null
    private var cachedRawDataBuffer: RangedShortDataBuffer? = null

    // Synchronize access to data members:
    protected val mutex = Mutex()

    fun getVisibleRawDataBuffer(timeAxisRangeFlow: StateFlow<FloatRange>): Pair<ShortArray, HORange>? {

        pipelineData?.let { pd ->
            pd.rangedRawDataBuffer?.buffer?.let { buffer ->
                val timeAxisRange = timeAxisRangeFlow.value
                val range = HORange(
                    maxOf(0, (timeAxisRange.start * pd.calcs.rawSampleRate).toInt()),
                    minOf(buffer.size, (timeAxisRange.endInclusive * pd.calcs.rawSampleRate).toInt())
                )

                return Pair(buffer, range)
            }
        }

        return null
    }

    data class FrequencyBandGeometry(
        val minFreqBucket: Int,
        val bandBins: Int,
        val dfHz: Float
    )

    /** First bucket index at or above [minHz]; last at or below [maxHz]. */
    private fun frequencyBucketIndices(
        minHz: Float,
        maxHz: Float,
        df: Float,
        freqCount: Int
    ): Pair<Int, Int>? {
        if (df <= 0f || freqCount < 1)
            return null
        val minBucket = ceil(minHz / df - 1e-6f).toInt().coerceIn(0, freqCount - 1)
        val maxBucket = (maxHz / df).toInt().coerceIn(0, freqCount - 1)
        if (maxBucket < minBucket)
            return null
        return minBucket to maxBucket
    }

    /** Band geometry for [minHz, maxHz] without reading spectrogram samples. */
    suspend fun frequencyBandGeometry(
        minHz: Float,
        maxHz: Float
    ): FrequencyBandGeometry? = mutex.withLock {
        val calcs = pipelineData?.calcs ?: return@withLock null
        val freqCount = calcs.transformedFrequencyBucketCount
        val df = calcs.transformedFrequencyInterval
        val range = frequencyBucketIndices(minHz, maxHz, df, freqCount)
            ?: return@withLock null
        val (minBucket, maxBucket) = range
        return@withLock FrequencyBandGeometry(
            minBucket, maxBucket - minBucket + 1, df
        )
    }

    /**
     * Copy one spectrogram time column's [minHz, maxHz] band into [dest].
     * Requires dest.size >= bandBins.
     */
    suspend fun fillFrequencyBand(
        timeBucket: Int,
        minHz: Float,
        maxHz: Float,
        dest: FloatArray
    ): FrequencyBandGeometry? = mutex.withLock {
        val pd = pipelineData ?: return@withLock null
        val calcs = pd.calcs
        val assigned = pd.transformStep.getDataAssignedRange() ?: return@withLock null
        if (timeBucket < assigned.start || timeBucket >= assigned.exclusiveEnd)
            return@withLock null
        if (timeBucket < 0 || timeBucket >= calcs.transformedTimeBucketCount)
            return@withLock null

        val freqCount = calcs.transformedFrequencyBucketCount
        val df = calcs.transformedFrequencyInterval
        val range = frequencyBucketIndices(minHz, maxHz, df, freqCount)
            ?: return@withLock null
        val (minBucket, maxBucket) = range
        val bandBins = maxBucket - minBucket + 1
        if (dest.size < bandBins)
            return@withLock null

        val row = timeBucket * freqCount
        val buf = pd.transformedDataBuffer.buffer
        for (i in 0 until bandBins)
            dest[i] = buf[row + minBucket + i]

        return@withLock FrequencyBandGeometry(minBucket, bandBins, df)
    }

    /** Spectrogram time-bucket interval in seconds (for EWMA). */
    suspend fun transformedTimeIntervalSeconds(): Float? = mutex.withLock {
        pipelineData?.calcs?.transformedTimeInterval
    }

    /**
     * Map a raw sample index (into the page/raw buffer) to a spectrogram time bucket.
     */
    data class SpectrogramTimeMapping(
        val fftStride: Int,
        val rawOffsetToPage: Int,
        val timeBucketCount: Int
    )

    suspend fun spectrogramTimeMapping(): SpectrogramTimeMapping? = mutex.withLock {
        val calcs = pipelineData?.calcs ?: return@withLock null
        if (calcs.fftStride < 1 || calcs.transformedTimeBucketCount < 1)
            return@withLock null
        return@withLock SpectrogramTimeMapping(
            calcs.fftStride,
            calcs.rawOffsetToPage,
            calcs.transformedTimeBucketCount
        )
    }

    suspend fun timeBucketForRawSampleIndex(sampleIndex: Int): Int? = mutex.withLock {
        val calcs = pipelineData?.calcs ?: return@withLock null
        if (calcs.fftStride < 1 || calcs.transformedTimeBucketCount < 1)
            return@withLock null
        val relative = sampleIndex - calcs.rawOffsetToPage
        return@withLock (relative / calcs.fftStride)
            .coerceIn(0, calcs.transformedTimeBucketCount - 1)
    }


    private fun startPipeline(pld: PipelineData) {
        pld.dataSourceStep.start()
        pld.transformStep.start()
        pld.colourMapStep.start()
    }

    /**
     * Called from the UI thread.
     *
     * This class must be cleaned up by its owner when it is finished with so that resources
     * and handles are freed.
     */
    suspend fun shutdown() {
        mutex.withLock {
            internalShutdown()
        }
    }

    private suspend fun internalShutdown(updateUI: Boolean = true) {
        // First shutdown anything that needs it at the pipeline level:
        shutdownPipeline()

        // Then shut down the individual steps:
        pipelineData?.let {
            // Make sure underlying handles and resources are closed:
            it.dataSourceStep.shutdown()
            it.transformStep.shutdown()
            it.colourMapStep.shutdown()
        }

        // Don't do this until all the shutdowns have complete. This avoids race conditions
        // that might arise between the pipeline and native layer processing in different threads:
        pipelineData = null

        synchronized(spectrogramBitmapHolder) {
            spectrogramBitmapHolder.bitmap = null
        }
        synchronized(amplitudeBitmapHolder) {
            amplitudeBitmapHolder.bitmap = null
        }

        if (updateUI) {
            // Clear the display. This call is thread safe.
            spectrogramBitmapHolder.signalUpdate()
            amplitudeBitmapHolder.signalUpdate()
        }
    }

    /**
     * Override this to do any shutdown that might need doing at the pipeline level.
     */
    protected open suspend fun shutdownPipeline() {
    }

    /**
     * Reset the pipeline, preserving the steps and data buffers, but resetting data values.
     */
    suspend fun resetState(updateUI: Boolean = true) {
        mutex.withLock {
            internalResetState(updateUI)
        }
    }

    private suspend fun internalResetState(updateUI: Boolean = true) {
        // First reset anything that needs it at the pipeline level:
        resetPipelineState()

        // Then reset the individual steps:
        pipelineData?.let {
            // Make sure underlying handles and resources are closed:
            it.dataSourceStep.resetState()
            it.transformStep.resetState()
            it.colourMapStep.resetState()
        }

        if (updateUI) {
            // Clear the display. This call is thread safe.
            spectrogramBitmapHolder.signalUpdate()
            amplitudeBitmapHolder.signalUpdate()
        }
    }

    /**
     * Override this to do any reset that might need doing at the pipeline level.
     */
    protected open suspend fun resetPipelineState() {

        Timber.d("baseline: resetPipelineState called")

        val pd = pipelineData
        if (pd != null) {
            pd.rangedRawDataBuffer.reset()
            pd.transformedDataBuffer.reset()
            pd.noiseBaselineHolder.reset()
        }

        synchronized(spectrogramBitmapHolder) {
            spectrogramBitmapHolder.bitmap?.apply {
                eraseColor(Color.BLACK)
            }
        }

        synchronized(amplitudeBitmapHolder) {
            amplitudeBitmapHolder.bitmap?.apply {
                eraseColor(Color.BLACK)
            }
        }
    }


    /**
     * Called from a worker thread.
     *
     * This method is the only way to update the visible data in its entirety.
     * The pipeline may be process starting from any step as required by the caller.
     * The final copy of the bitmap into the UI can be suppressed if required
     * to avoid flicker when multiple renders are done.
     */
    suspend fun fullExecute(
        fftParameters: FftParameters,
        rawPageRange: HORange? = null,
        amplitudeSizeDp: DpSize? = null,
        doRender: Boolean = true
    ) {
        mutex.withLock {
            // Might throw an OutOfMemoryError:
            internalFullExecute(fftParameters, rawPageRange, amplitudeSizeDp, doRender)
        }
    }

    private suspend fun internalFullExecute(
        fftParameters: FftParameters,
        rawPageRange: HORange? = null,
        amplitudeSizeDp: DpSize? = null,
        doRender: Boolean,
    ) {
        Timber.d("internalFullExecute called")

        // Slight hack if no amplitude pane:
        val dummyAmplitudeSize = DpSize(100.dp, 100.dp)

        // Shut down any existing pipeline without updating the
        // UI, to avoid momentary blanking of the screen.
        internalShutdown(updateUI = false)

        Timber.d("internalFullExecute calling setupPipeline")
        /*
         * Release existing memory allocations to the garbage collector so that
         * that setupPipeline standards the best chance of allocating new
         * arrays:
         */
        pipelineData = null

        // The following might throw an OutOfMemoryError:
        pipelineData = setupPipeline(
            fftParameters,
            rawPageRange,
            amplitudeSizeDp ?: dummyAmplitudeSize
        )

        pipelineData?.let {
            startPipeline(it)
            if (doRender)
                it.dataSourceStep.fullRender()
        }
    }

    /**
     * Render the raw data slice whose range is supplied.
     */
    open suspend fun sliceRender(sliceRange: HORange, transformedEntryIndex: Int) {
        var autoHetBuckets: IntRange? = null
        mutex.withLock {
            pipelineData?.dataSourceStep?.sliceRender(sliceRange, transformedEntryIndex)
            val calcs = pipelineData?.calcs
            if (calcs != null && calcs.sliceTransformedTimeBucketCount > 0) {
                val last = (transformedEntryIndex + calcs.sliceTransformedTimeBucketCount - 1)
                    .coerceAtMost(calcs.transformedTimeBucketCount - 1)
                if (last >= transformedEntryIndex)
                    autoHetBuckets = transformedEntryIndex..last
            }
        }
        // Outside pipeline lock: Model may take its own locks / call JNI.
        autoHetBuckets?.let { model.onLiveSpectrumBucketsForAutoHeterodyne(it) }
    }

    /**
     * Call this method from a worker thread.
     *
     * This method is invoked to apply an updated BnC setting, for example
     * because it has been manually or automatically changed.
     *
     * Do not call this method in the UI thread - it does heavy calculation.
     */
    suspend fun applyBnC(range: FloatRange) {
        mutex.withLock {
            val cms = pipelineData?.colourMapStep
            cms?.let {
                val params = it.params
                if (params != null) {
                    Timber.d("applyBnC: $range is being set")
                    val newParams =
                        ColourMapStep.Params(params.calcs, bnCRangeLogical = range)
                    it.params = newParams

                    pipelineData?.colourMapStep?.fullRender()
                }
            }
        }
    }

    /**
     * Re-run the pipeline from the data source so spectrogram and amplitude both
     * pick up a newly installed colour map (amplitude stroke colour included).
     */
    suspend fun fullRenderFromSource() {
        mutex.withLock {
            pipelineData?.dataSourceStep?.fullRender()
        }
    }

    /**
     * Call this method on a worker thread.
     *
     * Map the logical visible range to the data in transformed data buffer, and
     * calculate the minimum and maximum dB values there. We'll use that to set up
     * auto BnC.
     *
     * This method does heavy calculations: don't call it in the main thread.
     */
    suspend fun calculateAutoBnC(visibleXRange: FloatRange, visibleYRange: FloatRange): FloatRange? {
        mutex.withLock() {
            Timber.d("calculateAutoBnC called")
            val pd = pipelineData
            if (pd == null)
                return null

            val calcs = pd.calcs

            // Convert the logical ranges to actual ones:
            val xIndexRange = Pair(
                (visibleXRange.start * calcs.transformedTimeBucketCount - 1).toInt()
                    .coerceIn(0, calcs.transformedTimeBucketCount - 1),
                (visibleXRange.endInclusive * calcs.transformedTimeBucketCount - 1).toInt()
                    .coerceIn(0, calcs.transformedTimeBucketCount - 1)
            )
            val yIndexRange = Pair(
                (visibleYRange.start * calcs.transformedFrequencyBucketCount - 1).toInt()
                    .coerceIn(0, calcs.transformedFrequencyBucketCount - 1),
                (visibleYRange.endInclusive * calcs.transformedFrequencyBucketCount - 1).toInt()
                    .coerceIn(0, calcs.transformedFrequencyBucketCount - 1)
            )

            var range: FloatArray? = null

            // We need the intersection of the visible range and the assigned data range,
            // to avoid calculating BnC on uninitialized data:
            val assignedTimeRange = pipelineData?.transformStep?.getDataAssignedRange()
            // Timber.d("JM: transformed assignedTimeRange = $assignedTimeRange")
            if (assignedTimeRange != null) {
                val clippedXIndexRange = Pair(
                    maxOf(xIndexRange.first, assignedTimeRange.start),
                    minOf(xIndexRange.second, assignedTimeRange.exclusiveEnd)
                )
                // Timber.d("JM: clippedXIndexRange = $clippedXIndexRange")
                if (clippedXIndexRange.second > clippedXIndexRange.first) {
                    val rangeFromData = nativeFindBnCRange(
                        clippedXIndexRange.first, clippedXIndexRange.second,
                        yIndexRange.first, yIndexRange.second,
                        calcs.transformedFrequencyBucketCount,
                        pd.transformedDataBuffer.buffer,
                        pd.noiseBaselineHolder.noiseBaselineDb
                    )
                    if (rangeFromData != null) {
                        Timber.d("AutoBnC rangeFromData = ${rangeFromData[0]}..${rangeFromData[1]}, " +
                                "noise baseline is ${if (pd.noiseBaselineHolder.noiseBaselineDb != null) "present" else "absent"} ")
                        range = rangeFromData.copyOf()
                    }
                }
            }

            // Default BnC range to use if there is no data visible:
            var floatRange = ColourMapStep.dbRangeMax

            /*
             * Subjectively, it's nice if the bottom part of the data dB range is black, as it is usually noise
             * and nothing of interest. For now we use a fixed percentage of the range. An improvement
             * would be to find the dB value of highest frequency and use that as threshold.
             */

            if (range != null) {
                val lower = maxOf(ColourMapStep.dbRangeMax.start, range[0])
                val diff = range[1] - lower
                val blackRange = diff * 0.20f
                floatRange = FloatRange(lower + blackRange, range[1])
            }
            Timber.d("auto BnC range in visible region (1) is $floatRange")

            return floatRange
        }
    }

    /**
     * Call this expensive method on a worker thread.
     *
     * Calculate the noise profile if the buffer has been allocated to contain it
     * , resulting in a noise offset per frequency bucket.
     *
     * This method does heavy calculations: don't call it in the main thread.
     */
    suspend fun calculateNoiseBaseline() {
        mutex.withLock() {
            // Timber.d("calculateNoiseBaseline called")
            val pd = pipelineData
            if (pd == null)
                return

            val calcs = pd.calcs

            val baselineBuffer = FloatArray(calcs.transformedFrequencyBucketCount)

            // Only calculate the noise baseline if it is required, ie if the buffer is not null:
            // Use all the data available in the buffer, don't limit it to the visible range:
            val assignedTimeRange = pipelineData?.transformStep?.getDataAssignedRange()
            assignedTimeRange?.let {
                if (assignedTimeRange.exclusiveEnd > assignedTimeRange.start) {
                    // val elapsed = measureTime {
                        nativeFindNoiseBaseline(
                            assignedTimeRange.start, assignedTimeRange.exclusiveEnd,
                            calcs.transformedFrequencyBucketCount,
                            pd.transformedDataBuffer.buffer,
                            baselineBuffer
                        )
                    // }
                    // Timber.d("Time taken to find the noise baseline = $elapsed")

                    /*
                     * Adjust the profile to provide a linear reduction above a certain frequency.
                     * This subjectively looks natural and it avoids over emphasising spurious detail at
                     * very high frequencies. The parameters were chosen by trial and error.
                     * Linear reduction to avoid expensive logs in the loop below.
                    */
                    val fCornerHz: Float = 80f * 1000f
                    val reductionFactor = 10f
                    val freqCornerBucket =
                        round((fCornerHz) / calcs.transformedFrequencyInterval)
                            .toInt().coerceIn(1, calcs.transformedFrequencyBucketCount - 1)
                    for (freqBucket in freqCornerBucket until calcs.transformedFrequencyBucketCount) {
                        val freqRatio =
                            (freqBucket - freqCornerBucket).toFloat() / freqCornerBucket
                        val deltaDb = freqRatio * reductionFactor
                        // *add* the delta as we will subtract the profile:
                        baselineBuffer[freqBucket] = baselineBuffer[freqBucket] + deltaDb
                    }
                }

                pd.noiseBaselineHolder.noiseBaselineDb = baselineBuffer
            }
        }
    }

    fun clearNoiseBaseline(): Boolean {
        val changed = (pipelineData?.noiseBaselineHolder?.noiseBaselineDb != null)
        pipelineData?.noiseBaselineHolder?.noiseBaselineDb = null
        return changed
    }

    data class ScreenFactors(val aspectFactor: Float, val pixelsPerSecond: Float)

    /**
     * Set up the pipeline ready to be started. If anything bad happens,
     * we throw an exception.
     */
    private suspend fun setupPipeline(fftParameters: FftParameters, rawPageRange: HORange?,
                              amplitudeSizeDp: DpSize)
            : PipelineData {
        /**
         * Build a pipeline including all its steps and buffers.
         * The pipeline is created on entering file viewer mode, and lives until
         * we leave that mode.
         *
         * The steps use settings, and are triggered by the previous step, or directly by
         * calling trigger on the step. Each step triggers the next on completion.
         */

        val runtime = Runtime.getRuntime()

        // Noisy log:
        // diagnosticLogger.log { "setupPipeline start: runtime.{maxMemory, totalMemory, freeMemory) = " +
        //        "${runtime.maxMemory() / 1024}, ${runtime.totalMemory() / 1024}, ${runtime.freeMemory() / 1024} KB" }

        try {
            // Calculate everything we need to know to set up the pipeline:
            val calcs: CalculatedParams =
                doCalculations(pipelineParametersSnapshot, sampleRate, sampleCount, fftParameters, rawPageRange)

            // Update the UI with details of the transform:
            mutableDetailsTextFlow.value =
                "%.1fs at %d kHz, %d ch, %d-bit\nFFT window %d, overlap %d".format(
                    calcs.rawTotalDataLength.toFloat() / calcs.rawSampleRate,
                    (calcs.rawSampleRate / 1000f).toInt(),
                    numChannels, bitsPerSample,
                    calcs.fftWindowSize, calcs.fftOverlap
                )

            Timber.d("Calculations: fftWindowSize = ${calcs.fftWindowSize}, " +
                "fftWindowSize = ${calcs.fftOverlap}, " +
                "rawSliceEntries = ${calcs.rawSliceEntries}, " +
                "slice time = ${calcs.rawSliceEntries * 1000 / calcs.rawSampleRate} ms"
            )

            // throw OutOfMemoryError("testing running out of memory")    // Uncomment this to test OOM.

            /**
             * Allocate buffers used to share data between steps. These buffers are
             * sized to accommodate the entire data range corresponding to the
             * maximum file time window.
             */

            // Buffer for raw data read from the data file:
            var rangedRawDataBuffer: RangedShortDataBuffer? = null
            val sizeRequired = calcs.rawPagedDataLength + CANARY_ENTRIES
            val crwb = cachedRawDataBuffer
            if (preserveRawDataBuffer && crwb != null && crwb.buffer.size == sizeRequired) {
                Timber.d("reusing the raw data buffer: assignedRange = ${cachedRawDataBuffer?.assignedRange}")
                rangedRawDataBuffer = cachedRawDataBuffer
            }
            else {
                Timber.i("Attempting to allocate ShortArray($sizeRequired), ${Short.SIZE_BYTES * sizeRequired/1000} KB")
                rangedRawDataBuffer = RangedShortDataBuffer(ShortArray(sizeRequired))
            }
            require(rangedRawDataBuffer != null) { "Internal error: rawDataBuffer should not be null" }

            // Hold a reference in case we want to re-use it on rebuilding the pipeline:
            cachedRawDataBuffer = rangedRawDataBuffer
            rangedRawDataBuffer.buffer[rangedRawDataBuffer.buffer.size - 1] = CANARY_VALUE.toShort()

            val transformedDataBufferSize =
                calcs.transformedTimeBucketCount * calcs.transformedFrequencyBucketCount

            // Buffer for transformed data generated by the SFFT transform step.
            // We flatten the data into a one dimensional array in the way you
            // would guess:
            Timber.i("Attempting to allocate FloatArray($transformedDataBufferSize), " +
                    "${Float.SIZE_BYTES * transformedDataBufferSize/1000} KB")
            /*
            val transformedDataBuffer = FloatArray(transformedDataBufferSize)
            // Initialize to the value of the lowest end of the colour map:
            transformedDataBuffer.fill(dbRangeMax.start)ShortArray(sizeRequired)
             */
            val transformedDataBuffer = RangedFloatDataBuffer(
                FloatArray(transformedDataBufferSize + 1))
            // Initialize to the value of the lowest end of the colour map:
            transformedDataBuffer.buffer.fill(dbRangeMax.start)
            transformedDataBuffer.buffer[transformedDataBuffer.buffer.size - 1] = CANARY_VALUE.toFloat()

            /**
             * Bitmap to hold the final transformed and colour mapped data, and place
             * a reference to it in the holder so that other parts of the code
             * (such as UI rendering) can access it.
             */

            val spectrogramBitmap = createBitmap(
                calcs.transformedTimeBucketCount,
                calcs.transformedFrequencyBucketCount,
                Bitmap.Config.RGB_565
            )
            spectrogramBitmap.apply {
                eraseColor(Color.BLACK)
            }
            spectrogramBitmapHolder.bitmap = spectrogramBitmap

            val amplitudeBitmap = createBitmap(
                calcs.transformedTimeBucketCount,
                amplitudeSizeDp.height.value.roundToInt().coerceIn(10, null),
                Bitmap.Config.RGB_565
            )
            amplitudeBitmap.apply {
                eraseColor(Color.BLACK)
            }
            amplitudeBitmapHolder.bitmap = amplitudeBitmap

            /**
             * Create the steps in REVERSE order below so that each step can be passed
             * its subsequent step:
             */

            // The baseline holder is empty initially until we get some actual data and
            // calculate the noise baseline, later:
            val noiseBaselineHolder = NoiseBaselineHolder()
            // Timber.d("abcd: noiseBaselineHolder = NoiseBaselineHolder()")

            // Create a step to map the transformed data (spectral intensities) to colours:
            val colourMapStep =
                ColourMapStep(transformedDataBuffer, spectrogramBitmap, noiseBaselineHolder,
                    { model.colourMapSize }, model.settings)
            // Use the existing BnC range, so this is preserved when a new file is loaded:
            colourMapStep.params =
                ColourMapStep.Params(calcs = calcs, bnCRangeLogical = model.bnCRangeFlow.value)

            // Create a step to transform the raw data to the frequency domain:
            val transformStep = TransformStep(model,
                colourMapStep, rangedRawDataBuffer.buffer,
                transformedDataBuffer,
                amplitudeBitmapHolder,
                onTrigger,
                showCursor
            )
            val p = TransformStep.Params(calcs = calcs)
            Timber.d(
                "assigning transformStep.params with ${p.calcs.fftWindowSize}"            )
            transformStep.params = p

            // Create a step to populate the raw data buffer from the data file:
            val dataSourceStep = createDataSourceStep(this, transformStep, rangedRawDataBuffer)
            dataSourceStep.params = DataSourceStep.Params(calcs = calcs)

            // Slight hack to make sure we some memory left in hand. The GC will recover this
            // allocation when dummy has gone out of scope. If we don't have this much memory left,
            // an Error will be thrown:
            val memoryReserved = 10000
            val dummy = CharArray(memoryReserved)

            val pld = PipelineData(
                calcs = calcs,
                rangedRawDataBuffer = rangedRawDataBuffer,
                dataSourceStep = dataSourceStep,
                transformedDataBuffer = transformedDataBuffer,
                transformStep = transformStep,
                colourMapStep = colourMapStep,
                noiseBaselineHolder = noiseBaselineHolder
            )
            pipelineData = pld
            return pld
        } catch (e: Exception) {
            shutdown()
            throw e
        }
        finally {
            // Noisy log:
            // diagnosticLogger.log { "setupPipeline end: runtime.{maxMemory, totalMemory, freeMemory) = " +
            //        "${runtime.maxMemory() / 1024}, ${runtime.totalMemory() / 1024}, ${runtime.freeMemory() / 1024} KB" }
        }
    }

    /**
     * Calculate FFT parameters taking into account Settings and
     * assuming that the initial axis ranges is a full view of the first window into
     * the data.
     */
    suspend fun getDefaultFftParameters(
        sampleRate: Int,
        canvasSize: DpSize
    ): FftParameters {
        mutex.withLock {
            val maxRawDataCount = (pipelineParametersSnapshot.dataPageTimeSpanS * sampleRate).toInt()
            val rawDataCount: Int = minOf(sampleCount, maxRawDataCount)
            // val xAxisSpan = rawDataCount.toFloat() / sampleRate

            val yAxisSpan = sampleRate / 2.0f
            val screenFactors = calcScreenFactors(
                canvasSize, rawDataCount.toFloat() / sampleRate,
                yAxisSpan
            )

            val fftParameters = calculateFftParameters(pipelineParametersSnapshot, screenFactors, sampleRate)

            return fftParameters
        }
    }

    private fun doCalculations(
        pipelineParameters: PipelineParameters,
        sampleRate: Int,
        sampleCount: Int,
        fftParameters: FftParameters,
        theRawPageRange: HORange?
    ): CalculatedParams {

        // Limit the raw data buffer size to the maximum file window configured in
        // settings:
        val rawSamplesPerPage = (pipelineParameters.dataPageTimeSpanS * sampleRate).toInt()
        val rawPageRange = theRawPageRange ?: HORange(0, minOf(sampleCount, rawSamplesPerPage))
        val rawPageDataCount = rawPageRange.exclusiveEnd - rawPageRange.start

        // Use calculated FFT parameters values rather values from settings:
        var nFft = fftParameters.windowSamples
        var halfNFft = nFft / 2      // nFft is always even, so no rounding occurs.

        val fftOverlapCount = fftParameters.windowOverlap
        var fftStride = (nFft - fftOverlapCount)
        fftStride = fftStride.coerceIn(1, nFft)

        // Bound the spectrogram bitmap width (transformedTimeBucketCount, computed below) to the
        // largest texture this device's GPU can allocate. Without this, a long page at a high
        // sample rate with a small FFT window and/or high overlap produces an enormous bitmap that
        // the driver cannot allocate, which surfaces as a fatal EGL_BAD_ALLOC on the render thread.
        // We only intervene when the device limit would be exceeded, so capable devices and normal
        // settings are unaffected; constrained cases degrade by coarsening time resolution.
        val maxBitmapWidth = model.maxBitmapDimension
        if (maxBitmapWidth > 1 && rawPageDataCount > nFft) {
            // Smallest stride for which (rawPageDataCount - nFft) / stride + 1 <= maxBitmapWidth,
            // using integer ceiling division:
            val minStride = (rawPageDataCount - nFft + maxBitmapWidth - 2) / (maxBitmapWidth - 1)
            if (minStride > fftStride) {
                // If the required stride exceeds the window, grow the window to the next power of
                // two so the stride never exceeds it. This keeps the inter-slice overlap
                // (nFft - fftStride) non-negative and only coarsens resolution as much as needed.
                if (minStride > nFft) {
                    nFft = nextPowerOfTwo(minStride)
                    halfNFft = nFft / 2
                }
                fftStride = minStride.coerceAtMost(nFft)
                Timber.i(
                    "Capping spectrogram width to $maxBitmapWidth: nFft=$nFft, fftStride=$fftStride"
                )
            }
        }

        val rawTimeInterval = 1f / sampleRate
        val transformedTimeInterval: Float = rawTimeInterval * fftStride

        /**
         * There are nfft / 2 + 1 frequency buckets spanning the range from 0 to Nyquist. There are
         * one fewer intervals than buckets. Each bucket is centred at its frequency, so spans
         * +/- half the bucket interval.
         */
        val transformedFrequencyBucketCount: Int = halfNFft + 1
        val nyquist: Float = sampleRate / 2f
        val transformedFrequencyInterval: Float = nyquist / (halfNFft)

        /**
         * Important notes - I will say this only once ;-)
         * -----------------------------------------------
         *
         * - We take the time value of a bucket to be the time value of the raw data sample
         *      corresponding to the middle of the SFFT window.
         * - The SFFT window is a even number of entries because it is a power of two. To avoid an
         *      annoying half time step and to keep things simple, we round it down so it corresponds
         *      to the raw value just *before* the centre of the window. That is the time of
         *      the transformed data corresponding to that window.
         * - The first transformed time bucket corresponds to centre of the first fft window, subsequent
         *      ones are spaced by the stride time.
         * - We will discard any raw data samples left over at the end. That will be less than a window.
         * - Slices will be overlapped slightly, so that the first transformed result is
         *      one stride on from the final result of the previous window.
         * - The slice size is chosen to allow an exact number of FFT windows given the stride,
         *      which is (window size) + n * (stride length)
         */

        // Rounding down. The +1 is because the range, having subtracted the FFT window, is inclusive
        // of its end values:
        val transformedTimeBucketCount: Int =
            (rawPageDataCount - nFft) / fftStride + 1

        // The following size must be greater than the maximum FFT window size - preferably,
        // many times. This value determines the UI update granularity (~26 ms at any rate).
        val nominalSliceEntries = nominalSliceEntriesForSampleRate(sampleRate, nFft)

        // Round the slice size to accommodate an exact number of strides, allowing for a half window at
        // each end of the slice:
        val sliceTransformedTimeBucketCount =
            (nominalSliceEntries - nFft) / fftStride + 1
        val rawSliceEntries =
            (sliceTransformedTimeBucketCount - 1) * fftStride + nFft
        // What overlap do we need between slices such that the centre of the first window in a
        // slice is one stride one from the centre of the last window in the previous one?
        val rawSliceOverlap = nFft - fftStride

        return CalculatedParams(
            rawTotalDataLength = sampleCount,
            rawPagedDataLength = rawPageDataCount,
            rawOffsetToPage = rawPageRange.start,
            rawSampleRate = sampleRate,
            rawTimeInterval = rawTimeInterval,
            fftWindowSize = nFft,
            fftStride = fftStride,
            fftOverlap = fftOverlapCount,
            transformedTimeInterval = transformedTimeInterval,
            transformedFrequencyInterval = transformedFrequencyInterval,
            transformedFrequencyBucketCount = transformedFrequencyBucketCount,
            transformedTimeBucketCount = transformedTimeBucketCount,
            rawSliceEntries = rawSliceEntries,
            sliceTransformedTimeBucketCount = sliceTransformedTimeBucketCount,
            rawSliceOverlap = rawSliceOverlap
        )
    }

    /**
     * This function is called in the UI thread.
     *     * Calculate the axis ranges corresponding to the logical visible ranges
     * supplied, and update the Flows that drive the UI subscribes to.
     */
    suspend fun updateAxisRangesFromLogical(xVisibleRange: FloatRange, yVisibleRange: FloatRange) {
        mutex.withLock {
            pipelineData?.let {
                val (xDataRange, yDataRange) = it.dataSourceStep.getMaxAxisRanges()

                // Scale the max ranges by the logical ranges supplied:
                val xAxisMin = xDataRange.start + xDataRange.difference() * xVisibleRange.start
                val xAxisMax =
                    xDataRange.start + xDataRange.difference() * xVisibleRange.endInclusive

                val yAxisMax =
                    yDataRange.start + yDataRange.difference() * (1f - yVisibleRange.start)
                val yAxisMin =
                    yDataRange.start + yDataRange.difference() * (1f - yVisibleRange.endInclusive)

                // Timber.d("updateAxisRanges setting x axis range to $xAxisMin, $xAxisMax, xDataRange.start = ${xDataRange.start}")
                mutableXAxisRangeFlow.value = FloatRange(xAxisMin, xAxisMax)
                mutableYAxisRangeFlow.value = FloatRange(yAxisMin, yAxisMax)
            }
        }
    }

    suspend fun calculateFftParameters(
        settings: Settings,
        screenFactors: ScreenFactors
    ): FftParameters? {
        mutex.withLock {
            val mySampleRate: Int? = pipelineData?.calcs?.rawSampleRate

            return if (mySampleRate != null)
                calculateFftParameters(pipelineParametersSnapshot,  screenFactors, mySampleRate)
            else
                null
        }
    }

    data class PagingData(
        val rawTotalDataLength: Int,
        val rawSampleRate: Int,
        val pipelineParametersSnapshot: PipelineParameters
    )

    suspend fun getPagingData(): PagingData? {
        var pagingData: PagingData? = null
        mutex.withLock {
            pipelineData?.let {
                pagingData = PagingData(
                    rawTotalDataLength = it.calcs.rawTotalDataLength,
                    rawSampleRate = it.calcs.rawSampleRate,
                    pipelineParametersSnapshot
                )
            }
        }
        return pagingData
    }
}