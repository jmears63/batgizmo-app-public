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
import androidx.compose.foundation.layout.height
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
        steps: Int = 0 // optional number of discrete steps; 0 = continuous
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Label and current value
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label)
                Text("${value} kHz") // You can remove "kHz" if not needed
            }

            // Slider
            Slider(
                value = value.toFloat(),
                onValueChange = { newValue -> onValueChange(newValue.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = if (steps > 0) steps - 1 else 0, // Compose expects steps = number of steps between min and max
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    fun Compose(
        settings: Settings,
        sampleRateHz: Int,
        appMode: Int,
        audioStarting: Boolean,
        onDismiss: () -> Unit,
        onConfirm: (Int, Int, Int, Boolean) -> Unit,
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
            if (appMode != AppMode.VIEWER.value &&
                mode == Settings.AudioPlaybackModeOptions.DIRECT.value
            ) {
                return Settings.AudioPlaybackModeOptions.SINGLE_HETERODYNE.value
            }
            return mode
        }

        val playbackModeOptions =
            if (appMode == AppMode.VIEWER.value)
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

        val isDirectPlayback =
            audioPlaybackMode.intValue == Settings.AudioPlaybackModeOptions.DIRECT.value
        val isDualHeterodyne =
            audioPlaybackMode.intValue == Settings.AudioPlaybackModeOptions.DUAL_HETERODYNE.value

        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 200.dp)
                        .widthIn(max = 600.dp), // Card max width
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = spacedBy(12.dp)
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
                            audioPlaybackMode.intValue
                        ) { value ->
                            audioPlaybackMode.intValue = value
                        }

                        if (appMode == AppMode.VIEWER.value) {
                            if (isLandscape) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = loopedPlayback.value,
                                            onCheckedChange = { loopedPlayback.value = it }
                                        )
                                        Text("Loop playback")
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = loopedPlayback.value,
                                        onCheckedChange = { loopedPlayback.value = it }
                                    )
                                    Text("Looped playback")
                                }
                            }
                        }

                        if (!isDirectPlayback) {
                            IntegerSlider(
                                label = "Reference",
                                value = audioRef1kHz.intValue,
                                onValueChange = { audioRef1kHz.intValue = it },
                                range = heterodyneRange
                            )

                            if (isDualHeterodyne) {
                                IntegerSlider(
                                    label = "Reference 2",
                                    value = audioRef2kHz.intValue,
                                    onValueChange = { audioRef2kHz.intValue = it },
                                    range = heterodyneRange
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

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
                                        loopedPlayback.value
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
