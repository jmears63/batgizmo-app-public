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

import org.json.JSONObject

/**
 * One classifier class from an Auto Id labels JSON (all language columns + flags).
 */
data class LabelCatalogEntry(
    val texts: List<String>,
    val discard: Boolean,
    val disableByDefault: Boolean,
) {
    /** Stable key: first text column (typically Latin). */
    val key: String get() = texts.first()

    fun displayName(languageIndex: Int): String {
        if (texts.isEmpty()) return ""
        val i = if (languageIndex in texts.indices) languageIndex else 0
        return texts[i]
    }
}

/**
 * Parsed labels table: language column names plus per-class rows and flags.
 */
data class LabelsTable(
    val description: List<String>,
    val entries: List<LabelCatalogEntry>,
) {
    val languages: List<String> get() = description
    val labelKeys: List<String> get() = entries.map { it.key }
    val labelDiscard: List<Boolean> get() = entries.map { it.discard }
    val labelDisabledByDefault: List<Boolean> get() = entries.map { it.disableByDefault }

    fun labelsFor(languageIndex: Int): List<String> {
        val col = coerceLanguageIndex(languageIndex)
        return entries.map { it.displayName(col) }
    }

    fun coerceLanguageIndex(languageIndex: Int): Int {
        if (description.isEmpty()) return 0
        return if (languageIndex in description.indices) languageIndex else 0
    }
}

/** Load the shared Auto Id labels JSON schema from a UTF-8 string. */
fun parseLabelsJson(jsonText: String): LabelsTable {
    val root = JSONObject(jsonText)
    val descriptionJson = root.getJSONArray("description")
    val description = List(descriptionJson.length()) { i ->
        descriptionJson.getString(i)
    }
    require(description.isNotEmpty()) { "labels JSON description must be non-empty" }

    val labelsJson = root.getJSONArray("labels")
    val entries = ArrayList<LabelCatalogEntry>(labelsJson.length())
    for (i in 0 until labelsJson.length()) {
        val entry = labelsJson.getJSONObject(i)
        val rowJson = entry.getJSONArray("text")
        require(rowJson.length() == description.size) {
            "labels[$i].text has ${rowJson.length()} names; expected ${description.size}"
        }
        entries.add(
            LabelCatalogEntry(
                texts = List(rowJson.length()) { j -> rowJson.getString(j) },
                discard = entry.optBoolean("discard", false),
                disableByDefault = entry.optBoolean("disable_by_default", false),
            )
        )
    }
    return LabelsTable(description, entries)
}
