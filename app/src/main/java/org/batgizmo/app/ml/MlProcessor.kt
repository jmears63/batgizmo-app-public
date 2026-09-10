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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.batgizmo.app.ml.MlProcessor.Companion.CHUNK_SIZE_AT_REQUIRED_RATE
import org.batgizmo.app.ml.MlProcessor.Companion.REQUIRED_SAMPLE_RATE_HZ
import timber.log.Timber

/**
 * ML processing backend.
 *
 * Chunks are accepted via [tryEnqueue] (non-blocking, bounded queue) and consumed
 * by [run] on a worker coroutine. When the queue is full, [tryEnqueue] drops the
 * new chunk so producers never block.
 */
class MlProcessor {
    /**
     * One owned PCM chunk awaiting analysis.
     * [buffer] is fully owned; [offset] / [count] select the live region.
     */
    data class ChunkSubmission(
        val buffer: ShortArray,
        val offset: Int,
        val count: Int,
        val sampleRateHz: Int,
    )

    companion object {
        /** Sample rate expected by the model. */
        const val REQUIRED_SAMPLE_RATE_HZ = 256_000

        /** Samples per chunk at [REQUIRED_SAMPLE_RATE_HZ]. */
        const val CHUNK_SIZE_AT_REQUIRED_RATE = 144000

        /** Max chunks waiting for [run]; extras are dropped by [tryEnqueue]. */
        private const val MAX_QUEUED_CHUNKS = 2

        init {
            System.loadLibrary("batgizmo-native")
        }

        /**
         * Chunk length in samples for [sampleRateHz], scaled so duration matches
         * [CHUNK_SIZE_AT_REQUIRED_RATE] samples at [REQUIRED_SAMPLE_RATE_HZ].
         */
        fun chunkSizeForSampleRate(sampleRateHz: Int): Int {
            require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
            // Ceiling division so a fractional sample count rounds up.
            val scaled =
                (CHUNK_SIZE_AT_REQUIRED_RATE.toLong() * sampleRateHz +
                    REQUIRED_SAMPLE_RATE_HZ - 1) /
                    REQUIRED_SAMPLE_RATE_HZ
            return scaled.toInt().coerceAtLeast(1)
        }

        /**
         * Resample mono 16-bit PCM to [REQUIRED_SAMPLE_RATE_HZ] via r8brain (native).
         * Returns a newly allocated buffer, or null on failure.
         */
        @JvmStatic
        private external fun nativeResampleToRequiredRate(
            input: ShortArray,
            offset: Int,
            count: Int,
            inputSampleRateHz: Int,
        ): ShortArray?
    }

    private val queue = Channel<ChunkSubmission>(capacity = MAX_QUEUED_CHUNKS)

    private var chunkCount: Int = 0 // Count how many chunks we have processed.

    /**
     * Offer a chunk for background processing without blocking.
     *
     * On success, ownership of [buffer] transfers to this processor (or of a
     * copied slice when [offset]/[count] is not the whole array). On failure
     * (queue full or closed), the caller retains ownership and may discard it.
     *
     * @return true if the chunk was queued
     */
    fun tryEnqueue(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        sampleRateHz: Int,
    ): Boolean {
        require(offset >= 0) { "offset must be >= 0" }
        require(count >= 0) { "count must be >= 0" }
        require(offset + count <= buffer.size) { "offset + count exceeds buffer size" }
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        if (count == 0) return true

        val owned =
            if (offset == 0 && count == buffer.size) buffer
            else buffer.copyOfRange(offset, offset + count)

        val submission = ChunkSubmission(
            buffer = owned,
            offset = 0,
            count = owned.size,
            sampleRateHz = sampleRateHz,
        )
        return try {
            val result = queue.trySend(submission)
            if (!result.isSuccess) {
                Timber.w("MlProcessor queue full: chunk dropped (${owned.size} samples)")
            }
            result.isSuccess
        } catch (_: ClosedSendChannelException) {
            false
        }
    }

    /**
     * Consume queued chunks until [close] is called. Intended to run in a
     * dedicated coroutine. Invokes [onResult] for each processed chunk.
     */
    suspend fun run(onResult: (MlResult) -> Unit) {
        try {
            for (submission in queue) {
                onResult(processSubmission(submission))
            }
        } catch (_: CancellationException) {
            // Normal on shutdown.
        }
    }

    /** Stop accepting chunks and end [run]'s receive loop. */
    fun close() {
        queue.close()
    }

    /**
     * Analyse one queued chunk. Heavy work belongs here (resample, inference).
     * [submission.buffer] may be retained only for the duration of this call
     * unless explicitly kept for further async work.
     */
    private fun processSubmission(submission: ChunkSubmission): MlResult {
        chunkCount += 1

        Timber.i(
            "MlProcessor.processSubmission: chunk #$chunkCount: ${submission.count} samples " +
                "at ${submission.sampleRateHz} Hz"
        )

        // Resample the chunk data to the sampling rate the model needs. This should
        // result in the correct chunk size for the model (CHUNK_SIZE_AT_REQUIRED_RATE):
        val resampled = nativeResampleToRequiredRate(
            submission.buffer,
            submission.offset,
            submission.count,
            submission.sampleRateHz,
        )
        if (resampled == null) {
            Timber.e("MlProcessor: resample to $REQUIRED_SAMPLE_RATE_HZ Hz failed")
            return MlResult()
        }

        Timber.i(
            "MlProcessor: chunk #$chunkCount resampled to ${resampled.size} samples " +
                "at $REQUIRED_SAMPLE_RATE_HZ Hz"
        )

        // TODO: run model inference on [resampled] at REQUIRED_SAMPLE_RATE_HZ.
        return MlResult()
    }
}
