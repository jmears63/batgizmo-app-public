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

/**
 * How a model turns audio windows into class scores.
 */
enum class MlModelKind {
    /** BirdNET embeddings TFLite, then a regional linear head. */
    EMBED_THEN_CLASSIFY,

    /** Single end-to-end TFLite (e.g. stock BirdNET, simple BYO). */
    SINGLE_TFLITE,
}

/**
 * Metadata for an Auto Id model variant (loaded without opening TFLite).
 *
 * [id] is stable for settings / suppressions (e.g. `battybirdnet/uk-256khz`).
 */
data class MlModelDescriptor(
    val id: String,
    val displayName: String,
    val familyId: String,
    val familyDisplayName: String,
    val kind: MlModelKind,
    val sampleRateHz: Int,
    val windowSamples: Int,
    val overlapFraction: Float,
    val minSampleRateHz: Int,
    val minConfidence: Float,
    val languages: List<String>,
    val labelCatalog: List<LabelCatalogEntry>,
    /** Absolute asset or file paths resolved for this variant. */
    val resourcePaths: MlResourcePaths,
    /** When false, Settings hides/disables the Auto Id Suppressions control. */
    val enableSuppressionsButton: Boolean = true,
) {
    /** Stable label keys; computed once (BirdNET has ~6.5k classes). */
    val labelKeys: List<String> = labelCatalog.map { it.key }

    /** Per-class discard flags; computed once. */
    val labelDiscard: List<Boolean> = labelCatalog.map { it.discard }
}

/**
 * Resolved model file locations (assets paths or absolute filesystem paths).
 */
data class MlResourcePaths(
    val root: ModelResourceRoot,
    val embeddingsRelativeOrNull: String?,
    val classifierOrModel: String,
    val labels: String,
    /**
     * Optional species-range (geo) TFLite path relative to [root], e.g.
     * `../../BirdNET_…_MData_Model_FP16.tflite` for BirdNET.
     */
    val geoModelRelativeOrNull: String? = null,
    /** Occurrence threshold for [geoModelRelativeOrNull]; unused if geo model absent. */
    val geoThreshold: Float = 0.03f,
)

/** Where model bytes live. */
sealed class ModelResourceRoot {
    data class Assets(val baseAssetDir: String) : ModelResourceRoot()
    data class Files(val baseDir: java.io.File) : ModelResourceRoot()
}

/**
 * Runtime Auto Id model. Not thread-safe; use from a single infer worker.
 */
interface MlModelBase : AutoCloseable {
    val descriptor: MlModelDescriptor

    val id: String get() = descriptor.id
    val displayName: String get() = descriptor.displayName
    val sampleRateHz: Int get() = descriptor.sampleRateHz
    val windowSamples: Int get() = descriptor.windowSamples
    val overlapFraction: Float get() = descriptor.overlapFraction
    val minSampleRateHz: Int get() = descriptor.minSampleRateHz
    val minConfidence: Float get() = descriptor.minConfidence
    val languages: List<String> get() = descriptor.languages
    val labelCatalog: List<LabelCatalogEntry> get() = descriptor.labelCatalog

    val labelKeys: List<String> get() = descriptor.labelKeys
    val labelDiscard: List<Boolean> get() = descriptor.labelDiscard

    fun labelsFor(languageIndex: Int): List<String> {
        val col = coerceLanguageIndex(languageIndex)
        return labelCatalog.map { it.displayName(col) }
    }

    fun coerceLanguageIndex(languageIndex: Int): Int {
        if (languages.isEmpty()) return 0
        return if (languageIndex in languages.indices) languageIndex else 0
    }

    /**
     * Classify one float PCM window of [windowSamples] at [sampleRateHz].
     * Returns scores in `[0, 1]` aligned with [labelCatalog].
     */
    fun predict(window: FloatArray): FloatArray
}

/** Default LiteRT thread count for interpreters. */
const val DEFAULT_TFLITE_NUM_THREADS = 4
