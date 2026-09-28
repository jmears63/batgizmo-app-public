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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbDeviceUtf16NamesTest {

    @Test
    fun rodeAiDeviceDescriptor_stringIndexes() {
        val device = byteArrayOf(
            0x12, 0x01, 0x00, 0x02, 0xEF.toByte(), 0x02, 0x01, 0x40,
            0xF7.toByte(), 0x19, 0x23, 0x00, 0x15, 0x01, 0x01, 0x02, 0x03, 0x01
        )
        assertEquals(1, UsbDeviceUtf16Names.manufacturerIndex(device))
        assertEquals(2, UsbDeviceUtf16Names.productIndex(device))
    }

    @Test
    fun decodeStringDescriptor_preservesRodeStrokeO() {
        val descriptor = byteArrayOf(
            0x0A, 0x03,
            0x52, 0x00,
            0xD8.toByte(), 0x00,
            0x44, 0x00,
            0x45, 0x00
        )
        assertEquals("RØDE", UsbDeviceUtf16Names.decodeStringDescriptor(descriptor, descriptor.size))
    }

    @Test
    fun decodeLanguageId_englishUs() {
        val descriptor = byteArrayOf(0x04, 0x03, 0x09, 0x04)
        assertEquals(0x0409, UsbDeviceUtf16Names.decodeLanguageId(descriptor, descriptor.size))
    }

    @Test
    fun decodeStringDescriptor_rejectsNonString() {
        assertNull(UsbDeviceUtf16Names.decodeStringDescriptor(byteArrayOf(0x12, 0x01), 2))
    }

    @Test
    fun preferUtf16Name_restoresRodeStrokeOFromAsciiFold() {
        assertEquals(
            "RØDE",
            UsbDeviceUtf16Names.preferUtf16Name("R?DE", "RØDE", "AI-Micro")
        )
        assertEquals(
            "RØDE AI-Micro",
            UsbDeviceUtf16Names.preferUtf16Name("R?DE AI-Micro", "RØDE", "RØDE AI-Micro")
        )
        assertEquals(
            "RØDE AI-Micro",
            UsbDeviceUtf16Names.preferUtf16Name(
                "R?DE AI-Micro",
                "RØDE",
                "AI-Micro"
            )
        )
    }
}
