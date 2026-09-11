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
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * BirdNET embedding extractor plus BattyBirdNET regional species classifier.
 *
 * Mirrors the BattyBirdNET wrapper in BBNPoC/main.py: audio windows are
 * passed through BirdNET to obtain embeddings, then through a UK bat TFLite
 * head that scores bat (and noise) classes.
 *
 * Android LiteRT cannot expose intermediate tensors (`experimental_preserve_all_tensors`
 * is unavailable), so the BirdNET asset is an embeddings-only export whose
 * sole output is the penultimate GLOBAL_AVG_POOL layer (shape `[batch, 1024]`).
 *
 * Not thread-safe: use from a single worker thread (e.g. [MlProcessor]).
 */
class BattyBirdNET(
    assetManager: AssetManager,
    numThreads: Int = DEFAULT_NUM_THREADS,
) : AutoCloseable {

    /**
     * Language / column names from the labels JSON `description` array
     * (e.g. `"Latin"`, `"English"`), in file order.
     */
    val languages: List<String>

    /**
     * Stable per-class keys (first labels column, Latin names), aligned with
     * classifier outputs. Used for non-display filtering.
     */
    val labelKeys: List<String>

    private val labelRows: List<List<String>>
    private val embedder: Interpreter
    private val classifier: Interpreter

    private val embeddingDim: Int
    private val numClasses: Int

    init {
        val table = loadLabelsJson(assetManager, LABELS_JSON_ASSET)
        languages = table.description
        labelRows = table.rows
        labelKeys = labelRows.map { row -> row.first() }

        val options = Interpreter.Options().apply {
            setNumThreads(numThreads)
        }

        embedder = Interpreter(loadMappedAsset(assetManager, EMBEDDING_ASSET), options)
        embedder.allocateTensors()
        embeddingDim = embedder.getOutputTensor(0).shape()[1]

        classifier = Interpreter(loadMappedAsset(assetManager, CLASSIFIER_ASSET), options)
        classifier.allocateTensors()
        numClasses = classifier.getOutputTensor(0).shape()[1]

        require(labelRows.size == numClasses) {
            "Label count (${labelRows.size}) does not match classifier outputs ($numClasses)"
        }
        require(labelRows.all { it.size == languages.size }) {
            "Each labels row must have ${languages.size} names (one per description language)"
        }
        require(classifier.getInputTensor(0).shape()[1] == embeddingDim) {
            "Classifier input dim ${classifier.getInputTensor(0).shape()[1]} != embedding dim $embeddingDim"
        }
    }

    /**
     * Display names for [languageIndex] (column in the labels JSON), one per
     * classifier output. Out-of-range indexes fall back to 0.
     */
    fun labelsFor(languageIndex: Int): List<String> {
        val col = coerceLanguageIndex(languageIndex)
        return labelRows.map { row -> row[col] }
    }

    /**
     * Extract BirdNET feature embeddings for a batch of audio windows.
     *
     * @param samples shape `(batch, [SIG_SAMPLES])` float PCM at [SAMPLE_RATE_HZ]
     * @return embeddings of shape `(batch, embeddingDim)`
     */
    fun extractEmbeddings(samples: Array<FloatArray>): Array<FloatArray> {
        require(samples.isNotEmpty()) { "samples must be non-empty" }
        val batch = samples.size
        for (row in samples) {
            require(row.size == SIG_SAMPLES) {
                "each window must have $SIG_SAMPLES samples, got ${row.size}"
            }
        }

        embedder.resizeInput(0, intArrayOf(batch, SIG_SAMPLES))
        embedder.allocateTensors()

        val embeddings = Array(batch) { FloatArray(embeddingDim) }
        embedder.run(samples, embeddings)
        return embeddings
    }

    /**
     * Classify audio windows into bat (and noise) class confidences.
     *
     * @param samples shape `(batch, [SIG_SAMPLES])`
     * @return sigmoid scores of shape `(batch, n_labels)`, aligned with [labelKeys]
     */
    fun predict(samples: Array<FloatArray>): Array<FloatArray> {
        val features = extractEmbeddings(samples)
        val batch = features.size

        classifier.resizeInput(0, intArrayOf(batch, embeddingDim))
        classifier.allocateTensors()

        val logits = Array(batch) { FloatArray(numClasses) }
        classifier.run(features, logits)
        return Array(batch) { i -> flatSigmoid(logits[i]) }
    }

    /** Classify a single window; returns scores aligned with [labelKeys]. */
    fun predict(samples: FloatArray): FloatArray = predict(arrayOf(samples))[0]

    override fun close() {
        embedder.close()
        classifier.close()
    }

    /** Clamp [languageIndex] into `[0, languages.lastIndex]`, or `0` if empty. */
    fun coerceLanguageIndex(languageIndex: Int): Int {
        if (languages.isEmpty()) return 0
        return if (languageIndex in languages.indices) languageIndex else 0
    }

    companion object {
        /** Sample rate expected by BattyBirdNET (Hz). */
        const val SAMPLE_RATE_HZ = 256_000

        /** Samples per inference window (~0.5625 s at [SAMPLE_RATE_HZ]). */
        const val SIG_SAMPLES = 144_000

        const val DEFAULT_NUM_THREADS = 4

        /** Default species-name language index when unset or out of range. */
        const val DEFAULT_LANGUAGE_INDEX = 0

        private const val EMBEDDING_ASSET =
            "ml/BattyBirdNET/BirdNET_GLOBAL_6K_V2.4_Embeddings_FP32.tflite"
        private const val CLASSIFIER_ASSET = "ml/BattyBirdNET/BattyBirdNET-UK-256kHz.tflite"
        private const val LABELS_JSON_ASSET =
            "ml/BattyBirdNET/BattyBirdNET-UK-256kHz_Labels.json"

        private data class LabelsTable(
            val description: List<String>,
            val rows: List<List<String>>,
        )

        /**
         * Language / column names from the labels JSON `description` array
         * (e.g. `"Latin"`, `"English"`), in file order.
         */
        fun loadLabelLanguages(assetManager: AssetManager): List<String> =
            loadLabelsJson(assetManager, LABELS_JSON_ASSET).description

        /**
         * Map classifier logits to `[0, 1]` with a clipped sigmoid.
         * [sensitivity] is the negated slope (BirdNET convention; default `-1`).
         */
        fun flatSigmoid(
            logits: FloatArray,
            sensitivity: Float = -1f,
        ): FloatArray {
            return FloatArray(logits.size) { i ->
                val x = logits[i].coerceIn(-15f, 15f)
                1f / (1f + exp(sensitivity * x))
            }
        }

        private fun loadLabelsJson(assetManager: AssetManager, assetPath: String): LabelsTable {
            assetManager.open(assetPath).bufferedReader().use { reader ->
                val root = JSONObject(reader.readText())
                val descriptionJson = root.getJSONArray("description")
                val description = List(descriptionJson.length()) { i ->
                    descriptionJson.getString(i)
                }
                require(description.isNotEmpty()) { "labels JSON description must be non-empty" }

                val labelsJson = root.getJSONArray("labels")
                val rows = List(labelsJson.length()) { i ->
                    val rowJson = labelsJson.getJSONArray(i)
                    require(rowJson.length() == description.size) {
                        "labels[$i] has ${rowJson.length()} names; expected ${description.size}"
                    }
                    List(rowJson.length()) { j -> rowJson.getString(j) }
                }
                return LabelsTable(description, rows)
            }
        }

        private fun loadMappedAsset(
            assetManager: AssetManager,
            assetPath: String,
        ): MappedByteBuffer {
            assetManager.openFd(assetPath).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    return channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        afd.startOffset,
                        afd.declaredLength,
                    )
                }
            }
        }
    }
}
