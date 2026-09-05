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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.system.ErrnoException
import android.system.Os
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.MutableIntState
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.batgizmo.app.Settings
import org.batgizmo.app.UIModel
import org.batgizmo.app.pipeline.LiveConnectResult
import org.batgizmo.app.pipeline.LiveStreamErrorResult
import org.batgizmo.app.ui.TopLevelUI.AppMode
import timber.log.Timber
import uk.org.gimell.batgimzoapp.BuildConfig
import java.util.Locale

/**
 * UI-side live session orchestration: acquisition state machine, RECORD_AUDIO
 * gating, connect/error handling, and related button enablement.
 *
 * Audio start/stop remains owned by [SpectrogramUI]; this class calls into it
 * via [stopAudioAndResetUi], [startAudioNow], and
 * [onAudioPlaybackModeReselectionRequired].
 */
class LiveSessionController(
    private val model: UIModel,
    private val uiState: SpectrogramUI.UIState,
    private val buttonState: SpectrogramUI.ButtonState,
    private val stopAudioAndResetUi: () -> Unit,
    private val onAudioPlaybackModeReselectionRequired: (sampleRateHz: Int) -> Unit,
    private val startAudioNow: (appMode: MutableIntState) -> Unit,
    private val audioModeOff: Int,
    private val audioModeConnecting: Int,
) {
    /**
     * Possible UI states relating to live mode.
     * Note that these are just UI states, not underlying USB connection states.
     */
    enum class LiveMode(val value: Int) {
        OFF(0),
        CONNECTING(1),
        STREAMING(2),
        PAUSED(3)
    }

    /** Set from Compose to request [Manifest.permission.RECORD_AUDIO] before internal mic use. */
    private var openLiveWithPermissions: ((Int?) -> Unit)? = null

    private var pendingLiveInputSourceOverride: Int? = null
    private var pendingLiveUseSettingsSource: Boolean = false

    fun attachOpenLiveWithPermissions(opener: (Int?) -> Unit) {
        openLiveWithPermissions = opener
    }

    /** Handle the result of a RECORD_AUDIO permission request started by [ensureRecordAudioAndOpenLive]. */
    fun onRecordAudioPermissionResult(granted: Boolean) {
        val override = if (pendingLiveUseSettingsSource) null else pendingLiveInputSourceOverride
        pendingLiveInputSourceOverride = null
        pendingLiveUseSettingsSource = false
        if (granted) {
            model.openLive(::onFileWriterError, override)
        } else {
            buttonState.acquisitionChecked.value = false
            uiState.liveMode.intValue = LiveMode.OFF.value
            uiState.errorMessage.value =
                "Microphone permission is required to use the internal microphone."
            uiState.showErrorDialog.value = true
        }
    }

    /** Recording needs live data flowing; paused acquisition is not enough. */
    fun isLiveRecordingAvailable(appModeInt: Int): Boolean =
        appModeInt == AppMode.LIVE.value &&
            uiState.liveMode.intValue == LiveMode.STREAMING.value

    fun updateRecordingButtonsEnabled(isLiveRecordingAvailable: Boolean) {
        buttonState.manualRecordingEnabled.value =
            isLiveRecordingAvailable &&
                (!buttonState.triggeredRecordingChecked.value ||
                    buttonState.manualRecordingChecked.value)
        buttonState.triggeredRecordingEnabled.value =
            isLiveRecordingAvailable &&
                (!buttonState.manualRecordingChecked.value ||
                    buttonState.triggeredRecordingChecked.value)
    }

    fun updateAudioButtonEnabled(appModeInt: Int) {
        val liveRecordingAvailable = isLiveRecordingAvailable(appModeInt)
        val isViewingAndDataLoaded =
            appModeInt == AppMode.VIEWER.value && uiState.dataPresent.value
        buttonState.audioEnabled.value = liveRecordingAvailable || isViewingAndDataLoaded
        updateRecordingButtonsEnabled(liveRecordingAvailable)
    }

    fun acquisitionButtonHandler(
        appMode: MutableIntState,
        liveMode: MutableIntState,
        checked: Boolean
    ) {
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

                    // Start live data acquisition asynchronously:
                    liveMode.intValue = LiveMode.CONNECTING.value
                    openLiveCheckingPermissions()
                }
                else {
                    // Unchecked and already OFF, no action.
                }
            }
            LiveMode.CONNECTING.value -> {
                // Already connecting, nothing to do at present.
                if (BuildConfig.DEBUG)
                    Timber.d("Live mode: currently connecting, no action taken.")
            }
            LiveMode.STREAMING.value -> {
                if (!checked) {
                    // Currently streaming data, so we need to pause:
                    Timber.i("Live mode: pausing.")
                    uiState.liveMode.intValue = LiveMode.PAUSED.value
                    model.pauseLiveStream()

                    // Do an auto BnC etc whenever acquisition is paused.
                    model.doColourMappingAndRender(
                        model.settings.autoBnCEnabledLive,
                        model.settings.autoBaselineEnabled
                    )
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

    fun openLiveCheckingPermissions(liveInputSourceOverride: Int? = null) {
        openLiveWithPermissions?.invoke(liveInputSourceOverride)
            ?: model.openLive(::onFileWriterError, liveInputSourceOverride)
    }

    fun ensureRecordAudioAndOpenLive(
        context: Context,
        permissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
        liveInputSourceOverride: Int?,
    ) {
        val liveInputSource = liveInputSourceOverride ?: model.settings.liveInputSource
        if (liveInputSource != Settings.LiveInputSourceOptions.PHONE_MIC.value) {
            model.openLive(::onFileWriterError, liveInputSourceOverride)
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            model.openLive(::onFileWriterError, liveInputSourceOverride)
            return
        }

        pendingLiveUseSettingsSource = liveInputSourceOverride == null
        pendingLiveInputSourceOverride = liveInputSourceOverride
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun onLiveConnected(lcr: LiveConnectResult, appMode: MutableIntState) {
        Timber.i("onLiveConnected called: $lcr")

        if (lcr.connectedOK) {
            uiState.liveMode.intValue = LiveMode.STREAMING.value

            uiState.dataPresent.value = true

            // The title bar contains the microphone type. In an ideal world we would
            // sanitize the text received back from the microphone.
            val product = lcr.productName ?: "USB Device"
            var rateText = ""
            val hz = lcr.sampleRate
            if (hz != null) {
                rateText = String.format(Locale.getDefault(), " @ %.1f kHz", hz / 1000f)
                uiState.samplingRateHz.value = hz
                onAudioPlaybackModeReselectionRequired(hz)
            }
            else {
                uiState.samplingRateHz.value = null
            }
            var manufacturer = ""
            /*  The manufacturer name is often included in the product name.
            lcr.manufacturerName?.let {
                manufacturer = String.format("%s ", it)
            }
             */
            uiState.title.value = "$manufacturer$product$rateText"

            if (model.consumePendingStartAudioAfterLiveConnect()) {
                buttonState.audioChecked.value = true
                uiState.audioMode.intValue = audioModeConnecting
                // Auto-start from the high-rate mic offer: use current settings, skip the modal.
                uiState.audioSettingsAlreadyShown.value = true
                startAudioNow(appMode)
            }
        } else {
            // Connected failed so revert the UI state:
            buttonState.acquisitionChecked.value = false
            uiState.liveMode.intValue = LiveMode.OFF.value
            uiState.title.value = null
            uiState.samplingRateHz.value = null
            model.consumePendingStartAudioAfterLiveConnect()
            if (lcr.offerInternalMicFallback) {
                uiState.showInternalMicFallbackDialog.value = true
            } else if (lcr.errorMessage != null) {
                val msg = lcr.errorMessage
                uiState.errorMessage.value = "Unable to connect live.\n\n$msg"
                uiState.showErrorDialog.value = true
            }
        }
    }

    fun switchToInternalMicAndConnect(scope: CoroutineScope, micId: String? = null) {
        scope.launch {
            // Persist the chosen microphone (awaited) before connecting so the fallback uses it.
            if (micId != null) {
                model.updateStoredSettings(model.settings.copy(internalMicId = micId))
            }
            uiState.liveMode.intValue = LiveMode.CONNECTING.value
            buttonState.acquisitionChecked.value = true
            openLiveCheckingPermissions(Settings.LiveInputSourceOptions.PHONE_MIC.value)
        }
    }

    fun acceptHighRateMicOffer(appMode: MutableIntState) {
        model.armStartAudioAfterLiveConnect()
        appMode.intValue = AppMode.LIVE.value
        uiState.liveMode.intValue = LiveMode.CONNECTING.value
        buttonState.acquisitionChecked.value = true
        openLiveCheckingPermissions(Settings.LiveInputSourceOptions.USB.value)
    }

    /**
     * This method is called there is an error subsequent to successful
     * connection to the USB microphone.
     */
    fun onUsbError(result: LiveStreamErrorResult) {
        model.closePipeline()   // Idempotent. Also stops audio and file writing.

        uiState.liveMode.intValue = LiveMode.OFF.value
        uiState.audioMode.intValue = audioModeOff
        // Force the audio config model to be shown again if the microphone has been removed, so
        // that frequency sanity checks can be applied for the new microphone:
        uiState.audioSettingsAlreadyShown.value = false
        uiState.dataPresent.value = false

        buttonState.acquisitionChecked.value = false
        buttonState.manualRecordingEnabled.value = false
        buttonState.triggeredRecordingChecked.value = false
        buttonState.audioEnabled.value = false
        buttonState.slidersButtonEnabled.value = false
        buttonState.slidersButtonChecked.value = false

        uiState.title.value = null

        val errnoText = try {
            Os.strerror(result.errno)
        }
        catch (e: ErrnoException) {
            "Unknown errno"
        }

        uiState.errorMessage.value =
            "USB microphone communication error - please check that it is correctly plugged in.\n\n" +
                "errno = ${result.errno}: $errnoText"
        uiState.showErrorDialog.value = true
    }

    fun closeLive() {
        stopAudioAndResetUi()
        model.closePipeline()   // Idempotent.
    }

    fun onFileWriterError(msg: String) {
        buttonState.manualRecordingChecked.value = false
        buttonState.triggeredRecordingChecked.value = false
        updateRecordingButtonsEnabled(isLiveRecordingAvailable(AppMode.LIVE.value))

        /*  Removed for now, makes for a confusing UX.
        uiState.showErrorDialog.value = true
        uiState.errorMessage.value = msg
         */
    }
}
