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

import android.content.Context
import timber.log.Timber

/**
 * Loads spectrogram colour-map CSV assets, packs them to RGB565, and installs
 * them into the native layer for colour mapping / amplitude-graph stroke colour.
 */
class ColourMapHelper(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        init {
            System.loadLibrary("batgizmo-native")
        }

        /** Index into the colour map used for the amplitude-graph stroke colour. */
        private const val AMPLITUDE_GRAPH_COLOUR_MAP_INDEX = 180

        @OptIn(ExperimentalUnsignedTypes::class)
        private external fun nativeInitialize(
            colourMap: ShortArray,
            mapEntries: Int,
            amplitudeGraphColour: Short
        ): Int

        private external fun nativeSetColourMap(
            colourMap: ShortArray,
            mapEntries: Int,
            amplitudeGraphColour: Short
        ): Int
    }

    /** Entry count of the colour map currently installed in native code; null until first apply. */
    var mapSize: Int? = null
        private set

    /** Colour map id currently installed in native code; null until first apply. */
    private var appliedColourMapId: Int? = null

    /**
     * One-shot native init with [colourMapId]. Safe to call once per process from the ViewModel.
     */
    fun initialize(colourMapId: Int) {
        val colourMap = loadRgb565(colourMapId)
        mapSize = colourMap.size
        appliedColourMapId = colourMapId
        Timber.d(
            "Setting colourMapSize to $mapSize on ColourMapHelper instance ${
                System.identityHashCode(this)
            }."
        )
        val rc = nativeInitialize(
            colourMap,
            colourMap.size,
            amplitudeColourFromMap(colourMap)
        )
        check(rc == 0) { "native layer initialization must succeed" }
    }

    /**
     * Install [colourMapId] into Kotlin state and native code.
     * Returns true if the installed map changed.
     */
    fun apply(colourMapId: Int): Boolean {
        if (appliedColourMapId == colourMapId && mapSize != null)
            return false

        val colourMap = loadRgb565(colourMapId)
        mapSize = colourMap.size
        appliedColourMapId = colourMapId
        val rc = nativeSetColourMap(
            colourMap,
            colourMap.size,
            amplitudeColourFromMap(colourMap)
        )
        check(rc == 0) { "nativeSetColourMap must succeed" }
        Timber.d("Applied colour map id=$colourMapId size=$mapSize")
        return true
    }

    /** Load a colour-map CSV and convert it to RGB565 entries for native code. */
    private fun loadRgb565(colourMapId: Int): ShortArray {
        val filename = Settings.ColourMapOptions.fromValue(colourMapId).assetFilename
        var mapRows = readCsv(filename)
        require(mapRows.size >= 64) { "the colour map contains too few colours" }

        // It's prudent to sort the rows into ascending order:
        mapRows = mapRows.sortedBy { it[0] as Float }

        val colourMap = ShortArray(mapRows.size)     // JNI doesn't support UShort.
        for ((i, entry) in mapRows.withIndex()) {
            val r: Int = entry[1] as Int
            val g: Int = entry[2] as Int
            val b: Int = entry[3] as Int
            colourMap[i] = rgbToRGB565(r, g, b)
        }
        return colourMap
    }

    private fun readCsv(filename: String): List<Array<Any>> {
        val result = mutableListOf<Array<Any>>()
        appContext.assets.open(filename).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val values = line.split(",").map { it.trim() }
                if (values.size == 4) {
                    val x = values[0].toFloat()
                    val r = values[1].toInt()
                    val g = values[2].toInt()
                    val b = values[3].toInt()
                    result.add(arrayOf(x, r, g, b))
                }
            }
        }
        return result
    }

    private fun amplitudeColourFromMap(colourMap: ShortArray): Short =
        colourMap[minOf(AMPLITUDE_GRAPH_COLOUR_MAP_INDEX, colourMap.lastIndex)]

    /** Convert an 8 bit RGB colour to RGB565. */
    private fun rgbToRGB565(red: Int, green: Int, blue: Int): Short {
        val r5 = (red shr 3) and 0x1F   // Convert 8-bit red to 5-bit
        val g6 = (green shr 2) and 0x3F // Convert 8-bit green to 6-bit
        val b5 = (blue shr 3) and 0x1F  // Convert 8-bit blue to 5-bit

        return ((r5 shl 11) or (g6 shl 5) or b5).toShort() // Pack into 16-bit value
    }
}
