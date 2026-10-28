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
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/** Normalize `base/../child` style asset or relative paths. */
fun resolveRelativePath(baseDir: String, relative: String): String {
    val joined = if (relative.startsWith("/")) {
        relative.trimStart('/')
    } else {
        listOf(baseDir.trimEnd('/'), relative.trimStart('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }
    val stack = ArrayDeque<String>()
    for (part in joined.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (stack.isNotEmpty()) stack.removeLast()
            else -> stack.addLast(part)
        }
    }
    return stack.joinToString("/")
}

fun openUtf8Resource(assets: AssetManager, root: ModelResourceRoot, relativePath: String): String {
    return when (root) {
        is ModelResourceRoot.Assets -> {
            val path = resolveRelativePath(root.baseAssetDir, relativePath)
            assets.open(path).bufferedReader().use { it.readText() }
        }
        is ModelResourceRoot.Files -> {
            val file = File(root.baseDir, relativePath).canonicalFile
            require(file.path.startsWith(root.baseDir.canonicalFile.path)) {
                "Path escapes model root: $relativePath"
            }
            file.readText()
        }
    }
}

fun loadMappedModelBuffer(
    assets: AssetManager,
    root: ModelResourceRoot,
    relativePath: String,
): MappedByteBuffer {
    return when (root) {
        is ModelResourceRoot.Assets -> {
            val path = resolveRelativePath(root.baseAssetDir, relativePath)
            assets.openFd(path).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
        }
        is ModelResourceRoot.Files -> {
            val file = File(root.baseDir, relativePath).canonicalFile
            require(file.path.startsWith(root.baseDir.canonicalFile.path)) {
                "Path escapes model root: $relativePath"
            }
            FileInputStream(file).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
    }
}

/**
 * A compiled model plus the buffers used for every run.
 *
 * Live and viewer inference both open models through [openCompiledSession],
 * which requests the GPU accelerator. [close] releases the buffers, then
 * the compiled model.
 */
class CompiledModelSession(
    private val compiled: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>,
    val inputShapes: List<IntArray>,
    val outputShapes: List<IntArray>,
    val outputNames: List<String>,
    val inputDataType: DataType,
    val outputDataTypes: List<DataType>,
    val onGpu: Boolean,
) : AutoCloseable {
    /** Run one input tensor and return each output as a flat float array. */
    fun run(input: FloatArray): List<FloatArray> {
        inputs[0].writeFloat(input)
        compiled.run(inputs, outputs)
        return outputs.map { it.readFloat() }
    }

    override fun close() {
        for (buffer in inputs) buffer.close()
        for (buffer in outputs) buffer.close()
        compiled.close()
    }
}

/**
 * Compile [relativePath] for inference.
 *
 * The GPU accelerator is requested first. If this device cannot compile the
 * graph for the GPU, the same file is compiled for the CPU. [concreteInputShape]
 * is applied while reading tensor metadata when the file still has a dynamic
 * dimension.
 */
fun openCompiledSession(
    assets: AssetManager,
    root: ModelResourceRoot,
    relativePath: String,
    numThreads: Int,
    concreteInputShape: IntArray? = null,
): CompiledModelSession {
    val resolvedPath = resolvedModelPath(root, relativePath)
    val meta = readTensorMeta(assets, root, relativePath, numThreads, concreteInputShape)
    var onGpu = true
    val compiled = try {
        compileModel(assets, root, relativePath, gpuOptions(numThreads))
    } catch (e: Exception) {
        onGpu = false
        Timber.w(e, "CompiledModel GPU unavailable for $resolvedPath; using CPU")
        compileModel(assets, root, relativePath, cpuOptions(numThreads))
    }
    return try {
        val inputs = compiled.createInputBuffers()
        val outputs = compiled.createOutputBuffers()
        val device = if (onGpu) "GPU" else "CPU"
        Timber.i("CompiledModel loaded $resolvedPath on $device")
        CompiledModelSession(
            compiled = compiled,
            inputs = inputs,
            outputs = outputs,
            inputShapes = meta.inputShapes,
            outputShapes = meta.outputShapes,
            outputNames = meta.outputNames,
            inputDataType = meta.inputDataType,
            outputDataTypes = meta.outputDataTypes,
            onGpu = onGpu,
        )
    } catch (t: Throwable) {
        compiled.close()
        throw t
    }
}

private fun gpuOptions(numThreads: Int): CompiledModel.Options {
    return CompiledModel.Options(Accelerator.GPU).apply {
        cpuOptions = CompiledModel.CpuOptions(numThreads = numThreads)
    }
}

private fun cpuOptions(numThreads: Int): CompiledModel.Options {
    return CompiledModel.Options(Accelerator.CPU).apply {
        cpuOptions = CompiledModel.CpuOptions(numThreads = numThreads)
    }
}

private fun compileModel(
    assets: AssetManager,
    root: ModelResourceRoot,
    relativePath: String,
    options: CompiledModel.Options,
): CompiledModel {
    return when (root) {
        is ModelResourceRoot.Assets -> CompiledModel.create(
            assets,
            resolveRelativePath(root.baseAssetDir, relativePath),
            options,
        )
        is ModelResourceRoot.Files -> {
            val file = canonicalPackFile(root, relativePath)
            CompiledModel.create(file.path, options)
        }
    }
}

private data class TensorMeta(
    val inputShapes: List<IntArray>,
    val outputShapes: List<IntArray>,
    val outputNames: List<String>,
    val inputDataType: DataType,
    val outputDataTypes: List<DataType>,
)

/** Read shapes and names with the CPU interpreter, then close it. */
private fun readTensorMeta(
    assets: AssetManager,
    root: ModelResourceRoot,
    relativePath: String,
    numThreads: Int,
    concreteInputShape: IntArray?,
): TensorMeta {
    val interpreter = Interpreter(
        loadMappedModelBuffer(assets, root, relativePath),
        Interpreter.Options().apply { setNumThreads(numThreads) },
    )
    try {
        interpreter.allocateTensors()
        if (concreteInputShape != null &&
            !interpreter.getInputTensor(0).shape().contentEquals(concreteInputShape)
        ) {
            interpreter.resizeInput(0, concreteInputShape)
            interpreter.allocateTensors()
        }
        val input = interpreter.getInputTensor(0)
        return TensorMeta(
            inputShapes = listOf(input.shape().copyOf()),
            outputShapes = List(interpreter.outputTensorCount) {
                interpreter.getOutputTensor(it).shape().copyOf()
            },
            outputNames = List(interpreter.outputTensorCount) {
                interpreter.getOutputTensor(it).name()
            },
            inputDataType = input.dataType(),
            outputDataTypes = List(interpreter.outputTensorCount) {
                interpreter.getOutputTensor(it).dataType()
            },
        )
    } finally {
        interpreter.close()
    }
}

private fun resolvedModelPath(root: ModelResourceRoot, relativePath: String): String {
    return when (root) {
        is ModelResourceRoot.Assets -> resolveRelativePath(root.baseAssetDir, relativePath)
        is ModelResourceRoot.Files -> canonicalPackFile(root, relativePath).path
    }
}

private fun canonicalPackFile(root: ModelResourceRoot.Files, relativePath: String): File {
    val file = File(root.baseDir, relativePath).canonicalFile
    require(file.path.startsWith(root.baseDir.canonicalFile.path)) {
        "Path escapes model root: $relativePath"
    }
    return file
}

/**
 * Open a TFLite file on the CPU interpreter.
 *
 * Used to check a pack's tensor shapes. Inference uses [openCompiledSession].
 */
fun createInterpreter(
    assets: AssetManager,
    root: ModelResourceRoot,
    relativePath: String,
    numThreads: Int,
): Interpreter {
    val resolvedPath = resolvedModelPath(root, relativePath)
    val options = Interpreter.Options().apply { setNumThreads(numThreads) }
    return Interpreter(loadMappedModelBuffer(assets, root, relativePath), options).also { interpreter ->
        interpreter.allocateTensors()
        val inputs = (0 until interpreter.inputTensorCount).joinToString("; ") { i ->
            val t = interpreter.getInputTensor(i)
            "in[$i] shape=${t.shape().contentToString()} type=${t.dataType()}"
        }
        val outputs = (0 until interpreter.outputTensorCount).joinToString("; ") { i ->
            val t = interpreter.getOutputTensor(i)
            "out[$i] shape=${t.shape().contentToString()} type=${t.dataType()}"
        }
        Timber.i("TFLite loaded $resolvedPath: $inputs | $outputs")
    }
}

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
