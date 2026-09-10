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

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Test-only [MlClient] that simulates an ML backend.
 *
 * After a short delay, delivers a fixed detection so callers can exercise the
 * async result callback path without a real model.
 *
 * Consumes each chunk by not retaining [processChunk]'s buffer, so the garbage
 * collector can reclaim it after this call returns.
 */
class MlClientStub(
    onResult: (MlResult) -> Unit,
) : MlClient(onResult) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("MlClientStub")
    )

    override fun processChunk(
        buffer: ShortArray,
        offset: Int,
        count: Int,
    ) {
        // Intentionally do not store [buffer]; ownership ends here so it can
        // be garbage-collected once this method returns.
        scope.launch {
            delay(300.milliseconds)
            deliverResult(
                MlResult(
                    detections = listOf(
                        MlDetection(
                            label = "Common pipistrelle",
                            confidence = 0.87f,
                        ),
                        MlDetection(
                            label = "Noctule",
                            confidence = 0.60f,
                        ),
                        MlDetection(
                            label = "Daubenton",
                            confidence = 0.70f,
                        ),
                        MlDetection(
                            label = "Daubenton",
                            confidence = 0.87f,
                        )
                    )
                )
            )
        }
    }
}
