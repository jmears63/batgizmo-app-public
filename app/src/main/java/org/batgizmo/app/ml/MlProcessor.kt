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
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * How [MlProcessor.tryEnqueue] behaves when many buffers are already waiting.
 *
 * - [DropIfFull]: live audio — never queue more than [MlProcessor.MAX_LIVE_QUEUED];
 *   drop new buffers so inference stays near real time.
 * - [QueueAll]: file viewer — accept every buffer; process in order no matter how long
 *   it takes.
 */
enum class MlOverflowPolicy {
    DropIfFull,
    QueueAll,
}

/**
 * ML processing backend.
 *
 * Raw PCM is resampled once on a dedicated thread (stateful r8brain → model-rate
 * ring), then overlapping windows are offered to a single infer thread (LiteRT
 * is not thread-safe).
 *
 * Under [MlOverflowPolicy.DropIfFull], [tryEnqueue] drops when [pendingRawCount]
 * already reaches [MAX_LIVE_QUEUED].
 */
class MlProcessor(
    context: Context,
    private val modelId: String,
) {
    private sealed class RawItem {
        class Audio(
            val buffer: ShortArray,
            val sampleRateHz: Int,
            val observedAtEpochSec: Double,
            val isLast: Boolean,
            val bufferId: Int,
            val queuedAtElapsedRealtimeMs: Long,
            val epoch: Long,
        ) : RawItem()

        /** Clear ring + resampler; keep workers alive. */
        object ClearStream : RawItem()

        object Shutdown : RawItem()
    }

    private sealed class InferItem {
        class Job(
            val window: FloatArray,
            val bufferId: Int,
            val epoch: Long,
            val observedAtEpochSec: Double,
            val queuedAtElapsedRealtimeMs: Long,
            val queueWaitMs: Long,
            val resampleMs: Long,
            val inferQueuedAtElapsedRealtimeMs: Long,
            val inferQueueLengthWhenQueued: Int,
        ) : InferItem()

        object Shutdown : InferItem()
    }

    /** Timing / identity carried from a raw buffer through window emission. */
    private data class EmissionContext(
        val windowing: MlWindowing,
        val bufferId: Int,
        val jobEpoch: Long,
        val queuedAtMs: Long,
        val queueWaitMs: Long,
        val resampleStartedAtMs: Long,
    ) {
        val hop: Int get() = windowing.hopSamples()
        val resampleMs: Long
            get() = SystemClock.elapsedRealtime() - resampleStartedAtMs
    }

    /** Model-rate float ring: append resampled samples, copy fixed windows with hop. */
    private class FloatRing {
        private var buf = FloatArray(0)
        private var head = 0
        var size: Int = 0
            private set

        fun clear() {
            head = 0
            size = 0
        }

        fun append(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset) {
            if (count <= 0) return
            ensureCapacity(size + count)
            var src = offset
            var remaining = count
            while (remaining > 0) {
                val tail = (head + size) % buf.size
                val contiguous = min(remaining, buf.size - tail)
                System.arraycopy(samples, src, buf, tail, contiguous)
                size += contiguous
                src += contiguous
                remaining -= contiguous
            }
        }

        fun appendZeros(count: Int) {
            if (count <= 0) return
            ensureCapacity(size + count)
            var remaining = count
            while (remaining > 0) {
                val tail = (head + size) % buf.size
                val contiguous = min(remaining, buf.size - tail)
                buf.fill(0f, tail, tail + contiguous)
                size += contiguous
                remaining -= contiguous
            }
        }

        fun copyWindow(dest: FloatArray) {
            require(dest.size <= size) { "window larger than ring" }
            val first = min(dest.size, buf.size - head)
            System.arraycopy(buf, head, dest, 0, first)
            if (first < dest.size) {
                System.arraycopy(buf, 0, dest, first, dest.size - first)
            }
        }

        fun discard(n: Int) {
            require(n in 0..size) { "discard out of range" }
            if (n == 0) return
            head = (head + n) % buf.size
            size -= n
            if (size == 0) head = 0
        }

        private fun ensureCapacity(needed: Int) {
            if (needed <= buf.size) return
            val grown = FloatArray(
                needed.coerceAtLeast((buf.size * 2).coerceAtLeast(16))
            )
            if (size > 0) {
                val first = min(size, buf.size - head)
                System.arraycopy(buf, head, grown, 0, first)
                if (first < size) {
                    System.arraycopy(buf, 0, grown, first, size - first)
                }
            }
            buf = grown
            head = 0
        }
    }

    /**
     * Resample-thread state: stateful resampler, model-rate ring, and timeline.
     * Not thread-safe — owned exclusively by [resampleLoop].
     */
    private inner class ResampleStream {
        val ring = FloatRing()
        private var resamplerHandle = 0L
        private var resamplerInHz = 0
        private var resamplerOutHz = 0
        private var ringStartEpochSec = 0.0
        private var ringHasTimeline = false
        var streamEpoch: Long = epoch.get()

        fun noteRing() {
            ringFillForUi = ring.size
            publishBuffering()
        }

        fun clear() {
            destroyResampler()
            ring.clear()
            ringHasTimeline = false
            streamEpoch = epoch.get()
            noteRing()
        }

        fun ensureResampler(inHz: Int, outHz: Int): Boolean {
            val identity = inHz == outHz
            val ready =
                resamplerInHz == inHz &&
                    resamplerOutHz == outHz &&
                    (identity || resamplerHandle != 0L)
            if (ready) return true

            destroyResampler()
            resamplerInHz = inHz
            resamplerOutHz = outHz
            if (identity) return true

            val handle = nativeResamplerCreate(inHz, outHz)
            if (handle == 0L) {
                Timber.e("MlProcessor: failed to create resampler $inHz → $outHz")
                resamplerInHz = 0
                resamplerOutHz = 0
                return false
            }
            resamplerHandle = handle
            return true
        }

        fun appendPcm(pcm: ShortArray, offset: Int = 0, count: Int = pcm.size - offset) {
            if (count <= 0) return
            val floats = FloatArray(count)
            for (i in 0 until count) {
                floats[i] = pcm[offset + i] / 32768f
            }
            ring.append(floats)
        }

        fun appendResampledOrIdentity(
            pcm: ShortArray,
            captureRateHz: Int,
            modelRateHz: Int,
            bufferId: Int,
            observedAtEpochSec: Double,
        ) {
            if (pcm.isEmpty()) return
            if (!ringHasTimeline) {
                ringStartEpochSec = observedAtEpochSec
                ringHasTimeline = true
            }
            if (captureRateHz == modelRateHz) {
                appendPcm(pcm)
                return
            }
            val out = nativeResamplerProcess(resamplerHandle, pcm, 0, pcm.size)
            if (out == null) {
                Timber.e("MlProcessor: resample failed on buffer #$bufferId")
            } else {
                appendPcm(out)
            }
        }

        fun flushResampler() {
            if (resamplerHandle == 0L) return
            val flushed = nativeResamplerFlush(resamplerHandle) ?: return
            if (flushed.isNotEmpty()) appendPcm(flushed)
        }

        fun emitFullWindows(ctx: EmissionContext) {
            val w = ctx.windowing
            val resampleMs = ctx.resampleMs
            while (ring.size >= w.windowSamples) {
                if (ctx.jobEpoch != epoch.get()) return
                val window = FloatArray(w.windowSamples)
                ring.copyWindow(window)
                val observed = if (ringHasTimeline) ringStartEpochSec else 0.0
                offerWindow(window, ctx, observed, resampleMs)
                ring.discard(ctx.hop)
                if (ringHasTimeline) {
                    ringStartEpochSec += ctx.hop.toDouble() / w.modelSampleRateHz
                }
            }
            noteRing()
        }

        /**
         * Emit every full window, then zero-pad any partial remainder into one
         * final window. No-op if the ring is empty.
         */
        fun emitEndOfStream(ctx: EmissionContext) {
            if (ring.size <= 0) return
            if (ctx.jobEpoch != epoch.get()) return
            emitFullWindows(ctx)
            if (ring.size <= 0 || ctx.jobEpoch != epoch.get()) return
            if (ring.size >= ctx.windowing.windowSamples) return

            ring.appendZeros(ctx.windowing.windowSamples - ring.size)
            val window = FloatArray(ctx.windowing.windowSamples)
            ring.copyWindow(window)
            val observed = if (ringHasTimeline) ringStartEpochSec else 0.0
            offerWindow(window, ctx, observed, ctx.resampleMs)
            ring.clear()
            ringHasTimeline = false
            noteRing()
        }

        private fun destroyResampler() {
            if (resamplerHandle != 0L) {
                nativeResamplerDestroy(resamplerHandle)
                resamplerHandle = 0L
            }
            resamplerInHz = 0
            resamplerOutHz = 0
        }

        private fun offerWindow(
            window: FloatArray,
            ctx: EmissionContext,
            observedAtEpochSec: Double,
            resampleMs: Long,
        ) {
            if (ctx.jobEpoch != epoch.get()) return
            val inferQueuedAtMs = SystemClock.elapsedRealtime()
            val inferDepth = inferPendingCount.incrementAndGet()
            markWorkStarted()
            val offered = inferQueue.offer(
                InferItem.Job(
                    window = window,
                    bufferId = ctx.bufferId,
                    epoch = ctx.jobEpoch,
                    observedAtEpochSec = observedAtEpochSec,
                    queuedAtElapsedRealtimeMs = ctx.queuedAtMs,
                    queueWaitMs = ctx.queueWaitMs,
                    resampleMs = resampleMs,
                    inferQueuedAtElapsedRealtimeMs = inferQueuedAtMs,
                    inferQueueLengthWhenQueued = inferDepth,
                )
            )
            if (!offered) {
                inferPendingCount.decrementAndGet()
                markWorkFinished()
                Timber.w(
                    "MlProcessor: infer queue rejected window from buffer #${ctx.bufferId}"
                )
            }
        }
    }

    companion object {
        /**
         * Soft cap on queued raw buffers in live mode ([MlOverflowPolicy.DropIfFull]).
         * Viewer mode ([MlOverflowPolicy.QueueAll]) ignores this.
         */
        const val MAX_LIVE_QUEUED = 10

        init {
            System.loadLibrary("batgizmo-native")
        }

        @JvmStatic
        private external fun nativeResamplerCreate(
            inputSampleRateHz: Int,
            outputSampleRateHz: Int,
        ): Long

        @JvmStatic
        private external fun nativeResamplerProcess(
            handle: Long,
            input: ShortArray,
            offset: Int,
            count: Int,
        ): ShortArray?

        @JvmStatic
        private external fun nativeResamplerFlush(handle: Long): ShortArray?

        @JvmStatic
        private external fun nativeResamplerDestroy(handle: Long)
    }

    private val appContext = context.applicationContext
    private val rawQueue = LinkedBlockingQueue<RawItem>()
    private val inferQueue = LinkedBlockingQueue<InferItem>()

    private val pendingRawCount = AtomicInteger(0)
    private val inferPendingCount = AtomicInteger(0)
    private val bufferIdSeq = AtomicInteger(0)
    /** Bumped by [clearQueue] / [reset] so in-flight work is ignored. */
    private val epoch = AtomicLong(0)

    private val activeWorkCount = AtomicInteger(0)
    private val mutableBusyFlow = MutableStateFlow(false)
    /**
     * True while any work unit is outstanding (queued or in-flight raw/infer).
     * Drives the Auto Id sparkle together with [isBuffering].
     */
    val isBusy: StateFlow<Boolean> = mutableBusyFlow.asStateFlow()

    private val mutableBufferingFlow = MutableStateFlow(false)
    /**
     * True while raw PCM is waiting to be resampled, or the model-rate ring holds
     * a partial window (not yet a full infer input). Does not include infer-only
     * work — that is covered by [isBusy].
     */
    val isBuffering: StateFlow<Boolean> = mutableBufferingFlow.asStateFlow()

    /** Ring fill observed on the resample thread; used for [isBuffering]. */
    @Volatile
    private var ringFillForUi: Int = 0

    @Volatile
    private var overflowPolicy: MlOverflowPolicy = MlOverflowPolicy.DropIfFull

    @Volatile
    private var labelLanguageIndex: Int = 0

    @Volatile
    private var suppressedLabelKeys: Set<String> = emptySet()

    @Volatile
    private var geoSnapshot: BirdNetModel.GeoSnapshot? = null

    @Volatile
    private var windowing: MlWindowing? = null

    private var model: MlModelBase? = null

    /**
     * Capture rate from the last [reset]. Recorded for API honesty; each
     * [tryEnqueue] still carries its own rate (resampler adapts if it differs).
     */
    @Volatile
    private var captureSampleRateHz: Int = 0

    /**
     * True after [start] until [close] begins. Cleared at the start of [close]
     * so [tryEnqueue] stops accepting before workers are torn down.
     */
    @Volatile
    private var running = false

    private var resampleThread: Thread? = null
    private var inferThread: Thread? = null

    fun setOverflowPolicy(policy: MlOverflowPolicy) {
        overflowPolicy = policy
    }

    fun setLabelLanguage(languageIndex: Int) {
        labelLanguageIndex = languageIndex
    }

    fun setSuppressions(suppressions: Map<String, Boolean>) {
        suppressedLabelKeys = suppressions.filterValues { it }.keys
    }

    fun setGeoSnapshot(snapshot: BirdNetModel.GeoSnapshot?) {
        if (snapshot == null) return
        if (geoSnapshot != null) return
        geoSnapshot = snapshot
        val opened = model
        if (opened is BirdNetModel) {
            opened.setGeoSnapshot(snapshot)
        }
    }

    fun clearGeoSnapshot() {
        geoSnapshot = null
        val opened = model
        if (opened is BirdNetModel) {
            opened.resetGeoFilter()
        }
    }

    fun setWindowing(windowing: MlWindowing) {
        this.windowing = windowing
    }

    /**
     * Record the capture sample rate and clear stream/queue state.
     * Must be called before [tryEnqueue], and again whenever the rate changes.
     */
    fun reset(sampleRateHz: Int) {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        captureSampleRateHz = sampleRateHz
        clearQueue()
    }

    /**
     * Discard queued raw and infer work and reset the resample stream.
     *
     * Each queued [RawItem.Audio] / [InferItem.Job] holds one [activeWorkCount]
     * unit: this method finishes those units. In-flight items (already taken by
     * a worker) are finished by that worker's `finally` / epoch skip path — never
     * by both. [Shutdown] sentinels are preserved if encountered while draining.
     */
    fun clearQueue() {
        val newEpoch = epoch.incrementAndGet()
        var dropped = 0
        var sawRawShutdown = false
        while (true) {
            val item = rawQueue.poll() ?: break
            when (item) {
                is RawItem.Audio -> {
                    dropped++
                    pendingRawCount.decrementAndGet()
                    markWorkFinished()
                }
                RawItem.ClearStream -> Unit
                RawItem.Shutdown -> sawRawShutdown = true
            }
        }
        var sawInferShutdown = false
        while (true) {
            val item = inferQueue.poll() ?: break
            when (item) {
                is InferItem.Job -> {
                    dropped++
                    inferPendingCount.decrementAndGet()
                    markWorkFinished()
                }
                InferItem.Shutdown -> sawInferShutdown = true
            }
        }
        // Do not pendingRawCount.set(0): in-flight takes already decremented;
        // forcing zero would desync the live depth cap from the real queue.
        rawQueue.offer(RawItem.ClearStream)
        if (sawRawShutdown) {
            rawQueue.offer(RawItem.Shutdown)
        }
        if (sawInferShutdown) {
            inferQueue.offer(InferItem.Shutdown)
        }
        if (dropped > 0) {
            Timber.i("MlProcessor: cleared $dropped queued job(s) (epoch=$newEpoch)")
        }
        publishBuffering()
    }

    /**
     * Offer PCM for background processing without blocking.
     *
     * On success, ownership of [buffer] transfers when [offset] is 0 and [count]
     * equals [buffer].size; otherwise a slice is copied. Empty [count] with
     * [isLast] true flushes the stream (end of page / disconnect).
     *
     * @return true if accepted
     */
    fun tryEnqueue(
        buffer: ShortArray,
        offset: Int,
        count: Int,
        sampleRateHz: Int,
        observedAtEpochSec: Double,
        isLast: Boolean = false,
    ): Boolean {
        require(offset >= 0) { "offset must be >= 0" }
        require(count >= 0) { "count must be >= 0" }
        require(offset + count <= buffer.size) { "offset + count exceeds buffer size" }
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        if (!running) return false
        if (count == 0 && !isLast) return true

        val owned =
            when {
                count == 0 -> ShortArray(0)
                offset == 0 && count == buffer.size -> buffer
                else -> buffer.copyOfRange(offset, offset + count)
            }

        val bufferId = bufferIdSeq.incrementAndGet()
        // Snapshot epoch after reserving the slot so a concurrent clearQueue that
        // already bumped epoch either drains us (finishes our work unit) or we
        // carry the new epoch.
        if (overflowPolicy == MlOverflowPolicy.DropIfFull) {
            while (true) {
                if (!running) return false
                val pending = pendingRawCount.get()
                if (pending >= MAX_LIVE_QUEUED) {
                    Timber.w(
                        "MlProcessor live queue depth $pending: buffer #$bufferId dropped " +
                            "(${owned.size} samples)"
                    )
                    return false
                }
                if (pendingRawCount.compareAndSet(pending, pending + 1)) break
            }
        } else {
            pendingRawCount.incrementAndGet()
        }

        markWorkStarted()
        if (captureSampleRateHz != 0 && sampleRateHz != captureSampleRateHz) {
            Timber.d(
                "MlProcessor: enqueue rate $sampleRateHz Hz differs from " +
                    "last reset $captureSampleRateHz Hz (buffer #$bufferId)"
            )
        }
        val submission = RawItem.Audio(
            buffer = owned,
            sampleRateHz = sampleRateHz,
            observedAtEpochSec = observedAtEpochSec,
            isLast = isLast,
            bufferId = bufferId,
            queuedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            epoch = epoch.get(),
        )
        if (!running || !rawQueue.offer(submission)) {
            pendingRawCount.decrementAndGet()
            markWorkFinished()
            Timber.w("MlProcessor: buffer #$bufferId not accepted")
            return false
        }
        Timber.i(
            "MlProcessor: queued buffer #$bufferId (${owned.size} samples" +
                "${if (isLast) ", last" else ""}), queue length ${pendingRawCount.get()}"
        )
        publishBuffering()
        return true
    }

    /** Start the resample and infer worker threads. Safe to call once. */
    fun start(onResult: (MlResult) -> Unit) {
        check(!running) { "MlProcessor already started" }
        running = true
        val resample = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            resampleLoop()
        }, "MlResample")
        val infer = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            inferLoop(onResult)
        }, "MlInfer")
        resampleThread = resample
        inferThread = infer
        resample.start()
        infer.start()
    }

    /**
     * Stop accepting work, wake workers, join them, then release the model.
     * Idempotent. [running] is cleared first so [tryEnqueue] fails immediately.
     */
    fun close() {
        if (!running) return
        running = false
        rawQueue.offer(RawItem.Shutdown)
        val resample = resampleThread
        val infer = inferThread
        try {
            resample?.join(5_000)
            infer?.join(5_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (resample?.isAlive == true || infer?.isAlive == true) {
            Timber.w("MlProcessor: workers still alive after join; interrupting")
            resample?.interrupt()
            infer?.interrupt()
            try {
                resample?.join(1_000)
                infer?.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        // Model is owned by the infer thread until workers have stopped.
        model?.close()
        model = null
        resampleThread = null
        inferThread = null
        activeWorkCount.set(0)
        mutableBusyFlow.value = false
        mutableBufferingFlow.value = false
        ringFillForUi = 0
    }

    private fun resampleLoop() {
        val stream = ResampleStream()
        try {
            while (true) {
                when (val item = rawQueue.take()) {
                    RawItem.Shutdown -> {
                        stream.clear()
                        inferQueue.offer(InferItem.Shutdown)
                        break
                    }
                    RawItem.ClearStream -> stream.clear()
                    is RawItem.Audio -> processAudio(stream, item)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            inferQueue.offer(InferItem.Shutdown)
        } finally {
            stream.clear()
        }
    }

    private fun processAudio(stream: ResampleStream, item: RawItem.Audio) {
        pendingRawCount.decrementAndGet()
        val dequeuedAtMs = SystemClock.elapsedRealtime()
        val queueWaitMs = dequeuedAtMs - item.queuedAtElapsedRealtimeMs
        try {
            if (item.epoch != epoch.get()) return
            val w = resolveWindowing() ?: return
            val ctx = EmissionContext(
                windowing = w,
                bufferId = item.bufferId,
                jobEpoch = item.epoch,
                queuedAtMs = item.queuedAtElapsedRealtimeMs,
                queueWaitMs = queueWaitMs,
                resampleStartedAtMs = dequeuedAtMs,
            )

            // Defense in depth with MlClient.willAcceptAudio: client avoids
            // enqueueing useless live audio; we still gate here for BYOM /
            // model switches and flush-only isLast submits below the min rate.
            if (item.sampleRateHz < w.minSampleRateHz) {
                Timber.i(
                    "MlProcessor: buffer #${item.bufferId} dropped " +
                        "(${item.sampleRateHz} Hz < min ${w.minSampleRateHz} Hz)"
                )
                if (item.isLast) {
                    stream.emitEndOfStream(ctx)
                    stream.clear()
                }
                return
            }

            if (item.epoch != stream.streamEpoch) {
                stream.clear()
                stream.streamEpoch = item.epoch
            }
            if (!stream.ensureResampler(item.sampleRateHz, w.modelSampleRateHz)) {
                return
            }

            stream.appendResampledOrIdentity(
                item.buffer,
                item.sampleRateHz,
                w.modelSampleRateHz,
                item.bufferId,
                item.observedAtEpochSec,
            )
            stream.emitFullWindows(ctx)

            if (item.isLast) {
                stream.flushResampler()
                stream.emitEndOfStream(ctx)
                stream.clear()
            } else {
                stream.noteRing()
            }
        } finally {
            markWorkFinished()
            publishBuffering()
        }
    }

    private fun inferLoop(onResult: (MlResult) -> Unit) {
        try {
            while (true) {
                when (val item = inferQueue.take()) {
                    InferItem.Shutdown -> break
                    is InferItem.Job -> {
                        inferPendingCount.decrementAndGet()
                        if (item.epoch != epoch.get()) {
                            markWorkFinished()
                            publishBuffering()
                            continue
                        }
                        try {
                            onResult(inferWindow(item))
                        } finally {
                            markWorkFinished()
                            publishBuffering()
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun markWorkStarted() {
        activeWorkCount.incrementAndGet()
        mutableBusyFlow.value = true
    }

    private fun markWorkFinished() {
        val remaining = activeWorkCount.decrementAndGet()
        if (remaining < 0) {
            Timber.w("MlProcessor: activeWorkCount went negative ($remaining); clamping to 0")
            activeWorkCount.set(0)
            mutableBusyFlow.value = false
        } else if (remaining == 0) {
            mutableBusyFlow.value = false
        }
    }

    private fun publishBuffering() {
        val windowSamples = windowing?.windowSamples ?: Int.MAX_VALUE
        val ringFill = ringFillForUi
        // Ingest / partial-window only — not infer (see [isBusy]).
        mutableBufferingFlow.value =
            pendingRawCount.get() > 0 ||
                (ringFill > 0 && ringFill < windowSamples)
    }

    private fun resolveWindowing(): MlWindowing? {
        windowing?.let { return it }
        return try {
            MlCatalog.ensureInitialized(appContext)
            MlWindowing.from(MlCatalog.resolveDescriptor(appContext.assets, modelId)).also {
                windowing = it
            }
        } catch (e: Exception) {
            Timber.e(e, "MlProcessor: no descriptor for $modelId")
            null
        }
    }

    private fun ensureModel(): MlModelBase? {
        model?.let { return it }
        return try {
            MlCatalog.ensureInitialized(appContext)
            MlCatalog.openModel(appContext.assets, modelId).also { opened ->
                model = opened
                windowing = MlWindowing.from(opened.descriptor)
                if (opened is BirdNetModel) {
                    geoSnapshot?.let { opened.setGeoSnapshot(it) }
                }
                Timber.i(
                    "MlProcessor: loaded Auto Id model ${opened.id} " +
                        "(${opened.sampleRateHz} Hz, ${opened.windowSamples} samples)"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "MlProcessor: failed to load Auto Id model $modelId")
            null
        }
    }

    private fun inferWindow(job: InferItem.Job): MlResult {
        fun completedResult(
            detections: List<MlDetection> = emptyList(),
        ): MlResult = MlResult(
            detections = detections,
            observedAtEpochSec = job.observedAtEpochSec,
            completedAtEpochSec = System.currentTimeMillis() / 1000.0,
        )

        val autoId = ensureModel() ?: return completedResult()
        val detectionThreshold = autoId.detectionThreshold

        return try {
            val inferStartedAtMs = SystemClock.elapsedRealtime()
            val scores = autoId.predict(job.window)
            val labelDiscard = autoId.labelDiscard
            val labelKeys = autoId.labelKeys
            var displayLabels: List<String>? = null
            val detections = ArrayList<MlDetection>()
            for (i in scores.indices) {
                val score = scores[i]
                if (score <= detectionThreshold ||
                    labelDiscard[i] ||
                    labelKeys[i] in suppressedLabelKeys
                ) {
                    continue
                }
                val labels = displayLabels
                    ?: autoId.labelsFor(labelLanguageIndex).also { displayLabels = it }
                detections.add(
                    MlDetection(
                        label = labels[i],
                        confidence = score,
                    )
                )
            }
            detections.sortByDescending { it.confidence }
            val inferMs = SystemClock.elapsedRealtime() - inferStartedAtMs
            val inferQueueWaitMs = inferStartedAtMs - job.inferQueuedAtElapsedRealtimeMs
            val totalMs = SystemClock.elapsedRealtime() - job.queuedAtElapsedRealtimeMs
            val timing =
                "queue ${job.queueWaitMs} ms, resample ${job.resampleMs} ms, " +
                    "infer-queue ${job.inferQueueLengthWhenQueued} deep / $inferQueueWaitMs ms, " +
                    "infer $inferMs ms, total $totalMs ms"
            if (detections.isEmpty()) {
                Timber.i(
                    "MlProcessor: buffer #${job.bufferId} detections: (none) ($timing)"
                )
            } else {
                val listed = detections.joinToString { d ->
                    "${d.label}=${"%.0f".format(d.confidence * 100)}%"
                }
                Timber.i(
                    "MlProcessor: buffer #${job.bufferId} detections: $listed ($timing)"
                )
            }
            completedResult(detections)
        } catch (e: Exception) {
            val totalMs = SystemClock.elapsedRealtime() - job.queuedAtElapsedRealtimeMs
            Timber.e(
                e,
                "MlProcessor: inference failed on buffer #${job.bufferId} " +
                    "(queue ${job.queueWaitMs} ms, resample ${job.resampleMs} ms, " +
                    "total $totalMs ms)"
            )
            completedResult()
        }
    }
}
