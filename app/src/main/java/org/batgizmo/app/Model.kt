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

import android.app.Application
import android.content.Context
import android.location.Location
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.batgizmo.app.pipeline.AbstractPipeline
import org.batgizmo.app.pipeline.ColourMapStep
import org.batgizmo.app.pipeline.FileViewerPipeline
import org.batgizmo.app.pipeline.LiveUSBPipeline
import org.batgizmo.app.pipeline.UsbService
import org.batgizmo.app.ui.GraphBase
import org.batgizmo.app.ui.SpectrogramUI
import org.batgizmo.app.ui.TopLevelUI
import org.batgizmo.app.ui.TopLevelUI.AppMode
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

data class OpenWavFileResult(
    val wfi: WavFileReader.WavFileInfo? = null,
    val errorMessage: String? = null,
    val pagingData: AbstractPipeline.PagingData? = null,
    val pipelineParametersSnapshot: PipelineParameters? = null
)

/**
 * This object provides a way for native layer code to notify the kotlin
 * layer when a live data buffer is ready for processing.
 *
 * Global scope object for handling live data callbacks should be immune to
 * being destroyed during app reconfiguration etc.
 *
 * !!! Don't rename this class or method, they are referenced from native code.
 */
object LiveDataBridge {

    sealed class BufferDescriptor {
        abstract val samples: Int

        /** Samples in a native URB buffer (USB live path). */
        data class Native(val nativeAddress: Long, override val samples: Int) : BufferDescriptor()

        /** Samples in a Kotlin heap buffer (reserved for non-USB live sources). */
        data class Heap(val data: ShortArray, val offset: Int, override val samples: Int) :
            BufferDescriptor()
    }

    // Provide finite capacity for buffering and decoupling.
    // Should probably match the URBS_TO_JUGGLE in the native layer.
    val renderingChannel = Channel<BufferDescriptor>(capacity = 10)
    val fileWriterChannel = Channel<BufferDescriptor>(capacity = 100)

    private fun trySendToChannels(descriptor: BufferDescriptor) {
        val rc1 = renderingChannel.trySend(descriptor)
        if (!rc1.isSuccess) {
            Timber.e("renderingChannel is full: data buffer discarded")
        }
        val rc2 = fileWriterChannel.trySend(descriptor)
        if (!rc2.isSuccess) {
            Timber.e("fileWriterChannel is full: data buffer discarded")
        }
    }

    /**
     * This method is called from the native layer in thread it uses for data
     * acquisition, which is originally created and therefore owned by kotlin.
     *
     * Minimize processing in this method to avoid blocking the native data streaming
     * code.
     */
    @JvmStatic
    fun onDataBufferReady(nativeAddress: Long, samples: Int) {
        trySendToChannels(BufferDescriptor.Native(nativeAddress, samples))
    }
}

class UIModelFactory(
    private val application: Application,
    private val settingsDataStore: DataStore<Preferences>
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UIModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return UIModel(application, settingsDataStore) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * Public functions on this class are called from the UI thread. However,
 * they may launch worker thread tasks within the same instance. Therefore,
 * mutex.withLock is used for thread safety - so the worker
 * thread tasks don't contend with the UI thread code.
 *
 * Beware: the pipeline has its own locking, so avoid the pipeline calling
 * back to this class, to avoid the risk of deadlock.
 */
@OptIn(FlowPreview::class)
class UIModel(application: Application,
              private val settingsDataStore: DataStore<Preferences>)
    : AndroidViewModel(application) {

    companion object {
        init {
            System.loadLibrary("batgizmo-native")
        }

        @OptIn(ExperimentalUnsignedTypes::class)
        private external fun nativeInitialize(
            colourMap: ShortArray,
            mapEntries: Int,
            amplitudeGraphColour: Short
        ): Int

        private const val minSrcDeltaLogical: Float = 0.001f

        /**
         * Make sure the supplied inner range is at least minSrcDeltaLogical, and doesn't
         * fall outside the outer range as far as we can.
         */
        @JvmStatic
        private fun enforceNonZeroRange(
            innerRange: FloatRange,
            outerRange: FloatRange = FloatRange(0f, 1f)
        ): FloatRange {
            var (newMin, newMax) = innerRange
            if (innerRange.endInclusive - innerRange.start < minSrcDeltaLogical) {
                val newMean = (newMin + newMax) / 2
                newMax = newMean + minSrcDeltaLogical / 2f
                newMin = newMean - minSrcDeltaLogical / 2f
                if (newMax > outerRange.endInclusive) {
                    val delta = newMax - outerRange.endInclusive
                    newMax -= delta
                    newMin -= delta
                } else if (newMin < outerRange.start) {
                    val delta = outerRange.start - newMin
                    newMax += delta
                    newMin += delta
                }
            }
            return FloatRange(newMin, newMax)
        }

        /**
         * Make sure the logical offset provided doesn't move the supplied range
         * outside limits of validity. Return the offset, adjusted if required.
         */
        @JvmStatic
        private fun constrainOffsetForRange(logicalDelta: Float, range: FloatRange): Float {
            var constrainedLogicalDelta = logicalDelta
            if (logicalDelta > 0f) {
                if (range.endInclusive + logicalDelta > 1f)
                    constrainedLogicalDelta = 1f - range.endInclusive
            } else {
                if (range.start + logicalDelta < 0f)
                    constrainedLogicalDelta = 0f - range.start
            }
            return constrainedLogicalDelta
        }

        /**
         * Scale the logical value into a new range: typically used to scale a screen
         * logical value to the source visible region.
         */
        @JvmStatic
        protected fun logicalScaleToRange(logical: Float, range: FloatRange): Float {
            return logical * (range.endInclusive - range.start) + range.start
        }

    }

    /**
     * This method is called by the system when the last activity calls finish().
     */
    override fun onCleared() {
        Timber.d("onCleared() called")
        super.onCleared()

        /*
            We will explicitly stop recording so the wav file can be cleanly closed.
            Everything else I leave up the Android system to clean up as the process
            ends. Yes, lazy of me, but fine actually.
         */

        // Dedicated cleanup scope for critical cleanup tasks:
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanupScope.launch {

            // Signal to the main streaming loop to exit. Otherwise, the kotlin thread
            // never finishes and it continues zombie like after the app has appeared
            // to close. Does no harm if we are not connected.
            usbService.disconnect()
        }
    }

    // The single source of truth for the BnC slider value is normalize to the range 0f..1f.
    private val mutableBnCRangeFlow = MutableStateFlow(FloatRange(0f, 1f)) // Initial value
    val bnCRangeFlow: StateFlow<FloatRange> = mutableBnCRangeFlow.asStateFlow()

    private val mutableAutoBnCRequiredFlow = MutableStateFlow(false) // Initial value
    val autoBnCRequiredFlow: StateFlow<Boolean> = mutableAutoBnCRequiredFlow.asStateFlow()
    fun setAutoBnCRequired(required: Boolean) {
        mutableAutoBnCRequiredFlow.value = required
    }

    // The single source of truth for the audio boost slider value:
    private val mutableAudioBoostFlow = MutableStateFlow(8f) // Initial value used if no setting.
    val audioBoostFlow: StateFlow<Float> = mutableAudioBoostFlow.asStateFlow()
    fun setAudioBoost(boost: Float) {
        mutableAudioBoostFlow.value = boost
    }

    // The single source of truth for the microphone gain, if the microphone allows it
    // to be set via USB:
    data class UsbVolumeParameters(val min: Float, val max: Float, val res: Float)
    private val mutableMicrophoneVolumeParametersFlow = MutableStateFlow<UsbVolumeParameters?>(null)
    val microphoneVolumeParametersFlow: StateFlow<UsbVolumeParameters?> = mutableMicrophoneVolumeParametersFlow.asStateFlow()

    private val mutableMicrophoneGainFlow = MutableStateFlow<Float?>(null) // Initial value
    val microphoneGainFlow: StateFlow<Float?> = mutableMicrophoneGainFlow.asStateFlow()

    suspend fun setMicrophoneGain(gainDB: Float) {
        // Force the request gain to be an allowed value based on microphoneVolumeParametersFlow:
        microphoneVolumeParametersFlow.value?.let { parms ->
            var allowedGainDB = gainDB.coerceIn(parms.min, parms.max)
            val steps = ((allowedGainDB - parms.min) / parms.res).roundToInt()
            allowedGainDB = parms.min + steps * parms.res
            Timber.d("Setting microphone volume: requested = $gainDB, allowed = $allowedGainDB")
            if (usbService.setVolume(allowedGainDB))
                mutableMicrophoneGainFlow.value = allowedGainDB
        }
    }

    /*
        The single sources of truth for the logical and axis ranges used by the renderer.
        Min and max increase down and across the screen.

        VisibleRange is in logical coordinates: 1f is full width/height.
        Axis range is in real world units: s, kHz, dB etc.
     */

    // Time:
    private val defaultTimeVisibleRange = FloatRange(0f, 1f)
    private var mutableTimeVisibleRangeFlow = MutableStateFlow(defaultTimeVisibleRange)
    val timeVisibleRangeFlow: StateFlow<FloatRange> = mutableTimeVisibleRangeFlow.asStateFlow()

    private val defaultTimeAxisRange = FloatRange(0f, 5f)
    private var mutableTimeAxisRangeFlow = MutableStateFlow(defaultTimeAxisRange)
    val timeAxisRangeFlow: StateFlow<FloatRange> = mutableTimeAxisRangeFlow.asStateFlow()

    // Frequency:
    private val defaultFrequencyVisibleRange = FloatRange(0f, 1f)
    private var mutableFrequencyVisibleRangeFlow = MutableStateFlow(defaultFrequencyVisibleRange)
    val frequencyVisibleRangeFlow: StateFlow<FloatRange> =
        mutableFrequencyVisibleRangeFlow.asStateFlow()

    private val defaultFrequencyAxisRange = FloatRange(0f, 192000f)
    private var mutableFrequencyAxisRangeFlow = MutableStateFlow(defaultFrequencyAxisRange)
    val frequencyAxisRangeFlow: StateFlow<FloatRange> = mutableFrequencyAxisRangeFlow.asStateFlow()

    // Amplitude:
    private val defaultAmplitudeVisibleRange = FloatRange(0f, 1f)
    private var mutableAmplitudeVisibleRangeFlow = MutableStateFlow(defaultAmplitudeVisibleRange)
    val amplitudeVisibleRangeFlow: StateFlow<FloatRange> =
        mutableAmplitudeVisibleRangeFlow.asStateFlow()

    private val defaultAmplitudeAxisRange = FloatRange(-32767f, 32767f)
    private var mutableAmplitudeAxisRangeFlow = MutableStateFlow(defaultAmplitudeAxisRange)
    val amplitudeAxisRangeFlow: StateFlow<FloatRange> = mutableAmplitudeAxisRangeFlow.asStateFlow()

    // Details text to display over the graph:
    private val mutableDetailsTextFlow = MutableStateFlow<String?>(null)
    val detailsTextFlow: StateFlow<String?> = mutableDetailsTextFlow.asStateFlow()

    private val audioProgressChannel = Channel<Int>(Channel.CONFLATED)
    val audioProgressFlow = audioProgressChannel.receiveAsFlow()

    var colourMapSize: Int? = null

    // Used to notify back to the UI that an attempt at opening a file
    // is either successful or not.
    private val fileOpenedChannel = Channel<OpenWavFileResult>(Channel.BUFFERED)
    val fileOpenedFlow = fileOpenedChannel.receiveAsFlow()

    private var wavFileReader: WavFileReader? = null        // Lifecycle owned by this class.

    // private var wavFileInfo: WavFileReader.WavFileInfo? = null
    private var wavFileInfo = AtomicReference<WavFileReader.WavFileInfo>()  // Initializes to null
    private var pipeline: AbstractPipeline? = null

    var fileWriter: FileWriter? = null
    // Are we currently writing data to file?
    private val mutableCurrentlyWritingFlow = MutableStateFlow(false) // Initial value
    val currentlyWritingFlow: StateFlow<Boolean> = mutableCurrentlyWritingFlow.asStateFlow()

    // Various UI states that need to be accessible outside compose context:
    val spectrogramUIState = SpectrogramUI.UIState()
    val spectrogramButtonState = SpectrogramUI.ButtonState()
    val topLevelUIState = TopLevelUI.UIState()

    private var spectrogramSizeDp: DpSize? = null
    private var amplitudeSizeDp: DpSize? = null
    private var sizeGeneration =
        -1          // Track the UI Compose generation that sizes relate to.

    // Single global instance of the bitmap holder we will use for rendering the
    // spectrogram and amplitude:
    val spectrogramBitmapHolder = BitmapHolder()
    val amplitudeBitmapHolder = BitmapHolder()

    // Fail safe values that shouldn't get used:
    private val defaultFftParameters =
        AbstractPipeline.FftParameters(windowSamples = 512, windowOverlap = 256)

    // Track the most recently calculate auto parameters so we know when they change:
    private var currentFftParameters: AbstractPipeline.FftParameters = defaultFftParameters

    // Signal to the UI that an attempt to open a live connection has completed:
    private val liveConnectChannel = Channel<UsbService.UsbConnectResult>(Channel.BUFFERED)
    val liveConnectFlow = liveConnectChannel.receiveAsFlow()

    // Signal to the UI that an attempt to start audio has completed:
    private val liveAudioStartChannel = Channel<UsbService.AudioStartResult>(Channel.BUFFERED)
    val liveAudioStartFlow = liveAudioStartChannel.receiveAsFlow()

    // Signal to this model that an attempt to connect to the USB device has completed:
    private val usbConnectChannel = Channel<UsbService.UsbConnectResult>(Channel.BUFFERED)
    private val usbConnectFlow = usbConnectChannel.receiveAsFlow()
    private val usbErrorChannel = Channel<UsbService.UsbErrorResult>(Channel.BUFFERED)
    val usbErrorFlow = usbErrorChannel.receiveAsFlow()
    private val usbService =
        UsbService(getApplication(), this, usbConnectChannel, usbErrorChannel, viewModelScope)

    data class AppModeRequest(
        val mode: AppMode,
        val uriData: DocumentHelper.UriData?,              // Optional file to view in VIEWER mode.
        val streaming: Boolean      // True if we should enter LIVE mode with streaming active
    )

    private val resetAppModeChannel = Channel<AppModeRequest>(Channel.BUFFERED)
    val resetAppModeFlow = resetAppModeChannel.receiveAsFlow()

    private val settingsReadyChannel = Channel<Unit>(Channel.BUFFERED)
    val settingsReadyFlow = settingsReadyChannel.receiveAsFlow()
    var settings = Settings()
        private set  // public getter, private setter

    // The trigger monitor is displayed in the Settings UI for convenience when adjust the trigger
    // threshold. Multiple triggers are combined into one:
    val triggerMonitorChannel = Channel<Unit>(Channel.CONFLATED)

    // Protect all the instance data:
    val mutex = Mutex()

    // Used to make sure we wait until any previous pipeline has finished its
    // async shutdown before we create a new one. This avoids a race with a new
    // pipeline potentially treading on the heels of the previous one@
    var pipelineCloseJob: Job? = null


    // Location things:
    private val locationMutableFlow = MutableStateFlow<Location?>(null)
    val locationFlow : StateFlow<Location?> = locationMutableFlow
    val locationTracker = LocationTracker(getApplication()) { it ->
        Timber.d("Location update received: ${it.latitude} ${it.longitude}")
        locationMutableFlow.value = it
    }

    val documentHelper = DocumentHelper()

    init {
        Timber.d("Creating instance of UIModel")

        resetRanges()
        spectrogramButtonState.reset()
        spectrogramUIState.reset()

        // Initialize the UI to a known mode.
        resetUIMode(requestedMode = AppMode.LIVE)

        var mapRows = readColourMap("kindlmann-256.csv")
        require(mapRows.size >= 64) { "the colour map contains too few colours" }

        // It's prudent to sort the rows into ascending order:
        mapRows = mapRows.sortedBy { it[0] as Float }

        /**
         * Convert the colour map to a simpler format for more
         * efficient use in native code.
         */
        val colourMap = ShortArray(mapRows.size)     // JNI doesn't support UShort.
        for ((i, entry) in mapRows.withIndex()) {
            val r: Int = entry[1] as Int
            val g: Int = entry[2] as Int
            val b: Int = entry[3] as Int
            val rgb565 = rgbToRGB565(r, g, b)
            colourMap[i] = rgb565
        }

        colourMapSize = mapRows.size

        // Select a colour to use for the amplitude graph. It looks nice
        // if this is one of the colours used by the spectrogram:

        Timber.d(
            "Setting colourMapSize to $colourMapSize on model instance ${
                System.identityHashCode(this)
            }."
        )

        val size = colourMapSize
        // val amplitudeGraphColour: Short =
        //    (if (size != null) colourMap[(size * 0.5f).toInt()] else 0xFFFF) as Short

        val amplitudeGraphColour: Short = rgbToRGB565(0, 0xFF, 0xFF)

        // The JNI layer doesn't seem to support UShortArray, so we have to make
        // a copy of the colour which is a ShortArray:
        val rc = nativeInitialize(colourMap, mapRows.size, amplitudeGraphColour)
        check(rc == 0) { "native layer initialization must succeed" }

        /**
         * Get the preference values from storage asynchronously. When the values arrive, they
         * will overwrite the default settings values. If the value is not present in storage,
         * the default setting will be left unchanged. When the values arrive, we signal
         * to the UI that they are ready.
         */
        val flow: Flow<Preferences> = settingsDataStore.data
        // Kick off reading the settings in a coroutine, which will therefore happen in a bit:
        viewModelScope.launch(Dispatchers.IO) {
            flow.collect { prefs ->
                settings.copyFromPreferences(prefs)
                // Signal to the UI that the settings values are ready:
                settingsReadyChannel.send(Unit)
            }
        }
    }

    /**
     * What it says.
     */
    suspend fun updateStoredSettings(updatedSettings: Settings) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Timber.d("updateStoredSettings called: audioBoostMultiplier = ${updatedSettings.audioBoostFactor}")
                settings = updatedSettings
                // Invoke edit on the datastore to update and persist the changes:
                settingsDataStore.edit { prefs -> settings.copyToPreferences(prefs) }
            }
        }
    }

    /**
     * Convert an 8 bit RGB colour to RGB565.
     */
    private fun rgbToRGB565(red: Int, green: Int, blue: Int): Short {
        val r5 = (red shr 3) and 0x1F   // Convert 8-bit red to 5-bit
        val g6 = (green shr 2) and 0x3F // Convert 8-bit green to 6-bit
        val b5 = (blue shr 3) and 0x1F  // Convert 8-bit blue to 5-bit

        val result = ((r5 shl 11) or (g6 shl 5) or b5).toShort() // Pack into 16-bit value
        return result
    }

    val oomMessage = "Your device has insufficient free memory for rendering spectrograms of this size.\n\n"+
            "Try reducing the maximum viewable time span in settings."

    /**
     * Call from the UI thread.
     *
     * Open a wav file and publish the resulting metadata to the UI layer.
     * It is intended to be called in the UI thread. Heavy processing is offloaded
     * to a thread.
     *
     * There is no immediate return code. The outcome is signalled by updating
     * a StateFlow.
     *
     * This method leaves the file open if it is successful, so that data can be read from it.
     * It can be closes by calling reset().
     */
    fun openFile(uri: Uri, filename: String) {
        val context: Context = getApplication()

        val model = this
        var result: OpenWavFileResult? = null
        viewModelScope.launch(Dispatchers.Default + CoroutineName("openFile coroutine")) {

            // Allow any previous pipeline async cleanup to complete before we create a new one,
            // to avoid overlap and races:
            pipelineCloseJob?.join()
            pipelineCloseJob = null

            mutex.withLock {
                try {
                    Timber.d("openFile called for $filename")

                    // Take a snapshot of the parameters used to build this pipeline.
                    val pps = settings.pipelineParameters.copy()

                    // Synchronously clean up in case the pipeline was already open:
                    internalClosePipeline()

                    val wfr = WavFileReader(context)
                    wavFileReader = wfr
                    val wfi: WavFileReader.WavFileInfo = wfr.open(uri, filename)

                    // Reset these directly before the first pipeline rendering:
                    mutableTimeVisibleRangeFlow.value = defaultTimeVisibleRange
                    mutableFrequencyVisibleRangeFlow.value = defaultFrequencyVisibleRange
                    mutableAmplitudeVisibleRangeFlow.value = defaultAmplitudeVisibleRange

                    // Pass ownership of the WavFileReader to the pipeline, which now owns
                    // it and must do cleanup in due course:
                    val p = FileViewerPipeline(pps,
                        viewModelScope, wfr,
                        context, model,
                        spectrogramBitmapHolder, amplitudeBitmapHolder,
                        mutableTimeAxisRangeFlow, mutableFrequencyAxisRangeFlow,
                        mutableDetailsTextFlow, wfi.sampleRate, wfi.sampleCount
                    )
                    // Assume square if we don't know yet:
                    val fftParameters = p.getDefaultFftParameters(
                        wfi.sampleRate,
                        spectrogramSizeDp ?: DpSize(100.dp, 100.dp)
                    )

                    // Do a full render from file:
                    p.fullExecute(
                        fftParameters = fftParameters,
                        amplitudeSizeDp = amplitudeSizeDp
                    )

                    pipeline = p
                    wavFileInfo.set(wfi)
                    // This has a side affect of updating the axis ranges in the UI:
                    internalSetSpectrogramVisibleRange(FloatRange(0f, 1f), FloatRange(0f, 1f))

                    internalDoColourMappingAndRender(
                        settings.autoBnCEnabledViewer,
                        settings.autoBaselineEnabled)

                    // Signal to the UI that we have successfully opened the file and initialized
                    // the pipeline:
                    val pagingData = pipeline?.getPagingData()
                    result = OpenWavFileResult(wfi, pagingData = pagingData)

                } catch (e: Exception) {
                    // Clean up if anything bad happens:
                    internalClosePipeline()

                    // Log details:
                    val stackTraceElement = e.stackTrace.firstOrNull()
                    val message = if (stackTraceElement != null) {
                        "${e.localizedMessage ?: "Unknown error"}\n\n" +
                                "Module: ${stackTraceElement.fileName}\n" +
                                "Line: ${stackTraceElement.lineNumber}"
                    } else {
                        "${e.localizedMessage ?: "Unknown error"}"
                    }

                    Timber.w("Exception when opening wav file. $message")
                    Timber.i("Exception when opening wav file: ${e.stackTrace.asList()}")

                    // Return the message to the UI for display to the user:
                    result = OpenWavFileResult(null, message)
                }
                catch (e: OutOfMemoryError) {
                    internalClosePipeline()

                    // Note: this is an error, not an exception, so needs its own catch.
                    diagnosticLogger.log { "Out of memory in openFile()" }

                    // Return the message to the UI for display to the user:
                    result = OpenWavFileResult(null, oomMessage)
                }
            }

            // Do this suspending operation outside the critical section to avoid
            // blocking with the lock:
            Timber.i("Emitting result from open: $result")
            result?.let { fileOpenedChannel.send(it) }
        }
    }

    fun doColourMappingAndRender(
        autoBnCRequired: Boolean,
        autoBaselineRequired: Boolean
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            mutex.withLock {
                internalDoColourMappingAndRender(autoBnCRequired, autoBaselineRequired)
            }
        }
    }

    private suspend fun internalDoColourMappingAndRender(
        autoBnCRequired: Boolean,
        autoBaselineRequired: Boolean
    ) {
        Timber.d("internalDoColourMappingAndRender called: autoBnCRequired = $autoBnCRequired, autoBaselineRequired = $autoBaselineRequired")
        pipeline?.let { p ->
            var rerenderRequired = false

            /*
             * Do noise baseline calculation *before* auto BnC, as it will affect
             * the auto BnC range calculated.
             *
             * We could cache the result of this calculation. However, we would have to recalculate
             * whenever nfft changes, which is quite often, and whenever the visible region of a
             * data file is changed, which is also quite often. So the cache would only be somewhat
             * effective. Instead, we apply fairly aggressive decimation when calculating the noise
             * baseline to keep it quick.
             *
             * Similarly - it wouldn't work well to disable auto baseline but retain the existing
             * baseline, as it is calculated for a specific nfft.
             */
            if (autoBaselineRequired) {
                val t = measureTimeMillis {
                    p.calculateNoiseBaseline()
                }
                // Timber.d("p.calculateNoiseBaseline() took $t ms")
                rerenderRequired = true
            }
            else {
                if (p.clearNoiseBaseline())
                    rerenderRequired = true
            }

            if (autoBnCRequired) {
                val newBnCRange =
                    p.calculateAutoBnC(
                        timeVisibleRangeFlow.value,
                        frequencyVisibleRangeFlow.value
                    )

                if (newBnCRange != null) {
                    val logicalBnCRange = ColourMapStep.bnCRangeDbToLogical(newBnCRange)

                    Timber.d("doAutoBnC called: newBnCRange = $newBnCRange, logicalBnCRange = $logicalBnCRange")
                    // Update our single source of truth, notifying the UI as a side effect:
                    logicalBnCRange.also { mutableBnCRangeFlow.value = it }
                }

                rerenderRequired = true
            }

            if (rerenderRequired) {
                // Have the pipeline recalculate from the colour map step onwards:
                p.applyBnC(mutableBnCRangeFlow.value)
            }

            // Update the bitmap to the display:
            triggerBitblt()
        }
    }

    fun openLive(onFileWriterError: (String) -> Unit) {
        val model = this
        // Heavy processing in CPU worker thread:
        viewModelScope.launch(Dispatchers.Default) {
            val context: Context = getApplication()
            var usbConnectResult: UsbService.UsbConnectResult? = null

            // Allow any previous pipeline async cleanup to complete before we create a new one,
            // to avoid overlap and races:
            pipelineCloseJob?.join()
            pipelineCloseJob = null

            // Reset after any previous connect:
            mutableMicrophoneVolumeParametersFlow.value = null
            mutableMicrophoneGainFlow.value = null

            mutex.withLock {
                try {
                    // Take a snapshot of the parameters used to build this pipeline.
                    val pps = settings.pipelineParameters.copy()

                    // Synchronously clean up in case the pipeline was already open:
                    internalClosePipeline()

                    // Reset these directly before the first pipeline rendering:
                    mutableTimeVisibleRangeFlow.value = defaultTimeVisibleRange
                    mutableFrequencyVisibleRangeFlow.value = defaultFrequencyVisibleRange
                    mutableAmplitudeVisibleRangeFlow.value = defaultAmplitudeVisibleRange
                    // mutableTimeAxisRangeFlow.value = defaultTimeAxisRange

                    /*
                        Connect to the USB data stream, very asynchronously because user
                        approval may be required. This is two parts:
                        * 1: The connect call itself suspends before returning. User interaction
                        *       may be requested to approve access to the device.
                        * 2: Finally an event is posted into a channel for us to pick up.
                     */
                    // Make sure there are no pending responses:
                    drainChannel(usbConnectChannel)
                    drainChannel(usbErrorChannel)

                    // Part 1:
                    // Suspend until connect is complete, and handle any exceptions the obvious way:
                    coroutineScope {
                        usbService.connect( {
                            min: Float, max: Float, res: Float, cur: Float ->

                            // Lambda executed if the microphone supports volume setting:
                            mutableMicrophoneVolumeParametersFlow.value = UsbVolumeParameters(min, max, res)
                            mutableMicrophoneGainFlow.value = cur
                        })
                    }

                    // Part 2: suspend until the *first* response (collect would loop for ever):
                    val result = usbConnectFlow.first()

                    usbConnectResult = result
                    if (result.connectedOK) {
                        require(result.sampleRate != null)

                        val p = LiveUSBPipeline(
                            pps,
                            viewModelScope,
                            context, model,
                            spectrogramBitmapHolder, amplitudeBitmapHolder,
                            mutableTimeAxisRangeFlow, mutableFrequencyAxisRangeFlow,
                            mutableDetailsTextFlow, result.sampleRate,
                            result.sampleRate * pps.dataPageTimeSpanS
                        ) {
                            // This lambda is called if a trigger was detected in the live data.

                            // Trigger a file write:
                            fileWriter?.trigger()
                            // Trigger the monitor lamp in the Settings UI:
                            triggerMonitorChannel.trySend(Unit)
                        }

                        // Initial values for the FFT parameters (window, overlap):
                        val defaultSize = DpSize(100.dp, 100.dp) // Square by default.
                        Timber.d("spectrogramSizeDp = $spectrogramSizeDp in openLive()")
                        val fftParameters = p.getDefaultFftParameters(
                            result.sampleRate,
                            spectrogramSizeDp ?: defaultSize
                        )

                        // The following creates the pipeline internals if they don't already exist:
                        p.fullExecute(
                            fftParameters = fftParameters,
                            amplitudeSizeDp = amplitudeSizeDp,
                            doRender = false
                        )

                        pipeline = p

                        // Preset the time range in live mode to a value according to settings.
                        // This has a side affect of updating the axis ranges in the UI:
                        val (timeAxisRange, dummy) = getTimeAxisRangeForLive()
                        mutableTimeAxisRangeFlow.value = timeAxisRange
                        val logicalTimeRange = FloatRange(0f,
                            timeAxisRangeFlow.value.endInclusive / pps.dataPageTimeSpanS)
                        internalSetSpectrogramVisibleRange(logicalTimeRange, FloatRange(0f, 1f))

                        // Start up the file writer ready to write data to file:
                        fileWriter = FileWriter(viewModelScope, getApplication(),
                            model, locationFlow, usbConnectResult, result.sampleRate,
                            { isWriting: Boolean ->
                            mutableCurrentlyWritingFlow.value = isWriting },
                            onFileWriterError
                        )
                        fileWriter?.run()
                    }
                } catch (e: Exception) {
                    internalClosePipeline()

                    // Log details:
                    val stackTraceElement = e.stackTrace.firstOrNull()
                    val message = if (stackTraceElement != null) {
                        "${e.localizedMessage ?: "Unknown error"}\n\n" +
                                "Module: ${stackTraceElement.fileName}\n" +
                                "Line: ${stackTraceElement.lineNumber}"
                    } else {
                        e.localizedMessage ?: "Unknown error"
                    }

                    Timber.w("Exception during live connect: $message")
                    Timber.d("Exception during live connect: ${e.stackTrace.asList()}")

                    // Return the message to the UI for display to the user:
                    usbConnectResult = UsbService.UsbConnectResult(false, message)
                }
                catch (e: OutOfMemoryError) {
                    internalClosePipeline()

                    // Note: this is an error, not an exception, so needs its own catch.
                    diagnosticLogger.log { "Out of memory in openLive()" }

                    // Return the message to the UI for display to the user:
                    usbConnectResult = UsbService.UsbConnectResult(false, oomMessage)
                }
            }

            // Do this suspending operation outside the critical section to avoid
            // blocking with the lock:
            require(usbConnectResult != null)
            usbConnectResult.let {
                Timber.d("Sending result of live connection: $it")
                liveConnectChannel.send(it)
            }
        }
    }

    /**
     * On entering live mode or resuming after pause, we may need to reset the
     * X axis time span to a fixed range.
     *
     * The return value is a Pair of the X axis range to use, and a flag indicating if a rerender is required.
     */
    fun getTimeAxisRangeForLive(): Pair<FloatRange, Boolean> {
        require(pipeline != null) { "Logical error: pipeline must exist at this point" }
        pipeline?.let { p ->
            val pps: PipelineParameters = p.pipelineParametersSnapshot
            val initialXSpan = timeAxisRangeFlow.value.difference()
            if (settings.defaultLiveTimeSpanS == Settings.DefaultLiveTimeSpanOptions.DEFAULTLIVETIMESPAN_NONE.value) {
                // Create a range from the current live one, with a sanity limit, clamped to the maximum
                // time span supported:
                val minSaneTimeRangeS = 0.5F
                var xSpan = maxOf(timeAxisRangeFlow.value.difference(), minSaneTimeRangeS)
                xSpan = minOf(xSpan, pps.dataPageTimeSpanS.toFloat())
                return Pair(FloatRange(0F, xSpan), xSpan != initialXSpan)
            } else {
                // Set the time axis range for live mode according to the settings:
                val xSpan = minOf(settings.defaultLiveTimeSpanS, pps.dataPageTimeSpanS).toFloat()
                return Pair(FloatRange(0F, xSpan), xSpan != initialXSpan)
            }
        }

        // Shouldn't get here:
        return Pair(FloatRange(0f, settings.defaultLiveTimeSpanS.toFloat()), true)
    }

    fun startLiveAudio() {
        viewModelScope.launch(Dispatchers.Default) {

            val runtime = Runtime.getRuntime()
            Timber.i("startViewerAudio start: runtime.{maxMemory, totalMemory, freeMemory) = " +
                    "${runtime.maxMemory() / 1024}, ${runtime.totalMemory() / 1024}, ${runtime.freeMemory() / 1024} KB")

            var audioStartResult: UsbService.AudioStartResult? = null
            Timber.d("startAudio called")

            mutex.withLock {

                usbService.startAudio(
                    settings.heterodyneRef1kHz,
                    if (settings.heterodyneDual) settings.heterodyneRef2kHz else null,
                    settings.audioBoostFactor
                )

                audioStartResult = UsbService.AudioStartResult(startedOK = true)
            }

            audioStartResult?.let {
                Timber.d("Sending result of start audios: $it")
                liveAudioStartChannel.send(it)
            }

            Timber.i("startAudio end: runtime.{maxMemory, totalMemory, freeMemory) = " +
                    "${runtime.maxMemory() / 1024}, ${runtime.totalMemory() / 1024}, ${runtime.freeMemory() / 1024} KB")
        }
    }

    private fun onAudioProgress(position: Int) {
        audioProgressChannel.trySend(position)
    }

    fun startViewerAudio(sampleRateHz: Int) {
        viewModelScope.launch(Dispatchers.Default) {

            var audioStartResult: UsbService.AudioStartResult? = null
            Timber.d("startViewerAudio called")

            mutex.withLock {

                pipeline?.getVisibleRawDataBuffer(timeAxisRangeFlow)?.let { visibleRawData ->

                    Timber.d("Visible raw data range: ${visibleRawData.second}")

                    usbService.startAudioFromBuffer(
                        settings.heterodyneRef1kHz,
                        if (settings.heterodyneDual) settings.heterodyneRef2kHz else null,
                        settings.audioBoostFactor,
                        visibleRawData, settings.loopedAudioPlayback,
                        sampleRateHz, ::onAudioProgress
                    )

                    audioStartResult = UsbService.AudioStartResult(startedOK = true)
                }
            }

            audioStartResult?.let {
                Timber.d("Sending result of start audio: $it")
                liveAudioStartChannel.send(it)
            }
        }
    }

    fun stopAudio() {
        viewModelScope.launch(Dispatchers.Default) {
            mutex.withLock {
                // Timber.d("Model: stopAudio called")
                usbService.stopAudio()
            }
        }
    }

    /**
     * What it says.
     */
    private fun <T> drainChannel(channel: Channel<T>) {
        while (true) {
            val result = channel.tryReceive()
            if (!result.isSuccess)
                break;
        }
    }

    /**
     *  Call from the UI thread.
     *
     *  The function is idempotent so can be called in any situation to close
     *  down the current pipeline cleanly.
     *
     *  Note that the code that actually does the closing is async as it needs to wait for things to complete,
     *  so it continues after this function returns. Maybe this method should be called
     *  something like "signalClosePipeline".
     */
    fun closePipeline() {
        Timber.i("closePipeline called")
        pipelineCloseJob =
            viewModelScope.launch(Dispatchers.Default + CoroutineName("closePipeline coroutine")) {
                mutex.withLock {
                    internalClosePipeline()
                }
            }
    }

    fun pauseLiveStream() {
        viewModelScope.launch(Dispatchers.Default + CoroutineName("openLive coroutine")) {
            mutex.withLock {
                usbService.pause()
            }
        }
    }

    fun resumeLiveStream() {
        viewModelScope.launch(Dispatchers.Default + CoroutineName("openLive coroutine")) {
            mutex.withLock {
                Timber.w("Logical error: pipeline must exist at this point (2)")
                pipeline?.let { p ->
                    p.resetState()

                    val tar = timeAxisRangeFlow.value
                    val tar2 = tar
                    val pps = p.pipelineParametersSnapshot

                    /*
                        When resuming live acquisition, the visible region may have been panned and
                        scaled. We will preserve the Y max and min, and the X range, but move
                        the X range so it starts at 0, and apply a sane minimum time span.
                     */

                    // Get the live range to use based on settings:
                    val (timeAxisRange, rerender) = getTimeAxisRangeForLive()
                    // Calculate the corresponding logical range:
                    val visibleTimeRange = FloatRange(
                        0F,
                        timeAxisRange.endInclusive / pps.dataPageTimeSpanS
                    )

                    Timber.d(
                        "resumeLiveStream: adjusting time logical range from ${mutableTimeVisibleRangeFlow.value} to $visibleTimeRange"
                    )

                    // onVisibleRangeChange(settings, shouldAutoBnC, rawPageRangeState.value)

                    internalSetSpectrogramVisibleRange(
                        visibleTimeRange,
                        frequencyVisibleRangeFlow.value
                    )

                    // Do a full pipeline rebuild so that any new settings take effect:
                    reload(settings, null, false)

                    usbService.resume()
                }
            }
        }
    }

    fun setHeterodyne(kHz1: Int, kHz2: Int?) {
        viewModelScope.launch(Dispatchers.Default) {
            mutex.withLock {
                usbService.setHeterodyne(kHz1, kHz2)
            }
        }
    }

    suspend fun internalClosePipeline() {

        // Finish with the file writer:
        fileWriter?.shutdown()
        fileWriter = null

        // This blocks on the thread until native layer processing has cleanly terminated.
        usbService.disconnect()    // Harmless if it wasn't connected.

        pipeline?.shutdown()
        pipeline = null

        wavFileReader?.close()
        wavFileReader = null

        wavFileInfo.set(null)

        mutableDetailsTextFlow.value = null
        mutableMicrophoneVolumeParametersFlow.value = null
        mutableMicrophoneGainFlow.value = null

        resetRanges()
    }

    private fun resetRanges() {
        mutableTimeVisibleRangeFlow.value = defaultTimeVisibleRange
        mutableFrequencyVisibleRangeFlow.value = defaultFrequencyVisibleRange
        mutableAmplitudeVisibleRangeFlow.value = defaultAmplitudeVisibleRange

        mutableTimeAxisRangeFlow.value = defaultTimeAxisRange
        mutableFrequencyAxisRangeFlow.value = defaultFrequencyAxisRange
        mutableAmplitudeAxisRangeFlow.value = defaultAmplitudeAxisRange

        currentFftParameters = defaultFftParameters
    }

    private fun readColourMap(filename: String): List<Array<Any>> {
        val context: Context = getApplication()

        val result = mutableListOf<Array<Any>>()
        context.assets.open(filename).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val values = line.split(",").map { it.trim() }
                if (values.size == 4) {
                    val x = values[0].toFloat()
                    val r = values[1].toInt()
                    val g = values[2].toInt()
                    val b = values[3].toInt()
                    result.add(arrayOf(x, r, g, b))
                }
            }
        }
        return result
    }

    /**
     * Call from the UI thread.
     *
     * Call this when the BnC range has been changed.
     *
     * This method may be called from the UI thread - the heavy calculations
     * are passed off to another thread to be done asynchronously.
     */
    suspend fun applyBnC(range: FloatRange, redraw: Boolean = false) {
        mutex.withLock {
            // Update our single source of truth, notifying the UI as a side effect:
            mutableBnCRangeFlow.value = range

            if (redraw) {
                pipeline?.applyBnC(range)
                triggerBitblt()
            }
        }
    }

    /**
     * Apply a new audio boost value, or store the existing value to settings storage.
     */
    suspend fun applyAudioBoost(audioBoostFactor: Float?) {
        mutex.withLock {

            // Update our single source of truth, notifying the UI as a side effect:
            audioBoostFactor?.let {
                // Timber.d("Updated audio boost to $it")
                settings.audioBoostFactor = it
                mutableAudioBoostFlow.value = it

                usbService.setAudioBoost(it)
            }
        }

        if (audioBoostFactor == null) {
            // The null value signals that the user has finished sliding.
            // Timber.d("Writing settings to storage. audio boost = ${settings.audioBoostFactor}")
            updateStoredSettings(settings)
        }
    }

    /**
     * Call from the UI thread.
     *
     * Call this method to set the visible ranges of the transformed data. No re-rendering is done.
     *
     * Typically the range is set by:
     *  - UI operations such as zooming or dragging the spectrogram.
     *  - Loading a file.
     *
     */
    private suspend fun internalSetSpectrogramVisibleRange(
        xVisibleRange: FloatRange,
        yVisibleRange: FloatRange
    ) {
        mutableTimeVisibleRangeFlow.value = xVisibleRange
        mutableFrequencyVisibleRangeFlow.value = yVisibleRange
        // Intentionally don't set the Z range here.

        pipeline?.updateAxisRangesFromLogical(xVisibleRange, yVisibleRange)
    }

    /**
     * Call from the UI thread.
     *
     * The visible data range has changed, so reload to take that into account.
     */
    fun onVisibleRangeChange(settings: Settings, shouldAutoBnC: Boolean, rawPageRange: HORange?) {
        // Heavy calculations to CPU worker thread:
        viewModelScope.launch(Dispatchers.Default + CoroutineName("onRescale coroutine")) {
            mutex.withLock {
                reload(settings, rawPageRange, shouldAutoBnC)
            }
        }
    }

    /**
     * Call from the UI thread.
     *
     * This method is called when the settings have been updated. This is our
     * chance to apply the settings and render the UI as required.
     *
     * This method is called from the UI so heavy calculations are offloaded to
     * another thread.
     */
    fun onSettingsUpdate(newSettings: Settings, previousSettings: Settings?, rawPageRange: HORange?) {
        viewModelScope.launch(CoroutineName("onSettingsUpdate coroutine")) {
            mutex.withLock {
                Timber.d("onSettingsUpdate called")

                var resetVisibleRange = false
                pipeline?.let { p ->
                    val pps = p.pipelineParametersSnapshot
                    if (newSettings.pipelineParameters.dataPageTimeSpanS != pps.dataPageTimeSpanS)
                        resetVisibleRange = true
                }
                previousSettings?.let {
                    if (newSettings.pageOverlapPercent != it.pageOverlapPercent
                    ) {
                        resetVisibleRange = true
                    }
                }

                setAudioBoost(settings.audioBoostFactor)

                /*
                  For now, do a complete reload for any settings change. This could be smarter
                  but the reload is pretty fast so probably this is fine.
                 */
                reload(newSettings, rawPageRange, autoBnCRequiredFlow.value, resetVisibleRange)
            }
        }
    }

    /**
     * Called from the UI thread.
     *
     * This method is called from the UI to notify us of the size of the spectrogram
     * and amplitude widgets.
     *
     * We track the layoutGeneration so that we don't mix sizes obtained for example from
     * before and after a rotation.
     */
    suspend fun onUISizeChange(
        generation: Int, spectrogramSizeDp: DpSize?,
        amplitudeSizeDp: DpSize?, settings: Settings,
        rawPageRange: HORange?
    ) {
        mutex.withLock {
            Timber.d("onUISizeChange called: $spectrogramSizeDp; $amplitudeSizeDp")

            // If we are on to a new UI generation, reset the cached sizes to avoid using stale data
            // or mixing sizes between generations:
            if (generation != sizeGeneration) {
                sizeGeneration = generation
                this.spectrogramSizeDp = null
                this.amplitudeSizeDp = null
            }

            // Some deduping logic to avoid unnecessary redraws, as this gets called multiple times with the
            // same value from the UI layer:

            var changed = false
            if (spectrogramSizeDp != null) {
                changed = changed || this.spectrogramSizeDp != spectrogramSizeDp
                this.spectrogramSizeDp = spectrogramSizeDp
            }
            if (amplitudeSizeDp != null) {
                changed = changed || this.amplitudeSizeDp != amplitudeSizeDp
                this.amplitudeSizeDp = amplitudeSizeDp
            }

            if (changed && this.spectrogramSizeDp != null) {
                Timber.d(
                    "onUISizeChange applying UI size: $generation ${this.spectrogramSizeDp}, ${this.amplitudeSizeDp}"
                )
                viewModelScope.launch(Dispatchers.Default + CoroutineName("onRescale coroutine")) {
                    mutex.withLock {
                        reload(settings, rawPageRange, autoBnCRequiredFlow.value)
                    }
                }
            }
        }
    }

    /**
     * Call from the UI thread.
     *
     * The method is called from the UI to change the region of the source file
     * this is transformed (the page).
     */
    fun onPageChange(settings: Settings, rawPageRange: HORange) {
        Timber.d("onPageChange called: $rawPageRange")
        pipeline?.let {
            viewModelScope.launch(Dispatchers.Default + CoroutineName("onRescale coroutine")) {
                mutex.withLock {
                    reload(
                        settings,
                        rawPageRange,
                        autoBnCRequiredFlow.value,
                        resetVisibleRange = true
                    )
                }
            }
        }
    }

    /**
     * Re-run auto FFT calculations, rendering of data and auto BnC
     * in response to a change to settings or the way the data is being viewed.
     */
    private suspend fun reload(
        settings: Settings,
        rawPageRange: HORange?,
        shouldAutoBnC: Boolean,
        resetVisibleRange: Boolean = false
    ) {
        pipeline?.let { p ->
            Timber.d("reload called for rawPageRange = ${rawPageRange}")

            val newFftParameters = getFftParameters(p.pipelineParametersSnapshot, settings)
            var fftParametersChanged = false
            newFftParameters?.let { fftp ->
                fftParametersChanged = shouldRenderForFft(fftp)
                Timber.d("reload transformed required: $fftParametersChanged")
                currentFftParameters = fftp
            }

            try {
                if (fftParametersChanged || resetVisibleRange) {
                    p.fullExecute(
                        fftParameters = currentFftParameters,
                        rawPageRange = rawPageRange,
                        amplitudeSizeDp = amplitudeSizeDp
                    )
                }

                if (resetVisibleRange)
                    internalSetSpectrogramVisibleRange(
                        FloatRange(0f, 1f),
                        FloatRange(0f, 1f))

                internalDoColourMappingAndRender(
                    shouldAutoBnC,
                    settings.autoBaselineEnabled)
            }
            catch (e: OutOfMemoryError) {
                // Eat any the OOM here, as it will already have been displayed to the
                // user in openLive/openFile. In other words, we shouldn't get here, but log
                // just in case:
                diagnosticLogger.log { "Ignoring out of memory in reload()" }
            }
        }
    }

    /**
     * Rerender the UI with the existing pipeline.
     */
    suspend fun rerender() {
        mutex.withLock {
            triggerBitblt()
        }
    }

    private fun triggerBitblt() {
        spectrogramBitmapHolder.signalUpdate()
        amplitudeBitmapHolder.signalUpdate()
    }

    fun doAutoBnC() {
        viewModelScope.launch(Dispatchers.Default + CoroutineName("doAutoBnC coroutine")) {
            mutex.withLock {
                internalDoColourMappingAndRender(
                    true,
                    settings.autoBaselineEnabled)
            }
        }
    }

    /**
     * Do an auto BnC calculation based on the visible range supplied, and apply it
     * so that the UI is re-rendered accordingly.
     *
     * Contains heavy calculations so don't call it from the UI thread.
     */
    /*
    private suspend fun internalDoAutoBnC(thePipeline: AbstractPipeline) {

        val newBnCRange =
            thePipeline.calculateAutoBnC(
                timeVisibleRangeFlow.value,
                frequencyVisibleRangeFlow.value
            )

        if (newBnCRange != null) {
            val logicalBnCRange = ColourMapStep.bnCRangeDbToLogical(newBnCRange)

            Timber.d("doAutoBnC called: newBnCRange = $newBnCRange, logicalBnCRange= logicalBnCRange")
            // Update our single source of truth, notifying the UI as a side effect:
            mutableBnCRangeFlow.value = logicalBnCRange

            // Have the pipeline fully recalculate/re-render:
            thePipeline.applyBnC(logicalBnCRange)
        }
    }
     */

    /**
     * Return true if either values has changed since the last call that are enabled for auto
     * in the settings. This can be used to trigger UI a re-rendering.
     */
    private suspend fun getFftParameters(pps: PipelineParameters, settings: Settings, sampleRate: Int? = null)
            : AbstractPipeline.FftParameters? {

        val xAxisSpan = timeAxisRangeFlow.value.difference()
        val yAxisSpan = frequencyAxisRangeFlow.value.difference()

        val newFftParameters = calculateFftParameters(
            xAxisSpan, yAxisSpan, sampleRate, pps)

        return newFftParameters
    }

    private fun shouldRenderForFft(newFftParameters: AbstractPipeline.FftParameters?): Boolean {
        var rerenderRequired = false
        newFftParameters?.let {
            // Timber.d("new FFT parameters = $it")
            rerenderRequired = (newFftParameters != currentFftParameters)
        }
        return rerenderRequired
    }

    /**
     * Calculate FFT parameters taking into account any auto settings, and return them.
     */
    private suspend fun calculateFftParameters(
        xAxisSpan: Float,
        yAxisSpan: Float,
        sampleRate: Int?,
        pipelineParameters: PipelineParameters
    ): AbstractPipeline.FftParameters? {

        var newFftParameters: AbstractPipeline.FftParameters? = null       // Window size, overlap.

        val spectrogramSize = spectrogramSizeDp
        if (spectrogramSize != null) {
            // Some values that are needed to support auto FFT parameter calculation:
            val screenFactors =
                AbstractPipeline.calcScreenFactors(spectrogramSize, xAxisSpan, yAxisSpan)

            if (sampleRate == null) {
                val vpl = pipeline
                if (vpl != null) {
                    newFftParameters = vpl.calculateFftParameters(
                        settings, screenFactors
                    )
                }
            } else {
                newFftParameters = AbstractPipeline.calculateFftParameters(
                    pipelineParameters, screenFactors, sampleRate
                )

            }
        }
        return newFftParameters
    }

    /**
     * Get metadata relating to the currently open wav file, if any.
     */
    fun getWavFileInfo(): WavFileReader.WavFileInfo? {
        return wavFileInfo.get()
    }

    /**
     * This method is one way to change the mode of the app. Any code
     * may call this method from the UI thread to change the app mode.
     *
     * The selected mode is entered in reset state, unless the URI is provided
     * to the VIEWER state.
     */
    fun resetUIMode(
        requestedMode: AppMode = AppMode.LIVE,
        uriData: DocumentHelper.UriData? = null,
        streaming: Boolean = false
    ) {
        viewModelScope.launch(CoroutineName("resetUIMode coroutine")) {
            resetAppModeChannel.send(AppModeRequest(requestedMode, uriData, streaming))
        }
    }

    /**
     * Called on the UI thread so keep things light weight.
     */
    suspend fun panSpectrogramVisibleRange(displacement: Offset, size: IntSize, clampX: Boolean) {
        mutex.withLock {
            // Map screen pixels to logical source bitmap values scaled to within
            // the source region in use:
            var logicalSrcDeltaX: Float = -displacement.x / size.width * (
                    timeVisibleRangeFlow.value.endInclusive - timeVisibleRangeFlow.value.start)
            var logicalSrcDeltaY: Float = -displacement.y / size.height * (
                    frequencyVisibleRangeFlow.value.endInclusive - frequencyVisibleRangeFlow.value.start)

            logicalSrcDeltaX = constrainOffsetForRange(
                logicalSrcDeltaX,
                timeVisibleRangeFlow.value
            )
            logicalSrcDeltaY = constrainOffsetForRange(
                logicalSrcDeltaY,
                frequencyVisibleRangeFlow.value
            )

            if (clampX)
                logicalSrcDeltaX = 0f

            internalSetSpectrogramVisibleRange(
                FloatRange(
                    timeVisibleRangeFlow.value.start + logicalSrcDeltaX,
                    timeVisibleRangeFlow.value.endInclusive + logicalSrcDeltaX
                ),
                FloatRange(
                    frequencyVisibleRangeFlow.value.start + logicalSrcDeltaY,
                    frequencyVisibleRangeFlow.value.endInclusive + logicalSrcDeltaY
                )
            )

            /*
                Re-render for the new visible range. Don't fully update the pipeline until
                the end of the gesture, for smoothness.
            */
            triggerBitblt()
        }
    }

    suspend fun zoomSpectrogramVisibleRange(
        startCentroid: Offset,
        previousP1: Offset, previousP2: Offset,
        p1: Offset, p2: Offset,
        size: IntSize, clampX: Boolean
    ) {
        mutex.withLock {
            var (scaleFactorX, scaleFactorY) = Pair(1f, 1f)

            val (dxStart, dyStart) = Pair(
                abs(previousP1.x - previousP2.x),
                abs(previousP1.y - previousP2.y)
            )
            val (dxNow, dyNow) = Pair(abs(p1.x - p2.x), abs(p1.y - p2.y))

            /*
                For sanity and reasonable user experience:
                * Only zoom in one axis, the one with greatest change.
                * Avoid divide by zero.
                * Avoid highly leveraged zooms.
            */
            val minimumStartDelta = 30f
            if (abs(dxNow - dxStart) > abs(dyNow - dyStart))
                scaleFactorX = dxNow / maxOf(dxStart, minimumStartDelta)
            else
                scaleFactorY = dyNow / maxOf(dyStart, minimumStartDelta)

            // Timber.d("Scale factors: $scaleFactorX, $scaleFactorY")

            /**
             * Some extracted calculation code common to X and Y.
             */
            fun applyScaling(
                srcRangeLogical: StateFlow<FloatRange>,
                dstMeanLogical: Float,
                scaleFactor: Float
            ): FloatRange {
                // Map the destination logical means back to the source logical indexes:
                val srcMeanLogical = srcRangeLogical.value.start +
                        (srcRangeLogical.value.endInclusive - srcRangeLogical.value.start) * dstMeanLogical
                // Offset source min and max to centre the centroid at 0, and also the valid range:
                var centredMin = srcRangeLogical.value.start - srcMeanLogical
                var centredMax =
                    srcRangeLogical.value.endInclusive - srcMeanLogical
                // Apply the scaling factor, which will be centred on the centroid:
                centredMin /= scaleFactor  // We checked it for zero previously.
                centredMax /= scaleFactor
                // Remove the offset to get back to source bitmap coordinates:
                var newMin = centredMin + srcMeanLogical
                var newMax = centredMax + srcMeanLogical
                // Limit to valid ranges:
                newMin = newMin.coerceIn(0f, 1f)
                newMax = newMax.coerceIn(0f, 1f)
                // Make sure they are not too close:
                val newRange = enforceNonZeroRange(
                    FloatRange(newMin, newMax),
                    srcRangeLogical.value
                )
                return newRange
            }

            var xRangeScaled = timeVisibleRangeFlow.value
            if (!clampX) {
                if (scaleFactorX.isFinite() && scaleFactorX > 0) {
                    val dstMeanXLogical = startCentroid.x / size.width

                    xRangeScaled = applyScaling(
                        timeVisibleRangeFlow,
                        dstMeanXLogical,
                        scaleFactorX
                    )
                }
            }
            var yRangeScaled = frequencyVisibleRangeFlow.value
            if (scaleFactorY.isFinite() && scaleFactorY > 0) {
                val dstMeanYLogical = startCentroid.y / size.height

                yRangeScaled =
                    applyScaling(frequencyVisibleRangeFlow, dstMeanYLogical, scaleFactorY)
            }

            internalSetSpectrogramVisibleRange(xRangeScaled, yRangeScaled)

            /*
               Re-render for the new visible range. Don't fully update the pipeline until
               the end of the gesture, for smoothness.
            */
            triggerBitblt()
        }
    }

    suspend fun onLongPress(
        graph: GraphBase, displacement: Offset, size: IntSize,
        liveMode: Boolean
    ) {
        mutex.withLock {
            // Adjust ranges to move the tap position to the centre of the screen
            // maintaining the same scaling as far as possible.

            // Map the screen logical coordinates map into the range of the source min..max:
            val xDstLogical: Float = displacement.x / size.width
            val yDstLogical: Float = displacement.y / size.height
            val xSrcLogical: Float =
                logicalScaleToRange(xDstLogical, timeVisibleRangeFlow.value)
            val ySrcLogical: Float =
                logicalScaleToRange(yDstLogical, frequencyVisibleRangeFlow.value)

            // We need to adjust the ranges so that the tap ends up in the middle:
            val deltaX = timeVisibleRangeFlow.value.mean() - xSrcLogical
            val deltaY = frequencyVisibleRangeFlow.value.mean() - ySrcLogical

            // Apply the offset, making sure we don't end up with an invalid range:
            val constrainedXOffset =
                constrainOffsetForRange(-deltaX, timeVisibleRangeFlow.value)
            var xRangeNew =
                timeVisibleRangeFlow.value.addOffset(constrainedXOffset)
            xRangeNew = enforceNonZeroRange(xRangeNew)

            val constrainedYOffset =
                constrainOffsetForRange(-deltaY, frequencyVisibleRangeFlow.value)
            var yRangeNew =
                frequencyVisibleRangeFlow.value.addOffset(constrainedYOffset)
            yRangeNew = enforceNonZeroRange(yRangeNew)

            if (liveMode) {
                // No change:
                xRangeNew = timeVisibleRangeFlow.value
            }

            internalSetSpectrogramVisibleRange(xRangeNew, yRangeNew)

            // Don't change the pipeline during live acquisition:
            if (!liveMode)
                graph.onVisibleRangeChange(autoBnCRequiredFlow.value)

            triggerBitblt()
        }
    }

    suspend fun onDoubleTap(graph: GraphBase, liveMode: Boolean, shouldAutoBnC: Boolean) {
        mutex.withLock {
            val xRange = if (liveMode) timeVisibleRangeFlow.value else FloatRange(0f, 1f)
            internalSetSpectrogramVisibleRange(xRange, FloatRange(0f, 1f))

            // Don't change the pipeline during live acquisition:
            if (!liveMode)
                graph.onVisibleRangeChange(shouldAutoBnC)

            triggerBitblt()
        }
    }
}