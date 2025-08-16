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

import org.batgizmo.app.HORange

abstract class AbstractStep {

    /**
     * Take any actions required for data to start flowing.
     */
    open fun start() {
    }

    /**
     * Call this method from a worker thread.
     *
     * Process a slice of data from the step input to output.
     *
     * A half open range for the slice is supplied by the caller, which is in terms
     * of the input data for the step.
     */
    abstract fun sliceRender(sliceRange: HORange, transformedEntryIndex: Int = 0)

    /**
     * Call this method from a worker thread.
     *
     * Fully recalculate/re-render this step, and propagate that to subsequent steps. Internally
     * this is typically achieved by looping over calls to sliceRender.
     */
    abstract fun fullRender()

    /**
     * Call this method from the UI thread.
     *
     * Cleanup handles and resources used by the instance.
     * Call this when we have finished with the pipeline.
     */
    abstract suspend fun shutdown()

    /**
     * Reset any step state.
     */
    abstract suspend fun resetState()
}