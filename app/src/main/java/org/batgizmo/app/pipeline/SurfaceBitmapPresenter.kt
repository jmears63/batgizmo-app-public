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

/**
 * Last-mile spectrogram blit: full [Bitmap] + windowing rects → [SurfaceHolder].
 *
 * [CanvasSurfaceBitmapPresenter] is the existing hardware-canvas path.
 * [GlesSurfaceBitmapPresenter] is the parallel GLES path (fill in later).
 *
 * Flip [SpectrogramSurfacePresenters.USE_GLES] to select the implementation.
 * Do not mix canvas lock and EGL on the same surface in one session — the
 * chosen presenter owns the surface exclusively.
 */
interface SurfaceBitmapPresenter {
    fun onSurfaceCreated(holder: SurfaceHolder)
    fun onSurfaceChanged(holder: SurfaceHolder, width: Int, height: Int)
    fun onSurfaceDestroyed(holder: SurfaceHolder)

    /**
     * Called on the spectrogram [DrawThread] while the caller holds the
     * bitmap-holder lock.
     *
     * @param bitmap null clears the surface to black; [src]/[dst] ignored
     * @param src window into [bitmap] (from [DrawThread.calculateImageMapping])
     * @param dst destination in surface pixels (may extend past the surface;
     *   the presenter clips)
     */
    fun present(
        holder: SurfaceHolder,
        bitmap: Bitmap?,
        src: Rect,
        dst: Rect,
        paint: Paint,
    )
}

/** Factory + switch for spectrogram surface presenters. */
object SpectrogramSurfacePresenters {
    /**
     * When false (default): [CanvasSurfaceBitmapPresenter].
     * When true: [GlesSurfaceBitmapPresenter] (stub until GLES is filled in).
     */
    const val USE_GLES = false

    fun create(): SurfaceBitmapPresenter =
        if (USE_GLES) GlesSurfaceBitmapPresenter()
        else CanvasSurfaceBitmapPresenter()
}
