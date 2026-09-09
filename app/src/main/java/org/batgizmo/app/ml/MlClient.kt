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
 * Outcome of ML analysis for one submitted audio buffer.
 * Shape will be extended as the model integration lands.
 */
data class MlResult(
    val detections: List<MlDetection> = emptyList(),
)

data class MlDetection(
    val label: String,
    val confidence: Float,
)

/**
 * Client used by parts of the app that need ML analysis of raw audio.
 *
 * Callers construct a concrete subclass with a result lambda; that lambda is
 * invoked when analysis of a previously submitted buffer completes.
 *
 * Subclasses are interchangeable via this common type: use [MlClientStub]
 * for testing, and a full backend implementation for production.
 *
 * Incoming samples are copied into an owned buffer of [MlProcessor.CHUNK_SIZE]
 * samples. [process] is invoked only when that chunk is full.
 *
 * @param sampleRateHz sample rate of all buffers submitted to this client
 * @param onResult invoked with the analysis outcome for a submitted buffer
 */
abstract class MlClient(
    protected val sampleRateHz: Int,
    private val onResult: (MlResult) -> Unit,
) {
    private var chunkBuffer: ShortArray? = null
    private var chunkCurrentCount = 0

    init {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
    }

    /**
     * Submit mono 16-bit PCM samples for analysis.
     *
     * The caller's buffer is not retained after this call returns; the caller
     * may reuse it. [offset] and [count] select the slice within [buffer].
     * Samples are accumulated into an owned chunk; [process] runs when a full
     * chunk of [MlProcessor.CHUNK_SIZE] samples is ready.
     */
    fun submit(
        buffer: ShortArray,
        offset: Int,
        count: Int,
    ) {
        require(offset >= 0) { "offset must be >= 0" }
        require(count >= 0) { "count must be >= 0" }
        require(offset + count <= buffer.size) {
            "offset + count exceeds buffer size"
        }

        var srcOffset = offset
        var remaining = count
        while (remaining > 0) {
            val chunk = chunkBuffer
                ?: ShortArray(MlProcessor.CHUNK_SIZE).also { chunkBuffer = it }
            val space = MlProcessor.CHUNK_SIZE - chunkCurrentCount
            val toCopy = minOf(remaining, space)
            System.arraycopy(buffer, srcOffset, chunk, chunkCurrentCount, toCopy)
            chunkCurrentCount += toCopy
            srcOffset += toCopy
            remaining -= toCopy

            if (chunkCurrentCount == MlProcessor.CHUNK_SIZE) {
                // Hand ownership of this chunk to process; allocate a fresh
                // buffer for the next accumulation so the previous one can be
                // reclaimed if the subclass does not retain it.
                chunkBuffer = null
                chunkCurrentCount = 0
                process(chunk, 0, MlProcessor.CHUNK_SIZE)
            }
        }
    }

    /**
     * Analyse a full chunk. Called from [submit] when the chunk buffer is full.
     *
     * Ownership of [buffer] transfers to the subclass for this call. This client
     * does not retain a reference afterward. Implementations may keep the
     * buffer for async work, or drop it so the garbage collector can reclaim it.
     * Deliver outcomes with [deliverResult].
     */
    protected abstract fun process(
        buffer: ShortArray,
        offset: Int,
        count: Int,
    )

    /** Invoke the caller-supplied result callback. */
    protected fun deliverResult(result: MlResult) {
        onResult(result)
    }
}
