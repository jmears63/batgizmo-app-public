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
 * Deduplicated preprocess graph implied by a set of [MlModelDescriptor]s.
 *
 * Built dynamically: only rates / window configs / STFT configs that at least
 * one active model needs are present. Shared keys avoid repeating r8brain /
 * window / STFT work when several models agree on metadata.
 */
data class MlPipelineSpec(
    val rateBranches: List<RateBranch>,
) {
    data class RateBranch(
        val sampleRateHz: Int,
        val windowBranches: List<WindowBranch>,
    )

    data class WindowBranch(
        val windowing: MlWindowing,
        /** Models that take raw PCM windows (no STFT). */
        val pcmModels: List<MlModelDescriptor>,
        /** STFT params → models that share that spectrogram. */
        val stftGroups: List<StftGroup>,
    ) {
        val hasConsumers: Boolean
            get() = pcmModels.isNotEmpty() || stftGroups.any { it.models.isNotEmpty() }
    }

    data class StftGroup(
        val stft: MlStftParams,
        val models: List<MlModelDescriptor>,
    )

    /** All model descriptors that appear as leaves in this graph. */
    fun allModels(): List<MlModelDescriptor> =
        rateBranches.flatMap { rate ->
            rate.windowBranches.flatMap { w ->
                w.pcmModels + w.stftGroups.flatMap { it.models }
            }
        }

    companion object {
        /**
         * Build a graph for [descriptors] that can accept [captureSampleRateHz].
         * Models below their [MlModelDescriptor.minSampleRateHz] are omitted.
         */
        fun build(
            descriptors: List<MlModelDescriptor>,
            captureSampleRateHz: Int,
        ): MlPipelineSpec {
            require(captureSampleRateHz > 0) { "captureSampleRateHz must be > 0" }
            val eligible = descriptors.filter { captureSampleRateHz >= it.minSampleRateHz }
            if (eligible.isEmpty()) return MlPipelineSpec(emptyList())

            val byRate = eligible.groupBy { it.sampleRateHz }
            val rateBranches = byRate.entries
                .sortedBy { it.key }
                .map { (rateHz, atRate) ->
                    val byWindow = atRate.groupBy {
                        Triple(it.sampleRateHz, it.windowSamples, it.overlapFraction)
                    }
                    val windowBranches = byWindow.entries.map { (_, atWindow) ->
                        val first = atWindow.first()
                        val windowing = MlWindowing.from(first)
                        val pcm = atWindow.filter { it.stft == null }
                        val stftGroups = atWindow
                            .mapNotNull { d ->
                                d.stft?.let { stft ->
                                    // PCEN needs unnormalized |STFT|; sharing a
                                    // log-power STFT would force a reverse step.
                                    val scale =
                                        if (d.pcen) MlStftScale.UnnormalizedMagnitude
                                        else MlStftScale.LogPower
                                    stft.copy(scale = scale) to d
                                }
                            }
                            .groupBy({ it.first }, { it.second })
                            .entries
                            .map { (stft, models) -> StftGroup(stft, models) }
                        WindowBranch(
                            windowing = windowing,
                            pcmModels = pcm,
                            stftGroups = stftGroups,
                        )
                    }.filter { it.hasConsumers }
                    RateBranch(sampleRateHz = rateHz, windowBranches = windowBranches)
                }
                .filter { it.windowBranches.isNotEmpty() }

            return MlPipelineSpec(rateBranches)
        }
    }
}
