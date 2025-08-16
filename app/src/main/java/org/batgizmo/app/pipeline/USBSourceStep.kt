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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.HORange
import org.batgizmo.app.LiveDataBridge
import org.batgizmo.app.UIModel
import uk.org.gimell.batgimzoapp.BuildConfig

class USBSourceStep(
    private val pipeline: AbstractPipeline,
    private val scope: CoroutineScope,
    private val model: UIModel,
    nextStep: AbstractStep,
    rangedRawDataBuffer: AbstractPipeline.RangedRawDataBuffer,
    private val spectrogramBitmapHolder: BitmapHolder,
    private val amplitudeBitmapHolder: BitmapHolder
) : DataSourceStep(nextStep, rangedRawDataBuffer) {

    private val logTag = this::class.simpleName

    init {
        if (BuildConfig.DEBUG)
            Log.d(logTag, "init called for USBSourceStep")
    }

    private val nativeUSB = NativeUSB()
    private var channelJob: Job? = null

    private fun createChannelJob(): Job {
        return scope.launch(context = Dispatchers.Default) {
            // Worker thread.

            try {
                val safeParams = getSafeParams()
                val calcs = safeParams.calcs
                val rawDataSize = rangedRawDataBuffer.buffer.size - AbstractPipeline.CANARY_ENTRIES

                // We need to populate raw data up to this index to be ready to submit the
                // next slice:
                var nextSliceEndIndexHO = calcs.rawSliceEntries

                // The index to the next entry in the raw data buffer to populate:
                var rawDataBufferOffset = 0

                // The index to the next transformed data buffer entry to be written, which is also
                // the count of values we have from the start of the buffer:
                var transformedDataBufferOffset = 0

                // The for statement will check if a cancel is pending, and if so pass control
                // to the finally block for cleanup and to prevent this job becoming a zombie:
                for (bufferDescriptor in LiveDataBridge.renderingChannel) {
                    if (rawDataSize > 0) {
                        // Copy the native data into rawDataBuffer with wrap:
                        val copiedCount = nativeUSB.copyURBBufferData(
                            bufferDescriptor.nativeAddress,
                            bufferDescriptor.samples,
                            rangedRawDataBuffer.buffer,
                            rawDataBufferOffset,
                            rawDataSize
                        )
                        rawDataBufferOffset += copiedCount

                        // Check the canary value:
                        require(
                            this@USBSourceStep.rangedRawDataBuffer.buffer[rawDataSize]
                                    == AbstractPipeline.CANARY_VALUE
                        )

                        /*
                        Here's what we need to do. Data is arriving in native buffers, we know how much
                        arrives, and it is appended to the raw data buffer in a circular way.

                        We need to pass it to the pipeline in exact slices, which is a certain number
                        of data samples starting from a slice starting offset. That means we have
                        to track the slice we last sent, detect when we have enough data to send the next
                        slice, and do so. And handle the wrapping case.

                        Slices are sized to be an exact number of strides, as defined by the FFT window
                        size and overlap. The raw data range for a slice has to overlap so that the first
                        transformed value in a slice is corresponds to one stride on from the last
                        one in the previous slice. This procedure keeps calculations simple downstream.

                        Salient values are:
                            CalculatedParams.rawSliceEntries        basic slice size in raw points, but...
                            CalculatedParams.rawSliceOverlap
                            sliceTransformedTimeBucketCount         equivalent number of transformed time points

                        So the raw data index starts at zero and advances by (rawSliceEntries - rawSliceOverlap).

                        Any data left over after the last full slice is discarded - no fractional slices.
                    */

                        // Loop while there is enough data buffered to fill a slice:
                        while (rawDataBufferOffset >= nextSliceEndIndexHO) {

                            /*
                         * We have enough data to submit a slice to the pipeline.
                         *
                         * Beware that we are executing the pipeline slice calculation asynchronously here,
                         * without any locking that would prevent contention with other pipeline execution
                         * resulting from initial loading or the UI. Higher level application logic prevents
                         * this happening. But perhaps this should be more rigorous, and invoke the slice
                         * execution via the owning pipeline object, which would grab the pipeline mutex.
                         *
                         * We do the sliceRender call back in through the front door so that the pipeline
                         * is locked versus any other pipeline requests, such as from the UI. That is OK
                         * as we aren't holding any other locks at this point.
                         */

                            // Have the pipeline process the new slice:
                            val sliceDataRange = HORange(
                                nextSliceEndIndexHO - calcs.rawSliceEntries,
                                nextSliceEndIndexHO
                            )

                            // Keep track of the contiguous range raw data that we have populated:
                            val dar = rangedRawDataBuffer.assignedRange
                            if (dar == null) {
                                // This is the first slice we've seen:
                                rangedRawDataBuffer.assignedRange = sliceDataRange
                            } else {
                                // Extend the existing range to include the current range:
                                rangedRawDataBuffer.assignedRange = HORange(
                                    minOf(dar.first, sliceDataRange.first),
                                    maxOf(dar.second, sliceDataRange.second)
                                )
                            }
                            // Log.d(logTag, "JM: new raw data _dataAssignedRange = ${rangedRawDataBuffer.assignedRange}")

                            pipeline.sliceRender(sliceDataRange, transformedDataBufferOffset)

                            // Render the slices to UI as we go:
                            spectrogramBitmapHolder.signalUpdate()
                            amplitudeBitmapHolder.signalUpdate()

                            // Did we overlap the end of the visible region?
                            val visibleBufferOffsetLimit =
                                (rangedRawDataBuffer.buffer.size * model.timeVisibleRangeFlow.value.endInclusive)
                                    .toInt()
                                    .coerceIn(
                                        calcs.rawSliceEntries,
                                        rangedRawDataBuffer.buffer.size
                                    )
                            val visibleRegionOverflow =
                                nextSliceEndIndexHO > visibleBufferOffsetLimit

                            // Increment allowing for slice overlap so that the slices result in transformed
                            // data at equal intervals:
                            nextSliceEndIndexHO += (calcs.rawSliceEntries - calcs.rawSliceOverlap)
                            transformedDataBufferOffset += calcs.sliceTransformedTimeBucketCount

                            /*
                            Wrap if we need to. There are two cases that need a wrap:
                            * The slice we just rendered overlaps the end of the visible region,
                            * OR, the next slice would overflow the end of the raw buffer.
                            Actually the second shouldn't arise, but we check for paranoia reasons.
                         */

                            // Would the next slice worth of data overlap the end of the visible region?
                            if (visibleRegionOverflow) {
                                // Simplification - just discard surplus data at the end of the raw buffer
                                // and reset. No one can tell if the start of the visible spectrogram exactly
                                // picks up where it left off at the end.

                                rawDataBufferOffset = 0
                                nextSliceEndIndexHO = calcs.rawSliceEntries
                                transformedDataBufferOffset = 0
                            }
                        }
                    }
                }
            } finally {
                // We get here when the loop is cancelled on shutdown.
            }
        }
    }

    override fun start() {
        channelJob?.cancel()        // Paranoia.
        channelJob = createChannelJob()
    }

    /**
     * Make sure underlying handles and resources are closed:
     */
    override suspend fun shutdown() {

        // If we don't do this, it will continue for ever, zombie like.
        // Signal to the job to finish and wait for it to avoid
        // async native layer access to data that is about to be garbage
        // collected:
        channelJob?.cancelAndJoin()
        channelJob = null
    }

    override suspend fun resetState() {

        rangedRawDataBuffer.assignedRange = null

        // The job that handles new data contains data that we need to reset:
        channelJob?.cancelAndJoin()
        channelJob = createChannelJob()

    }

    override fun sliceRender(sliceRange: HORange, transformedEntryIndex: Int) {

        // Log.d(logTag, "push sliceRange = $sliceRange")
        getSafeParams()

        // Pass on the slice range that was actually read:
        if (sliceRange.second - sliceRange.first > 0)
            nextStep.sliceRender(sliceRange, transformedEntryIndex)
    }
}