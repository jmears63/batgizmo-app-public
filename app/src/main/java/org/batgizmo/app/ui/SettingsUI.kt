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

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.diagnosticLogger
import org.batgizmo.app.ml.BattyBirdNET
import org.batgizmo.app.ml.LabelCatalogEntry
import java.util.Locale

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

        // Track the live input source locally so the microphone selector can show/hide reactively
        // (model.settings is a plain var and does not trigger recomposition on its own):
        var liveInputSource by rememberSaveable { mutableStateOf(model.settings.liveInputSource) }
        var internalMicId by rememberSaveable { mutableStateOf(model.settings.internalMicId) }
        var unlimitedFileLength by rememberSaveable { mutableStateOf(model.settings.unlimitedFileLength) }
        var wavStorageVolume by rememberSaveable { mutableStateOf(model.settings.wavStorageVolume) }

        // Expand/collapse state for each collapsible section, indexed by SettingsSection.ordinal.
        // Held here (rather than inside the list items) so the LazyColumn can gate which sections'
        // rows are emitted, and saved so the open/closed state survives configuration changes.
        val expandedSections = rememberSaveable(
            saver = listSaver(
                save = { it.toList() },
                restore = { it.toMutableStateList() }
            )
        ) {
            SettingsSection.entries.map { false }.toMutableStateList()
        }

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

            settingsSection(SettingsSection.APPEARANCE, expandedSections) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyListSelector<Settings.OverlayTextModeOptions>(
                            Settings.OverlayTextModeOptions.entries,
                            "Overlay text",
                            model.settings.overlayTextMode
                        ) { value: Int ->
                            scope.launch {
                                model.updateStoredSettings(
                                    model.settings.copy(overlayTextMode = value)
                                )
                            }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyListSelector<Settings.ColourMapOptions>(
                            Settings.ColourMapOptions.entries,
                            "Spectrogram colour map",
                            model.settings.colourMap
                        ) { value: Int ->
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(colourMap = value))
                            }
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
            }

            settingsSection(SettingsSection.AUDIO_INPUT, expandedSections) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyListSelector<Settings.LiveInputSourceOptions>(
                            Settings.LiveInputSourceOptions.entries,
                            "Live audio source",
                            model.settings.liveInputSource
                        ) { value: Int ->
                            liveInputSource = value
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(liveInputSource = value))
                            }
                        }
                    }
                }

                // Microphone selection is only relevant when the internal microphone is the
                // source, but the selector stays visible (greyed and disabled) otherwise.
                item {
                    val micEnabled = liveInputSource == Settings.LiveInputSourceOptions.PHONE_MIC.value
                    // Discover the available internal microphones for the selector.
                    val micOptions = remember {
                        model.availableInternalMics().map { it.id to it.label }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyDynamicSelector(
                            options = micOptions,
                            description = "Internal microphone",
                            selectedValue = internalMicId,
                            enabled = micEnabled
                        ) { value: String ->
                            internalMicId = value
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(internalMicId = value))
                            }
                        }
                    }
                }
            }

            settingsSection(SettingsSection.AUDIO_OUTPUT, expandedSections) {
                item {
                    MyCheckbox(
                        "Auto Gain Control", model.settings.audioAGCEnabled
                    ) { value: Boolean ->
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(audioAGCEnabled = value))
                        }
                    }
                }
                item {
                    MyCheckbox(
                        "Pitch shift HPF (reduces noise)",
                        model.settings.audioPitchHpfEnabled
                    ) { value: Boolean ->
                        scope.launch {
                            model.updateStoredSettings(
                                model.settings.copy(audioPitchHpfEnabled = value)
                            )
                        }
                    }
                }
                item {
                    MyCheckbox(
                        "Show auto heterodyne reference",
                        model.settings.showHeterodyneReferenceLine
                    ) { value: Boolean ->
                        scope.launch {
                            model.updateStoredSettings(
                                model.settings.copy(showHeterodyneReferenceLine = value)
                            )
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyIntRangeSlider(
                            "Auto heterodyne range",
                            model.settings.autoHeterodyneLoMinKhz,
                            model.settings.autoHeterodyneLoMaxKhz,
                            Settings.AUTO_HET_LO_LIMIT_MIN_KHZ..
                                Settings.AUTO_HET_LO_LIMIT_MAX_KHZ
                        ) { minKhz: Int, maxKhz: Int ->
                            scope.launch {
                                model.updateStoredSettings(
                                    model.settings.copy(
                                        autoHeterodyneLoMinKhz = minKhz,
                                        autoHeterodyneLoMaxKhz = maxKhz
                                    )
                                )
                            }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyListSelector<Settings.AutoHeterodyneModeOptions>(
                            Settings.AutoHeterodyneModeOptions.entries,
                            "Auto Heterodyne Optimisation",
                            model.settings.autoHeterodyneMode
                        ) { value: Int ->
                            scope.launch {
                                model.updateStoredSettings(
                                    model.settings.copy(autoHeterodyneMode = value)
                                )
                            }
                        }
                    }
                }
            }

            settingsSection(SettingsSection.AUTO_BNC, expandedSections) {
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
                    MyCheckbox(
                        "Noise profile correction", model.settings.autoBaselineEnabled
                    ) { value: Boolean ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(autoBaselineEnabled = value))
                        }
                    }
                }
            }

            settingsSection(SettingsSection.RENDERING, expandedSections) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyListSelector<Settings.DefaultLiveTimeSpanOptions>(
                            Settings.DefaultLiveTimeSpanOptions.entries,
                            "Live acquisition time span",
                            model.settings.defaultLiveTimeSpanS
                        ) { value: Int ->
                            // Signal the updated settings values:
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(defaultLiveTimeSpanS = value))
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
                            model.settings.pipelineParameters.nFft
                        ) { value: Int ->
                            // Signal the updated settings values:
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(
                                    pipelineParameters = model.settings.pipelineParameters.copy(nFft = value)))

                            }
                        }
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyListSelector<Settings.FftOverlapOptions>(
                            Settings.FftOverlapOptions.entries,
                            "FFT window overlap",
                            model.settings.pipelineParameters.fftOverlapPercent
                        ) { value: Int ->
                            // Signal the updated settings values:
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(
                                    pipelineParameters = model.settings.pipelineParameters.copy(fftOverlapPercent = value)))
                            }
                        }
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyListSelector<Settings.DataBufferTimeSpanOptions>(
                            Settings.DataBufferTimeSpanOptions.entries,
                            "Maximum viewable time span (higher values degrade time resolution, restart needed)",
                            model.settings.pipelineParameters.dataPageTimeSpanS
                        ) { value: Int ->
                            // Signal the updated settings values:
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(
                                    pipelineParameters = model.settings.pipelineParameters.copy(dataPageTimeSpanS = value)))
                            }
                        }
                    }
                }
            }

            settingsSection(SettingsSection.RECORDING, expandedSections) {
                item {
                    MyCheckbox(
                        "Include location in files", model.settings.includeLocationInFile
                    ) { value: Boolean ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(includeLocationInFile = value))
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
                            model.settings.maxFileTimeMs,
                            enabled = !unlimitedFileLength
                        ) { value: Int ->
                            // Signal the updated settings values:
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(maxFileTimeMs = value))
                            }
                        }
                    }
                }

                item {
                    MyCheckbox(
                        "Unlimited file length", unlimitedFileLength
                    ) { value: Boolean ->
                        unlimitedFileLength = value
                        scope.launch {
                            model.updateStoredSettings(
                                model.settings.copy(unlimitedFileLength = value)
                            )
                        }
                    }
                }

                item {
                    val wavVolumeOptions = remember(wavStorageVolume) {
                        wavStorageVolumeOptions(context, wavStorageVolume)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyDynamicSelector(
                            options = wavVolumeOptions,
                            description = "WAV file storage location",
                            selectedValue = wavStorageVolume
                        ) { value: String ->
                            wavStorageVolume = value
                            scope.launch {
                                model.updateStoredSettings(
                                    model.settings.copy(wavStorageVolume = value)
                                )
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
                            10f..120f) {
                                range: ClosedFloatingPointRange<Float> ->
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(
                                    autoTriggerRangeMinkHz = range.start, autoTriggerRangeMaxkHz = range.endInclusive))
                            }
                        }
                    }
                }
            }

            settingsSection(SettingsSection.WARNINGS, expandedSections) {
                item {
                    MyCheckbox(
                        "Suppress audio feedback warnings", model.settings.suppressAudioFeedbackWarning
                    ) { value: Boolean ->
                        // Signal the updated settings values:
                        scope.launch {
                            model.updateStoredSettings(model.settings.copy(suppressAudioFeedbackWarning = value))
                        }
                    }
                }

                item {
                    MyCheckbox(
                        "Suppress update info notification", model.settings.suppressUpdateNotification
                    ) { value: Boolean ->
                        scope.launch {
                            model.updateStoredSettings(
                                model.settings.copy(suppressUpdateNotification = value)
                            )
                        }
                    }
                }

                item {
                    MyCheckbox(
                        "Suppress startup helper",
                        model.settings.suppressHighRateMicOffer
                    ) { value: Boolean ->
                        scope.launch {
                            model.updateStoredSettings(
                                model.settings.copy(suppressHighRateMicOffer = value)
                            )
                        }
                    }
                }
            }

            settingsSection(SettingsSection.AUTO_ID, expandedSections) {
                item {
                    var autoIdEnabled by rememberSaveable {
                        mutableStateOf(model.settings.autoId)
                    }
                    var showSuppressions by rememberSaveable { mutableStateOf(false) }
                    val labelCatalog = remember {
                        BattyBirdNET.loadLabelCatalog(context.assets)
                    }
                    val languageIndex = model.settings.autoIdLanguage
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MyCheckbox(
                            "BattyBirdNET", autoIdEnabled
                        ) { value: Boolean ->
                            autoIdEnabled = value
                            scope.launch {
                                model.updateStoredSettings(model.settings.copy(autoId = value))
                            }
                        }
                        Button(
                            onClick = { showSuppressions = true },
                            enabled = autoIdEnabled
                        ) {
                            Text("Suppressions")
                        }
                    }
                    if (showSuppressions) {
                        val suppressible = remember(labelCatalog) {
                            labelCatalog.filter { !it.discard }
                        }
                        AutoIdSuppressionsDialog(
                            entries = suppressible,
                            languageIndex = languageIndex,
                            suppressions = model.settings.bbnSuppresions,
                            onDismiss = { showSuppressions = false },
                            onConfirm = { updated ->
                                scope.launch {
                                    model.updateStoredSettings(
                                        model.settings.copy(bbnSuppresions = updated)
                                    )
                                    showSuppressions = false
                                }
                            },
                        )
                    }
                }

                item {
                    val languages = remember {
                        BattyBirdNET.loadLabelLanguages(context.assets)
                    }
                    val languageOptions = remember(languages) {
                        languages.mapIndexed { index, name -> index.toString() to name }
                    }
                    var autoIdLanguage by rememberSaveable {
                        mutableStateOf(model.settings.autoIdLanguage)
                    }
                    // Clamp a stored index that is no longer valid for this JSON.
                    val selectedIndex =
                        if (autoIdLanguage in languages.indices) autoIdLanguage else 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MyDynamicSelector(
                            options = languageOptions,
                            description = "Auto Id language",
                            selectedValue = selectedIndex.toString()
                        ) { value: String ->
                            val index = value.toIntOrNull()?.takeIf { it in languages.indices } ?: 0
                            autoIdLanguage = index
                            scope.launch {
                                model.updateStoredSettings(
                                    model.settings.copy(autoIdLanguage = index)
                                )
                            }
                        }
                    }
                }
            }

            settingsSection(SettingsSection.DIAGNOSTICS, expandedSections) {
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
}

/**
 * Modal listing Auto Id classes that are not discarded. Checkboxes bind to
 * [suppressions] (settings [Settings.bbnSuppresions]); missing keys fall back
 * to each entry's `disable_by_default`.
 */
@Composable
private fun AutoIdSuppressionsDialog(
    entries: List<LabelCatalogEntry>,
    languageIndex: Int,
    suppressions: Map<String, Boolean>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Boolean>) -> Unit,
) {
    val disabled = remember(entries, suppressions) {
        mutableStateMapOf<String, Boolean>().apply {
            entries.forEach { entry ->
                put(
                    entry.key,
                    suppressions[entry.key] ?: entry.disableByDefault,
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ids to Ignore") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                items(entries, key = { it.key }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            entry.displayName(languageIndex),
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Checkbox(
                            checked = disabled[entry.key] == true,
                            onCheckedChange = { disabled[entry.key] = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(disabled.toMap()) }) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dropdown options for WAV storage: Default, currently attached MediaStore volumes with
 * friendly labels, and an "Unavailable" entry if [selectedVolume] is set but missing.
 */
private fun wavStorageVolumeOptions(
    context: Context,
    selectedVolume: String
): List<Pair<String, String>> {
    val storageManager = context.getSystemService(StorageManager::class.java)
    val attachedVolumes = MediaStore.getExternalVolumeNames(context)

    val fromStorageVolumes = storageManager.storageVolumes.mapNotNull { volume ->
        val name = mediaStoreVolumeName(volume) ?: return@mapNotNull null
        if (name !in attachedVolumes) return@mapNotNull null
        name to friendlyVolumeLabel(context, volume, name)
    }

    val labelledNames = fromStorageVolumes.map { it.first }.toSet()
    // Attached MediaStore volumes with no matching StorageVolume entry:
    val unmatchedAttached = attachedVolumes
        .filter { it !in labelledNames }
        .sorted()
        .map { it to fallbackVolumeLabel(it) }

    val labelled = (fromStorageVolumes + unmatchedAttached)
        .sortedBy { it.first }
        .let { disambiguateDuplicateLabels(it) }

    return buildList {
        add("" to "Default")
        addAll(labelled)
        if (selectedVolume.isNotEmpty() && selectedVolume !in attachedVolumes) {
            add(selectedVolume to "Unavailable ($selectedVolume)")
        }
    }
}

/** Prefer [StorageVolume.getDescription], but replace UUID/hex-like OEM labels. */
private fun friendlyVolumeLabel(
    context: Context,
    volume: StorageVolume,
    volumeName: String
): String {
    val description = volume.getDescription(context).trim()
    if (description.isNotEmpty() && !looksLikeVolumeId(description, volumeName, volume.uuid)) {
        return description
    }
    return when {
        volume.isPrimary -> "Internal shared storage"
        volume.isRemovable -> "SD card"
        else -> "External storage"
    }
}

private fun fallbackVolumeLabel(volumeName: String): String {
    return if (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
        "Internal shared storage"
    } else {
        "External storage"
    }
}

/** True when [text] is effectively a volume UUID / MediaStore id, not a human label. */
private fun looksLikeVolumeId(text: String, volumeName: String, uuid: String?): Boolean {
    if (text.equals(volumeName, ignoreCase = true)) return true
    if (uuid != null && text.equals(uuid, ignoreCase = true)) return true
    // FAT-style volume id (e.g. 12F7-270F) or other hex/UUID forms.
    if (text.matches(Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$"))) return true
    if (text.matches(Regex("^[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}$"))) return true
    if (text.matches(Regex("^[0-9A-Fa-f]{8,}$"))) return true
    return false
}

/** If several volumes share a label, append a short id so they stay distinguishable. */
private fun disambiguateDuplicateLabels(
    entries: List<Pair<String, String>>
): List<Pair<String, String>> {
    val counts = entries.groupingBy { it.second }.eachCount()
    return entries.map { (name, label) ->
        if ((counts[label] ?: 0) > 1) name to "$label ($name)" else name to label
    }
}

/** MediaStore volume id for [volume], or null if it is not indexed by MediaStore. */
private fun mediaStoreVolumeName(volume: StorageVolume): String? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        volume.mediaStoreVolumeName
    } else if (volume.isPrimary) {
        MediaStore.VOLUME_EXTERNAL_PRIMARY
    } else {
        volume.uuid?.uppercase(Locale.US)
    }
}

/** The collapsible sections shown in the settings screen, in display order. */
private enum class SettingsSection(val title: String) {
    APPEARANCE("Appearance"),
    AUDIO_INPUT("Audio Source"),
    AUDIO_OUTPUT("Audio Output & Playback"),
    AUTO_BNC("Auto Brightness/Contrast"),
    RENDERING("Rendering"),
    RECORDING("Recording"),
    WARNINGS("Warnings"),
    AUTO_ID("Auto Id"),
    DIAGNOSTICS("Diagnostics"),
}

/**
 * Emit a collapsible settings [section]: a clickable header row followed by [content]'s items,
 * the latter only while the section is expanded. [expandedState] holds one flag per section,
 * indexed by [SettingsSection.ordinal].
 */
private fun LazyListScope.settingsSection(
    section: SettingsSection,
    expandedState: SnapshotStateList<Boolean>,
    content: LazyListScope.() -> Unit
) {
    item(key = section.name) {
        SettingsSectionHeader(
            title = section.title,
            expanded = expandedState[section.ordinal]
        ) {
            // Accordion behaviour: expanding a section collapses all others; tapping the
            // currently-open section just closes it.
            val willExpand = !expandedState[section.ordinal]
            for (i in expandedState.indices) {
                expandedState[i] = i == section.ordinal && willExpand
            }
        }
    }
    if (expandedState[section.ordinal]) {
        content()
    }
}

/** A clickable section header with a chevron that rotates to indicate expanded/collapsed state. */
@Composable
private fun SettingsSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    // Animate the chevron between pointing down (collapsed) and up (expanded).
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevronRotation"
    )
    Column {
        HorizontalDivider(thickness = 2.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                modifier = Modifier.rotate(chevronRotation)
            )
        }
    }
}