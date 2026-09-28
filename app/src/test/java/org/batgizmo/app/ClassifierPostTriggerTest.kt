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
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifierPostTriggerTest {

    private val batWindowSec = Settings.classifierWindowDurationSec(144000, 256000)!!
    private val birdWindowSec = Settings.classifierWindowDurationSec(144000, 48000)!!

    @Test
    fun battyBirdNetWindowIsNineSixteenthsOfASecond() {
        assertEquals(0.5625f, batWindowSec, 1e-6f)
    }

    @Test
    fun birdNetWindowIsThreeSeconds() {
        assertEquals(3.0f, birdWindowSec, 1e-6f)
    }

    @Test
    fun invalidGeometryYieldsNull() {
        assertNull(Settings.classifierWindowDurationSec(0, 256000))
        assertNull(Settings.classifierWindowDurationSec(144000, 0))
    }

    @Test
    fun oneSecondPostOnBatsCeilsToTwoWindows() {
        val post = Settings.ceiledClassifierPostTrigger(1000, batWindowSec)
        assertEquals(2, post.windowCount)
        assertEquals(1.125f, post.durationSec, 1e-6f)
    }

    @Test
    fun shortPostOnBatsIsStillOneWindow() {
        val post = Settings.ceiledClassifierPostTrigger(200, batWindowSec)
        assertEquals(1, post.windowCount)
        assertEquals(0.5625f, post.durationSec, 1e-6f)
    }

    @Test
    fun exactMultipleDoesNotGrow() {
        val post = Settings.ceiledClassifierPostTrigger(1125, batWindowSec)
        assertEquals(2, post.windowCount)
        assertEquals(1.125f, post.durationSec, 1e-6f)
    }

    @Test
    fun oneSecondPostOnBirdNetIsOneWindow() {
        val post = Settings.ceiledClassifierPostTrigger(1000, birdWindowSec)
        assertEquals(1, post.windowCount)
        assertEquals(3.0f, post.durationSec, 1e-6f)
    }

    @Test
    fun fiveSecondPostOnBirdNetIsTwoWindows() {
        val post = Settings.ceiledClassifierPostTrigger(5000, birdWindowSec)
        assertEquals(2, post.windowCount)
        assertEquals(6.0f, post.durationSec, 1e-6f)
    }

    @Test
    fun energyModeIsTheDefault() {
        val s = Settings()
        assertEquals(Settings.AutoTriggerModeOptions.ENERGY.value, s.autoTriggerMode)
        assertEquals(true, s.isEnergyAutoTrigger())
        assertEquals(false, s.isClassifierAutoTrigger())
    }

    @Test
    fun windowFallbackMatchesBattyBirdNet() {
        assertEquals(batWindowSec, Settings.CLASSIFIER_WINDOW_FALLBACK_SEC, 1e-6f)
    }
}
