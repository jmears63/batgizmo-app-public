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

package org.batgizmo.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.batgizmo.app.pipeline.LiveConnectResult
import org.batgizmo.app.pipeline.NativeUSB
import timber.log.Timber
import uk.org.gimell.batgimzoapp.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.round

class FileWriter(
    private val scope: CoroutineScope,
    private val context: Context,
    private val model: UIModel,
    private val locationFlow: StateFlow<Location?>,
    private val liveConnectResult: LiveConnectResult,
    private val sampleRate: Int,
    private val signalCurrentlyWriting: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        /** Top-level public folder (under Documents) that recordings are saved into. No slashes. */
        const val PUBLIC_FOLDER_NAME = "BatGizmo"

        public fun prettyFloat3Dps(value: Float) : String {
            return "%.3f".format(value).trimEnd('0').trimEnd('.')
        }
    }

    enum class TriggerType(val value: Int, val str: String) {
        OFF(0, "OFF"),
        AUTO(1, "Auto"),
        MANUAL(2, "Manual"),
        CONTINUATION(100, "Continuation")
    }

    data class TriggerConfig(
        val triggerType: TriggerType = TriggerType.OFF
    )

    private enum class State(val value: Int) {
        START_STATE(0),
        AUTO_TRIGGER_STATE(1),
        MANUAL_TRIGGER_STATE(2)
    }

    private data class WavFileInfo(
        val fileNameBase: String,       // Excludes .wav, we will add that later.
        val folderName: String
    )

    private val rawDataFileName = "filewriter.raw"      // Temporary data storage.
    private val publicFolderName = PUBLIC_FOLDER_NAME

    private var rawFile: File? = null
    private var rawStream: FileOutputStream? = null
    private var wavFileInfo: WavFileInfo? = null

    private var channelJob: Job? = null

    // private val bufferLengthS = 1f

    /**
     * The maximum chunk length we will write to file.
     * A bit arbitrary - big enough to get batching efficiency.
     */
    private val maxFileWriteChunkEntries = 9600


    /**
     * The maximum size of a any file we write.
     */
    private var maxFileEntries = 0

    /**
     * The number of entries to include before the trigger when writing to
     * file, if they are available.
     */
    private var preTriggerEntries = 0

    /**
     * The number of entries to write to file following a trigger.
     */
    private var postTriggerEntries = 0


    /**
     * A buffer to use to hold raw data waiting to be written to file.
     * Allow some padding so that there is time to open the file etc after
     * a trigger, without losing any data.
     */
    private val bufferPaddingTimeMs = 1000

    /**
     * Compute the ring buffer size needed to hold the given pre-trigger duration plus padding.
     * The padding gives time to open the file after a trigger without losing data, and also acts
     * as a floor so the buffer is never pathologically small when little or no pre-trigger is set.
     *
     * The multiplication is done in Long to avoid Int overflow: at high sample rates (e.g. 384 kHz)
     * and longer pre-trigger times, sampleRate * totalMs exceeds Int.MAX_VALUE even though the final
     * entry count (after dividing by 1000) comfortably fits in an Int.
     */
    private fun computeBufferSizeEntries(preTriggerTimeMs: Int): Int =
        (sampleRate.toLong() * (maxOf(preTriggerTimeMs, 0) + bufferPaddingTimeMs) / 1000).toInt()

    /**
     * The ring buffer holding recent live data, sized for the currently configured pre-trigger
     * time. It is reallocated by the data-reading coroutine (the only writer of buffer contents)
     * when that setting changes, while no file is being written. See [maybeResizeBuffer].
     */
    private var bufferSizeEntries = computeBufferSizeEntries(model.settings.preTriggerTimeMs)
    private var buffer = ShortArray(bufferSizeEntries)

    /**
     * Used to signal that new raw data is available in the buffer.
     * capacity zero => a rendezvous channel.
     */
    private val bufferDataAvailable = Channel<Unit>(capacity = 1)

    /**
     * The number of entries available to be read from the buffer, based on the difference between
     * entries added and entries removed from it..
     */
    private var entriesAvailable = 0

    /**
     * The number of entries we have written to the the current file so far.
     */
    private var entriesActuallyWrittenToCurrentFile = 0

    /**
     * The total entries written to all files in this sequence.
     */
    private var entriesActuallyWrittenToFileSequence = 0

    /**
     * The number of entries we have been asked to write to file, or null for unlimited.
     * Also, the number of those entries that are part of the pretrigger.
     */
    private var entriesToWriteToFileSequence: Int? = null
    private var preTriggerEntriesToBeWrittenToFile: Int = 0

    /**
     * The index that the next new entry should be written to.
     */
    private var nextWriteIndex = 0

    /**
     * The index that the next unread entry should be read from.
     */
    private var nextReadIndex = 0

    private var guanoDataTime: String? = null
    private var triggerHandlerJob: Job? = null

    private var triggerConfig: TriggerConfig? = null
    private var state: State = State.START_STATE
    private val triggerConfigChannel = Channel<TriggerConfig>(capacity = 10)
    private var stateJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private val triggerEventChannel = Channel<Unit>(Channel.CONFLATED)  // Combine multiple triggers into one.

    /**
     * True while a file write sequence is in progress. Used to avoid reallocating the ring buffer
     * (which the reader is concurrently consuming) during recording.
     */
    @Volatile
    private var fileWriteActive = false

    val batgizmoNamespace = "BatGizmo|App"  // As recommended by David Riggs, riggsd/guano-spec.


    /**
     * This mutex is used to protect all mutable data in this class *except* for the contents
     * of buffer. Use of mutex for the indexes we update has a side effect of being
     * a memory barrier. So as long as reading keeps ahead of writing, we are OK.
     */
    private var mutex = Mutex()

    private val nativeUSB = NativeUSB()

    /**
     * Reallocate the ring buffer if the configured pre-trigger time implies a different size.
     * Only safe to call from the data-reading coroutine (the sole writer of buffer contents) and
     * only while no file is being written, so there is no concurrent reader of the buffer.
     */
    private suspend fun maybeResizeBuffer() {
        if (fileWriteActive)
            return
        val required = computeBufferSizeEntries(model.settings.preTriggerTimeMs)
        if (required == bufferSizeEntries)
            return
        mutex.withLock {
            // Re-check under the lock: a recording may have started since the check above.
            if (fileWriteActive)
                return@withLock
            Timber.d("Resizing pre-trigger buffer from $bufferSizeEntries to $required entries")
            buffer = ShortArray(required)
            bufferSizeEntries = required
            // Discard any buffered pre-roll; it refills within the new pre-trigger window.
            nextWriteIndex = 0
            nextReadIndex = 0
            entriesAvailable = 0
        }
    }

    private fun createChannelJob(): Job {
        return scope.launch(context = Dispatchers.IO) {
            try {
                // Worker thread.
                require(bufferSizeEntries > 0)

                Timber.d("createChannelJob coroutine started")

                //  Handy for testing and debugging:
                val useFakeData = false
                var fake_value: Short = 0

                /*
                    Wait for new raw data to be available from the microphone.
                    The for statement will also check if a cancel is pending, and if so pass control
                    to the finally block for cleanup and prevent this job becoming a zombie:
                */
                for (bufferDescriptor in LiveDataBridge.fileWriterChannel) {

                    // New live data is available.

                    // Resize the ring buffer first if the pre-trigger setting has changed. This is
                    // the only place buffer contents are written, so resizing here avoids races.
                    maybeResizeBuffer()

                    // We will always read as much data into the buffer as we can even if
                    // we overtake the reader and overwrite, so that valid pre trigger data is always available:
                    val sourceSamples = bufferDescriptor.samples

                    /*
                        Copy the newly available data into the next available location in our buffer,
                        wrapping it as required.
                        Note: the mutex is not held, concurrent access to the buffer itself is not
                        synchronized:
                    */
                    var copiedCount = 0
                    if (useFakeData) {
                        var j = nextWriteIndex
                        for (i in 0 until sourceSamples) {
                            buffer[j] = fake_value
                            j += 1
                            if (j == bufferSizeEntries)
                                j = 0
                            fake_value = (fake_value + 1).toShort()
                            if (fake_value >= 0x7000)
                                fake_value = 0
                        }
                        copiedCount = sourceSamples
                    }
                    else {
                        copiedCount = LiveDataCopy.copyIntoRingBuffer(
                            bufferDescriptor,
                            buffer,
                            nextWriteIndex,
                            bufferSizeEntries,
                            nativeUSB
                        )
                    }

                    mutex.withLock {
                        require(copiedCount in 0 ..sourceSamples) {
                            "Expected up to $sourceSamples samples to be copied, actually got $copiedCount"
                        }
                        // Timber.d("New data arrived: $copiedCount entries")

                        require(copiedCount in 0..bufferSizeEntries) {
                            "nativeUSB.copyURBBufferData returned $copiedCount entries, bufferSizeEntries = $bufferSizeEntries" }

                        // Write the new data to the buffer, wrapping as required:
                        /// Timber.d("asdf: adding first value = ${buffer[nextWriteIndex]}")
                        nextWriteIndex = addAndWrap(nextWriteIndex, copiedCount, bufferSizeEntries)
                        /// Timber.d("asdf: nextWriteIndex = $nextWriteIndex")
                        // Can't be any more than the buffer size:
                        entriesAvailable = minOf(entriesAvailable + copiedCount, bufferSizeEntries)
                        /// Timber.d("asdf: entriesAvailable += copiedCount ($copiedCount) = $entriesAvailable")

                        // Timber.d("New raw data received: copiedCount = $copiedCount")
                        require(entriesAvailable in 0..bufferSizeEntries) {
                            "entriesAvailable = $entriesAvailable, bufferSizeEntries = $bufferSizeEntries"
                        }

                        /*
                            Try to signal to the file writer that more data is available. If the
                            listener isn't listening, just continue. The listener can pick up all
                            the data the next time around.
                         */
                        bufferDataAvailable.trySend(Unit)
                    }
                }
            }
            catch (e: CancellationException) {
                // Normal - the coroutine has been cancelled.
            }
            catch (e: Exception) {
                Timber.e("exception caught (1): $e")
                handleException(e)
            }
            finally {
                // We get here when the loop is cancelled on shutdown.
            }
            Timber.d("createChannelJob coroutine finished")
        }
    }

    suspend fun run() {
        Timber.i("run() called")

        // Start streaming data into the circular buffer:
        mutex.withLock {
            channelJob?.cancel()        // Paranoia.
            channelJob = createChannelJob()
        }

        // Start the trigger handler:
        stateJob = scope.launch(context = Dispatchers.Default) {
            Timber.i("run coroutine started")
            try {
                doStateMachine()
            }
            catch (e: Exception) {
                Timber.e("exception caught (2) : $e")
                handleException(e)
            }
            Timber.i("run coroutine finished")
        }

        // Generate some test triggers:
        if (false) {
            scope.launch(context = Dispatchers.Default) {
                Timber.i("test coroutine started")
                test()
                Timber.i("test coroutine finished")
            }
        }
    }

    /**
     * Make sure underlying handles and resources are closed:
     */
    suspend fun shutdown() {

        // Clean shutdown in case we are recording:
        cancelled.set(true)
        stateJob?.cancelAndJoin()


        mutex.withLock {

            // If we don't do this, it will continue for ever, zombie like.
            // Signal to the job to finish and wait for it to avoid
            // async native layer access to data that is about to be garbage
            // collected:
            channelJob?.cancelAndJoin()
            channelJob = null

            rawStream?.close()
            rawStream = null
        }
    }

    /**
     * Call this method to set the trigger mode.
     */
    fun configureTrigger(config: TriggerConfig) {
        // Ignore errors here, it just means the triggering configuration is rather sensitive:
        triggerConfigChannel.trySend(config)
    }

    /**
     * Call this method to trigger or retrigger in auto mode.
     */
    fun trigger() {
        Timber.d("trigger() called")
        triggerEventChannel.trySend(Unit)
    }

    private suspend fun test() {
        Timber.d("test() called")

        if (false) {
            // Manual trigger test.
            delay(1000)
            configureTrigger(TriggerConfig(TriggerType.MANUAL))
            delay(7000)
            configureTrigger(TriggerConfig(TriggerType.OFF))
        }

        if (false) {
            // Automatic trigger test.
            delay(5000)
            trigger()                          // 0.5 + 1
            delay(500)              // + 0.5
            trigger()
            delay(500)           // + 0.5
            trigger()
            delay(1500)           // 0.5 + 1
            trigger()

            /*
            delay(500)
            trigger()
            delay(500)
            trigger()
            delay(500)
            trigger()
            delay(500)
            trigger()
            delay(500)
            trigger()
            delay(500)
            trigger()

            delay(2000)
            trigger()
            */
        }
    }

    private suspend fun doStateMachine() {
        try {
            // Paranoia on startup:
            stopAndResetState()
            for (config in triggerConfigChannel) {
                Timber.d("Processing trigger config: $config")

                val initialState = state

                /**
                 * The concurrency model here is
                 * (1) only use local variables in this method
                 * (2) call out to action methods that lock the mutex and use instance data
                 *  as required.
                 */

                when (state) {
                    State.START_STATE -> {
                        if (config.triggerType == TriggerType.MANUAL) {
                            transitionStartManualTriggered(config)
                            state = State.MANUAL_TRIGGER_STATE
                        }
                        else if (config.triggerType == TriggerType.AUTO) {
                            transitionStartAutoTriggered(config)
                            state = State.AUTO_TRIGGER_STATE
                        }
                    }

                    State.AUTO_TRIGGER_STATE -> {
                        // Awaiting an actual trigger in auto mode.
                        if (config.triggerType == TriggerType.OFF) {
                            transitionStopAutoTriggered(config)
                            state = State.START_STATE
                        }
                        else if (config.triggerType == TriggerType.MANUAL) {
                            transitionStopAutoTriggered(config)
                            transitionStartManualTriggered(config)
                            state = State.MANUAL_TRIGGER_STATE
                        }
                    }

                    State.MANUAL_TRIGGER_STATE -> {
                        // Currently in manual trigger mode.
                        if (config.triggerType == TriggerType.OFF) {
                            transitionStopManualTriggered(config)
                            state = State.START_STATE
                        }
                        else if (config.triggerType == TriggerType.AUTO) {
                            transitionStopManualTriggered(config)
                            transitionStartAutoTriggered(config)
                            state = State.AUTO_TRIGGER_STATE
                        }
                    }
                }

                val finalState = state
                if (finalState != initialState) {
                    Timber.d("State change from $initialState to $finalState")
                }
            }
        } catch (e: CancellationException) {
            // The job was cancelled.
        } finally {
            // Finally block runs even on cancellation
        }
    }

    private suspend fun stopAndResetState() {
        // Tell the file write job to finish, and wait for it.
        // if it hasn't started yet, or already finished:
        cancelled.set(true)
        triggerHandlerJob?.cancelAndJoin()
        // Important: reset this so we don't immediately cancel next time:
        cancelled.set(false)

        entriesToWriteToFileSequence = null
        preTriggerEntriesToBeWrittenToFile = 0
        nextReadIndex = 0
        nextWriteIndex = 0
        entriesAvailable = 0
    }

    private suspend fun transitionStartManualTriggered(config: TriggerConfig) {
        mutex.withLock {
            triggerConfig = config

            // Asynchronous manual file writer:
            triggerHandlerJob = scope.launch(context = Dispatchers.IO) {
                Timber.d("transitionStartManualTriggered coroutine started")
                try {
                    writeFileSequence(this, false, TriggerType.MANUAL)
                }
                catch (e: Exception) {
                    Timber.e("exception caught (3): $e")
                    handleException(e)
                }
                Timber.d("transitionStartManualTriggered coroutine finished")
            }
        }
    }

    private suspend fun transitionStopManualTriggered(config: TriggerConfig) {
        mutex.withLock {
            triggerConfig = config
        }

        stopAndResetState()
    }


    private suspend fun transitionStartAutoTriggered(config: TriggerConfig) {

        mutex.withLock {
            triggerConfig = config

            // Discard any stale triggers queued up from before we enter this mode:
            flushChannel(triggerEventChannel)
        }

        var fileWriterJob: Job? = null

        // Asynchronous triggered file writer:
        triggerHandlerJob = scope.launch(context = Dispatchers.IO) {
            Timber.d("transitionStartAutoTriggered coroutine started")
            try {
                for (dummy in triggerEventChannel) {
                    mutex.withLock {
                        // This is a new trigger.
                        // Spawn a coroutine that writes a file:
                        fileWriterJob = scope.launch(context = Dispatchers.IO) {
                            writeFileSequence(this, true, TriggerType.AUTO)
                        }
                    }

                    // Wait until the file writer has done its thing. It will poll
                    // the trigger event channel on its own account and adjust its end time
                    // dynamically to handle retriggering. Any triggers it misses
                    // will remain queued in the channel for this loop to pick up.
                    fileWriterJob?.join()
                }
            }
            catch (e: CancellationException) {
                // The job was cancelled.
                // Also cancel any associated file writer:
                fileWriterJob?.cancelAndJoin()
            }
            catch (e: Exception) {
                Timber.e("exception caught (4): $e")
                handleException(e)
            }

            Timber.d("transitionStartAutoTriggered coroutine finished")
        }
    }

    private suspend fun transitionStopAutoTriggered(config: TriggerConfig) {
        mutex.withLock {
            triggerConfig = config
        }

        stopAndResetState()
    }

    private suspend fun writeFileSequence(scope: CoroutineScope,
                                          isTriggered: Boolean,
                                          triggerType: TriggerType
                                          ) {
        Timber.i("writeFileSequence called")

        val s = model.settings      // For brevity. Note that model.settings is a var not a val.

        var initialFileFields = linkedMapOf<String, String>()

        initialFileFields.apply {
            put("$batgizmoNamespace|TriggerType", triggerType.str)
            put("$batgizmoNamespace|PretriggerS", prettyFloat3Dps(s.preTriggerTimeMs / 1000f))
            put("$batgizmoNamespace|PosttriggerS", prettyFloat3Dps(s.postTriggerTimeMs / 1000f))
            put("$batgizmoNamespace|MaxFileTimeS", prettyFloat3Dps(s.maxFileTimeMs / 1000f))
        }

        if (triggerType == TriggerType.AUTO) {
            initialFileFields.apply() {
                put(
                    "$batgizmoNamespace|AutoTriggerThresholddB",
                    prettyFloat3Dps(s.autoTriggerThresholdDb)
                )
                put("$batgizmoNamespace|AutoTriggerMinkHz", prettyFloat3Dps(s.autoTriggerRangeMinkHz))
                put("$batgizmoNamespace|AutoTriggerMaxkHz", prettyFloat3Dps(s.autoTriggerRangeMaxkHz))
            }
        }

        val continuationFileFields = linkedMapOf(
            "$batgizmoNamespace|TriggerType" to "${TriggerType.CONTINUATION.str} (${triggerType.str})"
        )

        var resetIndexes = true
        var firstFile = true
        entriesActuallyWrittenToFileSequence = 0
        try {
            // Signal to the UI that we are writing to file:
            signalCurrentlyWriting(true)
            // Prevent the ring buffer being reallocated while we are reading from it.
            fileWriteActive = true
            do {
                val s = mutex.withLock {
                    // Open the temp file to write to. The first time around we reset
                    // the read index to current - pretrigger, as the starting
                    // point to write to file from.
                    startFile(resetIndexes, isTriggered)
                }
                resetIndexes = false

                // If this is a continuation of a previous file, we will resume from the
                // exact buffer position that the previous file ended. We need reading to keep
                // ahead of writing for that work.

                val continuationFileNeeded: Boolean = try {
                    writeStreamToFile(scope, s)
                } catch (e: Exception) {
                    Timber.d("Exception $e")
                    throw e
                } catch (e: Error) {
                    Timber.d("Error $e")
                    throw e
                }

                // Create a publicly accessible .wav file containing data from the temp file:
                mutex.withLock {
                    endFile(if (firstFile) initialFileFields else continuationFileFields)
                }
                firstFile = false

                Timber.i("continuationFileNeeded = $continuationFileNeeded")
            } while (continuationFileNeeded)
        }
        finally {
            Timber.d("Finally executed")
            fileWriteActive = false
            signalCurrentlyWriting(false)
        }
    }

    private suspend fun startFile(resetIndexes: Boolean, isTriggered: Boolean): FileOutputStream {

        getSettingsSnapshot()

        // Get the local time to use as the basis of the file and folder name and the GUANO timestamp
        // field:
        val now = OffsetDateTime.now()

        // Generate the name of the WAV file and folder for later when we need it.
        // Do it now so that it is based on the start time.
        val wfi = generateFileNameAndFolder(now)
        wavFileInfo = wfi
        Timber.i("Preparing to write data to WAV file $wfi")

        // We write raw live data to a cache file, and move it to a public
        // location and name once it is complete. We have no choice about this as
        // MediaStore locations don't support seek, which we need to update the file
        // header once we have finished. Pah.
        val f = File(context.cacheDir, rawDataFileName)
        // This truncates to empty if the file already exists:
        val s = f.outputStream()
        rawFile = f
        rawStream = s

        // Note the start time for use in guano metadata. Actually this is the trigger time,
        // ignoring the pretrigger interval.
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX")
        guanoDataTime = now.format(formatter)

        if (resetIndexes) {
            // Pretrigger and length of trigger need a definition of now to be consistent
            // with each other:
            val nowIndex = nextWriteIndex

            /*
             * Figure out where to start writing to file from in the buffer. That can be in past if pretrigger
             * is configured. The data writing loop will catch up from that point, as long as the data
             * hasn't been overwritten in the buffer.
             * Don't try to read more pretrigger data than is available.
             */
            val preTriggerEntriesAvailable = minOf(preTriggerEntries, entriesAvailable)
            require(preTriggerEntriesAvailable in 0..preTriggerEntries) {
                "preTriggerEntriesAvailable = $preTriggerEntriesAvailable, preTriggerEntries = $preTriggerEntries"
            }
            // Limit entriesAvailable to the data we intend to write to file, in effect discarding any older data
            // and avoiding reads from overtaking writes to the buffer:
            entriesAvailable = preTriggerEntriesAvailable
            nextReadIndex =
                subtractAndWrap(nowIndex, preTriggerEntriesAvailable, bufferSizeEntries)
            require(nextReadIndex in 0 until bufferSizeEntries) {
                "nextReadIndex = $nextReadIndex, bufferSizeEntries = $bufferSizeEntries"
            }
            /// Timber.d("asdf: preTriggerEntriesAvailable = $preTriggerEntriesAvailable, nextReadIndex = $nextReadIndex, " +
            ///        "nowIndex = $nowIndex, entriesAvailable = $entriesAvailable")

            // Note some values to be used when retriggering during a recording:
            preTriggerEntriesToBeWrittenToFile = preTriggerEntriesAvailable
            entriesToWriteToFileSequence = if (isTriggered) {
                // Calculate an end index based on the same reference point as the read index:
                preTriggerEntriesAvailable + postTriggerEntries
            } else {
                // Indefinite:
                null
            }
        }

        // Track how many entries to write to each file:
        entriesActuallyWrittenToCurrentFile = 0

        return s
    }

    private fun getSettingsSnapshot() {
        // Using float to avoid integer overflows:
        maxFileEntries = round(sampleRate.toFloat() * model.settings.maxFileTimeMs / 1000).toInt()
        preTriggerEntries = round(sampleRate.toFloat() * model.settings.preTriggerTimeMs / 1000).toInt()
        postTriggerEntries = round(sampleRate.toFloat() * model.settings.postTriggerTimeMs / 1000).toInt()

        // Make sure the maximum is long enough to accommodate the pre trigger. Multiple
        // files can be written to accommodate the post trigger if required
        maxFileEntries = maxOf(maxFileEntries, preTriggerEntries)
        Timber.d("Resultant maxFileEntries = $maxFileEntries")
    }

    private fun endFile(additionalGuanoFields: LinkedHashMap<String, String>?) {
        // Create a WAV file containing the raw data and clean up the temp file:
        rawStream?.let { rs ->
            rs.close()
            rawFile?.let { rf ->
                wavFileInfo?.let { wfi ->

                    // We postponed creating the wav header to this point so that we know
                    // the data length, to avoid the need patch the file after the event.

                    val guanoData = makeGuanoData(additionalGuanoFields)

                    val wavHeader = createWavHeader(
                        dataEntries = entriesActuallyWrittenToCurrentFile,
                        sampleRate = sampleRate,
                        bitsPerSample = 16,     // Ugly hard coding for now.
                        guanoData.size
                    )
                    val wavFooter = createWavFooterWithGuano(guanoData)

                    moveTempFileToMediaStore(rf, wfi, wavHeader, wavFooter)
                }
                // Finish with the temp file:
                rf.delete()
            }
        }
    }

    /**
     * Write data to file until the file is full or we have written all the data
     * we need to.
     *
     * Return true if a continuation file should be opened for further data to be written.
     */
    private suspend fun writeStreamToFile(
        thisScope: CoroutineScope,
        s: FileOutputStream
    ): Boolean {

        Timber.d("writeStreamToFile called")

        var continuationFileNeeded = true

        do {
            var nextReadIndexCopy = 0       // A local copy we can access without the mutex.
            var entriesAvailableCopy = 0
            var entriesToWriteToCurrentFile = 0
            var finishedCurrentFile = false

            mutex.withLock {
                // Timber.d("nextWriteIndex = $nextWriteIndex, nextReadIndex = $nextReadIndex, entriesAvailable = $entriesAvailable")

                /*
                    Handle retriggering if required by extending the total entries to be written to
                    the sequence of files.
                */
                entriesToWriteToFileSequence?.let { it ->
                    val result = triggerEventChannel.tryReceive()
                    if (result.isSuccess) {
                        // I don't *think* this is necessary, but just in case we need it to consume the event:
                        val dummy = result.getOrNull()

                        val remainingPretriggerEntries = maxOf(0, preTriggerEntriesToBeWrittenToFile - entriesActuallyWrittenToFileSequence)
                        entriesToWriteToFileSequence = entriesActuallyWrittenToFileSequence + remainingPretriggerEntries + postTriggerEntries

                        require(entriesToWriteToFileSequence!! >= 0) {
                            "entriesActuallyWrittenToCurrentFile = $entriesActuallyWrittenToCurrentFile, " +
                            "entriesToBeWrittenToFileSequence = $entriesToWriteToFileSequence, " +
                            "it = $it, entriesActuallyWrittenToFilesInSequence = $it"
                        }

                        Timber.d("Handling retrigger: entriesToBeWrittenToFile updated from $it to $entriesToWriteToFileSequence; " +
                                "entriesActuallyWrittenToFilesInSequence = $entriesActuallyWrittenToFileSequence; remaining pretrigger = $remainingPretriggerEntries")
                    }
                }

                // Get a consistent snapshot of instance level values, as we will release and re-acquire
                // the mutex in a moment:
                nextReadIndexCopy = nextReadIndex
                entriesAvailableCopy = entriesAvailable
                entriesToWriteToCurrentFile = entriesAvailableCopy
                require(entriesToWriteToCurrentFile >= 0) {
                    "entriesToWrite = $entriesToWriteToCurrentFile, nextReadIndexCopy = $nextReadIndexCopy"
                }

                // Limit based on the maximum file write chunk size:
                entriesToWriteToCurrentFile = minOf(entriesToWriteToCurrentFile, maxFileWriteChunkEntries)
                require(entriesToWriteToCurrentFile >= 0) {
                    "(1) entriesToWriteToCurrentFile = $entriesToWriteToCurrentFile, entriesAvailableCopy = $entriesAvailableCopy"
                }

                // Limit based on the maximum file size:
                val spaceRemainingInCurrentFile = maxFileEntries - entriesActuallyWrittenToCurrentFile
                if (entriesToWriteToCurrentFile > spaceRemainingInCurrentFile) {
                    entriesToWriteToCurrentFile = spaceRemainingInCurrentFile
                    Timber.d("Finishing this file: maximum file size exceeded: entriesActuallyWrittenToCurrentFile = $entriesActuallyWrittenToCurrentFile, " +
                            "maxFileEntries = $maxFileEntries")
                    finishedCurrentFile = true
                }
                require(entriesToWriteToCurrentFile >= 0) {
                    "(2) entriesToWriteToCurrentFile = $entriesToWriteToCurrentFile, spaceRemainingInThisFile = $spaceRemainingInCurrentFile"
                }

                // Limit based on the total number of entries we planned to write in the file sequence:
                entriesToWriteToFileSequence?.let {
                    /// Timber.d("asdf: entriesToWriteToFileSequence = $it, entriesActuallyWrittenToFileSequence = $entriesActuallyWrittenToFileSequence, entriesToWriteToCurrentFile=$entriesToWriteToCurrentFile")
                    if (entriesActuallyWrittenToFileSequence + entriesToWriteToCurrentFile >= it) {
                        entriesToWriteToCurrentFile = maxOf(0, it - entriesActuallyWrittenToFileSequence)
                        continuationFileNeeded = false
                        finishedCurrentFile = true
                        Timber.d("Finishing sequence: expected entries for file sequence have been written.")
                    }
                    require(entriesToWriteToCurrentFile >= 0) {
                        "(3) entriesToWriteToCurrentFile = $entriesToWriteToCurrentFile, entriesToWriteToFileSequence = $it, " +
                        "entriesActuallyWrittenToCurrentFile = $entriesActuallyWrittenToCurrentFile " +
                        "entriesToWriteToFileSequence = $entriesToWriteToFileSequence"
                    }
                }
            }

            /*
                Write to file if we are finishing, or there is at least a full chunk available:
                This avoids large numbers of very small writes.
             */
            if (finishedCurrentFile || entriesAvailableCopy >= maxFileWriteChunkEntries) {
                /// Timber.d("asdf: Writing chunk of size $entriesToWrite from entry $nextReadIndexCopy, "
                ///        + "entriesAvailable = $entriesAvailable, first value = ${buffer[nextReadIndexCopy]}")
                val count = writeDataWithWrap(s, start = nextReadIndexCopy, length = entriesToWriteToCurrentFile)
                mutex.withLock {
                    entriesAvailable -= count
                    /// Timber.d("asdf: entriesAvailable -= count ($count): $entriesAvailable")
                    require(count in 0..entriesToWriteToCurrentFile) {
                        "count = $count, entriesToWrite = $entriesToWriteToCurrentFile"
                    }
                    entriesActuallyWrittenToCurrentFile += count
                    entriesActuallyWrittenToFileSequence += count
                    nextReadIndex = addAndWrap(nextReadIndex, count, bufferSizeEntries)
                    require(nextReadIndex in 0..bufferSizeEntries) {
                        "nextReadIndex = $nextReadIndex, count=$count, entriesActuallyWrittenToFile=$entriesActuallyWrittenToCurrentFile"
                    }
                    // Timber.d("Entries written count = $count, nextReadIndex = $nextReadIndex")
                }
                if (finishedCurrentFile) {
                    Timber.d("Finishing file with $entriesActuallyWrittenToCurrentFile entries written.")
                    break   // The file is full.
                }
            }

            // Don't block until we have written all available data to file:
            if (entriesAvailable < maxFileWriteChunkEntries) {
                try {
                    // Yield until we get a signal that more data is available.
                    // Timber.d("Yielding until more data arrives.")
                    bufferDataAvailable.receive()
                    //Timber.d("bufferDataAvailable.receive() returned")
                } catch (e: CancellationException) {
                    Timber.d("File writing job is cancelled.")
                    continuationFileNeeded = false
                    break   // The job has been cancelled.
                }
            }
        } while (!cancelled.get() && thisScope.isActive)

        Timber.d("Finished file writing loop.")

        if (cancelled.get())
            continuationFileNeeded = false

        return continuationFileNeeded
    }

    private fun makeGuanoData(additionalGuanoFields: LinkedHashMap<String, String>?): ByteArray {
        val fields = linkedMapOf<String, String>() // preserves insertion order

        // Required GUANO fields
        fields["GUANO|Version"] = "1.0"

        liveConnectResult.sampleRate?.let { fields["Samplerate"] = it.toString() }
        liveConnectResult.manufacturerName?.let { fields["Make"] = it }
        liveConnectResult.productName?.let { fields["Model"] = it }
        guanoDataTime?.let { fields["Timestamp"] = it }

        if (model.settings.includeLocationInFile) {
            locationFlow.value?.let {
                fields["Loc Position"] = "${it.latitude} ${it.longitude}"
            }
        }

        // Custom Guano fields:
        fields["$batgizmoNamespace|DeviceModel"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        fields["$batgizmoNamespace|Version"] = BuildConfig.VERSION_NAME

        additionalGuanoFields?.let {
            fields.putAll(additionalGuanoFields)
        }

        // Render the map to a GUANO string
        val guanoString = buildString {
            fields.forEach { (key, value) ->
                append("$key: $value\n")
            }
        }

        var data = guanoString.toByteArray(Charsets.UTF_8)
        if (data.size % 2 == 1) {
            // Pad to even number of bytes for WAV compliance
            data += 0.toByte()
        }

        return data
    }

    private fun moveTempFileToMediaStore(
        rawFile: File,
        wfi: WavFileInfo,
        wavHeader: ByteArray,
        wavFooter: ByteArray
    ): Boolean {
        val resolver = context.contentResolver

        // DIRECTORY_DOCUMENTS as these are not normal audio files:
        val baseRelativePath = Environment.DIRECTORY_DOCUMENTS +
                "/$publicFolderName/${wfi.folderName.trimStart('/').trimEnd('/')}/"

        val finalFileName =
            generateUniqueFileName(wfi.fileNameBase, baseRelativePath, resolver) ?: return false

        Timber.d("Finally writing data to MediaStore file $finalFileName")

        val contentValues = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, finalFileName)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, baseRelativePath)
            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, contentValues) ?: return false

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(rawFile).use { inputStream ->
                    outputStream.write(wavHeader)
                    inputStream.copyTo(outputStream)
                    outputStream.write(wavFooter)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(uri, null, null)
            false
        }
        finally {
            contentValues.clear()
            contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            rawFile.delete()
        }
    }

    private fun generateUniqueFileName(
        baseNameBase: String,
        relativePath: String,
        resolver: ContentResolver
    ): String? {
        for (i in 0..99) {
            val candidateName = if (i == 0) "$baseNameBase.wav" else "$baseNameBase-$i.wav"
            if (!fileExistsInMediaStore(candidateName, relativePath, resolver)) {
                return candidateName
            }
        }
        Timber.w("All name variants taken for $baseNameBase in $relativePath")
        return null
    }

    private fun fileExistsInMediaStore(
        fileName: String,
        relativePath: String,
        resolver: ContentResolver
    ): Boolean {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection =
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(fileName, relativePath)

        val exists = resolver.query(collection, projection, selection,
            selectionArgs, null)?.use {
                cursor ->
            cursor.moveToFirst()
        } ?: false
        return exists
    }


    /**
     * Create a file name in the standard format used by bat detectors:
     * YYYMMDD_HHMMSS.wav, in local time subject to DST. Also, the name
     * of a folder to put it in, based on the date.
     */
    private fun generateFileNameAndFolder(now: OffsetDateTime): WavFileInfo {

        // --- Folder name: "YYYY-MM-DD" ---
        val folderFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val folderName = now.format(folderFormatter)

        // --- File name: "YYYYMMDD_HHMMSS.wav" ---
        val fileFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val fileNameBase = now.format(fileFormatter)

        return WavFileInfo(fileNameBase = fileNameBase, folderName = folderName)
    }

    /**
     * Write data to file from the given start point in the buffer, with wrapping as required.
     * Assume the caller has checked that enough data is available in the buffer - this
     * method just copies it dumbly.
     * Return the number of data points actually written.
     */
    private fun writeDataWithWrap(s: FileOutputStream, start: Int, length: Int): Int {

        var remainingEntriesToCopy = length

        // The data may be wrapped so we may need to write in two chunks:
        var chunk1Offset = start
        val chunk1Length = minOf(remainingEntriesToCopy, bufferSizeEntries - chunk1Offset)
        writePcm16LeToStream(s, buffer, chunk1Offset, chunk1Length)
        remainingEntriesToCopy -= chunk1Length

        // Copy a second chunk if the data is wrapped:
        if (remainingEntriesToCopy > 0) {
            val chunk2Offset = 0
            val chunk2Length = minOf(remainingEntriesToCopy, bufferSizeEntries - chunk2Offset)
            writePcm16LeToStream(s, buffer, chunk2Offset, chunk2Length)
            remainingEntriesToCopy -= chunk2Length  // Should be 0 at this point.
        }

        require(remainingEntriesToCopy == 0)

        return length - remainingEntriesToCopy
    }

    private fun writePcm16LeToStream(
        output: OutputStream,
        samples: ShortArray,
        offset: Int,
        length: Int
    ) {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "Invalid offset/length ($offset/$length) for the given sample array size of ${samples.size}"
        }

        val byteBuffer = ByteArray(length * 2)

        var j = 0
        for (i in offset until offset + length) {
            val sample = samples[i].toInt()
            byteBuffer[j++] = (sample and 0xFF).toByte()          // low byte (little endian)
            byteBuffer[j++] = ((sample shr 8) and 0xFF).toByte() // high byte
        }

        output.write(byteBuffer, 0, byteBuffer.size)
    }

    private fun createWavHeader(
        dataEntries: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        guanoDataLength: Int,
        channels: Int = 1,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalAudioLen = dataEntries * 2

        // Total = PCM data + standard header (36) + Guano chunk + data header (8) + guano header (8)
        val totalDataLen = 36 + 8 + totalAudioLen + 8 + guanoDataLength

        val header = ByteArray(44)

        // --- RIFF Header ---
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // --- fmt chunk (always 16 bytes for PCM) ---
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16) // Subchunk1Size
        writeShortLE(header, 20, 1) // PCM format
        writeShortLE(header, 22, channels.toShort())
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, blockAlign.toShort())
        writeShortLE(header, 34, bitsPerSample.toShort())

        var offset = 36

        // --- data chunk (must come after Guano) ---
        header[offset] = 'd'.code.toByte()
        header[offset + 1] = 'a'.code.toByte()
        header[offset + 2] = 't'.code.toByte()
        header[offset + 3] = 'a'.code.toByte()
        writeIntLE(header, offset + 4, totalAudioLen)

        return header
    }

    private fun createWavFooterWithGuano(guanoData: ByteArray): ByteArray
    {
        val guanoChunkSize = guanoData.size
        val guanoChunkTotalSize = 8 + guanoChunkSize

        val footer = ByteArray(guanoChunkTotalSize)

        footer[0] = 'g'.code.toByte()
        footer[1] = 'u'.code.toByte()
        footer[2] = 'a'.code.toByte()
        footer[3] = 'n'.code.toByte()
        writeIntLE(footer, 4, guanoChunkSize)
        System.arraycopy(guanoData, 0, footer, 8, guanoChunkSize)

        return footer
    }

    private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xff).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShortLE(buffer: ByteArray, offset: Int, value: Short) {
        buffer[offset] = (value.toInt() and 0xff).toByte()
        buffer[offset + 1] = ((value.toInt() shr 8) and 0xff).toByte()
    }

    private fun addAndWrap(value: Int, delta: Int, modulus: Int): Int {
        var result = value + delta
        while (result >= modulus)
            result -= modulus
        return result
    }

    private fun subtractAndWrap(value: Int, delta: Int, modulus: Int): Int {
        var result = value - delta
        while (result < 0)
            result += modulus
        return result
    }

    private fun <T> flushChannel(channel: Channel<T>) {
        while (channel.tryReceive().isSuccess) {
            // Item was consumed — we ignore it.
        }
    }

    private suspend fun handleException(e: Exception) {
        onError(e.message ?: e.stackTraceToString())
        // Try to close down cleanly
        try {
            stopAndResetState()
        }
        catch(e : Exception) {
            // Ignore any errors thing might occur.
        }
    }
}