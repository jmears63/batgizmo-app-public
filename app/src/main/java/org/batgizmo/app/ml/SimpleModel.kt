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
 * Single end-to-end TFLite Auto Id model (e.g. stock BirdNET, simple BYO).
 *
 * Expects input shape `[batch, featureLen]` (PCM window or packed STFT).
 *
 * Output:
 * - Classic: `[batch, numClasses]` logits → [flatSigmoid].
 * - Columnar ([MlModelDescriptor.outputColumns]): `[batch, numClasses, cols]`
 *   or flat `[batch, numClasses * cols]`; confidence from
 *   [BYOM_CONFIDENCE_COLUMN] (no sigmoid — values treated as already scaled).
 *
 * Not thread-safe. Open for subclasses such as [BirdNetModel].
 */
open class SimpleModel(
    protected val assets: AssetManager,
    override val descriptor: MlModelDescriptor,
    protected val numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
) : MlModelBase {

    private val session: CompiledModelSession
    protected val numClasses: Int
    private val confidenceColumnIndex: Int

    init {
        val paths = descriptor.resourcePaths
        session = openCompiledSession(
            assets,
            paths.root,
            paths.classifierOrModel,
            numThreads,
        )
        numClasses = descriptor.labelCatalog.size
        val outShape = session.outputShapes[0]
        requireColumnarOrFlatOutputShape(
            outShape,
            numClasses,
            descriptor.outputColumns,
        )
        confidenceColumnIndex = descriptor.outputColumns?.let { cols ->
            val idx = cols.indexOf(BYOM_CONFIDENCE_COLUMN)
            require(idx >= 0) {
                "outputColumns must include \"$BYOM_CONFIDENCE_COLUMN\" (got $cols)"
            }
            idx
        } ?: -1
        val inputLen = session.inputShapes[0].last()
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
            stft.modelInputSize(descriptor.windowSamples)
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
                val expected = stft.modelInputSize(descriptor.windowSamples)
                require(input.values.size == expected) {
                    "spectrogram must have $expected values, got ${input.values.size}"
                }
                input.values
            }
        }
        val columns = descriptor.outputColumns
        val values = session.run(features)[0]
        return if (columns == null) {
            flatSigmoid(values)
        } else {
            extractConfidenceColumn(values, session.outputShapes[0], columns.size, confidenceColumnIndex)
        }
    }

    /**
     * Run inference and return one score per label from the confidence column.
     * Accepts TFLite layouts `[1, N, C]`, `[N, C]`, or flat `[1, N*C]`.
     */
    private fun extractConfidenceColumn(
        values: FloatArray,
        outShape: IntArray,
        numColumns: Int,
        confIdx: Int,
    ): FloatArray {
        val supported = when {
            outShape.size == 3 &&
                outShape[0] == 1 &&
                outShape[1] == numClasses &&
                outShape[2] == numColumns -> true
            outShape.size == 2 &&
                outShape[0] == numClasses &&
                outShape[1] == numColumns -> true
            flatOutputLen(outShape) == numClasses * numColumns -> true
            else -> false
        }
        if (!supported) {
            error(
                "Unsupported columnar output shape ${outShape.contentToString()} " +
                    "for outputSize=$numClasses columns=$numColumns"
            )
        }
        return FloatArray(numClasses) { i -> values[i * numColumns + confIdx] }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Product of positive dims, or last dim when rank-1. */
        private fun flatOutputLen(shape: IntArray): Int {
            if (shape.isEmpty()) return 0
            if (shape.size == 1) return shape[0]
            var n = 1
            for (d in shape) {
                if (d > 0) n *= d
            }
            return n
        }

        /**
         * Ensure the TFLite output matches either classic `[…, numClasses]` or
         * columnar `[…, numClasses, cols]` / flat `numClasses * cols`.
         */
        fun requireColumnarOrFlatOutputShape(
            shape: IntArray,
            numClasses: Int,
            outputColumns: List<String>?,
        ) {
            if (outputColumns == null) {
                val outputLen = when {
                    shape.size >= 2 && shape[1] > 0 -> shape[1]
                    else -> shape.last()
                }
                require(outputLen == numClasses) {
                    "TFLite output length $outputLen does not match label count $numClasses " +
                        "(shape=${shape.contentToString()})"
                }
                return
            }
            val cols = outputColumns.size
            val ok = when {
                shape.size == 3 &&
                    shape.getOrElse(1) { -1 } == numClasses &&
                    shape.getOrElse(2) { -1 } == cols -> true
                shape.size == 2 &&
                    shape[0] == numClasses &&
                    shape[1] == cols -> true
                flatOutputLen(shape) == numClasses * cols -> true
                else -> false
            }
            require(ok) {
                "TFLite output shape ${shape.contentToString()} does not match " +
                    "outputSize=$numClasses × outputColumns($cols)"
            }
        }
    }
}
