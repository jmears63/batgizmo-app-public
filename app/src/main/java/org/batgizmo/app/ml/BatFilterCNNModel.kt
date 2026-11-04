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

import android.content.res.AssetManager
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * BatFilterCNN (live-system heatmap) specialisation of [SpectrogramModel].
 *
 * Generic spectrogram TFLite behaviour stays on the parent; BatFilterCNN-
 * specific behaviour belongs here. Not thread-safe.
 */
class BatFilterCNNModel(
    assets: AssetManager,
    descriptor: MlModelDescriptor,
    numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
) : SpectrogramModel(assets, descriptor, numThreads) {

    private val pcenCoeff = pcenSmoothCoeff(sampleRateHz.toFloat())

    override fun preprocessSpectrogram(
        values: FloatArray,
        nFrames: Int,
        nFreqBins: Int,
    ): FloatArray {
        if (!descriptor.pcen && !descriptor.subtractNoiseBaseline) return values
        // When PCEN is on, the shared STFT already emits unnormalized |X|.
        val out = values.copyOf()
        if (descriptor.pcen) {
            pcenMagnitudeInPlace(out, nFrames, nFreqBins, pcenCoeff)
        }
        if (descriptor.subtractNoiseBaseline) {
            subtractBandMeanInPlace(out, nFrames, nFreqBins)
        }
        return out
    }

    /**
     * The single strongest presence peak in the window, for smart heterodyne.
     *
     * Bin i is centred at `(i + 0.5) / nBins * durationSec`. A candidate is an
     * interior bin whose presence is at least [threshold] and strictly greater
     * than both neighbours. A peak is ignored when its centre is within 30 ms
     * of either end of the clip, or within half its pulse length of either end.
     * Among the remaining candidates, the highest presence wins, and the
     * earlier bin when those are equal.
     */
    override fun spectrogramChirps(
        presence: FloatArray,
        fmaxeHz: FloatArray,
        pulseLengthS: FloatArray,
        durationSec: Double,
        threshold: Float,
    ): List<SpectrogramChirp> =
        batFilterCnnSpectrogramChirps(
            presence, fmaxeHz, pulseLengthS, durationSec, threshold,
        )
}

// librosa.pcen defaults, and the scale BatDetect2 applies before that call.
// BD2 passes sr=sample_rate/10 and leaves hop_length at librosa's default
// of 512. That hop is what sets the smoother.
private const val PCEN_GAIN = 0.98
private const val PCEN_BIAS = 2.0
private const val PCEN_POWER = 0.5
private const val PCEN_TIME_CONSTANT_S = 0.400
private const val PCEN_EPS = 1e-6
private const val PCEN_LIBROSA_HOP = 512.0
private const val PCEN_SR_DIVISOR = 10.0
private const val PCEN_AUDIO_SCALE = 2147483648.0 // 2**31

/**
 * IIR coefficient b for BD2's librosa.pcen call at this sample rate.
 *
 * librosa derives b from time_constant * sr / hop_length. BD2 passes
 * sr = sample_rate / 10 and leaves hop_length at 512.
 */
internal fun pcenSmoothCoeff(sampleRateHz: Float): Float {
    val tFrames =
        PCEN_TIME_CONSTANT_S * (sampleRateHz / PCEN_SR_DIVISOR) / PCEN_LIBROSA_HOP
    return (
        (sqrt(1.0 + 4.0 * tFrames * tFrames) - 1.0) /
            (2.0 * tFrames * tFrames)
        ).toFloat()
}

/**
 * Own PCEN of a magnitude spectrogram (librosa.pcen formula).
 *
 * [values] is frame-major (`frame * nFreqBins + bin`), non-negative
 * unnormalized |STFT| from the native STFT PCEN path. Multiplies by
 * 2**31, then applies librosa's default gain, bias, power, and eps.
 * The smoother starts from librosa's unit-step state of 1.
 */
internal fun pcenMagnitudeInPlace(
    values: FloatArray,
    nFrames: Int,
    nFreqBins: Int,
    coeff: Float,
) {
    require(values.size == nFrames * nFreqBins) {
        "spectrogram size ${values.size} != $nFrames * $nFreqBins"
    }
    if (nFrames == 0) return
    val oneMinusB = 1.0 - coeff
    val logEps = ln(PCEN_EPS)
    val biasPow = PCEN_BIAS.pow(PCEN_POWER)
    val state = DoubleArray(nFreqBins) { 1.0 }
    // Scratch for the scaled magnitude of the current frame.
    val scaledFrame = DoubleArray(nFreqBins)
    for (frame in 0 until nFrames) {
        val base = frame * nFreqBins
        for (bin in 0 until nFreqBins) {
            scaledFrame[bin] = values[base + bin].toDouble() * PCEN_AUDIO_SCALE
            state[bin] = coeff * scaledFrame[bin] + oneMinusB * state[bin]
            val agc = exp(-PCEN_GAIN * (logEps + ln(1.0 + state[bin] / PCEN_EPS)))
            values[base + bin] = (
                biasPow * (exp(PCEN_POWER * ln(1.0 + scaledFrame[bin] * agc / PCEN_BIAS)) - 1.0)
                ).toFloat()
        }
    }
}

/**
 * Subtract each frequency band's mean over time, then clip at zero.
 *
 * BatDetect2's denoise_spec_avg. [values] is frame-major.
 */
internal fun subtractBandMeanInPlace(
    values: FloatArray,
    nFrames: Int,
    nFreqBins: Int,
) {
    require(values.size == nFrames * nFreqBins) {
        "spectrogram size ${values.size} != $nFrames * $nFreqBins"
    }
    if (nFrames == 0) return
    for (bin in 0 until nFreqBins) {
        var sum = 0.0
        for (frame in 0 until nFrames) {
            sum += values[frame * nFreqBins + bin]
        }
        val mean = (sum / nFrames).toFloat()
        for (frame in 0 until nFrames) {
            val i = frame * nFreqBins + bin
            values[i] = max(0f, values[i] - mean)
        }
    }
}

/**
 * Peaks this close to either end of the clip are dropped. A longer pulse
 * widens the margin to half its length.
 */
private const val WINDOW_EDGE_MARGIN_SEC = 0.030

/** Half of a pulse length in seconds. A negative length counts as zero. */
private fun pulseHalfSec(pulseLengthS: Float): Double {
    val length = pulseLengthS.toDouble()
    return 0.5 * if (length > 0.0) length else 0.0
}

/**
 * True when [centreSec] is within [WINDOW_EDGE_MARGIN_SEC], or within
 * [halfSec], of either end of a clip of [durationSec] seconds.
 */
private fun centreNearWindowEdge(
    centreSec: Double,
    halfSec: Double,
    durationSec: Double,
): Boolean {
    val margin = maxOf(WINDOW_EDGE_MARGIN_SEC, halfSec)
    return centreSec <= margin || centreSec >= durationSec - margin
}

/**
 * BatFilterCNN smart-heterodyne peak: strongest clear presence peak.
 * Kept package-visible for unit tests.
 */
internal fun batFilterCnnSpectrogramChirps(
    presence: FloatArray,
    fmaxeHz: FloatArray,
    pulseLengthS: FloatArray,
    durationSec: Double,
    threshold: Float,
): List<SpectrogramChirp> {
    val nBins = presence.size
    require(fmaxeHz.size == nBins && pulseLengthS.size == nBins) {
        "presence $nBins, fmaxe ${fmaxeHz.size}, pulse ${pulseLengthS.size}"
    }
    require(durationSec > 0.0) { "clip duration must be > 0" }
    if (nBins < 3) return emptyList()

    var bestBin = -1
    var bestPresence = Float.NEGATIVE_INFINITY
    for (i in 1 until nBins - 1) {
        val p = presence[i]
        if (p < threshold || presence[i - 1] >= p || presence[i + 1] >= p) continue
        val centre = binCentreSec(i, nBins, durationSec)
        if (centreNearWindowEdge(centre, pulseHalfSec(pulseLengthS[i]), durationSec)) continue
        if (p > bestPresence || (p == bestPresence && (bestBin < 0 || i < bestBin))) {
            bestPresence = p
            bestBin = i
        }
    }
    if (bestBin < 0) return emptyList()
    return listOf(
        SpectrogramChirp(
            bestBin,
            binCentreSec(bestBin, nBins, durationSec),
            presence[bestBin],
            fmaxeHz[bestBin],
        ),
    )
}
