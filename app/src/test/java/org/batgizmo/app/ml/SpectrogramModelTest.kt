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

package org.batgizmo.app.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrogramModelTest {

    @Test
    fun nchwPlacesFrequencyOnAxis2AndTimeOnAxis3() {
        // Two frames, three bins. Packed time-major: frame0 = 0,1,2 frame1 = 3,4,5.
        val packed = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f)
        val nchw = spectrogramToNchw(packed, nFrames = 2, nFreqBins = 3)
        assertEquals(1, nchw.size)
        assertEquals(1, nchw[0].size)
        assertEquals(3, nchw[0][0].size)
        assertArrayEquals(floatArrayOf(0f, 3f), nchw[0][0][0], 0f)
        assertArrayEquals(floatArrayOf(1f, 4f), nchw[0][0][1], 0f)
        assertArrayEquals(floatArrayOf(2f, 5f), nchw[0][0][2], 0f)
    }

    @Test
    fun flattenReadsRank2AndRank3InRowMajorOrder() {
        val rank2 = allocateFloatOutput(intArrayOf(1, 3)) as Array<FloatArray>
        rank2[0][0] = 1f
        rank2[0][1] = 2f
        rank2[0][2] = 3f
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), flattenFloats(rank2), 0f)

        val rank3 = allocateFloatOutput(intArrayOf(1, 2, 2)) as Array<Array<FloatArray>>
        rank3[0][0][0] = 4f
        rank3[0][0][1] = 5f
        rank3[0][1][0] = 6f
        rank3[0][1][1] = 7f
        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f), flattenFloats(rank3), 0f)
    }

    @Test
    fun presenceOutputIsTheNamedTensorNotTheTypeScores() {
        val names = listOf("fmaxe_hz", "presence_prob", "type_prob")
        val shapes = listOf(
            intArrayOf(1, 248),
            intArrayOf(1, 248),
            intArrayOf(1, 4, 248),
        )
        assertEquals(1, presenceOutputIndex(names, shapes, labelCount = 248))
    }

    @Test
    fun presenceOutputFallsBackToFirstVector() {
        val names = listOf("", "", "", "")
        val shapes = listOf(
            intArrayOf(1, 248),
            intArrayOf(1, 248),
            intArrayOf(1, 248),
            intArrayOf(1, 4, 248),
        )
        assertEquals(0, presenceOutputIndex(names, shapes, labelCount = 248))
    }

    @Test
    fun fmaxeOutputIsTheNamedTensor() {
        val names = listOf("presence_prob", "pulse_length_s", "fmaxe_hz", "type_prob")
        val shapes = listOf(
            intArrayOf(1, 248),
            intArrayOf(1, 248),
            intArrayOf(1, 248),
            intArrayOf(1, 4, 248),
        )
        assertEquals(
            2,
            fmaxeOutputIndex(names, shapes, labelCount = 248, presenceIndex = 0),
        )
    }

    @Test
    fun fmaxeOutputFollowsPresenceWhenUnnamed() {
        val names = listOf("", "", "", "")
        val shapes = listOf(
            intArrayOf(1, 248),
            intArrayOf(1, 248),
            intArrayOf(1, 248),
            intArrayOf(1, 4, 248),
        )
        assertEquals(
            1,
            fmaxeOutputIndex(names, shapes, labelCount = 248, presenceIndex = 0),
        )
    }

    @Test
    fun fmaxeIsShownInKhz() {
        assertEquals("45.2 kHz", formatFmaxeKhz(45230f))
    }
}
