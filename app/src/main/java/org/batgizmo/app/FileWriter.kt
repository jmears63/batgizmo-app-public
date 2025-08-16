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

package org.batgizmo.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
import org.batgizmo.app.pipeline.NativeUSB
import org.batgizmo.app.pipeline.UsbService
import uk.org.gimell.batgimzoapp.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class FileWriter(
    private val scope: CoroutineScope,
    private val context: Context,
    private val model: UIModel,
    private val locationFlow: StateFlow<Location?>,
    private val usbConnectResult: UsbService.UsbConnectResult,
    private val sampleRate: Int,
    private val signalCurrentlyWriting: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
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

    private val logTag = this::class.simpleName

    private val rawDataFileName = "filewriter.raw"      // Temporary data storage.
    private val publicFolderName = "BatGizmo"           // Don't include a /

    private var rawFile: File? = null
    private var rawStream: FileOutputStream? = null
    private var wavFileInfo: WavFileInfo? = null

    private var channelJob: Job? = null

    // private val bufferLengthS = 1f
    private val maxFileWriteChunkEntries = 9600          // A bit arbitrary - big enough to get batching efficiency.
    private val maxFileEntries = sampleRate * model.settings.maxFileTimeMs / 1000
    private val preTriggerEntries = sampleRate * model.settings.preTriggerTimeMs / 1000
    private val postTriggerEntries = sampleRate * model.settings.postTriggerTimeMs / 1000
    // Padding buffer size to allow for some latency when written data from trigger:
    private val bufferLengthEntries =
        sampleRate * (Settings.PreTriggerTimeOptions.PRETRIGGER_TIME_MAX.value + 500) / 1000
    private val buffer = ShortArray(bufferLengthEntries)

    private val bufferDataAvailable = Channel<Unit>(capacity = 0)

    private var entriesAvailable =
        0                // Total entries available in the buffer, capped at the buffer size.
    private var entriesActuallyWrittenToFile = 0
    private var entriesToBeWrittenToFile: Int? = null
    private var nextWriteIndex = 0              // Next entry that will be written to buffer.
    private var nextReadIndex = 0               // Next entry that will be read for writing file.
    private var iso8601DateTime: String? = null
    private var triggerHandlerJob: Job? = null

    private var triggerConfig: TriggerConfig? = null
    private var state: State = State.START_STATE
    private val triggerConfigChannel = Channel<TriggerConfig>(capacity = 10)
    private var stateJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    private val triggerEventChannel = Channel<Unit>(Channel.CONFLATED)  // Combine multiple triggers into one.

    val batgizmoNamespace = "BatGizmo|App"  // As recommended by David Riggs, riggsd/guano-spec.


    // This mutex is used to protect all mutable data in this class *except* for the contents
    // of buffer. Use of mutex for the indexes we update has a side effect of being
    // a memory barrier. So as long as reading keeps ahead of writing, we are OK.
    private var mutex = Mutex()

    private val nativeUSB = NativeUSB()

    private fun createChannelJob(): Job {
        return scope.launch(context = Dispatchers.IO) {
            try {
                // Worker thread.
                require(bufferLengthEntries > 0)

                if (BuildConfig.DEBUG)
                    Log.d(logTag, "createChannelJob coroutine started")
                // The for statement will check if a cancel is pending, and if so pass control
                // to the finally block for cleanup and prevent this job becoming a zombie:
                for (bufferDescriptor in LiveDataBridge.fileWriterChannel) {
                    // Copy the native data into the buffer with wrap.

                    var sourceSamples = bufferDescriptor.samples
                    // Paranoia: what if more data is available than we have space for in our
                    // buffer? Shouldn't happen, but...
                    if (sourceSamples > bufferLengthEntries) {
                        sourceSamples = bufferLengthEntries
                        Log.e(logTag, "buffer is not big enough for the data available: ${bufferDescriptor.samples} > $bufferLengthEntries")
                    }

                    // Note: the mutex is not held, concurrent access to the buffer itself is not
                    // synchronized:
                    val copiedCount = nativeUSB.copyURBBufferData(
                        bufferDescriptor.nativeAddress,
                        sourceSamples,
                        buffer,
                        nextWriteIndex,
                        bufferLengthEntries
                    )
                    // Log.d(logTag, "New data arrived: $copiedCount entries")

                    mutex.withLock {
                        nextWriteIndex = addAndWrap(nextWriteIndex, copiedCount, bufferLengthEntries)

                        // The number of entries available maxes out at the buffer size:
                        entriesAvailable = minOf(entriesAvailable + copiedCount, bufferLengthEntries)


                        // Signal to the file writer that more data is available:
                        bufferDataAvailable.trySend(Unit)
                    }
                }
            }
            catch (e: Exception) {
                handleException(e)
            }
            finally {
                // We get here when the loop is cancelled on shutdown.
            }
            if (BuildConfig.DEBUG)
                Log.d(logTag, "createChannelJob coroutine finished")
        }
    }

    suspend fun run() {
        Log.i(logTag, "run() called")

        // Start streaming data into the circular buffer:
        mutex.withLock {
            channelJob?.cancel()        // Paranoia.
            channelJob = createChannelJob()
        }

        // Start the trigger handler:
        stateJob = scope.launch(context = Dispatchers.Default) {
            Log.i(logTag, "run coroutine started")
            try {
                doStateMachine()
            }
            catch (e: Exception) {
                handleException(e)
            }
            Log.i(logTag, "run coroutine finished")
        }

        // Generate some test triggers:
        if (false) {
            scope.launch(context = Dispatchers.Default) {
                Log.i(logTag, "test coroutine started")
                test()
                Log.i(logTag, "test coroutine finished")
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
        if (BuildConfig.DEBUG)
            Log.d(logTag, "trigger() called")
        triggerEventChannel.trySend(Unit)
    }

    private suspend fun test() {
        if (BuildConfig.DEBUG)
            Log.d(logTag, "test() called")

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
                if (BuildConfig.DEBUG)
                    Log.d(logTag, "Processing trigger config: $config")

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
                    if (BuildConfig.DEBUG)
                        Log.d(logTag, "State change from $initialState to $finalState")
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

        entriesToBeWrittenToFile = null
        nextReadIndex = 0
    }

    private suspend fun transitionStartManualTriggered(config: TriggerConfig) {
        mutex.withLock {
            triggerConfig = config

            // Asynchronous manual file writer:
            triggerHandlerJob = scope.launch(context = Dispatchers.IO) {
                if (BuildConfig.DEBUG)
                    Log.d(logTag, "transitionStartManualTriggered coroutine started")
                try {
                    writeFileSequence(this, false, TriggerType.MANUAL)
                }
                catch (e: Exception) {
                    handleException(e)
                }
                if (BuildConfig.DEBUG)
                    Log.d(logTag, "transitionStartManualTriggered coroutine finished")
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
            if (BuildConfig.DEBUG)
                Log.d(logTag, "transitionStartAutoTriggered coroutine started")
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
                handleException(e)
            }

            if (BuildConfig.DEBUG)
                Log.d(logTag, "transitionStartAutoTriggered coroutine finished")
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
        Log.i(logTag, "writeFileSequence called")

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
        try {
            // Signal to the UI that we are writing to file:
            signalCurrentlyWriting(true)
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
                val continuationFileNeeded = writeStreamToFile(scope, s)

                // Create a pubically accessible .wav file containing data from the temp file:
                mutex.withLock {
                    endFile(if (firstFile) initialFileFields else continuationFileFields)
                }
                firstFile = false

                Log.i(logTag, "continuationFileNeeded = $continuationFileNeeded")
            } while (continuationFileNeeded)
        }
        finally {
            signalCurrentlyWriting(false)
        }
    }

    private suspend fun startFile(resetIndexes: Boolean, isTriggered: Boolean): FileOutputStream {
        // Generate the name of the WAV file and folder for later when we need it.
        // Do it now so that it is based on the start time.
        val wfi = generateFileNameAndFolder()
        wavFileInfo = wfi
        Log.i(logTag, "Preparing to write data to WAV file $wfi")

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
        val dateTimeFormatter = DateTimeFormatterBuilder()
            .appendInstant(1) // Fractional digits for seconds
            .toFormatter()
        iso8601DateTime = dateTimeFormatter.format(Instant.now())

        if (resetIndexes) {
            // Pretrigger and length of trigger need a definition of now to be consistent
            // with each other:
            val nowIndex = nextWriteIndex

            /*
             * Figure out where to start writing to file from. That can be in past if pretrigger
             * is configured. The data writing loop will catch up from that point, as long as the data
             * hasn't been overwritten in the buffer.
             * Don't try to read more pretrigger data than is available.
             */
            val preTriggerEntriesAvailable = minOf(preTriggerEntries, entriesAvailable)
            nextReadIndex =
                subtractAndWrap(nowIndex, preTriggerEntriesAvailable, bufferLengthEntries)

            entriesToBeWrittenToFile = if (isTriggered) {
                // Calculate an end index based on the same reference point as the read index:
                preTriggerEntriesAvailable + postTriggerEntries
            } else {
                // Indefinite:
                null
            }
        }


        // Track how many entries to write to each file:
        entriesActuallyWrittenToFile = 0

        /*
        Log.d(
            logTag,
            "in start(): entriesAvailable = $entriesAvailable, preTriggerEntries=$preTriggerEntries, nextReadIndex = $nextReadIndex"
        )
         */

        return s
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

                    val wavHeader = createWavHeaderWithGuano(
                        dataEntries = entriesActuallyWrittenToFile,
                        sampleRate = sampleRate,
                        bitsPerSample = 16,     // Ugly hard coding for now.
                        guanoData
                    )

                    moveTempFileToMediaStore(rf, wfi, wavHeader)
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

        if (BuildConfig.DEBUG)
            Log.d(logTag, "writeStreamToFile called")

        var continuationFileNeeded = true

        do {
            var entriesAvailableToWrite = 0
            var entriesToWrite = 0
            var nextReadIndexCopy = 0       // A local copy we can access without the mutex.
            var finished = false

            mutex.withLock {

                // If this is a finite length recording, handle retriggers:
                entriesToBeWrittenToFile?.let { it ->
                    // See if any trigger events have arrived while we are writing the file; if so,
                    // handle the retrigger by extending the total number of entries we plan to write:
                    val result = triggerEventChannel.tryReceive()
                    if (result.isSuccess) {
                        // I don't *think* this is necessary, but just in case we need it to consume the event:
                        val dummy = result.getOrNull()

                        val currentlyRemainingEntries = maxOf(0, it - entriesActuallyWrittenToFile)
                        entriesToBeWrittenToFile =
                            it - currentlyRemainingEntries + postTriggerEntries

                        if (BuildConfig.DEBUG)
                            Log.d(
                                logTag,
                                "Handling retrigger: entriesToBeWrittenToFile updated from $it to $entriesToBeWrittenToFile"
                            )
                    }
                }

                nextReadIndexCopy = nextReadIndex
                entriesAvailableToWrite =
                    subtractAndWrap(nextWriteIndex, nextReadIndexCopy, bufferLengthEntries)

                // Limit based on the entries available:
                entriesToWrite = entriesAvailableToWrite

                // Limit based on the maximum chunk size:
                entriesToWrite = minOf(entriesToWrite, maxFileWriteChunkEntries)

                // Limit based on the maximum file size:
                val spaceRemainingInFile = maxFileEntries - entriesActuallyWrittenToFile
                if (entriesToWrite > spaceRemainingInFile) {
                    entriesToWrite = spaceRemainingInFile
                    finished = true
                }

                // Limit based on the number of entries we planned to write:
                entriesToBeWrittenToFile?.let {
                    if (entriesActuallyWrittenToFile + entriesToWrite >= it) {
                        entriesToWrite = it - entriesActuallyWrittenToFile
                        continuationFileNeeded = false
                        finished = true
                    }
                }

                /*
                Log.d(
                    logTag,
                    "entriesAvailableToWrite = $entriesAvailableToWrite, maxFileChunkSize = $maxFileChunkSize"
                )
                 */
            }

            /*
                Write to file if we are finishing, or there is at least a full chunk available:
                This avoids large numbers of very small writes.
             */
            if (finished || entriesAvailableToWrite >= maxFileWriteChunkEntries) {
                val count = writeDataWithWrap(s, start = nextReadIndexCopy, length = entriesToWrite)
                mutex.withLock {
                    entriesActuallyWrittenToFile += count
                    nextReadIndex = addAndWrap(nextReadIndex, count, bufferLengthEntries)
                    // Log.d(logTag, "Entries written count = $count, nextReadIndex = $nextReadIndex")
                }
                if (finished) {
                    if (BuildConfig.DEBUG)
                        Log.d(logTag, "File is full or all data has been written.")
                    break   // The file is full.
                }
            } else {
                try {
                    // Yield until we get a signal that more data is available.
                    // Log.d(logTag, "Yielding until more data arrives.")
                    bufferDataAvailable.receive()
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG)
                        Log.d(logTag, "File writing job is cancelled.")
                    continuationFileNeeded = false
                    break   // The job has been cancelled.
                }
            }
        } while (!cancelled.get() && thisScope.isActive)

        if (cancelled.get())
            continuationFileNeeded = false

        return continuationFileNeeded
    }

    private fun makeGuanoData(additionalGuanoFields: LinkedHashMap<String, String>?): ByteArray {
        val fields = linkedMapOf<String, String>() // preserves insertion order

        // Required GUANO fields
        fields["GUANO|Version"] = "1.0"

        usbConnectResult.sampleRate?.let { fields["Samplerate"] = it.toString() }
        usbConnectResult.manufacturerName?.let { fields["Make"] = it }
        usbConnectResult.productName?.let { fields["Model"] = it }
        iso8601DateTime?.let { fields["Timestamp"] = it }

        if (model.settings.locationInFile) {
            locationFlow.value?.let {
                fields["Loc Position"] = "${it.latitude} ${it.longitude}"
            }
        }

        /* Redundant.
        usbConnectResult.sampleRate?.takeIf { it > 0 }?.let { rate ->
            val lengthSeconds = entriesActuallyWrittenToFile.toFloat() / rate
            fields["Length"] = prettyFloat3Dps(lengthSeconds)
        }
        */

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
        wavHeader: ByteArray
    ): Boolean {
        val resolver = context.contentResolver

        // DIRECTORY_DOCUMENTS as these are not normal audio files:
        val baseRelativePath = Environment.DIRECTORY_DOCUMENTS +
                "/$publicFolderName/${wfi.folderName.trimStart('/').trimEnd('/')}"

        val finalFileName =
            generateUniqueFileName(wfi.fileNameBase, baseRelativePath, resolver) ?: return false

        if (BuildConfig.DEBUG)
            Log.d(logTag, "Finally writing data to MediaStore file $finalFileName")

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
                }
            }
            contentValues.clear()
            contentValues.put(MediaStore.Files.FileColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            rawFile.delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(uri, null, null)
            false
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
        Log.w(logTag, "All name variants taken for $baseNameBase in $relativePath")
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

        resolver.query(collection, projection, selection, selectionArgs, null).use { cursor ->
            return cursor != null && cursor.moveToFirst()
        }
    }


    /**
     * Create a file name in the standard format used by bat detectors:
     * YYYMMDD_HHMMSS.wav, in local time subject to DST. Also, the name
     * of a folder to put it in, based on the date.
     */
    private fun generateFileNameAndFolder(): WavFileInfo {
        val now = Date()

        // Formatter for filename: YYYYMMDD_HHMMSS.wav
        val fileFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileNameBase = fileFormatter.format(now)

        // Formatter for folder name: YYYY-MM-DD
        val folderFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val folderName = folderFormatter.format(now)

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
        val chunk1Length = minOf(remainingEntriesToCopy, bufferLengthEntries - chunk1Offset)
        writePcm16LeToStream(s, buffer, chunk1Offset, chunk1Length)
        remainingEntriesToCopy -= chunk1Length

        // Copy a second chunk if the data is wrapped:
        if (remainingEntriesToCopy > 0) {
            val chunk2Offset = 0
            val chunk2Length = minOf(remainingEntriesToCopy, bufferLengthEntries - chunk2Offset)
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

    private fun createWavHeaderWithGuano(
        dataEntries: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        guanoData: ByteArray,
        channels: Int = 1,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalAudioLen = dataEntries * 2

        val guanoChunkSize = guanoData.size
        val guanoChunkTotalSize = 8 + guanoChunkSize

        // Total = PCM data + standard header (36) + Guano chunk + data header (8)
        val totalDataLen = totalAudioLen + 36 + guanoChunkTotalSize

        44 + guanoChunkTotalSize  // 44 includes standard + fmt + data headers
        val header = ByteArray(44 + guanoChunkTotalSize)

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

        header[offset] = 'g'.code.toByte()
        header[offset + 1] = 'u'.code.toByte()
        header[offset + 2] = 'a'.code.toByte()
        header[offset + 3] = 'n'.code.toByte()
        writeIntLE(header, offset + 4, guanoChunkSize)
        System.arraycopy(guanoData, 0, header, offset + 8, guanoChunkSize)
        offset += 8 + guanoChunkSize

        // --- data chunk (must come after Guano) ---
        header[offset] = 'd'.code.toByte()
        header[offset + 1] = 'a'.code.toByte()
        header[offset + 2] = 't'.code.toByte()
        header[offset + 3] = 'a'.code.toByte()
        writeIntLE(header, offset + 4, totalAudioLen)

        return header
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
        if (result >= modulus)
            result -= modulus
        return result
    }

    private fun subtractAndWrap(value: Int, delta: Int, modulus: Int): Int {
        var result = value - delta
        if (result < 0)
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