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

package org.batgizmo.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.batgizmo.app.UIModel
import timber.log.Timber
import uk.org.gimell.batgimzoapp.BuildConfig

private enum class SpectrogramGestureState {
    START,
    ONE_FINGER_DOWN,
    ONE_FINGER_MOVING,
    TWO_FINGERS_MOVING,
}

private data class GestureDataFrame(
    val leftPx: Float,
    val topPx: Float,
    val dataSize: IntSize,
) {
    fun framePositionToData(position: Offset): Offset =
        Offset(position.x - leftPx, position.y - topPx)
}

private fun gestureDataFrame(
    frameSize: IntSize,
    padding: GraphBase.GraphPadding,
    density: Density,
): GestureDataFrame {
    val leftPx = with(density) { padding.leftDp.dp.toPx() }
    val topPx = with(density) { padding.topDp.dp.toPx() }
    val rightPx = with(density) { padding.rightDp.dp.toPx() }
    val bottomPx = with(density) { padding.bottomDp.dp.toPx() }
    val dataWidth = (frameSize.width - leftPx - rightPx).toInt().coerceAtLeast(1)
    val dataHeight = (frameSize.height - topPx - bottomPx).toInt().coerceAtLeast(1)
    return GestureDataFrame(leftPx, topPx, IntSize(dataWidth, dataHeight))
}

private fun axisForFramePosition(
    position: Offset,
    frameSize: IntSize,
    padding: GraphBase.GraphPadding,
    density: Density,
    mode: GraphGestureMode,
): SpectrogramGestureAxis {
    if (mode == GraphGestureMode.AMPLITUDE_TIME) {
        // Amplitude pane only drives the shared time range.
        return SpectrogramGestureAxis.TIME
    }

    val leftPx = with(density) { padding.leftDp.dp.toPx() }
    val bottomPx = with(density) { padding.bottomDp.dp.toPx() }
    val frameHeight = frameSize.height.toFloat()

    // Time and frequency axes sit on the inner edges of the bottom and left borders.
    val timeAxisY = frameHeight - bottomPx
    val frequencyAxisX = leftPx

    val distToTime = kotlin.math.abs(position.y - timeAxisY)
    val distToFrequency = kotlin.math.abs(position.x - frequencyAxisX)

    val nearTime = bottomPx > 0f && distToTime <= bottomPx
    val nearFrequency = leftPx > 0f && distToFrequency <= leftPx

    return when {
        nearTime && nearFrequency ->
            if (distToTime <= distToFrequency) SpectrogramGestureAxis.TIME
            else SpectrogramGestureAxis.FREQUENCY

        nearTime -> SpectrogramGestureAxis.TIME
        nearFrequency -> SpectrogramGestureAxis.FREQUENCY
        else -> SpectrogramGestureAxis.FREE
    }
}

/**
 * Full-frame pointer handling for spectrogram and amplitude graph panes.
 */
class SpectrogramGestureHandler(
    private val model: UIModel,
    private val graph: GraphBase,
    private val mode: GraphGestureMode = GraphGestureMode.SPECTROGRAM,
) {
    private var clampX = false
    private var gestureJobCPUIntensive: Job? = null
    private var skipCount = 0

    fun setXConstraint(constrain: Boolean) {
        clampX = constrain
    }

    fun gestureModifier(
        scope: CoroutineScope,
        borderPadding: GraphBase.GraphPadding,
    ): Modifier {
        return Modifier.pointerInput(borderPadding, clampX, mode) {
            val minMovement = 10.dp
            val longPressDurationMs = 800L
            val tapMaximumDurationMs = 300L
            val maximumDoubleTapInterval = 600

            val dataFrame = gestureDataFrame(size, borderPadding, this)

            var startCentroid: Offset? = null
            var longPressJob: Job? = null
            var fingerDownTime: Long? = null
            var lastTapTime: Long? = null
            var sessionAxis = SpectrogramGestureAxis.FREE

            fun cancelLongPress() {
                longPressJob?.cancel()
                longPressJob = null
            }

            fun enterStart(): SpectrogramGestureState {
                startCentroid = null
                sessionAxis = SpectrogramGestureAxis.FREE
                cancelLongPress()
                return SpectrogramGestureState.START
            }

            fun enterOneFingerMoving(): SpectrogramGestureState {
                lastTapTime = null
                return SpectrogramGestureState.ONE_FINGER_MOVING
            }

            fun enterTwoFingersMoving(
                p1: PointerInputChange,
                p2: PointerInputChange,
            ): SpectrogramGestureState {
                lastTapTime = null
                val centroid = Offset(
                    (p1.position.x + p2.position.x) / 2,
                    (p1.position.y + p2.position.y) / 2
                )
                if (sessionAxis == SpectrogramGestureAxis.FREE) {
                    sessionAxis = axisForFramePosition(
                        centroid, size, borderPadding, this, mode
                    )
                }
                startCentroid = dataFrame.framePositionToData(centroid)
                return SpectrogramGestureState.TWO_FINGERS_MOVING
            }

            var state = enterStart()

            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val pointers = event.changes
                    var nextState = state

                    when (state) {
                        SpectrogramGestureState.START -> {
                            if (pointers.size == 1) {
                                val p = pointers[0]
                                if (p.pressed) {
                                    sessionAxis = axisForFramePosition(
                                        p.position, size, borderPadding, this, mode
                                    )
                                    val longPressAxis = sessionAxis
                                    longPressJob = scope.launch {
                                        delay(longPressDurationMs)
                                        Timber.i("SpectrogramGestureHandler long press detected")
                                        onLongPress(
                                            scope,
                                            dataFrame.framePositionToData(p.position),
                                            dataFrame.dataSize,
                                            longPressAxis
                                        )
                                    }
                                    startCentroid = p.position
                                    fingerDownTime = p.uptimeMillis
                                    nextState = SpectrogramGestureState.ONE_FINGER_DOWN
                                }
                            } else if (pointers.size == 2) {
                                val (p1, p2) = Pair(pointers[0], pointers[1])
                                if (p1.pressed && p2.pressed) {
                                    sessionAxis = axisForFramePosition(
                                        Offset(
                                            (p1.position.x + p2.position.x) / 2,
                                            (p1.position.y + p2.position.y) / 2
                                        ),
                                        size,
                                        borderPadding,
                                        this,
                                        mode
                                    )
                                    nextState = enterTwoFingersMoving(p1, p2)
                                }
                            }
                        }

                        SpectrogramGestureState.ONE_FINGER_DOWN -> {
                            if (pointers.isEmpty()) {
                                cancelLongPress()
                                nextState = enterStart()
                            } else if (pointers.size == 1) {
                                val p = pointers[0]
                                if (p.pressed) {
                                    startCentroid?.let { start ->
                                        if ((p.position - start).getDistance() > minMovement.toPx()) {
                                            cancelLongPress()
                                            nextState = enterOneFingerMoving()
                                        }
                                    }
                                } else if (p.changedToUp()) {
                                    cancelLongPress()
                                    fingerDownTime?.let { thisTap ->
                                        if (p.uptimeMillis - thisTap < tapMaximumDurationMs) {
                                            lastTapTime?.let { lastTap ->
                                                if (thisTap - lastTap < maximumDoubleTapInterval) {
                                                    onDoubleTap(scope, sessionAxis)
                                                    nextState = enterStart()
                                                }
                                            }
                                            lastTapTime = p.uptimeMillis
                                        } else {
                                            lastTapTime = null
                                        }
                                    }
                                    if (nextState == state)
                                        nextState = enterStart()
                                }
                            } else if (pointers.size == 2) {
                                val (p1, p2) = Pair(pointers[0], pointers[1])
                                if (p1.pressed && p2.pressed) {
                                    cancelLongPress()
                                    nextState = enterTwoFingersMoving(p1, p2)
                                }
                            }
                        }

                        SpectrogramGestureState.ONE_FINGER_MOVING -> {
                            if (pointers.isEmpty()) {
                                nextState = enterStart()
                            } else if (pointers.size == 1) {
                                val p = pointers[0]
                                if (p.pressed) {
                                    val displacement = p.position - p.previousPosition
                                    if (BuildConfig.DEBUG)
                                        Timber.d("SpectrogramGestureHandler panning to $displacement")
                                    onPan(scope, displacement, dataFrame.dataSize, sessionAxis)
                                } else {
                                    onCompletePan(scope)
                                    nextState = enterStart()
                                }
                            } else if (pointers.size == 2) {
                                val (p1, p2) = Pair(pointers[0], pointers[1])
                                if (p1.pressed && p2.pressed) {
                                    onCompletePan(scope)
                                    nextState = enterTwoFingersMoving(p1, p2)
                                }
                            }
                        }

                        SpectrogramGestureState.TWO_FINGERS_MOVING -> {
                            if (pointers.isEmpty() || pointers.size == 1) {
                                onCompleteZoom(scope)
                                nextState = enterStart()
                            } else if (pointers.size == 2) {
                                val (p1, p2) = Pair(pointers[0], pointers[1])
                                if (p1.pressed && p2.pressed) {
                                    startCentroid?.let { centroid ->
                                        onZoom(
                                            scope,
                                            centroid,
                                            p1.previousPosition, p2.previousPosition,
                                            p1.position, p2.position,
                                            dataFrame.dataSize,
                                            sessionAxis
                                        )
                                    }
                                } else {
                                    onCompleteZoom(scope)
                                    nextState = enterStart()
                                }
                            }
                        }
                    }

                    if (nextState != state && BuildConfig.DEBUG)
                        Timber.d("Gesture state change from $state to $nextState")

                    state = nextState
                }
            }
        }
    }

    private fun gestureJobInProgress(): Boolean {
        gestureJobCPUIntensive?.let { job ->
            if (job.isActive) {
                Timber.d("Skipping gesture job (${skipCount++}): previous job is still running")
                return true
            }
        }
        return false
    }

    private fun onPan(
        scope: CoroutineScope,
        displacement: Offset,
        dataSize: IntSize,
        axis: SpectrogramGestureAxis,
    ) {
        if (gestureJobInProgress())
            return

        scope.launch(CoroutineName("onPan coroutine")) {
            model.panSpectrogramVisibleRange(displacement, dataSize, clampX, axis)
        }
    }

    private fun onZoom(
        scope: CoroutineScope,
        startCentroid: Offset,
        previousP1: Offset, previousP2: Offset,
        p1: Offset, p2: Offset,
        dataSize: IntSize,
        axis: SpectrogramGestureAxis,
    ) {
        if (gestureJobInProgress())
            return

        scope.launch(CoroutineName("onZoom coroutine")) {
            model.zoomSpectrogramVisibleRange(
                startCentroid, previousP1, previousP2, p1, p2, dataSize, clampX, axis
            )
        }
    }

    private fun onLongPress(
        scope: CoroutineScope,
        dataPosition: Offset,
        dataSize: IntSize,
        axis: SpectrogramGestureAxis,
    ) {
        if (gestureJobInProgress())
            return

        gestureJobCPUIntensive = scope.launch(CoroutineName("onLongPress coroutine")) {
            withContext(Dispatchers.Default) {
                model.onLongPress(graph, dataPosition, dataSize, clampX, axis)
            }
        }
    }

    private fun onDoubleTap(scope: CoroutineScope, axis: SpectrogramGestureAxis) {
        if (gestureJobInProgress())
            return

        gestureJobCPUIntensive = scope.launch(CoroutineName("onDoubleTap coroutine")) {
            withContext(Dispatchers.Default) {
                model.onDoubleTap(graph, clampX, model.autoBnCRequiredFlow.value, axis)
            }
        }
    }

    private fun onCompleteZoom(scope: CoroutineScope) {
        if (gestureJobInProgress())
            return

        gestureJobCPUIntensive = scope.launch(CoroutineName("onCompleteZoom coroutine")) {
            withContext(Dispatchers.Default) {
                graph.onVisibleRangeChange(model.autoBnCRequiredFlow.value)
            }
        }
    }

    private fun onCompletePan(scope: CoroutineScope) {
        if (gestureJobInProgress())
            return

        gestureJobCPUIntensive = scope.launch(CoroutineName("onCompletePan coroutine")) {
            withContext(Dispatchers.Default) {
                graph.onVisibleRangeChange(model.autoBnCRequiredFlow.value)
            }
        }
    }
}
