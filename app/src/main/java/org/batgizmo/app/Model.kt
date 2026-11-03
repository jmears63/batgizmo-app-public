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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.batgizmo.app.ml.BirdNetModel
import org.batgizmo.app.ml.MlActiveModels
import org.batgizmo.app.ml.MlCatalog
import org.batgizmo.app.ml.MlClient
import org.batgizmo.app.ml.MlModelDescriptor
import org.batgizmo.app.ml.MlOverflowPolicy
import org.batgizmo.app.ml.MlResult
import org.batgizmo.app.ml.MlSummaryAccumulator
import org.batgizmo.app.ml.MlSummaryMode
import org.batgizmo.app.pipeline.AbstractPipeline
import org.batgizmo.app.pipeline.ColourMapStep
import org.batgizmo.app.pipeline.FileViewerPipeline
import org.batgizmo.app.pipeline.LiveAudioStartResult
import org.batgizmo.app.pipeline.LiveConnectResult
import org.batgizmo.app.pipeline.LiveInputSource
import org.batgizmo.app.pipeline.LiveStreamErrorResult
import org.batgizmo.app.pipeline.LiveUSBPipeline
import org.batgizmo.app.pipeline.MicCaptureService
import org.batgizmo.app.pipeline.DeviceMicInputSource
import org.batgizmo.app.pipeline.UsbLiveInputSource
import org.batgizmo.app.pipeline.UsbService
import org.batgizmo.app.pipeline.UsbHighRateMicProbeResult
import org.batgizmo.app.ui.GraphBase
import org.batgizmo.app.ui.SpectrogramGestureAxis
import org.batgizmo.app.ui.SpectrogramUI
import org.batgizmo.app.ui.TopLevelUI
import org.batgizmo.app.ui.TopLevelUI.AppMode
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
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
        /** Unix-epoch seconds of the first sample in this buffer. */
        abstract val observedAtEpochSec: Double

        /** Samples in a native URB buffer (USB live path). */
        data class Native(
            val nativeAddress: Long,
            override val samples: Int,
            override val observedAtEpochSec: Double,
        ) : BufferDescriptor()

        /** Samples in a Kotlin heap buffer (reserved for non-USB live sources). */
        data class Heap(
            val data: ShortArray,
            val offset: Int,
            override val samples: Int,
            override val observedAtEpochSec: Double,
        ) : BufferDescriptor()
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
        trySendToChannels(
            BufferDescriptor.Native(
                nativeAddress,
                samples,
                System.currentTimeMillis() / 1000.0,
            )
        )
    }

    /**
     * Post samples from a Kotlin heap buffer (phone microphone live path).
     * A copy is taken so the capture thread can reuse its read buffer immediately.
     */
    fun onHeapDataBufferReady(source: ShortArray, offset: Int, samples: Int) {
        if (samples <= 0)
            return
        val copy = ShortArray(samples)
        source.copyInto(copy, destinationOffset = 0, startIndex = offset, endIndex = offset + samples)
        trySendToChannels(
            BufferDescriptor.Heap(copy, 0, samples, System.currentTimeMillis() / 1000.0)
        )
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
        mlSummaryAccumulator.close()
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
            activeLiveInputSource?.disconnect() ?: usbService.disconnect()
        }
    }

    private fun createLiveInputSource(liveInputSource: Int): LiveInputSource {
        return when (liveInputSource) {
            Settings.LiveInputSourceOptions.PHONE_MIC.value -> {
                // Apply the user's chosen internal microphone before capture starts.
                micCaptureService.preferredInternalMicId = settings.internalMicId
                DeviceMicInputSource(micCaptureService)
            }

            else ->
                UsbLiveInputSource(usbService, usbConnectChannel, usbConnectFlow)
        }
    }

    /** The internal microphones the user can choose between (first entry is "Automatic"). */
    fun availableInternalMics(): List<MicCaptureService.MicOption> =
        micCaptureService.availableInternalMics()

    /** The audio outputs the user can choose between (first entry is "Default"). */
    fun availableAudioOutputs(): List<UsbService.AudioOutputOption> =
        usbService.availableAudioOutputs()

    private suspend fun cleanupPartialLiveConnect(liveInputSource: LiveInputSource) {
        liveMlAccepting = false
        flushMlClientFinal()
        fileWriter?.shutdown()
        fileWriter = null
        pipeline?.shutdown()
        pipeline = null
        setConnectedLiveInputSource(null)
        liveInputSource.disconnect()
        activeLiveInputSource = null
        synchronized(mlLock) {
            clearMlClientLocked()
        }
    }

    private suspend fun connectLiveInputAndBuildPipeline(
        pps: PipelineParameters,
        context: Context,
        onFileWriterError: (String) -> Unit,
        resetLiveVisibleRanges: Boolean,
        liveInputSourceSetting: Int,
    ): LiveConnectResult {
        val liveInputSource = createLiveInputSource(liveInputSourceSetting)
        activeLiveInputSource = liveInputSource

        if (liveInputSourceSetting == Settings.LiveInputSourceOptions.USB.value) {
            // Make sure there are no pending responses:
            drainChannel(usbErrorChannel)
        }

        try {
            val result = liveInputSource.connect { min, max, res, cur ->
                // Lambda executed if the microphone supports volume setting:
                mutableMicrophoneVolumeParametersFlow.value = UsbVolumeParameters(min, max, res)
                mutableMicrophoneGainFlow.value = cur
            }

            if (!result.connectedOK) {
                liveInputSource.disconnect()
                activeLiveInputSource = null
                return augmentUsbMicFallbackResult(result)
            }

            require(result.sampleRate != null)

            val p = LiveUSBPipeline(
                pps,
                viewModelScope,
                context, this,
                spectrogramBitmapHolder, amplitudeBitmapHolder,
                mutableTimeAxisRangeFlow, mutableFrequencyAxisRangeFlow,
                mutableDetailsTextFlow, result.sampleRate,
                result.sampleRate * pps.dataPageTimeSpanS
            ) {
                // This lambda is called if an energy trigger was detected in the live data.

                if (settings.isEnergyAutoTrigger()) {
                    fileWriter?.trigger()
                }
                // Trigger the monitor lamp in the Settings UI:
                triggerMonitorChannel.trySend(Unit)
            }

            // Initial values for the FFT parameters (window, overlap):
            val defaultSize = DpSize(100.dp, 100.dp) // Square by default.
            Timber.d("spectrogramSizeDp = $spectrogramSizeDp in connectLiveInputAndBuildPipeline()")
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
            rememberPipelineBuild(
                fftParameters,
                spectrogramSizeDp ?: defaultSize,
                amplitudeSizeDp
            )
            setConnectedLiveInputSource(liveInputSourceSetting)

            if (resetLiveVisibleRanges) {
                // Preset the time range in live mode to a value according to settings.
                // This has a side affect of updating the axis ranges in the UI:
                val (timeAxisRange, dummy) = getTimeAxisRangeForLive()
                mutableTimeAxisRangeFlow.value = timeAxisRange
                val logicalTimeRange = FloatRange(
                    0f,
                    timeAxisRangeFlow.value.endInclusive / pps.dataPageTimeSpanS
                )
                internalSetSpectrogramVisibleRange(logicalTimeRange, FloatRange(0f, 1f))
            }

            // Start up the file writer ready to write data to file:
            fileWriter = FileWriter(
                viewModelScope, getApplication(),
                this, locationFlow, result, result.sampleRate,
                { isWriting: Boolean ->
                    mutableCurrentlyWritingFlow.value = isWriting
                },
                onFileWriterError
            )
            fileWriter?.run()

            mlSummaryAccumulator.clear(MlSummaryMode.Live)
            liveMlAccepting = true
            ensureMlClient(result.sampleRate, forceReset = true)

            if (settings.autoBaselineEnabled) {
                tryRestoreNoiseBaseline(p)
            }

            return result
        } catch (e: OutOfMemoryError) {
            cleanupPartialLiveConnect(liveInputSource)
            return LiveConnectResult(false, oomMessage)
        } catch (e: Exception) {
            Timber.w(e, "Exception during live connect")
            cleanupPartialLiveConnect(liveInputSource)
            return LiveConnectResult(
                connectedOK = false,
                errorMessage = e.localizedMessage ?: "Unable to connect live input source.",
                offerInternalMicFallback =
                    settings.liveInputSource == Settings.LiveInputSourceOptions.USB.value &&
                    LiveConnectResult.isNoUsbMicrophoneError(e.localizedMessage)
            )
        }
    }

    private fun augmentUsbMicFallbackResult(result: LiveConnectResult): LiveConnectResult {
        if (result.offerInternalMicFallback)
            return result
        if (settings.liveInputSource != Settings.LiveInputSourceOptions.USB.value)
            return result
        if (result.connectedOK || !LiveConnectResult.isNoUsbMicrophoneError(result.errorMessage))
            return result
        return result.copy(offerInternalMicFallback = true)
    }

    private suspend fun reconnectLiveInputSource(
        onFileWriterError: (String) -> Unit,
        targetSource: Int = settings.liveInputSource
    ): LiveConnectResult {
        Timber.i(
            "Live input source changing from $connectedLiveInputSource to $targetSource; reconnecting"
        )

        activeLiveInputSource?.disconnect()
        activeLiveInputSource = null

        fileWriter?.shutdown()
        fileWriter = null

        liveMlAccepting = false
        flushMlClientFinal()

        pipeline?.shutdown()
        pipeline = null
        setConnectedLiveInputSource(null)

        mutableMicrophoneVolumeParametersFlow.value = null
        mutableMicrophoneGainFlow.value = null

        val pps = settings.pipelineParameters.copy()
        return connectLiveInputAndBuildPipeline(
            pps,
            getApplication(),
            onFileWriterError,
            resetLiveVisibleRanges = false,
            liveInputSourceSetting = targetSource
        )
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
    private val mutableAudioBoostFlow = MutableStateFlow(Settings.DEFAULT_AUDIO_BOOST_FACTOR)
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

    /**
     * Running ML summary: unique labels with max confidence and last-seen time.
     * Survives recomposition and configuration changes with the ViewModel.
     */
    private val mlSummaryAccumulator = MlSummaryAccumulator()
    val mlSummaryFlow = mlSummaryAccumulator.summary
    val mlSummaryModeFlow = mlSummaryAccumulator.mode

    /** True while Auto Id is ingesting samples into incomplete chunks. */
    private val mutableMlBufferingFlow = MutableStateFlow(false)
    val mlBufferingFlow: StateFlow<Boolean> = mutableMlBufferingFlow.asStateFlow()
    private var mlBufferingCollectJob: Job? = null

    /** True while Auto Id has queued or in-flight processor work. */
    private val mutableMlBusyFlow = MutableStateFlow(false)
    val mlBusyFlow: StateFlow<Boolean> = mutableMlBusyFlow.asStateFlow()
    private var mlBusyCollectJob: Job? = null

    /**
     * True when the Auto Id client will ingest audio at the current sample rate
     * (at or above the model minimum). False when there is no client or the rate
     * is too low.
     */
    private val mutableMlWillAcceptAudioFlow = MutableStateFlow(false)
    val mlWillAcceptAudioFlow: StateFlow<Boolean> = mutableMlWillAcceptAudioFlow.asStateFlow()
    private var mlWillAcceptAudioCollectJob: Job? = null

    /**
     * True when a user-selected classifier will ingest audio at the current
     * sample rate. Internal `liveSystem` models do not count.
     */
    private val mutableMlUserModelsActiveFlow = MutableStateFlow(false)
    val mlUserModelsActiveFlow: StateFlow<Boolean> = mutableMlUserModelsActiveFlow.asStateFlow()
    private var mlUserModelsActiveCollectJob: Job? = null

    /** Live ML client for the current sample rate, or null when inactive. */
    private var mlClient: MlClient? = null
    private var mlClientSampleRateHz: Int? = null

    /**
     * Bumped in [clearMlClientLocked] so in-flight [MlClient] callbacks from a
     * torn-down client cannot submit results after a model/session change.
     */
    private val mlResultGeneration = AtomicInteger(0)

    /**
     * Serializes [mlClient] mutate/submit so live [USBSourceStep] (no UIModel mutex) cannot
     * race settings/close. Never take [mutex] while holding this lock.
     */
    private val mlLock = Any()

    /**
     * When true, live PCM from [USBSourceStep] may be submitted to [mlClient]. Cleared on
     * pause / Auto Id off / close; set on live connect and resume when Auto Id is enabled.
     */
    @Volatile
    private var liveMlAccepting = false

    /**
     * Viewer smart heterodyne playback.
     * [ViewerSmartHet.Playing] blocks file-page analysis from replacing [mlClient].
     * [ViewerSmartHet.ModelLoaded] means BatCNN was added, so stopping must
     * submit the file page again without it.
     */
    private enum class ViewerSmartHet { Off, Playing, ModelLoaded }

    @Volatile
    private var viewerSmartHet = ViewerSmartHet.Off

    private val audioProgressChannel = Channel<Int>(Channel.CONFLATED)
    val audioProgressFlow = audioProgressChannel.receiveAsFlow()

    private var audioOutputActive = false

    private val colourMapHelper = ColourMapHelper(getApplication())
    /** Entry count of the colour map currently installed; used by the render pipeline. */
    val colourMapSize: Int?
        get() = colourMapHelper.mapSize

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

    /**
     * In-session noise baseline so live start/resume can reapply correction before
     * enough new data exists to recompute. Invalidated when sample rate or FFT
     * frequency-bucket count no longer match.
     */
    private data class NoiseBaselineCache(
        val sampleRateHz: Int,
        val frequencyBucketCount: Int,
        val baselineDb: FloatArray
    )
    private var noiseBaselineSessionCache: NoiseBaselineCache? = null

    // Various UI states that need to be accessible outside compose context:
    val spectrogramUIState = SpectrogramUI.UIState()
    val spectrogramButtonState = SpectrogramUI.ButtonState()
    val topLevelUIState = TopLevelUI.UIState()

    private var spectrogramSizeDp: DpSize? = null
    private var amplitudeSizeDp: DpSize? = null
    private var sizeGeneration =
        -1          // Track the UI Compose generation that sizes relate to.

    // The pane sizes the pipeline was last (re)built for, so a transient relayout that settles
    // back to the same size does not trigger a needless, disruptive rebuild:
    private var lastBuiltSpectrogramSizeDp: DpSize? = null
    private var lastBuiltAmplitudeSizeDp: DpSize? = null
    // Pending debounced pipeline rebuild triggered by pane size changes:
    private var sizeChangeJob: Job? = null
    // How long to wait for pane sizes to settle before rebuilding, long enough to absorb a
    // transient system relayout (e.g. the microphone privacy indicator appearing then leaving):
    private val sizeChangeDebounceMs = 1200L
    // Cap how long file-open will wait for that quiet period (picker return can bounce a few times):
    private val paneSizeSettleTimeoutMs = 4000L

    /**
     * After open/live pipeline build: record the FFT params and pane sizes actually used, and
     * cancel any pending size-debounce job. Otherwise open can race with layout bounce:
     * build on a mid-bounce size while [lastBuiltSpectrogramSizeDp] still matches the eventual
     * settled size, so the debounce skips reload and a bad first render sticks.
     */
    private fun rememberPipelineBuild(
        fftParameters: AbstractPipeline.FftParameters,
        builtSpectrogramSizeDp: DpSize,
        builtAmplitudeSizeDp: DpSize?
    ) {
        sizeChangeJob?.cancel()
        sizeChangeJob = null
        currentFftParameters = fftParameters
        lastBuiltSpectrogramSizeDp = builtSpectrogramSizeDp
        lastBuiltAmplitudeSizeDp = builtAmplitudeSizeDp
    }

    /**
     * Wait until spectrogram/amplitude pane sizes have been unchanged for [sizeChangeDebounceMs]
     * (same quiet period as bounce handling), or until [paneSizeSettleTimeoutMs] elapses.
     * Used before opening a file after the document picker, when returning often triggers a
     * layout bounce that would otherwise race the first render.
     */
    private suspend fun awaitPaneSizesSettled() {
        val completed = withTimeoutOrNull(paneSizeSettleTimeoutMs) {
            while (true) {
                val (spec, amp) = mutex.withLock {
                    Pair(spectrogramSizeDp, amplitudeSizeDp)
                }
                delay(sizeChangeDebounceMs)
                val settled = mutex.withLock {
                    spectrogramSizeDp != null &&
                        spectrogramSizeDp == spec &&
                        amplitudeSizeDp == amp
                }
                if (settled) {
                    Timber.d("Pane sizes settled: $spec, $amp")
                    return@withTimeoutOrNull true
                }
                Timber.d("Pane sizes still changing; waiting again")
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (completed != true)
            Timber.w("Timed out waiting for pane sizes to settle; opening anyway")
    }

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
    private val liveConnectChannel = Channel<LiveConnectResult>(Channel.BUFFERED)
    val liveConnectFlow = liveConnectChannel.receiveAsFlow()

    // Signal to the UI that an attempt to start audio has completed:
    private val liveAudioStartChannel = Channel<LiveAudioStartResult>(Channel.BUFFERED)
    val liveAudioStartFlow = liveAudioStartChannel.receiveAsFlow()

    // Signal to this model that an attempt to connect to the USB device has completed:
    private val usbConnectChannel = Channel<LiveConnectResult>(Channel.BUFFERED)
    private val usbConnectFlow = usbConnectChannel.receiveAsFlow()
    private val usbErrorChannel = Channel<LiveStreamErrorResult>(Channel.BUFFERED)
    val usbErrorFlow = usbErrorChannel.receiveAsFlow()
    private val usbProbeChannel = Channel<UsbHighRateMicProbeResult>(Channel.BUFFERED)
    private val usbService =
        UsbService(
            getApplication(), this, usbConnectChannel, usbErrorChannel,
            usbProbeChannel, viewModelScope
        )
    private val micCaptureService =
        MicCaptureService(getApplication(), viewModelScope).also { mic ->
            mic.feedLiveAudioSamples = usbService::feedLiveAudioSamples
        }
    private var activeLiveInputSource: LiveInputSource? = null
    private var connectedLiveInputSource: Int? = null
    private var liveInputSourceOverrideSession = false
    private val mutableConnectedLiveInputSourceFlow = MutableStateFlow<Int?>(null)
    val connectedLiveInputSourceFlow: StateFlow<Int?> =
        mutableConnectedLiveInputSourceFlow.asStateFlow()
    private var liveFileWriterErrorHandler: ((String) -> Unit)? = null

    /** Live input source in use now; falls back to the stored preference when not connected. */
    fun effectiveLiveInputSource(): Int =
        connectedLiveInputSource ?: settings.liveInputSource

    private fun setConnectedLiveInputSource(liveInputSourceSetting: Int?) {
        connectedLiveInputSource = liveInputSourceSetting
        mutableConnectedLiveInputSourceFlow.value = liveInputSourceSetting
        liveInputSourceOverrideSession = liveInputSourceSetting != null &&
            liveInputSourceSetting != settings.liveInputSource
    }

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

    /**
     * Startup What’s New / high-rate USB mic offer sequencing.
     * Declared after [settings] / [usbService] so suppress callbacks and probing can use both.
     */
    private val startupPrompts = StartupPromptCoordinator(
        scope = viewModelScope,
        settingsDataStore = settingsDataStore,
        settings = { settings },
        suppressUpdateNotification = {
            updateStoredSettings(settings.copy(suppressUpdateNotification = true))
        },
        suppressHighRateMicOffer = {
            updateStoredSettings(settings.copy(suppressHighRateMicOffer = true))
        },
        probeResults = usbProbeChannel.receiveAsFlow(),
        startHighRateProbe = { usbService.probeHighRateUsbMicrophone() },
    )
    val showWhatsNew: StateFlow<Boolean> = startupPrompts.showWhatsNew
    val highRateMicOffer: StateFlow<UsbHighRateMicProbeResult?> = startupPrompts.highRateMicOffer

    fun dismissWhatsNew(suppressFurther: Boolean = false) {
        startupPrompts.dismissWhatsNew(suppressFurther)
    }

    fun noteLaunchedForFileView() {
        startupPrompts.noteLaunchedForFileView()
    }

    private val mutableIncomingByomZip = MutableStateFlow<Uri?>(null)
    /** Non-null when a BYOM zip was opened via VIEW / SEND and awaits import UI. */
    val incomingByomZip: StateFlow<Uri?> = mutableIncomingByomZip.asStateFlow()

    fun offerIncomingByomZip(uri: Uri) {
        mutableIncomingByomZip.value = uri
    }

    fun consumeIncomingByomZip() {
        mutableIncomingByomZip.value = null
    }

    fun dismissHighRateMicOffer(suppressFurther: Boolean = false) {
        startupPrompts.dismissHighRateMicOffer(suppressFurther)
    }

    fun maybeProbeHighRateUsbMicrophone() {
        startupPrompts.maybeProbeHighRateUsbMicrophone()
    }

    /** When true, successful live connect should also start audio monitoring. */
    @Volatile
    private var pendingStartAudioAfterLiveConnect = false

    fun consumePendingStartAudioAfterLiveConnect(): Boolean {
        val pending = pendingStartAudioAfterLiveConnect
        pendingStartAudioAfterLiveConnect = false
        return pending
    }

    fun armStartAudioAfterLiveConnect() {
        pendingStartAudioAfterLiveConnect = true
    }

    // Last AGC enable value pushed to native; avoid re-calling setAgcEnabled (which resets
    // the envelope) when unrelated settings are persisted (e.g. audio boost slider release).
    private var appliedAudioAgcEnabled: Boolean? = null

    // The trigger monitor is displayed in the Settings UI for convenience when adjust the trigger
    // threshold. Multiple triggers are combined into one:
    val triggerMonitorChannel = Channel<Unit>(Channel.CONFLATED)

    // Protect all the instance data:
    val mutex = Mutex()

    // Used to make sure we wait until any previous pipeline has finished its
    // async shutdown before we create a new one. This avoids a race with a new
    // pipeline potentially treading on the heels of the previous one@
    var pipelineCloseJob: Job? = null

    /**
     * Auto-tuned heterodyne orchestration (activity tracking, LO smoothing, worker).
     * Declared after [usbService] / [mutex] so apply-LO can use both.
     */
    private val autoHeterodyne = AutoHeterodyneController(
        scope = viewModelScope,
        pipeline = { pipeline },
        settings = { settings },
        isAudioOutputActive = { audioOutputActive },
        applyHeterodyneLo = { refkHz ->
            mutex.withLock {
                usbService.setHeterodyne(refkHz, null)
            }
        }
    )
    val autoHeterodyneRefkHzFlow: StateFlow<Int?> = autoHeterodyne.refkHzFlow

    // Location things:
    private val locationMutableFlow = MutableStateFlow<Location?>(null)
    val locationFlow : StateFlow<Location?> = locationMutableFlow
    val locationTracker = LocationTracker(getApplication()) { it ->
        Timber.d("Location update received: ${it.latitude} ${it.longitude}")
        locationMutableFlow.value = it
        maybeOfferBirdNetGeoSnapshot(it)
    }

    val documentHelper = DocumentHelper()

    init {
        Timber.d("Creating instance of UIModel")

        resetRanges()
        spectrogramButtonState.reset()
        spectrogramUIState.reset()

        // Initialize the UI to a known mode.
        resetUIMode(requestedMode = AppMode.LIVE)

        colourMapHelper.initialize(settings.colourMap)
        startupPrompts.start()

        mlSummaryAccumulator.start(viewModelScope)

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
                var persistSettings = false
                mutex.withLock {
                    persistSettings = settings.copyFromPreferences(prefs)
                    if (settings.isAutoIdEnabled()) {
                        val modelId = settings.autoIdModelId
                        val descriptor = try {
                            MlCatalog.ensureInitialized(getApplication())
                            MlCatalog.resolveDescriptor(
                                getApplication<Application>().assets,
                                modelId,
                            )
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to resolve Auto Id model $modelId")
                            null
                        }
                        if (descriptor != null && settings.autoIdModelId != descriptor.id) {
                            settings.autoIdModelId = descriptor.id
                            val family = descriptor.familyId
                            settings.autoIdFamilyPrefs = settings.autoIdFamilyPrefs +
                                (family to AutoIdFamilyPref(
                                    descriptor.id,
                                    settings.autoIdFamilyPrefs[family]?.language
                                        ?: settings.autoIdLanguage,
                                ))
                            if (family !in settings.autoIdEnabledFamilyIds) {
                                settings.autoIdEnabledFamilyIds =
                                    settings.autoIdEnabledFamilyIds + family
                            }
                            persistSettings = true
                        }
                        // Merge disable_by_default for every enabled model, not only primary.
                        val assets = getApplication<Application>().assets
                        val active = try {
                            MlActiveModels.resolve(assets, settings)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to resolve active Auto Id models")
                            emptyList()
                        }
                        for (desc in active) {
                            val defaults = desc.labelCatalog
                                .filter { !it.discard }
                                .associate { it.key to it.disableByDefault }
                            if (settings.mergeAutoIdSuppressionDefaults(desc.id, defaults)) {
                                persistSettings = true
                            }
                        }
                    }
                    if (colourMapHelper.apply(settings.colourMap)) {
                        pipeline?.fullRenderFromSource()
                        triggerBitblt()
                    }
                }
                if (persistSettings) {
                    settingsDataStore.edit { editPrefs ->
                        settings.copyToPreferences(editPrefs)
                    }
                }
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
                val liveInputSourceChanged =
                    updatedSettings.liveInputSource != settings.liveInputSource
                val colourMapChanged = updatedSettings.colourMap != settings.colourMap
                val wasAutoIdEnabled = settings.isAutoIdEnabled()
                val nowAutoIdEnabled = updatedSettings.isAutoIdEnabled()
                val autoIdEnabled = nowAutoIdEnabled && !wasAutoIdEnabled
                val autoIdDisabled = !nowAutoIdEnabled && wasAutoIdEnabled
                val autoIdSelectionChanged =
                    updatedSettings.autoIdEnabledFamilyIds != settings.autoIdEnabledFamilyIds ||
                        updatedSettings.autoIdFamilyPrefs != settings.autoIdFamilyPrefs ||
                        updatedSettings.autoIdModelId != settings.autoIdModelId
                val autoIdLanguageChanged =
                    updatedSettings.autoIdLanguage != settings.autoIdLanguage ||
                        updatedSettings.autoIdFamilyPrefs != settings.autoIdFamilyPrefs
                val autoIdSuppressionsChanged =
                    updatedSettings.autoIdSuppressions != settings.autoIdSuppressions
                val liveSystemModelsChanged =
                    updatedSettings.isSmartHeterodyneSelected() !=
                        settings.isSmartHeterodyneSelected()
                settings = updatedSettings
                if (liveInputSourceChanged) {
                    liveInputSourceOverrideSession = false
                }
                // Invoke edit on the datastore to update and persist the changes:
                settingsDataStore.edit { prefs -> settings.copyToPreferences(prefs) }
                if (colourMapChanged && colourMapHelper.apply(settings.colourMap)) {
                    pipeline?.fullRenderFromSource()
                    triggerBitblt()
                }
                if (autoIdEnabled || (nowAutoIdEnabled && autoIdSelectionChanged)) {
                    if (autoIdSelectionChanged) {
                        synchronized(mlLock) {
                            clearMlClientLocked()
                        }
                        mlSummaryAccumulator.clear(MlSummaryMode.Live)
                    }
                    pipeline?.let { p ->
                        when (p) {
                            is FileViewerPipeline -> submitFilePageToMl(p)
                            is LiveUSBPipeline -> {
                                mlSummaryAccumulator.clear(MlSummaryMode.Live)
                                liveMlAccepting = true
                                ensureMlClient(p.sampleRateHz(), forceReset = true)
                            }
                            else -> {}
                        }
                    }
                } else if (autoIdDisabled) {
                    synchronized(mlLock) {
                        clearMlClientLocked()
                    }
                    mlSummaryAccumulator.clear(MlSummaryMode.Live)
                    when (val p = pipeline) {
                        is LiveUSBPipeline -> {
                            ensureMlClient(p.sampleRateHz(), forceReset = true)
                        }
                        is FileViewerPipeline -> {
                            liveMlAccepting = false
                            submitFilePageToMl(p)
                        }
                        else -> liveMlAccepting = false
                    }
                } else if (liveSystemModelsChanged && pipeline is LiveUSBPipeline) {
                    val live = pipeline as LiveUSBPipeline
                    synchronized(mlLock) {
                        clearMlClientLocked()
                    }
                    mlSummaryAccumulator.clear(MlSummaryMode.Live)
                    ensureMlClient(live.sampleRateHz(), forceReset = true)
                } else if (nowAutoIdEnabled &&
                    (autoIdLanguageChanged || autoIdSuppressionsChanged)
                ) {
                    synchronized(mlLock) {
                        mlClient?.let { client ->
                            applyMlClientLabelAndSuppressionsLocked(client, updatedSettings)
                        }
                    }
                    when (val p = pipeline) {
                        is FileViewerPipeline -> submitFilePageToMl(p)
                        else -> mlSummaryAccumulator.clear(MlSummaryMode.Live)
                    }
                }
            }
        }
    }

    /**
     * Drop (and optionally recreate) the live Auto Id client so TFLite is
     * reopened from disk. Required after BYOM replace of the same model id,
     * and before deleting the active BYOM pack.
     */
    suspend fun reloadAutoIdClient(recreate: Boolean = true) {
        withContext(Dispatchers.Default) {
            synchronized(mlLock) {
                clearMlClientLocked()
            }
            mlSummaryAccumulator.clear(MlSummaryMode.Live)
            if (!recreate) return@withContext
            when (val p = pipeline) {
                is LiveUSBPipeline -> {
                    ensureMlClient(p.sampleRateHz(), forceReset = true)
                }
                is FileViewerPipeline -> submitFilePageToMl(p)
                else -> {}
            }
        }
    }

    /**
     * Import a BYOM zip, select it, and force-reload TFLite (even when the
     * model id is unchanged after an overwrite).
     */
    suspend fun importByomClassifier(
        uri: Uri,
        replaceExisting: Boolean,
    ): MlModelDescriptor {
        val app = getApplication<Application>()
        val installed = withContext(Dispatchers.IO) {
            MlCatalog.importByomZip(app, uri, replaceExisting = replaceExisting)
        }
        val defaults = installed.labelCatalog
            .filter { !it.discard }
            .associate { it.key to it.disableByDefault }
        val language = settings.autoIdFamilyPrefs[installed.familyId]?.language
            ?: settings.autoIdLanguage
        val updated = settings.withAutoIdFamilySelection(
            familyId = installed.familyId,
            modelId = installed.id,
            language = language,
        )
        updated.mergeAutoIdSuppressionDefaults(installed.id, defaults)
        updateStoredSettings(updated)
        // Same-id overwrite does not flip autoIdModelChanged; always remmap.
        reloadAutoIdClient(recreate = true)
        return installed
    }

    /**
     * Remove an installed BYOM family. Tears down TFLite first when the active
     * model belongs to that family, then selects a bundled fallback.
     */
    suspend fun removeByomClassifier(familyId: String): Boolean {
        val app = getApplication<Application>()
        val assets = app.assets
        val removedModelIds = MlCatalog.listDescriptors(assets)
            .filter { it.familyId == familyId }
            .map { it.id }
            .toSet()
        val removingActive = familyId in settings.autoIdEnabledFamilyIds ||
            settings.autoIdModelId in removedModelIds
        if (removingActive) {
            reloadAutoIdClient(recreate = false)
        }
        val removed = withContext(Dispatchers.IO) {
            MlCatalog.removeByomFamily(app, familyId)
        }
        if (!removed) return false
        val nested = settings.autoIdSuppressions.filterKeys { it !in removedModelIds }
        val prefsWithout = settings.autoIdFamilyPrefs - familyId
        val enabledWithout = settings.autoIdEnabledFamilyIds.filterNot { it == familyId }
        val fallback = MlCatalog.resolveDescriptor(assets, null)
        val remaining = MlCatalog.listDescriptors(assets)
        val enabledOrFallback =
            enabledWithout.ifEmpty { listOf(fallback.familyId) }
        val firstFamily = enabledOrFallback.first()
        val remembered = prefsWithout[firstFamily]
        val variants = remaining.filter { it.familyId == firstFamily }
        val modelId = remembered?.variantId
            ?.takeIf { id -> variants.any { it.id == id } }
            ?: variants.firstOrNull()?.id
            ?: fallback.id
        val chosen = remaining.firstOrNull { it.id == modelId } ?: fallback
        val language = remembered?.language
            ?.takeIf { chosen.languages.isEmpty() || it in chosen.languages.indices }
            ?: settings.autoIdLanguage
        val updated = settings.copy(
            autoIdModelId = chosen.id,
            autoIdLanguage = language,
            autoIdEnabledFamilyIds = enabledOrFallback,
            autoIdFamilyPrefs = prefsWithout +
                (chosen.familyId to AutoIdFamilyPref(chosen.id, language)),
            autoIdSuppressions = nested,
        )
        val defaults = chosen.labelCatalog
            .filter { !it.discard }
            .associate { it.key to it.disableByDefault }
        updated.mergeAutoIdSuppressionDefaults(chosen.id, defaults)
        updateStoredSettings(updated)
        return true
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
     *
     * @param waitForPaneSizesToSettle if true, wait for spectrogram pane sizes to stop changing
     *   (layout bounce after the document picker) before building the pipeline. Not needed for
     *   next/previous within an already-stable viewer layout.
     */
    fun openFile(uri: Uri, filename: String, waitForPaneSizesToSettle: Boolean = false) {
        val context: Context = getApplication()

        val model = this
        var result: OpenWavFileResult? = null
        viewModelScope.launch(Dispatchers.Default + CoroutineName("openFile coroutine")) {

            if (waitForPaneSizesToSettle) {
                Timber.d("openFile: waiting for pane sizes to settle ($filename)")
                awaitPaneSizesSettled()
            }

            // Allow any previous pipeline async cleanup to complete before we create a new one,
            // to avoid overlap and races:
            pipelineCloseJob?.join()
            pipelineCloseJob = null

            mutex.withLock {
                try {
                    Timber.d("openFile called for $filename")

                    // Drop any prior Auto Id work before tearing down the old pipeline.
                    discardQueuedMlWork(MlSummaryMode.Viewer)

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
                        mutableDetailsTextFlow, wfi.sampleRate, wfi.sampleCount,
                        wfi.numChannels.toInt(), wfi.bytesPerValue * 8
                    )
                    // Assume square if we don't know yet:
                    val builtSpectrogramSizeDp = spectrogramSizeDp ?: DpSize(100.dp, 100.dp)
                    val fftParameters = p.getDefaultFftParameters(
                        wfi.sampleRate,
                        builtSpectrogramSizeDp
                    )

                    // Do a full render from file:
                    p.fullExecute(
                        fftParameters = fftParameters,
                        amplitudeSizeDp = amplitudeSizeDp
                    )

                    pipeline = p
                    // Align last-built sizes with this render so a bounce that settles to a
                    // different size still reloads, instead of skipping as a no-op bounce:
                    rememberPipelineBuild(
                        fftParameters,
                        builtSpectrogramSizeDp,
                        amplitudeSizeDp
                    )
                    wavFileInfo.set(wfi)
                    // This has a side affect of updating the axis ranges in the UI:
                    internalSetSpectrogramVisibleRange(FloatRange(0f, 1f), FloatRange(0f, 1f))

                    internalDoColourMappingAndRender(
                        settings.autoBnCEnabledViewer,
                        settings.autoBaselineEnabled)

                    submitFilePageToMl(p)

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
             * Prefer recomputing from current data. If that is not yet possible
             * (e.g. live buffer empty after start/resume), restore a compatible
             * session-cached baseline so correction is not missing until the next
             * zoom/reload.
             */
            if (autoBaselineRequired) {
                val t = measureTimeMillis {
                    if (p.calculateNoiseBaseline()) {
                        cacheNoiseBaselineFromPipeline(p)
                        rerenderRequired = true
                    } else if (tryRestoreNoiseBaseline(p)) {
                        rerenderRequired = true
                    }
                }
                // Timber.d("noise baseline apply took $t ms")
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

    private fun cacheNoiseBaselineFromPipeline(p: AbstractPipeline) {
        val baseline = p.copyNoiseBaseline() ?: return
        noiseBaselineSessionCache = NoiseBaselineCache(
            sampleRateHz = p.sampleRateHz(),
            frequencyBucketCount = baseline.size,
            baselineDb = baseline
        )
    }

    /**
     * Reinstall a session-cached baseline when sample rate and FFT bucket count still match.
     * Clears the cache if geometry is incompatible.
     */
    private suspend fun tryRestoreNoiseBaseline(p: AbstractPipeline): Boolean {
        val cache = noiseBaselineSessionCache ?: return false
        val bucketCount = p.noiseBaselineFrequencyBucketCount()
        if (cache.sampleRateHz != p.sampleRateHz() ||
            bucketCount == null ||
            cache.frequencyBucketCount != bucketCount
        ) {
            noiseBaselineSessionCache = null
            return false
        }
        return p.tryApplyNoiseBaseline(cache.baselineDb).also { applied ->
            if (applied) {
                Timber.i(
                    "Auto baseline: reused session noise baseline " +
                        "(sampleRateHz=${cache.sampleRateHz}, " +
                        "frequencyBuckets=${cache.frequencyBucketCount})"
                )
            }
        }
    }

    fun openLive(
        onFileWriterError: (String) -> Unit,
        liveInputSourceOverride: Int? = null,
    ) {
        liveFileWriterErrorHandler = onFileWriterError
        // Heavy processing in CPU worker thread:
        viewModelScope.launch(Dispatchers.Default) {
            val context: Context = getApplication()
            var liveConnectResult: LiveConnectResult? = null
            val liveInputSourceSetting =
                liveInputSourceOverride ?: settings.liveInputSource

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

                    liveConnectResult = connectLiveInputAndBuildPipeline(
                        pps,
                        context,
                        onFileWriterError,
                        resetLiveVisibleRanges = true,
                        liveInputSourceSetting = liveInputSourceSetting
                    )
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
                    liveConnectResult = LiveConnectResult(false, message)
                }
                catch (e: OutOfMemoryError) {
                    internalClosePipeline()

                    // Note: this is an error, not an exception, so needs its own catch.
                    diagnosticLogger.log { "Out of memory in openLive()" }

                    // Return the message to the UI for display to the user:
                    liveConnectResult = LiveConnectResult(false, oomMessage)
                }
            }

            // Do this suspending operation outside the critical section to avoid
            // blocking with the lock:
            require(liveConnectResult != null)
            liveConnectResult.let {
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

            var audioStartResult: LiveAudioStartResult? = null
            Timber.d("startAudio called")

            mutex.withLock {
                val sampleRateHz = pipeline?.sampleRateHz()
                if (sampleRateHz == null) {
                    Timber.w("startLiveAudio: no live pipeline; audio not started")
                    audioStartResult = LiveAudioStartResult(startedOK = false)
                } else {
                    val audio = preparePlaybackAudio(sampleRateHz)

                    when (effectiveLiveInputSource()) {
                        Settings.LiveInputSourceOptions.USB.value -> {
                            usbService.startAudio(
                                audio.heterodyne1kHz,
                                audio.heterodyne2kHz,
                                settings.audioBoostFactor,
                                audio.playbackMode,
                                audio.modeFactor,
                                settings.audioPitchHpfEnabled
                            )
                            audioOutputActive = true
                            if (audio.tracked)
                                autoHeterodyne.setDisplayedRefkHz(audio.heterodyne1kHz)
                            audioStartResult = LiveAudioStartResult(startedOK = true)
                        }

                        Settings.LiveInputSourceOptions.PHONE_MIC.value -> {
                            val started = usbService.startLiveInputAudio(
                                sampleRateHz,
                                audio.heterodyne1kHz,
                                audio.heterodyne2kHz,
                                settings.audioBoostFactor,
                                audio.playbackMode,
                                audio.modeFactor,
                                settings.audioPitchHpfEnabled
                            )
                            micCaptureService.liveAudioMonitorEnabled = started
                            audioOutputActive = started
                            if (started && audio.tracked)
                                autoHeterodyne.setDisplayedRefkHz(audio.heterodyne1kHz)
                            audioStartResult = LiveAudioStartResult(startedOK = started)
                        }

                        else -> {
                            Timber.w("Live audio monitor is not available for this input source")
                            audioStartResult = LiveAudioStartResult(startedOK = false)
                        }
                    }
                }
            }

            audioStartResult?.let {
                Timber.d("Sending result of start audios: $it")
                liveAudioStartChannel.send(it)
            }

            pipeline?.sampleRateHz()?.let { sampleRateHz ->
                startTrackedHeterodyneWorker(sampleRateHz)
                // BatCNN only while the live monitor is actually playing
                // smart heterodyne; add it here once output is active.
                if (audioOutputActive &&
                    settings.isSmartHeterodynePlayback(sampleRateHz)
                ) {
                    ensureMlClient(sampleRateHz, forceReset = true)
                }
            }

            Timber.i("startAudio end: runtime.{maxMemory, totalMemory, freeMemory) = " +
                    "${runtime.maxMemory() / 1024}, ${runtime.totalMemory() / 1024}, ${runtime.freeMemory() / 1024} KB")
        }
    }

    private fun onAudioProgress(position: Int) {
        if (position >= 0 && viewerSmartHet != ViewerSmartHet.Off)
            autoHeterodyne.onViewerPlayhead(position)
        audioProgressChannel.trySend(position)
    }

    fun startViewerAudio(sampleRateHz: Int) {
        viewModelScope.launch(Dispatchers.Default) {

            var audioStartResult: LiveAudioStartResult? = null
            var smartPlayback: Pair<ShortArray, HORange>? = null
            Timber.d("startViewerAudio called")

            mutex.withLock {

                pipeline?.getVisibleRawDataBuffer(timeAxisRangeFlow)?.let { visibleRawData ->

                    Timber.d("Visible raw data range: ${visibleRawData.second}")

                    val audio = preparePlaybackAudio(sampleRateHz)
                    usbService.startAudioFromBuffer(
                        audio.heterodyne1kHz,
                        audio.heterodyne2kHz,
                        settings.audioBoostFactor,
                        visibleRawData, settings.loopedAudioPlayback,
                        sampleRateHz,
                        audio.playbackMode,
                        audio.modeFactor,
                        settings.audioPitchHpfEnabled,
                        ::onAudioProgress
                    )

                    audioOutputActive = true
                    if (audio.tracked)
                        autoHeterodyne.setDisplayedRefkHz(audio.heterodyne1kHz)
                    if (settings.isSmartHeterodynePlayback(sampleRateHz)) {
                        // Same lock as stopAudio, and only while this mutex is held,
                        // so a stop that is waiting cannot clear the flag first.
                        synchronized(mlLock) {
                            viewerSmartHet = ViewerSmartHet.Playing
                        }
                        smartPlayback = visibleRawData
                    }
                    audioStartResult = LiveAudioStartResult(startedOK = true)
                }
            }

            audioStartResult?.let {
                Timber.d("Sending result of start audio: $it")
                liveAudioStartChannel.send(it)
            }

            startTrackedHeterodyneWorker(sampleRateHz)

            smartPlayback?.let { (buffer, range) ->
                if (viewerSmartHet == ViewerSmartHet.Playing)
                    attachViewerSmartHeterodyne(sampleRateHz, buffer, range)
            }
        }
    }

    /**
     * Reference frequencies and whether the local oscillator is tracked.
     * Resets the tracker when the mode tracks.
     */
    private data class PlaybackAudioSetup(
        val playbackMode: Int,
        val modeFactor: Int,
        val heterodyne1kHz: Int,
        val heterodyne2kHz: Int?,
        val tracked: Boolean,
    )

    private suspend fun preparePlaybackAudio(sampleRateHz: Int): PlaybackAudioSetup {
        val playbackMode = settings.effectiveAudioPlaybackMode(sampleRateHz)
        val modeFactor =
            if (playbackMode == Settings.AudioPlaybackModeOptions.TIME_EXPANSION.value)
                Settings.AudioTimeExpansionFactorOptions.coerce(
                    settings.audioTimeExpansionFactor
                )
            else
                Settings.AudioPitchRatioOptions.coerceForSampleRate(
                    settings.audioPitchRatio, sampleRateHz
                )
        val tracked = settings.isTrackedHeterodynePlayback(sampleRateHz)
        if (tracked)
            autoHeterodyne.resetTracker()
        val heterodyne1kHz =
            if (tracked) autoHeterodyne.initialRefkHz()
            else settings.coerceHeterodyneRefkHz(settings.heterodyneRef1kHz)
        val heterodyne2kHz =
            if (playbackMode == Settings.AudioPlaybackModeOptions.DUAL_HETERODYNE.value)
                settings.coerceHeterodyneRefkHz(settings.heterodyneRef2kHz)
            else
                null
        return PlaybackAudioSetup(
            playbackMode,
            modeFactor,
            heterodyne1kHz,
            heterodyne2kHz,
            tracked,
        )
    }

    /** Start the LO worker. Auto heterodyne also refreshes the viewer bucket cache. */
    private suspend fun startTrackedHeterodyneWorker(sampleRateHz: Int) {
        if (!audioOutputActive || !settings.isTrackedHeterodynePlayback(sampleRateHz))
            return
        if (settings.isAutoTunedHeterodynePlayback(sampleRateHz))
            autoHeterodyne.refreshViewerCache()
        autoHeterodyne.startWorker()
    }

    /**
     * Viewer smart heterodyne: add BatCNN and analyse the buffer now playing.
     * Peak epochs are seconds from the first sample of [range]. [stopAudio]
     * removes BatCNN and restores ordinary file-page analysis.
     */
    private suspend fun attachViewerSmartHeterodyne(
        sampleRateHz: Int,
        buffer: ShortArray,
        range: HORange,
    ) {
        if (viewerSmartHet != ViewerSmartHet.Playing || range.length <= 0)
            return
        val app = getApplication<Application>()
        MlCatalog.ensureInitialized(app)
        if (!MlActiveModels.liveSystemAccepts(app.assets, sampleRateHz)) {
            Timber.w(
                "Viewer smart heterodyne: BatCNN will not ingest audio at $sampleRateHz Hz"
            )
            return
        }
        autoHeterodyne.beginPlayheadGatedPeaks(range.start, sampleRateHz)
        if (abortViewerSmartHetIfStopped())
            return
        // mlLock is reentrant, so this check and the client swap cannot interleave
        // with [stopAudio] deciding whether to restore the file page.
        val loaded = synchronized(mlLock) {
            if (viewerSmartHet != ViewerSmartHet.Playing) {
                false
            } else {
                ensureMlClient(
                    sampleRateHz,
                    forceReset = true,
                    overflowPolicy = MlOverflowPolicy.QueueAll,
                    summaryMode = MlSummaryMode.Viewer,
                    preferFileGuanoLocation = true,
                    clearSummary = false,
                )
                val client = mlClient
                if (client == null) {
                    false
                } else {
                    viewerSmartHet = ViewerSmartHet.ModelLoaded
                    client.submit(
                        buffer,
                        range.start,
                        range.length,
                        observedAtEpochSec = 0.0,
                        isLast = true,
                    )
                    Timber.i(
                        "Viewer smart heterodyne: BatCNN analysing ${range.length} samples " +
                            "at $sampleRateHz Hz"
                    )
                    true
                }
            }
        }
        if (!loaded)
            autoHeterodyne.clearPlayheadGate()
    }

    /** Clear the playhead gate when playback ended before BatCNN was added. */
    private suspend fun abortViewerSmartHetIfStopped(): Boolean {
        if (viewerSmartHet != ViewerSmartHet.Off)
            return false
        autoHeterodyne.clearPlayheadGate()
        return true
    }

    fun stopAudio() {
        viewModelScope.launch(Dispatchers.Default) {
            // Model mutex first, then mlLock. pause and settings updates hold
            // the model mutex and then take mlLock; the other order deadlocks.
            var restoreViewerMl = false
            var liveSmartHetSampleRateHz: Int? = null
            mutex.withLock {
                restoreViewerMl = synchronized(mlLock) {
                    val restore = viewerSmartHet == ViewerSmartHet.ModelLoaded
                    viewerSmartHet = ViewerSmartHet.Off
                    restore
                }
                val liveRate = (pipeline as? LiveUSBPipeline)?.sampleRateHz()
                if (audioOutputActive &&
                    liveRate != null &&
                    settings.isSmartHeterodynePlayback(liveRate)
                ) {
                    liveSmartHetSampleRateHz = liveRate
                }
                autoHeterodyne.stopWorker()
                audioOutputActive = false
                autoHeterodyne.resetTracker()
                micCaptureService.liveAudioMonitorEnabled = false
                usbService.stopAudio()
            }
            if (restoreViewerMl)
                pipeline?.let { submitFilePageToMl(it) }
            else liveSmartHetSampleRateHz?.let { sampleRateHz ->
                // Drop BatCNN now that live smart-heterodyne output has stopped.
                ensureMlClient(sampleRateHz, forceReset = true)
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
                activeLiveInputSource?.pause()
                // End the current ML stream and drop liveSystem models until
                // acquisition resumes.
                liveMlAccepting = false
                flushMlClientFinal()
                (pipeline as? LiveUSBPipeline)?.let { live ->
                    if (settings.isSmartHeterodyneSelected()) {
                        ensureMlClient(live.sampleRateHz(), forceReset = true)
                    }
                }
            }
        }
    }

    fun resumeLiveStream() {
        viewModelScope.launch(Dispatchers.Default + CoroutineName("openLive coroutine")) {
            var reconnectResult: LiveConnectResult? = null

            mutex.withLock {
                var sourceChanged = false

                // We need to (re)connect if what is connected no longer matches the preferred source
                // in settings. This covers: the user changed the source while paused, an override
                // session (running a fallback because the preferred source was missing at start), and
                // the user changing which internal microphone to use while paused.
                val phoneMic = Settings.LiveInputSourceOptions.PHONE_MIC.value
                val internalMicChanged =
                    connectedLiveInputSource == phoneMic &&
                        settings.liveInputSource == phoneMic &&
                        micCaptureService.preferredInternalMicId != settings.internalMicId

                if (connectedLiveInputSource != null &&
                    (connectedLiveInputSource != settings.liveInputSource || internalMicChanged)
                ) {
                    val handler = liveFileWriterErrorHandler
                    if (handler == null) {
                        Timber.w("Cannot reconnect live input: no file writer error handler")
                        reconnectResult = LiveConnectResult(
                            connectedOK = false,
                            errorMessage = "Unable to switch live input source."
                        )
                        return@withLock
                    }
                    sourceChanged = true

                    if (liveInputSourceOverrideSession) {
                        // Running on a fallback source. Re-probe the preferred source and switch to
                        // it if it is now available; otherwise keep running on the fallback source
                        // without surfacing an error (so a missing USB mic doesn't stop acquisition).
                        val fallbackSource = connectedLiveInputSource!!
                        val preferredResult = reconnectLiveInputSource(handler, settings.liveInputSource)
                        if (preferredResult.connectedOK) {
                            reconnectResult = preferredResult
                        } else {
                            Timber.i(
                                "Preferred live input ${settings.liveInputSource} still unavailable; " +
                                    "continuing on fallback source $fallbackSource"
                            )
                            val fallbackResult = reconnectLiveInputSource(handler, fallbackSource)
                            if (!fallbackResult.connectedOK) {
                                // The fallback failed too, so this is a genuine failure to report.
                                reconnectResult = fallbackResult
                                return@withLock
                            }
                            // Continue silently on the fallback source (no UI churn or re-prompt).
                        }
                    } else {
                        // The user changed the preferred source in settings while paused.
                        reconnectResult = reconnectLiveInputSource(handler)
                        if (!reconnectResult!!.connectedOK) {
                            return@withLock
                        }
                    }
                }

                pipeline?.let { p ->
                    p.resetState()

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

                    internalSetSpectrogramVisibleRange(
                        visibleTimeRange,
                        frequencyVisibleRangeFlow.value
                    )

                    // Do a full pipeline rebuild so that any new settings take effect:
                    reload(settings, null, false)

                    // A fresh connect already streams; only resume a paused existing source.
                    if (!sourceChanged) {
                        activeLiveInputSource?.resume()
                    }

                    mlSummaryAccumulator.clear(MlSummaryMode.Live)
                    liveMlAccepting = true
                    ensureMlClient(p.sampleRateHz(), forceReset = true)
                }
            }

            reconnectResult?.let { result ->
                Timber.d("Sending result of live reconnect: $result")
                liveConnectChannel.send(result)
            }
        }
    }

    fun setHeterodyne(kHz1: Int, kHz2: Int?) {
        viewModelScope.launch(Dispatchers.Default) {
            mutex.withLock {
                val lo1 = settings.coerceHeterodyneRefkHz(kHz1)
                val lo2 = kHz2?.let { settings.coerceHeterodyneRefkHz(it) }
                usbService.setHeterodyne(lo1, lo2)
            }
        }
    }

    fun onLiveSpectrumBucketsForAutoHeterodyne(buckets: IntRange) {
        autoHeterodyne.onLiveSpectrumBuckets(buckets)
    }

    fun updateAutoHeterodyneAtRawSample(sampleIndex: Int) {
        autoHeterodyne.updateAtRawSample(sampleIndex)
    }

    fun pipelineSampleRateHz(): Int? = pipeline?.sampleRateHz()

    suspend fun internalClosePipeline() {

        // Stop Auto Id and abandon any waiting work (do not flush — close should not
        // keep analysing after the user leaves the viewer/live session).
        liveMlAccepting = false
        viewerSmartHet = ViewerSmartHet.Off
        discardQueuedMlWork(MlSummaryMode.Live)

        // Finish with the file writer:
        fileWriter?.shutdown()
        fileWriter = null

        activeLiveInputSource?.disconnect()
        activeLiveInputSource = null
        setConnectedLiveInputSource(null)

        pipeline?.shutdown()
        pipeline = null

        wavFileReader?.close()
        wavFileReader = null

        wavFileInfo.set(null)

        mutableDetailsTextFlow.value = null
        synchronized(mlLock) {
            // Attach may have set ModelLoaded after the early Off above.
            viewerSmartHet = ViewerSmartHet.Off
            clearMlClientLocked()
            // Drop results already delivered but not yet merged, then clear summary again
            // so an in-flight chunk cannot repopulate the panel after close.
            mlSummaryAccumulator.clear(MlSummaryMode.Live)
        }
        mutableMicrophoneVolumeParametersFlow.value = null
        mutableMicrophoneGainFlow.value = null

        resetRanges()
    }

    /** Caller must hold [mlLock]. */
    private fun clearMlClientLocked() {
        mlBufferingCollectJob?.cancel()
        mlBufferingCollectJob = null
        mutableMlBufferingFlow.value = false
        mlBusyCollectJob?.cancel()
        mlBusyCollectJob = null
        mutableMlBusyFlow.value = false
        mlWillAcceptAudioCollectJob?.cancel()
        mlWillAcceptAudioCollectJob = null
        mutableMlWillAcceptAudioFlow.value = false
        mlUserModelsActiveCollectJob?.cancel()
        mlUserModelsActiveCollectJob = null
        mutableMlUserModelsActiveFlow.value = false
        mlResultGeneration.incrementAndGet()
        mlClient?.shutdown()
        mlClient = null
        mlClientSampleRateHz = null
    }

    /** Caller must hold [mlLock]. Collect ingest/busy signals into UI flows. */
    private fun attachMlActivityCollectorsLocked(client: MlClient) {
        mlBufferingCollectJob?.cancel()
        mlBufferingCollectJob = viewModelScope.launch {
            client.isBuffering.collect { buffering ->
                mutableMlBufferingFlow.value = buffering
            }
        }
        mlBusyCollectJob?.cancel()
        mlBusyCollectJob = viewModelScope.launch {
            client.isBusy.collect { busy ->
                mutableMlBusyFlow.value = busy
            }
        }
        mlWillAcceptAudioCollectJob?.cancel()
        mlWillAcceptAudioCollectJob = viewModelScope.launch {
            client.willAcceptAudioFlow.collect { accepts ->
                mutableMlWillAcceptAudioFlow.value = accepts
                // Drop on-screen hits when the model cannot use this sample rate
                // (or after client teardown). Otherwise rows from the previous
                // model linger after switching to an inapplicable one.
                if (!accepts) {
                    mlSummaryAccumulator.clear(mlSummaryModeFlow.value)
                }
            }
        }
        mlUserModelsActiveCollectJob?.cancel()
        mlUserModelsActiveCollectJob = viewModelScope.launch {
            client.userModelsWillAcceptFlow.collect { active ->
                mutableMlUserModelsActiveFlow.value = active
            }
        }
    }

    /**
     * Smart heterodyne is selected and playback that needs BatCNN is running:
     * the live monitor is outputting, or viewer smart-het playback has started.
     */
    private fun shouldIncludeLiveSystemModels(): Boolean =
        settings.isSmartHeterodyneSelected() &&
            (viewerSmartHet != ViewerSmartHet.Off ||
                (audioOutputActive && liveMlAccepting))

    /**
     * BatCNN presence peaks tune smart heterodyne. The labels are bins,
     * so they stay out of the species summary and recording metadata.
     */
    private fun forwardLiveSystemPeaks(result: MlResult) {
        val peaks = result.detections.mapNotNull { detection ->
            val hz = detection.fmaxeHz ?: return@mapNotNull null
            val timeSec = detection.timeSec ?: return@mapNotNull null
            AutoHeterodyneController.FmaxePeak(
                result.observedAtEpochSec + timeSec,
                hz,
            )
        }
        if (peaks.isNotEmpty())
            autoHeterodyne.onFmaxePeaks(peaks)
    }

    /**
     * Auto Id hits: overlay, GUANO species notes, and classifier auto-trigger.
     */
    private fun recordSpeciesResult(
        result: MlResult,
        resultDesc: MlModelDescriptor?,
        primary: MlModelDescriptor?,
    ) {
        mlSummaryAccumulator.submitResult(result)
        if (result.detections.isEmpty())
            return
        fileWriter?.noteClassifierDetections(
            result.observedAtEpochSec,
            result.detections,
        )
        if (!settings.isClassifierAutoTrigger() || primary == null)
            return
        val top = result.detections.first()
        val windowDesc = resultDesc ?: primary
        val windowSec = Settings.classifierWindowDurationSec(
            windowDesc.windowSamples,
            windowDesc.sampleRateHz,
        ) ?: Settings.CLASSIFIER_WINDOW_FALLBACK_SEC
        val windowEnd = result.observedAtEpochSec + windowSec
        Timber.i(
            "CLF_REC_DBG classifier window %s → %s (%d detection(s))",
            FileWriter.wallClock(result.observedAtEpochSec),
            FileWriter.wallClock(windowEnd),
            result.detections.size,
        )
        fileWriter?.triggerClassifier(
            result.observedAtEpochSec,
            top.labelKey,
        )
    }

    /**
     * Ensure an [MlClient] exists and has been [MlClient.reset] for [sampleRateHz].
     * The client is always fed audio. Classifiers and resampling run for the
     * checked Auto Id families when Auto Id is enabled. `liveSystem` models
     * are added when [shouldIncludeLiveSystemModels] is true. Results are queued
     * and merged into [mlSummaryFlow].
     *
     * @param forceReset if true, always [MlClient.reset] even when the rate is unchanged
     *   (e.g. starting analysis of a new file page).
     * @param overflowPolicy live [MlOverflowPolicy.DropIfFull] vs viewer [MlOverflowPolicy.QueueAll]
     * @param summaryMode Auto Id summary TTL policy applied when the client is reset
     * @param preferFileGuanoLocation when true (viewer), use GUANO `Loc Position` if
     *   present, otherwise [locationFlow]; when false (live), use [locationFlow] only
     * @param clearSummary when false, keep the current Auto Id overlay across a
     *   reset. Viewer smart-heterodyne playback uses this so species labels stay
     *   up while BatCNN is added.
     */
    fun ensureMlClient(
        sampleRateHz: Int,
        forceReset: Boolean = false,
        overflowPolicy: MlOverflowPolicy = MlOverflowPolicy.DropIfFull,
        summaryMode: MlSummaryMode = MlSummaryMode.Live,
        preferFileGuanoLocation: Boolean = false,
        clearSummary: Boolean = true,
    ) {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        synchronized(mlLock) {
            MlCatalog.ensureInitialized(getApplication())
            val assets = getApplication<Application>().assets
            val includeLiveSystem = shouldIncludeLiveSystemModels()
            val descriptors = if (settings.isAutoIdEnabled() || includeLiveSystem) {
                MlActiveModels.resolve(
                    assets,
                    settings,
                    includeLiveSystemModels = includeLiveSystem,
                )
            } else {
                emptyList()
            }
            // Keep the stored Auto Id choice on a user-selected model. A
            // liveSystem model must not become the persisted primary.
            val settingsPrimary = descriptors.firstOrNull { !it.liveSystem }
            if (settingsPrimary != null && settings.autoIdModelId != settingsPrimary.id) {
                settings.autoIdModelId = settingsPrimary.id
                settings.autoIdLanguage =
                    settings.autoIdFamilyPrefs[settingsPrimary.familyId]?.language
                        ?: settings.autoIdLanguage
            }
            val primary = descriptors.firstOrNull()
            val primaryId = primary?.id ?: ""
            val activeIds = descriptors.map { it.id }
            val existing = mlClient
            if (existing != null &&
                (existing.modelId != primaryId || existing.activeModelIds() != activeIds)
            ) {
                clearMlClientLocked()
            }
            val descriptorsById = descriptors.associateBy { it.id }
            val client = mlClient ?: run {
                val generation = mlResultGeneration.get()
                MlClient(
                    getApplication(),
                    primaryId,
                    descriptors,
                ) { result ->
                    // Drop late results from a previous client (model switch /
                    // teardown) and any results while this rate is rejected.
                    if (generation == mlResultGeneration.get() &&
                        mutableMlWillAcceptAudioFlow.value
                    ) {
                        val resultDesc = descriptorsById[result.modelId]
                        if (resultDesc?.liveSystem == true)
                            forwardLiveSystemPeaks(result)
                        else
                            recordSpeciesResult(result, resultDesc, primary)
                    }
                }.also {
                    mlClient = it
                    attachMlActivityCollectorsLocked(it)
                }
            }
            client.setOverflowPolicy(overflowPolicy)
            applyMlClientLabelAndSuppressionsLocked(client, settings)
            val anyGeo = descriptors.any { it.resourcePaths.geoModelRelativeOrNull != null }
            if (anyGeo) {
                if (forceReset) {
                    client.clearGeoSnapshot()
                }
                resolveAutoIdGeoSnapshot(preferFileGuanoLocation)?.let { snap ->
                    client.setGeoSnapshot(snap)
                }
            }
            if (forceReset || mlClientSampleRateHz != sampleRateHz) {
                client.reset(sampleRateHz)
                mlClientSampleRateHz = sampleRateHz
                if (clearSummary)
                    mlSummaryAccumulator.clear(summaryMode)
            }
        }
    }

    /** Apply per-model language and suppressions from [settings] (caller holds [mlLock]). */
    private fun applyMlClientLabelAndSuppressionsLocked(client: MlClient, settings: Settings) {
        for (modelId in client.activeModelIds()) {
            val familyId = Settings.familyIdOfModelId(modelId)
            val language = settings.autoIdFamilyPrefs[familyId]?.language
                ?: settings.autoIdLanguage
            client.setLabelLanguage(modelId, language)
            client.setSuppressions(modelId, settings.suppressionsFor(modelId))
        }
    }

    /**
     * BirdNET geo query: optionally GUANO `Loc Position` from the open file, else
     * the current [locationFlow] fix. Returns null if neither is available.
     */
    private fun resolveAutoIdGeoSnapshot(
        preferFileGuano: Boolean,
    ): BirdNetModel.GeoSnapshot? {
        val week = BirdNetModel.weekOfYear()
        if (preferFileGuano) {
            parseGuanoLocPosition()?.let { (lat, lon) ->
                return BirdNetModel.GeoSnapshot(
                    latitude = lat,
                    longitude = lon,
                    week = week,
                    source = BirdNetModel.GeoSource.GUANO,
                )
            }
        }
        val loc = locationMutableFlow.value ?: return null
        return BirdNetModel.GeoSnapshot(
            latitude = loc.latitude.toFloat(),
            longitude = loc.longitude.toFloat(),
            week = week,
            source = BirdNetModel.GeoSource.LIVE_GPS,
        )
    }

    /**
     * Parse GUANO `Loc Position` as `latitude longitude` (same order as
     * [FileWriter]). Returns null if missing or invalid.
     */
    private fun parseGuanoLocPosition(): Pair<Float, Float>? {
        val raw = wavFileInfo.get()
            ?.guanoChunkInfo
            ?.entriesMap
            ?.get("loc position")
            ?.value
            ?: return null
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val lat = parts[0].toFloatOrNull() ?: return null
        val lon = parts[1].toFloatOrNull() ?: return null
        if (lat !in -90f..90f || lon !in -180f..180f) return null
        return lat to lon
    }

    /** Current ML client, if [ensureMlClient] has been called. */
    fun mlClientOrNull(): MlClient? = synchronized(mlLock) { mlClient }

    /**
     * Offer a one-shot BirdNET geo snapshot from [location] if an Auto Id client
     * is active. Ignored after the first accepted snapshot.
     */
    private fun maybeOfferBirdNetGeoSnapshot(location: Location) {
        synchronized(mlLock) {
            val client = mlClient ?: return
            client.setGeoSnapshot(
                BirdNetModel.GeoSnapshot(
                    latitude = location.latitude.toFloat(),
                    longitude = location.longitude.toFloat(),
                    week = BirdNetModel.weekOfYear(),
                    source = BirdNetModel.GeoSource.LIVE_GPS,
                )
            )
        }
    }

    /** True when live capture should feed the ML pipeline. */
    fun shouldSubmitLiveAudioToMl(): Boolean = liveMlAccepting

    /**
     * Cheap path from [USBSourceStep]: submit samples to [MlClient] when Auto Id is
     * accepting. Does not take [mutex].
     */
    fun maybeSubmitLiveAudioToMl(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        observedAtEpochSec: Double = System.currentTimeMillis() / 1000.0,
    ) {
        if (!shouldSubmitLiveAudioToMl() || count <= 0) return
        synchronized(mlLock) {
            if (!shouldSubmitLiveAudioToMl()) return
            mlClient?.submit(
                buffer,
                offset,
                count,
                observedAtEpochSec = observedAtEpochSec,
                isLast = false,
            )
        }
    }

    /**
     * Drop any waiting ML work and clear the on-screen summary.
     * An in-flight chunk may still finish; new page submit uses [forceReset].
     */
    private fun discardQueuedMlWork(summaryMode: MlSummaryMode) {
        mlSummaryAccumulator.clear(summaryMode)
        synchronized(mlLock) {
            mlClient?.clearQueue()
        }
    }

    /**
     * When [pipeline] is a file viewer with assigned raw PCM, reset the ML
     * client and submit that page's samples. With no classifiers enabled the
     * audio is still ingested and is not resampled.
     *
     * Intended for file open, left/right paging, and classifier changes — not for
     * zoom or other view-only reloads.
     */
    private fun submitFilePageToMl(pipeline: AbstractPipeline) {
        if (pipeline !is FileViewerPipeline) return
        // Playback owns the client until [stopAudio] restores this page.
        if (viewerSmartHet != ViewerSmartHet.Off) return
        val sampleRateHz = pipeline.sampleRateHz()
        if (sampleRateHz <= 0) return
        val (buffer, range) = pipeline.getAssignedRawDataBuffer() ?: return

        discardQueuedMlWork(MlSummaryMode.Viewer)
        ensureMlClient(
            sampleRateHz,
            forceReset = true,
            overflowPolicy = MlOverflowPolicy.QueueAll,
            summaryMode = MlSummaryMode.Viewer,
            preferFileGuanoLocation = true,
        )
        val observedAtEpochSec = System.currentTimeMillis() / 1000.0
        synchronized(mlLock) {
            mlClient?.submit(
                buffer,
                range.start,
                range.length,
                observedAtEpochSec = observedAtEpochSec,
                isLast = true,
            )
        }
        Timber.d(
            "Submitted file page to MlClient: ${range.length} samples at $sampleRateHz Hz " +
                "(range $range)"
        )
    }

    /** Pad/process any partial ML chunk ([MlClient.submit] with [isLast] true). */
    private fun flushMlClientFinal() {
        synchronized(mlLock) {
            val client = mlClient ?: return
            if (mlClientSampleRateHz == null) return
            client.submit(
                ShortArray(0),
                0,
                0,
                observedAtEpochSec = System.currentTimeMillis() / 1000.0,
                isLast = true,
            )
        }
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
                    if (newSettings.autoHeterodyneLoMinKhz != it.autoHeterodyneLoMinKhz ||
                        newSettings.autoHeterodyneLoMaxKhz != it.autoHeterodyneLoMaxKhz
                    ) {
                        autoHeterodyne.resetActivityState()
                    }
                }

                setAudioBoost(settings.audioBoostFactor)
                if (appliedAudioAgcEnabled != newSettings.audioAGCEnabled) {
                    appliedAudioAgcEnabled = newSettings.audioAGCEnabled
                    usbService.setAgcEnabled(newSettings.audioAGCEnabled)
                }

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
                // Force the next settled size to rebuild, even if it matches a previous generation:
                lastBuiltSpectrogramSizeDp = null
                lastBuiltAmplitudeSizeDp = null
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
                /*
                 * Debounce the rebuild. Transient relayouts - notably the system microphone
                 * privacy indicator briefly changing the window insets when the internal mic
                 * starts - make the pane size wobble and settle back to its original value.
                 * Rebuilding on each intermediate size resets a live spectrogram to the start,
                 * so we wait for the size to settle and only rebuild if it has actually changed
                 * from the size the pipeline was last built for.
                 */
                sizeChangeJob?.cancel()
                sizeChangeJob = viewModelScope.launch(
                    Dispatchers.Default + CoroutineName("onRescale coroutine")
                ) {
                    delay(sizeChangeDebounceMs)
                    mutex.withLock {
                        val settledSpectrogramSize = this@UIModel.spectrogramSizeDp
                        val settledAmplitudeSize = this@UIModel.amplitudeSizeDp
                        if (settledSpectrogramSize != null &&
                            (settledSpectrogramSize != lastBuiltSpectrogramSizeDp ||
                                settledAmplitudeSize != lastBuiltAmplitudeSizeDp)
                        ) {
                            lastBuiltSpectrogramSizeDp = settledSpectrogramSize
                            lastBuiltAmplitudeSizeDp = settledAmplitudeSize
                            Timber.d(
                                "onUISizeChange applying UI size: $generation " +
                                    "$settledSpectrogramSize, $settledAmplitudeSize"
                            )
                            reload(settings, rawPageRange, autoBnCRequiredFlow.value)
                        }
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
                    // Stop analysing the previous page immediately (reload can take a while).
                    // Leave a smart-heterodyne playback client alone; stop removes BatCNN.
                    if (viewerSmartHet == ViewerSmartHet.Off)
                        discardQueuedMlWork(MlSummaryMode.Viewer)
                    reload(
                        settings,
                        rawPageRange,
                        autoBnCRequiredFlow.value,
                        resetVisibleRange = true
                    )
                    // New file page only: feed Auto Id (not on zoom/FFT-only reloads).
                    pipeline?.let { p -> submitFilePageToMl(p) }
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
                    val sampleRateHz = p.sampleRateHz()
                    if (audioOutputActive &&
                        sampleRateHz != null &&
                        settings.isAutoTunedHeterodynePlayback(sampleRateHz)
                    ) {
                        autoHeterodyne.resetActivityState()
                        autoHeterodyne.refreshViewerCache()
                    }
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
                val pageSamples =
                    (pipelineParameters.dataPageTimeSpanS * sampleRate).toInt()
                newFftParameters = AbstractPipeline.calculateFftParameters(
                    pipelineParameters, screenFactors, sampleRate, pageSamples
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
    suspend fun panSpectrogramVisibleRange(
        displacement: Offset,
        size: IntSize,
        clampX: Boolean,
        axis: SpectrogramGestureAxis = SpectrogramGestureAxis.FREE,
    ) {
        mutex.withLock {
            // Map screen pixels to logical source bitmap values scaled to within
            // the source region in use:
            var logicalSrcDeltaX: Float = -displacement.x / size.width * (
                    timeVisibleRangeFlow.value.endInclusive - timeVisibleRangeFlow.value.start)
            var logicalSrcDeltaY: Float = -displacement.y / size.height * (
                    frequencyVisibleRangeFlow.value.endInclusive - frequencyVisibleRangeFlow.value.start)

            when (axis) {
                SpectrogramGestureAxis.TIME -> logicalSrcDeltaY = 0f
                SpectrogramGestureAxis.FREQUENCY -> logicalSrcDeltaX = 0f
                SpectrogramGestureAxis.FREE -> Unit
            }

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
        size: IntSize,
        clampX: Boolean,
        axis: SpectrogramGestureAxis = SpectrogramGestureAxis.FREE,
    ) {
        mutex.withLock {
            var (scaleFactorX, scaleFactorY) = Pair(1f, 1f)

            val (dxStart, dyStart) = Pair(
                abs(previousP1.x - previousP2.x),
                abs(previousP1.y - previousP2.y)
            )
            val (dxNow, dyNow) = Pair(abs(p1.x - p2.x), abs(p1.y - p2.y))

            val minimumStartDelta = 30f
            when (axis) {
                SpectrogramGestureAxis.TIME ->
                    scaleFactorX = dxNow / maxOf(dxStart, minimumStartDelta)

                SpectrogramGestureAxis.FREQUENCY ->
                    scaleFactorY = dyNow / maxOf(dyStart, minimumStartDelta)

                SpectrogramGestureAxis.FREE -> {
                    if (abs(dxNow - dxStart) > abs(dyNow - dyStart))
                        scaleFactorX = dxNow / maxOf(dxStart, minimumStartDelta)
                    else
                        scaleFactorY = dyNow / maxOf(dyStart, minimumStartDelta)
                }
            }

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
        graph: GraphBase,
        displacement: Offset,
        size: IntSize,
        clampX: Boolean,
        axis: SpectrogramGestureAxis = SpectrogramGestureAxis.FREE,
    ) {
        mutex.withLock {
            val xDstLogical: Float = displacement.x / size.width
            val yDstLogical: Float = displacement.y / size.height
            val xSrcLogical: Float =
                logicalScaleToRange(xDstLogical, timeVisibleRangeFlow.value)
            val ySrcLogical: Float =
                logicalScaleToRange(yDstLogical, frequencyVisibleRangeFlow.value)

            val deltaX = timeVisibleRangeFlow.value.mean() - xSrcLogical
            val deltaY = frequencyVisibleRangeFlow.value.mean() - ySrcLogical

            var xRangeNew = timeVisibleRangeFlow.value
            if (axis != SpectrogramGestureAxis.FREQUENCY && !clampX) {
                val constrainedXOffset =
                    constrainOffsetForRange(-deltaX, timeVisibleRangeFlow.value)
                xRangeNew = enforceNonZeroRange(
                    timeVisibleRangeFlow.value.addOffset(constrainedXOffset)
                )
            }

            var yRangeNew = frequencyVisibleRangeFlow.value
            if (axis != SpectrogramGestureAxis.TIME) {
                val constrainedYOffset =
                    constrainOffsetForRange(-deltaY, frequencyVisibleRangeFlow.value)
                yRangeNew = enforceNonZeroRange(
                    frequencyVisibleRangeFlow.value.addOffset(constrainedYOffset)
                )
            }

            internalSetSpectrogramVisibleRange(xRangeNew, yRangeNew)

            if (!clampX)
                graph.onVisibleRangeChange(autoBnCRequiredFlow.value)

            triggerBitblt()
        }
    }

    suspend fun onDoubleTap(
        graph: GraphBase,
        clampX: Boolean,
        shouldAutoBnC: Boolean,
        axis: SpectrogramGestureAxis = SpectrogramGestureAxis.FREE,
    ) {
        mutex.withLock {
            val xRange = when (axis) {
                SpectrogramGestureAxis.FREQUENCY -> timeVisibleRangeFlow.value
                else -> if (clampX) timeVisibleRangeFlow.value else FloatRange(0f, 1f)
            }
            val yRange = when (axis) {
                SpectrogramGestureAxis.TIME -> frequencyVisibleRangeFlow.value
                else -> FloatRange(0f, 1f)
            }
            internalSetSpectrogramVisibleRange(xRange, yRange)

            if (!clampX)
                graph.onVisibleRangeChange(shouldAutoBnC)

            triggerBitblt()
        }
    }
}