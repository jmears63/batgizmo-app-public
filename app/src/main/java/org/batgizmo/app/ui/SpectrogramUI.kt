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

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.batgizmo.app.DocumentHelper
import org.batgizmo.app.FileWriter
import org.batgizmo.app.FileWriter.TriggerType
import org.batgizmo.app.HORange
import org.batgizmo.app.OpenWavFileResult
import org.batgizmo.app.PipelineParameters
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.diagnosticLogger
import org.batgizmo.app.pipeline.AbstractPipeline
import org.batgizmo.app.pipeline.UsbService
import org.batgizmo.app.ui.TopLevelUI.AppMode
import timber.log.Timber
import uk.org.gimell.batgimzoapp.BuildConfig
import uk.org.gimell.batgimzoapp.R
import java.util.Locale
import kotlin.Boolean
import kotlin.math.floor

class SpectrogramUI(
    private val model: UIModel
) {
    companion object {
    }

    val localShowGrid = compositionLocalOf<Boolean> { true }

    // Represent the state of buttons in the UI:
    data class ButtonState(
        val acquisitionChecked: MutableState<Boolean> = mutableStateOf(false),
        val acquisitionEnabled: MutableState<Boolean> = mutableStateOf(false),
        val manualRecordingChecked: MutableState<Boolean> = mutableStateOf(false),
        val manualRecordingEnabled: MutableState<Boolean> = mutableStateOf(false),
        val triggeredRecordingChecked: MutableState<Boolean> = mutableStateOf(false),
        val triggeredRecordingEnabled: MutableState<Boolean> = mutableStateOf(false),
        val audioChecked: MutableState<Boolean> = mutableStateOf(false),
        val audioEnabled: MutableState<Boolean> = mutableStateOf(false),
        val slidersButtonChecked: MutableState<Boolean> = mutableStateOf(false),
        val slidersButtonEnabled: MutableState<Boolean> = mutableStateOf(false),
        val showMetadataEnabled: MutableState<Boolean> = mutableStateOf(false),
        val closeFileEnabled: MutableState<Boolean> = mutableStateOf(false),
        val previousFileEnabled: MutableState<Boolean> = mutableStateOf(false),
        val nextFileEnabled: MutableState<Boolean> = mutableStateOf(false)
    ) {
        fun reset() {
            acquisitionChecked.value = false
            acquisitionEnabled.value = false
            manualRecordingChecked.value = false
            manualRecordingEnabled.value = false
            triggeredRecordingChecked.value = false
            triggeredRecordingEnabled.value = false
            audioChecked.value = false
            audioEnabled.value = false
            slidersButtonChecked.value = false
            slidersButtonEnabled.value = false
            showMetadataEnabled.value = false
            closeFileEnabled.value = false
            previousFileEnabled.value = false
            nextFileEnabled.value = false
        }
    }

    /*
         Define state needed by this class that will be stored by the model for persistence.
         We have to do this for state that is needed outside the Compose context.
     */
    data class UIState(
        val fileIsOpen: MutableState<Boolean> = mutableStateOf(false),
        val title: MutableState<String?> = mutableStateOf(null),
        val menuExpanded: MutableState<Boolean> = mutableStateOf(false),
        val showMetadata: MutableState<Boolean> = mutableStateOf(false),
        val showErrorDialog: MutableState<Boolean> = mutableStateOf(false),
        val errorMessage: MutableState<String> = mutableStateOf(""),
        val processingFlag: MutableState<Boolean> = mutableStateOf(false),
        val pagingState: MutableState<PagingStateHandler?> = mutableStateOf(null),
        val pagingEnabled: MutableState<Boolean> = mutableStateOf(false),
        val rawPageRange: MutableState<HORange?> = mutableStateOf(null),
        val pageLeftEnabled: MutableState<Boolean> = mutableStateOf(false),
        val pageRightEnabled: MutableState<Boolean> = mutableStateOf(false),
        val liveMode: MutableIntState = mutableIntStateOf(LiveMode.OFF.value),
        val audioMode: MutableIntState = mutableIntStateOf(AudioMode.OFF.value),
        val showAudioConfig: MutableState<Boolean> = mutableStateOf(false),
        val audioSettingsAlreadyShown: MutableState<Boolean> = mutableStateOf(false),
        val heterodyneRef1kHz: MutableState<Int?> = mutableStateOf(null),
        val heterodyneRef2kHz: MutableState<Int?> = mutableStateOf(null),
        val liveSamplingRateHz: MutableState<Int?> = mutableStateOf(null),
        val dataPresent: MutableState<Boolean> = mutableStateOf(false),
    ) {
        fun reset() {
            fileIsOpen.value = false
            title.value = null
            menuExpanded.value = false
            showMetadata.value = false
            showErrorDialog.value = false
            errorMessage.value = ""
            processingFlag.value = false
            pagingState.value = null
            pagingEnabled.value = false
            rawPageRange.value = null
            pageLeftEnabled.value = false
            pageRightEnabled.value = false
            liveMode.intValue = LiveMode.OFF.value
            audioMode.intValue = AudioMode.OFF.value
            showAudioConfig.value = false
            audioSettingsAlreadyShown.value = false
            heterodyneRef1kHz.value = null
            heterodyneRef2kHz.value = null
        }
    }

    private var uiState: UIState = model.spectrogramUIState
    private var buttonState: ButtonState = model.spectrogramButtonState

    private val spectrogramGraph = SpectrogramGraph(model, uiState.rawPageRange)
    private val amplitudeGraph = AmplitudeGraph(model, uiState.rawPageRange)
    private val sliders = Sliders()
    private val heterodyneCursors = HeterodyneCursors(model,
        uiState.heterodyneRef1kHz, uiState.heterodyneRef2kHz)

    private val audioConfig = AudioConfig()


    /**
     * This class maintains state relating to paging, and handles visibility
     * and enabling of paging UI elements directly.
     */
    class PagingStateHandler(
        private val pagingData: AbstractPipeline.PagingData,
        settings: Settings,                         // For settings that take immediate effect.
        private val rawPageRange: MutableState<HORange?>,
        private val pagingEnabled: MutableState<Boolean>,
        private val pageRightEnabled: MutableState<Boolean>,
        private val pageLeftEnabled: MutableState<Boolean>
    ) {
        private data class Internals(
            val rawPageLength: Int,
            val stride: Int,
            val totalPages: Int,
            var currentPage: Int
        )

        private var internals = calcInternals(pagingData.pipelineParametersSnapshot, settings)

        init {
            // reset()  // Not needed here.
            updateUI()
        }

        private fun calcInternals(pipelineParameters: PipelineParameters, settings: Settings): Internals {
            val rawPageLength = pipelineParameters.dataPageTimeSpanS * pagingData.rawSampleRate
            val stride =
                (rawPageLength.toFloat() * (1f - settings.pageOverlapPercent.toFloat() / 100f) + 0.5).toInt()
                    .coerceIn(1, rawPageLength)
            val totalPages = 1 + (pagingData.rawTotalDataLength - rawPageLength) / stride

            return Internals(
                rawPageLength = rawPageLength,
                stride = stride,
                totalPages = totalPages,
                currentPage = 0
            )
        }

        fun doPageRight() {
            setPage(internals.currentPage + 1)
        }

        fun doPageLeft() {
            setPage(internals.currentPage - 1)
        }

        private fun updateUI() {
            pagingEnabled.value = internals.totalPages > 1
            pageRightEnabled.value = internals.currentPage < internals.totalPages - 1
            pageLeftEnabled.value = internals.currentPage > 0
        }

        private fun setPage(newPage: Int) {
            var result: HORange? = null

            if (newPage in 0..<internals.totalPages) {

                // The last page has more overlap to avoid spilling off the end:
                val endCorrection = maxOf(
                    0,
                    (newPage + 1) * internals.stride - pagingData.rawTotalDataLength
                )

                val start = maxOf(newPage * internals.stride - endCorrection, 0)
                var endExclusive = maxOf(start + internals.rawPageLength)
                endExclusive = minOf(
                    endExclusive,
                    pagingData.rawTotalDataLength
                )    // Don't overrun the end of data.

                if (start >= 0) // Paranoia.
                    result = HORange(start, endExclusive)
            }

            if (result != null) {
                Timber.i("Moving to page $newPage starting at ${result.first} length ${result.second - result.first}")
                internals.currentPage = newPage

                require(result.second - result.first <= internals.rawPageLength)

                rawPageRange.value = result
            }

            updateUI()
        }

        fun reset(settings: Settings) {
            internals = calcInternals(pagingData.pipelineParametersSnapshot, settings)
            setPage(0)
        }
    }

    init {
        // Set the initial state:
        spectrogramGraph.reset()
        amplitudeGraph.reset()
    }

    /**
     * Possible UI states relating to live mode.
     * Note that these are just UI states, not underlying USB connection states.
     */
    private enum class LiveMode(val value: Int) {
        OFF(0),
        CONNECTING(1),
        STREAMING(2),
        PAUSED(3)
    }

    /**
     * Possible UI states relating to audio mode.
     * Note that these are just UI states, not underlying audio processing.
     */
    private enum class AudioMode(val value: Int) {
        OFF(0),
        CONNECTING(1),
        ON(2)
    }

    /**
     * Define the main spectrogram UI.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Compose(
        viewModel: UIModel,
        amplitudePaneVisibility: Int,
        leftHandedMode: Boolean,
        settingsVisible: MutableState<Boolean>,
        orientation: MutableIntState,
        appMode: MutableIntState,
        onExitApp: () -> Unit
    ) {
        if (BuildConfig.DEBUG)
            Timber.d("SpectrogramUI.Compose called")

        val context = LocalContext.current
        val menuExpanded = rememberSaveable { uiState.menuExpanded }
        val scope = rememberCoroutineScope()

        val documentPickerLauncher = DocumentHelper.composeDocumentPickerLauncher(
            model.documentHelper,
            onSelection = { uriData: DocumentHelper.UriData ->
                model.resetUIMode(AppMode.VIEWER, uriData)
            }
        )

        fun onShowMetadata() {
            uiState.showMetadata.value = true
        }

        // Collect file metadata from a file that has just been opened:
        LaunchedEffect(Unit) {
            // Main UI thread. The following suspends waiting for file open events.
            viewModel.fileOpenedFlow.collectLatest { result ->
                onViewingFileOpened(result, appMode)
            }
        }

        // Response to a live connect result:
        LaunchedEffect(Unit) {
            // Main UI thread. The following suspends waiting for live data open events.
            viewModel.liveConnectFlow.collectLatest { result ->
                onLiveConnected(result, appMode)
            }
        }

        // Response to a USB error:
        LaunchedEffect(Unit) {
            // Main UI thread. The following suspends waiting for live data open events.
            viewModel.usbErrorFlow.collectLatest { result ->
                onUsbError(result)
            }
        }

        // Response to audio start result:
        LaunchedEffect(Unit) {
            // Main UI thread. The following suspends waiting for live data open events.
            viewModel.audioStartFlow.collectLatest { result ->
                onAudioStarted(result, appMode)
            }
        }

        // Enable buttons depending on audio state:
        LaunchedEffect(uiState.audioMode.intValue) {
            buttonState.audioChecked.value = (uiState.audioMode.intValue == AudioMode.CONNECTING.value
                    || uiState.audioMode.intValue == AudioMode.ON.value)
        }

        /**
         * Handle the case when data streaming has started.
         */
        LaunchedEffect(appMode.intValue, uiState.liveMode.intValue) {
            val isLiveAndStreaming = appMode.intValue == AppMode.LIVE.value &&
                    uiState.liveMode.intValue in setOf(LiveMode.STREAMING.value, LiveMode.PAUSED.value)
            buttonState.audioEnabled.value = isLiveAndStreaming
            buttonState.manualRecordingEnabled.value = isLiveAndStreaming
            buttonState.triggeredRecordingEnabled.value = isLiveAndStreaming
        }

        /**
         * Logic to enable/disable auto BnC.
         */
        LaunchedEffect(appMode.intValue, model.settings.autoBnCEnabledLive, model.settings.autoBnCEnabledViewer) {
            // Logic to determine if auto BnC should be applied. This happens asynchronously so
            // there may be races.
            val required = if (appMode.intValue == AppMode.LIVE.value)
                model.settings.autoBnCEnabledLive
            else
                model.settings.autoBnCEnabledViewer

            // Set the value via the model - which exposes back to us as autoBnCRequiredFlow.
            model.setAutoBnCRequired(required)
        }

        // buttonState.audioEnabled.value

        /**
         * Prevent sleep when we are acquiring live data:
         */
        val activity: Activity = LocalActivity.current as Activity
        LaunchedEffect(uiState.liveMode.intValue, appMode.intValue) {
            val keepScreenOn = appMode.intValue == AppMode.LIVE.value && uiState.liveMode.intValue != LiveMode.OFF.value
            // Timber.d("keepScreenOn = $keepScreenOn")
            if (keepScreenOn)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        /**
         * Prevent any change to the X visible range during live data update, to avoid
         * confusing UI behaviour:
         */
        LaunchedEffect(uiState.liveMode.intValue, appMode.intValue) {
            spectrogramGraph.setClampX(uiState.liveMode.intValue == LiveMode.STREAMING.value && appMode.intValue == AppMode.LIVE.value)
        }

        /**
         * Propagate audio settings changes through the UI and model, when any of the arguments
         * change in value. Note that changes to settings values cam't trigger a launched effect.
         */
        LaunchedEffect(appMode.intValue, uiState.audioMode.intValue) {
            updateHeterodyneUIState(appMode)
        }

        /**
         * Notify the heterodyne frequencies to the native layer.
         */
        LaunchedEffect(uiState.heterodyneRef1kHz.value, uiState.heterodyneRef2kHz.value) {
            uiState.heterodyneRef1kHz.value?.let { kHz1 ->
                model.setHeterodyne(kHz1, uiState.heterodyneRef2kHz.value)
            }
        }

        /**
         * Logic to enable the sliders button.
         */
        val autoBnCRequiredState = model.autoBnCRequiredFlow.collectAsStateWithLifecycle()
        LaunchedEffect(uiState.dataPresent.value, autoBnCRequiredState.value) {
            val enabled = shouldEnableSlidersButton()
            buttonState.slidersButtonEnabled.value = enabled
            if (!enabled) {
                // Hide the sliders if we disable the button:
                buttonState.slidersButtonChecked.value = false
            }
        }

        var logSizeChecked by rememberSaveable { mutableStateOf(false) }
        if (!logSizeChecked && diagnosticLogger.logFileIsLarge(context)) {
            uiState.errorMessage.value = "The diagnosticLogger log is large. Considered disabling and/or clearing diagnosticLogger in Settings"
            uiState.showErrorDialog.value = true
        }
        logSizeChecked = true

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (orientation.intValue == Configuration.ORIENTATION_PORTRAIT) {
                    ComposeTopBar(
                        appMode,
                        documentPickerLauncher,
                        settingsVisible,
                        onExitApp
                    )
                }
            },

            bottomBar = {
                // Put the buttons at the bottom when in portrait mode:
                ComposeBottomBar(
                    orientation,
                    appMode,
                    uiState.liveMode,
                    ::onShowMetadata
                )
            }

        ) { innerPadding ->

            ComposeMiddle(
                context,
                orientation,
                appMode,
                innerPadding,
                viewModel,
                amplitudePaneVisibility,
                leftHandedMode,
                settingsVisible,
                uiState.liveMode,
                ::onShowMetadata,
                documentPickerLauncher,
                onExitApp
            )
        }
    }

    @Composable
    private fun ComposeMiddle(
        context: Context,
        orientation: MutableIntState,
        appMode: MutableIntState,
        innerPadding: PaddingValues,
        viewModel: UIModel,
        amplitudePaneVisibility: Int,
        leftHandedMode: Boolean,
        settingsVisible: MutableState<Boolean>,
        liveMode: MutableIntState,
        onShowMetadata: () -> Unit,
        documentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
        onExitApp: () -> Unit
    ) {
        // Timber.d("ComposeMiddle called")
        Row {
            /*
                zIndex is set for the following two sibling elements. This is so that
                the left hand button element is draw above the spectrogram, to avoid it
                being hidden by the SurfaceView. That is a quirk of API 30.
             */

            if (orientation.intValue == Configuration.ORIENTATION_LANDSCAPE && leftHandedMode) {
                Column(Modifier.zIndex(1f)) {
                    ComposeButtonsVertical(
                        innerPadding,
                        liveMode,
                        appMode,
                        onShowMetadata,
                        documentPickerLauncher,
                        settingsVisible,
                        onExitApp
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)     // Needs to be present so that any column to the right can get its natural width.
                    .zIndex((0f))
            ) {
                // Box so that we can overlay things on the spectrogram:
                Box {
                    val title = rememberSaveable { uiState.title }
                    val detailsText = model.detailsTextFlow.collectAsStateWithLifecycle()

                    SpectrogramPaneSet(
                        modifier = Modifier.padding(innerPadding),
                        model = viewModel,
                        amplitudePaneVisibility = amplitudePaneVisibility,
                        title = title,
                        { modifier: Modifier ->
                            ComposeOverlay(modifier, buttonState, detailsText)
                        }
                    )

                    if (uiState.processingFlag.value) {
                        Box(
                            modifier = Modifier.fillMaxSize(),  // Expands Box to full size
                            contentAlignment = Alignment.Center // Centers the icon inside the Box
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
            if (orientation.intValue == Configuration.ORIENTATION_LANDSCAPE && !leftHandedMode) {
                Column {
                    ComposeButtonsVertical(
                        innerPadding,
                        liveMode,
                        appMode,
                        onShowMetadata,
                        documentPickerLauncher,
                        settingsVisible,
                        onExitApp
                    )
                }
            }
        }

        if (uiState.showMetadata.value) {
            FileMetadata(context, model, onDismiss = { uiState.showMetadata.value = false })
        }

        if (uiState.showAudioConfig.value) {

            val scope = rememberCoroutineScope()

            // Calculate the heterodyne range we can support:
            var heterodyneRangekHz = IntRange(heterodyneCursors.minimumHeterodynekHz, 150)
            uiState.liveSamplingRateHz.value?.let { hz ->
                var upper = floor(hz / (2f * 1000f)).toInt() - 1
                // Limit the heterodyne to the useful range so that the UI slider is
                // more manageable on smaller screens:
                upper = minOf(upper, 150)
                if (upper - heterodyneCursors.minimumHeterodynekHz > 2)    // Sanity
                    heterodyneRangekHz = IntRange(heterodyneCursors.minimumHeterodynekHz, upper)
            }

            val isStarting = uiState.audioMode.intValue != AudioMode.ON.value
            audioConfig.Compose(
                model.settings,
                isStarting,
                onDismiss =  {
                    // They changed their mind:
                    uiState.showAudioConfig.value = false
                    if (isStarting)
                        buttonState.audioChecked.value = false
                },
                onConfirm = { audioDualHeterodyne: Boolean, audioRef1kHz: Int,
                              audioRef2kHz: Int, audioBoostFactor: Int ->
                    scope.launch {
                        model.updateStoredSettings(
                            model.settings.copy(
                                heterodyneDual = audioDualHeterodyne,
                                heterodyneRef1kHz = audioRef1kHz,
                                heterodyneRef2kHz = audioRef2kHz,
                                audioBoostShift = audioBoostFactor
                            )
                        )

                        // The following is inside this launch so that settings are updated
                        // before use them to start the audio.

                        uiState.showAudioConfig.value = false
                        uiState.audioSettingsAlreadyShown.value = true

                        // Notify changes to the UI, and model:
                        updateHeterodyneUIState(appMode)

                        // Start audio asynchronously. We will be notified of the outcome
                        // in due course:
                        model.startAudio()
                    }
                },
                heterodyneRange = heterodyneRangekHz
            )
        }

        if (uiState.showErrorDialog.value) {
            ErrorDialog(
                onDismiss = { uiState.showErrorDialog.value = false },
                uiState.errorMessage.value
            )
        }
    }

    @Composable
    private fun ComposeOverlay(
        modifier: Modifier,
        buttonState: ButtonState,
        detailsText: State<String?>
    ) {
        // A box so we can have two layers.
        Box(modifier = modifier
            .fillMaxSize()) {

            // Layer 1: static things:
            Column(Modifier
                .fillMaxSize()
                .padding(5.dp)) {

                val textHeightSp = 14.sp // Scale independent.

                val commonModifier = Modifier.fillMaxWidth()
                val commonAlignment = Alignment.CenterVertically

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentlyWritingFile by model.currentlyWritingFlow.collectAsState()

                    val colour = when (uiState.liveMode.intValue) {
                        LiveMode.OFF.value -> Color.Transparent
                        LiveMode.CONNECTING.value -> Color.DarkGray
                        LiveMode.STREAMING.value -> {
                            if (buttonState.triggeredRecordingChecked.value || buttonState.manualRecordingChecked.value) {
                                if (currentlyWritingFile) Color(0xFF8B0000) else Color(0xFFB07020)
                            }
                            else
                                Color(0xFF006400)           // Dark green
                        }
                        LiveMode.PAUSED.value -> Color.DarkGray
                        else -> Color.Transparent           // Shouldn't get here.
                    }

                    if (uiState.liveMode.intValue != LiveMode.OFF.value) {
                        MyLamp2(20.dp, colour)
                    }

                    Spacer(Modifier.weight(1f))

                    if (uiState.fileIsOpen.value) {
                        Column {
                            MyTransparentButton(
                                ImageVector.vectorResource(R.drawable.baseline_close_24),
                                "Close file", true
                            ) {
                                model.resetUIMode(AppMode.LIVE)
                            }
                        }
                    }

                    if (uiState.audioMode.value == AudioMode.ON.value) {
                        Column {
                            uiState.heterodyneRef1kHz.value?.let {
                                Text("${uiState.heterodyneRef1kHz.value} kHz")
                            }
                            uiState.heterodyneRef2kHz.value?.let {
                                Text("${uiState.heterodyneRef2kHz.value} kHz")
                            }
                        }
                    }

                }

                // Takes up excess vertical space:
                Spacer(modifier = Modifier.weight(1f))

                if (uiState.pagingEnabled.value) {
                    Row(commonModifier, verticalAlignment = commonAlignment) {
                        Column {
                            MyTransparentButton(
                                image = ImageVector.vectorResource(R.drawable.baseline_keyboard_double_arrow_left_24),
                                contentDescription = "page left",
                                enabled = uiState.pageLeftEnabled.value
                            ) {
                                doPageLeft()
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Column {
                            MyTransparentButton(
                                image = ImageVector.vectorResource(R.drawable.baseline_keyboard_double_arrow_right_24),
                                contentDescription = "page right",
                                enabled = uiState.pageRightEnabled.value
                            )
                            {
                                doPageRight()
                            }
                        }
                    }
                }

                val bnCRange = model.bnCRangeFlow.collectAsStateWithLifecycle()
                /*
                This row is always present, but totally transparent when it is not
                required. This allows the screen layout to not jump around.
                */
                Row(
                    commonModifier
                        .then(if (!buttonState.slidersButtonChecked.value) Modifier.alpha(0f) else Modifier),
                    verticalAlignment = commonAlignment,
                ) {
                    Spacer(Modifier.weight(1f))
                    Column(
                        modifier = Modifier
                            .widthIn(max = 400.dp)
                            .background(Color.Transparent),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        sliders.Compose(
                            Modifier, model, bnCRange, buttonState.slidersButtonChecked.value,
                            uiState.rawPageRange
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f)
                    ) {
                        if (model.settings.showParameterOverlay) {
                            val text = detailsText.value
                            if (text != null) {
                                Text(
                                    text,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(
                                        fontSize = textHeightSp,
                                        color = Color.Gray
                                    )
                                )
                            }
                        }
                    }
                    Column {
                        MyTransparentLatchingButton(
                            buttonState.slidersButtonChecked,
                            buttonState.slidersButtonEnabled,
                            ImageVector.vectorResource(R.drawable.baseline_tune_24),
                            "Show sliders",
                            onSelectionChanged = { _: Boolean -> })
                    }
                }
            }

            // Layer 2: dynamic things:
            heterodyneCursors.Compose()
       }
    }

    private fun updateHeterodyneUIState(appMode: MutableIntState) {
        if (uiState.audioMode.intValue in setOf(AudioMode.ON.value)
            &&
            appMode.intValue in setOf(AppMode.LIVE.value)) {

            // Assigning these values makes the heterodyne cursor appear on the graph:
            uiState.heterodyneRef1kHz.value = model.settings.heterodyneRef1kHz
            uiState.heterodyneRef2kHz.value = if (model.settings.heterodyneDual)
                model.settings.heterodyneRef2kHz
            else
                null
        }
        else {
            uiState.heterodyneRef1kHz.value = null
            uiState.heterodyneRef2kHz.value = null
        }

        // Trigger a re-render to take these changes into account:
        model.spectrogramBitmapHolder.signalUpdate()
    }

    private fun doPageLeft() {
        val ps = uiState.pagingState.value
        ps?.let {
            it.doPageLeft()
            uiState.rawPageRange.value?.let { r ->
                model.onPageChange(model.settings, r)
            }
        }
    }

    private fun doPageRight() {
        val ps = uiState.pagingState.value
        ps?.let {
            it.doPageRight()
            uiState.rawPageRange.value?.let { r ->
                model.onPageChange(model.settings, r)
            }
        }
    }

    @Composable
    private fun ComposeBottomBar(
        orientation: MutableIntState,
        appMode: MutableIntState,
        liveMode: MutableIntState,
        onShowMetadata: () -> Unit
    ) {
        if (orientation.intValue == Configuration.ORIENTATION_PORTRAIT) {
            BottomAppBar {
                ComposeButtonsHorizontal(liveMode, appMode, onShowMetadata)
            }
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun ComposeTopBar(
        appMode: MutableIntState,
        documentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
        settingsVisible: MutableState<Boolean>,
        onExitApp: () -> Unit
    ) {
        TopAppBar(
            // Colours will be obtained from the enclosing theme, no need
            // to specify theme here.

            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "BatGizmo ${AppMode.getText(appMode.intValue)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },

            navigationIcon = {
                ComposeNavigationIcon(documentPickerLauncher, settingsVisible, onExitApp)
            },

            actions = {
                IconButton(onClick = { settingsVisible.value = true }) {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = "Settings"
                    )
                }
            }
        )
    }

    @Composable
    private fun ComposeNavigationIcon(
        documentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
        settingsVisible: MutableState<Boolean>,
        onExitApp: () -> Unit
    )
    {
        // Make sure the menu appears next to the button it relates to:
        var iconButtonCoordinates by remember { mutableStateOf(Offset.Zero) }
        var iconButtonSize by remember { mutableStateOf(IntSize.Zero) }
        var menuExpanded by remember { uiState.menuExpanded }

        IconButton(onClick = {
            menuExpanded = true
            },
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    iconButtonCoordinates = coordinates.localToWindow(Offset.Zero)
                    iconButtonSize = coordinates.size
                }
        ) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "Main Menu"
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("View file...") },
                onClick = {
                    documentPickerLauncher.launch(
                        arrayOf(
                            "audio/wav",
                            "audio/x-wav"
                        )
                    )
                },
                leadingIcon = { Icon(
                    painter = painterResource(id = R.drawable.outline_audio_file_24),
                    contentDescription = "Settings")
                }
            )
            DropdownMenuItem(
                text = { Text("Close file") },
                onClick = {
                    // In case we are currently viewing in multiple file mode:
                    model.documentHelper.reset()
                    model.resetUIMode(AppMode.LIVE)
                },
                leadingIcon = { Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Settings")
                },
                enabled = uiState.fileIsOpen.value
            )
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = {
                    settingsVisible.value = true
                    menuExpanded = false
                },
                leadingIcon = { Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = "Settings")
                }
            )
            DropdownMenuItem(
                text = { Text("Exit") },
                onClick = {
                    menuExpanded = false
                    onExitApp()
                },
                leadingIcon = { Icon(
                    painter = painterResource(id = R.drawable.outline_power_settings_new_24),
                    contentDescription = "Settings")
                }
            )

            HorizontalDivider()

            DropdownMenuItem(
                onClick = {}, // No-op or show a dialog
                text = { Text("Version: ${BuildConfig.VERSION_NAME}") }
            )
        }
    }

    @Composable
    fun SpectrogramPaneSet(
        modifier: Modifier,
        model: UIModel,
        amplitudePaneVisibility: Int,
        title: MutableState<String?>,
        overlayComposer: @Composable (Modifier) -> Unit,
        windowSizeClass: WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass,
    ) {
        if (BuildConfig.DEBUG)
            Timber.d("SpectrogramPaneSet called")

        var showAmplitudePane = true
        if (amplitudePaneVisibility == Settings.VisibilityOptions.NEVER.value)
            showAmplitudePane = false
        else if (amplitudePaneVisibility == Settings.VisibilityOptions.AUTO.value) {
            if (windowSizeClass.windowHeightSizeClass == WindowHeightSizeClass.COMPACT)
                showAmplitudePane = false
        }

        // Used to make sure that pane sizes relate to the same UI generation to avoid
        // race conditions during reconfiguration. Preserved across reconfigurations.

        val scope = rememberCoroutineScope()

        val density = LocalDensity.current

        val paneSizeHelper = PaneSizeHelper()
        val (onAmplitudeSizeChange, onSpectrogramSizeChange) = paneSizeHelper.compose(
            model, scope, showAmplitudePane, density, uiState.rawPageRange.value)

        Column(modifier.fillMaxSize()) {
            if (showAmplitudePane) {
                amplitudeGraph.Compose(
                    Modifier
                        .fillMaxWidth()
                        .weight(0.15f)
                        .onSizeChanged { sizePx ->
                            // Timber.d("Amplitude size is $sizePx")
                            onAmplitudeSizeChange(sizePx)
                        },
                    model,
                    localShowGrid.current
                )
            }

            // The spectrogram:
            spectrogramGraph.Compose(
                Modifier
                    .fillMaxWidth()
                    .weight(0.85f)
                    .onSizeChanged { sizePx ->
                        // Timber.d("Spectrogram size is $sizePx")
                        onSpectrogramSizeChange(sizePx)
                    },
                model,
                localShowGrid.current,
                title.value,
                overlayComposer
            )
        }
    }

    @Composable
    fun ComposeButtonsHorizontal(
        liveMode: MutableIntState,
        appMode: MutableIntState,
        onShowMetadata: () -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            ComposeButtonsGroup1(liveMode, appMode)
            Spacer(Modifier.weight(1f))
            ComposeButtonsGroup2(liveMode, appMode, onShowMetadata)
        }
    }

    @Composable
    fun ComposeButtonsVertical(
        innerPadding: PaddingValues,
        liveMode: MutableIntState,
        appMode: MutableIntState,
        onShowMetadata: () -> Unit,
        documentPickerLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
        settingsVisible: MutableState<Boolean>,
        onExitApp: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {   // Box so that the menu is correctly located relative to its button
                ComposeNavigationIcon(
                    documentPickerLauncher,
                    settingsVisible,
                    onExitApp
                )
            }
            Spacer(Modifier.weight(1f))
            ComposeButtonsGroup1(liveMode, appMode)
            Spacer(Modifier.weight(1f))
            ComposeButtonsGroup2(liveMode, appMode, onShowMetadata)
        }
    }

    private fun acquisitionButtonHandler(scope: CoroutineScope,
                                         appMode: MutableIntState,
                                         liveMode: MutableIntState,
                                         checked: Boolean) {
        // Assumption: we are already in live mode. The button is disabled
        // in viewer mode, to avoid the need to asynchronously switch UI mode
        // and connect to USB in parallel, with the risk of a race.
        if (appMode.intValue != AppMode.LIVE.value) {
            Timber.w("Internal error: not in live mode")
        }

        when (uiState.liveMode.intValue) {
            LiveMode.OFF.value -> {
                if (checked) {
                    Timber.i("Live mode: connecting.")

                    // Start data streaming from the USB device, asynchronously:
                    liveMode.intValue = LiveMode.CONNECTING.value
                    model.openLive(::fileWriterErrorHandler)
                }
                else {
                    // Unchecked and already OFF, no action.
                }
            }
            LiveMode.CONNECTING.value -> {
                // Already connecting, nothing to do at present.
                if (BuildConfig.DEBUG)
                    Timber.d("Live mode: currently connecting, no action taken.")
                /*
                if (!checked) {
                }
                 */
            }
            LiveMode.STREAMING.value -> {
                if (!checked) {
                    // Currently streaming data, so we need to pause:
                    Timber.i("Live mode: pausing.")
                    uiState.liveMode.intValue = LiveMode.PAUSED.value
                    model.pauseLiveStream()

                    // Is the following a good idea? It does an auto BnC whenever
                    //  acquisition is paused, which may just be making the noise very vivid.
                    if (model.autoBnCRequiredFlow.value) {
                        model.doAutoBnC()
                    }
                }
                else {
                    // Checked and already streaming, no action.
                }
            }
            LiveMode.PAUSED.value -> {
                if (checked) {
                    // Currently paused, so we need to resume:
                    Timber.i("Live mode: resuming from pause.")
                    uiState.liveMode.intValue = LiveMode.STREAMING.value
                    model.resumeLiveStream()
                }
                else {
                    // Unchecked and already paused, no action.
                }
            }
        }
    }

    @Composable
    fun ComposeButtonsGroup1(
        liveMode: MutableIntState,
        appMode: MutableIntState
    ) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        MyLatchingButton(
            buttonState.acquisitionChecked, buttonState.acquisitionEnabled,
            ImageVector.vectorResource(R.drawable.baseline_mic_24_filled),
            "Toggle acquisition",
            onSelectionChanged = { checked ->
                if (!checked) {
                    // Stop any file writing in progress.
                    model.fileWriter?.configureTrigger(FileWriter.TriggerConfig(triggerType = TriggerType.OFF))
                    buttonState.manualRecordingChecked.value = false
                    buttonState.triggeredRecordingChecked.value = false
                }

                // Start/stop acquisition as required:
                acquisitionButtonHandler(scope, appMode, liveMode, checked)
            }
        )

        if (appMode.intValue == AppMode.LIVE.value) {

            MyLatchingButton(
                buttonState.manualRecordingChecked, buttonState.manualRecordingEnabled,
                ImageVector.vectorResource(R.drawable.baseline_insert_drive_file_24_filled),
                "Toggle manual recording",
                onSelectionChanged = { checked: Boolean ->
                    // Start or stop manual recording:
                    when (checked) {
                        true -> {
                            // Mutually exclusive trigger modes:
                            buttonState.triggeredRecordingChecked.value = false
                            model.fileWriter?.configureTrigger(FileWriter.TriggerConfig(triggerType = TriggerType.MANUAL))
                        }

                        false -> {
                            model.fileWriter?.configureTrigger(FileWriter.TriggerConfig(triggerType = TriggerType.OFF))
                        }
                    }
                })

            MyLatchingButton(
                buttonState.triggeredRecordingChecked, buttonState.triggeredRecordingEnabled,
                ImageVector.vectorResource(R.drawable.baseline_insert_page_break_24_filled),
                "Toggle triggered recording",
                onSelectionChanged = { checked: Boolean ->
                    // Start or stop manual recording:
                    when (checked) {
                        true -> {
                            // Mutually exclusive trigger modes:
                            buttonState.manualRecordingChecked.value = false
                            model.fileWriter?.configureTrigger(FileWriter.TriggerConfig(triggerType = TriggerType.AUTO))
                        }

                        false -> {
                            model.fileWriter?.configureTrigger(FileWriter.TriggerConfig(triggerType = TriggerType.OFF))
                        }
                    }
                })
        }
        else {
            fun gotoFile(uriData: DocumentHelper.UriData) {
                val filename = DocumentHelper.getFileName(context, uriData.uri)
                model.closePipeline()
                model.openFile(uriData.uri, filename ?: uriData.uri.toString())
                buttonState.previousFileEnabled.value = uriData.previousAvailable
                buttonState.nextFileEnabled.value = uriData.nextAvailable
            }
            MyButton(
                Icons.Filled.KeyboardArrowUp, "Previous file",
                buttonState.previousFileEnabled.value)  {
                    val uriData = model.documentHelper.getPreviousFile()
                    uriData?.let { it ->
                        gotoFile(it)
                    }
                }
            MyButton(
                Icons.Filled.KeyboardArrowDown, "Next file",
                buttonState.nextFileEnabled.value)  {
                val uriData = model.documentHelper.getNextFile()
                uriData?.let { it ->
                    gotoFile(it)
                }
            }
        }

        MyLatchingButton(
            buttonState.audioChecked, buttonState.audioEnabled,
            ImageVector.vectorResource(R.drawable.baseline_volume_up_24_filled),
            "Toggle audio",
            onSelectionChanged = { checked: Boolean ->
                if (checked) {
                    // Provide instant UI feedback:
                    uiState.audioMode.intValue = AudioMode.CONNECTING.value

                    // The first time, we route them via the audio config dialog:
                    if (!uiState.audioSettingsAlreadyShown.value)
                        uiState.showAudioConfig.value = true
                    else {
                        // Kick off live audio asynchronously. We will get notified later with the outcome:
                        model.startAudio()
                    }
                }
                else {
                    // Provide instant UI feedback:
                    uiState.audioMode.intValue = AudioMode.OFF.value
                    // Stop live audio asynchronously. Fire and forget.
                    model.stopAudio()
                }
            },
            onLongPress = { checked: Boolean ->
                // Route them via the audio config dialog:
                // uiState.audioMode.intValue = AudioMode.CONNECTING.value
                uiState.showAudioConfig.value = true

                // TODO: Two cases to handle:
                //  * Not running audio at the moment.
                //  * Running audio - need to stop and restart it with the new settings.
            }
        )
    }

    @Composable
    fun ComposeButtonsGroup2(
        liveMode: MutableIntState,
        appMode: MutableIntState,
        onShowMetadata: () -> Unit
    ) {
        if (appMode.value == AppMode.VIEWER.value) {
            MyButton(
                Icons.Filled.Info, "Metadata",
                buttonState.showMetadataEnabled.value, onShowMetadata
            )
        }
    }

    private fun viewUri(context: Context, viewModel: UIModel, uriData: DocumentHelper.UriData) {
        val filename = DocumentHelper.getFileName(context, uriData.uri)

        uiState.processingFlag.value = true
        uiState.rawPageRange.value = null
        uiState.pagingState.value = null

        // Timber.d("processingFlag set true")
        viewModel.openFile(uriData.uri, filename ?: "(unknown)")

        buttonState.previousFileEnabled.value = uriData.previousAvailable
        buttonState.nextFileEnabled.value = uriData.nextAvailable
    }

    private fun onViewingFileOpened(
        owfr: OpenWavFileResult,
        appMode: MutableIntState
    ) {
        uiState.processingFlag.value = false

        Timber.i("onViewingFileOpened called: ${owfr.wfi?.fileName}")

        if (owfr.wfi != null) {
            val wfi = owfr.wfi
            // Put the UI into a suitable state to view a URI:
            uiState.menuExpanded.value = false
            appMode.intValue = AppMode.VIEWER.value
            val title = wfi.fileName
            uiState.title.value = title
            uiState.fileIsOpen.value = true

            // Paging data is constant for the data file, it doesn't
            // change when the page sized is changed:
            val pd = owfr.pagingData
            pd?.let {
                uiState.pagingState.value = PagingStateHandler(
                    pd,
                    model.settings,
                    uiState.rawPageRange,
                    uiState.pagingEnabled,
                    uiState.pageRightEnabled,
                    uiState.pageLeftEnabled
                )
            }

            // buttonState.slidersButtonEnabled.value = true
            uiState.dataPresent.value = true
            buttonState.showMetadataEnabled.value = true
            buttonState.closeFileEnabled.value = true

        } else if (owfr.errorMessage != null) {
            val msg = owfr.errorMessage
            uiState.errorMessage.value = "Unable to open data file.\n\n$msg"
            uiState.showErrorDialog.value = true

            // Clean up. An error results in any file open or partly open to be closed:
            model.closePipeline()
        }
    }

    private fun onLiveConnected(
        lcr: UsbService.UsbConnectResult,
        appMode: MutableIntState
    ) {

        Timber.i("onLiveConnected called: ${lcr}")

        if (lcr.connectedOK) {
            uiState.liveMode.intValue = LiveMode.STREAMING.value

            // buttonState.slidersButtonEnabled.value = true
            uiState.dataPresent.value = true

            // The title bar contains the microphone type. In an ideal world we would
            // sanitize the text received back from the microphone.
            val product = lcr.productName ?: "USB Device"
            var rateText = ""
            val hz = lcr.sampleRate
            if (hz != null) {
                rateText = String.format(Locale.getDefault(), " @ %.1f kHz", hz / 1000f)
                uiState.liveSamplingRateHz.value = hz
            }
            else {
                uiState.liveSamplingRateHz.value = null
            }
            var manufacturer = ""
            /*  The manufacturer name is often included in the product name.
            lcr.manufacturerName?.let {
                manufacturer = String.format("%s ", it)
            }
             */
            uiState.title.value = "$manufacturer$product$rateText"
        } else {
            // Connected failed so revert the UI state:
            buttonState.acquisitionChecked.value = false
            uiState.liveMode.intValue = LiveMode.OFF.value
            uiState.title.value = null
            uiState.liveSamplingRateHz.value = null
            if (lcr.errorMessage != null) {
                val msg = lcr.errorMessage
                uiState.errorMessage.value = "Unable to connect live.\n\n$msg"
                uiState.showErrorDialog.value = true
            }
        }
    }

    private fun onAudioStarted(
        asr: UsbService.AudioStartResult,
        appMode: MutableIntState
    ) {
        Timber.i("onAudioStarted called: $asr")

        if (asr.startedOK) {
            uiState.audioMode.intValue = AudioMode.ON.value
        } else {
            // Connected failed so revert the UI state:
            uiState.audioMode.intValue = AudioMode.OFF.value
        }
    }

    /**
     * This method is called there is an error subsequent to successful
     * connection to the USB microphone.
     */
    private fun onUsbError(result: UsbService.UsbErrorResult) {
        model.closePipeline()   // Idempotent. Also stops audio and file writing.

        uiState.liveMode.intValue = LiveMode.OFF.value
        uiState.audioMode.intValue = AudioMode.OFF.value
        // Force the audio config model to be shown again if the microphone has been removed, so
        // that frequency sanity checks can be applied for the new microphone:
        uiState.audioSettingsAlreadyShown.value = false
        uiState.dataPresent.value = false

        buttonState.acquisitionChecked.value = false
        buttonState.manualRecordingEnabled.value = false
        buttonState.triggeredRecordingChecked.value = false
        buttonState.audioEnabled.value = false
        // buttonState.slidersButtonEnabled.value = false


        uiState.title.value = null

        var errnoText = try {
            Os.strerror(result.errno)
        }
        catch (e: ErrnoException) {
            "Unknown errno"
        }

        uiState.errorMessage.value = "USB microphone communication error - please check the connection.\n\n" +
                "errno = ${result.errno}: $errnoText"
        uiState.showErrorDialog.value = true
    }

    fun onSettingsUpdate(newSettings: Settings, previousSettings: Settings?) {
        var resetPaging = false
        previousSettings?.let {
            if (newSettings.pageOverlapPercent != previousSettings.pageOverlapPercent
                || newSettings.pipelineParameters.dataPageTimeSpanS != previousSettings.pageOverlapPercent
                || newSettings.showParameterOverlay != previousSettings.showParameterOverlay
            )
                resetPaging = true
        }

        if (resetPaging) {
            // Something related to paging changed so we need to reset to take that into account:
            uiState.pagingState.value?.reset(newSettings)
        }

        model.onSettingsUpdate(newSettings, previousSettings, uiState.rawPageRange.value)
    }

    private fun closeLive() {
        model.stopAudio()       // Idempotent.
        model.closePipeline()   // Idempotent.
    }

    private fun closeViewer() {
        model.closePipeline()   // Idempotent.
        model.documentHelper.reset()

        uiState.fileIsOpen.value = false
        uiState.title.value = null
        uiState.menuExpanded.value = false
        uiState.pagingState.value = null
        uiState.pagingEnabled.value = false
        uiState.dataPresent.value = false

        buttonState.slidersButtonChecked.value = false
    }

    private fun resetUI() {
        buttonState.reset()
        uiState.reset()
    }

    /**
     * Responds to an event to change the UI to a viewer in reset state, whatever state it is in
     * currently.
     */
    fun resetToViewer(context: Context, previousMode: Int, uriData: DocumentHelper.UriData?) {

        resetUI()

        if (previousMode == AppMode.LIVE.value) {
            closeLive()
        }

        if (previousMode == AppMode.VIEWER.value) {
            closeViewer()
        }

        if (uriData != null) {
            viewUri(context, model, uriData)
        }

        // Stop periodic location updates when we are in viewer mode:
        model.locationTracker.stopPeriodicUpdates()

        buttonState.slidersButtonEnabled.value = shouldEnableSlidersButton()
    }

    fun resetToLive(context: Context, previousMode: Int, streaming: Boolean) {

        resetUI()

        if (previousMode == AppMode.VIEWER.value) {
            closeViewer()
        }

        if (previousMode == AppMode.LIVE.value) {
            closeLive()
        }

        buttonState.acquisitionEnabled.value = true
        buttonState.showMetadataEnabled.value = false
        buttonState.closeFileEnabled.value = false
        buttonState.slidersButtonEnabled.value = shouldEnableSlidersButton()
        // Other button enabled done via a LaunchedEffect

        // Request periodic location updates when we are in live mode:
        model.locationTracker.startPeriodicUpdates()

        if (streaming)
            model.openLive(::fileWriterErrorHandler)
    }

    fun shouldEnableSlidersButton(): Boolean {
        val enabled = uiState.dataPresent.value && !model.autoBnCRequiredFlow.value
        // Timber.d("JM: uiState.dataPresent.value = ${uiState.dataPresent.value}, model.autoBnCRequiredFlow.value = ${model.autoBnCRequiredFlow.value}, Setting slidersButtonEnabled = $enabled")
        return enabled
    }

    private fun fileWriterErrorHandler(msg: String) {
        buttonState.manualRecordingChecked.value = false
        buttonState.triggeredRecordingChecked.value = false

        /*  Removed for now, makes for a confusing UX.
        uiState.showErrorDialog.value = true
        uiState.errorMessage.value = msg

        Log.e(logTag, msg)
         */
    }
}
