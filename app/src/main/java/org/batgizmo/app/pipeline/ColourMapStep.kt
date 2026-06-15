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

import android.graphics.Bitmap
import org.batgizmo.app.FloatRange
import org.batgizmo.app.HORange
import org.batgizmo.app.Settings

class ColourMapStep(
    private val rangedTransformedDataBuffer: AbstractPipeline.RangedFloatDataBuffer,
    private val bitmap: Bitmap,
    private val noiseBaselineHolder: AbstractPipeline.NoiseBaselineHolder,
    private val colourMapSize: Int?,
    private val settings: Settings
) : AbstractStep() {

    companion object {

        private external fun doColourMapping(
            first: Int,
            second: Int,
            transformedDataBuffer: FloatArray,
            noiseBaselineDb: FloatArray?,
            transformedTimeBucketCount: Int,
            transformedFrequencyBucketCount: Int,
            bitmap: Bitmap,
            offset: Float,
            multiplier: Float
        ): Int

        /** The dB range supported by BnC corresponding to the logical range 0f..1f. */
        val dbRangeMax = FloatRange(-30f, 100f)

        fun bnCRangeDbToLogical(bnCdBRange: FloatRange): FloatRange {
            val minLogical = ((bnCdBRange.start - dbRangeMax.start) / dbRangeMax.difference()).coerceIn(0f, 1f)
            val maxLogical = ((bnCdBRange.endInclusive - dbRangeMax.start) / dbRangeMax.difference()).coerceIn(0f, 1f)
            return FloatRange(minLogical, maxLogical)
        }
    }

    // This data class is immutable so no special thread safety is required.
    data class Params(
        val calcs: AbstractPipeline.CalculatedParams,
        val bnCRangeLogical: FloatRange,
    )

    var params: Params? = null
        set(value) {
            field = value
            onParamsSet()
        }

    /**
     * Called when the params are set - this function does all the one off initialisation
     * that this class needs, ready to processes slices.
     */
    private fun onParamsSet() {
    }

    /**
     * Make sure underlying handles and resources are closed:
     */
    override suspend fun shutdown() {
    }

    override suspend fun resetState() {
    }

    override fun sliceRender(sliceRange: HORange, transformedEntryIndex: Int) {
        val safeParams = getSafeParams()
        val calcs = safeParams.calcs

        val (offset, multiplier) = calculate(safeParams.bnCRangeLogical)

        /*
         * Note: sliceRange is in transformed time bucket indices. We therefore need to
         * multiply it by the number of frequency buckets to get the buffer index.
         */

        // The bitmap is also accessed by the rendering thread:
        synchronized (bitmap) {

            // Keep track of the contiguous range of raw data that we have populated:
            rangedTransformedDataBuffer.update(sliceRange)

            // Do the actual colour mapping:
            val rc = doColourMapping(
                sliceRange.start, sliceRange.exclusiveEnd,
                rangedTransformedDataBuffer.buffer,
                noiseBaselineHolder.noiseBaselineDb,
                calcs.transformedTimeBucketCount,
                calcs.transformedFrequencyBucketCount,
                bitmap,
                offset, multiplier
            )
            require(rc >= 0) { "doColourMapping failed: rc = $rc" }
        }
    }

    /**
     * Get the offset and multiplier that will result in the normalized brightness
     * and contrast range supplied.
     *
     * The normalized BnC range is 0..1 corresponding to the dbLimits.
     */
    private fun calculate(bnCLogicalRange: FloatRange): Pair<Float, Float> {
        require(colourMapSize != null) { "the colour map must be loaded before it can be used" }

        /**
         * The dB values from the transform pipeline step that map to
         * the logical BnC range 0f..1f.
         */
        val dbSpan = dbRangeMax.endInclusive - dbRangeMax.start

        // Convert the logical range to a dB range:
        val dBRange = FloatRange(
            dbRangeMax.start + bnCLogicalRange.start * dbSpan,
            dbRangeMax.start + bnCLogicalRange.endInclusive * dbSpan
        )

        // We need the dB range to map to colour map indices:
        val offset: Float = dBRange.start
        val multiplier: Float = colourMapSize.toFloat() / (dBRange.endInclusive - offset)

        return Pair(offset, multiplier)
    }

    private fun getSafeParams(): Params {
        val p = params
        require(p != null) { "params must be set first" }
        return p
    }

    /**
     * Call this method from a worker thread.
     *
     * Fully re-calculate and re-render the spectrogram from this step onwards,
     * typically because there is a change to BnC.
     */
    override fun fullRender() {
        val safeParams = getSafeParams()

        /**
         * Generate a series of slices to read the entire page.
         * The slices are relative to the file window.
         */
        val calcs = safeParams.calcs
        val sliceSize = calcs.sliceTransformedTimeBucketCount
        var start = 0

        /**
         * Round up the number of slices to process, to include any final partial slice. This
         * means we don't miss out the end of the data. It also means we need to handle the case
         * that a slice may be shorter than the usual slice length.
         *
         * Limit the range we render to the range of assigned data.
         */

        var numTimeEntries = 0
        rangedTransformedDataBuffer.assignedRange?.let {
            // Assumption: the range starts at 0:
            numTimeEntries = minOf(it.exclusiveEnd, calcs.transformedTimeBucketCount)
        }
        val numSlices = (numTimeEntries / calcs.sliceTransformedTimeBucketCount)
            .toInt() + 1

        for (i in 0 until numSlices) {
            var end = start + sliceSize

            // The final slice may extends beyond the end of the data available:
            end = minOf(end, numTimeEntries)
            if (end > start)
                sliceRender(HORange(start, end))

            start += sliceSize
        }
    }
}