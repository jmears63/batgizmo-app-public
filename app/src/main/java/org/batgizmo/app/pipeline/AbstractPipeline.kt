/*
 * Copyright (c) 2025 John Mears
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
import android.util.Log
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.FloatRange
import org.batgizmo.app.HORange
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.pipeline.ColourMapStep.Companion.dbRangeMax
import uk.org.gimell.batgimzoapp.BuildConfig
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

abstract class AbstractPipeline(
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
    protected val preserveRawDataBuffer: Boolean,
    protected val onTrigger: () -> Unit = {}
) {
    abstract fun createDataSourceStep(
        pipeline: AbstractPipeline,
        transformStep: TransformStep,
        rangedRawDataBuffer: RangedRawDataBuffer
    ): DataSourceStep

    data class FftParameters(
        val windowSamples: Int,
        val windowOverlap: Int
    )

    companion object {
        const val CANARY_ENTRIES = 1
        const val CANARY_VALUE = 0xFACE.toShort()

        external fun findBnCRange(
            xMin: Int, xMax: Int,
            yMin: Int, yMax: Int,
            frequencyBuckets: Int,
            transformedDataBuffer: FloatArray
        ): FloatArray?

        /**
         * Calculate the FFT window size and overlap we are going to use, based on user settings
         * and screen factors.
         */
        fun calculateFftParameters(
            settings: Settings,
            screenFactors: ScreenFactors,
            sampleRate: Int
        ) : FftParameters {

            var fftWindowSamples: Int = settings.nFft
            if (fftWindowSamples == Settings.NFftOptions.NFFT_AUTO.value) {
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
                fftWindowSamples = Settings.coerceNFft(calculatedWindowSamples)
            }

            /*
                Now we know what FFT window size we are going to use, we can figure out
                what window overlap we want.
            */

            var overlapPercentage: Int = settings.fftOverlapPercent

            if (Settings.isAutoOverlap(settings.fftOverlapPercent)) {

                /*
                    Find a window overlap size that gives us no more than half a data point per
                    screen Dp:
                 */

                val fftWindowTime: Float = fftWindowSamples.toFloat() / sampleRate
                val fftWindowPixels: Float = screenFactors.pixelsPerSecond * fftWindowTime
                val multiplier: Float = 2f / fftWindowPixels
                val calculatedOverlapPercentage = 100f / multiplier
                val maxOverlap: Float =
                    if (settings.fftOverlapPercent == Settings.FftOverlapOptions.OVERLAP_AUTO75.value)
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

    /**
     * A raw data buffer and its associated range of indexes which are
     * have been assigned. A null value means the range is not yet known.
     */
    data class RangedRawDataBuffer(val buffer: ShortArray, var assignedRange: HORange? = null) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as RangedRawDataBuffer

            if (!buffer.contentEquals(other.buffer)) return false
            if (assignedRange != other.assignedRange) return false

            return true
        }

        override fun hashCode(): Int {
            var result = buffer.contentHashCode()
            result = 31 * result + (assignedRange?.hashCode() ?: 0)
            return result
        }

        fun reset() {
            buffer.fill(0)
            buffer[buffer.size - 1] = CANARY_VALUE.toShort()
            if (assignedRange != null)
                assignedRange = HORange(0, 0)
        }
    }

    data class PipelineData(
        val calcs: CalculatedParams,
        val dataSourceStep: DataSourceStep,
        val transformStep: TransformStep,
        val colourMapStep: ColourMapStep,
        val rangedRawDataBuffer: RangedRawDataBuffer,
        val transformedDataBuffer: FloatArray,
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
            if (!transformedDataBuffer.contentEquals(other.transformedDataBuffer)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = calcs.hashCode()
            result = 31 * result + dataSourceStep.hashCode()
            result = 31 * result + transformStep.hashCode()
            result = 31 * result + colourMapStep.hashCode()
            result = 31 * result + rangedRawDataBuffer.hashCode()
            result = 31 * result + transformedDataBuffer.contentHashCode()
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
    private var cachedRawDataBuffer: RangedRawDataBuffer? = null

    // Synchronize access to data members:
    protected val mutex = Mutex()

    private val logTag = this::class.simpleName

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
        val pld = pipelineData
        if (pld != null) {
            pld.rangedRawDataBuffer.reset()
            // Reset to the value that corresponds to the start of the colour map:
            pld.transformedDataBuffer.fill(dbRangeMax.start)
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
            internalFullExecute(fftParameters, rawPageRange, amplitudeSizeDp, doRender)
        }
    }

    private suspend fun internalFullExecute(
        fftParameters: FftParameters,
        rawPageRange: HORange? = null,
        amplitudeSizeDp: DpSize? = null,
        doRender: Boolean,
    ) {
        if (BuildConfig.DEBUG)
            Log.d(logTag, "internalExecute called")

        // Slight hack if no amplitude pane:
        val dummyAmplitudeSize = DpSize(100.dp, 100.dp)

        // Shut down any existing pipeline without updating the
        // UI, to avoid momentary blanking of the screen.
        internalShutdown(updateUI = false)

        if (BuildConfig.DEBUG)
            Log.d(logTag, "internalExecute calling setupPipeline")
        pipelineData = setupPipeline(
            fftParameters,
            rawPageRange,
            amplitudeSizeDp ?: dummyAmplitudeSize
        )
        pipelineData?.let {
            startPipeline(it)
            if (doRender) {
                it.dataSourceStep.fullRender()
            }
        }
    }

    /**
     * Render the raw data slice whose range is supplied.
     */
    open suspend fun sliceRender(sliceRange: HORange, transformedEntryIndex: Int) {
        mutex.withLock {
            pipelineData?.dataSourceStep?.sliceRender(sliceRange, transformedEntryIndex)
        }
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
            val mapStep = pipelineData?.colourMapStep
            mapStep?.let {
                val params = it.params
                if (params != null) {
                    if (BuildConfig.DEBUG)
                        Log.d(logTag, "applyBnC: $range is being set")
                    val newParams =
                        ColourMapStep.Params(params.calcs, bnCRangeLogical = range)
                    it.params = newParams

                    pipelineData?.let {
                        it.colourMapStep.fullRender()
                        spectrogramBitmapHolder.signalUpdate()
                        amplitudeBitmapHolder.signalUpdate()
                    }
                }
            }
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
            val assignedTimeRange = pipelineData?.transformStep?.dataAssignedRange
            // Log.d(logTag, "JM: transformed assignedTimeRange = $assignedTimeRange")
            if (assignedTimeRange != null) {
                val clippedXIndexRange = Pair(
                    maxOf(xIndexRange.first, assignedTimeRange.first),
                    minOf(xIndexRange.second, assignedTimeRange.second)
                )
                // Log.d(logTag, "JM: clippedXIndexRange = $clippedXIndexRange")
                if (clippedXIndexRange.second > clippedXIndexRange.first) {
                    val rangeFromData = findBnCRange(
                        clippedXIndexRange.first, clippedXIndexRange.second,
                        yIndexRange.first, yIndexRange.second,
                        calcs.transformedFrequencyBucketCount,
                        pd.transformedDataBuffer
                    )
                    if (rangeFromData != null)
                        range = rangeFromData.copyOf()
                }
            }

            // Default BnC range to use if there is no data visible:
            var floatRange = ColourMapStep.dbRangeMax

            /*
             * Subjectively, it's nice if the bottom part of the data dB range is black, as it is noise
             * and nothing of interest. For now we use a fixed percentage of the range. An improvement
             * would be to find the dB value of highest frequency and use that as threshold.
             */

            if (range != null) {
                val lower = maxOf(ColourMapStep.dbRangeMax.start, range[0])
                val diff = range[1] - lower
                val blackRange = diff * 0.25f
                floatRange = FloatRange(lower + blackRange, range[1])
            }
            if (BuildConfig.DEBUG)
                Log.d(logTag, "auto BnC range in visible region is $floatRange")

            return floatRange
        }
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

        try {
            // Calculate everything we need to know to set up the pipeline:
            val calcs: CalculatedParams =
                doCalculations(model.settings, sampleRate, sampleCount, fftParameters, rawPageRange)

            // Update the UI with details of the transform:
            mutableDetailsTextFlow.value = "%.1fs at %d kHz\nFFT window %d, overlap %d".format(
                calcs.rawTotalDataLength.toFloat() / calcs.rawSampleRate,
                (calcs.rawSampleRate / 1000f).toInt(),
                calcs.fftWindowSize, calcs.fftOverlap
            )

            if (BuildConfig.DEBUG)
                Log.d(logTag, "Calculations: fftWindowSize = ${calcs.fftWindowSize}, " +
                    "fftWindowSize = ${calcs.fftOverlap}, " +
                    "rawSliceEntries = ${calcs.rawSliceEntries}, " +
                    "slice time = ${calcs.rawSliceEntries * 1000 / calcs.rawSampleRate} ms"
                )

            /**
             * Allocate buffers used to share data between steps. These buffers are
             * sized to accommodate the entire data range corresponding to the
             * maximum file time window.
             */
            /**
             * Allocate buffers used to share data between steps. These buffers are
             * sized to accommodate the entire data range corresponding to the
             * maximum file time window.
             */

            // Buffer for raw data read from the data file:
            var rangedRawDataBuffer: RangedRawDataBuffer? = null
            val sizeRequired = calcs.rawPagedDataLength + CANARY_ENTRIES
            val crwb = cachedRawDataBuffer
            if (preserveRawDataBuffer && crwb != null && crwb.buffer.size == sizeRequired) {
                if (BuildConfig.DEBUG)
                    Log.d(logTag, "reusing the raw data buffer: assignedRange = ${cachedRawDataBuffer?.assignedRange}")
                rangedRawDataBuffer = cachedRawDataBuffer
            }
            else {
                rangedRawDataBuffer = RangedRawDataBuffer(ShortArray(sizeRequired))
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
            val transformedDataBuffer = FloatArray(transformedDataBufferSize)
            // Initialize to the value of the lowest end of the colour map:
            transformedDataBuffer.fill(dbRangeMax.start)

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

            // Create a step to map the transformed data (spectral intensities) to colours:
            val colourMapStep =
                ColourMapStep(transformedDataBuffer, spectrogramBitmap, model.colourMapSize, model.settings)
            // Use the existing BnC range, so this is preserved when a new file is loaded:
            colourMapStep.params =
                ColourMapStep.Params(calcs = calcs, bnCRangeLogical = model.bnCRangeFlow.value)

            // Create a step to populate the raw data buffer from the data file:
            val transformStep = TransformStep(model,
                colourMapStep, rangedRawDataBuffer.buffer,
                transformedDataBuffer,
                amplitudeBitmapHolder,
                onTrigger
            )
            val p = TransformStep.Params(calcs = calcs)
            if (BuildConfig.DEBUG)
                Log.d(
                    logTag,
                    "assigning transformStep.params with ${p.calcs.fftWindowSize}"
                )
            transformStep.params = p

            // Create a step to populate the raw data buffer from the data file:
            val dataSourceStep = createDataSourceStep(this, transformStep, rangedRawDataBuffer)
            dataSourceStep.params = DataSourceStep.Params(calcs = calcs)

            val pld = PipelineData(
                calcs = calcs,
                rangedRawDataBuffer = rangedRawDataBuffer,
                dataSourceStep = dataSourceStep,
                transformedDataBuffer = transformedDataBuffer,
                transformStep = transformStep,
                colourMapStep = colourMapStep
            )
            pipelineData = pld
            return pld
        } catch (e: Exception) {
            shutdown()
            throw e
        }
    }

    /**
     * Calculate FFT parameters taking into account Settings and
     * assuming that the initial axis ranges is a full view of the first window into
     * the data.
     */
    suspend fun getDefaultFftParameters(
        //wfi: WavFileReader.WavFileInfo,
        sampleRate: Int,
        canvasSize: DpSize
    ): FftParameters {
        mutex.withLock {
            val maxRawDataCount = (model.settings.dataPageIntervalS * sampleRate).toInt()
            val rawDataCount: Int = minOf(sampleCount, maxRawDataCount)
            // val xAxisSpan = rawDataCount.toFloat() / sampleRate

            val yAxisSpan = sampleRate / 2.0f
            val screenFactors = calcScreenFactors(
                canvasSize, rawDataCount.toFloat() / sampleRate,
                yAxisSpan
            )

            val fftParameters = calculateFftParameters(model.settings, screenFactors, sampleRate)

            return fftParameters
        }
    }

    private fun doCalculations(
        settings: Settings,
        sampleRate: Int,
        sampleCount: Int,
        fftParameters: FftParameters,
        theRawPageRange: HORange?
    ): CalculatedParams {

        // Limit the raw data buffer size to the maximum file window configured in
        // settings:
        val rawSamplesPerInterval = (settings.dataPageIntervalS * sampleRate).toInt()
        val rawPageRange = theRawPageRange ?: HORange(0, minOf(sampleCount, rawSamplesPerInterval))
        val rawPageDataCount = rawPageRange.second - rawPageRange.first

        // Use calculated FFT parameters values rather values from settings:
        val nFft = fftParameters.windowSamples
        val halfNFft = nFft / 2      // nFft is always even, so no rounding occurs.

        val fftOverlapCount = fftParameters.windowOverlap
        var fftStride = (nFft - fftOverlapCount)
        fftStride = fftStride.coerceIn(1, nFft)

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
        // many times. This value determines the UI update granularity.
        val nominalSliceEntries = 10000 // 20000

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
            rawOffsetToPage = rawPageRange.first,
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
     *
     * Calculate the axis ranges corresponding to the logical visible ranges
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

                // Log.d(logTag, "updateAxisRanges setting x axis range to $xAxisMin, $xAxisMax, xDataRange.start = ${xDataRange.start}")
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
                calculateFftParameters(settings, screenFactors, mySampleRate)
            else
                null
        }
    }

    data class PagingData(
        val rawTotalDataLength: Int,
        val rawSampleRate: Int,
    )

    suspend fun getPagingData(): PagingData? {
        var pagingData: PagingData? = null
        mutex.withLock {
            pipelineData?.let {
                pagingData = PagingData(
                    rawTotalDataLength = it.calcs.rawTotalDataLength,
                    rawSampleRate = it.calcs.rawSampleRate,
                )
            }
        }
        return pagingData
    }
}