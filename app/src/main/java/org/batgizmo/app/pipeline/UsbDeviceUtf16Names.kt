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

import kotlin.math.min

/**
 * USB string descriptors are UTF-16LE. Android's [android.hardware.usb.UsbDevice]
 * manufacturer/product names are ASCII-folded (Ø → '?'); decode the wire form instead.
 */
internal object UsbDeviceUtf16Names {

    fun manufacturerIndex(rawDescriptors: ByteArray): Int = stringIndex(rawDescriptors, 14)

    fun productIndex(rawDescriptors: ByteArray): Int = stringIndex(rawDescriptors, 15)

    fun decodeLanguageId(buffer: ByteArray, byteCount: Int): Int? {
        if (byteCount < 4) return null
        if ((buffer[1].toInt() and 0xFF) != 0x03) return null
        val langId = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
        return langId.takeIf { it != 0 }
    }

    fun decodeStringDescriptor(buffer: ByteArray, byteCount: Int): String? {
        if (byteCount < 2) return null
        if ((buffer[1].toInt() and 0xFF) != 0x03) return null
        val declared = buffer[0].toInt() and 0xFF
        val payloadBytes = (min(declared, byteCount) - 2) and 0x7FFFFFFE
        if (payloadBytes <= 0) return null
        return String(buffer, 2, payloadBytes, Charsets.UTF_16LE)
            .trim('\u0000', '\uFEFF')
            .trim()
            .ifEmpty { null }
    }

    /**
     * Android USB/audio names replace non-ASCII with '?'. If [androidName] is that
     * folded form of a UTF-16 manufacturer/product, return the original.
     */
    fun preferUtf16Name(
        androidName: String,
        utf16Manufacturer: String?,
        utf16Product: String?
    ): String? {
        val candidates = listOfNotNull(
            utf16Product,
            utf16Manufacturer,
            if (utf16Manufacturer != null && utf16Product != null)
                "$utf16Manufacturer $utf16Product"
            else
                null
        )
        return candidates.firstOrNull { asciiFold(it) == androidName }
    }

    fun asciiFold(value: String): String = buildString {
        value.forEach { ch ->
            append(if (ch.code in 0x20..0x7E) ch else '?')
        }
    }

    private fun stringIndex(rawDescriptors: ByteArray, offset: Int): Int {
        if (rawDescriptors.size < 18) return 0
        if ((rawDescriptors[0].toInt() and 0xFF) < 18) return 0
        if ((rawDescriptors[1].toInt() and 0xFF) != 0x01) return 0
        return rawDescriptors[offset].toInt() and 0xFF
    }
}
