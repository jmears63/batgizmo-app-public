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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import org.batgizmo.app.Settings
import kotlin.enums.EnumEntries
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> MyListSelector(
    enumEntries: List<T>,
    description: String,
    selectedValue: Int,
    enabled: Boolean = true,
    optionEnabled: (T) -> Boolean = { true },
    onChange: (Int) -> Unit
) where T : Enum<T>, T: Settings.EnumHelper {

    fun valueForLabel(label: String): Int {
        val i = enumEntries.indexOfFirst { it.theLabel() == label }
        return enumEntries[i].theValue()
    }

    fun labelForValue(value: Int): String {
        val i = enumEntries.indexOfFirst { it.theValue() == value }
        return enumEntries[i].theLabel()
    }

    // Some local UI state, persistent through rotation etc:
    var selectedText by rememberSaveable { mutableStateOf(labelForValue(selectedValue)) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Never leave the menu open if the control becomes disabled.
    if (!enabled && expanded) {
        expanded = false
    }

    // List of dropdown menu items
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        // TextField for input and dropdown
        TextField(
            value = selectedText,
            onValueChange = {  },
            readOnly = true,
            enabled = enabled,
            label = { Text(description) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors()
        )

        // Dropdown menu
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            enumEntries.forEach { option ->
                val enabled = optionEnabled(option)
                DropdownMenuItem(
                    text = { Text(option.theLabel()) },
                    onClick = {
                        selectedText = option.theLabel()
                        expanded = false
                        onChange(valueForLabel(option.theLabel()))
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * A dropdown selector over a dynamic list of (value, label) pairs, for cases where the options are
 * not a fixed enum (e.g. the set of microphones discovered at runtime). If [selectedValue] is not
 * among [options] the first option is displayed instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDynamicSelector(
    options: List<Pair<String, String>>,
    description: String,
    selectedValue: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Never leave the menu open if the control becomes disabled.
    if (!enabled && expanded) {
        expanded = false
    }

    // Derive the displayed label from the current selection, falling back to the first option
    // (typically "Automatic") if the selected value is no longer available:
    val selectedText = options.firstOrNull { it.first == selectedValue }?.second
        ?: options.firstOrNull()?.second
        ?: ""

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        TextField(
            value = selectedText,
            onValueChange = { },
            readOnly = true,
            enabled = enabled,
            label = { Text(description) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.textFieldColors()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onChange(value)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun <T> MySliderSelector(
    enumEntries: EnumEntries<T>,
    description: String,
    selectedValue: Int,
    onChange: (Int) -> Unit
) where T : Enum<T>, T : Settings.EnumHelper {

    // Find min and max values
    val minValue = enumEntries.minOf { it.theValue() }
    val maxValue = enumEntries.maxOf { it.theValue() }

    // Map value → label for display
    fun labelForValue(value: Int) = enumEntries.first { it.theValue() == value }.theLabel()

    // State for slider
    var sliderValue by rememberSaveable { mutableStateOf(selectedValue.toFloat()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$description: ${labelForValue(sliderValue.toInt())}")

        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
            },
            onValueChangeFinished = {
                onChange(sliderValue.toInt())
            },
            valueRange = minValue.toFloat()..maxValue.toFloat(),
            steps = enumEntries.size - 2, // exclude min/max
            modifier = Modifier.fillMaxWidth()
                /* .semantics {
                    contentDescription = "$description: ${labelForValue(sliderValue.toInt())}"
                } */
        )
    }
}

private val borderStroke = BorderStroke(Dp.Hairline, Color.Gray)

@Composable
fun MyTransparentLatchingButton(
    checked: MutableState<Boolean>,
    enabled: State<Boolean>,
    image: ImageVector,
    contentDescription: String,
    onSelectionChanged: (checked: Boolean) -> Unit
) {
    OutlinedIconToggleButton(
        checked = checked.value,
        enabled = enabled.value,
        border = borderStroke,
        onCheckedChange = {
            checked.value = it
            onSelectionChanged(checked.value)
        }
    ) {
        Icon(image, contentDescription = contentDescription)
    }
}

@Composable
fun MyButton(image: ImageVector, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shape = IconButtonDefaults.filledShape,
        enabled = enabled
    ) {
        Icon(image, contentDescription = contentDescription)
    }
}

@Composable
fun MyTransparentButton(
    image: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedIconButton(
        onClick = onClick,
        shape = IconButtonDefaults.filledShape,
        border = borderStroke,
        enabled = enabled
    ) {
        Icon(image, contentDescription = contentDescription)
    }
}

@Composable
fun MyLamp(dp: Dp, colour: Color) {
    Box(
        modifier = Modifier
            .size(dp)
            // .border(2.dp, Color.Gray, CircleShape)
            .clip(CircleShape)
            .background(colour)
    )
}

@Composable
fun MyLamp2(dp: Dp, colour: Color) {
    Box(
        modifier = Modifier.size(dp * 2), // room for glow
        contentAlignment = Alignment.Center
    ) {
        // Fixed outer glow (no animation)
        Box(
            modifier = Modifier
                .size(dp * 1.6f)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(colour.copy(alpha = 0.35f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        // Inner lamp
        Box(
            modifier = Modifier
                .size(dp)
                .shadow(8.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(colour, colour.copy(alpha = 0.6f))
                    )
                )
        )
    }
}
@Composable
fun MyCheckbox(label: String, initialValue: Boolean, onChange: (Boolean) -> Job) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        var isChecked by rememberSaveable { mutableStateOf(initialValue) }
        Checkbox(
            checked = isChecked,
            onCheckedChange = {
                isChecked = it
                onChange(it)
            })
    }
}

@Composable
fun MyLatchingButton(
    selected: MutableState<Boolean>,
    enabled: Boolean,
    image: ImageVector,
    contentDescription: String,
    iconTint: Color? = null,
    overlayImage: ImageVector? = null,
    overlayTint: Color? = null,
    overlaySize: Dp? = null,
    overlayAlignment: Alignment = Alignment.Center,
    onSelectionChanged: (checked: Boolean) -> Unit,
    onLongPress: ((checked: Boolean) -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColorBehind = Color.Transparent

    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        selected.value -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        selected.value -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    fun tintOrContentColor(tint: Color?): Color = when {
        tint != null && !enabled -> tint.copy(alpha = 0.5f)
        tint != null -> tint
        else -> contentColor
    }

    val iconColor = tintOrContentColor(iconTint)
    val overlayColor = tintOrContentColor(overlayTint)

    val clickModifier = if (onLongPress != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = if (enabled) ripple(bounded = true) else null,
            enabled = enabled,
            onClick = {
                selected.value = !selected.value
                onSelectionChanged(selected.value)
            },
            onLongClick = {
                onLongPress(selected.value)
            }
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = if (enabled) ripple(bounded = true) else null,
            enabled = enabled,
            onClick = {
                selected.value = !selected.value
                onSelectionChanged(selected.value)
            }
        )
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(backgroundColorBehind) // This acts as the border "color"
            .padding(4.dp)
            .clip(CircleShape)
            .background(containerColor)
            .then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides iconColor) {
            if (overlayImage == null) {
                Icon(
                    imageVector = image,
                    contentDescription = contentDescription
                )
            } else {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = image,
                        contentDescription = contentDescription,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (overlaySize != null) {
                        Box(
                            modifier = Modifier
                                .align(overlayAlignment)
                                .size(overlaySize)
                        ) {
                            Icon(
                                imageVector = overlayImage,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                tint = overlayColor
                            )
                        }
                    } else {
                        Icon(
                            imageVector = overlayImage,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            tint = overlayColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MyFloatSlider(label: String, format: String, initialValue: Float,
                  range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Job)
{
    var sliderValue by remember { mutableFloatStateOf(initialValue) } // Initial value

    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        val s = "$label: ${format.format(sliderValue)}"
        Text(text = s)

        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
            },
            onValueChangeFinished = {
                onChange(sliderValue)
            },
            valueRange = range
        )
    }
}

@Composable
fun MyFloatRangeSlider(
    label: String,
    format: String,
    initialRangeStart: Float,
    initialRangeEnd: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (ClosedFloatingPointRange<Float>) -> Job
) {
    var sliderValues by remember { mutableStateOf<ClosedFloatingPointRange<Float>>(
        initialRangeStart..initialRangeEnd) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "$label: ${format.format(sliderValues.start)} - ${format.format(sliderValues.endInclusive)}"
        )

        RangeSlider(
            value = sliderValues,
            onValueChange = { newRange: ClosedFloatingPointRange<Float> ->
                sliderValues = newRange
            },
            onValueChangeFinished = {
                onChange(sliderValues)
            },
            valueRange = valueRange
        )
    }
}

@Composable
fun MyIntRangeSlider(
    label: String,
    initialRangeStart: Int,
    initialRangeEnd: Int,
    valueRange: IntRange,
    onChange: (Int, Int) -> Job
) {
    var minKhz by rememberSaveable { mutableIntStateOf(initialRangeStart) }
    var maxKhz by rememberSaveable { mutableIntStateOf(initialRangeEnd) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("$label: $minKhz - $maxKhz kHz")

        RangeSlider(
            value = minKhz.toFloat()..maxKhz.toFloat(),
            onValueChange = { newRange ->
                val newMin = newRange.start.roundToInt()
                    .coerceIn(valueRange.first, valueRange.last)
                val newMax = newRange.endInclusive.roundToInt()
                    .coerceIn(valueRange.first, valueRange.last)
                minKhz = minOf(newMin, newMax)
                maxKhz = maxOf(newMin, newMax)
            },
            onValueChangeFinished = {
                onChange(minKhz, maxKhz)
            },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
        )
    }
}
