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

    private fun copyHeapIntoRing(
        source: ShortArray,
        sourceOffset: Int,
        sourceSamples: Int,
        dest: ShortArray,
        destOffset: Int,
        destCapacity: Int
    ): Int {
        var samplesToCopy = minOf(sourceSamples, destCapacity)
        var srcIndex = sourceOffset
        var dstIndex = destOffset

        val part1Space = destCapacity - destOffset
        val part1Count = minOf(samplesToCopy, part1Space)
        for (i in 0 until part1Count) {
            dest[dstIndex++] = source[srcIndex++]
        }
        samplesToCopy -= part1Count

        if (samplesToCopy > 0) {
            dstIndex = 0
            val part2Count = minOf(samplesToCopy, destCapacity)
            for (i in 0 until part2Count) {
                dest[dstIndex++] = source[srcIndex++]
            }
            samplesToCopy -= part2Count
        }

        return sourceSamples - samplesToCopy
    }
}
