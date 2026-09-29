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
import org.junit.Test

class SpeciesCodeTest {

    @Test
    fun twoWordScientificName() {
        assertEquals(
            "myodau",
            FileWriter.speciesCodeFromScientificName("Myotis daubentonii"),
        )
        assertEquals(
            "barbar",
            FileWriter.speciesCodeFromScientificName("Barbastella barbastellus"),
        )
        assertEquals(
            "pipnat",
            FileWriter.speciesCodeFromScientificName("Pipistrellus nathusii"),
        )
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(
            "nycnoc",
            FileWriter.speciesCodeFromScientificName("  Nyctalus noctula  "),
        )
    }

    @Test
    fun stripsNonLettersInsideWords() {
        assertEquals(
            "myodau",
            FileWriter.speciesCodeFromScientificName("Myotis daubentonii."),
        )
    }

    @Test
    fun blankOrOddCasesYieldEmpty() {
        assertEquals("", FileWriter.speciesCodeFromScientificName(""))
        assertEquals("", FileWriter.speciesCodeFromScientificName("   "))
        assertEquals("", FileWriter.speciesCodeFromScientificName("Foo"))
        assertEquals("", FileWriter.speciesCodeFromScientificName("Myotis"))
        assertEquals(
            "",
            FileWriter.speciesCodeFromScientificName("Myotis mystacinus mystacinus"),
        )
        assertEquals("", FileWriter.speciesCodeFromScientificName("Ab Xy"))
        assertEquals("", FileWriter.speciesCodeFromScientificName("Myotis xy"))
        assertEquals("", FileWriter.speciesCodeFromScientificName("A daubentonii"))
        assertEquals("", FileWriter.speciesCodeFromScientificName("123 456"))
        assertEquals("", FileWriter.speciesCodeFromScientificName("Myotis/daubentonii"))
        assertEquals("", FileWriter.speciesCodeFromScientificName(".. .."))
    }
}
