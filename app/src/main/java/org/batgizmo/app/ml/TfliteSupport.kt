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

fun createInterpreter(
    assets: AssetManager,
    root: ModelResourceRoot,
    relativePath: String,
    numThreads: Int,
): Interpreter {
    val options = Interpreter.Options().apply { setNumThreads(numThreads) }
    return Interpreter(loadMappedModelBuffer(assets, root, relativePath), options).also {
        it.allocateTensors()
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
