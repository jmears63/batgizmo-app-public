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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SunriseSunsetTest {

    private val london = ZoneId.of("Europe/London")

    @Test
    fun londonSummerSolstice_hasPlausibleGeometricTimes() {
        // Geometric (no refraction) is a few minutes earlier/later than published
        // "sunrise/sunset" tables, which use ~90.833°. Ballpark only.
        val times = SunriseSunset.forLocation(
            latitudeDeg = 51.5074,
            longitudeDeg = -0.1278,
            date = LocalDate.of(2024, 6, 21),
            zoneId = london
        )
        assertNotNull(times.sunrise)
        assertNotNull(times.sunset)
        val rise = times.sunrise!!
        val set = times.sunset!!
        assertTrue(rise < set)
        assertTrue(rise.isAfter(LocalTime.of(3, 30)))
        assertTrue(rise.isBefore(LocalTime.of(5, 30)))
        assertTrue(set.isAfter(LocalTime.of(20, 0)))
        assertTrue(set.isBefore(LocalTime.of(22, 30)))
    }

    @Test
    fun londonWinterSolstice_hasShorterDayThanSummer() {
        val summer = SunriseSunset.forLocation(
            51.5074, -0.1278, LocalDate.of(2024, 6, 21), london
        )
        val winter = SunriseSunset.forLocation(
            51.5074, -0.1278, LocalDate.of(2024, 12, 21), london
        )
        assertNotNull(summer.sunrise)
        assertNotNull(summer.sunset)
        assertNotNull(winter.sunrise)
        assertNotNull(winter.sunset)
        val summerLen =
            java.time.Duration.between(summer.sunrise, summer.sunset).toMinutes()
        val winterLen =
            java.time.Duration.between(winter.sunrise, winter.sunset).toMinutes()
        assertTrue(summerLen > winterLen)
    }

    @Test
    fun arcticMidwinter_returnsNullWhenSunDoesNotRise() {
        // Near the Arctic Circle in midwinter: polar night → no geometric rise/set.
        val times = SunriseSunset.forLocation(
            latitudeDeg = 78.0,
            longitudeDeg = 15.0,
            date = LocalDate.of(2024, 12, 21),
            zoneId = ZoneId.of("UTC")
        )
        assertNull(times.sunrise)
        assertNull(times.sunset)
    }

    @Test
    fun outOfRangeCoordinates_returnNull() {
        val times = SunriseSunset.forLocation(
            latitudeDeg = 999.0,
            longitudeDeg = 0.0,
            date = LocalDate.of(2024, 6, 21),
            zoneId = ZoneId.of("UTC")
        )
        assertEquals(null, times.sunrise)
        assertEquals(null, times.sunset)
    }
}
