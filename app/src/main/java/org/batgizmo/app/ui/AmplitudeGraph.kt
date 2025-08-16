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

package org.batgizmo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import org.batgizmo.app.HORange
import org.batgizmo.app.UIModel
import org.batgizmo.app.pipeline.AmplitudeRenderer

class AmplitudeGraph(model: UIModel, rawPageRangeState: MutableState<HORange?>)
    : GraphBase(model, rawPageRangeState,
        model.timeAxisRangeFlow, model.amplitudeAxisRangeFlow, supportCursor = true) {

    init {
        val amplitudeUnits = listOf(AxisBorder.Unit())
        val timeUnits = listOf(AxisBorder.Unit())

        topBorder = BlankBorderHorizontal()
        rightBorder = BlankBorderVertical()
        leftBorder = AxisBorderVertical("", amplitudeUnits,
            showAxis=false, layoutType=AxisBorder.Layout.COMPACT)
        bottomBorder = AxisBorderHorizontal("", timeUnits,
            showAxis=true, layoutType=AxisBorder.Layout.NONE)

        renderer = AmplitudeRenderer(model, this, rawPageRangeState, model.amplitudeBitmapHolder)
    }

    @Composable
    fun Compose(
        modifier: Modifier,
        viewModel: UIModel,
        showGrid: Boolean
    ) {
        ComposeFrame(modifier, showGrid, null)
    }
}