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
import android.os.SystemClock
import org.tensorflow.lite.DataType
import timber.log.Timber
import java.util.Locale

/**
 * TFLite model whose input is a log-power spectrogram.
 *
 * The graph input is `[1, 1, freq, time]`: one channel, frequency increasing
 * along the third axis, time along the fourth. [MlModelInput.Spectrogram]
 * is packed time-major (each frame holds every frequency bin). This class
 * transposes that packing into the graph layout.
 *
 * Every output tensor is returned by [infer]. [predict] returns the
 * presence-probability map only, already in `[0, 1]`, one value per
 * label. A bin over the detection threshold is labelled with that bin's
 * FmaxE in kHz. The type-score tensor is not used.
 *
 * Not thread-safe.
 */
class SpectrogramModel(
    assets: AssetManager,
    override val descriptor: MlModelDescriptor,
    numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
) : MlModelBase {

    private val session: CompiledModelSession
    private val nFreqBins: Int
    private val nFrames: Int
    private val presenceIndex: Int
    private val fmaxeIndex: Int
    private var fmaxeHz: FloatArray = FloatArray(0)

    init {
        val stft = requireNotNull(descriptor.stft) {
            "spectrogram_tflite requires stft (model ${descriptor.id})"
        }
        val paths = descriptor.resourcePaths
        nFreqBins = stft.keptFreqBins()
        nFrames = stft.nFrames(descriptor.windowSamples)
        session = openCompiledSession(
            assets,
            paths.root,
            paths.classifierOrModel,
            numThreads,
            concreteInputShape = intArrayOf(1, 1, nFreqBins, nFrames),
        )
        require(session.inputDataType == DataType.FLOAT32) {
            "spectrogram input must be float32 (model ${descriptor.id})"
        }
        val shape = session.inputShapes[0]
        require(shape.size == 4) {
            "spectrogram input rank ${shape.size} != 4 " +
                "(shape=${shape.contentToString()}, model ${descriptor.id})"
        }
        require(dimMatches(shape[0], 1) && dimMatches(shape[1], 1)) {
            "spectrogram input must be [1, 1, freq, time], got ${shape.contentToString()}"
        }
        require(dimMatches(shape[2], nFreqBins) && dimMatches(shape[3], nFrames)) {
            "spectrogram input ${shape.contentToString()} != " +
                "[1, 1, $nFreqBins, $nFrames] " +
                "(fft=${stft.fftSize}, hop=${stft.hopSamples}, " +
                "firstFrequencyBin=${stft.firstFrequencyBin}, " +
                "windowSamples=${descriptor.windowSamples})"
        }
        session.outputDataTypes.forEachIndexed { index, dataType ->
            require(dataType == DataType.FLOAT32) {
                "spectrogram output $index must be float32 (model ${descriptor.id})"
            }
        }
        val outputNames = session.outputNames
        val outputShapes = session.outputShapes
        presenceIndex = presenceOutputIndex(
            names = outputNames,
            shapes = outputShapes,
            labelCount = descriptor.labelCatalog.size,
        )
        fmaxeIndex = fmaxeOutputIndex(
            names = outputNames,
            shapes = outputShapes,
            labelCount = descriptor.labelCatalog.size,
            presenceIndex = presenceIndex,
        )
    }

    /**
     * Run the graph on one spectrogram. Each entry is one output tensor,
     * in graph order, with [SpectrogramOutput.values] in row-major order.
     */
    fun infer(input: MlModelInput): List<SpectrogramOutput> {
        val spectrogram = when (input) {
            is MlModelInput.Spectrogram -> input
            is MlModelInput.PcmWindow ->
                error("spectrogram_tflite expects a spectrogram (model ${descriptor.id})")
        }
        require(spectrogram.nFrames == nFrames && spectrogram.nFreqBins == nFreqBins) {
            "spectrogram is ${spectrogram.nFreqBins}×${spectrogram.nFrames}, " +
                "model expects $nFreqBins×$nFrames"
        }
        val flat = flattenFloats(spectrogramToNchw(spectrogram.values, nFrames, nFreqBins))
        val startedMs = SystemClock.elapsedRealtime()
        val outputs = session.run(flat)
        val wallMs = SystemClock.elapsedRealtime() - startedMs
        val device = if (session.onGpu) "GPU+CPU" else "CPU"
        Timber.i("SpectrogramModel[${descriptor.id}]: tflite wall $device $wallMs ms")
        return List(outputs.size) { index ->
            SpectrogramOutput(session.outputShapes[index].copyOf(), outputs[index])
        }
    }

    override fun predict(input: MlModelInput): FloatArray {
        val outputs = infer(input)
        val presence = outputs[presenceIndex]
        val fmaxe = outputs[fmaxeIndex]
        require(presence.values.size == descriptor.labelCatalog.size) {
            "presence output length ${presence.values.size} != " +
                "label count ${descriptor.labelCatalog.size} (model ${descriptor.id})"
        }
        require(fmaxe.values.size == presence.values.size) {
            "fmaxe output length ${fmaxe.values.size} != " +
                "presence length ${presence.values.size} (model ${descriptor.id})"
        }
        fmaxeHz = fmaxe.values
        return presence.values
    }

    override fun detectionLabel(index: Int, catalogLabel: String): String {
        val hz = fmaxeHz.getOrNull(index) ?: return catalogLabel
        return formatFmaxeKhz(hz)
    }

    override fun close() {
        session.close()
    }

    companion object {
        private fun dimMatches(actual: Int, expected: Int): Boolean =
            actual == expected || actual == -1
    }
}

/**
 * Index of the presence-probability output.
 *
 * Prefers a tensor whose name contains "presence". Otherwise uses output 0
 * when it is a single probability per label, which is the export order
 * (presence, then frequency, pulse length, and the type scores).
 */
internal fun presenceOutputIndex(
    names: List<String>,
    shapes: List<IntArray>,
    labelCount: Int,
): Int {
    val named = names.indexOfFirst { it.contains("presence", ignoreCase = true) }
    if (named >= 0) return named
    require(shapes.isNotEmpty()) { "spectrogram model has no outputs" }
    val shape = shapes[0]
    val flat = shape.fold(1) { acc, dim -> if (dim > 0) acc * dim else acc }
    require(shape.size <= 2 && flat == labelCount) {
        "no presence output; output 0 shape ${shape.contentToString()} " +
            "is not $labelCount probabilities"
    }
    return 0
}

/**
 * Index of the FmaxE output, in Hz, one value per presence bin.
 *
 * Prefers a tensor whose name contains "fmaxe". Otherwise uses the export
 * order: the vector immediately after presence.
 */
internal fun fmaxeOutputIndex(
    names: List<String>,
    shapes: List<IntArray>,
    labelCount: Int,
    presenceIndex: Int,
): Int {
    val named = names.indexOfFirst { it.contains("fmaxe", ignoreCase = true) }
    if (named >= 0) return named
    val candidate = if (presenceIndex == 0) 1 else 0
    require(candidate in shapes.indices) { "spectrogram model has no FmaxE output" }
    val shape = shapes[candidate]
    val flat = shape.fold(1) { acc, dim -> if (dim > 0) acc * dim else acc }
    require(shape.size <= 2 && flat == labelCount) {
        "no FmaxE output; output $candidate shape ${shape.contentToString()} " +
            "is not $labelCount frequencies"
    }
    return candidate
}

/** FmaxE in Hz, shown in kHz to 0.1 kHz. */
internal fun formatFmaxeKhz(hz: Float): String =
    "%.1f kHz".format(Locale.US, hz / 1000f)

/** One float output tensor from [SpectrogramModel.infer]. */
class SpectrogramOutput(
    val shape: IntArray,
    val values: FloatArray,
)

/**
 * Transpose a time-major packed spectrogram into `[1, 1, freq, time]`.
 * Packed index is `frame * nFreqBins + bin`.
 */
internal fun spectrogramToNchw(
    values: FloatArray,
    nFrames: Int,
    nFreqBins: Int,
): Array<Array<Array<FloatArray>>> {
    require(values.size == nFrames * nFreqBins) {
        "spectrogram size ${values.size} != $nFrames * $nFreqBins"
    }
    val nchw = Array(1) { Array(1) { Array(nFreqBins) { FloatArray(nFrames) } } }
    val plane = nchw[0][0]
    for (frame in 0 until nFrames) {
        val base = frame * nFreqBins
        for (bin in 0 until nFreqBins) {
            plane[bin][frame] = values[base + bin]
        }
    }
    return nchw
}

/** Nested float arrays whose component type matches [shape] (rank 1..4). */
internal fun allocateFloatOutput(shape: IntArray): Any {
    require(shape.isNotEmpty() && shape.all { it > 0 }) {
        "output shape ${shape.contentToString()} is not a fixed float tensor"
    }
    return when (shape.size) {
        1 -> FloatArray(shape[0])
        2 -> Array(shape[0]) { FloatArray(shape[1]) }
        3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
        4 -> Array(shape[0]) {
            Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } }
        }
        else -> error("output rank ${shape.size} is not supported")
    }
}

internal fun flattenFloats(node: Any): FloatArray {
    val out = FloatArray(countFloats(node))
    writeFloats(node, out, 0)
    return out
}

private fun countFloats(node: Any): Int = when (node) {
    is FloatArray -> node.size
    is Array<*> -> node.sumOf { countFloats(it!!) }
    else -> error("unexpected tensor node ${node.javaClass}")
}

private fun writeFloats(node: Any, dest: FloatArray, offset: Int): Int = when (node) {
    is FloatArray -> {
        node.copyInto(dest, offset)
        offset + node.size
    }
    is Array<*> -> {
        var cursor = offset
        for (child in node) {
            cursor = writeFloats(child!!, dest, cursor)
        }
        cursor
    }
    else -> error("unexpected tensor node ${node.javaClass}")
}
