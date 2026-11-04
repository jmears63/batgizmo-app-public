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

/**
 * One analysis frame ready for [MlModelBase.predict].
 *
 * Which variant is produced depends on [MlModelDescriptor.stft]:
 * - null → [PcmWindow]
 * - present → [Spectrogram] (packed STFT; scale from [MlStftParams])
 */
sealed class MlModelInput {
    /** Raw float PCM at the model's sample rate, length = windowSamples. */
    data class PcmWindow(val samples: FloatArray) : MlModelInput() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PcmWindow) return false
            return samples.contentEquals(other.samples)
        }

        override fun hashCode(): Int = samples.contentHashCode()
    }

    /**
     * Packed spectrogram from the ML STFT stage.
     * Layout matches [MlStftParams.outputSize].
     */
    data class Spectrogram(
        val values: FloatArray,
        val nFrames: Int,
        val nFreqBins: Int,
    ) : MlModelInput() {
        init {
            require(values.size == nFrames * nFreqBins) {
                "spectrogram size ${values.size} != $nFrames * $nFreqBins"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Spectrogram) return false
            return nFrames == other.nFrames &&
                nFreqBins == other.nFreqBins &&
                values.contentEquals(other.values)
        }

        override fun hashCode(): Int {
            var result = values.contentHashCode()
            result = 31 * result + nFrames
            result = 31 * result + nFreqBins
            return result
        }
    }
}
