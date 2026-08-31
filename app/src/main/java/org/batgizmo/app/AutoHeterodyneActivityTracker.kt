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

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import timber.log.Timber

/**
 * Auto heterodyne activity tracker: per-bin EWMA mean and variance of linear
 * power (τ = [ACTIVITY_TAU_S]). Active bins have σ/μ above [MIN_COEFF_VAR];
 * observation is the lowest-frequency active bin.
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
    private var activityDumped = false

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
        activityDumped = false
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
        activityDumped = false
    }

    /**
     * Ingest one spectrogram column ([band] in dB). Returns lowest-frequency
     * active bin, or null if none / not warm.
     */
    fun processColumn(band: FloatArray, dtSeconds: Float): Observation? {
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

        logBucketActivityIfNeeded()

        var lowestF = -1
        var lowestCv = 0f
        var lowestDb = Float.NEGATIVE_INFINITY
        for (f in 0 until nBins) {
            if (binHz(f) < MIN_FREQ_HZ)
                continue
            val mu = meanPower[f]
            if (mu < minMeanPower)
                continue
            val cv = sqrt(varPower[f]) / mu
            if (cv > MIN_COEFF_VAR && (lowestF < 0 || f < lowestF)) {
                lowestF = f
                lowestCv = cv
                lowestDb = band[f]
            }
        }
        if (lowestF < 0)
            return null
        return Observation((minFreqBucket + lowestF) * dfHz, lowestDb, lowestCv)
    }

    private fun isWarm(): Boolean = columnCount >= minWarmColumns

    /** One-shot activity dump after warm-up (no-op if already logged). */
    fun logBucketActivityIfNeeded() {
        if (activityDumped)
            return
        activityDumped = true
        logBucketActivity()
    }

    /** Log σ/μ activity per band bin once after warm-up (for tuning). */
    private fun logBucketActivity() {
        if (nBins < 1) {
            Timber.i("Auto-het bucket activity (after warm-up): no band geometry")
            return
        }
        val minMeanPower = dbToPower(MIN_MEAN_POWER_DB)
        val entries = buildList {
            for (f in 0 until nBins) {
                if (binHz(f) < MIN_FREQ_HZ)
                    continue
                val mu = meanPower[f]
                val activity =
                    if (mu >= minMeanPower)
                        sqrt(varPower[f]) / mu
                    else
                        0f
                add(binHz(f) / 1000f to activity)
            }
        }
        if (entries.isEmpty()) {
            Timber.i("Auto-het bucket activity (after warm-up): no bins")
            return
        }
        val totalChunks =
            (entries.size + ACTIVITY_LOG_CHUNK_SIZE - 1) / ACTIVITY_LOG_CHUNK_SIZE
        for (chunk in 0 until totalChunks) {
            val start = chunk * ACTIVITY_LOG_CHUNK_SIZE
            val end = minOf(start + ACTIVITY_LOG_CHUNK_SIZE, entries.size)
            val csv = buildString {
                if (chunk == 0)
                    append("freq,activity,graph\n")
                for (i in start until end) {
                    val (freqKHz, activity) = entries[i]
                    append(
                        "%.1f,%.3f,%s\n".format(
                            freqKHz,
                            activity,
                            activityGraphColumn(activity)
                        )
                    )
                }
            }
            Timber.i(
                "Auto-het bucket activity (after warm-up) (%d/%d)\n%s",
                chunk + 1,
                totalChunks,
                csv.trimEnd()
            )
        }
    }

    private fun binHz(f: Int): Float = (minFreqBucket + f) * dfHz

    /** Quoted pseudo-graph: [n] spaces + `.` or `*` if above [MIN_COEFF_VAR]. */
    private fun activityGraphColumn(activity: Float): String {
        val n = activity.toInt().coerceAtLeast(0)
        val symbol = if (activity > MIN_COEFF_VAR) '*' else '.'
        return "\"${" ".repeat(n)}$symbol\""
    }

    private fun dbToPower(db: Float): Float =
        10.0.pow(db / 10.0).toFloat()

    companion object {
        const val ACTIVITY_TAU_S = 0.5f
        /** Bucket entries per activity dump log line. */
        private const val ACTIVITY_LOG_CHUNK_SIZE = 128
        /** Ignore bins below this frequency (matches auto-het search band). */
        const val MIN_FREQ_HZ = 16_000f
        /** Minimum σ/μ (linear power) for an active bin. */
        const val MIN_COEFF_VAR = 5f
        /** Ignore bins whose EWMA power is below this dB level. */
        const val MIN_MEAN_POWER_DB = -30f
    }
}
