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

import org.batgizmo.app.FileWriter.SpeciesAutoIdHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeciesAutoIdTest {

    private val windowSec = 1.0

    @Test
    fun mergesSameSpeciesKeepingHighestScore() {
        val hits = listOf(
            SpeciesAutoIdHit(100.0, "Myotis daubentonii", 0.4f),
            SpeciesAutoIdHit(100.5, "Myotis daubentonii", 0.9f),
            SpeciesAutoIdHit(101.0, "Pipistrellus nathusii", 0.7f),
        )
        assertEquals(
            "Myotis daubentonii, Pipistrellus nathusii",
            FileWriter.buildSpeciesAutoId(
                hits = hits,
                windowSec = windowSec,
                fileStartEpochSec = 100.0,
                fileEndEpochSec = 103.0,
            ),
        )
    }

    @Test
    fun ordersByDescendingScoreAndCapsAtTen() {
        val hits = (0 until 12).map { i ->
            SpeciesAutoIdHit(
                windowStartEpochSec = 100.0 + i * 0.1,
                labelKey = "Species species$i",
                confidence = i / 12f,
            )
        }
        val result = FileWriter.buildSpeciesAutoId(
            hits = hits,
            windowSec = windowSec,
            fileStartEpochSec = 100.0,
            fileEndEpochSec = 110.0,
        )!!
        val names = result.split(", ")
        assertEquals(10, names.size)
        assertEquals("Species species11", names.first())
        assertEquals("Species species2", names.last())
    }

    @Test
    fun excludesWindowsOutsideFileSpan() {
        val hits = listOf(
            SpeciesAutoIdHit(90.0, "Outside early", 0.99f),
            SpeciesAutoIdHit(100.0, "Inside", 0.5f),
            SpeciesAutoIdHit(120.0, "Outside late", 0.99f),
        )
        assertEquals(
            "Inside",
            FileWriter.buildSpeciesAutoId(
                hits = hits,
                windowSec = windowSec,
                fileStartEpochSec = 100.0,
                fileEndEpochSec = 110.0,
            ),
        )
    }

    @Test
    fun openingTriggerBypassOnlyWhenAllowed() {
        val triggerStart = 95.0
        val hits = listOf(
            SpeciesAutoIdHit(triggerStart, "Myotis daubentonii", 0.8f),
        )
        assertNull(
            FileWriter.buildSpeciesAutoId(
                hits = hits,
                windowSec = windowSec,
                fileStartEpochSec = 100.0,
                fileEndEpochSec = 110.0,
                openingTriggerWindowStartEpochSec = triggerStart,
                allowOpeningTrigger = false,
            ),
        )
        assertEquals(
            "Myotis daubentonii",
            FileWriter.buildSpeciesAutoId(
                hits = hits,
                windowSec = windowSec,
                fileStartEpochSec = 100.0,
                fileEndEpochSec = 110.0,
                openingTriggerWindowStartEpochSec = triggerStart,
                allowOpeningTrigger = true,
            ),
        )
    }

    @Test
    fun openingTriggerMatchesWithSmallEpochEpsilon() {
        assertTrue(FileWriter.sameClassifierWindowStart(100.0, 100.0 + 5e-4))
        val hits = listOf(
            SpeciesAutoIdHit(100.0, "Myotis daubentonii", 0.8f),
        )
        assertEquals(
            "Myotis daubentonii",
            FileWriter.buildSpeciesAutoId(
                hits = hits,
                windowSec = windowSec,
                fileStartEpochSec = 105.0,
                fileEndEpochSec = 110.0,
                openingTriggerWindowStartEpochSec = 100.0 + 5e-4,
                allowOpeningTrigger = true,
            ),
        )
    }

    @Test
    fun usesCommaAndSpaceSeparator() {
        val hits = listOf(
            SpeciesAutoIdHit(100.0, "Aaa aaa", 0.9f),
            SpeciesAutoIdHit(100.0, "Bbb bbb", 0.5f),
        )
        assertEquals(
            "Aaa aaa, Bbb bbb",
            FileWriter.buildSpeciesAutoId(
                hits = hits,
                windowSec = windowSec,
                fileStartEpochSec = 100.0,
                fileEndEpochSec = 110.0,
            ),
        )
    }
}
