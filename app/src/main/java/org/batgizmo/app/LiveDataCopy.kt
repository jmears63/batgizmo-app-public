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

import org.batgizmo.app.pipeline.NativeUSB

/**
 * Copy live audio samples into a ring-structured destination buffer.
 * Wrap behaviour matches [NativeUSB.copyURBBufferData] in nativeusb.cpp.
 */
object LiveDataCopy {

    fun copyIntoRingBuffer(
        descriptor: LiveDataBridge.BufferDescriptor,
        dest: ShortArray,
        destOffset: Int,
        destCapacity: Int,
        nativeUSB: NativeUSB
    ): Int {
        return when (descriptor) {
            is LiveDataBridge.BufferDescriptor.Native ->
                nativeUSB.copyURBBufferData(
                    descriptor.nativeAddress,
                    descriptor.samples,
                    dest,
                    destOffset,
                    destCapacity
                )

            is LiveDataBridge.BufferDescriptor.Heap ->
                copyHeapIntoRing(
                    descriptor.data,
                    descriptor.offset,
                    descriptor.samples,
                    dest,
                    destOffset,
                    destCapacity
                )
        }
    }

    /**
     * Copy [descriptor] into a contiguous [dest] starting at index 0.
     * [dest] must be at least [LiveDataBridge.BufferDescriptor.samples] long.
     * Returns the number of samples written.
     */
    fun copyIntoLinearBuffer(
        descriptor: LiveDataBridge.BufferDescriptor,
        dest: ShortArray,
        nativeUSB: NativeUSB
    ): Int {
        require(dest.size >= descriptor.samples) {
            "dest size ${dest.size} < samples ${descriptor.samples}"
        }
        return when (descriptor) {
            is LiveDataBridge.BufferDescriptor.Native ->
                nativeUSB.copyURBBufferData(
                    descriptor.nativeAddress,
                    descriptor.samples,
                    dest,
                    0,
                    dest.size
                )

            is LiveDataBridge.BufferDescriptor.Heap -> {
                System.arraycopy(
                    descriptor.data,
                    descriptor.offset,
                    dest,
                    0,
                    descriptor.samples
                )
                descriptor.samples
            }
        }
    }

    private fun copyHeapIntoRing(
        source: ShortArray,
        sourceOffset: Int,
        sourceSamples: Int,
        dest: ShortArray,
        destOffset: Int,
        destCapacity: Int
    ): Int {
        if (destCapacity <= 0 || sourceSamples <= 0 || sourceOffset < 0)
            return 0
        require(destCapacity <= dest.size) {
            "destCapacity $destCapacity exceeds dest.size ${dest.size}"
        }

        // USBSourceStep may pass a logical write cursor past destCapacity until the
        // next slice boundary resets it; wrap into the ring before copying.
        var dstIndex = destOffset % destCapacity
        if (dstIndex < 0)
            dstIndex += destCapacity

        val maxFromSource = (source.size - sourceOffset).coerceAtLeast(0)
        var samplesToCopy = minOf(sourceSamples, destCapacity, maxFromSource)
        if (samplesToCopy <= 0)
            return 0

        val copiedTotal = samplesToCopy
        var srcIndex = sourceOffset

        val part1Space = destCapacity - dstIndex
        val part1Count = minOf(samplesToCopy, part1Space)
        for (i in 0 until part1Count) {
            dest[dstIndex++] = source[srcIndex++]
        }
        samplesToCopy -= part1Count

        if (samplesToCopy > 0) {
            dstIndex = 0
            for (i in 0 until samplesToCopy) {
                dest[dstIndex++] = source[srcIndex++]
            }
        }

        return copiedTotal
    }
}
