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
 * How the native STFT packs each bin before model preprocess.
 *
 * [LogPower] is `10·log10((|X|/windowSum)² + eps)`.
 * [UnnormalizedMagnitude] is `|X|` (no window-sum divide), for PCEN.
 */
enum class MlStftScale {
    LogPower,
    UnnormalizedMagnitude,
}

/**
 * Optional STFT feature stage for an Auto Id model.
 *
 * When present on [MlModelDescriptor], each PCM analysis window is converted to a
 * packed spectrogram before inference: periodic Hann, then [scale]. Absent /
 * null ⇒ raw PCM input.
 *
 * Output layout: row-major `[nFrames * nFreqBins]`, frames along the analysis
 * window with [hopSamples], each frame has [nFreqBins] = fftSize/2+1 bins.
 * [firstFrequencyBin], when greater than 0, drops bins below that index
 * before the values are passed to the model.
 *
 * [scale] is not read from family.json; [MlPipelineSpec] sets
 * [MlStftScale.UnnormalizedMagnitude] when the consumer enables PCEN.
 */
data class MlStftParams(
    val fftSize: Int,
    val hopSamples: Int,
    /** Analysis window name; only `"hann"` is supported for now. */
    val window: String = "hann",
    /**
     * Lowest frequency bin included in the model input (`stft.firstFrequencyBin`).
     * `0` keeps every bin. Bins below this index are discarded.
     */
    val firstFrequencyBin: Int = 0,
    val scale: MlStftScale = MlStftScale.LogPower,
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
        require(firstFrequencyBin in 0 until nFreqBins()) {
            "stft.firstFrequencyBin ($firstFrequencyBin) must be in 0 until ${nFreqBins()}"
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

    /** Frequency bins kept in the model input after discarding bins below [firstFrequencyBin]. */
    fun keptFreqBins(): Int = nFreqBins() - firstFrequencyBin

    /**
     * Packed model-input length after discarding bins below [firstFrequencyBin].
     * [firstFrequencyBin] `0` keeps every bin ([outputSize]).
     */
    fun modelInputSize(windowSamples: Int): Int =
        nFrames(windowSamples) * keptFreqBins()

    /**
     * Copy [packed] (frame-major, [nFreqBins] bins per frame) keeping bins
     * from [firstFrequencyBin] upward. Does not modify [packed].
     */
    fun dropBinsBelow(packed: FloatArray, nFrames: Int): FloatArray {
        val fullBins = nFreqBins()
        require(packed.size == nFrames * fullBins) {
            "spectrogram size ${packed.size} != $nFrames * $fullBins"
        }
        if (firstFrequencyBin == 0) return packed
        val kept = keptFreqBins()
        val out = FloatArray(nFrames * kept)
        for (frame in 0 until nFrames) {
            val src = frame * fullBins + firstFrequencyBin
            packed.copyInto(out, frame * kept, src, src + kept)
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject()
        .put("fftSize", fftSize)
        .put("hopSamples", hopSamples)
        .put("window", window)
        .put("firstFrequencyBin", firstFrequencyBin)

    companion object {
        /**
         * Parse optional `stft` object from bundled `family.json`.
         * Returns null when the key is absent or JSON null.
         */
        fun fromJsonOrNull(parent: JSONObject): MlStftParams? {
            if (!parent.has("stft") || parent.isNull("stft")) return null
            val json = parent.getJSONObject("stft")
            val firstFrequencyBin = if (
                json.has("firstFrequencyBin") && !json.isNull("firstFrequencyBin")
            ) {
                json.getInt("firstFrequencyBin")
            } else {
                0
            }
            return MlStftParams(
                fftSize = json.getInt("fftSize"),
                hopSamples = json.getInt("hopSamples"),
                window = json.optString("window", "hann"),
                firstFrequencyBin = firstFrequencyBin,
            )
        }

        /**
         * Parse optional BYOM spectrogram input from the `model` object.
         *
         * - `stftWindow` present ⇒ log-power STFT before inference; value is
         *   the power-of-two FFT / analysis-window length.
         * - `stftHop` optional; defaults to [stftWindow] (non-overlapping frames).
         * - `firstFrequencyBin` optional; defaults to 0 (keep every bin).
         * - `stftWindow` absent ⇒ raw PCM input (`null`); `stftHop` or
         *   `firstFrequencyBin` alone is an error.
         */
        fun fromByomModelOrNull(model: JSONObject): MlStftParams? {
            val hasWindow = model.has("stftWindow") && !model.isNull("stftWindow")
            if (!hasWindow) {
                require((!model.has("stftHop") || model.isNull("stftHop")) &&
                    (!model.has("firstFrequencyBin") || model.isNull("firstFrequencyBin"))
                ) {
                    "model.stftHop and model.firstFrequencyBin require model.stftWindow"
                }
                return null
            }
            val fftSize = model.getInt("stftWindow")
            val hopSamples = if (model.has("stftHop") && !model.isNull("stftHop")) {
                model.getInt("stftHop")
            } else {
                fftSize
            }
            val firstFrequencyBin = if (
                model.has("firstFrequencyBin") && !model.isNull("firstFrequencyBin")
            ) {
                model.getInt("firstFrequencyBin")
            } else {
                0
            }
            return MlStftParams(
                fftSize = fftSize,
                hopSamples = hopSamples,
                firstFrequencyBin = firstFrequencyBin,
            )
        }
    }
}
