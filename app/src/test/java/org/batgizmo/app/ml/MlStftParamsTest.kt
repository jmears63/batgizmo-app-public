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

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MlStftParamsTest {

    @Test
    fun byomModelAbsentMeansRawPcm() {
        assertNull(MlStftParams.fromByomModelOrNull(JSONObject()))
    }

    @Test
    fun byomModelWindowOnlyDefaultsHopToWindow() {
        val stft = MlStftParams.fromByomModelOrNull(
            JSONObject().put("stftWindow", 1024),
        )
        assertEquals(MlStftParams(fftSize = 1024, hopSamples = 1024), stft)
    }

    @Test
    fun byomModelWindowAndHop() {
        val stft = MlStftParams.fromByomModelOrNull(
            JSONObject()
                .put("stftWindow", 2048)
                .put("stftHop", 512),
        )
        assertEquals(MlStftParams(fftSize = 2048, hopSamples = 512), stft)
    }

    @Test(expected = IllegalArgumentException::class)
    fun byomModelHopWithoutWindowRejected() {
        MlStftParams.fromByomModelOrNull(JSONObject().put("stftHop", 512))
    }

    @Test
    fun byomModelFirstFrequencyBin() {
        val stft = MlStftParams.fromByomModelOrNull(
            JSONObject()
                .put("stftWindow", 512)
                .put("stftHop", 128)
                .put("firstFrequencyBin", 30),
        )
        assertEquals(
            MlStftParams(fftSize = 512, hopSamples = 128, firstFrequencyBin = 30),
            stft,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun byomModelFirstFrequencyBinWithoutWindowRejected() {
        MlStftParams.fromByomModelOrNull(JSONObject().put("firstFrequencyBin", 30))
    }

    @Test(expected = IllegalArgumentException::class)
    fun byomModelNonPowerOfTwoWindowRejected() {
        MlStftParams.fromByomModelOrNull(JSONObject().put("stftWindow", 1000))
    }

    @Test
    fun firstFrequencyBinAbsentKeepsEveryBin() {
        val stft = MlStftParams.fromJsonOrNull(
            JSONObject().put(
                "stft",
                JSONObject().put("fftSize", 8).put("hopSamples", 4),
            ),
        )
        assertEquals(MlStftParams(fftSize = 8, hopSamples = 4), stft)
    }

    @Test
    fun firstFrequencyBinKeepsFromThatBin() {
        val stft = MlStftParams.fromJsonOrNull(
            JSONObject().put(
                "stft",
                JSONObject()
                    .put("fftSize", 8)
                    .put("hopSamples", 4)
                    .put("firstFrequencyBin", 2),
            ),
        )!!
        assertEquals(2, stft.firstFrequencyBin)
        assertEquals(3, stft.keptFreqBins())
        // fft 8, hop 4, 16 samples → 3 frames.
        val nFrames = stft.nFrames(16)
        assertEquals(nFrames * 3, stft.modelInputSize(16))
        val packed = FloatArray(nFrames * stft.nFreqBins()) { it.toFloat() }
        val cropped = stft.dropBinsBelow(packed, nFrames)
        val expected = FloatArray(nFrames * 3)
        for (frame in 0 until nFrames) {
            for (binIndex in 0 until 3) {
                expected[frame * 3 + binIndex] =
                    packed[frame * stft.nFreqBins() + 2 + binIndex]
            }
        }
        assertArrayEquals(expected, cropped, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun firstFrequencyBinNegativeRejected() {
        MlStftParams.fromJsonOrNull(
            JSONObject().put(
                "stft",
                JSONObject()
                    .put("fftSize", 8)
                    .put("hopSamples", 4)
                    .put("firstFrequencyBin", -1),
            ),
        )
    }

    @Test
    fun liveBatcnnClipIs310FramesAnd155PresenceBins() {
        val root = listOf(
            File("src/main/assets/ml/batfiltercnn"),
            File("app/src/main/assets/ml/batfiltercnn"),
        ).first { File(it, "family.json").isFile }
        val family = JSONObject(File(root, "family.json").readText())
        val stft = MlStftParams.fromJsonOrNull(family)!!
        val windowSamples = family.getInt("windowSamples")
        assertEquals(64_000, windowSamples)
        assertEquals(205, stft.hopSamples)
        assertEquals(30, stft.firstFrequencyBin)
        assertEquals(310, stft.nFrames(windowSamples))
        assertEquals(227, stft.keptFreqBins())
        val preprocess = family.getJSONObject("preprocess")
        assertEquals(true, preprocess.optBoolean("pcen", false))
        assertEquals(true, preprocess.optBoolean("subtractNoiseBaseline", false))
        val labels = JSONObject(
            File(root, "variants/filter/labels.json").readText()
        ).getJSONArray("labels")
        assertEquals(155, labels.length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun firstFrequencyBinAtOrAboveBinCountRejected() {
        MlStftParams.fromJsonOrNull(
            JSONObject().put(
                "stft",
                JSONObject()
                    .put("fftSize", 8)
                    .put("hopSamples", 4)
                    .put("firstFrequencyBin", 5),
            ),
        )
    }
}

