/*
 * Copyright (c) 2025 John Mears
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

package org.batgizmo.app.pipeline

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.FloatRange
import org.batgizmo.app.UIModel

/**
 * Public methods on this class are intended to be invoked from multiple threads (UI and workers)
 * and are therefore implemented in thread-safe way.
 */
class LiveUSBPipeline(
    scope: CoroutineScope,
    context: Context,
    model: UIModel,
    spectrogramBitmapHolder: BitmapHolder,
    amplitudeBitmapHolder: BitmapHolder,
    mutableXAxisRangeFlow: MutableStateFlow<FloatRange>,
    mutableYAxisRangeFlow: MutableStateFlow<FloatRange>,
    mutableDetailsTextFlow: MutableStateFlow<String?>,
    sampleRate: Int,
    sampleCount: Int,
    onTrigger: () ->Unit
) : AbstractPipeline(
    scope,
    context,
    model,
    spectrogramBitmapHolder,
    amplitudeBitmapHolder,
    mutableXAxisRangeFlow,
    mutableYAxisRangeFlow,
    mutableDetailsTextFlow,
    sampleRate,
    sampleCount,
    preserveRawDataBuffer = true,    // So we can rebuild the pipeline without loss of data.,
    onTrigger
) {
    companion object {
        const val DEFAULTLIVETIMESPAN_S = 3f
    }

    override fun createDataSourceStep(
        pipeline: AbstractPipeline,
        transformStep: TransformStep,
        rangedRawDataBuffer: RangedRawDataBuffer
    ): DataSourceStep {
        return USBSourceStep(
            pipeline,
            scope,
            model,
            transformStep,
            rangedRawDataBuffer,
            spectrogramBitmapHolder,
            amplitudeBitmapHolder
        )
    }
}