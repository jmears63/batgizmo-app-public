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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.StateFlow
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.FloatRange
import org.batgizmo.app.HORange
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.ui.GraphBase
import uk.org.gimell.batgimzoapp.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

abstract class DrawThread(
    protected val model: UIModel,
    protected val surfaceHolder: SurfaceHolder,
    protected val bitmapHolder: BitmapHolder
) : Thread() {
    var running: AtomicBoolean = AtomicBoolean(true)

    private var logTag = this::class.simpleName

    fun terminateThread() {
        running.set(false)
        // Wake up the thread so it can terminate itself:
        bitmapHolder.signalUpdate()
    }

    override fun run() {
        Log.i(this::class.simpleName, "starting DrawThread")

        /**
         * Important - don't cache the screen height or width, as this can change as the screen is
         * rotated. Though in fact I think the thread terminates and restarts in that case, but
         * the principle is sound.
         */

        // Paint for drawing the bitmap:
        val bmPaint = Paint().apply {
            // isFilterBitmap = true // Provides bilinear scaling, avoiding a pixelated effect.
        }

        while (running.get()) {
            // Wait for either bitmap data to update, or a request to terminate this loop:
            bitmapHolder.waitForUpdate()
            if (!running.get())
                break

            // Log.d(this::class.simpleName, "Thread about to call draw.")
            draw(bmPaint)

            if (!running.get())
                break
        }
    }

    protected fun calculateImageMapping(
        bitmap: Bitmap,
        canvas: Canvas,
        xVisibleRangeFlow: StateFlow<FloatRange>,
        yVisibleRangeFlow: StateFlow<FloatRange>,
    ): Pair<Rect, Rect> {
        // Pixels are Float so we can support partial pixels:
        fun logicalToPixels(logical: Float, pixelSize: Int): Float {
            // 0f-1f maps to 0 to pixelSize-1:
            return logical * (pixelSize - 1)
        }

        fun logicalToPixels(
            range: FloatRange,
            pixelSize: Int
        ): Pair<Float, Float> {
            return Pair(
                logicalToPixels(range.start, pixelSize),
                logicalToPixels(range.endInclusive, pixelSize)
            )
        }

        /**
         * It's good to be able to support fractional pixels when the visible
         * area in the source bitmap is small, ie when the zoom factor is high. In that
         * case individual source pixels are visible when rendered to the screen. If
         * we round to the nearest source pixel, resulting rendered image is not quite right,
         * and panning and zooming are jumpy and weird. Therefore, the following scheme
         * is used:
         *
         *  o Both the source bitmap and the screen canvas have two rectangles:
         *      - The visible data area. In the case of the screen that is always
         *      the same size as the canvas. In the case of the source bitmap, it
         *      can have fractional pixels as mentioned above.
         *      - The expanded data area. On the source side, this is the visible
         *      data area rounded outwards to whole numbers of source pixels. On the
         *      screen, it is the mapping of that source expanded area which extends
         *      a little way beyond the canvas pixel range, including to negative
         *      pixels.
         *  o Do calculations in terms of the expanded areas.
         *  o When we come to render to the screen, the destination rectangle
         *      is the expanded destination region. The canvas will clip this
         *      to the visible range. The result is that partial source pixels
         *      are represented by destination pixels.
         *
         *  We use a SurfaceView so that hardware acceleration (GPU) is used for rendering:
         *      - Offsetting and scaling.
         *      - Interpolation of values if required.
         *      - Clipping to the canvas area.
         *
         *   Note that these calculations could be cached as the change only when
         *   there is panning or scaling.
         */

        // Get the visible source region in pixels:
        val srcXVisibleRangePixels =
            logicalToPixels(xVisibleRangeFlow.value, bitmap.width)
        val srcYVisibleRangePixels =
            logicalToPixels(yVisibleRangeFlow.value, bitmap.height)

        // Calculate the expanded source region in exact source pixels, by rounding
        // outwards:
        val expandedSrcRect = Rect(
            floor(srcXVisibleRangePixels.first).toInt(),
            floor(srcYVisibleRangePixels.first).toInt(),
            ceil(srcXVisibleRangePixels.second).toInt(),
            ceil(srcYVisibleRangePixels.second).toInt()
        )

        // Calculate the source bitmap margins between the visible and expanded regions:
        val xScalingFactor: Float = canvas.width.toFloat() /
                (srcXVisibleRangePixels.second - srcXVisibleRangePixels.first)
        val deltaXSrcMinPx =
            srcXVisibleRangePixels.first - expandedSrcRect.left
        val deltaXDstMinPx = deltaXSrcMinPx * xScalingFactor
        val deltaXSrcMaxPx =
            expandedSrcRect.right - srcXVisibleRangePixels.second
        val deltaXDstMaxPx = deltaXSrcMaxPx * xScalingFactor

        val yScalingFactor: Float = canvas.height.toFloat() /
                (srcYVisibleRangePixels.second - srcYVisibleRangePixels.first)
        val deltaYSrcMinPx =
            srcYVisibleRangePixels.first - expandedSrcRect.top
        val deltaYDstMinPx = deltaYSrcMinPx * yScalingFactor
        val deltaYSrcMaxPx =
            expandedSrcRect.bottom - srcYVisibleRangePixels.second
        val deltaYDstMaxPx = deltaYSrcMaxPx * yScalingFactor

        // Calculate the expanded canvas region:
        val expandedDestRect = Rect(
            -deltaXDstMinPx.roundToInt(),
            -deltaYDstMinPx.roundToInt(),
            canvas.width + deltaXDstMaxPx.roundToInt(),
            canvas.height + deltaYDstMaxPx.roundToInt()
        )

        return Pair(expandedSrcRect, expandedDestRect)
    }

    protected abstract fun draw(bmPaint: Paint)
}

abstract class SHCallback(
    private var model: UIModel,
    protected val bitmapHolder: BitmapHolder
) : SurfaceHolder.Callback {

    private var drawThread: DrawThread? = null

    protected abstract fun createThread(model: UIModel, holder: SurfaceHolder): DrawThread

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Launch the rendering thread which uses our holder:
        drawThread = createThread(model, holder)
        drawThread?.apply { start() }

        // Do a single initial update to render any pre-existing bitmap. This is important for
        // example after a rotation.
        bitmapHolder.signalUpdate()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        // Log.d(this::class.simpleName, "surfaceChanged: width = $width height = $height")

        // The size may have changed, so signal that a UI updated is needed:
        bitmapHolder.signalUpdate()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        drawThread?.let {
            it.terminateThread()   // Signal a clean shutdown of the thread.
            it.join()              // Wait for thread to finish,
            if (BuildConfig.DEBUG)
                Log.d(this::class.simpleName, "DrawThread complete")
        }
    }
}

abstract class RendererBase(
    private val model: UIModel,
    private val graph: GraphBase,
    private val rawPageRangeState: MutableState<HORange?>,
    protected val bitmapHolder: BitmapHolder
) {
    protected var liveMode: Boolean = false

    var sizeDp: DpSize? = null
        private set

    fun reset() {
        if (BuildConfig.DEBUG)
            Log.d(this::class.simpleName, "reset called")

        this.liveMode = false

        // There is a race between resetting the ranges above and rerendering the display.
        // The following line is to make sure that we do a rerender after with
        // the newly reset values. Hence, the following line is not as redundant
        // as it might appear:
        bitmapHolder.signalUpdate()
    }

    @Composable
    abstract fun Compose(modifier: Modifier, settings: Settings)

    @Composable
    protected fun Compose(modifier: Modifier, settings: Settings, callback: SHCallback, gestureModifier: Modifier) {
        val density = LocalDensity.current

        AndroidView(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    // Check if the size truly changed. This lambda gets called multiple times
                    // with the same size:ge
                    if (BuildConfig.DEBUG)
                        Log.d(
                            this::class.simpleName,
                            "AndroidView size changed: $size"
                        )

                    // Note the size for future use:
                    sizeDp = with(density) {
                        DpSize(size.width.toDp(), size.height.toDp())
                    }
                }
                .then(gestureModifier),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(callback)
                }
            }
        )
    }

    fun setXConstraint(constrain: Boolean) {
        liveMode = constrain
    }

}

