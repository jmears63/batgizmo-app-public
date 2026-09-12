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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production [MlClientBase] that runs real ML analysis (via [MlProcessor]).
 *
 * Chunk ownership transfers here from [MlClientBase.submit]; [processChunk]
 * only enqueues work so the caller is not blocked on inference.
 *
 * Resampling runs on a small thread pool; LiteRT inference stays on one thread.
 */
class MlClient(
    context: Context,
    onResult: (MlResult) -> Unit,
) : MlClientBase(onResult) {

    private val processor = MlProcessor(context.applicationContext)

    private val resampleParallelism = MlProcessor.defaultResampleParallelism()

    private val resampleThreadIndex = AtomicInteger(0)

    private val resampleDispatcher: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(resampleParallelism) { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "MlResample-${resampleThreadIndex.getAndIncrement()}")
        }.asCoroutineDispatcher()

    private val inferDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "MlInfer")
        }.asCoroutineDispatcher()

    private val scope = CoroutineScope(
        SupervisorJob() + CoroutineName("MlClient")
    )
    private val runJob: Job = scope.launch {
        processor.run(
            resampleDispatcher = resampleDispatcher,
            inferDispatcher = inferDispatcher,
            resampleParallelism = resampleParallelism,
        ) { result ->
            deliverResult(result)
        }
    }

    /** Live vs viewer enqueue policy; forwarded to [MlProcessor]. */
    fun setOverflowPolicy(policy: MlOverflowPolicy) {
        processor.setOverflowPolicy(policy)
    }

    /** Species-name language index for the ML panel; forwarded to [MlProcessor]. */
    fun setLabelLanguage(languageIndex: Int) {
        processor.setLabelLanguage(languageIndex)
    }

    /** User suppressions (Latin key → ignore); forwarded to [MlProcessor]. */
    fun setBbnSuppressions(suppressions: Map<String, Boolean>) {
        processor.setBbnSuppressions(suppressions)
    }

    /** Discard queued chunks (not the in-flight one). */
    fun clearQueue() {
        processor.clearQueue()
    }

    override fun reset(sampleRateHz: Int) {
        processor.clearQueue()
        super.reset(sampleRateHz)
    }

    override fun processChunk(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        observedAtEpochSec: Double,
    ) {
        // Non-blocking hand-off; ownership transfers on success.
        processor.tryEnqueue(buffer, offset, count, sampleRateHz, observedAtEpochSec)
    }

    override fun shutdown() {
        processor.close()
        runJob.cancel()
        scope.cancel()
        resampleDispatcher.close()
        inferDispatcher.close()
        super.shutdown()
    }
}
