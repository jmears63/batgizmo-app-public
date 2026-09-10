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
 * Call [reset] with the sample rate before any [submit]. Chunk length is scaled
 * via [MlProcessor.chunkSizeForSampleRate]. Chunks overlap by 50%: after the
 * first half-chunk (written to one buffer only), each sample is written into
 * two in-flight chunks; whenever a chunk is full, [processChunk] is called for it.
 * Pass [submit]'s [isLast] when the stream ends so a partial final chunk is
 * zero-padded (after discarding the newest overlap) and processed.
 *
 * @param onResult invoked with the analysis outcome for a submitted buffer
 */
abstract class MlClient(
    private val onResult: (MlResult) -> Unit,
) {
    private class Chunk(
        val buffer: ShortArray,
        var filled: Int = 0,
    )

    /** Sample rate set by the last [reset]; 0 until [reset] has been called. */
    protected var sampleRateHz: Int = 0
        private set

    /**
     * Samples per chunk at the current [sampleRateHz]; 0 until [reset].
     * See [MlProcessor.chunkSizeForSampleRate].
     */
    var chunkSize: Int = 0
        private set

    /** Hop between chunk starts (50% of [chunkSize]); 0 until [reset]. */
    private var hopSize: Int = 0

    private val activeChunks = ArrayDeque<Chunk>()

    /**
     * Set the sample rate, recompute [chunkSize], and clear accumulated audio state.
     * Must be called before [submit], and again whenever the rate changes.
     */
    open fun reset(sampleRateHz: Int) {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        this.sampleRateHz = sampleRateHz
        chunkSize = MlProcessor.chunkSizeForSampleRate(sampleRateHz)
        hopSize = (chunkSize / 2).coerceAtLeast(1)
        activeChunks.clear()
        activeChunks.addLast(Chunk(ShortArray(chunkSize)))
    }

    /**
     * Submit mono 16-bit PCM samples for analysis.
     *
     * Requires a prior [reset]. The caller's buffer is not retained after this
     * call returns; the caller may reuse it. [offset] and [count] select the
     * slice within [buffer].
     *
     * Samples are written into all in-flight overlapping chunks. After the
     * first [hopSize] samples (first half-chunk), two chunks are filled in
     * parallel. When any chunk reaches [chunkSize], [processChunk] is invoked for it.
     *
     * When [isLast] is true, after absorbing [count] samples the newest overlapping
     * chunk is discarded and the currently fullest remaining chunk is zero-padded
     * to [chunkSize] and passed to [processChunk].
     */
    fun submit(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        isLast: Boolean = false,
    ) {
        check(sampleRateHz > 0 && chunkSize > 0 && hopSize > 0) {
            "reset(sampleRateHz) must be called before submit"
        }
        require(offset >= 0) { "offset must be >= 0" }
        require(count >= 0) { "count must be >= 0" }
        require(offset + count <= buffer.size) {
            "offset + count exceeds buffer size"
        }

        val size = chunkSize
        val hop = hopSize
        var srcOffset = offset
        var remaining = count

        while (remaining > 0) {
            if (activeChunks.isEmpty()) {
                activeChunks.addLast(Chunk(ShortArray(size)))
            }

            // Largest run we can write without missing a hop boundary or overflow.
            var toCopy = remaining
            for (chunk in activeChunks) {
                toCopy = minOf(toCopy, size - chunk.filled)
            }
            val last = activeChunks.last()
            if (last.filled < hop) {
                toCopy = minOf(toCopy, hop - last.filled)
            }
            check(toCopy > 0) { "internal error: no progress filling overlapping chunks" }

            for (chunk in activeChunks) {
                System.arraycopy(buffer, srcOffset, chunk.buffer, chunk.filled, toCopy)
                chunk.filled += toCopy
            }
            srcOffset += toCopy
            remaining -= toCopy

            // Drain completed chunks, then open the next overlap when the newest
            // reaches 50% full (may happen immediately after a completion).
            while (activeChunks.isNotEmpty()) {
                when {
                    activeChunks.first().filled == size -> {
                        val completed = activeChunks.removeFirst()
                        processChunk(completed.buffer, 0, size)
                    }
                    activeChunks.last().filled == hop -> {
                        activeChunks.addLast(Chunk(ShortArray(size)))
                    }
                    else -> break
                }
            }
        }

        if (isLast) {
            flushFinalChunk()
        }
    }

    /**
     * End-of-stream: drop the newest overlapping chunk, zero-pad the fullest
     * remaining chunk to [chunkSize], and process it. Clears in-flight state.
     */
    private fun flushFinalChunk() {
        if (activeChunks.isEmpty()) return

        // Newest overlap is incomplete relative to the older in-flight chunk.
        if (activeChunks.size > 1) {
            activeChunks.removeLast()
        }

        val fullest = activeChunks.maxBy { it.filled }
        activeChunks.clear()

        if (fullest.filled <= 0) return

        val size = chunkSize
        if (fullest.filled < size) {
            fullest.buffer.fill(0, fullest.filled, size)
        }
        processChunk(fullest.buffer, 0, size)
    }

    /**
     * Analyse a full chunk. Called from [submit] when a chunk buffer is full.
     *
     * Ownership of [buffer] transfers to the subclass for this call. This client
     * does not retain a reference afterward. Implementations may keep the
     * buffer for async work, or drop it so the garbage collector can reclaim it.
     * Deliver outcomes with [deliverResult].
     */
    protected abstract fun processChunk(
        buffer: ShortArray,
        offset: Int,
        count: Int,
    )

    /** Invoke the caller-supplied result callback. */
    protected fun deliverResult(result: MlResult) {
        onResult(result)
    }
}
