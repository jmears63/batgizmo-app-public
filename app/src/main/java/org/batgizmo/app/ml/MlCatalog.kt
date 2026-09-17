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

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Discovers Auto Id model families/variants under `assets/ml` (and optionally a
 * user models directory) and opens [MlModelBase] instances.
 */
object MlCatalog {

    const val ASSETS_ML_ROOT = "ml"
    const val CATALOG_ASSET = "$ASSETS_ML_ROOT/catalog.json"

    /** Stable id of the bundled UK BattyBirdNET pack. */
    const val DEFAULT_MODEL_ID = "battybirdnet/uk-256khz"

    /** App-private directory for future bring-your-own model packs. */
    fun userModelsDir(context: Context): File =
        File(context.applicationContext.filesDir, ASSETS_ML_ROOT)

    /**
     * List bundled (and optional user) model descriptors without loading TFLite.
     * Failed variants are skipped and logged.
     */
    fun listDescriptors(
        assets: AssetManager,
        userModelsDir: File? = null,
    ): List<MlModelDescriptor> {
        val bundled = loadBundledDescriptors(assets)
        val user = userModelsDir?.let { loadUserDescriptors(assets, it) }.orEmpty()
        return bundled + user
    }

    fun findDescriptor(
        assets: AssetManager,
        modelId: String,
        userModelsDir: File? = null,
    ): MlModelDescriptor? =
        listDescriptors(assets, userModelsDir).firstOrNull { it.id == modelId }

    /**
     * Resolve [modelId], falling back to catalog default then first available.
     */
    fun resolveDescriptor(
        assets: AssetManager,
        modelId: String?,
        userModelsDir: File? = null,
    ): MlModelDescriptor {
        val all = listDescriptors(assets, userModelsDir)
        require(all.isNotEmpty()) { "No Auto Id models found under $ASSETS_ML_ROOT" }
        val preferred = modelId?.takeIf { it.isNotBlank() }
        preferred?.let { id -> all.firstOrNull { it.id == id } }?.let { return it }
        val catalogDefault = readCatalogDefaultId(assets)
        catalogDefault?.let { id -> all.firstOrNull { it.id == id } }?.let { return it }
        all.firstOrNull { it.id == DEFAULT_MODEL_ID }?.let { return it }
        return all.first()
    }

    /** Open TFLite interpreters for [descriptor]. Caller must [MlModelBase.close]. */
    fun openModel(
        assets: AssetManager,
        descriptor: MlModelDescriptor,
        numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
    ): MlModelBase {
        return when (descriptor.kind) {
            MlModelKind.EMBED_THEN_CLASSIFY ->
                EmbedThenClassifyModel(assets, descriptor, numThreads)
            MlModelKind.SINGLE_TFLITE ->
                if (descriptor.resourcePaths.geoModelRelativeOrNull != null) {
                    BirdNetModel(assets, descriptor, numThreads)
                } else {
                    SimpleModel(assets, descriptor, numThreads)
                }
        }
    }

    fun openModel(
        assets: AssetManager,
        modelId: String?,
        userModelsDir: File? = null,
        numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
    ): MlModelBase =
        openModel(assets, resolveDescriptor(assets, modelId, userModelsDir), numThreads)

    private fun readCatalogDefaultId(assets: AssetManager): String? {
        return try {
            assets.open(CATALOG_ASSET).bufferedReader().use { reader ->
                val root = JSONObject(reader.readText())
                if (root.has("defaultModelId")) {
                    root.getString("defaultModelId").takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "MlCatalog: failed to read $CATALOG_ASSET")
            null
        }
    }

    private fun loadBundledDescriptors(assets: AssetManager): List<MlModelDescriptor> {
        val familyIds = try {
            assets.open(CATALOG_ASSET).bufferedReader().use { reader ->
                val root = JSONObject(reader.readText())
                val arr = root.getJSONArray("families")
                List(arr.length()) { i -> arr.getString(i) }
            }
        } catch (e: Exception) {
            Timber.e(e, "MlCatalog: cannot read $CATALOG_ASSET")
            return emptyList()
        }

        val out = ArrayList<MlModelDescriptor>()
        for (familyId in familyIds) {
            val familyDir = "$ASSETS_ML_ROOT/$familyId"
            val family = try {
                readFamilyJson(assets, ModelResourceRoot.Assets(familyDir), "family.json")
            } catch (e: Exception) {
                Timber.e(e, "MlCatalog: skip family $familyId")
                continue
            }
            val variantsDir = "$familyDir/variants"
            val variantNames = try {
                assets.list(variantsDir)?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            for (variantName in variantNames) {
                val variantDir = "$variantsDir/$variantName"
                try {
                    out.add(
                        readVariantDescriptor(
                            assets = assets,
                            root = ModelResourceRoot.Assets(variantDir),
                            family = family,
                            variantRelativeJson = "variant.json",
                        )
                    )
                } catch (e: Exception) {
                    Timber.e(e, "MlCatalog: skip variant $variantDir")
                }
            }
        }
        return out
    }

    /**
     * User models: `userModelsDir/<familyId>/variants/<variantId>/variant.json`
     * with the same manifest schema as bundled assets.
     */
    private fun loadUserDescriptors(
        assets: AssetManager,
        userModelsDir: File,
    ): List<MlModelDescriptor> {
        if (!userModelsDir.isDirectory) return emptyList()
        val out = ArrayList<MlModelDescriptor>()
        val familyDirs = userModelsDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (familyDir in familyDirs) {
            val familyJson = File(familyDir, "family.json")
            if (!familyJson.isFile) continue
            val family = try {
                readFamilyJson(
                    assets,
                    ModelResourceRoot.Files(familyDir),
                    "family.json",
                )
            } catch (e: Exception) {
                Timber.e(e, "MlCatalog: skip user family ${familyDir.name}")
                continue
            }
            val variantsRoot = File(familyDir, "variants")
            val variantDirs = variantsRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
            for (variantDir in variantDirs) {
                try {
                    out.add(
                        readVariantDescriptor(
                            assets = assets,
                            root = ModelResourceRoot.Files(variantDir),
                            family = family,
                            variantRelativeJson = "variant.json",
                        )
                    )
                } catch (e: Exception) {
                    Timber.e(e, "MlCatalog: skip user variant ${variantDir.path}")
                }
            }
        }
        return out
    }

    private data class FamilyInfo(
        val id: String,
        val displayName: String,
        val kind: MlModelKind,
        val sampleRateHz: Int,
        val windowSamples: Int,
        val overlapFraction: Float,
        val minSampleRateHz: Int,
        val minConfidence: Float,
        /** Path relative to the family directory; null if no species-range model. */
        val geoModelRelativeToFamily: String?,
        val geoThreshold: Float,
        val enableSuppressionsButton: Boolean,
        /** Optional full model id of the family's preferred variant. */
        val defaultVariantId: String?,
    )

    private fun readFamilyJson(
        assets: AssetManager,
        root: ModelResourceRoot,
        relative: String,
    ): FamilyInfo {
        val json = JSONObject(openUtf8Resource(assets, root, relative))
        val sampleRateHz = json.getInt("sampleRateHz")
        val geoModel = if (json.has("geoModel")) {
            json.getString("geoModel").takeIf { it.isNotBlank() }
        } else {
            null
        }
        val defaultVariantId = if (json.has("defaultVariantId")) {
            json.getString("defaultVariantId").takeIf { it.isNotBlank() }
        } else {
            null
        }
        return FamilyInfo(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            kind = parseKind(json.getString("kind")),
            sampleRateHz = sampleRateHz,
            windowSamples = json.getInt("windowSamples"),
            overlapFraction = json.optDouble("overlapFraction", 0.25).toFloat()
                .coerceIn(0f, 0.95f),
            minSampleRateHz = json.optInt(
                "minSampleRateHz",
                (sampleRateHz * 0.75f).toInt().coerceAtLeast(1),
            ),
            minConfidence = json.optDouble("minConfidence", 0.7).toFloat(),
            geoModelRelativeToFamily = geoModel,
            geoThreshold = json.optDouble("geoThreshold", 0.03).toFloat(),
            enableSuppressionsButton = json.optBoolean("enableSuppressionsButton", true),
            defaultVariantId = defaultVariantId,
        )
    }

    private fun readVariantDescriptor(
        assets: AssetManager,
        root: ModelResourceRoot,
        family: FamilyInfo,
        variantRelativeJson: String,
    ): MlModelDescriptor {
        val json = JSONObject(openUtf8Resource(assets, root, variantRelativeJson))
        val kind = if (json.has("kind")) parseKind(json.getString("kind")) else family.kind
        val labelsRelative = json.getString("labels")
        val labelsTable = parseLabelsJson(openUtf8Resource(assets, root, labelsRelative))

        val embeddings = when (kind) {
            MlModelKind.EMBED_THEN_CLASSIFY -> json.getString("embeddings")
            MlModelKind.SINGLE_TFLITE -> null
        }
        val classifierOrModel = when (kind) {
            MlModelKind.EMBED_THEN_CLASSIFY -> json.getString("classifier")
            MlModelKind.SINGLE_TFLITE -> json.getString("model")
        }
        // Family-level geo asset → path relative to the variant root (`…/variants/<id>/`).
        val geoFromVariant = family.geoModelRelativeToFamily?.let { "../../$it" }

        return MlModelDescriptor(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            familyId = family.id,
            familyDisplayName = family.displayName,
            kind = kind,
            sampleRateHz = family.sampleRateHz,
            windowSamples = family.windowSamples,
            overlapFraction = family.overlapFraction,
            minSampleRateHz = family.minSampleRateHz,
            minConfidence = family.minConfidence,
            languages = labelsTable.languages,
            labelCatalog = labelsTable.entries,
            resourcePaths = MlResourcePaths(
                root = root,
                embeddingsRelativeOrNull = embeddings,
                classifierOrModel = classifierOrModel,
                labels = labelsRelative,
                geoModelRelativeOrNull = geoFromVariant,
                geoThreshold = family.geoThreshold,
            ),
            enableSuppressionsButton = family.enableSuppressionsButton,
            familyDefaultVariantId = family.defaultVariantId,
        )
    }

    private fun parseKind(raw: String): MlModelKind =
        when (raw) {
            "embed_then_classify" -> MlModelKind.EMBED_THEN_CLASSIFY
            "single_tflite" -> MlModelKind.SINGLE_TFLITE
            else -> error("Unknown Auto Id model kind: $raw")
        }
}
