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

import org.batgizmo.app.HORange
import org.batgizmo.app.WavFileReader

class FileSourceStep(nextStep: AbstractStep,
                     rangedRawDataBuffer: AbstractPipeline.RangedShortDataBuffer,
                     private val wavFileReader: WavFileReader)
    : DataSourceStep(nextStep, rangedRawDataBuffer, false) {

    /**
     * Make sure underlying handles and resources are closed:
     */
    override suspend fun shutdown() {
        // Don't clean up the WavFileReader here, we don't own it.
    }

    override suspend fun resetState() {
    }

    override fun sliceRender(sliceRange: HORange, transformedEntryIndex: Int) {

        // Log.d(this::class.simpleName, "push sliceRange = $sliceRange")
        val safeParams = getSafeParams()
        val calcs = safeParams.calcs

        // The slice is relative to the page:
        val absoluteRange = HORange(sliceRange.start + calcs.rawOffsetToPage,
            minOf(sliceRange.exclusiveEnd + calcs.rawOffsetToPage, calcs.rawTotalDataLength))

        // Log.d(this::class.simpleName, "push absoluteRange = $absoluteRange")

        val bufferFirst = sliceRange.start
        val samplesRead = wavFileReader.readData(absoluteRange,
            rangedRawDataBuffer.buffer, bufferFirst)

        val actualSliceRange = HORange(sliceRange.start, sliceRange.start + samplesRead)

        /*
            // For testing, generate a sine wave:
            for (i in actualSliceRange.first until actualSliceRange.second) {
                // rawDataBuffer[i] = if (i and period == 0) 8192 else -8192
                rawDataBuffer[i] = (10 * sin(i / 19.2f * 2 * PI)).toInt().toShort()
            }
        */

        // Pass on the slice range that was actually read:
        if (actualSliceRange.exclusiveEnd - actualSliceRange.start > 0) {
            // Keep track of the contiguous range of raw data that we have populated:
            rangedRawDataBuffer.update(actualSliceRange)

            nextStep.sliceRender(actualSliceRange, transformedEntryIndex)
        }
    }
}