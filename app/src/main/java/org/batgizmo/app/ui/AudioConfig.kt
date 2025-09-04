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

package org.batgizmo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.batgizmo.app.Settings
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
    fun Compose(settings: Settings,
                audioStarting: Boolean,
                onDismiss: () -> Unit,
                onConfirm: (Boolean, Int, Int, Int) -> Unit,
                heterodyneRange: IntRange,
    ) {
        // Make sure the heterodyne frequencies are within the range
        // allowed:
        fun constrainFrequency(kHz: Int, factor: Float): Int {
            return if (kHz > heterodyneRange.endInclusive || kHz < heterodyneRange.start)
                (heterodyneRange.start + (heterodyneRange.endInclusive - heterodyneRange.start) * factor + 0.5).toInt()
            else
                kHz
        }
        val constrainedRef1kHz = constrainFrequency(settings.heterodyneRef1kHz, 0.33f)
        val constrainedRef2kHz = constrainFrequency(settings.heterodyneRef2kHz, 0.66f)

        // Copy the current values out of the settings. We will update settings
        // if the user confirms, otherwise discard them.
        val audioDualHeterodyne = rememberSaveable { mutableStateOf(settings.heterodyneDual)}
        val audioRef1kHz = rememberSaveable { mutableIntStateOf(constrainedRef1kHz)}
        val audioRef2kHz = rememberSaveable { mutableIntStateOf(constrainedRef2kHz)}
        val audioBoostShift = rememberSaveable { mutableIntStateOf(settings.audioBoostShift) }

        AlertDialog(
            onDismissRequest = onDismiss,   // Action on passive dismissal such as tapping outside.
            title = { Text("Audio Settings") },
            text = {
                Column(modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = spacedBy(8.dp)) {

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            enabled = true,
                            checked = audioDualHeterodyne.value,
                            onCheckedChange = { audioDualHeterodyne.value = it }
                        )
                        Text("Dual heterodyne mode")
                    }

                    IntegerSlider(
                        label = "Reference",
                        value = audioRef1kHz.intValue,
                        onValueChange = { audioRef1kHz.intValue = it },
                        range = heterodyneRange
                    )

                    if (audioDualHeterodyne.value) {
                        IntegerSlider(
                            label = "Reference 2",
                            value = audioRef2kHz.intValue,
                            onValueChange = { audioRef2kHz.intValue = it },
                            range = heterodyneRange
                        )
                    }

                    MySliderSelector<Settings.AudioBoostOptions>(
                        Settings.AudioBoostOptions.entries,
                        "Audio boost",
                        audioBoostShift.intValue
                    ) { value: Int ->
                        audioBoostShift.intValue = value
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm(audioDualHeterodyne.value, audioRef1kHz.intValue,
                        audioRef2kHz.intValue, audioBoostShift.intValue)
                }) {
                    Text(if (audioStarting) "Start" else "Apply" )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}
