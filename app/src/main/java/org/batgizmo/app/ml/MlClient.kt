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

import android.content.Context
import android.os.Process
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Production [MlClientBase] that runs real ML analysis (via [MlProcessor]).
 *
 * Chunk ownership transfers here from [MlClientBase.submit]; [processChunk]
 * only enqueues work so the caller is not blocked on inference.
 */
class MlClient(
    context: Context,
    onResult: (MlResult) -> Unit,
) : MlClientBase(onResult) {

    private val processor = MlProcessor(context.applicationContext)

    /** Single low-priority worker so resampling/inference stays off Default/IO pools. */
    private val lowPriorityDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "MlProcessor")
        }.asCoroutineDispatcher()

    private val scope = CoroutineScope(
        SupervisorJob() + lowPriorityDispatcher + CoroutineName("MlClient")
    )
    private val runJob: Job = scope.launch {
        processor.run { result ->
            deliverResult(result)
        }
    }

    override fun processChunk(
        buffer: ShortArray,
        offset: Int,
        count: Int,
    ) {
        // Non-blocking hand-off; ownership transfers on success.
        processor.tryEnqueue(buffer, offset, count, sampleRateHz)
    }

    override fun shutdown() {
        processor.close()
        runJob.cancel()
        scope.cancel()
        lowPriorityDispatcher.close()
        super.shutdown()
    }
}
