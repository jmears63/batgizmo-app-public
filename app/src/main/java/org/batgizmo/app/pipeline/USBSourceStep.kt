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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.batgizmo.app.BitmapHolder
import org.batgizmo.app.HORange
import org.batgizmo.app.LiveDataBridge
import org.batgizmo.app.LiveDataCopy
import org.batgizmo.app.UIModel
import timber.log.Timber

class USBSourceStep(
    private val pipeline: AbstractPipeline,
    private val scope: CoroutineScope,
    private val model: UIModel,
    nextStep: AbstractStep,
    rangedRawDataBuffer: AbstractPipeline.RangedShortDataBuffer,
    private val spectrogramBitmapHolder: BitmapHolder,
    private val amplitudeBitmapHolder: BitmapHolder
) : DataSourceStep(nextStep, rangedRawDataBuffer, true) {

    init {
        Timber.d("init called for USBSourceStep")
    }

    private val nativeUSB = NativeUSB()
    private var channelJob: Job? = null

    private fun createChannelJob(): Job {
        return scope.launch(context = Dispatchers.Default) {
            // Worker thread.

            try {
                val safeParams = getSafeParams()
                val calcs = safeParams.calcs
                val rawDataCapacity = rangedRawDataBuffer.buffer.size - AbstractPipeline.CANARY_ENTRIES

                // We need to populate raw data up to this index to be ready to submit the
                // next slice:
                var nextSliceEndIndexHO = calcs.rawSliceEntries

                // The index to the next entry in the raw data buffer to populate:
                var rawDataBufferOffset = 0

                // The index to the next transformed data buffer entry to be written, which is also
                // the count of values we have from the start of the buffer:
                var transformedDataBufferOffset = 0

                var totalCopiedCount = 0

                // The for statement will check if a cancel is pending, and if so pass control
                // to the finally block for cleanup and to prevent this job becoming a zombie:
                for (bufferDescriptor in LiveDataBridge.renderingChannel) {
                    if (rawDataCapacity > 0) {
                        // Copy live data into rawDataBuffer with wrap:
                        val copiedCount = LiveDataCopy.copyIntoRingBuffer(
                            bufferDescriptor,
                            rangedRawDataBuffer.buffer,
                            rawDataBufferOffset,
                            rawDataCapacity,
                            nativeUSB
                        )
                        rawDataBufferOffset += copiedCount

                        // Update the raw data buffer with the range of data that has actually been populated. Limit
                        // the range to the visible range which may be less than the total raw buffer range:
                        totalCopiedCount += copiedCount
                        val visibleRawMax = (rawDataCapacity * model.timeVisibleRangeFlow.value.endInclusive).toInt()
                        totalCopiedCount = minOf(totalCopiedCount, visibleRawMax)
                        rangedRawDataBuffer.update(HORange(0, totalCopiedCount))

                        // Check the canary value:
                        require(
                            this@USBSourceStep.rangedRawDataBuffer.buffer[rawDataCapacity]
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
                             * We do the sliceRender call back in through the front door so that the pipeline
                             * is locked versus any other pipeline requests, such as from the UI. That is OK
                             * as we aren't holding any other locks at this point.
                             */

                            // val rawDataSize = rangedRawDataBuffer.buffer.size - AbstractPipeline.CANARY_ENTRIES

                            /*
                             * BEWARE: both rawDataBufferOffset and nextSliceEndIndexHO can be beyond the end of the raw data buffer at this point.
                             * So, we clamp to the valid buffer data range:
                             */
                            val bufferEndReached = nextSliceEndIndexHO >= rawDataCapacity
                            val sliceDataRange = HORange(
                                maxOf(nextSliceEndIndexHO - calcs.rawSliceEntries,0),
                                minOf(nextSliceEndIndexHO, rawDataCapacity)
                            )

                            pipeline.sliceRender(
                                sliceDataRange,
                                transformedDataBufferOffset)

                            // Render the slices to UI as we go:
                            spectrogramBitmapHolder.signalUpdate()
                            amplitudeBitmapHolder.signalUpdate()

                            // Did we overlap the end of the visible region?
                            val visibleBufferOffsetLimit =
                                (rawDataCapacity * model.timeVisibleRangeFlow.value.endInclusive)
                                    .toInt()
                                    .coerceIn(
                                        calcs.rawSliceEntries,
                                        rawDataCapacity
                                    )
                            val visibleRegionOverflow =
                                nextSliceEndIndexHO > visibleBufferOffsetLimit

                            // Do we need to wrap to the start of the buffer:
                            if (bufferEndReached || visibleRegionOverflow) {
                                // Simplification - just discard surplus data at the end of the raw buffer
                                // and reset. No one can tell if the start of the visible spectrogram exactly
                                // picks up where it left off at the end.

                                rawDataBufferOffset = 0
                                nextSliceEndIndexHO = calcs.rawSliceEntries
                                transformedDataBufferOffset = 0
                            }
                            else
                            {
                                // Increment allowing for slice overlap so that the slices result in transformed
                                // data at equal intervals. Beware that nextSliceEndIndexHO can be off the end
                                // of the raw data buffer - subsequent code needs to handle that.
                                nextSliceEndIndexHO += (calcs.rawSliceEntries - calcs.rawSliceOverlap)
                                transformedDataBufferOffset += calcs.sliceTransformedTimeBucketCount
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

        rangedRawDataBuffer.assignedRange = HORange.EMPTY

        // The job that handles new data contains data that we need to reset:
        channelJob?.cancelAndJoin()
        channelJob = createChannelJob()
    }

    override fun sliceRender(sliceRange: HORange, transformedEntryIndex: Int) {

        // Timber.d("push sliceRange = $sliceRange")
        getSafeParams()

        // Pass on the slice range that was actually read:
        if (sliceRange.exclusiveEnd - sliceRange.start > 0)
            nextStep.sliceRender(sliceRange, transformedEntryIndex)
    }
}