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

package org.batgizmo.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.batgizmo.app.pipeline.UsbHighRateMicProbeResult
import uk.org.gimell.batgimzoapp.BuildConfig

/**
 * Sequences startup UI prompts so What’s New and the high-rate USB mic offer
 * do not stack. What’s New is deferred until the mic probe/offer path has
 * finished (or been skipped).
 */
class StartupPromptCoordinator(
    private val scope: CoroutineScope,
    private val settingsDataStore: DataStore<Preferences>,
    private val settings: () -> Settings,
    private val suppressUpdateNotification: suspend () -> Unit,
    private val suppressHighRateMicOffer: suspend () -> Unit,
    private val probeResults: Flow<UsbHighRateMicProbeResult>,
    private val startHighRateProbe: suspend () -> Unit,
) {
    private val keyLastSeenVersion = stringPreferencesKey("lastSeenVersion")

    private val _showWhatsNew = MutableStateFlow(false)
    val showWhatsNew: StateFlow<Boolean> = _showWhatsNew.asStateFlow()

    /** Version-update dialog requested but held until the high-rate mic offer is finished. */
    private var pendingWhatsNew = false

    private val _highRateMicOffer = MutableStateFlow<UsbHighRateMicProbeResult?>(null)
    val highRateMicOffer: StateFlow<UsbHighRateMicProbeResult?> = _highRateMicOffer.asStateFlow()

    private var highRateUsbProbeAttempted = false
    private var highRateUsbProbeCompleted = false

    @Volatile
    private var launchedForFileView = false

    /** Start version check and probe-result collection. Call once from ViewModel init. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            val prefs = settingsDataStore.data.first()
            val lastSeen = prefs[keyLastSeenVersion]
            val current = BuildConfig.VERSION_NAME
            val snapshot = Settings()
            snapshot.copyFromPreferences(prefs)
            if (lastSeen != null && lastSeen != current && !snapshot.suppressUpdateNotification) {
                // Defer until after the high-rate mic startup offer (if any).
                pendingWhatsNew = true
                tryShowPendingWhatsNew()
            }
            // Persist the current version.
            settingsDataStore.edit { it[keyLastSeenVersion] = current }
        }

        scope.launch {
            probeResults.collect { result ->
                highRateUsbProbeCompleted = true
                if (result.found && !launchedForFileView)
                    _highRateMicOffer.value = result
                else
                    tryShowPendingWhatsNew()
            }
        }
    }

    fun dismissWhatsNew(suppressFurther: Boolean = false) {
        _showWhatsNew.value = false
        if (suppressFurther) {
            scope.launch {
                suppressUpdateNotification()
            }
        }
    }

    /**
     * Call when the activity was started (or re-started) with ACTION_VIEW to open a file.
     * Suppresses the high-rate USB mic startup helper for that launch.
     */
    fun noteLaunchedForFileView() {
        launchedForFileView = true
        // Drop any offer that raced ahead of the VIEW intent handling.
        if (_highRateMicOffer.value != null) {
            _highRateMicOffer.value = null
            tryShowPendingWhatsNew()
        }
    }

    fun dismissHighRateMicOffer(suppressFurther: Boolean = false) {
        _highRateMicOffer.value = null
        if (suppressFurther) {
            scope.launch {
                suppressHighRateMicOffer()
            }
        }
        tryShowPendingWhatsNew()
    }

    /**
     * Once per process, if the preferred live source is USB, probe for a high-rate
     * (>= 192 kHz) microphone using the shared USB selection/permission path.
     */
    fun maybeProbeHighRateUsbMicrophone() {
        if (highRateUsbProbeAttempted)
            return
        highRateUsbProbeAttempted = true
        val s = settings()
        if (launchedForFileView ||
            s.suppressHighRateMicOffer ||
            s.liveInputSource != Settings.LiveInputSourceOptions.USB.value
        ) {
            highRateUsbProbeCompleted = true
            tryShowPendingWhatsNew()
            return
        }
        scope.launch(Dispatchers.Default) {
            startHighRateProbe()
        }
    }

    /** Show What’s New only after the startup mic probe/offer path has finished. */
    private fun tryShowPendingWhatsNew() {
        if (!pendingWhatsNew)
            return
        if (!highRateUsbProbeCompleted)
            return
        if (_highRateMicOffer.value != null)
            return
        pendingWhatsNew = false
        _showWhatsNew.value = true
    }
}
