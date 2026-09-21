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

import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.HORange
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.ui.GraphBase

class SpectrogramDrawThread(
    model: UIModel,
    surfaceHolder: SurfaceHolder,
    bitmapHolder: BitmapHolder,
    private val presenter: SurfaceBitmapPresenter,
) : DrawThread(model, surfaceHolder, bitmapHolder) {

    private val unusedRect = Rect()

    override fun draw(bmPaint: Paint) {
        // The bitmap is also accessed by the pipeline thread:
        synchronized(model.spectrogramBitmapHolder) {
            val bitmap = bitmapHolder.bitmap
            if (bitmap == null) {
                presenter.present(
                    surfaceHolder,
                    bitmap = null,
                    src = unusedRect,
                    dst = unusedRect,
                    paint = bmPaint,
                )
                return
            }
            val frame = surfaceHolder.surfaceFrame
            val (expandedSrcRect, expandedDestRect) = calculateImageMapping(
                bitmap,
                frame.width(),
                frame.height(),
                model.timeVisibleRangeFlow,
                model.frequencyVisibleRangeFlow,
            )
            presenter.present(
                surfaceHolder,
                bitmap,
                expandedSrcRect,
                expandedDestRect,
                bmPaint,
            )
        }
    }
}

class SpectrogramSHCallback(
    private var model: UIModel,
    bitmapHolder: BitmapHolder,
) : SHCallback(model, bitmapHolder) {

    private val presenter: SurfaceBitmapPresenter = SpectrogramSurfacePresenters.create()

    override fun createThread(model: UIModel, surfaceHolder: SurfaceHolder): DrawThread {
        return SpectrogramDrawThread(model, surfaceHolder, bitmapHolder, presenter)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        presenter.onSurfaceCreated(holder)
        super.surfaceCreated(holder)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        presenter.onSurfaceChanged(holder, width, height)
        super.surfaceChanged(holder, format, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Stop the draw thread before tearing down the presenter (EGL must not
        // be current on another thread while present() could still run).
        super.surfaceDestroyed(holder)
        presenter.onSurfaceDestroyed(holder)
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
        settings: Settings,
    ) {
        Compose(
            modifier,
            settings,
            SpectrogramSHCallback(model, model.spectrogramBitmapHolder),
        )
    }
}
