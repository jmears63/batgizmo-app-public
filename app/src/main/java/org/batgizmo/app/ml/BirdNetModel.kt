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
import timber.log.Timber
import java.util.Calendar

/**
 * BirdNET acoustic [SimpleModel] plus a one-shot species-range (MData) filter.
 *
 * The geo TFLite is loaded lazily on the first [predict] that has a
 * [GeoSnapshot], run once for that lat/lon/week, then closed. Subsequent
 * predictions zero out scores for species below the occurrence threshold.
 *
 * If there is no snapshot yet, or the geo model cannot produce a mask,
 * [predict] returns all-zero scores (no detections) and logs a warning.
 *
 * Not thread-safe; use from a single infer worker.
 */
class BirdNetModel(
    assets: AssetManager,
    descriptor: MlModelDescriptor,
    numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
) : SimpleModel(assets, descriptor, numThreads) {

    /**
     * Where a [GeoSnapshot] came from (for logging / diagnostics).
     */
    enum class GeoSource {
        /** GUANO `Loc Position` from the open WAV (viewer). */
        GUANO,

        /** Current device fix from live GPS / locationFlow. */
        LIVE_GPS,
    }

    /**
     * Location and BirdNET week used for the species-range model.
     *
     * [week] is in `1..48` (four weeks per month) or `-1` for year-round.
     */
    data class GeoSnapshot(
        val latitude: Float,
        val longitude: Float,
        val week: Int,
        val source: GeoSource,
    ) {
        init {
            require(week == -1 || week in 1..48) {
                "BirdNET week must be -1 or in 1..48, got $week"
            }
        }
    }

    private val geoRelative: String? = descriptor.resourcePaths.geoModelRelativeOrNull
    private val geoThreshold: Float = descriptor.resourcePaths.geoThreshold

    /** Set once before the geo model runs; ignored after [geoResolved]. */
    @Volatile
    private var snapshot: GeoSnapshot? = null

    /** Index-aligned allow mask; null after a failed/permanent geo resolve. */
    private var geoMask: BooleanArray? = null

    /** True after a successful or failed one-shot geo run (not while waiting for a snapshot). */
    private var geoResolved: Boolean = false

    private var loggedMissingSnapshot: Boolean = false

    /**
     * Capture the location/week for the geo filter. First snapshot wins: later
     * calls are ignored so the species mask stays fixed for the session (one
     * MData run; avoids re-filtering as GPS updates). Cleared via [resetGeoFilter]
     * when starting a new file/live session.
     */
    fun setGeoSnapshot(snapshot: GeoSnapshot) {
        if (geoRelative == null || geoResolved || this.snapshot != null) return
        this.snapshot = snapshot
    }

    fun setGeoSnapshot(latitude: Double, longitude: Double, week: Int, source: GeoSource) {
        setGeoSnapshot(
            GeoSnapshot(
                latitude = latitude.toFloat(),
                longitude = longitude.toFloat(),
                week = week,
                source = source,
            )
        )
    }

    /**
     * Clear any geo snapshot/mask so a new location can be applied (e.g. opening
     * a different file in the viewer). Safe before or after [ensureGeoMask].
     */
    fun resetGeoFilter() {
        snapshot = null
        geoMask = null
        geoResolved = false
        loggedMissingSnapshot = false
    }

    override fun predict(window: FloatArray): FloatArray {
        val scores = super.predict(window)
        applyGeoFilter(scores)
        return scores
    }

    private fun applyGeoFilter(scores: FloatArray) {
        val mask = ensureGeoMask()
        if (mask == null) {
            scores.fill(0f)
            return
        }
        val n = minOf(scores.size, mask.size)
        for (i in 0 until n) {
            if (!mask[i]) scores[i] = 0f
        }
    }

    /**
     * Build the allow mask once. Closes the geo interpreter immediately after.
     * Returns null when detections must be suppressed (no snapshot yet, or geo
     * failure).
     */
    private fun ensureGeoMask(): BooleanArray? {
        if (geoResolved) return geoMask

        val relative = geoRelative
        if (relative == null) {
            geoResolved = true
            Timber.w("BirdNetModel: no geo model asset; suppressing detections")
            return null
        }

        val snap = snapshot
        if (snap == null) {
            if (!loggedMissingSnapshot) {
                loggedMissingSnapshot = true
                Timber.w("BirdNetModel: no geo snapshot; suppressing detections")
            }
            return null
        }

        geoResolved = true
        return try {
            val geo = createInterpreter(assets, descriptor.resourcePaths.root, relative, numThreads)
            try {
                val inputShape = geo.getInputTensor(0).shape()
                require(inputShape.last() == 3 || inputShape.contentEquals(intArrayOf(1, 3))) {
                    "Unexpected geo input shape ${inputShape.contentToString()}"
                }
                val outClasses = geo.getOutputTensor(0).shape().last()
                require(outClasses == numClasses) {
                    "Geo outputs ($outClasses) != acoustic classes ($numClasses)"
                }

                val input = arrayOf(
                    floatArrayOf(snap.latitude, snap.longitude, snap.week.toFloat())
                )
                val output = Array(1) { FloatArray(outClasses) }
                geo.run(input, output)

                val occurrence = output[0]
                val mask = BooleanArray(outClasses) { i -> occurrence[i] >= geoThreshold }
                val allowed = mask.count { it }
                Timber.i(
                    "BirdNetModel: geo filter source=${snap.source} " +
                        "lat=${snap.latitude} lon=${snap.longitude} week=${snap.week} " +
                        "threshold=$geoThreshold → $allowed / $outClasses species"
                )
                geoMask = mask
                mask
            } finally {
                geo.close()
            }
        } catch (e: Exception) {
            Timber.w(e, "BirdNetModel: geo model failed; suppressing detections")
            geoMask = null
            null
        }
    }

    companion object {
        /**
         * BirdNET week index for [calendar]: four weeks per month, clamped to
         * `[1, 48]`. Matches BirdNET-Analyzer's week convention.
         */
        fun weekOfYear(calendar: Calendar = Calendar.getInstance()): Int {
            val month = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH is 0-based
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val week = (month - 1) * 4 + (day - 1) / 7 + 1
            return week.coerceIn(1, 48)
        }
    }
}
