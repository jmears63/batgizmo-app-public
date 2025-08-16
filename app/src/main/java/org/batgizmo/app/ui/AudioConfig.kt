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

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.batgizmo.app.Settings

class AudioConfig {

    @SuppressLint("UnusedBoxWithConstraintsScope")
    @Composable
    fun IntegerLazyPicker(
        label: String,
        value: Int,
        onValueChange: (Int) -> Unit,
        range: IntRange,
        modifier: Modifier = Modifier
    ) {
        // NB values and keys are both integers. Values are in kHz; keys are indexes into the
        // list of values.
        val values = range.toList()

        // Used to scroll to the selected value:
        val listState = rememberLazyListState()

        val valueWidth = 48.dp
        val spacingWidth = 8.dp
        val itemWidth = valueWidth + spacingWidth

        BoxWithConstraints {
            val rowWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }

            LaunchedEffect(Unit) {
                val index = values.indexOf(value)
                if (index >= 0) {
                    val scrollOffset = ((rowWidthPx - itemWidthPx) / 2).toInt()
                    listState.animateScrollToItem(index, -scrollOffset)
                }
            }

            Column() {
                Text(label)

                LazyRow(
                    state = listState,
                    modifier = Modifier
                        // .height(56.dp) // Adjust height for a horizontal row
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                    horizontalArrangement = Arrangement.spacedBy(spacingWidth)
                ) {
                    items(values.size) { index ->
                        val selectedValue = values[index]
                        val isSelected = selectedValue == value
                        val backgroundColor = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else
                            Color.Transparent

                        Box(
                            modifier = Modifier
                                .width(valueWidth)
                                .semantics {
                                    contentDescription = if (isSelected)
                                        "Selected value $selectedValue"
                                    else
                                        "Value $selectedValue"
                                }
                                .clickable { onValueChange(selectedValue) }
                                .background(backgroundColor, shape = RoundedCornerShape(4.dp))
                                .padding(vertical=12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$selectedValue",
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Compose(settings: Settings,
                onDismiss: () -> Unit,
                onConfirm: (Boolean, Int, Int, Int) -> Unit,
                heterodyneRange: IntRange
    ) {
        val heterodyne = true

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
            title = { Text("Audio") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            enabled = true,
                            selected = heterodyne,    // For now the only option..
                            onClick = {}
                        )
                        Text("Heterodyne")
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            enabled = heterodyne == true,
                            checked = audioDualHeterodyne.value,
                            onCheckedChange = { audioDualHeterodyne.value = it }
                        )
                        Text("Dual heterodyne mode")
                    }

                    MyListSelector<Settings.AudioBoostOptions>(
                        Settings.AudioBoostOptions.entries,
                        "Audio boost",
                        audioBoostShift.intValue
                    ) { value: Int ->
                        audioBoostShift.intValue = value
                    }

                    Spacer(Modifier.height(16.dp))

                    IntegerLazyPicker(
                        label = "Reference (kHz)",
                        value = audioRef1kHz.intValue,
                        onValueChange = { audioRef1kHz.intValue = it },
                        range = heterodyneRange
                    )

                    if (audioDualHeterodyne.value) {
                        Spacer(Modifier.height(8.dp))
                        IntegerLazyPicker(
                            label = "Reference 2 (kHz)",
                            value = audioRef2kHz.intValue,
                            onValueChange = { audioRef2kHz.intValue = it },
                            range = heterodyneRange
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm(audioDualHeterodyne.value, audioRef1kHz.intValue,
                        audioRef2kHz.intValue, audioBoostShift.intValue)
                }) {
                    Text("Start")
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
