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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Abstraction over live audio acquisition (USB bat detector or phone microphone).
 */
interface LiveInputSource {
    suspend fun connect(
        onFeatureUnitDiscovered: (Float, Float, Float, Float) -> Unit
    ): LiveConnectResult

    suspend fun disconnect()
    suspend fun pause()
    suspend fun resume()
}

class UsbLiveInputSource(
    private val usbService: UsbService,
    private val usbConnectChannel: Channel<LiveConnectResult>,
    private val usbConnectFlow: Flow<LiveConnectResult>
) : LiveInputSource {

    override suspend fun connect(
        onFeatureUnitDiscovered: (Float, Float, Float, Float) -> Unit
    ): LiveConnectResult {
        /*
            Connect to the USB data stream, very asynchronously because user
            approval may be required. This is two parts:
            * 1: The connect call itself suspends before returning. User interaction
            *       may be requested to approve access to the device.
            * 2: Finally an event is posted into a channel for us to pick up.
         */
        // Make sure there are no pending responses:
        drainConnectChannel(usbConnectChannel)

        // Part 1:
        // Suspend until connect is complete, and handle any exceptions the obvious way:
        try {
            usbService.connect(onFeatureUnitDiscovered)
        } catch (e: Exception) {
            return LiveConnectResult(
                connectedOK = false,
                errorMessage = e.localizedMessage ?: "Unable to connect USB microphone.",
                offerInternalMicFallback =
                    LiveConnectResult.isNoUsbMicrophoneError(e.localizedMessage)
            )
        }

        // Part 2: suspend until the *first* response (collect would loop for ever):
        val result = usbConnectFlow.first()
        return if (!result.connectedOK &&
            LiveConnectResult.isNoUsbMicrophoneError(result.errorMessage)
        ) {
            result.copy(offerInternalMicFallback = true)
        } else {
            result
        }
    }

    private suspend fun <T> drainConnectChannel(channel: Channel<T>) {
        while (channel.tryReceive().isSuccess) {
            // Discard stale connect results.
        }
    }

    override suspend fun disconnect() {
        usbService.disconnect()
    }

    override suspend fun pause() {
        usbService.pause()
    }

    override suspend fun resume() {
        usbService.resume()
    }
}

class DeviceMicInputSource(
    private val micCaptureService: MicCaptureService
) : LiveInputSource {

    override suspend fun connect(
        onFeatureUnitDiscovered: (Float, Float, Float, Float) -> Unit
    ): LiveConnectResult {
        return micCaptureService.start()
    }

    override suspend fun disconnect() {
        micCaptureService.stop()
    }

    override suspend fun pause() {
        micCaptureService.pause()
    }

    override suspend fun resume() {
        micCaptureService.resume()
    }
}
