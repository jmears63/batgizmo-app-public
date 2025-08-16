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

import android.graphics.Bitmap
import android.util.Log
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.HORange
import org.batgizmo.app.UIModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round

/**
 * This step performs an SFFT on the raw data and calculates the square magnitude of the
 * resulting spectral data.
 */
class TransformStep(
    private val model: UIModel,
    private val nextStep: AbstractStep,
    private val rawDataBuffer: ShortArray,
    private val transformedDataBuffer: FloatArray,
    private val amplitudeBitmapHolder: BitmapHolder,
    private val onTrigger: () -> Unit
) : AbstractStep() {

    companion object {
        /**
         * Prepare to do FFTs. This allocates buffers in the native layer that
         * must be freed in due course by calling cleanupFft.
         *
         * Return -1 if it didn't work out, otherwise 0.
         */
        private external fun initFft(fftWindowSize: Int): Int

        /**
         * Do a series of SFFTs. The input buffer contains the raw data, with windows
         * unwrapped. The output buffer is populated with the SFFT of each input window.
         *
         * minDB is the minimum dB range supported by BnC, which will be used to avoid
         * attempting log(0).
         *
         * Return the number of windows processed, or -1 if it didn't work out.
         */
        private external fun doFft(
            numWindows: Int,
            sliceBuffer: FloatArray,
            transformedDataBuffer: FloatArray,
            transformedBufferIndex: Int,
            minDB: Float,
            triggerFlagBuffer: IntArray,
            minTriggerBucket: Int,
            maxTriggerBucket: Int,
            autoTriggerThresholdDb: Float
        ): Int

        /**
         * Do amplitude calculations. For each FFT window unwrapped in the slice buffer
         * calculate the range of raw data values, and draw that range as a vertical line
         * in the output bitmap.
         *
         * Return the number of windows processed, or -1 if it didn't work out.
         */
        private external fun doAmplitude(
            numWindows: Int,
            fftWindowSize: Int,
            sliceBuffer: FloatArray,
            transformedBufferIndex: Int,
            transformedBufferTimeSize: Int,
            transformedSliceTimeSize: Int,
            bitmap: Bitmap
        ): Int

        /**
         * Clean up resources allocated by initFft.
         */
        private external fun cleanupFft()

        /**
         * Unwrap windows for SFFT from the raw data buffer, applying the window supplied.
         */
        private external fun unwrapSlices(
            rawDataBuffer: ShortArray,
            rawDataEntries: Int,
            startIndex: Int,
            windowCount: Int,
            fftStride: Int,
            window: FloatArray,
            fftWindowSize: Int,
            inputSliceBuffer: FloatArray
        ): Int

        // Used to synchronize native layer access:
        private val dummySyncObject = String.toString()
    }

    private val logTag = this::class.simpleName

    // This data class is immutable so no special thread safety is needed.
    data class Params(
        val calcs: AbstractPipeline.CalculatedParams
    )

    var params: Params? = null
        set(value) {
            field = value
            initializeStep()
        }

    // This data class is immutable so no special thread safety is needed.
    data class StepData(
        val fftWindow: FloatArray,
        val inputSliceBuffer: FloatArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as StepData

            if (!fftWindow.contentEquals(other.fftWindow)) return false
            if (!inputSliceBuffer.contentEquals(other.inputSliceBuffer)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = fftWindow.contentHashCode()
            result = 31 * result + inputSliceBuffer.contentHashCode()
            return result
        }
    }

    private var stepData: StepData? = null

    private var initFftWindow = 0

    // Array to record if the trigger threshold was exceeded:
    private val triggerResultBuffer = IntArray(1)


    // Range of transformedDataBuffer indexes that have been assigned values, in
    // time indexes (not buffer indexes), exposed read only:
    private var _dataAssignedRange: HORange? = null
    val dataAssignedRange: HORange?
        get() = _dataAssignedRange

    /**
     * Called when the params are set - this function does all the one off initialisation
     * that this class needs, ready to processes slices.
     */
    private fun initializeStep() {
        val safeParams = getSafeParams()
        val fftWindow = createHannWindow(safeParams.calcs.fftWindowSize)
        val calcs = safeParams.calcs

        // Buffer that holds SFFT input data:
        val unwrappedRawDataEntries = calcs.sliceTransformedTimeBucketCount * calcs.fftWindowSize
        val inputSliceBuffer = FloatArray(unwrappedRawDataEntries + 1)   // +1 for canary.

        // TODO: revisit use of synchronized:
        synchronized(dummySyncObject) {
            initFftWindow = calcs.fftWindowSize
            val rc = initFft(calcs.fftWindowSize)
            require(rc != -1) { "initFft failed" }

            _dataAssignedRange = null
        }

        stepData = StepData(
            fftWindow = fftWindow,
            inputSliceBuffer = inputSliceBuffer
        )
    }

    /**
     * Make sure underlying handles and resources are closed:
     */
    override suspend fun shutdown() {
        synchronized(dummySyncObject) {
            cleanupFft()
        }
    }

    override suspend fun resetState() {
        _dataAssignedRange = null
    }

    override fun sliceRender(sliceRange: HORange, transformedEntryIndex: Int) {
        val safeParams = getSafeParams()
        val safeStepData = getSafeStepData()
        val calcs = safeParams.calcs

        /**
         * Do SFFT on the raw data slice whose range is provided. The result will
         * be populated into the transformed data buffer at positions
         * corresponding to the source range in the raw data buffer.
         *
         * We use Float rather than int to simplify handling of overflow and preservation of
         * resolution. Performance of the FFT is similar. ARM has single cycle multiply for
         * Float and Int, so the only overhead is the conversion to and from Float, which again
         * is single cycle.
         */

        val windowData = safeStepData.fftWindow

        /**
         * Calculate the actual number of windows we will calculate.
         * The last slice is usually shorter than the others, so we have to use the actual number
         * rather than the maximum as pre calculated.
         */
        val windowCount =
            if (sliceRange.second - sliceRange.first < calcs.fftWindowSize)
                0
            else {
                ((sliceRange.second - sliceRange.first) - calcs.fftWindowSize) / calcs.fftStride + 1; }
        require(windowCount <= calcs.sliceTransformedTimeBucketCount) { "Internal error: two many windows to transform" }

        if (windowCount > 0) {

            if (calcs.fftWindowSize != initFftWindow) {
                Log.e(logTag, "FFT window size mismatch. Race condition? $windowCount != $initFftWindow")
            }

            /**
             * Unwrap the FFT windows and apply the window function. This is in C
             * because it was excruciatingly slow in Kotlin. We ignore any data remaining
             * at the end of the input buffer, which should be less than a window's worth.
             */
            val rc1 = unwrapSlices(
                rawDataBuffer, calcs.rawPagedDataLength, sliceRange.first,
                windowCount, calcs.fftStride,
                windowData, calcs.fftWindowSize,
                safeStepData.inputSliceBuffer
            )
            require(rc1 >= 0) { "unwrapSlices failed" }

            /**
             * Do the actual FFT.
             */

            // Map the trigger frequency range from settings to a range of frequency buckets:
            var minTriggerBucket = round(model.settings.autoTriggerRangeMinkHz * 1000 / calcs.transformedFrequencyInterval).toInt()
            minTriggerBucket = minTriggerBucket.coerceIn(0, calcs.transformedFrequencyBucketCount - 1)
            var maxTriggerBucket = round(model.settings.autoTriggerRangeMaxkHz * 1000 / calcs.transformedFrequencyInterval).toInt()
            maxTriggerBucket = maxTriggerBucket.coerceIn(0, calcs.transformedFrequencyBucketCount - 1)
            val autoTriggerThresholdDb = model.settings.autoTriggerThresholdDb

            synchronized(dummySyncObject) {

                val rc2 = doFft(
                    windowCount, safeStepData.inputSliceBuffer,
                    transformedDataBuffer,
                    transformedEntryIndex * calcs.transformedFrequencyBucketCount,
                    ColourMapStep.dbRangeMax.start,
                    triggerResultBuffer,
                    minTriggerBucket, maxTriggerBucket,
                    autoTriggerThresholdDb
                )

                synchronized(amplitudeBitmapHolder) {

                    require(amplitudeBitmapHolder.bitmap != null) {
                        "Internal error, amplitude bitmap has not been allocated"
                    }

                    val rc3 = doAmplitude(
                        windowCount, calcs.fftWindowSize,
                        safeStepData.inputSliceBuffer,
                        transformedEntryIndex,
                        calcs.transformedTimeBucketCount,
                        calcs.sliceTransformedTimeBucketCount,
                        amplitudeBitmapHolder.bitmap!!
                    )

                    amplitudeBitmapHolder.cursorTime =
                        (transformedEntryIndex + calcs.sliceTransformedTimeBucketCount) * calcs.transformedTimeInterval
                }
            }

            if (triggerResultBuffer[0] != 0) {
                // Log.d(logTag, "minTriggerBucket = $minTriggerBucket, maxTriggerBucket = $maxTriggerBucket, autoTriggerThresholdDb = $autoTriggerThresholdDb")

                // Signal that there has been a trigger within this slice.
                onTrigger()
            }

            /**
             * Trigger the next pipeline step. The slice range needs to be
             * in transformed time bucket indices as that is what the next step
             * cares about.
             */
            val nextSliceRange = HORange(transformedEntryIndex, transformedEntryIndex + windowCount)
            // Note the range in the transformed buffer that has been assigned values:
            val dar = _dataAssignedRange
            if (dar == null)
                _dataAssignedRange = nextSliceRange
            else {
                _dataAssignedRange = HORange(
                    minOf(dar.first, nextSliceRange.first),
                    maxOf(dar.second, nextSliceRange.second)
                )
            }
            // Log.d(logTag, "JM: transformed _dataAssignedRange = $_dataAssignedRange")
            if (nextSliceRange.second - nextSliceRange.first > 0)
                nextStep.sliceRender(nextSliceRange)
        }
    }

    private fun getSafeParams(): Params {
        val p = params
        require(p != null) { "params must be set before getSafeParams us called" }
        return p
    }

    private fun getSafeStepData(): StepData {
        require(stepData != null) { "stepData must be set before getSafeStepData us called" }
        return stepData!!
    }

    private fun createHannWindow(length: Int): FloatArray {
        require(length > 0) { "Length must be positive" }
        return FloatArray(length) { i ->
            (0.5 * (1 - cos(2f * PI * i / (length - 1f)))).toFloat()
        }
    }

    /**
     * Call this method from a worker thread.
     */
    override fun fullRender() {
        // Not required currently. This would need to generate slices based on
        // raw data indexes.
    }
}