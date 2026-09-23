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
import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.HORange
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.ui.GraphBase
import timber.log.Timber

class SpectrogramDrawThread(
    model: UIModel,
    surfaceHolder: SurfaceHolder,
    bitmapHolder: BitmapHolder,
    private val presenter: SurfaceBitmapPresenter,
) : DrawThread(model, surfaceHolder, bitmapHolder) {

    private val unusedRect = Rect()
    private val frameStats = SpectrogramFrameStats()

    override fun draw(bmPaint: Paint) {
        /*
         * Lock order (must not invert elsewhere):
         *   1) spectrogramBitmapHolder
         *   2) Bitmap (same object ColourMapStep locks for JNI writes)
         * Never take the holder lock while already holding the Bitmap lock.
         */
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
                frameStats.noteRenderedFrame()
                return
            }
            val frame = surfaceHolder.surfaceFrame
            val vw = frame.width()
            val vh = frame.height()
            if (vw <= 0 || vh <= 0)
                return
            synchronized(bitmap) {
                val (expandedSrcRect, expandedDestRect) = calculateImageMapping(
                    bitmap,
                    vw,
                    vh,
                    model.timeVisibleRangeFlow,
                    model.frequencyVisibleRangeFlow,
                )
                val dirtyColumns = bitmapHolder.takeDirtyColumns(bitmap.width)
                presenter.present(
                    surfaceHolder,
                    bitmap,
                    expandedSrcRect,
                    expandedDestRect,
                    bmPaint,
                    dirtyColumns,
                )
                frameStats.noteRenderedFrame()
            }
        }
    }
}

/**
 * Counts spectrogram presents and logs totals about once per 10 seconds.
 */
private class SpectrogramFrameStats {
    private var totalFrames = 0L
    private var framesThisInterval = 0
    private var windowStartMs = 0L

    fun noteRenderedFrame() {
        totalFrames++
        framesThisInterval++
        val now = SystemClock.elapsedRealtime()
        if (windowStartMs == 0L) {
            windowStartMs = now
            return
        }
        val elapsedMs = now - windowStartMs
        if (elapsedMs >= LOG_INTERVAL_MS) {
            Timber.i(
                "Spectrogram frames: total=$totalFrames last10s=$framesThisInterval"
            )
            framesThisInterval = 0
            windowStartMs = now
        }
    }

    companion object {
        private const val LOG_INTERVAL_MS = 10_000L
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
