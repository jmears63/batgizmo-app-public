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

import org.batgizmo.app.AutoHeterodyneActivityTracker.Companion.ACTIVITY_TAU_S
import org.batgizmo.app.AutoHeterodyneActivityTracker.Companion.MIN_COEFF_VAR
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Auto heterodyne activity tracker: per-bin EWMA mean and variance of linear
 * power (τ = [ACTIVITY_TAU_S]). Active bins have σ/μ above [MIN_COEFF_VAR].
 * Contiguous active runs in one time column are activity spans; the span with
 * the highest peak activity wins. Observation within that span depends on
 * [Settings.AutoHeterodyneModeOptions]:
 * - Hockey Stick: lowest-frequency bin
 * - Rhinolophus: highest-frequency bin
 * - Generic/Myotis: peak-activity bin
 */
class AutoHeterodyneActivityTracker {
    data class Observation(val hz: Float, val db: Float, val coeffVar: Float)

    private var meanPower = FloatArray(0)
    private var varPower = FloatArray(0)
    private var nBins = 0
    private var minFreqBucket = 0
    private var dfHz = 0f
    private var columnCount = 0
    private var minWarmColumns = 8
    private var havePrev = false
    private var scratchBand = FloatArray(0)

    fun reset() {
        meanPower = FloatArray(0)
        varPower = FloatArray(0)
        nBins = 0
        minFreqBucket = 0
        dfHz = 0f
        columnCount = 0
        minWarmColumns = 8
        havePrev = false
        scratchBand = FloatArray(0)
    }

    fun bandScratch(size: Int): FloatArray {
        if (scratchBand.size != size)
            scratchBand = FloatArray(size)
        return scratchBand
    }

    fun ensureGeometry(
        bandBins: Int,
        minFreqBucket: Int,
        dfHz: Float,
        timeIntervalSeconds: Float
    ) {
        val warm = if (timeIntervalSeconds > 0f)
            ceil(ACTIVITY_TAU_S / timeIntervalSeconds).toInt().coerceIn(2, 128)
        else
            2
        if (bandBins == nBins &&
            minFreqBucket == this.minFreqBucket &&
            dfHz == this.dfHz &&
            warm == minWarmColumns
        ) {
            return
        }
        this.nBins = bandBins
        this.minFreqBucket = minFreqBucket
        this.dfHz = dfHz
        this.minWarmColumns = warm
        meanPower = FloatArray(bandBins)
        varPower = FloatArray(bandBins)
        columnCount = 0
        havePrev = false
        scratchBand = FloatArray(bandBins)
    }

    /**
     * Ingest one spectrogram column ([band] in dB). Returns an observation from
     * the highest-peak activity span per [mode], or null if none / not warm.
     */
    fun processColumn(
        band: FloatArray,
        dtSeconds: Float,
        mode: Int = Settings.AutoHeterodyneModeOptions.DEFAULT.value
    ): Observation? {
        if (nBins < 1 || band.size != nBins || dfHz <= 0f || dtSeconds <= 0f)
            return null

        val alpha = (1.0 - exp((-dtSeconds / ACTIVITY_TAU_S).toDouble())).toFloat()
            .coerceIn(0f, 1f)
        val minMeanPower = dbToPower(MIN_MEAN_POWER_DB)

        if (!havePrev) {
            for (f in 0 until nBins) {
                if (binHz(f) < MIN_FREQ_HZ)
                    continue
                meanPower[f] = dbToPower(band[f])
                varPower[f] = 0f
            }
            havePrev = true
            columnCount = 1
            return null
        }

        for (f in 0 until nBins) {
            if (binHz(f) < MIN_FREQ_HZ)
                continue
            val p = dbToPower(band[f])
            val prevMean = meanPower[f]
            val newMean = prevMean + alpha * (p - prevMean)
            val dev = p - prevMean
            varPower[f] += alpha * (dev * dev - varPower[f])
            meanPower[f] = newMean
        }
        columnCount++

        if (!isWarm())
            return null

        return selectObservationFromActivitySpans(band, minMeanPower, mode)
    }

    /**
     * Contiguous active bins form spans. Choose the span with the highest peak
     * σ/μ; pick the observation bin within that span according to [mode].
     */
    private fun selectObservationFromActivitySpans(
        band: FloatArray,
        minMeanPower: Float,
        mode: Int
    ): Observation? {
        var bestPeakCv = MIN_COEFF_VAR
        var bestObsF = -1
        var bestObsCv = 0f
        var bestObsDb = Float.NEGATIVE_INFINITY

        var spanStart = -1
        var spanEnd = -1
        var spanPeakF = -1
        var spanPeakCv = 0f
        var spanStartCv = 0f
        var spanStartDb = Float.NEGATIVE_INFINITY
        var spanEndCv = 0f
        var spanEndDb = Float.NEGATIVE_INFINITY
        var spanPeakDb = Float.NEGATIVE_INFINITY

        fun closeSpan() {
            if (spanStart < 0 || spanPeakF < 0)
                return
            if (spanPeakCv > bestPeakCv) {
                bestPeakCv = spanPeakCv
                when (Settings.AutoHeterodyneModeOptions.coerce(mode)) {
                    Settings.AutoHeterodyneModeOptions.HOCKEY_STICK.value -> {
                        bestObsF = spanStart
                        bestObsCv = spanStartCv
                        bestObsDb = spanStartDb
                    }
                    Settings.AutoHeterodyneModeOptions.RHINOLOPHUS.value -> {
                        bestObsF = spanEnd
                        bestObsCv = spanEndCv
                        bestObsDb = spanEndDb
                    }
                    else -> {
                        // Generic/Myotis: peak activity within the span.
                        bestObsF = spanPeakF
                        bestObsCv = spanPeakCv
                        bestObsDb = spanPeakDb
                    }
                }
            }
            spanStart = -1
            spanEnd = -1
            spanPeakF = -1
        }

        for (f in 0 until nBins) {
            if (binHz(f) < MIN_FREQ_HZ) {
                closeSpan()
                continue
            }
            val mu = meanPower[f]
            if (mu < minMeanPower) {
                closeSpan()
                continue
            }
            val cv = sqrt(varPower[f]) / mu
            if (cv <= MIN_COEFF_VAR) {
                closeSpan()
                continue
            }
            if (spanStart < 0) {
                spanStart = f
                spanStartCv = cv
                spanStartDb = band[f]
                spanPeakF = f
                spanPeakCv = cv
                spanPeakDb = band[f]
            } else if (cv > spanPeakCv) {
                spanPeakF = f
                spanPeakCv = cv
                spanPeakDb = band[f]
            }
            spanEnd = f
            spanEndCv = cv
            spanEndDb = band[f]
        }
        closeSpan()

        if (bestObsF < 0)
            return null
        return Observation(
            (minFreqBucket + bestObsF) * dfHz,
            bestObsDb,
            bestObsCv
        )
    }

    private fun isWarm(): Boolean = columnCount >= minWarmColumns

    private fun binHz(f: Int): Float = (minFreqBucket + f) * dfHz

    private fun dbToPower(db: Float): Float =
        10.0.pow(db / 10.0).toFloat()

    companion object {
        const val ACTIVITY_TAU_S = 0.5f
        /** Ignore bins below this frequency (matches auto-het search band). */
        const val MIN_FREQ_HZ = 16_000f
        /** Minimum σ/μ (linear power) for an active bin. */
        const val MIN_COEFF_VAR = 5f
        /** Ignore bins whose EWMA power is below this dB level. */
        const val MIN_MEAN_POWER_DB = -30f
    }
}
