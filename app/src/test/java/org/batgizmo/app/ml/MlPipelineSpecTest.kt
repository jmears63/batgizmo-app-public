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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlPipelineSpecTest {

    private fun descriptor(
        id: String,
        sampleRateHz: Int,
        windowSamples: Int = 144000,
        overlapFraction: Float = 0.25f,
        minSampleRateHz: Int = sampleRateHz / 2,
        stft: MlStftParams? = null,
    ) = MlModelDescriptor(
        id = id,
        displayName = id,
        familyId = id.substringBefore('/'),
        familyDisplayName = id,
        kind = MlModelKind.SINGLE_TFLITE,
        sampleRateHz = sampleRateHz,
        windowSamples = windowSamples,
        overlapFraction = overlapFraction,
        minSampleRateHz = minSampleRateHz,
        detectionThreshold = 0.5f,
        languages = listOf("en"),
        labelCatalog = listOf(
            LabelCatalogEntry(
                texts = listOf("a", "A"),
                discard = false,
                disableByDefault = false,
            )
        ),
        resourcePaths = MlResourcePaths(
            root = ModelResourceRoot.Assets("ml/x"),
            embeddingsRelativeOrNull = null,
            classifierOrModel = "model.tflite",
            labels = "labels.json",
        ),
        stft = stft,
    )

    @Test
    fun build_sharesResampleAndWindow_whenMetadataMatches() {
        val a = descriptor("fam/a", sampleRateHz = 256_000)
        val b = descriptor("fam/b", sampleRateHz = 256_000)
        val spec = MlPipelineSpec.build(listOf(a, b), captureSampleRateHz = 384_000)
        assertEquals(1, spec.rateBranches.size)
        assertEquals(1, spec.rateBranches[0].windowBranches.size)
        val window = spec.rateBranches[0].windowBranches[0]
        assertEquals(2, window.pcmModels.size)
        assertTrue(window.stftGroups.isEmpty())
    }

    @Test
    fun build_splitsRates_andAddsStftNode() {
        val pcm = descriptor("pcm/a", sampleRateHz = 256_000)
        val stftModel = descriptor(
            "cnn/a",
            sampleRateHz = 256_000,
            stft = MlStftParams(fftSize = 1024, hopSamples = 256),
        )
        val bird = descriptor("bird/a", sampleRateHz = 48_000, windowSamples = 144_000)
        val spec = MlPipelineSpec.build(
            listOf(pcm, stftModel, bird),
            captureSampleRateHz = 384_000,
        )
        assertEquals(2, spec.rateBranches.size)
        val high = spec.rateBranches.first { it.sampleRateHz == 256_000 }
        assertEquals(1, high.windowBranches.size)
        val w = high.windowBranches[0]
        assertEquals(listOf(pcm.id), w.pcmModels.map { it.id })
        assertEquals(1, w.stftGroups.size)
        assertEquals(listOf(stftModel.id), w.stftGroups[0].models.map { it.id })
    }

    @Test
    fun build_omitsModelsBelowMinCaptureRate() {
        val highMin = descriptor(
            "bat/a",
            sampleRateHz = 256_000,
            minSampleRateHz = 196_000,
        )
        val lowMin = descriptor(
            "bird/a",
            sampleRateHz = 48_000,
            minSampleRateHz = 36_000,
        )
        val spec = MlPipelineSpec.build(
            listOf(highMin, lowMin),
            captureSampleRateHz = 48_000,
        )
        assertEquals(1, spec.rateBranches.size)
        assertEquals(48_000, spec.rateBranches[0].sampleRateHz)
        assertEquals(listOf(lowMin.id), spec.allModels().map { it.id })
    }
}
