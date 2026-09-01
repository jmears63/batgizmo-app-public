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

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

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
    var showHeterodyneReferenceLine: Boolean = true,
    var defaultLiveTimeSpanS: Int = DefaultLiveTimeSpanOptions.DEFAULTLIVETIMESPAN_3S.value,
    var liveInputSource: Int = LiveInputSourceOptions.USB.value,
    // Stable descriptor of the chosen internal microphone, or "" for automatic selection.
    var internalMicId: String = "",
    var pageOverlapPercent: Int = PagingOverlapOptions.PAGINGOVERLAP_25.value,                  // *
    var autoBnCEnabledViewer: Boolean = true,
    var autoBnCEnabledLive: Boolean = true,
    var autoBaselineEnabled: Boolean = false,
    /** Spectrogram overlay text: none, time and sunset, or plus technical parameters. */
    var overlayTextMode: Int = OverlayTextModeOptions.BASIC.value,
    /** Selected spectrogram colour map; see [ColourMapOptions]. */
    var colourMap: Int = ColourMapOptions.KINDLMANN.value,
    var leftHandButtons: Boolean = false,
    var enableLogging: Boolean = false,
    var heterodyneDual: Boolean = false,
    var heterodyneRef1kHz: Int = 50,
    var heterodyneRef2kHz: Int = 20,
    var audioPlaybackMode: Int = AudioPlaybackModeOptions.AUTO_TUNED_HETERODYNE.value,
    var audioPlaybackModePersisted: Boolean = false,
    var audioPitchRatio: Int = AudioPitchRatioOptions.DEFAULT.value,
    var audioTimeExpansionFactor: Int = AudioTimeExpansionFactorOptions.DEFAULT.value,
    /** Optional 4 kHz two-pole HPF on pitch-shifting input (before TD-OLA). */
    var audioPitchHpfEnabled: Boolean = true,
    var loopedAudioPlayback: Boolean = false,
    var suppressAudioFeedbackWarning: Boolean = false,
    /** Shape preset for auto-tuned heterodyne tracking. */
    var autoHeterodyneMode: Int = AutoHeterodyneModeOptions.HOCKEY_STICK.value,
    /** Lower frequency limit (kHz) for auto heterodyne activity spans. */
    var autoHeterodyneLoMinKhz: Int = DEFAULT_AUTO_HET_LO_MIN_KHZ,
    /** Upper frequency limit (kHz) for auto heterodyne activity spans. */
    var autoHeterodyneLoMaxKhz: Int = DEFAULT_AUTO_HET_LO_MAX_KHZ,
    var includeLocationInFile: Boolean = true,
    var audioBoostFactor: Float = DEFAULT_AUDIO_BOOST_FACTOR,
    var audioAGCEnabled: Boolean = true,
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

    /** Spectrogram overlay text detail level. */
    enum class OverlayTextModeOptions(val value: Int, val label: String) : EnumHelper {
        NONE(0, "None"),
        BASIC(1, "Basic"),
        FULL(2, "Full");

        override fun theValue(): Int = value
        override fun theLabel(): String = label

        companion object {
            val DEFAULT = BASIC
            fun coerce(mode: Int): Int =
                entries.firstOrNull { it.value == mode }?.value ?: DEFAULT.value
        }
    }

    enum class ColourMapOptions(
        val value: Int,
        val label: String,
        val assetFilename: String
    ) : EnumHelper {
        KINDLMANN(0, "Kindlmann", "kindlmann-256.csv"),
        EXTENDED_KINDLMANN(1, "Extended Kindlmann", "extended-kindlmann-256.csv"),
        BLACK_BODY(2, "Black body", "black-body-256.csv"),
        INFERNO(3, "Inferno", "inferno-256.csv"),
        GREYSCALE(4, "Greyscale", "greyscale-256.csv"),
        BLACK_RED_YELLOW_WHITE(5, "Black-Red-Yellow-White", "cet-l03-256.csv"),
        BLUE_PINK_LIGHT_PINK(6, "Blue-Pink-Light Pink", "cet-l07-256.csv"),
        BLUE_MAGENTA_YELLOW(7, "Blue-Magenta-Yellow", "cet-l08-256.csv"),
        BLUE_GREEN_YELLOW(8, "Blue-Green-Yellow", "cet-l09-256.csv"),
        BLACK_BLUE_GREEN_YELLOW_WHITE(9, "Black-Blue-Green-Yellow-White", "cet-l16-256.csv"),
        BLACK_BLUE_GREEN_ORANGE_YELLOW(10, "Black-Blue-Green-Orange-Yellow", "cet-l20-256.csv");

        override fun theValue(): Int = value
        override fun theLabel(): String = label

        companion object {
            fun fromValue(value: Int): ColourMapOptions =
                entries.firstOrNull { it.value == value } ?: KINDLMANN
        }
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
        AUTO_TUNED_HETERODYNE(5, "Auto heterodyne"),
        SINGLE_HETERODYNE(0, "Classic heterodyne"),
        DUAL_HETERODYNE(1, "Dual heterodyne"),
        PITCH_SHIFTING(3, "Pitch shifting"),
        TIME_EXPANSION(4, "Classic time expansion"),
        DIRECT(2, "Direct playback");

        override fun theValue(): Int = value
        override fun theLabel(): String = label
    }

    /** Auto-tuned heterodyne tracking shape presets. */
    enum class AutoHeterodyneModeOptions(val value: Int, val label: String) : EnumHelper {
        HOCKEY_STICK(0, "Hockey Stick"),
        MYOTIS(1, "Generic/Myotis"),
        RHINOLOPHUS(2, "Rhinolophus");

        override fun theValue(): Int = value
        override fun theLabel(): String = label

        companion object {
            val DEFAULT = HOCKEY_STICK
            fun coerce(mode: Int): Int =
                entries.firstOrNull { it.value == mode }?.value ?: DEFAULT.value
        }
    }

    /** Pitch division ratios for TD-OLA playback (value = ÷ factor). */
    enum class AudioPitchRatioOptions(val value: Int, val label: String) : EnumHelper {
        PITCH_4(4, "4"),
        PITCH_6(6, "6"),
        PITCH_8(8, "8"),
        PITCH_12(12, "12"),
        PITCH_16(16, "16"),
        PITCH_24(24, "24"),
        PITCH_32(32, "32");

        override fun theValue(): Int = value
        override fun theLabel(): String = label

        /**
         * True if this factor can be realised without clamping W_out in dsp_tdola
         * (W_out = Win * pitch * Fout / Fin ≤ Win * 4).
         */
        fun isApplicable(sampleRateHz: Int): Boolean =
            value.toLong() * TARGET_AUDIO_OUT_RATE_HZ <=
                TDOLA_MAX_WOUT_OVER_WIN.toLong() * sampleRateHz

        companion object {
            val DEFAULT = PITCH_16
            fun coerce(ratio: Int): Int =
                entries.firstOrNull { it.value == ratio }?.value ?: DEFAULT.value

            fun coerceForSampleRate(ratio: Int, sampleRateHz: Int): Int {
                val preferred = coerce(ratio)
                if (entries.any { it.value == preferred && it.isApplicable(sampleRateHz) })
                    return preferred
                return entries.filter { it.isApplicable(sampleRateHz) }
                    .maxByOrNull { it.value }?.value
                    ?: DEFAULT.value
            }
        }
    }

    /** Classic time-expansion slowdown factors (viewer mode); same set as pitch ratios. */
    enum class AudioTimeExpansionFactorOptions(val value: Int, val label: String) : EnumHelper {
        TE_4(4, "4"),
        TE_6(6, "6"),
        TE_8(8, "8"),
        TE_12(12, "12"),
        TE_16(16, "16"),
        TE_24(24, "24"),
        TE_32(32, "32");

        override fun theValue(): Int = value
        override fun theLabel(): String = label

        companion object {
            val DEFAULT = TE_8
            fun coerce(factor: Int): Int =
                entries.firstOrNull { it.value == factor }?.value ?: DEFAULT.value
        }
    }

    /** Default mode when none has been confirmed/persisted yet. */
    fun defaultAudioPlaybackModeForSampleRate(sampleRateHz: Int): Int =
        if (isAutoHeterodyneSampleRateApplicable(sampleRateHz))
            AudioPlaybackModeOptions.AUTO_TUNED_HETERODYNE.value
        else
            AudioPlaybackModeOptions.SINGLE_HETERODYNE.value

    /** Clamp a stored playback mode to what [sampleRateHz] can support. */
    fun coerceAudioPlaybackModeForSampleRate(mode: Int, sampleRateHz: Int): Int {
        if (mode == AudioPlaybackModeOptions.AUTO_TUNED_HETERODYNE.value &&
            !isAutoHeterodyneSampleRateApplicable(sampleRateHz)
        ) {
            return AudioPlaybackModeOptions.SINGLE_HETERODYNE.value
        }
        return mode
    }

    /**
     * True when the user previously confirmed auto heterodyne but [sampleRateHz] is now too low.
     * The audio playback modal should be shown again for an explicit mode choice.
     */
    fun requiresAudioModeReselection(sampleRateHz: Int): Boolean =
        audioPlaybackModePersisted &&
            audioPlaybackMode == AudioPlaybackModeOptions.AUTO_TUNED_HETERODYNE.value &&
            !isAutoHeterodyneSampleRateApplicable(sampleRateHz)

    fun effectiveAudioPlaybackMode(sampleRateHz: Int): Int {
        val mode = if (audioPlaybackModePersisted)
            audioPlaybackMode
        else
            defaultAudioPlaybackModeForSampleRate(sampleRateHz)
        return coerceAudioPlaybackModeForSampleRate(mode, sampleRateHz)
    }

    fun isDualHeterodynePlayback(sampleRateHz: Int): Boolean =
        effectiveAudioPlaybackMode(sampleRateHz) ==
            AudioPlaybackModeOptions.DUAL_HETERODYNE.value

    fun isDirectPlayback(sampleRateHz: Int): Boolean =
        effectiveAudioPlaybackMode(sampleRateHz) ==
            AudioPlaybackModeOptions.DIRECT.value

    fun isPitchShiftingPlayback(sampleRateHz: Int): Boolean =
        effectiveAudioPlaybackMode(sampleRateHz) ==
            AudioPlaybackModeOptions.PITCH_SHIFTING.value

    fun isTimeExpansionPlayback(sampleRateHz: Int): Boolean =
        effectiveAudioPlaybackMode(sampleRateHz) ==
            AudioPlaybackModeOptions.TIME_EXPANSION.value

    fun isAutoTunedHeterodynePlayback(sampleRateHz: Int): Boolean =
        effectiveAudioPlaybackMode(sampleRateHz) ==
            AudioPlaybackModeOptions.AUTO_TUNED_HETERODYNE.value

    /**
     * Classic single/dual heterodyne — modes with a manual reference frequency UI.
     */
    fun isHeterodynePlayback(sampleRateHz: Int): Boolean {
        val mode = effectiveAudioPlaybackMode(sampleRateHz)
        return mode == AudioPlaybackModeOptions.SINGLE_HETERODYNE.value ||
                mode == AudioPlaybackModeOptions.DUAL_HETERODYNE.value
    }

    /** Any mode that uses the heterodyne DSP path (manual or auto-tuned). */
    fun usesHeterodyneDsp(sampleRateHz: Int): Boolean =
        isHeterodynePlayback(sampleRateHz) || isAutoTunedHeterodynePlayback(sampleRateHz)

    /** Clamp a manual heterodyne LO (kHz) to at least [HETERODYNE_MIN_REF_KHZ]. */
    fun coerceHeterodyneRefkHz(kHz: Int): Int =
        kHz.coerceAtLeast(HETERODYNE_MIN_REF_KHZ)

    /** Valid integer kHz limits for the auto heterodyne LO range UI. */
    fun coerceAutoHeterodyneLoLimitKhz(kHz: Int): Int =
        kHz.coerceIn(AUTO_HET_LO_LIMIT_MIN_KHZ, AUTO_HET_LO_LIMIT_MAX_KHZ)

    /** Coerced auto heterodyne LO limits with min ≤ max. */
    fun normalizedAutoHeterodyneLoRange(): Pair<Int, Int> {
        val min = coerceAutoHeterodyneLoLimitKhz(autoHeterodyneLoMinKhz)
        val max = coerceAutoHeterodyneLoLimitKhz(autoHeterodyneLoMaxKhz)
        return if (min <= max)
            min to max
        else
            min to min
    }

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
        PRETRIGGER_TIME_1000MS(1000, "1s"),
        PRETRIGGER_TIME_2000MS(2000, "2s"),
        PRETRIGGER_TIME_5000MS(5000, "5s"),
        PRETRIGGER_TIME_MAX(10000, "10s");

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
        /** Default manual audio boost multiplier (UI slider). */
        const val DEFAULT_AUDIO_BOOST_FACTOR = 1f

        /**
         * AAudio / pitch / TE output rate. Must match TARGET_AUDIO_OUT_RATE in dsp_internal.h.
         */
        const val TARGET_AUDIO_OUT_RATE_HZ = 48_000

        /**
         * Max W_out/Win in dsp_tdola (DSP_TDOLA_OUT_MAX / DSP_TDOLA_WINDOW_LEN).
         * Limits how large a pitch factor can be for a given Fin.
         */
        const val TDOLA_MAX_WOUT_OVER_WIN = 4

        /** Sample rates at or below this use direct playback when no mode is stored. */
        const val DIRECT_PLAYBACK_MAX_SAMPLE_RATE_HZ = TARGET_AUDIO_OUT_RATE_HZ

        /** Auto heterodyne requires at least this input sample rate (Hz). */
        const val AUTO_HET_MIN_SAMPLE_RATE_HZ = 192_000

        /** Minimum LO (kHz) for manual classic/dual heterodyne reference controls. */
        const val HETERODYNE_MIN_REF_KHZ = 15

        /** Default auto heterodyne LO range (kHz). */
        const val DEFAULT_AUTO_HET_LO_MIN_KHZ = 16
        const val DEFAULT_AUTO_HET_LO_MAX_KHZ = 120

        /** Valid auto heterodyne activity-span frequency limits (kHz). */
        const val AUTO_HET_LO_LIMIT_MIN_KHZ = 10
        const val AUTO_HET_LO_LIMIT_MAX_KHZ = 130

        fun isAutoHeterodyneSampleRateApplicable(sampleRateHz: Int): Boolean =
            sampleRateHz >= AUTO_HET_MIN_SAMPLE_RATE_HZ
    }

    private val keyUseDarkTheme = booleanPreferencesKey("useDarkTheme")
    private val keyAmplitudePaneVisibility = intPreferencesKey("amplitudePaneVisibility")
    private val keyShowGrid = booleanPreferencesKey("showGrid")
    private val keyOverlayTextMode = intPreferencesKey("overlayTextMode")
    private val keyShowParameterOverlay = booleanPreferencesKey("showParameterOverlay")
    private val keyShowHeterodyneReferenceLine =
        booleanPreferencesKey("showHeterodyneReferenceLine")
    private val keyAutoBnCViewer = booleanPreferencesKey("autoBnCViewer")
    private val keyAutoBnCLive = booleanPreferencesKey("autoBnCLive")
    private val keyAutoBaselineEnabled = booleanPreferencesKey("autoBaselineEnabled")
    private val keyColourMap = intPreferencesKey("colourMap")
    private val keyDefaultLiveTimeSpanS = intPreferencesKey("keyDefaultLiveTimeSpanS")
    private val keyLiveInputSource = intPreferencesKey("liveInputSource")
    private val keyInternalMicId = stringPreferencesKey("internalMicId")
    private val keyPageOverlapPercent = intPreferencesKey("keyPageOverlapPercent")
    private val keyLeftHandedMode = booleanPreferencesKey("keyLeftHandedMode")
    private val keyEnableLogging = booleanPreferencesKey("enableLogging")
    private val keyAudioRef1kHz = intPreferencesKey("audioRef1kHz")
    private val keyAudioRef2kHz = intPreferencesKey("audioRef2kHz")
    private val keyAudioDualHeterodyne = booleanPreferencesKey("audioDualHeterodyne")
    private val keyAudioPlaybackMode = intPreferencesKey("audioPlaybackMode")
    private val keyAudioPitchRatio = intPreferencesKey("audioPitchRatio")
    private val keyAudioTimeExpansionFactor = intPreferencesKey("audioTimeExpansionFactor")
    private val keyAudioPitchHpfEnabled = booleanPreferencesKey("audioPitchHpfEnabled")
    private val keyAudioBoostFactor = floatPreferencesKey("audioBoostFactor2")
    private val keyAudioAGCEnabled = booleanPreferencesKey("audioAGCEnabled")
    private val keyLocationInFile = booleanPreferencesKey("locationInFile")
    private val keyPreTriggerTimeMs = intPreferencesKey("preTriggerTimeMs")
    private val keyPostTriggerTimeMs = intPreferencesKey("postTriggerTimeMs")
    private val keyMaxFileTimeMs = intPreferencesKey("maxFileTimeMs")
    private val keyAutoTriggerThresholdDb = floatPreferencesKey("autoTriggerThresholdDb")
    private val keyAutoTriggerRangeStartkHz = floatPreferencesKey("autoTriggerRangeStartkHz")
    private val keyAutoTriggerRangeEndkHz = floatPreferencesKey("autoTriggerRangeEndkHz")
    private val keyLoopedAudioPlayback = booleanPreferencesKey("loopedAudioPlayback")
    private val keySuppressAudioFeedbackWarning = booleanPreferencesKey("suppressAudioFeedbackWarning")
    private val keyAutoHeterodyneMode = intPreferencesKey("autoHeterodyneMode")
    private val keyAutoHeterodyneLoMinKhz = intPreferencesKey("autoHeterodyneLoMinKhz")
    private val keyAutoHeterodyneLoMaxKhz = intPreferencesKey("autoHeterodyneLoMaxKhz")


    fun copyToPreferences(prefs: MutablePreferences) {

        pipelineParameters.copyToPreferences(prefs)

        // Copy the settings data into the preferences datastore:
        prefs[keyUseDarkTheme] = useDarkTheme
        prefs[keyOverlayTextMode] = OverlayTextModeOptions.coerce(overlayTextMode)
        prefs[keyColourMap] = colourMap
        prefs[keyAmplitudePaneVisibility] = amplitudePaneVisibility
        prefs[keyShowGrid] = showGrid
        prefs[keyShowHeterodyneReferenceLine] = showHeterodyneReferenceLine
        prefs[keyAutoBnCViewer] = autoBnCEnabledViewer
        prefs[keyAutoBnCLive] = autoBnCEnabledLive
        prefs[keyAutoBaselineEnabled] = autoBaselineEnabled
        prefs[keyAutoBaselineEnabled] = autoBaselineEnabled
        prefs[keyDefaultLiveTimeSpanS] = defaultLiveTimeSpanS
        prefs[keyLiveInputSource] = liveInputSource
        prefs[keyInternalMicId] = internalMicId
        prefs[keyPageOverlapPercent] = pageOverlapPercent
        prefs[keyLeftHandedMode] = leftHandButtons
        prefs[keyEnableLogging] = enableLogging
        heterodyneDual = audioPlaybackMode == AudioPlaybackModeOptions.DUAL_HETERODYNE.value
        prefs[keyAudioDualHeterodyne] = heterodyneDual
        if (audioPlaybackModePersisted)
            prefs[keyAudioPlaybackMode] = audioPlaybackMode
        prefs[keyAudioPitchRatio] = AudioPitchRatioOptions.coerce(audioPitchRatio)
        prefs[keyAudioTimeExpansionFactor] =
            AudioTimeExpansionFactorOptions.coerce(audioTimeExpansionFactor)
        prefs[keyAudioPitchHpfEnabled] = audioPitchHpfEnabled
        prefs[keyAudioRef1kHz] = heterodyneRef1kHz
        prefs[keyAudioRef2kHz] = heterodyneRef2kHz
        prefs[keyAudioBoostFactor] = audioBoostFactor
        prefs[keyAudioAGCEnabled] = audioAGCEnabled
        prefs[keyLocationInFile] = includeLocationInFile
        prefs[keyPreTriggerTimeMs] = preTriggerTimeMs
        prefs[keyPostTriggerTimeMs] = postTriggerTimeMs
        prefs[keyMaxFileTimeMs] = maxFileTimeMs
        prefs[keyAutoTriggerThresholdDb] = autoTriggerThresholdDb
        prefs[keyAutoTriggerRangeStartkHz] = autoTriggerRangeMinkHz
        prefs[keyAutoTriggerRangeEndkHz] = autoTriggerRangeMaxkHz
        prefs[keyLoopedAudioPlayback] = loopedAudioPlayback
        prefs[keySuppressAudioFeedbackWarning] = suppressAudioFeedbackWarning
        prefs[keyAutoHeterodyneMode] = AutoHeterodyneModeOptions.coerce(autoHeterodyneMode)
        val (loMinKhz, loMaxKhz) = normalizedAutoHeterodyneLoRange()
        autoHeterodyneLoMinKhz = loMinKhz
        autoHeterodyneLoMaxKhz = loMaxKhz
        prefs[keyAutoHeterodyneLoMinKhz] = loMinKhz
        prefs[keyAutoHeterodyneLoMaxKhz] = loMaxKhz
    }

    fun copyFromPreferences(prefs: Preferences) {

        pipelineParameters.copyFromPreferences(prefs)

        if (prefs[keyUseDarkTheme] != null)
            useDarkTheme = requireNotNull(prefs[keyUseDarkTheme])
        if (prefs[keyOverlayTextMode] != null)
            overlayTextMode =
                OverlayTextModeOptions.coerce(requireNotNull(prefs[keyOverlayTextMode]))
        else if (prefs[keyShowParameterOverlay] != null)
            overlayTextMode =
                if (requireNotNull(prefs[keyShowParameterOverlay]))
                    OverlayTextModeOptions.FULL.value
                else
                    OverlayTextModeOptions.NONE.value
        if (prefs[keyColourMap] != null)
            colourMap = requireNotNull(prefs[keyColourMap])
        if (prefs[keyAmplitudePaneVisibility] != null)
            amplitudePaneVisibility = requireNotNull(prefs[keyAmplitudePaneVisibility])
        if (prefs[keyShowGrid] != null)
            showGrid = requireNotNull(prefs[keyShowGrid])
        if (prefs[keyShowHeterodyneReferenceLine] != null)
            showHeterodyneReferenceLine = requireNotNull(prefs[keyShowHeterodyneReferenceLine])
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
        if (prefs[keyInternalMicId] != null)
            internalMicId = requireNotNull(prefs[keyInternalMicId])
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
        if (prefs[keyAudioPitchRatio] != null)
            audioPitchRatio =
                AudioPitchRatioOptions.coerce(requireNotNull(prefs[keyAudioPitchRatio]))
        if (prefs[keyAudioTimeExpansionFactor] != null)
            audioTimeExpansionFactor = AudioTimeExpansionFactorOptions.coerce(
                requireNotNull(prefs[keyAudioTimeExpansionFactor])
            )
        if (prefs[keyAudioPitchHpfEnabled] != null)
            audioPitchHpfEnabled = requireNotNull(prefs[keyAudioPitchHpfEnabled])
        if (prefs[keyAudioRef1kHz] != null)
            heterodyneRef1kHz = requireNotNull(prefs[keyAudioRef1kHz])
        if (prefs[keyAudioRef2kHz] != null)
            heterodyneRef2kHz = requireNotNull(prefs[keyAudioRef2kHz])
        // Enforce manual-reference LO floor after loading refs.
        heterodyneRef1kHz = coerceHeterodyneRefkHz(heterodyneRef1kHz)
        heterodyneRef2kHz = coerceHeterodyneRefkHz(heterodyneRef2kHz)
        if (prefs[keyAudioBoostFactor] != null)
            audioBoostFactor = requireNotNull(prefs[keyAudioBoostFactor])
        if (prefs[keyAudioAGCEnabled] != null)
            audioAGCEnabled = requireNotNull(prefs[keyAudioAGCEnabled])
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
        if (prefs[keySuppressAudioFeedbackWarning] != null)
            suppressAudioFeedbackWarning = requireNotNull(prefs[keySuppressAudioFeedbackWarning])
        if (prefs[keyAutoHeterodyneMode] != null)
            autoHeterodyneMode =
                AutoHeterodyneModeOptions.coerce(requireNotNull(prefs[keyAutoHeterodyneMode]))
        if (prefs[keyAutoHeterodyneLoMinKhz] != null)
            autoHeterodyneLoMinKhz = requireNotNull(prefs[keyAutoHeterodyneLoMinKhz])
        if (prefs[keyAutoHeterodyneLoMaxKhz] != null)
            autoHeterodyneLoMaxKhz = requireNotNull(prefs[keyAutoHeterodyneLoMaxKhz])
        val (loMinKhz, loMaxKhz) = normalizedAutoHeterodyneLoRange()
        autoHeterodyneLoMinKhz = loMinKhz
        autoHeterodyneLoMaxKhz = loMaxKhz
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
