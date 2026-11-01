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
 * Multi-model ML processing backend.
 *
 * Raw PCM is ingested once, then a metadata-driven graph shares resample /
 * window / optional log-power STFT stages across active models. Each model
 * has its own infer thread and LiteRT instance.
 *
 * Under [MlOverflowPolicy.DropIfFull], [tryEnqueue] drops when [pendingRawCount]
 * already reaches [MAX_LIVE_QUEUED].
 */
class MlProcessor(
    context: Context,
    private val descriptors: List<MlModelDescriptor>,
) {
    init {
        require(descriptors.map { it.id }.toSet().size == descriptors.size) {
            "duplicate model ids in descriptors"
        }
    }

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

        /** Clear rings + resamplers; keep workers alive. */
        object ClearStream : RawItem()

        object Shutdown : RawItem()
    }

    private sealed class InferItem {
        class Job(
            val input: MlModelInput,
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

    private class StftHandle(
        val params: MlStftParams,
        val windowSamples: Int,
        var nativeHandle: Long,
    ) {
        fun process(pcm: FloatArray): FloatArray? {
            if (nativeHandle == 0L) return null
            val startedNs = System.nanoTime()
            val out = nativeStftProcess(nativeHandle, pcm)
            val stftMs = (System.nanoTime() - startedNs) / 1_000_000
            Timber.i(
                "MlProcessor: stft $stftMs ms " +
                    "(fft=${params.fftSize} hop=${params.hopSamples})"
            )
            return out
        }

        fun destroy() {
            if (nativeHandle != 0L) {
                nativeStftDestroy(nativeHandle)
                nativeHandle = 0L
            }
        }
    }

    /**
     * One model-rate branch: shared r8brain into float scratch, then fan-out
     * append into per-window rings (so different window/hop configs do not share
     * discard state).
     */
    private inner class RateStream(
        val modelRateHz: Int,
        val windowBranches: List<WindowStream>,
    ) {
        private var resamplerHandle = 0L
        private var resamplerInHz = 0
        private var resamplerOutHz = 0
        var streamEpoch: Long = epoch.get()

        fun clear() {
            destroyResampler()
            for (w in windowBranches) w.clear()
            streamEpoch = epoch.get()
            noteRings()
        }

        fun noteRings() {
            var fill = 0
            for (w in windowBranches) {
                if (w.ring.size > fill) fill = w.ring.size
            }
            ringFillForUi = fill
            publishBuffering()
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

        fun appendResampledOrIdentity(
            pcm: ShortArray,
            captureRateHz: Int,
            bufferId: Int,
            observedAtEpochSec: Double,
        ) {
            if (pcm.isEmpty()) return
            for (w in windowBranches) w.ensureTimeline(observedAtEpochSec)

            if (captureRateHz == modelRateHz) {
                appendPcmToWindows(pcm)
                return
            }
            val startedNs = System.nanoTime()
            val out = nativeResamplerProcess(resamplerHandle, pcm, 0, pcm.size)
            val resampleCallMs = (System.nanoTime() - startedNs) / 1_000_000
            Timber.i(
                "MlProcessor: resample $resampleCallMs ms " +
                    "($captureRateHz→$modelRateHz, buffer #$bufferId)"
            )
            if (out == null) {
                Timber.e("MlProcessor: resample failed on buffer #$bufferId ($captureRateHz→$modelRateHz)")
            } else {
                appendPcmToWindows(out)
            }
        }

        fun flushResampler() {
            if (resamplerHandle == 0L) return
            val startedNs = System.nanoTime()
            val flushed = nativeResamplerFlush(resamplerHandle)
            val resampleCallMs = (System.nanoTime() - startedNs) / 1_000_000
            Timber.i("MlProcessor: resample flush $resampleCallMs ms →${modelRateHz} Hz")
            if (flushed == null) return
            if (flushed.isNotEmpty()) appendPcmToWindows(flushed)
        }

        fun emitFullWindows(ctxBase: EmissionContext) {
            for (w in windowBranches) {
                w.emitFullWindows(
                    ctxBase.copy(windowing = w.windowing),
                    modelWorkers,
                )
            }
            noteRings()
        }

        fun emitEndOfStream(ctxBase: EmissionContext) {
            for (w in windowBranches) {
                w.emitEndOfStream(
                    ctxBase.copy(windowing = w.windowing),
                    modelWorkers,
                )
            }
            noteRings()
        }

        private fun appendPcmToWindows(pcm: ShortArray) {
            if (pcm.isEmpty()) return
            val floats = FloatArray(pcm.size)
            for (i in pcm.indices) {
                floats[i] = pcm[i] / 32768f
            }
            for (w in windowBranches) {
                w.ring.append(floats)
            }
        }

        private fun destroyResampler() {
            if (resamplerHandle != 0L) {
                nativeResamplerDestroy(resamplerHandle)
                resamplerHandle = 0L
            }
            resamplerInHz = 0
            resamplerOutHz = 0
        }
    }

    private inner class WindowStream(
        val windowing: MlWindowing,
        val pcmModelIds: List<String>,
        val stftGroups: List<Pair<MlStftParams, List<String>>>,
    ) {
        val ring = FloatRing()
        private var ringStartEpochSec = 0.0
        private var ringHasTimeline = false
        private val stftHandles = HashMap<MlStftParams, StftHandle>()

        fun ensureTimeline(observedAtEpochSec: Double) {
            if (!ringHasTimeline) {
                ringStartEpochSec = observedAtEpochSec
                ringHasTimeline = true
            }
        }

        fun clear() {
            ring.clear()
            ringHasTimeline = false
            for (h in stftHandles.values) h.destroy()
            stftHandles.clear()
        }

        fun emitFullWindows(
            ctx: EmissionContext,
            workers: Map<String, ModelWorker>,
        ) {
            val w = ctx.windowing
            val resampleMs = ctx.resampleMs
            while (ring.size >= w.windowSamples) {
                if (ctx.jobEpoch != epoch.get()) return
                val window = FloatArray(w.windowSamples)
                ring.copyWindow(window)
                val observed = if (ringHasTimeline) ringStartEpochSec else 0.0
                dispatchWindow(window, ctx, observed, resampleMs, workers)
                ring.discard(ctx.hop)
                if (ringHasTimeline) {
                    ringStartEpochSec += ctx.hop.toDouble() / w.modelSampleRateHz
                }
            }
        }

        fun emitEndOfStream(
            ctx: EmissionContext,
            workers: Map<String, ModelWorker>,
        ) {
            if (ring.size <= 0) return
            if (ctx.jobEpoch != epoch.get()) return
            emitFullWindows(ctx, workers)
            if (ring.size <= 0 || ctx.jobEpoch != epoch.get()) return
            if (ring.size >= ctx.windowing.windowSamples) return

            ring.appendZeros(ctx.windowing.windowSamples - ring.size)
            val window = FloatArray(ctx.windowing.windowSamples)
            ring.copyWindow(window)
            val observed = if (ringHasTimeline) ringStartEpochSec else 0.0
            dispatchWindow(window, ctx, observed, ctx.resampleMs, workers)
            ring.clear()
            ringHasTimeline = false
        }

        private fun dispatchWindow(
            window: FloatArray,
            ctx: EmissionContext,
            observedAtEpochSec: Double,
            resampleMs: Long,
            workers: Map<String, ModelWorker>,
        ) {
            for (modelId in pcmModelIds) {
                val worker = workers[modelId] ?: continue
                offerInfer(
                    worker,
                    MlModelInput.PcmWindow(window.copyOf()),
                    ctx,
                    observedAtEpochSec,
                    resampleMs,
                )
            }
            for ((stft, modelIds) in stftGroups) {
                if (modelIds.isEmpty()) continue
                val spectrogram = ensureStft(stft)?.process(window)
                if (spectrogram == null) {
                    Timber.e(
                        "MlProcessor: STFT failed fft=${stft.fftSize} hop=${stft.hopSamples}"
                    )
                    continue
                }
                val nFrames = stft.nFrames(windowing.windowSamples)
                val input = stft.dropBinsBelow(spectrogram, nFrames)
                val keptBins = stft.keptFreqBins()
                for ((index, modelId) in modelIds.withIndex()) {
                    val worker = workers[modelId] ?: continue
                    val values =
                        if (index == modelIds.lastIndex) input
                        else input.copyOf()
                    offerInfer(
                        worker,
                        MlModelInput.Spectrogram(values, nFrames, keptBins),
                        ctx,
                        observedAtEpochSec,
                        resampleMs,
                    )
                }
            }
        }

        private fun ensureStft(params: MlStftParams): StftHandle? {
            stftHandles[params]?.let { return it }
            val handle = nativeStftCreate(
                params.fftSize,
                params.hopSamples,
                windowing.windowSamples,
            )
            if (handle == 0L) {
                Timber.e("MlProcessor: failed to create STFT $params")
                return null
            }
            return StftHandle(params, windowing.windowSamples, handle).also {
                stftHandles[params] = it
            }
        }

        private fun offerInfer(
            worker: ModelWorker,
            input: MlModelInput,
            ctx: EmissionContext,
            observedAtEpochSec: Double,
            resampleMs: Long,
        ) {
            if (ctx.jobEpoch != epoch.get()) return

            if (overflowPolicy == MlOverflowPolicy.DropIfFull) {
                while (true) {
                    val pending = worker.inferPendingCount.get()
                    if (pending >= MAX_LIVE_INFER_QUEUED) {
                        Timber.w(
                            "MlProcessor: infer queue depth $pending for " +
                                "${worker.descriptor.id}: window from buffer #${ctx.bufferId} dropped"
                        )
                        return
                    }
                    if (worker.inferPendingCount.compareAndSet(pending, pending + 1)) break
                }
            } else {
                worker.inferPendingCount.incrementAndGet()
            }

            val inferQueuedAtMs = SystemClock.elapsedRealtime()
            val inferDepth = worker.inferPendingCount.get()
            markWorkStarted()
            val job = InferItem.Job(
                input = input,
                bufferId = ctx.bufferId,
                epoch = ctx.jobEpoch,
                observedAtEpochSec = observedAtEpochSec,
                queuedAtElapsedRealtimeMs = ctx.queuedAtMs,
                queueWaitMs = ctx.queueWaitMs,
                resampleMs = resampleMs,
                inferQueuedAtElapsedRealtimeMs = inferQueuedAtMs,
                inferQueueLengthWhenQueued = inferDepth,
            )
            val offered = if (overflowPolicy == MlOverflowPolicy.QueueAll) {
                // May block preprocess until this model catches up — viewer must not drop.
                try {
                    worker.inferQueue.put(job)
                    true
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            } else {
                worker.inferQueue.offer(job)
            }
            if (!offered) {
                worker.inferPendingCount.decrementAndGet()
                markWorkFinished()
                Timber.w(
                    "MlProcessor: infer queue rejected window for ${worker.descriptor.id} " +
                        "from buffer #${ctx.bufferId}"
                )
            }
        }
    }

    private inner class ModelWorker(
        val descriptor: MlModelDescriptor,
    ) {
        val inferQueue = LinkedBlockingQueue<InferItem>()
        val inferPendingCount = AtomicInteger(0)
        /** Last [geoEpoch] applied on this worker's infer thread. */
        var appliedGeoEpoch: Long = -1L
        @Volatile
        var model: MlModelBase? = null
        var thread: Thread? = null

        /**
         * Open the model on the infer thread only. Geo is applied via
         * [syncGeoOnInferThread], not here from other threads.
         */
        fun ensureModel(): MlModelBase? {
            model?.let { return it }
            return try {
                MlCatalog.ensureInitialized(appContext)
                MlCatalog.openModel(appContext.assets, descriptor.id).also { opened ->
                    model = opened
                    Timber.i(
                        "MlProcessor: loaded Auto Id model ${opened.id} " +
                            "(${opened.sampleRateHz} Hz, ${opened.windowSamples} samples" +
                            "${if (opened.descriptor.stft != null) ", stft" else ""})"
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "MlProcessor: failed to load Auto Id model ${descriptor.id}")
                null
            }
        }

        /** Infer-thread only. */
        fun closeModel() {
            model?.close()
            model = null
            appliedGeoEpoch = -1L
        }
    }

    companion object {
        const val MAX_LIVE_QUEUED = 10

        /**
         * Soft cap on queued infer jobs per model in live mode
         * ([MlOverflowPolicy.DropIfFull]). Viewer ([MlOverflowPolicy.QueueAll])
         * ignores this and may block preprocess on [BlockingQueue.put].
         */
        const val MAX_LIVE_INFER_QUEUED = 8

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

        @JvmStatic
        private external fun nativeStftCreate(
            fftSize: Int,
            hopSamples: Int,
            windowSamples: Int,
        ): Long

        @JvmStatic
        private external fun nativeStftProcess(
            handle: Long,
            pcmWindow: FloatArray,
        ): FloatArray?

        @JvmStatic
        private external fun nativeStftDestroy(handle: Long)
    }

    private val appContext = context.applicationContext
    private val rawQueue = LinkedBlockingQueue<RawItem>()

    private val pendingRawCount = AtomicInteger(0)
    private val bufferIdSeq = AtomicInteger(0)
    private val epoch = AtomicLong(0)

    private val activeWorkCount = AtomicInteger(0)
    private val mutableBusyFlow = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = mutableBusyFlow.asStateFlow()

    private val mutableBufferingFlow = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = mutableBufferingFlow.asStateFlow()

    @Volatile
    private var ringFillForUi: Int = 0

    @Volatile
    private var overflowPolicy: MlOverflowPolicy = MlOverflowPolicy.DropIfFull

    /** modelId → species-name language column index. */
    @Volatile
    private var labelLanguageByModel: Map<String, Int> = emptyMap()

    /** modelId → suppressed label keys. */
    @Volatile
    private var suppressedByModel: Map<String, Set<String>> = emptyMap()

    /**
     * BirdNET geo snapshot. Written from the client thread; read and applied to
     * [BirdNetModel] only on each model's infer thread (see [syncGeoOnInferThread]).
     */
    @Volatile
    private var geoSnapshot: BirdNetModel.GeoSnapshot? = null

    /** Bumped whenever [geoSnapshot] is set or cleared so infer threads re-sync. */
    private val geoEpoch = AtomicLong(0)

    @Volatile
    private var captureSampleRateHz: Int = 0

    @Volatile
    private var running = false

    private var preprocessThread: Thread? = null
    private val modelWorkers: Map<String, ModelWorker> =
        descriptors.associate { it.id to ModelWorker(it) }

    /** Smallest windowSamples among descriptors; used for buffering UI. */
    private val minWindowSamples: Int =
        descriptors.minOfOrNull { it.windowSamples } ?: Int.MAX_VALUE

    fun setOverflowPolicy(policy: MlOverflowPolicy) {
        overflowPolicy = policy
    }

    /** Set species-name language for one model (other models unchanged). */
    fun setLabelLanguage(modelId: String, languageIndex: Int) {
        labelLanguageByModel = labelLanguageByModel + (modelId to languageIndex)
    }

    /** Replace suppressions for [modelId] (other models unchanged). */
    fun setSuppressions(modelId: String, suppressions: Map<String, Boolean>) {
        val next = suppressedByModel.toMutableMap()
        next[modelId] = suppressions.filterValues { it }.keys
        suppressedByModel = next
    }

    /**
     * Publish a geo snapshot for BirdNET workers. First snapshot wins.
     * [BirdNetModel] is updated only on each infer thread via [syncGeoOnInferThread].
     */
    fun setGeoSnapshot(snapshot: BirdNetModel.GeoSnapshot?) {
        if (snapshot == null) return
        if (geoSnapshot != null) return
        geoSnapshot = snapshot
        geoEpoch.incrementAndGet()
    }

    /**
     * Clear geo snapshot/mask so a new location can be applied. Infer threads
     * observe the bump via [geoEpoch] and call [BirdNetModel.resetGeoFilter].
     */
    fun clearGeoSnapshot() {
        geoSnapshot = null
        geoEpoch.incrementAndGet()
    }

    /**
     * Infer-thread only: apply the latest [geoSnapshot] to [bird] when [geoEpoch]
     * has advanced since this worker last synced.
     */
    private fun syncGeoOnInferThread(worker: ModelWorker, bird: BirdNetModel) {
        val epochNow = geoEpoch.get()
        if (epochNow == worker.appliedGeoEpoch) return
        bird.resetGeoFilter()
        geoSnapshot?.let { bird.setGeoSnapshot(it) }
        worker.appliedGeoEpoch = epochNow
    }

    fun reset(sampleRateHz: Int) {
        require(sampleRateHz > 0) { "sampleRateHz must be > 0" }
        captureSampleRateHz = sampleRateHz
        clearQueue()
    }

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
        for (worker in modelWorkers.values) {
            var sawInferShutdown = false
            while (true) {
                val item = worker.inferQueue.poll() ?: break
                when (item) {
                    is InferItem.Job -> {
                        dropped++
                        worker.inferPendingCount.decrementAndGet()
                        markWorkFinished()
                    }
                    InferItem.Shutdown -> sawInferShutdown = true
                }
            }
            if (sawInferShutdown) {
                worker.inferQueue.offer(InferItem.Shutdown)
            }
        }
        rawQueue.offer(RawItem.ClearStream)
        if (sawRawShutdown) {
            rawQueue.offer(RawItem.Shutdown)
        }
        if (dropped > 0) {
            Timber.i("MlProcessor: cleared $dropped queued job(s) (epoch=$newEpoch)")
        }
        publishBuffering()
    }

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

    fun start(onResult: (MlResult) -> Unit) {
        check(!running) { "MlProcessor already started" }
        running = true
        for (worker in modelWorkers.values) {
            val t = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                inferLoop(worker, onResult)
            }, "MlInfer-${worker.descriptor.id}")
            worker.thread = t
            t.start()
        }
        val preprocess = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            preprocessLoop()
        }, "MlPreprocess")
        preprocessThread = preprocess
        preprocess.start()
    }

    fun close() {
        if (!running) return
        running = false
        rawQueue.offer(RawItem.Shutdown)
        val preprocess = preprocessThread
        try {
            preprocess?.join(5_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (preprocess?.isAlive == true) {
            Timber.w("MlProcessor: preprocess still alive after join; interrupting")
            preprocess.interrupt()
            try {
                preprocess.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (worker in modelWorkers.values) {
            val t = worker.thread
            try {
                t?.join(5_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (t?.isAlive == true) {
                Timber.w("MlProcessor: infer ${worker.descriptor.id} still alive; interrupting")
                t.interrupt()
                try {
                    t.join(5_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (t?.isAlive == true) {
                Timber.e(
                    "MlProcessor: infer ${worker.descriptor.id} did not exit; " +
                        "leaving model close to that thread"
                )
            }
            // Model is closed in inferLoop's finally — never from this thread.
            worker.thread = null
        }
        preprocessThread = null
        activeWorkCount.set(0)
        mutableBusyFlow.value = false
        mutableBufferingFlow.value = false
        ringFillForUi = 0
    }

    private fun preprocessLoop() {
        var rates: List<RateStream> = emptyList()
        var graphCaptureHz = 0
        try {
            while (true) {
                when (val item = rawQueue.take()) {
                    RawItem.Shutdown -> {
                        for (r in rates) r.clear()
                        for (worker in modelWorkers.values) {
                            worker.inferQueue.offer(InferItem.Shutdown)
                        }
                        break
                    }
                    RawItem.ClearStream -> {
                        for (r in rates) r.clear()
                    }
                    is RawItem.Audio -> {
                        if (graphCaptureHz != item.sampleRateHz) {
                            for (r in rates) r.clear()
                            rates = buildRateStreams(item.sampleRateHz)
                            graphCaptureHz = item.sampleRateHz
                        }
                        processAudio(rates, item)
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            for (worker in modelWorkers.values) {
                worker.inferQueue.offer(InferItem.Shutdown)
            }
        } finally {
            for (r in rates) r.clear()
        }
    }

    private fun buildRateStreams(captureSampleRateHz: Int): List<RateStream> {
        val spec = MlPipelineSpec.build(descriptors, captureSampleRateHz)
        val branches = if (spec.rateBranches.isEmpty()) {
            "(none)"
        } else {
            spec.rateBranches.joinToString("; ") { rate ->
                val models = rate.windowBranches.flatMap { w ->
                    w.pcmModels + w.stftGroups.flatMap { it.models }
                }
                val modelList = models.joinToString { "${it.id} (${it.displayName})" }
                "$captureSampleRateHz → ${rate.sampleRateHz} Hz: $modelList"
            }
        }
        Timber.i(
            "MlProcessor: pipeline rebuilt for $captureSampleRateHz Hz capture; " +
                "rate branches: $branches"
        )
        return spec.rateBranches.map { rate ->
            RateStream(
                modelRateHz = rate.sampleRateHz,
                windowBranches = rate.windowBranches.map { w ->
                    WindowStream(
                        windowing = w.windowing,
                        pcmModelIds = w.pcmModels.map { it.id },
                        stftGroups = w.stftGroups.map { g ->
                            g.stft to g.models.map { it.id }
                        },
                    )
                },
            )
        }
    }

    private fun processAudio(rates: List<RateStream>, item: RawItem.Audio) {
        pendingRawCount.decrementAndGet()
        val dequeuedAtMs = SystemClock.elapsedRealtime()
        val queueWaitMs = dequeuedAtMs - item.queuedAtElapsedRealtimeMs
        try {
            if (item.epoch != epoch.get()) return
            if (rates.isEmpty()) {
                if (descriptors.isNotEmpty()) {
                    Timber.i(
                        "MlProcessor: buffer #${item.bufferId} dropped " +
                            "(no models accept ${item.sampleRateHz} Hz)"
                    )
                }
                return
            }

            val ctxBase = EmissionContext(
                windowing = rates.first().windowBranches.first().windowing,
                bufferId = item.bufferId,
                jobEpoch = item.epoch,
                queuedAtMs = item.queuedAtElapsedRealtimeMs,
                queueWaitMs = queueWaitMs,
                resampleStartedAtMs = dequeuedAtMs,
            )

            for (rate in rates) {
                if (item.epoch != rate.streamEpoch) {
                    rate.clear()
                    rate.streamEpoch = item.epoch
                }
                if (!rate.ensureResampler(item.sampleRateHz, rate.modelRateHz)) {
                    continue
                }
                rate.appendResampledOrIdentity(
                    item.buffer,
                    item.sampleRateHz,
                    item.bufferId,
                    item.observedAtEpochSec,
                )
                rate.emitFullWindows(ctxBase)
                if (item.isLast) {
                    rate.flushResampler()
                    rate.emitEndOfStream(ctxBase)
                    rate.clear()
                } else {
                    rate.noteRings()
                }
            }
        } finally {
            markWorkFinished()
            publishBuffering()
        }
    }

    private fun inferLoop(worker: ModelWorker, onResult: (MlResult) -> Unit) {
        try {
            while (true) {
                when (val item = worker.inferQueue.take()) {
                    InferItem.Shutdown -> break
                    is InferItem.Job -> {
                        worker.inferPendingCount.decrementAndGet()
                        if (item.epoch != epoch.get()) {
                            markWorkFinished()
                            publishBuffering()
                            continue
                        }
                        try {
                            onResult(inferWindow(worker, item))
                        } finally {
                            markWorkFinished()
                            publishBuffering()
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // Own the LiteRT lifetime on this thread only (thread-safe close).
            worker.closeModel()
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
        val ringFill = ringFillForUi
        mutableBufferingFlow.value =
            pendingRawCount.get() > 0 ||
                (ringFill > 0 && ringFill < minWindowSamples)
    }

    private fun inferWindow(worker: ModelWorker, job: InferItem.Job): MlResult {
        fun completedResult(
            detections: List<MlDetection> = emptyList(),
        ): MlResult = MlResult(
            detections = detections,
            observedAtEpochSec = job.observedAtEpochSec,
            completedAtEpochSec = System.currentTimeMillis() / 1000.0,
            modelId = worker.descriptor.id,
        )

        val autoId = worker.ensureModel() ?: return completedResult()
        if (autoId is BirdNetModel) {
            syncGeoOnInferThread(worker, autoId)
        }
        val detectionThreshold = autoId.detectionThreshold
        val suppressed = suppressedByModel[worker.descriptor.id].orEmpty()

        return try {
            val inferStartedAtMs = SystemClock.elapsedRealtime()
            val scores = autoId.predict(job.input)
            val labelDiscard = autoId.labelDiscard
            val labelKeys = autoId.labelKeys
            var displayLabels: List<String>? = null
            val languageIndex = labelLanguageByModel[worker.descriptor.id] ?: 0
            val detections = ArrayList<MlDetection>()
            fun addDetection(
                index: Int,
                score: Float,
                fmaxeHz: Float? = null,
                timeSec: Double? = null,
            ) {
                if (index !in labelDiscard.indices ||
                    labelDiscard[index] ||
                    labelKeys[index] in suppressed
                ) {
                    return
                }
                val labels = displayLabels
                    ?: autoId.labelsFor(languageIndex).also { displayLabels = it }
                detections.add(
                    MlDetection(
                        label = autoId.detectionLabel(index, labels[index]),
                        confidence = score,
                        labelKey = labelKeys[index],
                        fmaxeHz = fmaxeHz,
                        timeSec = timeSec,
                    )
                )
            }
            val spectrogram = autoId as? SpectrogramModel
            if (spectrogram != null) {
                for (chirp in spectrogram.chirps(detectionThreshold)) {
                    addDetection(
                        chirp.binIndex,
                        chirp.presence,
                        fmaxeHz = chirp.fmaxeHz,
                        timeSec = chirp.timeSec,
                    )
                }
            } else {
                for (i in scores.indices) {
                    val score = scores[i]
                    if (score <= detectionThreshold) continue
                    addDetection(i, score)
                }
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
                    "MlProcessor[${worker.descriptor.id}]: buffer #${job.bufferId} " +
                        "detections: (none) ($timing)"
                )
            } else {
                val listed = detections.joinToString { d ->
                    "${d.label}=${"%.0f".format(d.confidence * 100)}%"
                }
                Timber.i(
                    "MlProcessor[${worker.descriptor.id}]: buffer #${job.bufferId} " +
                        "detections: $listed ($timing)"
                )
            }
            completedResult(detections)
        } catch (e: Exception) {
            val totalMs = SystemClock.elapsedRealtime() - job.queuedAtElapsedRealtimeMs
            Timber.e(
                e,
                "MlProcessor[${worker.descriptor.id}]: inference failed on buffer " +
                    "#${job.bufferId} (queue ${job.queueWaitMs} ms, resample ${job.resampleMs} ms, " +
                    "total $totalMs ms)"
            )
            completedResult()
        }
    }
}
