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

import android.util.Log
import org.batgizmo.app.FloatRange
import org.batgizmo.app.HORange
import uk.org.gimell.batgimzoapp.BuildConfig

/**
 * The file viewer reads enough data from from the input file to fully populate the
 * raw data cache in the next step.
 *
 * It reads a window of file data into raw data buffer to fill the buffer if possible. The
 * window can start from any position in the raw data stream.
 */
abstract class DataSourceStep(
    protected val nextStep: AbstractStep,
    protected val rangedRawDataBuffer: AbstractPipeline.RangedRawDataBuffer
) : AbstractStep() {

    // This data class is immutable so special thread safety support is required.
    data class Params(
        val calcs: AbstractPipeline.CalculatedParams
    )

    var params: Params? = null

    private val logTag = this::class.simpleName

    /**
     * Get a safe copy of params or throw an exception.
     */
    protected fun getSafeParams(): Params {
        val p = params
        require(p != null) { "Params must be set before trigger is called." }
        return p
    }

    /**
     * Called from the UI thread.
     *
     * Get the axis ranges corresponding to the full range of the data read from the dat
     * file, used to populate the transformed data buffer.
     */
    fun getMaxAxisRanges(): Pair<FloatRange, FloatRange> {
        val safeParams = getSafeParams()
        val calcs = safeParams.calcs

        if (BuildConfig.DEBUG)
            Log.d(this::class.simpleName, "getMaxAxisRanges calcs.rawOffsetToPage = ${calcs.rawOffsetToPage}")

        val halfFftWindow = calcs.fftWindowSize / 2
        var xAxisMin = (calcs.rawOffsetToPage + halfFftWindow) * calcs.rawTimeInterval
        var xAxisMax = (calcs.rawOffsetToPage + calcs.rawPagedDataLength - halfFftWindow) * calcs.rawTimeInterval

        val yAxisMin = 0f
        val yAxisMax = calcs.transformedFrequencyInterval * calcs.transformedFrequencyBucketCount

        return Pair(FloatRange(xAxisMin, xAxisMax), FloatRange(yAxisMin, yAxisMax))
    }

    override fun fullRender() {
        val safeParams = getSafeParams()

        /**
         * Generate a series of slices to read the entire file window.
         * The slices are relative to the file window.
         */
        val calcs = safeParams.calcs
        val sliceSize = calcs.rawSliceEntries

        if (BuildConfig.DEBUG)
            Log.d(this::class.simpleName, "fullRender start")

        /**
         * Round up the number of slices to process, to include any final partial slice. This
         * means we don't miss out the end of the data. It also means we need to handle the case
         * that a slice may be shorter than the usual slice length.
         */
        val numSlices = (calcs.transformedTimeBucketCount / calcs.sliceTransformedTimeBucketCount)
            .toInt() + 1

        // We will tell the transform step the starting location to write its result:
        var transformedEntryIndex = 0
        val assignedDataRange = rangedRawDataBuffer.assignedRange
        // Log.d(logTag, "JM: assignedDataRange = $assignedDataRange")

        var relativeStart = 0
        for (i in 0 until numSlices) {

            // Start and end are relative to the slice:
            var relativeEnd = relativeStart + sliceSize

            // Usually the last slice will overspill the end of the page:
            relativeEnd = minOf(relativeEnd, calcs.rawPagedDataLength)

            // Clip the data range if required to limit it to the range of
            // raw data that has actually been populated:
            val clippedDataRange = if (assignedDataRange != null) {
                HORange(maxOf(assignedDataRange.first, relativeStart),
                    minOf(assignedDataRange.second, relativeEnd))
            }
            else
                HORange(relativeStart, relativeEnd)

            // If there is anything left to render, do so:
            if (clippedDataRange.second > clippedDataRange.first) {
                // Log.d(logTag, "JM: rendering raw data slice $clippedDataRange")
                sliceRender(clippedDataRange, transformedEntryIndex)
            }

            // Increment this even if we didn't actually render the slice:
            transformedEntryIndex += calcs.sliceTransformedTimeBucketCount

            // Allow an overlap between slices to maintain strides between
            // slices:
            relativeStart += (sliceSize - calcs.rawSliceOverlap)

            if (relativeStart >= calcs.rawTotalDataLength)
                break   // EOF
        }
    }
}