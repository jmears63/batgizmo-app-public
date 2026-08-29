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

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.batgizmo.app.Settings
import org.batgizmo.app.ui.TopLevelUI.AppMode
import kotlin.math.roundToInt

class AudioConfig {

    @Composable
    fun IntegerSlider(
        label: String,
        value: Int,
        onValueChange: (Int) -> Unit,
        range: IntRange,
        stackLabel: Boolean,
        steps: Int = 0 // optional number of discrete steps; 0 = continuous
    ) {
        val valueText = "$value kHz"

        // Factored out so the slider is defined once; the caller supplies the width modifier.
        val slider: @Composable (Modifier) -> Unit = { sliderModifier ->
            Slider(
                value = value.toFloat(),
                onValueChange = { newValue -> onValueChange(newValue.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = if (steps > 0) steps - 1 else 0, // Compose expects steps = number of steps between min and max
                modifier = sliderModifier
            )
        }

        if (stackLabel) {
            // Portrait: label and value share the top row (value at the right end); the
            // slider uses the full width on its own row below.
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label)
                    Text(valueText)
                }
                slider(Modifier.fillMaxWidth())
            }
        } else {
            // Landscape: label, slider and value on a single row to minimise height.
            // The value has a fixed width so the slider does not reflow as its digit count changes.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, modifier = Modifier.padding(end = 8.dp))
                slider(
                    Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                Text(
                    text = valueText,
                    modifier = Modifier.width(60.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }

    @Composable
    fun Compose(
        settings: Settings,
        sampleRateHz: Int,
        appMode: Int,
        audioStarting: Boolean,
        onDismiss: () -> Unit,
        onConfirm: (Int, Int, Int, Boolean, Int, Int) -> Unit,
        heterodyneRange: IntRange,
    ) {
        // Constrain frequencies
        fun constrainFrequency(kHz: Int, factor: Float): Int {
            return if (kHz !in heterodyneRange)
                (heterodyneRange.start + (heterodyneRange.endInclusive - heterodyneRange.start) * factor + 0.5).toInt()
            else
                kHz
        }

        val constrainedRef1kHz = constrainFrequency(settings.heterodyneRef1kHz, 0.33f)
        val constrainedRef2kHz = constrainFrequency(settings.heterodyneRef2kHz, 0.66f)

        fun coercePlaybackModeForAppMode(mode: Int): Int {
            if (appMode != AppMode.VIEWER.value) {
                if (mode == Settings.AudioPlaybackModeOptions.DIRECT.value ||
                    mode == Settings.AudioPlaybackModeOptions.TIME_EXPANSION.value
                ) {
                    return Settings.AudioPlaybackModeOptions.SINGLE_HETERODYNE.value
                }
            }
            return mode
        }

        // Direct is viewer-only (hidden in live). Time expansion stays visible in live but
        // is greyed out / disabled — it only works on recorded material.
        val isViewer = appMode == AppMode.VIEWER.value
        val playbackModeOptions =
            if (isViewer)
                Settings.AudioPlaybackModeOptions.entries
            else
                Settings.AudioPlaybackModeOptions.entries.filter {
                    it != Settings.AudioPlaybackModeOptions.DIRECT
                }

        // State
        val initialPlaybackMode = coercePlaybackModeForAppMode(
            if (settings.audioPlaybackModePersisted)
                settings.audioPlaybackMode
            else
                settings.defaultAudioPlaybackModeForSampleRate(sampleRateHz)
        )
        val audioPlaybackMode = rememberSaveable { mutableIntStateOf(initialPlaybackMode) }

        LaunchedEffect(appMode) {
            audioPlaybackMode.intValue = coercePlaybackModeForAppMode(audioPlaybackMode.intValue)
        }
        val audioRef1kHz = rememberSaveable { mutableIntStateOf(constrainedRef1kHz) }
        val audioRef2kHz = rememberSaveable { mutableIntStateOf(constrainedRef2kHz) }
        val loopedPlayback = rememberSaveable { mutableStateOf(settings.loopedAudioPlayback) }
        val audioPitchRatio = rememberSaveable {
            mutableIntStateOf(Settings.AudioPitchRatioOptions.coerce(settings.audioPitchRatio))
        }
        val audioTimeExpansionFactor = rememberSaveable {
            mutableIntStateOf(
                Settings.AudioTimeExpansionFactorOptions.coerce(settings.audioTimeExpansionFactor)
            )
        }

        val isHeterodynePlayback =
            audioPlaybackMode.intValue == Settings.AudioPlaybackModeOptions.SINGLE_HETERODYNE.value ||
                audioPlaybackMode.intValue == Settings.AudioPlaybackModeOptions.DUAL_HETERODYNE.value
        val isDualHeterodyne =
            audioPlaybackMode.intValue == Settings.AudioPlaybackModeOptions.DUAL_HETERODYNE.value
        val isPitchShifting =
            audioPlaybackMode.intValue == Settings.AudioPlaybackModeOptions.PITCH_SHIFTING.value
        val isTimeExpansion =
            audioPlaybackMode.intValue == Settings.AudioPlaybackModeOptions.TIME_EXPANSION.value

        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 200.dp)
                        .widthIn(max = if (isLandscape) 420.dp else 600.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Audio Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )

                        MyListSelector<Settings.AudioPlaybackModeOptions>(
                            playbackModeOptions,
                            "Playback mode",
                            audioPlaybackMode.intValue,
                            optionEnabled = { option ->
                                isViewer ||
                                    option != Settings.AudioPlaybackModeOptions.TIME_EXPANSION
                            }
                        ) { value ->
                            audioPlaybackMode.intValue = value
                        }

                        if (isPitchShifting) {
                            MyListSelector<Settings.AudioPitchRatioOptions>(
                                Settings.AudioPitchRatioOptions.entries,
                                "Shift factor",
                                audioPitchRatio.intValue
                            ) { value ->
                                audioPitchRatio.intValue = value
                            }
                        }

                        if (isTimeExpansion) {
                            MyListSelector<Settings.AudioTimeExpansionFactorOptions>(
                                Settings.AudioTimeExpansionFactorOptions.entries,
                                "Expansion factor",
                                audioTimeExpansionFactor.intValue
                            ) { value ->
                                audioTimeExpansionFactor.intValue = value
                            }
                        }

                        if (isHeterodynePlayback) {
                            IntegerSlider(
                                label = "Reference",
                                value = audioRef1kHz.intValue,
                                onValueChange = { audioRef1kHz.intValue = it },
                                range = heterodyneRange,
                                stackLabel = !isLandscape
                            )

                            if (isDualHeterodyne) {
                                IntegerSlider(
                                    label = "Reference 2",
                                    value = audioRef2kHz.intValue,
                                    onValueChange = { audioRef2kHz.intValue = it },
                                    range = heterodyneRange,
                                    stackLabel = !isLandscape
                                )
                            }
                        }

                        if (isViewer) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = loopedPlayback.value,
                                    onCheckedChange = { loopedPlayback.value = it }
                                )
                                Text(if (isLandscape) "Loop playback" else "Looped playback")
                            }
                        }

                        // Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Cancel")
                            }
                            Spacer(Modifier.width(12.dp))
                            Button(
                                onClick = {
                                    onConfirm(
                                        audioPlaybackMode.intValue,
                                        audioRef1kHz.intValue,
                                        audioRef2kHz.intValue,
                                        loopedPlayback.value,
                                        audioPitchRatio.intValue,
                                        audioTimeExpansionFactor.intValue
                                    )
                                }
                            ) {
                                Text(if (audioStarting) "Start" else "Apply")
                            }
                        }
                    }
                }
            }
        }
    }
}
