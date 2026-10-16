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

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grandfathering of pre-checkbox Auto Id prefs into multi-family selection.
 */
class AutoIdSettingsMigrationTest {

    private val keyAutoId = booleanPreferencesKey("autoId")
    private val keyMlModelId = stringPreferencesKey("autoIdModelId")
    private val keyAutoIdLanguage = intPreferencesKey("autoIdLanguageIndex")
    private val keyAutoIdFamilyPrefs = stringPreferencesKey("autoIdFamilyPrefs")
    private val keyAutoIdEnabledFamilyIds = stringPreferencesKey("autoIdEnabledFamilyIds")
    private val keyBbnSuppresions = stringPreferencesKey("bbnSuppresions")

    @Test
    fun grandfatherSingleModelSelection_checksThatFamilyAndSyncsPref() {
        val prefs = mutablePreferencesOf(
            keyAutoId to true,
            keyMlModelId to "battybirdnet/eu-256khz",
            keyAutoIdLanguage to 0,
            keyAutoIdFamilyPrefs to """{"birdnet":{"variant":"birdnet/global-6k-v2.4","language":1}}""",
        )
        val settings = Settings()
        val rewrite = settings.copyFromPreferences(prefs)

        assertTrue(rewrite)
        assertTrue(settings.autoIdEnabled)
        assertEquals(listOf("battybirdnet"), settings.autoIdEnabledFamilyIds)
        assertEquals("battybirdnet/eu-256khz", settings.autoIdModelId)
        assertEquals(0, settings.autoIdLanguage)
        assertEquals(
            AutoIdFamilyPref("battybirdnet/eu-256khz", 0),
            settings.autoIdFamilyPrefs["battybirdnet"],
        )
        // Other family memories preserved but not enabled.
        assertEquals(
            AutoIdFamilyPref("birdnet/global-6k-v2.4", 1),
            settings.autoIdFamilyPrefs["birdnet"],
        )
        assertFalse("birdnet" in settings.autoIdEnabledFamilyIds)
    }

    @Test
    fun grandfatherBlankModelIdAsDisabled_stillKeepsDefaultModel() {
        val prefs = mutablePreferencesOf(
            keyMlModelId to "",
        )
        val settings = Settings()
        val rewrite = settings.copyFromPreferences(prefs)

        assertTrue(rewrite)
        assertFalse(settings.autoIdEnabled)
        assertEquals(Settings.DEFAULT_AUTO_ID_MODEL_ID, settings.autoIdModelId)
        assertEquals(
            listOf(Settings.DEFAULT_AUTO_ID_FAMILY_ID),
            settings.autoIdEnabledFamilyIds,
        )
    }

    @Test
    fun legacyFlatBbnSuppressions_nestUnderSelectedBatModel() {
        val prefs = mutablePreferencesOf(
            keyAutoId to true,
            keyMlModelId to "battybirdnet/sweden-256khz",
            keyAutoIdLanguage to 1,
            keyBbnSuppresions to """{"Pipistrellus pipistrellus":true,"Noise":false}""",
        )
        val settings = Settings()
        settings.copyFromPreferences(prefs)

        assertEquals(
            mapOf("Pipistrellus pipistrellus" to true, "Noise" to false),
            settings.autoIdSuppressions["battybirdnet/sweden-256khz"],
        )
    }

    @Test
    fun alreadyMigratedEnabledFamilies_notRewritten() {
        val prefs = mutablePreferencesOf(
            keyAutoId to true,
            keyMlModelId to "birdnet/global-6k-v2.4",
            keyAutoIdLanguage to 1,
            keyAutoIdEnabledFamilyIds to """["birdnet","battybirdnet"]""",
            keyAutoIdFamilyPrefs to
                """{"birdnet":{"variant":"birdnet/global-6k-v2.4","language":1},""" +
                """"battybirdnet":{"variant":"battybirdnet/uk-256khz","language":0}}""",
        )
        val settings = Settings()
        val rewrite = settings.copyFromPreferences(prefs)

        assertFalse(rewrite)
        assertEquals(listOf("birdnet", "battybirdnet"), settings.autoIdEnabledFamilyIds)
        assertEquals("birdnet/global-6k-v2.4", settings.autoIdModelId)
        assertEquals(1, settings.autoIdLanguage)
    }
}
