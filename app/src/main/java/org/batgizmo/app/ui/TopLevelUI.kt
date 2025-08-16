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

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.diagnosticLogger
import org.batgizmo.app.ui.theme.BatgizmoAppTheme

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
        val leftHandedMode = rememberSaveable { mutableStateOf(model.settings.leftHandButtons) }

        // Have we received the settings values yet?
        val settingsAvailable = rememberSaveable { mutableStateOf(false) }

        val context = LocalContext.current

        LaunchedEffect(Unit) {
            // Main UI thread.

            // Register a method to handle changes to the app mode. The following
            // method doesn't return, but it does release to other co routines.
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
            leftHandedMode.value = model.settings.leftHandButtons

            // It's OK to draw the full UI now:
            settingsAvailable.value = true

            spectrogramUI.onSettingsUpdate(model.settings, previousSettings)
            if (model.settings.enableLogging)
                diagnosticLogger.startLogging(context)
            else
                diagnosticLogger.stopLogging()
        }

        LaunchedEffect(Unit) {
            model.settingsReadyFlow.collectLatest {
                // Log.d(this::class.simpleName, "Collect the value from settingsReadyFlow")
                onSettingsUpdate()
            }
        }

        /**
         * Compose the UI. Avoid doing this before preferences are available to avoid
         * UI flicker on launch. Instead we momentarily get a blank screen, which is
         * less distracting.
         */
        if (settingsAvailable.value) {
            BatgizmoAppTheme(useDarkTheme = useDarkTheme.value) {
                CompositionLocalProvider(spectrogramUI.localShowGrid provides showGrid.value) {
                    val configuration = LocalConfiguration.current
                    val orientation = remember { mutableIntStateOf(configuration.orientation) }

                    // Always display the main UI, so that state is preserved behind the
                    // settings UI:
                    spectrogramUI.Compose(model,
                        amplitudePaneVisibility.intValue,
                        leftHandedMode.value,
                        settingsVisible,
                        orientation,
                        appMode
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
            }
        }
    }

    fun processViewIntent(context: Context, lifecycleScope: LifecycleCoroutineScope,
                          viewModel: UIModel, uri: Uri) {
        lifecycleScope.launch {
            // Main UI thread.
            model.resetUIMode(AppMode.VIEWER, uri)
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
                spectrogramUI.resetToViewer(context, previousMode, request.uri)
            }
            AppMode.LIVE -> {
                spectrogramUI.resetToLive(context, previousMode, request.streaming)
            }
        }
    }
}

