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
 * Resolves the runtime model set: checked Settings families (each with its
 * preferred variant) when Auto Id is on, then any `liveSystem` families when
 * the caller asks for them.
 *
 * Callers must [MlCatalog.ensureInitialized] first. The caller decides whether
 * this session should run `liveSystem` models.
 */
object MlActiveModels {
    /**
     * Catalog ids of internal companions to run alongside user-selected models
     * when Auto Id is enabled. Empty until companions are registered.
     */
    fun companionModelIds(): List<String> = emptyList()

    /**
     * True when a `liveSystem` model can ingest [sampleRateHz].
     * Callers must [MlCatalog.ensureInitialized] first.
     */
    fun liveSystemAccepts(assets: AssetManager, sampleRateHz: Int): Boolean {
        if (sampleRateHz <= 0) return false
        return liveSystemVariants(MlCatalog.listDescriptors(assets))
            .any { sampleRateHz >= it.minSampleRateHz }
    }

    /**
     * User-selected descriptors when Auto Id is on, then `liveSystem` models
     * when [includeLiveSystemModels] is true, then companions. Empty when
     * neither source contributes a model.
     *
     * @param includeLiveSystemModels true when this session should run
     * internal `liveSystem` models. The caller has already decided that.
     */
    fun resolve(
        assets: AssetManager,
        settings: Settings,
        includeLiveSystemModels: Boolean = false,
    ): List<MlModelDescriptor> {
        val all = MlCatalog.listDescriptors(assets)
        val byFamily = all.groupBy { it.familyId }
        val seen = linkedSetOf<String>()
        val selected = ArrayList<MlModelDescriptor>()

        if (settings.isAutoIdEnabled()) {
            for (familyId in settings.autoIdEnabledFamilyIds) {
                val variants = byFamily[familyId]
                if (variants.isNullOrEmpty()) {
                    Timber.w("MlActiveModels: enabled family $familyId not in catalog")
                    continue
                }
                val chosen = chooseVariant(variants, settings.autoIdFamilyPrefs[familyId]?.variantId)
                if (chosen.id in seen) continue
                seen.add(chosen.id)
                selected.add(chosen)
            }
        }

        if (includeLiveSystemModels) {
            for (desc in liveSystemVariants(all)) {
                if (desc.id in seen) continue
                seen.add(desc.id)
                selected.add(desc)
            }
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

    /** Every `liveSystem` variant, family default first within each family. */
    private fun liveSystemVariants(all: List<MlModelDescriptor>): List<MlModelDescriptor> {
        val selected = ArrayList<MlModelDescriptor>()
        for ((_, variants) in all.groupBy { it.familyId }) {
            if (variants.none { it.liveSystem }) continue
            for (desc in sortVariants(variants)) {
                if (desc.liveSystem) selected.add(desc)
            }
        }
        return selected
    }

    private fun chooseVariant(
        variants: List<MlModelDescriptor>,
        preferredId: String?,
    ): MlModelDescriptor {
        val sorted = sortVariants(variants)
        return preferredId
            ?.let { id -> sorted.firstOrNull { it.id == id } }
            ?: sorted.first()
    }

    /** Family default first, then display name. */
    private fun sortVariants(variants: List<MlModelDescriptor>): List<MlModelDescriptor> =
        variants.sortedWith(
            compareBy<MlModelDescriptor> { it.id != it.familyDefaultVariantId }
                .thenBy { it.displayName }
        )
}
