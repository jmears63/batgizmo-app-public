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

package org.batgizmo.app.pipeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    }

    private val mutex = Mutex()
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var paused = false

    val sampleRateHz: Int
        get() = SAMPLE_RATE_HZ

    suspend fun start(): LiveConnectResult {
        return mutex.withLock {
            stopLocked()

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return LiveConnectResult(
                    connectedOK = false,
                    errorMessage = "Microphone permission is required. Grant it in app settings."
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
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(channelConfig)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return LiveConnectResult(
                    connectedOK = false,
                    errorMessage = "Unable to open the phone microphone for capture."
                )
            }

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
                        samplesRead > 0 ->
                            LiveDataBridge.onHeapDataBufferReady(readBuffer, 0, samplesRead)

                        samplesRead < 0 ->
                            Timber.e("AudioRecord.read failed: $samplesRead")
                    }
                }
            }

            LiveConnectResult(
                connectedOK = true,
                productName = "Internal Microphone",
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
