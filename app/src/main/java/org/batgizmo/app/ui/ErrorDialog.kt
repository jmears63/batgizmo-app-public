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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    message: String,
    confirmText: String = "OK",
    dismissText: String = "Cancel",
    checkboxLabel: String? = null,
    checkboxChecked: Boolean = false,
    onCheckboxChange: ((Boolean) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(message)
                if (checkboxLabel != null && onCheckboxChange != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checkboxChecked,
                            onCheckedChange = onCheckboxChange
                        )
                        Text(checkboxLabel)
                    }
                }
            }
        }
    )
}

/**
 * A dialog that asks the user to pick one option from a list (e.g. an internal microphone).
 * [options] is a list of (id, label) pairs; the chosen id is passed to [onConfirm].
 */
@Composable
fun MicSelectionDialog(
    title: String,
    message: String,
    options: List<Pair<String, String>>,
    initialSelectedId: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    confirmText: String = "Use",
    dismissText: String = "Cancel",
) {
    // Start on the previously chosen option if it is still available, otherwise the first option.
    val selectedId = remember(initialSelectedId, options) {
        mutableStateOf(
            if (options.any { it.first == initialSelectedId }) initialSelectedId
            else options.firstOrNull()?.first ?: ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedId.value) }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(message)
                Spacer(Modifier.height(12.dp))
                MyDynamicSelector(
                    options = options,
                    description = "Microphone",
                    selectedValue = selectedId.value
                ) { value -> selectedId.value = value }
            }
        }
    )
}

@Composable
fun ErrorDialog(
    onDismiss: () -> Unit,
    errorText: String
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
        title = { Text("Error") },
        text = {
            Row(Modifier.fillMaxWidth()) {
                Text(errorText)
            }
        }
    )
}
