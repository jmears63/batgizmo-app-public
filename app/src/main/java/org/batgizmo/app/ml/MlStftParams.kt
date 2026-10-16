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

/**
 * Optional log-magnitude STFT feature stage for an Auto Id model.
 *
 * When present on [MlModelDescriptor], each PCM analysis window is converted to a
 * packed log-magnitude spectrogram before inference. Absent / null ⇒ raw PCM input.
 *
 * Output layout: row-major `[nFrames * nFreqBins]`, frames along the analysis
 * window with [hopSamples], each frame has [nFreqBins] = fftSize/2+1 bins.
 */
data class MlStftParams(
    val fftSize: Int,
    val hopSamples: Int,
    /** Analysis window name; only `"hann"` is supported for now. */
    val window: String = "hann",
) {
    init {
        require(fftSize >= 2 && fftSize.countOneBits() == 1) {
            "stft.fftSize must be a power of two >= 2 (got $fftSize)"
        }
        require(hopSamples > 0) { "stft.hopSamples must be > 0" }
        require(hopSamples <= fftSize) { "stft.hopSamples must be <= fftSize" }
        require(window == "hann") {
            "stft.window must be \"hann\" for now (got \"$window\")"
        }
    }

    /** Number of non-negative frequency bins per frame. */
    fun nFreqBins(): Int = fftSize / 2 + 1

    /** Number of STFT frames that fit in a PCM window of [windowSamples]. */
    fun nFrames(windowSamples: Int): Int {
        require(windowSamples >= fftSize) {
            "windowSamples ($windowSamples) must be >= stft.fftSize ($fftSize)"
        }
        return 1 + (windowSamples - fftSize) / hopSamples
    }

    /** Packed spectrogram length for a PCM window of [windowSamples]. */
    fun outputSize(windowSamples: Int): Int = nFrames(windowSamples) * nFreqBins()

    fun toJson(): JSONObject = JSONObject()
        .put("fftSize", fftSize)
        .put("hopSamples", hopSamples)
        .put("window", window)

    companion object {
        /**
         * Parse optional `stft` object from family / BYOM JSON.
         * Returns null when the key is absent or JSON null.
         */
        fun fromJsonOrNull(parent: JSONObject): MlStftParams? {
            if (!parent.has("stft") || parent.isNull("stft")) return null
            val json = parent.getJSONObject("stft")
            return MlStftParams(
                fftSize = json.getInt("fftSize"),
                hopSamples = json.getInt("hopSamples"),
                window = json.optString("window", "hann"),
            )
        }
    }
}
