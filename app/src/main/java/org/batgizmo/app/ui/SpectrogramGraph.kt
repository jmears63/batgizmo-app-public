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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.batgizmo.app.HORange
import org.batgizmo.app.UIModel
import org.batgizmo.app.pipeline.SpectrogramRenderer

class SpectrogramGraph(
    model: UIModel,
    rawPageRangeState: MutableState<HORange?>,
)
    : GraphBase(model, rawPageRangeState,
    model.timeAxisRangeFlow, model.frequencyAxisRangeFlow, supportCursor = false) {

    private val frequencyAxisBorder =
        AxisBorderVertical(
            "Frequency",
            listOf(AxisBorder.Unit(units = "kHz", scaling = 1000f)),
            layoutType = AxisBorder.Layout.COMPACT
        )
    private val titleBorder: TitleBorder
    private val gestureHandler = SpectrogramGestureHandler(model, this)

    init {
        // Define units to be used by axes:
        val timeUnits = listOf(
            AxisBorder.Unit(units="s", scaling=1f),
            AxisBorder.Unit(limit=0.5f, units="ms", scaling=1E-3f)
            )
        val frequencyUnits = listOf(
            AxisBorder.Unit(units = "kHz", scaling = 1000f),
        )

        titleBorder = TitleBorder(null)
        topBorder = titleBorder
        rightBorder = BlankBorderVertical()
        leftBorder = frequencyAxisBorder
        bottomBorder = AxisBorderHorizontal(
            "Time", timeUnits, layoutType = AxisBorder.Layout.COMPACT)

        renderer = SpectrogramRenderer(model, this, rawPageRangeState,
            model.spectrogramBitmapHolder)
    }

    private fun updateFrequencyAxisHighlights(triggeredRecording: Boolean) {
        val sampleRateHz = model.pipelineSampleRateHz()
        val audioOn = model.spectrogramUIState.isAudioPlaybackOn()
        frequencyAxisBorder.triggerHighlightRangeHz =
            if (triggeredRecording) {
                model.settings.autoTriggerRangeMinkHz * 1000f to
                    model.settings.autoTriggerRangeMaxkHz * 1000f
            } else {
                null
            }
        frequencyAxisBorder.highlightRangeHz =
            if (audioOn &&
                sampleRateHz != null &&
                model.settings.isAutoTunedHeterodynePlayback(sampleRateHz)
            ) {
                val (minKhz, maxKhz) = model.settings.normalizedAutoHeterodyneLoRange()
                minKhz * 1000f to maxKhz * 1000f
            } else {
                null
            }
    }

    @Composable
    fun Compose(
        modifier: Modifier,
        viewModel: UIModel,
        showGrid: Boolean,
        title: String?,
        overlayComposer: @Composable (Modifier) -> Unit
    ) {
        val triggeredRecording by model.spectrogramButtonState.triggeredRecordingChecked
        updateFrequencyAxisHighlights(triggeredRecording)
        titleBorder.setTitle(title)
        ComposeFrame(
            modifier,
            showGrid,
            overlayComposer,
            frameGestures = { padding, scope ->
                gestureHandler.gestureModifier(scope, padding)
            }
        )
    }

    fun setClampX(constrain: Boolean) {
        renderer.setXConstraint(constrain)
        gestureHandler.setXConstraint(constrain)
    }
}
