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
import org.batgizmo.app.Settings
import timber.log.Timber

/**
 * Resolves the runtime Auto Id model set: checked Settings families (each with
 * its preferred variant) plus any internal companions (e.g. BatCNN).
 *
 * Callers must [MlCatalog.ensureInitialized] first.
 */
object MlActiveModels {
    /**
     * Catalog ids of internal companions to run alongside user-selected models
     * when Auto Id is enabled. Empty until companions are registered.
     */
    fun companionModelIds(): List<String> = emptyList()

    /**
     * Descriptors for [Settings.autoIdEnabledFamilyIds] (variant from
     * [Settings.autoIdFamilyPrefs] or family default), then companions.
     * Empty when no families are checked or none resolve.
     */
    fun resolve(assets: AssetManager, settings: Settings): List<MlModelDescriptor> {
        val all = MlCatalog.listDescriptors(assets)
        val byFamily = all.groupBy { it.familyId }
        val seen = linkedSetOf<String>()
        val selected = ArrayList<MlModelDescriptor>()

        for (familyId in settings.autoIdEnabledFamilyIds) {
            val variants = byFamily[familyId]
            if (variants.isNullOrEmpty()) {
                Timber.w("MlActiveModels: enabled family $familyId not in catalog")
                continue
            }
            val sorted = variants.sortedWith(
                compareBy<MlModelDescriptor> { it.id != it.familyDefaultVariantId }
                    .thenBy { it.displayName }
            )
            val pref = settings.autoIdFamilyPrefs[familyId]
            val chosen = pref?.variantId
                ?.let { id -> sorted.firstOrNull { it.id == id } }
                ?: sorted.first()
            if (chosen.id in seen) continue
            seen.add(chosen.id)
            selected.add(chosen)
        }

        for (id in companionModelIds()) {
            if (id in seen) continue
            try {
                val desc = MlCatalog.resolveDescriptor(assets, id)
                seen.add(desc.id)
                selected.add(desc)
            } catch (e: Exception) {
                Timber.w(e, "MlActiveModels: companion $id not available")
            }
        }
        return selected
    }
}
