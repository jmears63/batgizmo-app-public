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

import org.batgizmo.app.ml.MlProcessor.Companion.CHUNK_SIZE_AT_REQUIRED_RATE
import org.batgizmo.app.ml.MlProcessor.Companion.REQUIRED_SAMPLE_RATE_HZ


/**
 * ML processing backend.
 *
 * Implementation to be added later, including a limited length queue of chunks waiting to
 * be processed.
 */
class MlProcessor {
    companion object {
        /** Sample rate at which [CHUNK_SIZE_AT_REQUIRED_RATE] is defined. */
        private const val REQUIRED_SAMPLE_RATE_HZ = 256_000

        /** Samples per chunk at [REQUIRED_SAMPLE_RATE_HZ]. */
        private const val CHUNK_SIZE_AT_REQUIRED_RATE = 144000

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
    }
}
