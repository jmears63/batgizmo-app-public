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
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 4, 155),
        )
        assertEquals(1, presenceOutputIndex(names, shapes, labelCount = 155))
    }

    @Test
    fun presenceOutputFallsBackToFirstVector() {
        val names = listOf("", "", "", "")
        val shapes = listOf(
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 4, 155),
        )
        assertEquals(0, presenceOutputIndex(names, shapes, labelCount = 155))
    }

    @Test
    fun fmaxeOutputIsTheNamedTensor() {
        val names = listOf("presence_prob", "pulse_length_s", "fmaxe_hz", "type_prob")
        val shapes = listOf(
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 4, 155),
        )
        assertEquals(
            2,
            fmaxeOutputIndex(names, shapes, labelCount = 155, presenceIndex = 0),
        )
    }

    @Test
    fun fmaxeOutputFollowsPresenceWhenUnnamed() {
        val names = listOf("", "", "", "")
        val shapes = listOf(
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 4, 155),
        )
        assertEquals(
            1,
            fmaxeOutputIndex(names, shapes, labelCount = 155, presenceIndex = 0),
        )
    }

    @Test
    fun fmaxeIsShownInKhz() {
        assertEquals("45.2 kHz", formatFmaxeKhz(45230f))
    }

    @Test
    fun pulseLengthOutputIsTheNamedTensor() {
        val names = listOf("presence_prob", "fmaxe_hz", "pulse_length_s", "type_prob")
        val shapes = listOf(
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 4, 155),
        )
        assertEquals(
            2,
            pulseLengthOutputIndex(
                names, shapes, labelCount = 155, presenceIndex = 0, fmaxeIndex = 1,
            ),
        )
    }

    @Test
    fun pulseLengthOutputIsTheRemainingPerBinVectorWhenUnnamed() {
        val names = listOf("", "", "", "")
        val shapes = listOf(
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 155),
            intArrayOf(1, 4, 155),
        )
        assertEquals(
            2,
            pulseLengthOutputIndex(
                names, shapes, labelCount = 155, presenceIndex = 0, fmaxeIndex = 1,
            ),
        )
    }

    @Test
    fun binCentreIsMidwayThroughItsShareOfTheClip() {
        assertEquals(0.5 / 155.0 * 0.25, binCentreSec(0, 155, 0.25), 0.0)
        assertEquals(123.5 / 155.0 * 0.25, binCentreSec(123, 155, 0.25), 0.0)
    }

    @Test
    fun chirpLabelShowsTimeAndFmaxe() {
        assertEquals("0.124 s  45.2 kHz", formatChirpLabel(0.124496, 45230f))
    }

    @Test
    fun strictPeaksIgnoreEndsTiesPlateausAndTheThreshold() {
        val fmaxe = FloatArray(5) { it * 1000f }
        val silent = FloatArray(5)
        assertEquals(
            listOf(2),
            batFilterCnnSpectrogramChirps(
                floatArrayOf(0.9f, 0.1f, 0.4f, 0.1f, 0.9f),
                fmaxe, silent, durationSec = 1.0, threshold = 0.05f,
            ).map { it.binIndex },
        )
        assertEquals(
            emptyList<Int>(),
            batFilterCnnSpectrogramChirps(
                floatArrayOf(0f, 0.4f, 0.4f, 0f, 0f),
                fmaxe, silent, durationSec = 1.0, threshold = 0.05f,
            ).map { it.binIndex },
        )
        assertEquals(
            listOf(1),
            batFilterCnnSpectrogramChirps(
                floatArrayOf(0f, 0.05f, 0f, 0f, 0f),
                fmaxe, silent, durationSec = 1.0, threshold = 0.05f,
            ).map { it.binIndex },
        )
        assertEquals(
            emptyList<Int>(),
            batFilterCnnSpectrogramChirps(
                floatArrayOf(0f, 0.049f, 0f, 0f, 0f),
                fmaxe, silent, durationSec = 1.0, threshold = 0.05f,
            ).map { it.binIndex },
        )
    }

    @Test
    fun strongestClearPeakWinsRegardlessOfPulseOverlap() {
        val nBins = 9
        val presence = FloatArray(nBins)
        presence[1] = 0.2f
        presence[3] = 0.9f
        presence[5] = 0.4f
        val fmaxe = FloatArray(nBins) { 10_000f * (it + 1) }
        val pulses = FloatArray(nBins) { 0.30f }
        val chirps = batFilterCnnSpectrogramChirps(presence, fmaxe, pulses, 1.0, 0.05f)
        assertEquals(1, chirps.size)
        assertEquals(3, chirps[0].binIndex)
        assertEquals(3.5 / 9.0, chirps[0].timeSec, 0.0)
        assertEquals(40_000f, chirps[0].fmaxeHz, 0f)
        assertEquals(0.9f, chirps[0].presence, 0f)
    }

    @Test
    fun equalPresenceKeepsTheEarlierBin() {
        val nBins = 11
        val presence = FloatArray(nBins)
        presence[4] = 0.5f
        presence[6] = 0.5f
        val fmaxe = FloatArray(nBins) { 10_000f * (it + 1) }
        val pulses = FloatArray(nBins)
        val chirps = batFilterCnnSpectrogramChirps(presence, fmaxe, pulses, 1.0, 0.05f)
        assertEquals(listOf(4), chirps.map { it.binIndex })
        assertEquals(50_000f, chirps[0].fmaxeHz, 0f)
    }

    @Test
    fun peaksWithinThirtyMsOrHalfAPulseOfEitherEndAreIgnored() {
        val nBins = 250
        val duration = 1.0
        val fmaxe = FloatArray(nBins)

        fun chirpBins(peak: Int, pulseLength: Float): List<Int> {
            val presence = FloatArray(nBins)
            presence[peak] = 0.8f
            val pulses = FloatArray(nBins)
            pulses[peak] = pulseLength
            return batFilterCnnSpectrogramChirps(presence, fmaxe, pulses, duration, 0.05f)
                .map { it.binIndex }
        }

        // Centres are (i + 0.5) / 250 seconds. Bin 7 is 30 ms from the start.
        assertEquals(emptyList<Int>(), chirpBins(7, 0f))
        assertEquals(listOf(8), chirpBins(8, 0f))
        assertEquals(emptyList<Int>(), chirpBins(242, 0f))
        assertEquals(listOf(241), chirpBins(241, 0f))

        // Half of 0.125 s is 62.5 ms. Bin 15 is centred at 62 ms; bin 16 at 66 ms.
        assertEquals(emptyList<Int>(), chirpBins(15, 0.125f))
        assertEquals(listOf(16), chirpBins(16, 0.125f))

        val presence = FloatArray(nBins)
        presence[8] = 0.9f
        presence[20] = 0.4f
        val pulses = FloatArray(nBins)
        pulses[8] = 0.10f
        pulses[20] = 0.10f
        assertEquals(
            listOf(20),
            batFilterCnnSpectrogramChirps(presence, fmaxe, pulses, duration, 0.05f).map { it.binIndex },
        )
    }
}
