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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.diagnosticLogger

class SettingsUI(private val model: UIModel) {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Compose(onBack: () -> Unit) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    // Colours will be obtained from the enclosing theme, no need
                    // to specify theme here.

                    title = {
                        Text(
                            "Batgizmo Settings", maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },

                    navigationIcon = {
                        IconButton(onClick = { onBack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            SettingsDetails(modifier = Modifier, innerPadding)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsDetails(
        modifier: Modifier,
        innerPadding: PaddingValues
    ) {
        // Get a main UI scope to use for coroutines:
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        var diagnosticsExist = rememberSaveable { mutableStateOf(diagnosticLogger.logFileExists(context)) }
        var loggingEnabled = rememberSaveable { mutableStateOf(model.settings.enableLogging) }

        val enableShareDiagnostics = remember { derivedStateOf { diagnosticsExist.value || loggingEnabled.value} }
        val enableClearDiagnostics = remember { derivedStateOf { diagnosticsExist.value && !loggingEnabled.value } }

        LazyColumn(
            modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // TODO review hard coded left margin

            item {
                Text("General")
            }

            item {
                MyCheckbox(
                    "Dark theme", model.settings.useDarkTheme
                ) { value: Boolean ->
                    // Signal the updated settings values:
                    scope.launch {
                        model.updateStoredSettings(model.settings.copy(useDarkTheme = value))
                    }
                }
            }

            item {
                MyCheckbox(
                    "Show parameter overlay", model.settings.showParameterOverlay
                ) { value: Boolean ->
                    // Signal the updated settings values:
                    scope.launch {
                        model.updateStoredSettings(model.settings.copy(showParameterOverlay = value))
                    }
                }
            }

            item {
                MyCheckbox(
                    "Show grid", model.settings.showGrid
                ) { value: Boolean ->
                    // Signal the updated settings values:
                    scope.launch {
                        model.updateStoredSettings(model.settings.copy(showGrid = value))
                    }
                }
            }

            item {
                MyCheckbox(
                    "Buttons on the left", model.settings.leftHandButtons
                ) { value: Boolean ->
                    // Signal the updated settings values:
                    scope.launch {
                        model.updateStoredSettings(model.settings.copy(leftHandButtons = value))
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyListSelector<Settings.VisibilityOptions>(
                        Settings.VisibilityOptions.entries,
                        "Display amplitude pane",
                        model.settings.amplitudePaneVisibility
                    ) { value: Int ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(amplitudePaneVisibility = value))
                        }
                    }
                }
            }

            item {
                HorizontalDivider(thickness = 2.dp)
                Text("Auto Brightness/Contrast")
            }

            item {
                MyCheckbox(
                    "Viewer mode", model.settings.autoBnCEnabledViewer
                ) { value: Boolean ->
                    // Signal the updated settings values:
                    scope.launch {
                        model.updateStoredSettings(model.settings.copy(autoBnCEnabledViewer = value))
                    }
                }
            }

            item {
                MyCheckbox(
                    "Live mode", model.settings.autoBnCEnabledLive
                ) { value: Boolean ->
                    // Signal the updated settings values:
                    scope.launch {
                        model.updateStoredSettings(model.settings.copy(autoBnCEnabledLive = value))
                    }
                }
            }

            item {
                HorizontalDivider(thickness = 2.dp)
                Text("Rendering")
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyListSelector<Settings.DataBufferIntervalOptions>(
                        Settings.DataBufferIntervalOptions.entries,
                        "Data buffer length",
                        model.settings.dataPageIntervalS
                    ) { value: Int ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(dataPageIntervalS = value))
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyListSelector<Settings.PagingOverlapOptions>(
                        Settings.PagingOverlapOptions.entries,
                        "Large file paging overlap",
                        model.settings.pageOverlapPercent
                    ) { value: Int ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(pageOverlapPercent = value))
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyListSelector<Settings.NFftOptions>(
                        Settings.NFftOptions.entries,
                        "FFT window size",
                        model.settings.nFft
                    ) { value: Int ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(nFft = value))
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyListSelector<Settings.FftOverlapOptions>(
                        Settings.FftOverlapOptions.entries,
                        "FFT window overlap",
                        model.settings.fftOverlapPercent
                    ) { value: Int ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(fftOverlapPercent = value))
                        }
                    }
                }
            }

            item {
                HorizontalDivider(thickness = 2.dp)
                Text("Recording")
            }

            item {
                MyCheckbox(
                    "Include location in files", model.settings.locationInFile
                ) { value: Boolean ->
                    // Signal the updated settings values:
                    scope.launch {
                        model.updateStoredSettings(model.settings.copy(locationInFile = value))
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyListSelector<Settings.PreTriggerTimeOptions>(
                        Settings.PreTriggerTimeOptions.entries,
                        "Pre trigger",
                        model.settings.preTriggerTimeMs
                    ) { value: Int ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(preTriggerTimeMs = value))
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyListSelector<Settings.PostTriggerTimeOptions>(
                        Settings.PostTriggerTimeOptions.entries,
                        "Post trigger",
                        model.settings.postTriggerTimeMs
                    ) { value: Int ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(postTriggerTimeMs = value))
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyListSelector<Settings.MaxFileTimeOptions>(
                        Settings.MaxFileTimeOptions.entries,
                        "Maximum file length",
                        model.settings.maxFileTimeMs
                    ) { value: Int ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(maxFileTimeMs = value))
                        }
                    }
                }
            }

            item {
                HorizontalDivider(thickness = 2.dp)

                // Remember a coroutine scope tied to Compose lifecycle
                val scope = rememberCoroutineScope()

                // State to hold the current color
                var color by remember { mutableStateOf(androidx.compose.ui.graphics.Color.DarkGray) }

                // Collect events from the channel in a LaunchedEffect
                LaunchedEffect(model.triggerMonitorChannel) {
                    var redTimeoutJob: Job? = null

                    for (event in model.triggerMonitorChannel) {
                        // Set color to red immediately
                        color = androidx.compose.ui.graphics.Color.Red

                        // Cancel any existing timeout job
                        redTimeoutJob?.cancel()

                        // Start a new timeout coroutine to reset color after 500ms
                        redTimeoutJob = launch {
                            delay(500)
                            color = androidx.compose.ui.graphics.Color.DarkGray
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trigger")
                    MyLamp2(20.dp, color)
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyFloatSlider("Trigger threshold (dB)", "%.1f",
                        model.settings.autoTriggerThresholdDb, -25f..70f) {
                        value: Float ->
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(autoTriggerThresholdDb = value))
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyFloatRangeSlider("Trigger range (kHz)", "%.1f",
                        model.settings.autoTriggerRangeMinkHz,
                        model.settings.autoTriggerRangeMaxkHz,
                        15f..120f) {
                            range: ClosedFloatingPointRange<Float> ->
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(
                                autoTriggerRangeMinkHz = range.start, autoTriggerRangeMaxkHz = range.endInclusive))
                        }
                    }
                }
            }
            item {
                HorizontalDivider(thickness = 2.dp)
                Text("DiagnosticLogger")
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {

                    MyCheckbox(
                        "Enable diagnostic logging", model.settings.enableLogging
                    ) { checked: Boolean ->
                        loggingEnabled.value = checked
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(enableLogging = checked))
                        }
                    }
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    Button(
                        onClick = {
                            diagnosticLogger.shareDiagnosticsLog(context)
                            diagnosticsExist.value = diagnosticLogger.logFileExists(context)
                        },
                        enabled = enableShareDiagnostics.value
                    ) {
                        Text("Share Data")
                    }
                    Button(
                        onClick = {
                            diagnosticLogger.clearData(context)
                            diagnosticsExist.value = diagnosticLogger.logFileExists(context)
                        },
                        enabled = enableClearDiagnostics.value
                    ) {
                        Text("Clear Data")
                    }
                }
            }
        }
    }
}