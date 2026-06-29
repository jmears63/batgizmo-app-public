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

import android.graphics.Canvas
import android.graphics.DashPathEffect
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

class SpectrogramDrawThread(
    model: UIModel,
    surfaceHolder: SurfaceHolder,
    bitmapHolder: BitmapHolder
)
    : DrawThread(model, surfaceHolder, bitmapHolder)
{
    private val cursorColour = Color.Yellow
    val lineWidthPx = 2f    // Slight improvement: use dp and convert to px.

    private val cursorPaint = Paint().apply {
        color = cursorColour.toArgb()
        // textSize = with(density) { textHeightDp.toPx() }
        // textAlign = Paint.Align.LEFT
        strokeWidth = lineWidthPx
        pathEffect = DashPathEffect(floatArrayOf(2f, 20f), 0f)
    }

    override fun draw(bmPaint: Paint) {
        val bitmap = bitmapHolder.bitmap
        var canvas1: Canvas? = null
        try {
            // The bitmap is also accessed by the pipeline thread:
            synchronized(model.spectrogramBitmapHolder) {
                val canvas = surfaceHolder.lockHardwareCanvas()
                canvas1 = canvas
                if (canvas != null) {
                    // Record the GPU's real texture-size limit so the pipeline can bound the
                    // spectrogram bitmap to what this device can actually allocate:
                    if (canvas.isHardwareAccelerated)
                        model.noteMaxBitmapDimension(
                            minOf(canvas.maximumBitmapWidth, canvas.maximumBitmapHeight)
                        )

                    if (bitmap == null) {
                        // Blank the display if the bitmap is null:
                        canvas.drawColor(Color.Black.toArgb())
                    } else {
                        val (expandedSrcRect, expandedDestRect) = calculateImageMapping(
                            bitmap, canvas,
                            model.timeVisibleRangeFlow,
                            model.frequencyVisibleRangeFlow)

                        // Log.d(this::class.simpleName, "expandedSrcRect = $expandedSrcRect, expandedDestRect = $expandedDestRect")
                        // Copy the data from the source to the screen in one go:
                        canvas.drawBitmap(
                            bitmap,
                            expandedSrcRect,
                            expandedDestRect,
                            bmPaint
                        )
                    }
                }
            }
        } finally {
            if (canvas1 != null) {
                surfaceHolder.unlockCanvasAndPost(canvas1)
            }
        }
    }
}

class SpectrogramSHCallback(
    private var model: UIModel,
    bitmapHolder: BitmapHolder
) : SHCallback(model, bitmapHolder) {
    override fun createThread(model: UIModel, surfaceHolder: SurfaceHolder): DrawThread {
        return SpectrogramDrawThread(model, surfaceHolder, bitmapHolder)
    }
}

class SpectrogramRenderer(
    private val model: UIModel,
    graph: GraphBase,
    rawPageRangeState: MutableState<HORange?>,
    bitmapHolder: BitmapHolder,
) : RendererBase(model, graph, rawPageRangeState, bitmapHolder) {

    @Composable
    override fun Compose(
        modifier: Modifier,
        settings: Settings
    ) {
        Compose(
            modifier,
            settings,
            SpectrogramSHCallback(model, model.spectrogramBitmapHolder)
        )
    }
}
