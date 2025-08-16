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

import android.graphics.Canvas
import android.graphics.Paint
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.HORange
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.ui.GraphBase

class AmplitudeDrawThread(model: UIModel, surfaceHolder: SurfaceHolder, bitmapHolder: BitmapHolder)
    : DrawThread(model, surfaceHolder, bitmapHolder)
{
    private val cursorPaint = Paint().apply {
        color = Color.Yellow.toArgb()
        strokeWidth = 2f
        isAntiAlias = true
    }

    override fun draw(bmPaint: Paint) {
        val bitmap = bitmapHolder.bitmap
        var canvas1: Canvas? = null
        try {
            // The bitmap is also accessed by the pipeline thread:
            synchronized(model.amplitudeBitmapHolder) {
                val canvas = surfaceHolder.lockHardwareCanvas()
                canvas1 = canvas
                if (canvas != null) {
                    if (bitmap != null) {
                        val (expandedSrcRect, expandedDestRect) = calculateImageMapping(
                            bitmap, canvas,
                            model.timeVisibleRangeFlow,
                            model.amplitudeVisibleRangeFlow)

                        // Log.d(this::class.simpleName, "expandedSrcRect = $expandedSrcRect, expandedDestRect = $expandedDestRect")
                        // Copy the data from the source to the screen in one go:
                        canvas.drawBitmap(
                            bitmap,
                            expandedSrcRect,
                            expandedDestRect,
                            bmPaint
                        )

                        bitmapHolder.cursorTime?.let { t ->
                            val x = canvas.width * (t - model.timeAxisRangeFlow.value.start) /
                                    (model.timeAxisRangeFlow.value.endInclusive - model.timeAxisRangeFlow.value.start)
                            if (x >=0 && x < canvas.width )
                                canvas.drawLine(x, 0f, x, (canvas.height - 1).toFloat(), cursorPaint)
                        }
                    }
                    else
                        canvas.drawColor(Color.Black.toArgb())
                }
            }
        } finally {
            if (canvas1 != null) {
                surfaceHolder.unlockCanvasAndPost(canvas1)
            }
        }
    }
}

class AmplitudeSHCallback(
    private var model: UIModel,
    bitmapHolder: BitmapHolder
) : SHCallback(model, bitmapHolder) {
    override fun createThread(model: UIModel, surfaceHolder: SurfaceHolder): DrawThread {
        return AmplitudeDrawThread(model, surfaceHolder, bitmapHolder)
    }
}

class AmplitudeRenderer(
    private val model: UIModel,
    private val graph: GraphBase,
    private val rawPageRangeState: MutableState<HORange?>,
    bitmapHolder: BitmapHolder
) : RendererBase(model, graph, rawPageRangeState, bitmapHolder) {

    @Composable
    override fun Compose(
        modifier: Modifier,
        settings: Settings
    ) {
        Compose(modifier, settings,
            AmplitudeSHCallback(model, model.amplitudeBitmapHolder), Modifier)
    }
}
