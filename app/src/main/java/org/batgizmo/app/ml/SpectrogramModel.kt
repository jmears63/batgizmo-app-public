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
import org.tensorflow.lite.DataType
import java.util.Locale

/**
 * TFLite model whose input is a packed STFT spectrogram.
 *
 * The graph input is `[1, 1, freq, time]`: one channel, frequency increasing
 * along the third axis, time along the fourth. [MlModelInput.Spectrogram]
 * is packed time-major (each frame holds every frequency bin). This class
 * transposes that packing into the graph layout.
 *
 * Every output tensor is returned by [infer]. [predict] returns the
 * presence-probability map only, already in `[0, 1]`, one value per
 * label. [chirps] turns that series into one chirp per overlapping
 * pulse: the surviving bin's centre time and its FmaxE. The type-score
 * tensor is not used.
 *
 * Abstract base for spectrogram TFLite models such as [BatFilterCNNModel].
 * Not thread-safe.
 */
abstract class SpectrogramModel(
    assets: AssetManager,
    override val descriptor: MlModelDescriptor,
    numThreads: Int = DEFAULT_TFLITE_NUM_THREADS,
) : MlModelBase {

    private val session: CompiledModelSession
    private val nFreqBins: Int
    private val nFrames: Int
    private val presenceIndex: Int
    private val fmaxeIndex: Int
    private val pulseLengthIndex: Int
    private val clipDurationSec: Double
    private var presence: FloatArray = FloatArray(0)
    private var fmaxeHz: FloatArray = FloatArray(0)
    private var pulseLengthS: FloatArray = FloatArray(0)

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
        pulseLengthIndex = pulseLengthOutputIndex(
            names = outputNames,
            shapes = outputShapes,
            labelCount = descriptor.labelCatalog.size,
            presenceIndex = presenceIndex,
            fmaxeIndex = fmaxeIndex,
        )
        clipDurationSec = descriptor.windowSamples.toDouble() / descriptor.sampleRateHz.toDouble()
    }

    /**
     * Optional transform of the packed STFT spectrogram before the
     * graph input is built. [values] is frame-major
     * (`frame * nFreqBins + bin`). The base implementation returns
     * [values] unchanged.
     */
    protected open fun preprocessSpectrogram(
        values: FloatArray,
        nFrames: Int,
        nFreqBins: Int,
    ): FloatArray = values

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
        val prepared = preprocessSpectrogram(
            spectrogram.values, nFrames, nFreqBins,
        )
        require(prepared.size == nFrames * nFreqBins) {
            "preprocessed spectrogram size ${prepared.size} != $nFrames * $nFreqBins " +
                "(model ${descriptor.id})"
        }
        val flat = flattenFloats(spectrogramToNchw(prepared, nFrames, nFreqBins))
        val outputs = session.run(flat)
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
        val pulseLength = outputs[pulseLengthIndex]
        require(pulseLength.values.size == presence.values.size) {
            "pulse length output length ${pulseLength.values.size} != " +
                "presence length ${presence.values.size} (model ${descriptor.id})"
        }
        this.presence = presence.values
        fmaxeHz = fmaxe.values
        pulseLengthS = pulseLength.values
        return presence.values
    }

    /**
     * Chirps in the series from the last [predict], in time order.
     * Subclasses implement [spectrogramChirps].
     */
    internal fun chirps(threshold: Float): List<SpectrogramChirp> =
        spectrogramChirps(presence, fmaxeHz, pulseLengthS, clipDurationSec, threshold)

    /**
     * Turn presence / FmaxE / pulse-length series into chirps. Subclasses
     * supply the peak and overlap rules for their model family.
     */
    internal abstract fun spectrogramChirps(
        presence: FloatArray,
        fmaxeHz: FloatArray,
        pulseLengthS: FloatArray,
        durationSec: Double,
        threshold: Float,
    ): List<SpectrogramChirp>

    override fun detectionLabel(index: Int, catalogLabel: String): String {
        val hz = fmaxeHz.getOrNull(index) ?: return catalogLabel
        val nBins = fmaxeHz.size
        if (nBins == 0) return catalogLabel
        return formatChirpLabel(binCentreSec(index, nBins, clipDurationSec), hz)
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
    require(isPerBinOutput(shape, labelCount)) {
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
    require(isPerBinOutput(shape, labelCount)) {
        "no FmaxE output; output $candidate shape ${shape.contentToString()} " +
            "is not $labelCount frequencies"
    }
    return candidate
}

/**
 * Index of the pulse-length output, in seconds, one value per presence bin.
 *
 * Prefers a tensor whose name contains "pulse". Otherwise uses the remaining
 * per-bin vector, which is the export order after presence and FmaxE.
 */
internal fun pulseLengthOutputIndex(
    names: List<String>,
    shapes: List<IntArray>,
    labelCount: Int,
    presenceIndex: Int,
    fmaxeIndex: Int,
): Int {
    val named = names.indexOfFirst { it.contains("pulse", ignoreCase = true) }
    if (named >= 0) return named
    val candidate = shapes.indices.firstOrNull { index ->
        index != presenceIndex &&
            index != fmaxeIndex &&
            isPerBinOutput(shapes[index], labelCount)
    }
    require(candidate != null) { "spectrogram model has no pulse-length output" }
    return candidate
}

private fun isPerBinOutput(shape: IntArray, labelCount: Int): Boolean {
    if (shape.size > 2) return false
    val flat = shape.fold(1) { acc, dim -> if (dim > 0) acc * dim else acc }
    return flat == labelCount
}

/** FmaxE in Hz, shown in kHz to 0.1 kHz. */
internal fun formatFmaxeKhz(hz: Float): String =
    "%.1f kHz".format(Locale.US, hz / 1000f)

/** Chirp time in seconds and FmaxE in kHz. */
internal fun formatChirpLabel(timeSec: Double, fmaxeHz: Float): String =
    "%.3f s  %s".format(Locale.US, timeSec, formatFmaxeKhz(fmaxeHz))

/** Centre of bin [binIndex] in a clip of [nBins] bins and [durationSec] seconds. */
internal fun binCentreSec(binIndex: Int, nBins: Int, durationSec: Double): Double =
    (binIndex + 0.5) / nBins * durationSec

/**
 * One chirp kept from a presence series.
 *
 * [timeSec] is the centre of [binIndex]. [fmaxeHz] is that bin's FmaxE.
 */
internal data class SpectrogramChirp(
    val binIndex: Int,
    val timeSec: Double,
    val presence: Float,
    val fmaxeHz: Float,
)

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
