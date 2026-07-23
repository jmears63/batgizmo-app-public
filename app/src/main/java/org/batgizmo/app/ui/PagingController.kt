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

package org.batgizmo.app.ui

import androidx.compose.runtime.MutableState
import org.batgizmo.app.HORange
import org.batgizmo.app.PipelineParameters
import org.batgizmo.app.Settings
import org.batgizmo.app.pipeline.AbstractPipeline
import timber.log.Timber

/**
 * Maintains viewer paging state and updates paging UI enable flags.
 *
 * [pagingData] is constant for a given open file; page length / overlap come from
 * [Settings] and can change when settings are applied.
 */
class PagingController(
    private val pagingData: AbstractPipeline.PagingData,
    settings: Settings,
    private val rawPageRange: MutableState<HORange?>,
    private val pagingEnabled: MutableState<Boolean>,
    private val pageRightEnabled: MutableState<Boolean>,
    private val pageLeftEnabled: MutableState<Boolean>
) {
    private data class Internals(
        val rawPageLength: Int,
        val stride: Int,
        val totalPages: Int,
        var currentPage: Int
    )

    private var internals = calcInternals(pagingData.pipelineParametersSnapshot, settings)

    init {
        updateUI()
    }

    private fun calcInternals(pipelineParameters: PipelineParameters, settings: Settings): Internals {
        val rawPageLength = pipelineParameters.dataPageTimeSpanS * pagingData.rawSampleRate
        val stride =
            (rawPageLength.toFloat() * (1f - settings.pageOverlapPercent.toFloat() / 100f) + 0.5).toInt()
                .coerceIn(1, rawPageLength)

        val totalPages =
            if (pagingData.rawTotalDataLength <= rawPageLength)
                1
            else {
                var count = 1                       // Initial full page.
                val excess = pagingData.rawTotalDataLength - rawPageLength
                val wholeIntermediatePages = excess / stride
                count += wholeIntermediatePages     // What it says.
                if (excess % stride > 0)
                    count += 1                      // Final partial page.
                count
            }

        return Internals(
            rawPageLength = rawPageLength,
            stride = stride,
            totalPages = totalPages,
            currentPage = 0
        )
    }

    fun doPageRight() {
        setPage(internals.currentPage + 1)
    }

    fun doPageLeft() {
        setPage(internals.currentPage - 1)
    }

    private fun updateUI() {
        pagingEnabled.value = internals.totalPages > 1
        pageRightEnabled.value = internals.currentPage < internals.totalPages - 1
        pageLeftEnabled.value = internals.currentPage > 0
    }

    private fun setPage(newPage: Int) {
        var result: HORange? = null

        if (newPage in 0..<internals.totalPages) {

            // The last page has more overlap to avoid spilling off the end:
            val endCorrection = maxOf(
                0,
                (newPage + 1) * internals.stride - pagingData.rawTotalDataLength
            )

            val start = maxOf(newPage * internals.stride - endCorrection, 0)
            var endExclusive = maxOf(start + internals.rawPageLength)
            endExclusive = minOf(
                endExclusive,
                pagingData.rawTotalDataLength
            )    // Don't overrun the end of data.

            if (start >= 0) // Paranoia.
                result = HORange(start, endExclusive)
        }

        if (result != null) {
            Timber.i("Moving to page $newPage starting at ${result.start} length ${result.exclusiveEnd - result.start}")
            internals.currentPage = newPage

            require(result.exclusiveEnd - result.start <= internals.rawPageLength)

            rawPageRange.value = result
        }

        updateUI()
    }

    fun reset(settings: Settings) {
        internals = calcInternals(pagingData.pipelineParametersSnapshot, settings)
        setPage(0)
    }
}
