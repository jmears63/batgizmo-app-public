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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Discovers Auto Id model families/variants under `assets/ml` and owns the
 * bring-your-own-model (BYOM) list under the app-private `filesDir/ml` tree.
 * Opens [MlModelBase] instances for either source.
 */
object MlCatalog {

    const val ASSETS_ML_ROOT = "ml"
    const val CATALOG_ASSET = "$ASSETS_ML_ROOT/catalog.json"

    /** Stable id of the bundled UK BattyBirdNET pack. */
    const val DEFAULT_MODEL_ID = "battybirdnet/uk-256khz"

    /**
     * App-private root for BYOM packs (`filesDir/ml`). Null until the catalog
     * is initialised with a [Context].
     */
    private var byomModelsDir: File? = null

    /**
     * Cached descriptors for installed BYOM families/variants. Owned and
     * refreshed by this catalog (empty until initialised / rescanned).
     */
    private var byomDescriptors: List<MlModelDescriptor> = emptyList()

    /** Guards BYOM cache and filesystem install/remove/refresh. */
    private val lock = ReentrantLock()

    /** App-private directory for bring-your-own model packs. */
    fun userModelsDir(context: Context): File =
        File(context.applicationContext.filesDir, ASSETS_ML_ROOT)

    /**
     * Bind the BYOM root under [context.filesDir] and scan installed packs.
     * Safe to call more than once (idempotent). May be called off the main thread.
     */
    fun ensureInitialized(context: Context) {
        val app = context.applicationContext
        lock.withLock {
            val dir = userModelsDir(app)
            if (byomModelsDir?.absolutePath == dir.absolutePath && byomModelsDir != null) {
                return
            }
            byomModelsDir = dir
            if (!dir.exists()) {
                dir.mkdirs()
            }
            cleanStaleInstallArtifactsLocked(dir)
            byomDescriptors = loadByomDescriptors(app.assets, dir)
            Timber.i("MlCatalog: BYOM scan found ${byomDescriptors.size} model(s)")
        }
    }

    /** Rescan [byomModelsDir] into [byomDescriptors]. No-op if not initialised. */
    fun refreshByom(assets: AssetManager) {
        lock.withLock { refreshByomLocked(assets) }
    }

    private fun refreshByomLocked(assets: AssetManager) {
        val dir = byomModelsDir ?: return
        byomDescriptors = loadByomDescriptors(assets, dir)
    }

    /**
     * Drop leftover `.$id.installing` / restore or drop `.$id.backup` from a
     * crashed commit. Caller must hold [lock].
     */
    private fun cleanStaleInstallArtifactsLocked(root: File) {
        val children = root.listFiles() ?: return
        for (f in children) {
            if (!f.isDirectory || !f.name.startsWith('.')) continue
            val name = f.name
            when {
                name.endsWith(".backup") -> {
                    val dirName = name.removePrefix(".").removeSuffix(".backup")
                    val dest = File(root, dirName)
                    if (!dest.exists()) {
                        Timber.w("MlCatalog: restoring orphan backup $name")
                        if (!f.renameTo(dest)) {
                            f.copyRecursively(dest, overwrite = true)
                            f.deleteRecursively()
                        }
                    } else {
                        Timber.w("MlCatalog: removing stale backup $name")
                        f.deleteRecursively()
                    }
                }
                name.endsWith(".installing") -> {
                    Timber.w("MlCatalog: removing stale installing dir $name")
                    f.deleteRecursively()
                }
            }
        }
    }

    /**
     * List bundled models plus cached BYOM descriptors without loading TFLite.
     * Failed variants are skipped and logged.
     */
    fun listDescriptors(assets: AssetManager): List<MlModelDescriptor> {
        val byom = lock.withLock { byomDescriptors }
        return loadBundledDescriptors(assets) + byom
    }

    fun findDescriptor(
        assets: AssetManager,
        modelId: String,
    ): MlModelDescriptor? =
        listDescriptors(assets).firstOrNull { it.id == modelId }

    /**
     * Resolve [modelId], falling back to catalog default then first available.
     * Rescans BYOM once if [modelId] is missing from the in-memory list (e.g.
     * after import or if the cache was empty at first init).
     */
    fun resolveDescriptor(
        assets: AssetManager,
        modelId: String?,
    ): MlModelDescriptor {
        fun lookup(all: List<MlModelDescriptor>, preferred: String?): MlModelDescriptor? {
            if (preferred.isNullOrBlank()) return null
            return all.firstOrNull { it.id == preferred }
        }

        val preferred = modelId?.takeIf { it.isNotBlank() }
        var all = listDescriptors(assets)
        lookup(all, preferred)?.let { return it }

        // BYOM may have been installed after the last scan.
        if (preferred != null && lock.withLock { byomModelsDir != null }) {
            refreshByom(assets)
            all = listDescriptors(assets)
            lookup(all, preferred)?.let { return it }
        }

        require(all.isNotEmpty()) { "No Auto Id models found under $ASSETS_ML_ROOT" }
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
        if (descriptor.isByom) {
            Timber.i(
                "MlCatalog: opening BYOM id=${descriptor.id} kind=${descriptor.kind} " +
                    "sampleRateHz=${descriptor.sampleRateHz} " +
                    "windowSamples=${descriptor.windowSamples}"
            )
        }
        // Route by kind for both bundled and BYOM (including legacy family.json packs).
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
        numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
    ): MlModelBase =
        openModel(assets, resolveDescriptor(assets, modelId), numThreads)

    /**
     * Summary of a BYOM pack from `byom.json` (no TFLite load).
     */
    data class ByomManifestSummary(
        val familyId: String,
        val displayName: String,
        val versionName: String?,
    )

    /**
     * Summary of an installed BYOM with [familyId], if present on disk.
     * Prefers the cached descriptor; falls back to reading `byom.json` so an
     * overwrite prompt still appears when the pack directory exists but is not
     * currently loaded into the cache.
     */
    fun installedByomSummary(familyId: String): ByomManifestSummary? {
        lock.withLock {
            byomDescriptors.firstOrNull { it.familyId == familyId }?.let {
                return ByomManifestSummary(it.familyId, it.familyDisplayName, it.versionName)
            }
            val root = byomModelsDir ?: return null
            val manifest = File(File(root, sanitizeByomDirName(familyId)), BYOM_MANIFEST)
            if (!manifest.isFile) return null
            return try {
                parseByomManifestSummary(manifest.readText())
            } catch (e: Exception) {
                Timber.w(e, "MlCatalog: could not read installed BYOM $familyId")
                ByomManifestSummary(familyId, familyId, versionName = null)
            }
        }
    }

    /**
     * Read `byom.json` from [zipUri] without installing. Unknown JSON fields are ignored.
     *
     * Layout: files may sit at the zip root, or under a single optional top-level
     * folder (that wrapper is ignored). `byom.json` must be at that pack root.
     */
    fun peekByomZip(context: Context, zipUri: android.net.Uri): ByomManifestSummary {
        val entries = readZipEntryNames(context, zipUri)
        val packPrefix = detectByomPackPrefix(entries)
        val manifestPath = packPrefix + BYOM_MANIFEST
        require(entries.any { !it.endsWith('/') && it == manifestPath }) {
            missingByomManifestMessage(
                if (packPrefix.isEmpty()) null else packPrefix.trimEnd('/'),
            )
        }
        val text = readZipEntryUtf8(context, zipUri, manifestPath)
            ?: error("Could not read $manifestPath from zip")
        // JSONObject: touch only recognised keys; extra fields are ignored.
        val fields = parseByomPackFields(JSONObject(text))
        requireZipContainsFile(entries, packPrefix + fields.modelFile, "model.file")
        requireZipContainsFile(entries, packPrefix + fields.labelsRelative, "model.labels")
        return fields.summary
    }

    /**
     * Import a BYOM zip (must contain `byom.json` plus referenced model/labels).
     *
     * Two phases:
     * 1. Extract to a temp directory and run all sanity checks (TFLite, sizes, …).
     *    On failure the temp tree is deleted and any already-installed BYOM is untouched.
     * 2. Only after checks pass: detect an existing install (requires [replaceExisting]),
     *    then swap the validated pack into the final `filesDir/ml/<id>/` location.
     *
     * Zip layout may be flat at the archive root, or wrapped in a single top-level
     * folder (ignored; contents of that folder are the pack).
     */
    fun importByomZip(
        context: Context,
        zipUri: android.net.Uri,
        replaceExisting: Boolean = false,
    ): MlModelDescriptor {
        ensureInitialized(context)
        val app = context.applicationContext
        val assets = app.assets
        // Hold the catalog lock for the whole install so readers cannot scan
        // mid-commit and inference cannot pick a half-written pack.
        return lock.withLock {
            val root = byomModelsDir ?: error("BYOM directory not initialised")
            val staging = File(app.cacheDir, "byom-import-${System.nanoTime()}")
            var installing: File? = null
            var backup: File? = null
            try {
                // --- Phase 1: stage + sanity-check (installed BYOMs untouched) ---
                if (!staging.mkdirs()) {
                    error("Could not create import staging directory")
                }
                unzipSafely(app, zipUri, staging)
                val packDir = resolveByomPackRoot(staging)
                require(File(packDir, BYOM_MANIFEST).isFile) {
                    missingByomManifestMessage(underFolder = null)
                }
                val preview = readByomPack(assets, packDir, validateTflite = true)
                val familyId = preview.first().familyId
                val dirName = sanitizeByomDirName(familyId)
                requireUnderByomRoot(root, dirName)

                val bundledIds = readBundledFamilyIds(assets)
                require(familyId !in bundledIds) {
                    "BYOM id \"$familyId\" conflicts with a built-in model family"
                }
                require(dirName !in bundledIds) {
                    "BYOM directory \"$dirName\" conflicts with a built-in model family"
                }

                // --- Phase 2: commit to final location ---
                try {
                    val dest = File(root, dirName)
                    if (dest.exists()) {
                        val existingId = readInstalledPackFamilyId(dest)
                        if (existingId != null && existingId != familyId) {
                            error(
                                "Directory \"$dirName\" is already used by BYOM " +
                                    "\"$existingId\"; choose a different id " +
                                    "(got \"$familyId\")"
                            )
                        }
                        require(replaceExisting) {
                            "BYOM \"$familyId\" is already installed"
                        }
                    }

                    val installingDir = File(root, ".$dirName.installing")
                    val backupDir = File(root, ".$dirName.backup")
                    installing = installingDir
                    backup = backupDir
                    if (installingDir.exists()) installingDir.deleteRecursively()
                    if (backupDir.exists()) backupDir.deleteRecursively()

                    packDir.copyRecursively(target = installingDir, overwrite = true)
                    require(File(installingDir, BYOM_MANIFEST).isFile) {
                        "Import failed: $BYOM_MANIFEST missing after staging copy"
                    }

                    if (dest.exists()) {
                        // Move current install aside so a failed rename can roll back.
                        check(dest.renameTo(backupDir) || run {
                            dest.copyRecursively(backupDir, overwrite = true)
                            dest.deleteRecursively()
                            backupDir.isDirectory
                        }) { "Could not move existing BYOM aside for replacement" }
                    }

                    val committed = installingDir.renameTo(dest) || run {
                        installingDir.copyRecursively(dest, overwrite = true)
                        installingDir.deleteRecursively()
                        File(dest, BYOM_MANIFEST).isFile
                    }
                    if (!committed) {
                        // Roll back previous install if we moved it aside.
                        if (backupDir.isDirectory && !dest.exists()) {
                            backupDir.renameTo(dest) || run {
                                backupDir.copyRecursively(dest, overwrite = true)
                                backupDir.deleteRecursively()
                                true
                            }
                        }
                        error("Could not commit BYOM to $dirName")
                    }

                    backupDir.deleteRecursively()
                    installing = null
                    backup = null

                    refreshByomLocked(assets)
                    byomDescriptors.firstOrNull { it.familyId == familyId }
                        ?: error("Imported BYOM family \"$familyId\" not found after scan")
                } catch (e: Exception) {
                    throw ByomCommitException(
                        e.message?.takeIf { it.isNotBlank() } ?: "Failed to install classifier",
                        e,
                    )
                }
            } finally {
                staging.deleteRecursively()
                installing?.deleteRecursively()
                // Leave backup in place only if dest is missing (partial failure); else drop it.
                backup?.let { b ->
                    val dirName = b.name.removePrefix(".").removeSuffix(".backup")
                    val dest = File(root, dirName)
                    if (dest.exists()) {
                        b.deleteRecursively()
                    }
                }
            }
        }
    }

    private fun missingByomManifestMessage(underFolder: String?): String {
        val location = if (underFolder.isNullOrEmpty()) {
            "at the pack root"
        } else {
            "at the pack root (under $underFolder)"
        }
        return "Zip does not contain $BYOM_MANIFEST $location. " +
            "Are you sure it is a BatGizmo classifier pack?"
    }

    private fun requireUnderByomRoot(root: File, dirName: String) {
        val dest = File(root, dirName).canonicalFile
        require(
            dest.path == root.canonicalFile.path ||
                dest.path.startsWith(root.canonicalFile.path + File.separator)
        ) {
            "Invalid BYOM directory name \"$dirName\""
        }
    }

    /**
     * Delete an installed BYOM family directory and refresh the cache.
     * [familyId] is the logical id from `byom.json` (directory name is sanitized).
     * Returns true if something was removed.
     */
    fun removeByomFamily(context: Context, familyId: String): Boolean {
        ensureInitialized(context)
        val dirName = sanitizeByomDirName(familyId)
        val assets = context.applicationContext.assets
        return lock.withLock {
            val dir = byomModelsDir ?: return@withLock false
            val familyDir = File(dir, dirName).canonicalFile
            require(
                familyDir.path.startsWith(dir.canonicalFile.path + File.separator) ||
                    familyDir.path == dir.canonicalFile.path
            ) {
                "Path escapes BYOM root"
            }
            if (!familyDir.isDirectory) return@withLock false
            val removed = familyDir.deleteRecursively()
            if (removed) {
                refreshByomLocked(assets)
            }
            removed
        }
    }

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
                            isByom = false,
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
     * BYOM packs under [userModelsDir]:
     * - Preferred: `<sanitized-id>/byom.json` (+ model/labels files)
     * - Legacy: `<dir>/family.json` + `variants/<id>/variant.json`
     */
    private fun loadByomDescriptors(
        assets: AssetManager,
        userModelsDir: File,
    ): List<MlModelDescriptor> {
        if (!userModelsDir.isDirectory) return emptyList()
        val out = ArrayList<MlModelDescriptor>()
        // Skip staging/backup dirs (".$id.installing", ".$id.backup").
        val familyDirs = userModelsDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            .orEmpty()
        for (familyDir in familyDirs) {
            val byomJson = File(familyDir, BYOM_MANIFEST)
            if (byomJson.isFile) {
                try {
                    // Catalog load: skip TFLite open (expensive; import validates).
                    out.addAll(readByomPack(assets, familyDir, validateTflite = false))
                } catch (e: Exception) {
                    Timber.e(e, "MlCatalog: skip BYOM pack ${familyDir.name}")
                }
                continue
            }
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
                            isByom = true,
                        )
                    )
                } catch (e: Exception) {
                    Timber.e(e, "MlCatalog: skip user variant ${variantDir.path}")
                }
            }
        }
        return out
    }

    /**
     * Parse a flat BYOM pack directory containing [BYOM_MANIFEST].
     *
     * Forward compatibility: only known keys are read; unknown fields in
     * `byom.json` (and nested objects) are silently ignored so newer packs
     * remain loadable on older app builds.
     */
    private fun readByomPack(
        assets: AssetManager,
        packDir: File,
        validateTflite: Boolean,
    ): List<MlModelDescriptor> {
        val root = ModelResourceRoot.Files(packDir)
        // JSONObject: touch only recognised keys; extra fields are ignored.
        val fields = parseByomPackFields(
            JSONObject(openUtf8Resource(assets, root, BYOM_MANIFEST))
        )
        requirePackFileExists(packDir, fields.modelFile, "model.file")
        requirePackFileExists(packDir, fields.labelsRelative, "model.labels")
        if (validateTflite) {
            validateByomTfliteFile(
                assets = assets,
                packDir = packDir,
                modelRelative = fields.modelFile,
                inputSize = fields.windowSamples,
                outputSize = fields.outputSize,
            )
        }
        val labelsTable = parseLabelsJson(
            openUtf8Resource(assets, root, fields.labelsRelative)
        )
        require(labelsTable.entries.size == fields.outputSize) {
            "Label count (${labelsTable.entries.size}) != model.outputSize (${fields.outputSize})"
        }
        val familyId = fields.summary.familyId
        val variantId = "$familyId/default"
        return listOf(
            MlModelDescriptor(
                id = variantId,
                displayName = "Default",
                familyId = familyId,
                familyDisplayName = fields.summary.displayName,
                kind = MlModelKind.SINGLE_TFLITE,
                sampleRateHz = fields.sampleRateHz,
                windowSamples = fields.windowSamples,
                overlapFraction = fields.overlapFraction,
                minSampleRateHz = fields.minSampleRateHz,
                detectionThreshold = fields.detectionThreshold,
                languages = labelsTable.languages,
                labelCatalog = labelsTable.entries,
                resourcePaths = MlResourcePaths(
                    root = root,
                    embeddingsRelativeOrNull = null,
                    classifierOrModel = fields.modelFile,
                    labels = fields.labelsRelative,
                ),
                enableSuppressionsButton = fields.enableSuppressionsButton,
                familyDefaultVariantId = variantId,
                isByom = true,
                versionName = fields.summary.versionName,
            ),
        )
    }

    /**
     * Shared `byom.json` field parse/validate used by peek and install.
     * Unknown JSON keys are ignored (forward compatible).
     */
    private data class ByomPackFields(
        val summary: ByomManifestSummary,
        val modelFile: String,
        val labelsRelative: String,
        val sampleRateHz: Int,
        val windowSamples: Int,
        val outputSize: Int,
        val minSampleRateHz: Int,
        val overlapFraction: Float,
        val detectionThreshold: Float,
        val enableSuppressionsButton: Boolean,
    )

    private fun parseByomPackFields(json: JSONObject): ByomPackFields {
        val summary = parseByomManifestSummary(json)
        val model = json.getJSONObject("model")
        val modelFile = requireSafePackRelativePath(model.getString("file"), "model.file")
        val labelsRelative =
            requireSafePackRelativePath(model.getString("labels"), "model.labels")
        val sampleRateHz = model.getInt("sampleRateHz")
        require(sampleRateHz in MIN_BYOM_MODEL_SAMPLE_RATE_HZ..MAX_BYOM_MODEL_SAMPLE_RATE_HZ) {
            "model.sampleRateHz must be in " +
                "$MIN_BYOM_MODEL_SAMPLE_RATE_HZ..$MAX_BYOM_MODEL_SAMPLE_RATE_HZ " +
                "(got $sampleRateHz)"
        }
        val windowSamples = when {
            json.has("windowSamples") -> json.getInt("windowSamples")
            model.has("inputSize") -> model.getInt("inputSize")
            else -> error("$BYOM_MANIFEST missing windowSamples / model.inputSize")
        }
        require(windowSamples >= MIN_BYOM_INPUT_SIZE) {
            "model.inputSize must be at least $MIN_BYOM_INPUT_SIZE (got $windowSamples)"
        }
        require(model.has("outputSize")) { "model.outputSize is required" }
        val outputSize = model.getInt("outputSize")
        require(outputSize >= MIN_BYOM_OUTPUT_SIZE) {
            "model.outputSize must be at least $MIN_BYOM_OUTPUT_SIZE (got $outputSize)"
        }
        val minSampleRateHz = when {
            json.has("minMicrophoneSampleRateHz") ->
                json.getInt("minMicrophoneSampleRateHz")
            json.has("minSampleRateHz") -> json.getInt("minSampleRateHz")
            else -> DEFAULT_BYOM_MIC_SAMPLE_RATE_HZ
        }
        require(minSampleRateHz in MIN_BYOM_MIC_SAMPLE_RATE_HZ..MAX_BYOM_MIC_SAMPLE_RATE_HZ) {
            "minMicrophoneSampleRateHz must be in " +
                "$MIN_BYOM_MIC_SAMPLE_RATE_HZ..$MAX_BYOM_MIC_SAMPLE_RATE_HZ " +
                "(got $minSampleRateHz)"
        }
        val overlapFraction = json.optDouble("overlapFraction", 0.25).toFloat()
        require(overlapFraction in 0f..1f) {
            "overlapFraction must be in 0.0..1.0 (got $overlapFraction)"
        }
        val detectionThreshold = when {
            json.has("detectionThreshold") ->
                json.getDouble("detectionThreshold").toFloat()
            json.has("minConfidence") ->
                // Legacy BYOM key; prefer detectionThreshold.
                json.getDouble("minConfidence").toFloat()
            else -> 0.7f
        }
        require(detectionThreshold in 0f..1f) {
            "detectionThreshold must be in 0.0..1.0 (got $detectionThreshold)"
        }
        return ByomPackFields(
            summary = summary,
            modelFile = modelFile,
            labelsRelative = labelsRelative,
            sampleRateHz = sampleRateHz,
            windowSamples = windowSamples,
            outputSize = outputSize,
            minSampleRateHz = minSampleRateHz,
            overlapFraction = overlapFraction,
            detectionThreshold = detectionThreshold,
            enableSuppressionsButton = json.optBoolean("enableSuppressionsButton", true),
        )
    }

    /** Family ids listed in assets `catalog.json` (built-in packs). */
    private fun readBundledFamilyIds(assets: AssetManager): Set<String> {
        return try {
            assets.open(CATALOG_ASSET).bufferedReader().use { reader ->
                val root = JSONObject(reader.readText())
                val arr = root.getJSONArray("families")
                buildSet {
                    for (i in 0 until arr.length()) {
                        add(arr.getString(i))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "MlCatalog: failed to read bundled family ids")
            emptySet()
        }
    }

    /** Logical id from an on-disk pack (`byom.json` or legacy `family.json`). */
    private fun readInstalledPackFamilyId(packDir: File): String? {
        val byom = File(packDir, BYOM_MANIFEST)
        if (byom.isFile) {
            return try {
                parseByomManifestSummary(byom.readText()).familyId
            } catch (_: Exception) {
                null
            }
        }
        val familyJson = File(packDir, "family.json")
        if (familyJson.isFile) {
            return try {
                JSONObject(familyJson.readText()).getString("id")
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun parseByomManifestSummary(jsonText: String): ByomManifestSummary =
        parseByomManifestSummary(JSONObject(jsonText))

    /** Forward compatible: only known keys; unknown fields ignored. */
    private fun parseByomManifestSummary(json: JSONObject): ByomManifestSummary {
        val versionName = when {
            json.has("version") ->
                json.getString("version").takeIf { it.isNotBlank() }
            json.has("versionName") ->
                json.getString("versionName").takeIf { it.isNotBlank() }
            else -> null
        }
        return ByomManifestSummary(
            familyId = json.getString("id"),
            displayName = json.getString("displayName"),
            versionName = versionName,
        )
    }

    /**
     * Pack root after unzip: either [staging] itself, or its sole top-level
     * directory when the archive wraps everything in one folder (ignoring
     * `__MACOSX` / dotfiles).
     */
    private fun resolveByomPackRoot(staging: File): File {
        val children = staging.listFiles()
            ?.filterNot { isIgnorableByomTopLevelName(it.name) }
            .orEmpty()
        val dirs = children.filter { it.isDirectory }
        val files = children.filter { it.isFile }
        return if (dirs.size == 1 && files.isEmpty()) dirs.single() else staging
    }

    /**
     * Zip-path prefix of the pack root (`""` or `"Wrapper/"`), matching
     * [resolveByomPackRoot] for an extracted tree.
     */
    private fun detectByomPackPrefix(entryPaths: Collection<String>): String {
        val topDirs = linkedSetOf<String>()
        val topFiles = linkedSetOf<String>()
        for (raw in entryPaths) {
            // Only file entries define the layout (skip directory placeholders).
            if (raw.endsWith('/')) continue
            val path = raw.trimEnd('/')
            if (path.isEmpty()) continue
            val first = path.substringBefore('/')
            if (isIgnorableByomTopLevelName(first)) continue
            if (path.contains('/')) {
                topDirs.add(first)
            } else {
                topFiles.add(first)
            }
        }
        return if (topDirs.size == 1 && topFiles.isEmpty()) {
            topDirs.single() + "/"
        } else {
            ""
        }
    }

    private fun isIgnorableByomTopLevelName(name: String): Boolean =
        name == "__MACOSX" || name.startsWith('.')

    /** Relative pack path: no absolute path, empty segments, or `..`. */
    private fun requireSafePackRelativePath(relative: String, fieldName: String): String {
        val normalized = relative.replace('\\', '/').trim().trimStart('/')
        require(normalized.isNotEmpty()) { "$fieldName must not be empty" }
        require(!normalized.startsWith("/") && normalized.split('/').none { it.isEmpty() || it == ".." }) {
            "$fieldName must be a relative path within the pack (got \"$relative\")"
        }
        return normalized
    }

    private fun requireZipContainsFile(
        entries: Collection<String>,
        path: String,
        fieldName: String,
    ) {
        val want = path.trimEnd('/')
        require(entries.any { !it.endsWith('/') && it.trimEnd('/') == want }) {
            "$fieldName \"$want\" is missing from the zip"
        }
    }

    private fun requirePackFileExists(packDir: File, relative: String, fieldName: String) {
        val packRoot = packDir.canonicalFile
        val file = File(packDir, relative).canonicalFile
        require(file.path == packRoot.path ||
            file.path.startsWith(packRoot.path + File.separator)) {
            "$fieldName \"$relative\" escapes the pack directory"
        }
        require(file.isFile) {
            "$fieldName \"$relative\" is missing from the pack"
        }
    }

    /**
     * Open [modelRelative] as TFLite and require input/output lengths match
     * [inputSize] / [outputSize] (same conventions as [SimpleModel]).
     */
    private fun validateByomTfliteFile(
        assets: AssetManager,
        packDir: File,
        modelRelative: String,
        inputSize: Int,
        outputSize: Int,
    ) {
        val root = ModelResourceRoot.Files(packDir)
        val interpreter = try {
            createInterpreter(assets, root, modelRelative, numThreads = 1)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "model.file is not a valid TFLite model: ${e.message}",
                e,
            )
        }
        try {
            require(interpreter.inputTensorCount >= 1) {
                "TFLite model has no input tensors"
            }
            require(interpreter.outputTensorCount >= 1) {
                "TFLite model has no output tensors"
            }
            var inputLen = interpreter.getInputTensor(0).shape().last()
            if (inputLen <= 0) {
                interpreter.resizeInput(0, intArrayOf(1, inputSize))
                interpreter.allocateTensors()
                inputLen = interpreter.getInputTensor(0).shape().last()
            }
            require(inputLen == inputSize) {
                "TFLite input length $inputLen does not match model.inputSize $inputSize"
            }
            val outShape = interpreter.getOutputTensor(0).shape()
            val outputLen = when {
                outShape.size >= 2 && outShape[1] > 0 -> outShape[1]
                else -> outShape.last()
            }
            require(outputLen == outputSize) {
                "TFLite output length $outputLen does not match model.outputSize $outputSize"
            }
        } finally {
            interpreter.close()
        }
    }

    private fun readZipEntryNames(
        context: Context,
        zipUri: android.net.Uri,
    ): List<String> {
        val input = context.contentResolver.openInputStream(zipUri)
            ?: error("Could not open selected file")
        val names = ArrayList<String>()
        input.use { stream ->
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(stream)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val path = entry.name.replace('\\', '/')
                    require(!path.startsWith("/") && !path.split('/').any { it == ".." }) {
                        "Zip entry has illegal path: ${entry.name}"
                    }
                    if (!entry.isDirectory) {
                        names.add(path.trimEnd('/'))
                    } else {
                        val dirPath = path.trimEnd('/')
                        if (dirPath.isNotEmpty()) {
                            names.add("$dirPath/")
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
        return names
    }

    private fun readZipEntryUtf8(
        context: Context,
        zipUri: android.net.Uri,
        entryPath: String,
    ): String? {
        val input = context.contentResolver.openInputStream(zipUri)
            ?: error("Could not open selected file")
        input.use { stream ->
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(stream)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val path = entry.name.replace('\\', '/').trimEnd('/')
                    require(!path.startsWith("/") && !path.split('/').any { it == ".." }) {
                        "Zip entry has illegal path: ${entry.name}"
                    }
                    if (!entry.isDirectory && path == entryPath.trimEnd('/')) {
                        val text = zis.bufferedReader().readText()
                        zis.closeEntry()
                        return text
                    }
                    zis.closeEntry()
                }
            }
        }
        return null
    }

    private fun unzipSafely(
        context: Context,
        zipUri: android.net.Uri,
        destDir: File,
    ) {
        val input = context.contentResolver.openInputStream(zipUri)
            ?: error("Could not open selected file")
        var totalUncompressed = 0L
        input.use { stream ->
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(stream)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith("/") && !name.split('/').any { it == ".." }) {
                        "Zip entry has illegal path: ${entry.name}"
                    }
                    val outFile = File(destDir, name)
                    val canonical = outFile.canonicalFile
                    require(canonical.path.startsWith(destDir.canonicalFile.path + File.separator) ||
                        canonical.path == destDir.canonicalFile.path) {
                        "Zip entry escapes destination: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        canonical.mkdirs()
                    } else {
                        canonical.parentFile?.mkdirs()
                        canonical.outputStream().use { out ->
                            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                totalUncompressed += n
                                require(totalUncompressed <= MAX_BYOM_UNCOMPRESSED_BYTES) {
                                    "Zip is too large after decompression"
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
    }

    private data class FamilyInfo(
        val id: String,
        val displayName: String,
        val kind: MlModelKind,
        val sampleRateHz: Int,
        val windowSamples: Int,
        val overlapFraction: Float,
        val minSampleRateHz: Int,
        val detectionThreshold: Float,
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
            detectionThreshold = when {
                json.has("detectionThreshold") ->
                    json.getDouble("detectionThreshold").toFloat()
                json.has("minConfidence") ->
                    json.getDouble("minConfidence").toFloat()
                else -> 0.7f
            },
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
        isByom: Boolean,
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
            detectionThreshold = family.detectionThreshold,
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
            isByom = isByom,
        )
    }

    private fun parseKind(raw: String): MlModelKind =
        when (raw) {
            "embed_then_classify" -> MlModelKind.EMBED_THEN_CLASSIFY
            "single_tflite" -> MlModelKind.SINGLE_TFLITE
            else -> error("Unknown Auto Id model kind: $raw")
        }

    /**
     * Map a `byom.json` id to a single path segment safe for the filesystem.
     * Letters, digits, `.`, `_`, and `-` are kept; other characters become `_`.
     */
    private fun sanitizeByomDirName(id: String): String {
        val cleaned = buildString(id.length) {
            for (ch in id.trim()) {
                append(
                    when {
                        ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' -> ch
                        else -> '_'
                    }
                )
            }
        }
            .replace(Regex("_+"), "_")
            .trim('_', '.', '-')
        require(cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") {
            "BYOM id \"$id\" cannot be used as a directory name"
        }
        return cleaned
    }

    private const val BYOM_MANIFEST = "byom.json"
    private const val MAX_BYOM_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
    private const val DEFAULT_BYOM_MIC_SAMPLE_RATE_HZ = 48_000
    private const val MIN_BYOM_MIC_SAMPLE_RATE_HZ = 36_000
    private const val MAX_BYOM_MIC_SAMPLE_RATE_HZ = 512_000
    private const val MIN_BYOM_MODEL_SAMPLE_RATE_HZ = 48_000
    private const val MAX_BYOM_MODEL_SAMPLE_RATE_HZ = 512_000
    private const val MIN_BYOM_INPUT_SIZE = 1024
    private const val MIN_BYOM_OUTPUT_SIZE = 1
}

/** Thrown when BYOM stage/validate succeeded but installing into `filesDir/ml` failed. */
class ByomCommitException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
