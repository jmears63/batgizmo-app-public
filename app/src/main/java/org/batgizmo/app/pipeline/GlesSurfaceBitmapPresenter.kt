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
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import timber.log.Timber

/**
 * Parallel GLES spectrogram path (stub).
 *
 * Intended work:
 * - EGL window surface from [SurfaceHolder.getSurface] (create/make current on the
 *   draw thread that calls [present], not the UI thread)
 * - Upload RGB_565 [bitmap] as a GL texture
 * - Draw a textured quad; map [src]→UVs and [dst]→clip/viewport
 * - eglSwapBuffers instead of unlockCanvasAndPost
 *
 * Enable via [SpectrogramSurfacePresenters.USE_GLES]. Until implemented, [present]
 * is a no-op so the canvas path remains the safe default when the flag is false.
 */
class GlesSurfaceBitmapPresenter : SurfaceBitmapPresenter {

    private var loggedStub = false

    override fun onSurfaceCreated(holder: SurfaceHolder) {
        // TODO: store holder; defer EGL create until first [present] on draw thread.
    }

    override fun onSurfaceChanged(holder: SurfaceHolder, width: Int, height: Int) {
        // TODO: update viewport / recreate window surface if required.
    }

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {
        // TODO: tear down EGL (prefer on the draw thread before it exits).
    }

    override fun present(
        holder: SurfaceHolder,
        bitmap: Bitmap?,
        src: Rect,
        dst: Rect,
        paint: Paint,
    ) {
        if (!loggedStub) {
            loggedStub = true
            Timber.w(
                "GlesSurfaceBitmapPresenter.present is a stub — " +
                    "flip SpectrogramSurfacePresenters.USE_GLES only after GLES is implemented"
            )
        }
        // TODO: EGL make current, upload texture, draw quad, swap.
    }
}
