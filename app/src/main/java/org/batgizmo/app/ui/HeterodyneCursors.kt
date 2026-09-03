package org.batgizmo.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import kotlin.math.round
import kotlin.math.roundToInt

class HeterodyneCursors(
    private val model: UIModel,
    private val heterodyneRef1kHz: MutableState<Int?>,
    private val heterodyneRef2kHz: MutableState<Int?>

) {
    companion object {
        val referenceLineColor = Color.Yellow
        val triggerRangeLineColor = Color.Green
    }

    public val minimumHeterodynekHz: Int
        get() = Settings.HETERODYNE_MIN_REF_KHZ

    @SuppressLint("UnusedBoxWithConstraintsScope")
    @Composable
    fun Compose() {

        if (heterodyneRef1kHz.value != null) {
            val shape = RoundedCornerShape(12.dp)
            val iconBoxSizeDp = 42.dp
            val iconBoxSizePx = with(LocalDensity.current) { iconBoxSizeDp.toPx() }
            val iconSizeDp = 24.dp
            val offsetY1 = rememberSaveable { mutableFloatStateOf(0f) }
            val offsetY2 = rememberSaveable { mutableFloatStateOf(0f) }

            val scope = rememberCoroutineScope()

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize()
            ) {
                val maxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

                val yAxisState = model.frequencyAxisRangeFlow.collectAsStateWithLifecycle()
                val frequencyRange = yAxisState.value

                fun yPxForRefkHz(refkHz: Int): Float =
                    maxHeightPx * (refkHz * 1000f - frequencyRange.endInclusive) /
                        (frequencyRange.start - frequencyRange.endInclusive)

                // Calculate the positions of the marker lines, based on the actual rounded reference
                // kHz value:
                var y1Px: Float? = null
                var y2Px: Float? = null
                heterodyneRef1kHz.value?.let { ref1kHz ->
                    y1Px = yPxForRefkHz(ref1kHz)
                }
                heterodyneRef2kHz.value?.let { ref2kHz ->
                    y2Px = yPxForRefkHz(ref2kHz)
                }

                // Keep the handle aligned with the line when the frequency axis range (or pane
                // height) changes. Do NOT key this on y1Px/y2Px: during a drag those change with
                // each rounded kHz step, and writing them back into offsetY makes the handle jump
                // ahead of the finger—especially after zooming in, when 1 kHz spans many pixels.
                LaunchedEffect(frequencyRange, maxHeightPx) {
                    heterodyneRef1kHz.value?.let { offsetY1.floatValue = yPxForRefkHz(it) }
                    heterodyneRef2kHz.value?.let { offsetY2.floatValue = yPxForRefkHz(it) }
                }

                // Draw the horizontal line
                Canvas(modifier = Modifier.fillMaxSize()) {
                    fun drawLine(y: Float) {
                        if (y in 0f..size.height) {
                            drawLine(
                                color = referenceLineColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 10f))
                            )
                        }
                    }

                    y1Px?.let { y1 -> drawLine(y1) }
                    y2Px?.let { y2 -> drawLine(y2) }
                }

                @Composable
                fun drawDraggable(
                    offsetY: MutableFloatState,
                    heterodyneRefkHz: MutableState<Int?>,
                    updateSetting: (kHz: Int) -> Settings
                ) {
                    // The draggable icon
                    if (offsetY.floatValue in -iconBoxSizePx..maxHeightPx) {
                        // Timber.d("offsetY = $offsetY")
                        Box(
                            modifier = Modifier
                                .size(iconBoxSizeDp)
                                .align(Alignment.TopStart)
                                .offset { IntOffset(0, (offsetY.floatValue - iconBoxSizePx / 2).roundToInt()) }
                                .border(BorderStroke(2.dp, Color.DarkGray), shape)
                                .clip(shape)
                                .pointerInput(maxHeightPx, frequencyRange) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()    // Eat the change.

                                            // Update the offset in response to the drag, with
                                            // no rounding — 1:1 with the finger in pixel space:
                                            val newOffset = offsetY.floatValue + dragAmount.y
                                            offsetY.floatValue =
                                                newOffset.coerceIn(0f, maxHeightPx)

                                            // Calculate the corresponding rounded reference kHz:
                                            val hz = frequencyRange.endInclusive -
                                                    offsetY.floatValue / maxHeightPx *
                                                    (frequencyRange.endInclusive - frequencyRange.start)
                                            heterodyneRefkHz.value =
                                                model.settings.coerceHeterodyneRefkHz(
                                                    round(hz / 1000f).toInt()
                                                )
                                        },
                                        onDragEnd = {
                                            // Snap the handle to the rounded line, then persist.
                                            heterodyneRefkHz.value?.let { kHz: Int ->
                                                offsetY.floatValue = yPxForRefkHz(kHz)
                                                    .coerceIn(0f, maxHeightPx)
                                                scope.launch {
                                                    model.updateStoredSettings(updateSetting(kHz))
                                                }
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.UnfoldMore,
                                contentDescription = "Heterodyne reference adjustor",
                                modifier = Modifier.size(iconSizeDp)
                            )
                        }
                    }
                }

                val sampleRateHz = model.pipelineSampleRateHz()
                val autoTuned = sampleRateHz?.let {
                    model.settings.isAutoTunedHeterodynePlayback(it)
                } == true

                // Auto-tuned mode: tracked LO line only (not draggable).
                if (!autoTuned) {
                    drawDraggable(offsetY1, heterodyneRef1kHz) { kHz: Int ->
                        model.settings.copy(heterodyneRef1kHz = kHz)
                    }
                    if (sampleRateHz?.let {
                            model.settings.isDualHeterodynePlayback(it)
                        } == true
                    ) {
                        drawDraggable(offsetY2, heterodyneRef2kHz) { kHz: Int ->
                            model.settings.copy(heterodyneRef2kHz = kHz)
                        }
                    }
                }
            }
        }
    }
}
