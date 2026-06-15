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

/**
 * Outcome of attempting to connect a live input source (USB bat detector, phone microphone, etc.).
 */
data class LiveConnectResult(
    val connectedOK: Boolean,
    val errorMessage: String? = null,
    val deviceName: String? = null,
    val manufacturerName: String? = null,
    val productName: String? = null,
    val sampleRate: Int? = null,
    val offerInternalMicFallback: Boolean = false,
) {
    companion object {
        const val NO_USB_MICROPHONE_MESSAGE =
            "No USB microphone found. Please plug one in to the phone."

        fun isNoUsbMicrophoneError(errorMessage: String?): Boolean {
            return errorMessage?.startsWith("No USB microphone found") == true
        }
    }
}

/**
 * Outcome of attempting to start live or viewer audio playback.
 */
data class LiveAudioStartResult(
    val startedOK: Boolean
)

/**
 * Error reported by a live input stream (currently the USB native streaming path).
 */
data class LiveStreamErrorResult(
    val errno: Int
)
