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

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

class WavFileReader(private val ctx: Context) {

    data class WavFileInfo(
        val fileName: String?,
        val fileUri: Uri,
        val sampleRate: Int,
        val numChannels: Short,
        val lengthSeconds: Float,
        val sampleCount: Int,
        val valueRange: Short,
        val timeRange: FloatRange,
        val frequencyRange: FloatRange,
        val bytesPerValue: Int,
        val guanoChunkInfo: WavFileParser.GuanoChunkInfo?
    )

    private data class OpenState(
        var raFile: RandomAccessFile,
        var wavFileInfo: WavFileInfo,
        var wavChunks: WavFileParser.WavChunks,
        var wavFileParser: WavFileParser
        )

    // We only assign the following when open was successful:
    private var openState: OpenState? = null

    /**
     * Call this to close the file that is currently open, if any, and release
     * resources.
     */
    fun close() {
        openState?.raFile?.close()
        openState = null
    }

    fun open(uri: Uri, fileName: String?): WavFileInfo {
        // Free any file and resources already open. No harm done if none were.
        close()

        var raFile: RandomAccessFile? = null
        var wavFileParser: WavFileParser? = null
        var wavChunks: WavFileParser.WavChunks? = null

        /**
         *  Put everything in a try block so that we can do cleanup on any kind of failure.
         *  This avoids leaving things in a half open state.
         */
        try {

            // Copy the content to a temporary file that allows us relatively
            // fast access with seek. The source might be over slow USB or remote
            // HTTP connection.
            val file: File = copyUriToCache(uri)
            val maxFileLength = 50000000
            if (file.length() > maxFileLength) {
                throw IllegalArgumentException("File is greater than the maximum supported ($maxFileLength bytes),")
            }
            val safeRaFile = RandomAccessFile(file, "r")
            raFile = safeRaFile

            // Parse the file as a wav file, to extract what metadata we can:
            wavFileParser = WavFileParser()
            val safeWavFileParser: WavFileParser = wavFileParser

            // Can throw an Exception, which is caught higher up:
            val safeWavChunks: WavFileParser.WavChunks =
                safeWavFileParser.readChunks(safeRaFile, fileName ?: "(no name)")
            wavChunks = safeWavChunks

            var sampleRate = safeWavChunks.fmtChunk.sampleRateHz
            // val data = wavChunks.dataChunk.data
            //wavChunks.guanoChunk?.entriesMap
            val numChannels = safeWavChunks.fmtChunk.numChannels
            // Use the number of samples we actually found, not the number expected from metadata:
            val sampleCount = safeWavChunks.dataChunk.actualSampleCount

            /**
             * Do some sanity checks.
             */

            if (safeWavChunks.fmtChunk.bitsPerSample != 16.toShort()) {
                throw IllegalArgumentException("Support is currently limited to 16 bit PCM - found ${safeWavChunks.fmtChunk.bitsPerSample} bit data.")
            }

            if (safeWavChunks.fmtChunk.numChannels != 1.toShort()) {
                throw IllegalArgumentException("Support is currently limited to one channel. ${safeWavChunks.fmtChunk.numChannels} channels are present.")
            }

            if (safeWavChunks.fmtChunk.sampleRateHz <= 0) {
                throw IllegalArgumentException("The sample rate must be a positive number - it is actually ${safeWavChunks.fmtChunk.sampleRateHz}.")
            }

            val minSamples = Settings.NFftOptions.NFFT_4096.value * 2
            if (sampleCount < minSamples) {
                throw IllegalArgumentException("The data file contains too few samples ($sampleCount, must be at least $minSamples).")
            }

            /**
             * The data seems sane: go ahead and use it.
             */

            // See if there is a sample rate in the GUANO. If there is, use it, because the
            // header sometimes reflects data stored in TE form:
            val guanoChunk: WavFileParser.GuanoChunkInfo? = safeWavChunks.guanoChunk
            if (guanoChunk != null) {
                val guanoKey = "Samplerate".lowercase()
                if (guanoKey in guanoChunk.entriesMap) {
                    val value: String =
                        guanoChunk.entriesMap[guanoKey]!!.value // We've just checked it.
                    val guanoSampleRate = value.toIntOrNull()
                    if (guanoSampleRate != null && guanoSampleRate > 0) {
                        sampleRate = guanoSampleRate
                    }
                }
            }

            // Force the amplitude range to be symmetrical. The inner max is to avoid the impossible negation of -32678.
            val dataRange = safeWavChunks.dataChunk.dataRange
            val symmetricDataRange =
                maxOf<Short>((-maxOf<Short>(dataRange.first, -32767)).toShort(), dataRange.second)

            // What are the maximum ranges for time and frequency that this data can support:
            val lengthSeconds = sampleCount.toFloat() / sampleRate
            val timeRange = if (lengthSeconds > 0) {
                FloatRange(0f, lengthSeconds)
            } else {
                FloatRange(0f, 1f)
            }

            val frequencyRange = FloatRange(0f, (sampleRate / 2).toFloat())

            val wavFileInfo = WavFileInfo(
                fileName = fileName,
                fileUri = uri,
                sampleRate = sampleRate,
                numChannels = numChannels,
                lengthSeconds = lengthSeconds,
                sampleCount = sampleCount,
                valueRange = symmetricDataRange,
                timeRange = timeRange,
                frequencyRange = frequencyRange,
                bytesPerValue = safeWavChunks.fmtChunk.bitsPerSample / 8,
                guanoChunkInfo = wavChunks.guanoChunk
            )

            // Atomically update all state and pass ownership out of this method:
            openState = OpenState(raFile=raFile, wavFileParser=safeWavFileParser,
                wavChunks=wavChunks,wavFileInfo=wavFileInfo)

            return wavFileInfo
        }
        catch (e: Exception) {
            // Clean up anything that might have been opened or allocated:
            raFile?.close()
            raFile = null
            wavChunks = null
            wavFileParser = null

            // Rethrow the exception as is:
            throw e
        }
    }

    /**
     * Create a temporary local file with the content from the URI. That means we have
     * relatively fast access which is seekable.
     *
     * This can throw an Exception.
     */
    private fun copyUriToCache(uri: Uri): File {

        // Create a cache file with an exact name, so that we overwrite it each time
        // a new file is opened for viewing:
        val cacheFile = File(ctx.cacheDir, "viewer_temp.wav")

        // Delete the cache file on exiting from the app:
        cacheFile.deleteOnExit()

        // Copy the content from the URI into the cache file:
        ctx.contentResolver.openInputStream(uri).use { inputStream ->
            if (inputStream == null) {
                throw IOException("InputStream is null when creating cache file.")
            }

            // This will silently overwrite any existing cache file:
            FileOutputStream(cacheFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return cacheFile
    }

    /**
     * Attempt to read the requested number of data samples from the wav file, starting
     * from a specific index. Write the results into the buffer supplied which the caller
     * guarantees to be long enough.
     *
     * Return the number of data samples actually read, which might be less than requested
     * if we reach EOF.
     *
     * If anything goes wrong, an exception is thrown.
     *
     */
    fun readData(range: HORange, dataBuffer: ShortArray, bufferOffset: Int = 0) : Int {
        if (openState == null) {
            throw IllegalStateException("Attempt to readData when the WavFileReader has not been successfully opened.")
        }

        val safeOpenState = openState!!

        val actualCount = safeOpenState.wavFileParser.readData(
            safeOpenState.raFile,
            safeOpenState.wavChunks.fmtChunk,
            range,
            dataBuffer,
            bufferOffset)

        return actualCount
    }
}