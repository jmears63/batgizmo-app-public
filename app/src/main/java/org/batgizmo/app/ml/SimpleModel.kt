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
import org.tensorflow.lite.Interpreter

/**
 * Single end-to-end TFLite Auto Id model (e.g. stock BirdNET, simple BYO).
 *
 * Expects input shape `[batch, windowSamples]` and output logits
 * `[batch, numClasses]`, then applies [flatSigmoid]. Not thread-safe.
 *
 * Open for subclasses such as [BirdNetModel] that post-filter scores.
 */
open class SimpleModel(
    protected val assets: AssetManager,
    override val descriptor: MlModelDescriptor,
    protected val numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
) : MlModelBase {

    private val interpreter: Interpreter
    protected val numClasses: Int

    init {
        val paths = descriptor.resourcePaths
        interpreter = createInterpreter(
            assets,
            paths.root,
            paths.classifierOrModel,
            numThreads,
        )
        numClasses = interpreter.getOutputTensor(0).shape()[1]
        require(descriptor.labelCatalog.size == numClasses) {
            "Label count (${descriptor.labelCatalog.size}) does not match model outputs ($numClasses)"
        }
        val inputLen = interpreter.getInputTensor(0).shape().last()
        val expectedLen = expectedInputLength()
        require(inputLen == expectedLen) {
            "Model input length $inputLen != expected $expectedLen " +
                "(windowSamples=${descriptor.windowSamples}, stft=${descriptor.stft})"
        }
    }

    private fun expectedInputLength(): Int {
        val stft = descriptor.stft
        return if (stft == null) {
            descriptor.windowSamples
        } else {
            stft.outputSize(descriptor.windowSamples)
        }
    }

    override fun predict(input: MlModelInput): FloatArray {
        val features = when (input) {
            is MlModelInput.PcmWindow -> {
                require(descriptor.stft == null) {
                    "model ${descriptor.id} expects spectrogram input"
                }
                require(input.samples.size == descriptor.windowSamples) {
                    "window must have ${descriptor.windowSamples} samples, got ${input.samples.size}"
                }
                input.samples
            }
            is MlModelInput.Spectrogram -> {
                val stft = requireNotNull(descriptor.stft) {
                    "model ${descriptor.id} expects PCM input"
                }
                val expected = stft.outputSize(descriptor.windowSamples)
                require(input.values.size == expected) {
                    "spectrogram must have $expected values, got ${input.values.size}"
                }
                input.values
            }
        }
        val len = features.size
        interpreter.resizeInput(0, intArrayOf(1, len))
        interpreter.allocateTensors()
        val logits = Array(1) { FloatArray(numClasses) }
        interpreter.run(arrayOf(features), logits)
        return flatSigmoid(logits[0])
    }

    override fun close() {
        interpreter.close()
    }
}
