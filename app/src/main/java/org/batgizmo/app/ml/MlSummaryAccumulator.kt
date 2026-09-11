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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** Live vs file-viewer summary policy. Set via [MlSummaryAccumulator.clear]. */
enum class MlSummaryMode {
    /** Most-recent-first; only the top [MlSummaryAccumulator.MAX_SUMMARY_ENTRIES] are kept. */
    Live,

    /** Highest-confidence-first; only the top [MlSummaryAccumulator.MAX_SUMMARY_ENTRIES] are kept. */
    Viewer,
}

/**
 * Accumulates per-chunk [MlResult]s into a running UI summary: one entry per
 * label, keeping the highest confidence seen so far, with [lastSeenAtEpochSec]
 * for age styling.
 *
 * Sort / retention depend on [MlSummaryMode] ([mode] / [clear]):
 * - live = most recently seen first
 * - viewer = highest confidence first
 * Both modes keep at most [MAX_SUMMARY_ENTRIES] labels in state.
 *
 * Owns the result queue and merge loop. Callers [submitResult] from inference
 * threads and [clear] on session/page boundaries (passing [MlSummaryMode]).
 */
class MlSummaryAccumulator {
    private data class Entry(
        val label: String,
        val confidence: Float,
        val lastSeenAtEpochSec: Double,
    )

    private val resultChannel = Channel<MlResult>(Channel.UNLIMITED)

    private val entries = LinkedHashMap<String, Entry>()

    private val mutableSummary = MutableStateFlow<List<MlSummaryEntry>>(emptyList())

    /** Running summary for the Auto Id overlay (already capped and ordered). */
    val summary: StateFlow<List<MlSummaryEntry>> = mutableSummary.asStateFlow()

    private val mutableMode = MutableStateFlow(MlSummaryMode.Live)

    /** Current live/viewer policy; updated by [clear]. */
    val mode: StateFlow<MlSummaryMode> = mutableMode.asStateFlow()

    private var mergeJob: Job? = null

    /**
     * Start merging results into [summary]. Idempotent if already started.
     */
    fun start(scope: CoroutineScope) {
        if (mergeJob?.isActive == true) return
        mergeJob = scope.launch(CoroutineName("mlSummaryMerge")) {
            for (result in resultChannel) {
                applyResult(result)
            }
        }
    }

    /**
     * Queue a chunk result for merging. Non-blocking; never drops while open.
     * Safe to call from the infer worker.
     */
    fun submitResult(result: MlResult) {
        try {
            resultChannel.trySend(result)
        } catch (_: ClosedSendChannelException) {
            // Shutting down.
        }
    }

    /**
     * Drop any results not yet merged, reset [summary], and set [mode] for
     * subsequent sorting / retention.
     */
    fun clear(mode: MlSummaryMode) {
        mutableMode.value = mode
        while (resultChannel.tryReceive().isSuccess) {
            // discard pending results
        }
        entries.clear()
        publishSummary()
    }

    /** Stop accepting results (call when the owning ViewModel is cleared). */
    fun close() {
        resultChannel.close()
        mergeJob = null
        entries.clear()
        publishSummary()
    }

    private fun applyResult(result: MlResult) {
        val at = result.observedAtEpochSec
        for (detection in result.detections) {
            val previous = entries[detection.label]
            val confidence =
                if (previous == null || detection.confidence > previous.confidence) {
                    detection.confidence
                } else {
                    previous.confidence
                }
            // Refresh last-seen whenever the label appears again.
            entries[detection.label] = Entry(detection.label, confidence, at)
        }
        publishSummary()
    }

    private fun publishSummary() {
        val sorted = when (mutableMode.value) {
            MlSummaryMode.Live ->
                entries.values.sortedByDescending { it.lastSeenAtEpochSec }
            MlSummaryMode.Viewer ->
                entries.values.sortedByDescending { it.confidence }
        }
        val keep = sorted.take(MAX_SUMMARY_ENTRIES)
        for (dropped in sorted.drop(MAX_SUMMARY_ENTRIES)) {
            Timber.i(
                "MlSummary: dropped \"%s\" (%.0f%%) from %s list",
                dropped.label,
                dropped.confidence * 100f,
                mutableMode.value,
            )
            entries.remove(dropped.label)
        }
        mutableSummary.value = keep.map {
            MlSummaryEntry(it.label, it.confidence, it.lastSeenAtEpochSec)
        }
    }

    companion object {
        const val MAX_SUMMARY_ENTRIES = 5
    }
}
