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

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Geometric sunrise and sunset: the instant the centre of the solar disc is on the
 * horizon (zenith angle 90°), with no atmospheric-refraction correction.
 *
 * Equations follow the NOAA Solar Calculator approach
 * (https://gml.noaa.gov/grad/solcalc/).
 *
 * Returns null times for polar day/night (sun never rises or never sets), or when
 * lat/lon are out of range.
 */
object SunriseSunset {

    data class Times(val sunrise: LocalTime?, val sunset: LocalTime?)

    /** Geometric horizon: centre of the disc, no refraction. */
    private const val GEOMETRIC_ZENITH_DEG = 90.0

    fun forLocation(
        latitudeDeg: Double,
        longitudeDeg: Double,
        date: LocalDate = LocalDate.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Times {
        if (latitudeDeg !in -90.0..90.0 || longitudeDeg !in -180.0..180.0) {
            return Times(null, null)
        }

        // DST-aware offset for this calendar date in the device zone.
        val offsetMinutes =
            date.atStartOfDay(zoneId).offset.totalSeconds / 60.0

        val sunriseUtc = sunriseSetUtcMinutes(
            rise = true,
            date = date,
            latitudeDeg = latitudeDeg,
            longitudeDeg = longitudeDeg
        )
        val sunsetUtc = sunriseSetUtcMinutes(
            rise = false,
            date = date,
            latitudeDeg = latitudeDeg,
            longitudeDeg = longitudeDeg
        )

        return Times(
            sunrise = sunriseUtc?.let { utcMinutesToLocalTime(it, offsetMinutes) },
            sunset = sunsetUtc?.let { utcMinutesToLocalTime(it, offsetMinutes) }
        )
    }

    /**
     * Sunrise or sunset as minutes from 00:00 UTC on [date], or null if undefined
     * (polar day/night). One refinement pass matches the NOAA calculator.
     */
    private fun sunriseSetUtcMinutes(
        rise: Boolean,
        date: LocalDate,
        latitudeDeg: Double,
        longitudeDeg: Double
    ): Double? {
        val jd = julianDay(date)
        val first = sunriseSetUtcMinutesForJd(rise, jd, latitudeDeg, longitudeDeg)
            ?: return null
        // Refine using a Julian day near the event (NOAA second pass).
        val refinedJd = julianDay(date) + first / 1440.0
        return sunriseSetUtcMinutesForJd(rise, refinedJd, latitudeDeg, longitudeDeg)
            ?: first
    }

    private fun sunriseSetUtcMinutesForJd(
        rise: Boolean,
        jd: Double,
        latitudeDeg: Double,
        longitudeDeg: Double
    ): Double? {
        val t = julianCentury(jd)
        val eqTime = equationOfTimeMinutes(t)
        val declRad = sunDeclinationRad(t)
        val haRad = hourAngleSunriseRad(latitudeDeg, declRad) ?: return null
        val haDeg = if (rise) Math.toDegrees(haRad) else -Math.toDegrees(haRad)
        // Minutes past 00:00 UTC.
        return 720.0 - 4.0 * (longitudeDeg + haDeg) - eqTime
    }

    /**
     * Hour angle at geometric sunrise (radians), or null when the sun does not
     * cross the geometric horizon that day.
     */
    private fun hourAngleSunriseRad(latitudeDeg: Double, declRad: Double): Double? {
        val latRad = Math.toRadians(latitudeDeg)
        // cos(90°) = 0 → arg = -tan(lat) * tan(decl)
        val arg =
            (cos(Math.toRadians(GEOMETRIC_ZENITH_DEG)) /
                (cos(latRad) * cos(declRad))) -
                tan(latRad) * tan(declRad)
        if (arg < -1.0 || arg > 1.0) return null
        return acos(arg)
    }

    private fun equationOfTimeMinutes(t: Double): Double {
        val epsilonRad = Math.toRadians(obliquityCorrectionDeg(t))
        val l0Rad = Math.toRadians(geomMeanLongSunDeg(t))
        val e = eccentricityEarthOrbit(t)
        val mRad = Math.toRadians(geomMeanAnomalySunDeg(t))
        val y = tan(epsilonRad / 2.0).let { it * it }
        val sin2l0 = sin(2.0 * l0Rad)
        val sinm = sin(mRad)
        val cos2l0 = cos(2.0 * l0Rad)
        val sin4l0 = sin(4.0 * l0Rad)
        val sin2m = sin(2.0 * mRad)
        val eTimeRad =
            y * sin2l0 - 2.0 * e * sinm + 4.0 * e * y * sinm * cos2l0 -
                0.5 * y * y * sin4l0 - 1.25 * e * e * sin2m
        return Math.toDegrees(eTimeRad) * 4.0 // degrees → minutes of time
    }

    private fun sunDeclinationRad(t: Double): Double {
        val eRad = Math.toRadians(obliquityCorrectionDeg(t))
        val lambdaRad = Math.toRadians(sunApparentLongDeg(t))
        return asin(sin(eRad) * sin(lambdaRad))
    }

    private fun sunApparentLongDeg(t: Double): Double {
        val o = sunTrueLongDeg(t)
        val omega = 125.04 - 1934.136 * t
        return o - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
    }

    private fun sunTrueLongDeg(t: Double): Double =
        geomMeanLongSunDeg(t) + sunEquationOfCenterDeg(t)

    private fun sunEquationOfCenterDeg(t: Double): Double {
        val mRad = Math.toRadians(geomMeanAnomalySunDeg(t))
        return sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2.0 * mRad) * (0.019993 - 0.000101 * t) +
            sin(3.0 * mRad) * 0.000289
    }

    private fun geomMeanLongSunDeg(t: Double): Double {
        var l0 = 280.46646 + t * (36000.76983 + 0.0003032 * t)
        l0 %= 360.0
        if (l0 < 0) l0 += 360.0
        return l0
    }

    private fun geomMeanAnomalySunDeg(t: Double): Double =
        357.52911 + t * (35999.05029 - 0.0001537 * t)

    private fun eccentricityEarthOrbit(t: Double): Double =
        0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun meanObliquityOfEclipticDeg(t: Double): Double {
        val seconds =
            21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        return 23.0 + (26.0 + seconds / 60.0) / 60.0
    }

    private fun obliquityCorrectionDeg(t: Double): Double {
        val e0 = meanObliquityOfEclipticDeg(t)
        val omega = 125.04 - 1934.136 * t
        return e0 + 0.00256 * cos(Math.toRadians(omega))
    }

    /** Julian Day for calendar [date] at 0h UTC. */
    private fun julianDay(date: LocalDate): Double {
        var y = date.year
        var m = date.monthValue
        val d = date.dayOfMonth
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) +
            floor(30.6001 * (m + 1)) +
            d + b - 1524.5
    }

    private fun julianCentury(jd: Double): Double =
        (jd - 2451545.0) / 36525.0

    private fun utcMinutesToLocalTime(utcMinutes: Double, offsetMinutes: Double): LocalTime {
        var local = utcMinutes + offsetMinutes
        // Normalise into [0, 1440).
        local %= 1440.0
        if (local < 0) local += 1440.0
        val totalSeconds = (local * 60.0).toInt().coerceIn(0, 24 * 3600 - 1)
        return LocalTime.ofSecondOfDay(totalSeconds.toLong())
    }
}
