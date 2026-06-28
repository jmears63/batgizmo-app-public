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

package org.batgizmo.app.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.batgizmo.app.LiveDataBridge
import timber.log.Timber

/**
 * Captures mono PCM from the device microphone and posts buffers to [LiveDataBridge].
 */
class MicCaptureService(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        /** Read interval; ~40 Hz matches the USB live update cadence. */
        private const val READ_CHUNK_MS = 10

        /** Sentinel for [preferredInternalMicId] meaning "let the app choose". */
        const val AUTOMATIC_MIC_ID = ""

        // AudioDeviceInfo.TYPE_BUILTIN_BACK_MIC; the constant is only defined from API 31.
        private const val TYPE_BUILTIN_BACK_MIC = 37
    }

    /**
     * A selectable internal microphone. [id] is a stable descriptor suitable for persistence and
     * matching (the dynamically assigned AudioDeviceInfo.id is deliberately not used, as it is not
     * stable across sessions). [label] is a human-friendly name for the UI.
     */
    data class MicOption(val id: String, val label: String)

    private val mutex = Mutex()
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var paused = false
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** Stable descriptor of the microphone the user chose, or [AUTOMATIC_MIC_ID] for automatic. */
    @Volatile
    var preferredInternalMicId: String = AUTOMATIC_MIC_ID

    @Volatile
    var liveAudioMonitorEnabled: Boolean = false

    var feedLiveAudioSamples: ((ShortArray, Int, Int) -> Unit)? = null

    val sampleRateHz: Int
        get() = SAMPLE_RATE_HZ

    private fun builtInInputDevices(): List<AudioDeviceInfo> {
        val builtInTypes = mutableSetOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builtInTypes.add(TYPE_BUILTIN_BACK_MIC)
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { device ->
                device.type in builtInTypes &&
                    (device.sampleRates.isEmpty() || SAMPLE_RATE_HZ in device.sampleRates)
            }
    }

    /**
     * A stable identifier for a device, used for persistence and matching. The runtime
     * AudioDeviceInfo.id is reassigned between sessions, so we derive a descriptor from the
     * device type and (where available) its address, which are stable.
     */
    private fun deviceDescriptor(device: AudioDeviceInfo): String =
        "${device.type}:${device.address.orEmpty()}"

    private fun deviceLabel(device: AudioDeviceInfo): String =
        when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            TYPE_BUILTIN_BACK_MIC -> "Back microphone"
            else -> "Microphone"
        }

    /**
     * The internal microphones that can be selected. The first entry is always "Automatic", which
     * lets the app choose. Returns at most the built-in mics the platform exposes; on many devices
     * this is a single entry.
     */
    fun availableInternalMics(): List<MicOption> {
        val automatic = MicOption(AUTOMATIC_MIC_ID, "Automatic")

        val devices = builtInInputDevices().map { device ->
            MicOption(deviceDescriptor(device), deviceLabel(device))
        }

        // Disambiguate duplicate labels (e.g. several mics of the same type with no address):
        val deduped = devices
            .groupBy { it.label }
            .flatMap { (_, group) ->
                if (group.size == 1) group
                else group.mapIndexed { i, opt -> opt.copy(label = "${opt.label} ${i + 1}") }
            }

        return listOf(automatic) + deduped
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun tryOpenAudioRecord(
        audioSource: Int,
        preferredDevice: AudioDeviceInfo?,
        bufferBytes: Int,
        channelConfig: Int,
        encoding: Int,
    ): AudioRecord? {
        return try {
            val builder = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
            val record = builder.build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            if (preferredDevice != null && !record.setPreferredDevice(preferredDevice)) {
                record.release()
                return null
            }
            record
        } catch (e: Exception) {
            Timber.w(
                e,
                "AudioRecord open failed source=$audioSource device=${preferredDevice?.productName}"
            )
            null
        }
    }

    /** An opened capture device together with a human-friendly label for it. */
    private data class OpenedMic(val record: AudioRecord, val label: String)

    /**
     * Open the phone's built-in microphone even when a USB audio device is connected.
     * Android may otherwise route [MediaRecorder.AudioSource.MIC] to the USB device.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openBuiltInMicrophone(
        bufferBytes: Int,
        channelConfig: Int,
        encoding: Int,
    ): OpenedMic? {
        val builtInDevices = builtInInputDevices()
        val sourcesToTry = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
        )

        // Try the user's chosen microphone first (if present), then fall back to the others so a
        // saved-but-absent selection never prevents capture:
        val orderedDevices = if (preferredInternalMicId != AUTOMATIC_MIC_ID) {
            val (preferred, others) =
                builtInDevices.partition { deviceDescriptor(it) == preferredInternalMicId }
            preferred + others
        } else {
            builtInDevices
        }

        // Pick the first audio source that matches our criteria:
        if (orderedDevices.isEmpty()) {
            for (source in sourcesToTry) {
                tryOpenAudioRecord(source, null, bufferBytes, channelConfig, encoding)?.let {
                    return OpenedMic(it, "Internal microphone")
                }
            }
            return null
        }

        for (device in orderedDevices) {
            Timber.d("Trying built-in input device id=${device.id} ${device.productName}")
            for (source in sourcesToTry) {
                tryOpenAudioRecord(source, device, bufferBytes, channelConfig, encoding)?.let {
                    return OpenedMic(it, deviceLabel(device))
                }
            }
        }
        return null
    }

    suspend fun start(): LiveConnectResult {
        return mutex.withLock {
            stopLocked()

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return LiveConnectResult(
                    connectedOK = false,
                    errorMessage = "Microphone permission is required to use the internal microphone."
                )
            }

            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ, channelConfig, encoding
            )
            if (minBufferBytes <= 0) {
                return LiveConnectResult(
                    connectedOK = false,
                    errorMessage = "This device does not support ${SAMPLE_RATE_HZ / 1000} kHz mono capture."
                )
            }

            val bufferBytes = maxOf(minBufferBytes * 2, SAMPLE_RATE_HZ * 2 * READ_CHUNK_MS / 1000)
            val opened = openBuiltInMicrophone(bufferBytes, channelConfig, encoding)
                ?: return LiveConnectResult(
                    connectedOK = false,
                    errorMessage = "Unable to open the internal microphone for capture."
                )
            val record = opened.record

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return LiveConnectResult(
                    connectedOK = false,
                    errorMessage = "Unable to start phone microphone capture."
                )
            }

            audioRecord = record
            paused = false
            captureJob = scope.launch(Dispatchers.IO) {
                val readBuffer = ShortArray(SAMPLE_RATE_HZ * READ_CHUNK_MS / 1000)
                while (isActive) {
                    val recorder = audioRecord
                    if (recorder == null)
                        break

                    if (paused) {
                        delay(20)
                        continue
                    }

                    val samplesRead = recorder.read(readBuffer, 0, readBuffer.size)
                    when {
                        samplesRead > 0 -> {
                            LiveDataBridge.onHeapDataBufferReady(readBuffer, 0, samplesRead)
                            if (liveAudioMonitorEnabled)
                                feedLiveAudioSamples?.invoke(readBuffer, 0, samplesRead)
                        }

                        samplesRead < 0 ->
                            Timber.e("AudioRecord.read failed: $samplesRead")
                    }
                }
            }

            LiveConnectResult(
                connectedOK = true,
                productName = opened.label,
                sampleRate = SAMPLE_RATE_HZ
            )
        }
    }

    suspend fun stop() {
        mutex.withLock {
            stopLocked()
        }
    }

    private suspend fun stopLocked() {
        liveAudioMonitorEnabled = false
        captureJob?.cancelAndJoin()
        captureJob = null

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    record.stop()
            } catch (e: IllegalStateException) {
                Timber.w(e, "MicCaptureService stop")
            }
            record.release()
        }
        audioRecord = null
        paused = false
    }

    suspend fun pause() {
        mutex.withLock {
            paused = true
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    record.stop()
            }
        }
    }

    suspend fun resume() {
        mutex.withLock {
            audioRecord?.let { record ->
                record.startRecording()
                paused = false
            }
        }
    }
}
