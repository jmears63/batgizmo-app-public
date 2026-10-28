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

/**
 * Two-stage Auto Id: BirdNET embeddings TFLite, then a regional classifier head.
 *
 * Used by BattyBirdNET regional variants. Not thread-safe.
 */
class EmbedThenClassifyModel(
    assets: AssetManager,
    override val descriptor: MlModelDescriptor,
    numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
) : MlModelBase {

    private val embedderSession: CompiledModelSession
    private val classifierSession: CompiledModelSession
    private val embeddingDim: Int
    private val numClasses: Int

    init {
        val paths = descriptor.resourcePaths
        val embeddingsPath = requireNotNull(paths.embeddingsRelativeOrNull) {
            "embed_then_classify requires embeddings path"
        }
        embedderSession = openCompiledSession(
            assets, paths.root, embeddingsPath, numThreads,
        )
        embeddingDim = embedderSession.outputShapes[0][1]

        classifierSession = openCompiledSession(
            assets, paths.root, paths.classifierOrModel, numThreads,
        )
        numClasses = classifierSession.outputShapes[0][1]

        require(descriptor.labelCatalog.size == numClasses) {
            "Label count (${descriptor.labelCatalog.size}) does not match classifier outputs ($numClasses)"
        }
        require(classifierSession.inputShapes[0][1] == embeddingDim) {
            "Classifier input dim ${classifierSession.inputShapes[0][1]} != embedding dim $embeddingDim"
        }
    }

    override fun predict(input: MlModelInput): FloatArray {
        require(descriptor.stft == null) {
            "embed_then_classify does not support stft (model ${descriptor.id})"
        }
        val window = when (input) {
            is MlModelInput.PcmWindow -> input.samples
            is MlModelInput.Spectrogram ->
                error("embed_then_classify expects PCM windows (model ${descriptor.id})")
        }
        require(window.size == descriptor.windowSamples) {
            "window must have ${descriptor.windowSamples} samples, got ${window.size}"
        }
        val embedding = embedderSession.run(window)[0]
        val logits = classifierSession.run(embedding)[0]
        return flatSigmoid(logits)
    }

    override fun close() {
        embedderSession.close()
        classifierSession.close()
    }
}
