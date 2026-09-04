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
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.org.gimell.batgimzoapp.BuildConfig
import org.batgizmo.app.DocumentHelper
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.diagnosticLogger
import org.batgizmo.app.ui.theme.BatgizmoAppTheme
import timber.log.Timber

class TopLevelUI(private val model: UIModel) {

    private var previousSettings: Settings? = null

    // The basic two modes of the UI:
    enum class AppMode(val value: Int, val label: String) {
        // The names will appear in the UI as text:
        VIEWER(1, "Viewer"),
        LIVE(2, "Live");

        companion object {
            fun getText(value: Int): String {
                val match: AppMode? = entries.find { it.value == value }
                return match?.label ?: ""
            }
        }
    }

    /*
     Define state needed by this class that will be stored by the model for persistence.
     We have to do this for state that is needed outside the Compose context.
    */
    data class UIState(
        val settingsVisible: MutableState<Boolean> = mutableStateOf(false),
        val appMode: MutableIntState = mutableIntStateOf(AppMode.LIVE.value)
    )

    private var uiState: UIState = model.topLevelUIState

    private val settingsUI = SettingsUI(model)
    private val spectrogramUI = SpectrogramUI(model)


    @Composable
    fun Compose(model: UIModel) {

        /** Define global UI state here. Some of them are initialized from the default settings
         * values. The default values will be updated asynchronously when the stored preferences
         * values are available.
         */
        val settingsVisible = rememberSaveable { uiState.settingsVisible }
        val appMode = rememberSaveable { uiState.appMode }

        val useDarkTheme = rememberSaveable { mutableStateOf(model.settings.useDarkTheme) }
        val amplitudePaneVisibility =
            rememberSaveable { mutableIntStateOf(model.settings.amplitudePaneVisibility) }
        val showGrid = rememberSaveable { mutableStateOf(model.settings.showGrid) }
        val showHeterodyneReferenceLine =
            rememberSaveable { mutableStateOf(model.settings.showHeterodyneReferenceLine) }
        val overlayTextMode =
            rememberSaveable { mutableIntStateOf(model.settings.overlayTextMode) }
        val leftHandedMode = rememberSaveable { mutableStateOf(model.settings.leftHandButtons) }

        // Have we received the settings values yet?
        val settingsAvailable = rememberSaveable { mutableStateOf(false) }

        val context = LocalContext.current

        LaunchedEffect(Unit) {
            // Main UI thread.

            // Register a method to handle changes to the app mode. The following
            // method doesn't return, but it does release to other coroutines.
            model.resetAppModeFlow.collectLatest { mode ->
                onResetAppMode(context, mode)
            }
        }

        // This will be called asynchronously when values arrive from the data store:
        fun onSettingsUpdate() {
            // Log.d(this::class.simpleName, "onSettingsUpdate useDarkTheme = ${model.settings.useDarkTheme}")

            // Update UI state as required from the settings:
            useDarkTheme.value = model.settings.useDarkTheme
            amplitudePaneVisibility.intValue = model.settings.amplitudePaneVisibility
            showGrid.value = model.settings.showGrid
            showHeterodyneReferenceLine.value = model.settings.showHeterodyneReferenceLine
            overlayTextMode.intValue = model.settings.overlayTextMode
            leftHandedMode.value = model.settings.leftHandButtons

            // It's OK to draw the full UI now:
            settingsAvailable.value = true

            spectrogramUI.onSettingsUpdate(model.settings, previousSettings)
        }

        LaunchedEffect(Unit) {
            model.settingsReadyFlow.collectLatest {

                if (model.settings.enableLogging)
                    diagnosticLogger.startLogging(context)
                else
                    diagnosticLogger.stopLogging()

                // Log.d(this::class.simpleName, "Collect the value from settingsReadyFlow")
                onSettingsUpdate()
            }
        }

        // Track if we should show the exit confirmation dialog
        var showExitDialog by remember { mutableStateOf(false) }

        fun onExitApp() {
            showExitDialog = true // Show confirmation dialog instead of finishing immediately
        }

        BackHandler {
            // Handle backing out of the top level activity.

            Timber.d("Back pressed")
            onExitApp()
        }

        /**
         * Compose the UI. Avoid doing this before preferences are available to avoid
         * UI flicker on launch. Instead we momentarily get a blank screen, which is
         * less distracting.
         */
        if (settingsAvailable.value) {
            BatgizmoAppTheme(useDarkTheme = useDarkTheme.value) {
                Box(    // To apply any system bars padding, though that is zero if system bars are hidden.
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .systemBarsPadding()
                ) {
                    CompositionLocalProvider(
                        spectrogramUI.localShowGrid provides showGrid.value,
                        spectrogramUI.localShowHeterodyneReferenceLine provides
                            showHeterodyneReferenceLine.value,
                        spectrogramUI.localOverlayTextMode provides overlayTextMode.intValue
                    ) {
                        val configuration = LocalConfiguration.current
                        val orientation = remember { mutableIntStateOf(configuration.orientation) }
                        // Keep in sync across configuration changes; remember alone only
                        // captures the initial orientation.
                        LaunchedEffect(configuration.orientation) {
                            orientation.intValue = configuration.orientation
                        }

                        // Always display the main UI, so that state is preserved behind the
                        // settings UI:
                        spectrogramUI.Compose(
                            model,
                            amplitudePaneVisibility.intValue,
                            leftHandedMode.value,
                            settingsVisible,
                            orientation,
                            appMode,
                            ::onExitApp
                        )
                        if (settingsVisible.value) {
                            previousSettings = model.settings.copy()
                            settingsUI.Compose {
                                settingsVisible.value = false
                                // Log.d(this::class.simpleName, "onBack callback called")
                                onSettingsUpdate()
                            }
                        }
                    }

                    // Compose these inside the app theme so dialogs match the rest of the UI.
                    val showWhatsNew by model.showWhatsNew.collectAsStateWithLifecycle()
                    var showReleaseNotesFromWhatsNew by rememberSaveable { mutableStateOf(false) }
                    var dontShowUpdateAgain by rememberSaveable { mutableStateOf(false) }

                    if (showWhatsNew) {
                        AlertDialog(
                            onDismissRequest = { model.dismissWhatsNew(dontShowUpdateAgain) },
                            title = { Text("Updated") },
                            text = {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("This app has been updated to version ${BuildConfig.VERSION_NAME}.")
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = dontShowUpdateAgain,
                                            onCheckedChange = { dontShowUpdateAgain = it }
                                        )
                                        Text("Don't show me this again")
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    model.dismissWhatsNew(dontShowUpdateAgain)
                                    showReleaseNotesFromWhatsNew = true
                                }) {
                                    Text("Release Notes")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { model.dismissWhatsNew(dontShowUpdateAgain) }) {
                                    Text("Close")
                                }
                            }
                        )
                    }

                    if (showReleaseNotesFromWhatsNew) {
                        ReleaseNotesDialog(onDismiss = {
                            showReleaseNotesFromWhatsNew = false
                        })
                    }
                }
            }
        }

        val activity = LocalActivity.current as? ComponentActivity

        if (showExitDialog) {
            val appName = context.applicationInfo.loadLabel(context.packageManager).toString()

            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("Exit $appName") },
                text = { Text("Are you sure you want to exit? This will stop any live recording and audio in progress.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            activity?.finish() // Finish activity if user confirms
                        }
                    ) {
                        Text("Yes")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showExitDialog = false } // Do nothing if user cancels
                    ) {
                        Text("No")
                    }
                }
            )
        }
    }

    fun processViewIntent(context: Context, lifecycleScope: LifecycleCoroutineScope,
                          viewModel: UIModel, uri: Uri) {
        lifecycleScope.launch {
            // In case we are currently viewing in multiple file mode:
            model.documentHelper.reset()
            // Main UI thread.
            model.resetUIMode(AppMode.VIEWER,
                DocumentHelper.UriData(uri, false, false))
        }
    }

    private fun onResetAppMode(context: Context, request: UIModel.AppModeRequest) {
        /*
            Set the app to the app mode supplied, resetting to base state for that mode.
            If we we already in that mode, just reset it.
        */

        val previousMode = uiState.appMode.intValue

        // Reset local UI state accordingly:
        uiState.appMode.intValue = request.mode.value
        uiState.settingsVisible.value = false


        when (request.mode) {
            AppMode.VIEWER -> {
                spectrogramUI.resetToViewer(context, previousMode, request.uriData)
            }
            AppMode.LIVE -> {
                spectrogramUI.resetToLive(context, previousMode, request.streaming)
            }
        }
    }
}

