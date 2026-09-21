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
import org.batgizmo.app.HORange

/**
 * Last-mile bitmap blit: full [Bitmap] + windowing rects → [SurfaceHolder].
 *
 * Used for spectrogram and amplitude graphs.
 *
 * [CanvasSurfaceBitmapPresenter] is the hardware-canvas path.
 * [GlesSurfaceBitmapPresenter] uploads the bitmap as a GLES texture and draws a quad.
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
     * Called on the graph [DrawThread] while the caller holds the
     * bitmap-holder lock (and usually the [Bitmap] lock for spectrogram).
     *
     * @param bitmap null clears the surface to black; [src]/[dst]/[dirtyColumns] ignored
     * @param src window into [bitmap] (from [DrawThread.calculateImageMapping])
     * @param dst destination in surface pixels (may extend past the surface;
     *   the presenter clips)
     * @param dirtyColumns time-bucket columns that changed since the last present
     *   (from [org.batgizmo.app.BitmapHolder.takeDirtyColumns]); null means no
     *   pixel changes. GLES uploads `dirty ∩ src` when [src] is unchanged, or
     *   rebuilds the visible-window texture when [src] changes. Canvas ignores this.
     * @param cursorX optional vertical cursor in surface pixels (amplitude playback)
     */
    fun present(
        holder: SurfaceHolder,
        bitmap: Bitmap?,
        src: Rect,
        dst: Rect,
        paint: Paint,
        dirtyColumns: HORange? = null,
        cursorX: Float? = null,
    )
}

/** Factory + switch for spectrogram surface presenters. */
object SpectrogramSurfacePresenters {
    /**
     * When false (default): [CanvasSurfaceBitmapPresenter].
     * When true: [GlesSurfaceBitmapPresenter].
     */
    const val USE_GLES = false

    /**
     * Debug: when > 0, [GlesSurfaceBitmapPresenter] treats this as
     * [GLES20.GL_MAX_TEXTURE_SIZE] (after querying the real limit, takes the min).
     * Use `2048` (or similar) to exercise the oversized-visible-window downsample path.
     * Keep `0` for the real GPU limit.
     */
    const val DEBUG_MAX_TEXTURE_SIZE_CAP = 0

    fun create(): SurfaceBitmapPresenter =
        if (USE_GLES) GlesSurfaceBitmapPresenter()
        else CanvasSurfaceBitmapPresenter()
}
