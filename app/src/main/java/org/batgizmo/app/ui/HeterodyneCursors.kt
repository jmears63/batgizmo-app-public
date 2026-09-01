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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import uk.org.gimell.batgimzoapp.R
import kotlin.math.round
import kotlin.math.roundToInt

class HeterodyneCursors(
    private val model: UIModel,
    private val heterodyneRef1kHz: MutableState<Int?>,
    private val heterodyneRef2kHz: MutableState<Int?>

) {
    companion object {
        val referenceLineColor = Color.Yellow
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

                // Calculate the positions of the marker lines, based on the actual rounded reference
                // kHz value:
                var y1Px: Float? = null
                var y2Px: Float? = null
                heterodyneRef1kHz.value?.let { ref1kHz ->
                    y1Px = maxHeightPx * (ref1kHz * 1000f - yAxisState.value.endInclusive) /
                            (yAxisState.value.start - yAxisState.value.endInclusive)
                }
                heterodyneRef2kHz.value?.let { ref2kHz ->
                    y2Px = maxHeightPx * (ref2kHz * 1000f - yAxisState.value.endInclusive) /
                            (yAxisState.value.start - yAxisState.value.endInclusive)
                }

                // Initialize the icon offset to the marker line offset:
                LaunchedEffect(yAxisState.value, y1Px, y2Px) {
                    // Sync the icon offset to the marker line on
                    // newly composing these elements and whenever the Y scale changes:
                    y1Px?.let { offsetY1.floatValue = it }
                    y2Px?.let { offsetY2.floatValue = it }
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
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()    // Eat the change.

                                            // Update the offset in response to the drag, with
                                            // no rounding:
                                            val newOffset = offsetY.floatValue + dragAmount.y
                                            offsetY.floatValue =
                                                newOffset.coerceIn(0f, maxHeightPx)

                                            // Calculate the corresponding rounded reference kHz:
                                            val hz = yAxisState.value.endInclusive -
                                                    offsetY.floatValue / maxHeightPx * (yAxisState.value.endInclusive - yAxisState.value.start)
                                            heterodyneRefkHz.value =
                                                model.settings.coerceHeterodyneRefkHz(
                                                    round(hz / 1000f).toInt()
                                                )
                                        },
                                        onDragEnd = {
                                            // They've finished dragging, to write the updated values
                                            // to settings for persistence:
                                            heterodyneRefkHz.value?.let { kHz: Int ->
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
                                imageVector = ImageVector.vectorResource(R.drawable.outline_pan_tool_alt_24),
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
