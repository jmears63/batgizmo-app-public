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
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.Log
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.HORange
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.ui.GraphBase
import uk.org.gimell.batgimzoapp.BuildConfig

class SpectrogramDrawThread(
    model: UIModel,
    surfaceHolder: SurfaceHolder,
    bitmapHolder: BitmapHolder
)
    : DrawThread(model, surfaceHolder, bitmapHolder)
{
    private val cursorColour = Color.Yellow
    val lineWidthPx = 2f    // TODO use dp and convert to px.

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

private enum class SpectrogramGestureState(val code: Int) {
    START(0),
    ONE_FINGER_DOWN(1),
    ONE_FINGER_MOVING(2),
    TWO_FINGERS_MOVING(3);
}

class SpectrogramRenderer(
    private val model: UIModel,
    private val graph: GraphBase,
    private val rawPageRangeState: MutableState<HORange?>,
    bitmapHolder: BitmapHolder,
) : RendererBase(model, graph, rawPageRangeState, bitmapHolder) {

    @Composable
    override fun Compose(
        modifier: Modifier,
        settings: Settings
    ) {
        val coroutineScope = rememberCoroutineScope()
        Compose(modifier, settings,
            SpectrogramSHCallback(model, model.spectrogramBitmapHolder),
            getGestureModifier(coroutineScope)
        )
    }

    @Composable
    private fun getGestureModifier(scope: CoroutineScope): Modifier {

        return Modifier.pointerInput(Unit) {

            /*
                "MENE, MENE, TEKEL, UPHARSIN"

                "The moving finger writes, and having writ,
                Moves on: nor all thy piety nor wit
                Shall lure it back to cancel half a line,
                Nor all thy tears wash out a word of it."

                On a more practical note, the following awaitPointerEventScope installs
                pointer input handler that allows us to use awaitPointerEvent to handle
                finger gestures.
            */

            val minMovement = 10.dp             // Ignore movements less than this.
            val longPressDurationMs = 800L      // How long is a long press.
            val tapMaximumDurationMs = 300L     // Taps are quite short.
            val maximumDoubleTapInterval = 600  // A double tap must occur in this interval.

            var startCentroid: Offset? = null
            var longPressJob: Job? = null
            var fingerDownTime: Long? = null
            var lastTapTime: Long? = null

            fun cancelLongPress() {
                longPressJob?.cancel()  // We are moving, so it's not a long press.
                longPressJob = null
            }

            /*
                Pairs of functions for each state, for execution on entering and leaving the
                state.
             */
            fun enterStart(): SpectrogramGestureState {
                startCentroid = null
                cancelLongPress()

                return SpectrogramGestureState.START
            }

            fun leaveStart() {
            }

            fun enterOneFingerDown(): SpectrogramGestureState {
                return SpectrogramGestureState.ONE_FINGER_DOWN
            }

            fun leaveOneFingerDown() {
                cancelLongPress()
            }

            fun enterOneFingerMoving(): SpectrogramGestureState {
                lastTapTime = null  // Cancel any apparent double tap that is in progress.
                return SpectrogramGestureState.ONE_FINGER_MOVING
            }

            fun leaveOneFingerMoving() {

            }

            fun enterTwoFingersMoving(
                p1: PointerInputChange,
                p2: PointerInputChange
            ): SpectrogramGestureState {
                lastTapTime = null  // Cancel any apparent double tap that is in progress.

                startCentroid = Offset(
                    (p1.position.x + p2.position.x) / 2,
                    (p1.position.y + p2.position.y) / 2
                )

                return SpectrogramGestureState.TWO_FINGERS_MOVING
            }

            fun leaveTwoFingersMoving() {
            }

            // Kick things off:
            var state = enterStart()

            awaitPointerEventScope {

                while (true) {
                    // Suspend until there is a pointer event:
                    val event = awaitPointerEvent()

                    val pointers = event.changes

                    var nextState = state

                    when (state) {
                        SpectrogramGestureState.START -> {
                            /*
                                We enter this state when no pointers are pressed.
                                We leave it when we detect that either one or more fingers are pressed.
                            */

                            if (pointers.size == 1) {
                                val p = pointers[0]
                                if (p.pressed) {

                                    // Start a timer that we will use to tell if it is a long touch:
                                    longPressJob = scope.launch {
                                        delay(longPressDurationMs)
                                        Log.i(
                                            this::class.simpleName,
                                            "getGestureModifier long press detected"
                                        )
                                        onLongPress(scope, p.position, size)
                                        // Remain in state START.
                                    }

                                    startCentroid = p.position
                                    fingerDownTime = p.uptimeMillis

                                    leaveStart()
                                    nextState = enterOneFingerDown()
                                }
                            } else if (pointers.size == 2) {
                                val (p1, p2) = Pair(pointers[0], pointers[1])
                                if (p1.pressed && p2.pressed) {
                                    leaveStart()
                                    nextState = enterTwoFingersMoving(p1, p2)
                                }
                            }
                        }

                        SpectrogramGestureState.ONE_FINGER_DOWN -> {
                            if (pointers.isEmpty()) {
                                leaveOneFingerDown()
                                nextState = enterStart()
                            } else if (pointers.size == 1) {
                                val p = pointers[0]
                                if (p.pressed) {
                                    // Has the finger moved?
                                    startCentroid?.let { start ->
                                        val distance = (p.position - start).getDistance()
                                        if (distance > minMovement.toPx()) {
                                            leaveOneFingerDown()
                                            nextState = enterOneFingerMoving()
                                        }
                                    }
                                } else if (p.changedToUp()) {
                                    fingerDownTime?.let { thisTap ->
                                        // Was it short enough to count as a tap?
                                        if (p.uptimeMillis - thisTap < tapMaximumDurationMs) {
                                            // Is it part of a double tap?
                                            lastTapTime?.let { lastTap ->
                                                if (thisTap - lastTap < maximumDoubleTapInterval) {
                                                    onDoubleTap(scope)

                                                    leaveOneFingerDown()
                                                    nextState = enterStart()
                                                }
                                            }
                                            lastTapTime = p.uptimeMillis
                                        } else {
                                            lastTapTime = null
                                        }
                                    }

                                    leaveOneFingerDown()
                                    nextState = enterStart()
                                }
                            } else if (pointers.size == 2) {
                                val (p1, p2) = Pair(pointers[0], pointers[1])
                                if (p1.pressed && p2.pressed) {
                                    // Additional finger touched.
                                    leaveOneFingerDown()
                                    nextState = enterTwoFingersMoving(p1, p2)
                                }
                            }
                        }

                        SpectrogramGestureState.ONE_FINGER_MOVING -> {
                            if (pointers.isEmpty()) {
                                leaveOneFingerMoving()
                                nextState = enterStart()
                            } else if (pointers.size == 1) {
                                val p = pointers[0]
                                if (p.pressed) {
                                    startCentroid?.let { start ->
                                        val displacement = p.position - p.previousPosition
                                        if (BuildConfig.DEBUG)
                                            Log.d(
                                                this::class.simpleName,
                                                "getGestureModifier panning to $displacement"
                                            )
                                        onPan(scope, displacement, size)
                                    }
                                } else {
                                    onCompletePan(scope)
                                    leaveOneFingerMoving()
                                    nextState = enterStart()
                                }
                            } else if (pointers.size == 2) {
                                val (p1, p2) = Pair(pointers[0], pointers[1])
                                if (p1.pressed && p2.pressed) {
                                    onCompletePan(scope)
                                    leaveOneFingerMoving()
                                    nextState = enterTwoFingersMoving(p1, p2)
                                }
                            }
                        }

                        SpectrogramGestureState.TWO_FINGERS_MOVING -> {
                            if (pointers.isEmpty()) {
                                leaveTwoFingersMoving()
                                nextState = enterStart()
                            } else if (pointers.size == 1) {
                                leaveTwoFingersMoving()
                                nextState = enterStart()
                            } else if (pointers.size == 2) {
                                val (p1, p2) = Pair(pointers[0], pointers[1])
                                if (p1.pressed && p2.pressed) {
                                    // They are zooming:
                                    startCentroid?.let {
                                        onZoom(scope, it, p1.previousPosition, p2.previousPosition,
                                            p1.position, p2.position, size)
                                    }
                                } else {
                                    onCompleteZoom(scope)
                                    leaveTwoFingersMoving()
                                    nextState = enterStart()
                                }
                            }
                        }
                    }

                    if (nextState != state) {
                        if (BuildConfig.DEBUG)
                            Log.d(
                                this::class.simpleName,
                                "Gesture state change from $state to $nextState"
                            )
                    }

                    state = nextState
                }
            }
        }
    }

    private fun onPan(scope: CoroutineScope, displacement: Offset, size: IntSize): Unit {
        Log.i(this::class.simpleName, "gesture action onPan called: $displacement")

        scope.launch(CoroutineName("onPan coroutine")) {
            // UI thread:
            model.panSpectrogramVisibleRange(displacement, size, liveMode)
        }
    }

    private fun onZoom(scope: CoroutineScope,
        startCentroid: Offset,
        previousP1: Offset, previousP2: Offset,
        p1: Offset, p2: Offset,
        size: IntSize
    ) {
        Log.i(this::class.simpleName, "gesture action onZoom called")

        scope.launch(CoroutineName("onZoom coroutine")) {
            // UI thread:
            model.zoomSpectrogramVisibleRange(startCentroid, previousP1, previousP2,
                p1, p2, size, liveMode)
        }
    }

    private fun onLongPress(scope: CoroutineScope, displacement: Offset, size: IntSize): Unit {
        Log.i(this::class.simpleName, "gesture action onLongPress called")

        scope.launch(CoroutineName("onZoom coroutine")) {
            // UI thread:
            model.onLongPress(graph, displacement, size, liveMode)
        }
    }

    private fun onDoubleTap(scope: CoroutineScope): Unit {
        Log.i(this::class.simpleName, "gesture action onDoubleTap called")

        // Reset ranges to make all data visible:
        scope.launch(CoroutineName("onZoom coroutine")) {
            // UI thread:
            model.onDoubleTap(graph, liveMode, model.autoBnCRequiredFlow.value)
        }
    }

    private fun onCompleteZoom(scope: CoroutineScope): Unit {
        Log.i(this::class.simpleName, "gesture action onZoomComplete called")

        scope.launch(CoroutineName("onCompleteZoom coroutine")) {
            // UI thread:
            // Results in a pipeline rebuild if required:
            if (!liveMode)
                graph.onVisibleRangeChange(model.autoBnCRequiredFlow.value)
        }
    }

    private fun onCompletePan(scope: CoroutineScope): Unit {
        Log.i(this::class.simpleName, "gesture action onCompletePan called")

        scope.launch(CoroutineName("onCompletePan coroutine")) {
            // UI thread:
            // Results in a pipeline rebuild if required:
            if (!liveMode)
                graph.onVisibleRangeChange(model.autoBnCRequiredFlow.value)
        }
    }
}
