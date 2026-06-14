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

package org.batgizmo.app

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/**
 * There are two kinds of settings:
 * * General settings that take effect immediately.
 * * Pipeline related settings that are used when creating a processing pipeline, and are
 *      associated the pipeline that was created.
 */
data class Settings(
    var useDarkTheme: Boolean = true,
    var amplitudePaneVisibility: Int = VisibilityOptions.AUTO.value,
    var showGrid: Boolean = true,
    var defaultLiveTimeSpanS: Int = DefaultLiveTimeSpanOptions.DEFAULTLIVETIMESPAN_3S.value,
    var liveInputSource: Int = LiveInputSourceOptions.USB.value,
    var pageOverlapPercent: Int = PagingOverlapOptions.PAGINGOVERLAP_25.value,                  // *
    var autoBnCEnabledViewer: Boolean = true,
    var autoBnCEnabledLive: Boolean = true,
    var autoBaselineEnabled: Boolean = false,
    var showParameterOverlay: Boolean = true,
    var leftHandButtons: Boolean = false,
    var enableLogging: Boolean = false,
    var heterodyneDual: Boolean = false,
    var heterodyneRef1kHz: Int = 50,
    var heterodyneRef2kHz: Int = 20,
    var audioPlaybackMode: Int = AudioPlaybackModeOptions.SINGLE_HETERODYNE.value,
    var audioPlaybackModePersisted: Boolean = false,
    var loopedAudioPlayback: Boolean = false,
    var includeLocationInFile: Boolean = true,
    var audioBoostFactor: Float = 4f,
    var preTriggerTimeMs: Int = PreTriggerTimeOptions.PRETRIGGER_TIME_500MS.value,
    var postTriggerTimeMs: Int = PostTriggerTimeOptions.POSTTRIGGER_TIME_1000MS.value,
    var maxFileTimeMs: Int = MaxFileTimeOptions.MAX_FILE_TIME_5000MS.value,
    var autoTriggerThresholdDb: Float = 40f,
    var autoTriggerRangeMinkHz: Float = 16f,
    var autoTriggerRangeMaxkHz: Float = 120f,
    var pipelineParameters: PipelineParameters = PipelineParameters()
) {
    // Provide some abstraction to allow different enums to be handled the same way:
    interface EnumHelper {
        fun theValue(): Int
        fun theLabel(): String
    }

    enum class VisibilityOptions(val value: Int, val label: String) : EnumHelper {
        AUTO(1, "Auto"),
        ALWAYS(2, "Always"),
        NEVER(3, "Never");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class NFftOptions(val value: Int, val label: String) : EnumHelper {
        NFFT_AUTO(0, "Auto"),
        NFFT_64(64, "64"),
        NFFT_128(128, "128"),
        NFFT_256(256, "256"),
        NFFT_512(512, "512"),
        NFFT_1024(1024, "1024"),
        NFFT_2048(2048, "2048"),
        NFFT_4096(4096, "4096");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class FftOverlapOptions(val value: Int, val label: String) : EnumHelper {
        OVERLAP_AUTO75(0, "Auto (up to 75%)"),
        OVERLAP_AUTO90(-1, "Auto (up to 90%)"), // Can be heavy on CPU.
        OVERLAP_25(25, "25%"),
        OVERLAP_50(50, "50%"),
        OVERLAP_75(75, "75%"),
        OVERLAP_90(90, "90%"),
        OVERLAP_95(95, "95%");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class DataBufferTimeSpanOptions(val value: Int, val label: String) : EnumHelper {
        DATABUFFER_5S(5, "5s"),
        DATABUFFER_10S(10, "10s"),
        DATABUFFER_15S(15, "15s"),
        DATABUFFER_20S(20, "20s"),
        DATABUFFER_30S(30, "30s (higher spec devices)"),
        DATABUFFER_60S(60, "60s (higher spec devices)");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class LiveInputSourceOptions(val value: Int, val label: String) : EnumHelper {
        USB(1, "External USB microphone"),
        PHONE_MIC(2, "Internal microphone");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class AudioPlaybackModeOptions(val value: Int, val label: String) : EnumHelper {
        SINGLE_HETERODYNE(0, "Single heterodyne"),
        DUAL_HETERODYNE(1, "Dual heterodyne"),
        DIRECT(2, "Direct playback");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    /** Sample rates at or below this use direct playback when no mode is stored. */
    fun defaultAudioPlaybackModeForSampleRate(sampleRateHz: Int): Int {
        return if (sampleRateHz <= DIRECT_PLAYBACK_MAX_SAMPLE_RATE_HZ)
            AudioPlaybackModeOptions.DIRECT.value
        else
            AudioPlaybackModeOptions.SINGLE_HETERODYNE.value
    }

    fun effectiveAudioPlaybackMode(sampleRateHz: Int): Int {
        return if (audioPlaybackModePersisted)
            audioPlaybackMode
        else
            defaultAudioPlaybackModeForSampleRate(sampleRateHz)
    }

    fun isDualHeterodynePlayback(sampleRateHz: Int): Boolean =
        effectiveAudioPlaybackMode(sampleRateHz) ==
            AudioPlaybackModeOptions.DUAL_HETERODYNE.value

    fun isDirectPlayback(sampleRateHz: Int): Boolean =
        effectiveAudioPlaybackMode(sampleRateHz) ==
            AudioPlaybackModeOptions.DIRECT.value

    enum class DefaultLiveTimeSpanOptions(val value: Int, val label: String) : EnumHelper {
        DEFAULTLIVETIMESPAN_NONE(0, "Use existing"),
        DEFAULTLIVETIMESPAN_1S(1, "1s"),
        DEFAULTLIVETIMESPAN_2S(2, "2s"),
        DEFAULTLIVETIMESPAN_3S(3, "3s"),
        DEFAULTLIVETIMESPAN_5S(5, "5s"),
        DEFAULTLIVETIMESPAN_10S(10, "10s");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class PagingOverlapOptions(val value: Int, val label: String) : EnumHelper {
        PAGINGOVERLAP_0(0, "0%"),
        PAGINGOVERLAP_10(10, "10%"),
        PAGINGOVERLAP_25(25, "25%"),
        PAGINGOVERLAP_50(50, "50%");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class PreTriggerTimeOptions(val value: Int, val label: String) : EnumHelper {
        PRETRIGGER_TIME_0MS(0, "none"),
        PRETRIGGER_TIME_200MS(200, "0.2s"),
        PRETRIGGER_TIME_500MS(500, "0.5s"),
        PRETRIGGER_TIME_MAX(1000, "1s");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class PostTriggerTimeOptions(val value: Int, val label: String) : EnumHelper {
        // Don't allow 0 as this would result in zero length data files which is silly.
        PRETRIGGER_TIME_200MS(200, "0.2s"),
        PRETRIGGER_TIME_500MS(500, "0.5s"),
        POSTTRIGGER_TIME_1000MS(1000, "1s"),
        POSTTRIGGER_TIME_2000MS(2000, "2s"),
        POSTTRIGGER_TIME_3000MS(3000, "3s"),
        POSTTRIGGER_TIME_4000MS(4000, "4s"),
        POSTTRIGGER_TIME_5000MS(5000, "5s");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class MaxFileTimeOptions(val value: Int, val label: String) : EnumHelper {
        MAX_FILE_TIME_500MS(500, "0.5s"),
        MAX_FILE_TIME_1000MS(1000, "1s"),
        MAX_FILE_TIME_2000MS(2000, "2s"),
        MAX_FILE_TIME_5000MS(5000, "5s"),
        MAX_FILE_TIME_10000MS(10000, "10s"),
        MAX_FILE_TIME_15000MS(15000, "15s");

        override fun theValue(): Int = value
        override fun theLabel(): String = label    }

    companion object {
        /** Sample rates at or below this use direct playback when no mode is stored. */
        const val DIRECT_PLAYBACK_MAX_SAMPLE_RATE_HZ = 48_000
    }

    private val keyUseDarkTheme = booleanPreferencesKey("useDarkTheme")
    private val keyAmplitudePaneVisibility = intPreferencesKey("amplitudePaneVisibility")
    private val keyShowGrid = booleanPreferencesKey("showGrid")
    private val keyAutoBnCViewer = booleanPreferencesKey("autoBnCViewer")
    private val keyAutoBnCLive = booleanPreferencesKey("autoBnCLive")
    private val keyAutoBaselineEnabled = booleanPreferencesKey("autoBaselineEnabled")
    private val keyShowParameterOverlay = booleanPreferencesKey("showParameterOverlay")
    private val keyDefaultLiveTimeSpanS = intPreferencesKey("keyDefaultLiveTimeSpanS")
    private val keyLiveInputSource = intPreferencesKey("liveInputSource")
    private val keyPageOverlapPercent = intPreferencesKey("keyPageOverlapPercent")
    private val keyLeftHandedMode = booleanPreferencesKey("keyLeftHandedMode")
    private val keyEnableLogging = booleanPreferencesKey("enableLogging")
    private val keyAudioRef1kHz = intPreferencesKey("audioRef1kHz")
    private val keyAudioRef2kHz = intPreferencesKey("audioRef2kHz")
    private val keyAudioDualHeterodyne = booleanPreferencesKey("audioDualHeterodyne")
    private val keyAudioPlaybackMode = intPreferencesKey("audioPlaybackMode")
    private val keyAudioBoostFactor = floatPreferencesKey("audioBoostFactor")
    private val keyLocationInFile = booleanPreferencesKey("locationInFile")
    private val keyPreTriggerTimeMs = intPreferencesKey("preTriggerTimeMs")
    private val keyPostTriggerTimeMs = intPreferencesKey("postTriggerTimeMs")
    private val keyMaxFileTimeMs = intPreferencesKey("maxFileTimeMs")
    private val keyAutoTriggerThresholdDb = floatPreferencesKey("autoTriggerThresholdDb")
    private val keyAutoTriggerRangeStartkHz = floatPreferencesKey("autoTriggerRangeStartkHz")
    private val keyAutoTriggerRangeEndkHz = floatPreferencesKey("autoTriggerRangeEndkHz")
    private val keyLoopedAudioPlayback = booleanPreferencesKey("loopedAudioPlayback")


    fun copyToPreferences(prefs: MutablePreferences) {

        pipelineParameters.copyToPreferences(prefs)

        // Copy the settings data into the preferences datastore:
        prefs[keyUseDarkTheme] = useDarkTheme
        prefs[keyShowParameterOverlay] = showParameterOverlay
        prefs[keyAmplitudePaneVisibility] = amplitudePaneVisibility
        prefs[keyShowGrid] = showGrid
        prefs[keyAutoBnCViewer] = autoBnCEnabledViewer
        prefs[keyAutoBnCLive] = autoBnCEnabledLive
        prefs[keyAutoBaselineEnabled] = autoBaselineEnabled
        prefs[keyAutoBaselineEnabled] = autoBaselineEnabled
        prefs[keyDefaultLiveTimeSpanS] = defaultLiveTimeSpanS
        prefs[keyLiveInputSource] = liveInputSource
        prefs[keyPageOverlapPercent] = pageOverlapPercent
        prefs[keyLeftHandedMode] = leftHandButtons
        prefs[keyEnableLogging] = enableLogging
        heterodyneDual = audioPlaybackMode == AudioPlaybackModeOptions.DUAL_HETERODYNE.value
        prefs[keyAudioDualHeterodyne] = heterodyneDual
        if (audioPlaybackModePersisted)
            prefs[keyAudioPlaybackMode] = audioPlaybackMode
        prefs[keyAudioRef1kHz] = heterodyneRef1kHz
        prefs[keyAudioRef2kHz] = heterodyneRef2kHz
        prefs[keyAudioBoostFactor] = audioBoostFactor
        prefs[keyLocationInFile] = includeLocationInFile
        prefs[keyPreTriggerTimeMs] = preTriggerTimeMs
        prefs[keyPostTriggerTimeMs] = postTriggerTimeMs
        prefs[keyMaxFileTimeMs] = maxFileTimeMs
        prefs[keyAutoTriggerThresholdDb] = autoTriggerThresholdDb
        prefs[keyAutoTriggerRangeStartkHz] = autoTriggerRangeMinkHz
        prefs[keyAutoTriggerRangeEndkHz] = autoTriggerRangeMaxkHz
        prefs[keyLoopedAudioPlayback] = loopedAudioPlayback
    }

    fun copyFromPreferences(prefs: Preferences) {

        pipelineParameters.copyFromPreferences(prefs)

        if (prefs[keyUseDarkTheme] != null)
            useDarkTheme = requireNotNull(prefs[keyUseDarkTheme])
        if (prefs[keyShowParameterOverlay] != null)
            showParameterOverlay = requireNotNull(prefs[keyShowParameterOverlay])
        if (prefs[keyAmplitudePaneVisibility] != null)
            amplitudePaneVisibility = requireNotNull(prefs[keyAmplitudePaneVisibility])
        if (prefs[keyShowGrid] != null)
            showGrid = requireNotNull(prefs[keyShowGrid])
        if (prefs[keyAutoBnCViewer] != null)
            autoBnCEnabledViewer = requireNotNull(prefs[keyAutoBnCViewer])
        if (prefs[keyAutoBnCLive] != null)
            autoBnCEnabledLive = requireNotNull(prefs[keyAutoBnCLive])
        if (prefs[keyAutoBaselineEnabled] != null)
            autoBaselineEnabled = requireNotNull(prefs[keyAutoBaselineEnabled])
        if (prefs[keyDefaultLiveTimeSpanS] != null)
            defaultLiveTimeSpanS = requireNotNull(prefs[keyDefaultLiveTimeSpanS])
        if (prefs[keyLiveInputSource] != null)
            liveInputSource = requireNotNull(prefs[keyLiveInputSource])
        if (prefs[keyPageOverlapPercent] != null)
            pageOverlapPercent = requireNotNull(prefs[keyPageOverlapPercent])
        if (prefs[keyLeftHandedMode] != null)
            leftHandButtons = requireNotNull(prefs[keyLeftHandedMode])
        if (prefs[keyEnableLogging] != null)
            enableLogging = requireNotNull(prefs[keyEnableLogging])
        if (prefs[keyAudioPlaybackMode] != null) {
            audioPlaybackModePersisted = true
            audioPlaybackMode = requireNotNull(prefs[keyAudioPlaybackMode])
            heterodyneDual =
                audioPlaybackMode == AudioPlaybackModeOptions.DUAL_HETERODYNE.value
        } else {
            audioPlaybackModePersisted = false
        }
        if (prefs[keyAudioRef1kHz] != null)
            heterodyneRef1kHz = requireNotNull(prefs[keyAudioRef1kHz])
        if (prefs[keyAudioRef2kHz] != null)
            heterodyneRef2kHz = requireNotNull(prefs[keyAudioRef2kHz])
        if (prefs[keyAudioBoostFactor] != null)
            audioBoostFactor = requireNotNull(prefs[keyAudioBoostFactor])
        if (prefs[keyLocationInFile] != null)
            includeLocationInFile = requireNotNull(prefs[keyLocationInFile])
        if (prefs[keyPreTriggerTimeMs] != null)
            preTriggerTimeMs = requireNotNull(prefs[keyPreTriggerTimeMs])
        if (prefs[keyPostTriggerTimeMs] != null)
            postTriggerTimeMs = requireNotNull(prefs[keyPostTriggerTimeMs])
        if (prefs[keyMaxFileTimeMs] != null)
            maxFileTimeMs = requireNotNull(prefs[keyMaxFileTimeMs])
        if (prefs[keyAutoTriggerThresholdDb] != null)
            autoTriggerThresholdDb = requireNotNull(prefs[keyAutoTriggerThresholdDb])
        if (prefs[keyAutoTriggerRangeStartkHz] != null)
            autoTriggerRangeMinkHz = requireNotNull(prefs[keyAutoTriggerRangeStartkHz])
        if (prefs[keyAutoTriggerRangeEndkHz] != null)
            autoTriggerRangeMaxkHz = requireNotNull(prefs[keyAutoTriggerRangeEndkHz])
        if (prefs[keyLoopedAudioPlayback] != null)
            loopedAudioPlayback = requireNotNull(prefs[keyLoopedAudioPlayback])
    }
}

data class PipelineParameters(
    var dataPageTimeSpanS: Int = DataBufferTimeSpanOptions.DATABUFFER_10S.value,
    var fftOverlapPercent: Int = FftOverlapOptions.OVERLAP_AUTO75.value,
    var nFft: Int = NFftOptions.NFFT_AUTO.value
) {
    // Provide some abstraction to allow different enums to be handled the same way:
    interface EnumHelper {
        fun theValue(): Int
        fun theLabel(): String
    }

    enum class NFftOptions(val value: Int, val label: String) : EnumHelper {
        NFFT_AUTO(0, "Auto"),
        NFFT_64(64, "64"),
        NFFT_128(128, "128"),
        NFFT_256(256, "256"),
        NFFT_512(512, "512"),
        NFFT_1024(1024, "1024"),
        NFFT_2048(2048, "2048"),
        NFFT_4096(4096, "4096");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class FftOverlapOptions(val value: Int, val label: String) : EnumHelper {
        OVERLAP_AUTO75(0, "Auto (up to 75%)"),
        OVERLAP_AUTO90(-1, "Auto (up to 90%)"), // Can be heavy on CPU.
        OVERLAP_25(25, "25%"),
        OVERLAP_50(50, "50%"),
        OVERLAP_75(75, "75%"),
        OVERLAP_90(90, "90%"),
        OVERLAP_95(95, "95%");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    enum class DataBufferTimeSpanOptions(val value: Int, val label: String) : EnumHelper {
        DATABUFFER_5S(5, "5s"),
        DATABUFFER_10S(10, "10s"),
        DATABUFFER_15S(15, "15s"),
        DATABUFFER_20S(20, "20s"),
        DATABUFFER_30S(30, "30s");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    companion object {
        /**
         * Coerce the FFT window size provided to the range supported.
         */
        fun coerceNFft(nFft: Int): Int {
            return nFft.coerceIn(NFftOptions.NFFT_64.value, NFftOptions.NFFT_4096.value)
        }

        fun isAutoOverlap(overlapOption: Int): Boolean {
            return overlapOption == FftOverlapOptions.OVERLAP_AUTO75.value
                    || overlapOption == FftOverlapOptions.OVERLAP_AUTO90.value
        }
    }

    private val keyNFft = intPreferencesKey("nFft")
    private val keyFftOverlapPercent = intPreferencesKey("fftOverlapPercent")
    private val keyDataBufferIntervalS = intPreferencesKey("keyDataBufferIntervalS")

    fun copyToPreferences(prefs: MutablePreferences) {
        // Copy the settings data into the preferences datastore:
        prefs[keyNFft] = nFft
        prefs[keyFftOverlapPercent] = fftOverlapPercent
        prefs[keyDataBufferIntervalS] = dataPageTimeSpanS
    }

    fun copyFromPreferences(prefs: Preferences) {
        if (prefs[keyNFft] != null)
            nFft = requireNotNull(prefs[keyNFft])
        if (prefs[keyFftOverlapPercent] != null)
            fftOverlapPercent = requireNotNull(prefs[keyFftOverlapPercent])
        if (prefs[keyDataBufferIntervalS] != null)
            dataPageTimeSpanS = requireNotNull(prefs[keyDataBufferIntervalS])
    }
}
