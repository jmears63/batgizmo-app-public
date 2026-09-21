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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Existing spectrogram path: [SurfaceHolder.lockHardwareCanvas] + [Canvas.drawBitmap].
 */
class CanvasSurfaceBitmapPresenter : SurfaceBitmapPresenter {

    override fun onSurfaceCreated(holder: SurfaceHolder) {}

    override fun onSurfaceChanged(holder: SurfaceHolder, width: Int, height: Int) {}

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {}

    override fun present(
        holder: SurfaceHolder,
        bitmap: Bitmap?,
        src: Rect,
        dst: Rect,
        paint: Paint,
    ) {
        var canvas = holder.lockHardwareCanvas() ?: return
        try {
            if (bitmap == null) {
                canvas.drawColor(Color.Black.toArgb())
            } else {
                canvas.drawBitmap(bitmap, src, dst, paint)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}
