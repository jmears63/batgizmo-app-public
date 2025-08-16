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

import android.util.Log
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileException(message: String) : Exception(message)

/**
 *  See http://soundfile.sapp.org/doc/WaveFormat/
 *  See https://github.com/riggsd/guano-spec/blob/master/guano_specification.md
 */
class WavFileParser {

    // Interesting things extracted form the "wav " chunk.
    data class FmtChunkInfo(
        val numChannels: Short,
        val sampleRateHz: Int,
        val bitsPerSample: Short
    )

    data class DataChunkInfo(
        val actualSampleCount: Int = 0,
        val dataRange: Pair<Short, Short> = Pair(Short.MIN_VALUE, Short.MAX_VALUE),
        val dataByteCount: Int = 0
    )

    data class GuanoEntry(val key: String, val value: String)

    data class GuanoChunkInfo(
        val raw: String,
        val entriesList: List<GuanoEntry>,
        val entriesMap: Map<String, GuanoEntry>     // The key is normalized to lower case.
    )

    data class WavChunks(
        val fmtChunk: FmtChunkInfo,
        val dataChunk: DataChunkInfo,
        val guanoChunk: GuanoChunkInfo?             // Optional: may not be present in otherwise valid data files.
    )

    private var startOfData: Long? = null           // Offset to the start of the data chunk.

    private fun reset() {
        startOfData = 0
    }

    /**
     * This method either success or throws an exception.
     */
    fun readChunks(raFile: RandomAccessFile, filename: String): WavChunks {
        reset()

        var fmtChunk: FmtChunkInfo? = null
        var dataChunk: DataChunkInfo? = null
        var guanoChunk: GuanoChunkInfo? = null

        // Find the actual file length:
        val fileLength = raFile.length()

        readTextBytes(raFile, filename, 4, "ChunkID", "RIFF")
        readInt32(raFile, filename, "ChunkSize") // Sometimes off by 8. No idea.
        readTextBytes(raFile, filename, 4, "Format", "WAVE")

        /**
         * So far so good. Now there will be a series of chunks in no particular order.
         * Keep trying to read chunks as long as there is unconsumed input.
         */
        while (raFile.filePointer < fileLength) {
            val subChunkId = readTextBytes(raFile, filename, 4, "SubchunkID")
            if (subChunkId == "fmt ") {        // Note: the space is important.
                if (fmtChunk != null)
                    Log.i(this::class.simpleName, "Ignoring extra fmt chunk")
                else
                    fmtChunk = readFormatChunk(raFile, filename)
            } else if (subChunkId == "data") {
                if (dataChunk != null)
                    Log.i(this::class.simpleName, "Ignoring extra data chunk")
                else {
                    if (fmtChunk != null)
                        dataChunk = skimDataChunk(raFile, filename, fmtChunk)
                    else {
                        throw WavFileException("Data chunk found without fmt header.")
                    }
                }
            } else if (subChunkId == "guan") {
                if (guanoChunk != null)
                    Log.i(this::class.simpleName, "Ignoring extra guano chunk.")
                else {
                    guanoChunk = readGuanoChunk(raFile, filename)
                }
            } else {
                Log.i(this::class.simpleName, "Ignoring unknown wav file chunk $subChunkId.")
                skipChunk(raFile, filename)
            }
        }

        if (fmtChunk != null && dataChunk != null)
            return WavChunks(fmtChunk, dataChunk, guanoChunk)
        else {
            throw WavFileException("Bad wav file: both fmt and data chunks must be present.")
        }
    }

    /**
     * This method either succeeds or throws an Exception.
     */
    private fun readGuanoChunk(raFile: RandomAccessFile, filename: String): GuanoChunkInfo {
        /**
         * See:
         *  https://www.wildlifeacoustics.com/SCHEMA/GUANO.html
         *  https://github.com/riggsd/guano-py/blob/master/guano.py
         */
        val chunkSizeBytes = readInt32(raFile, filename, "ChunkSize")
        val byteArray = ByteArray(chunkSizeBytes)
        raFile.readFully(byteArray)
        val guanoString = String(byteArray, Charsets.UTF_8)

        // Low tech parsing of the guano data is probably most resilient against minor
        // syntax errors:
        val rawEntries = guanoString.split("\r\n", "\n")
        val entriesList = mutableListOf<GuanoEntry>()
        val entriesMap = mutableMapOf<String, GuanoEntry>()
        val maxGuanoEntries = 50    // Sanity.
        for ((i, entry) in rawEntries.withIndex()) {
            if (i >= maxGuanoEntries)
                break
            try {
                // Split on the first : character
                val (k, v) = entry.split(":", limit = 2)
                val key = k.trim()
                val value = v.trim()
                val guanoEntry = GuanoEntry(key, value)
                entriesList.add(guanoEntry)
                entriesMap[key.lowercase()] = guanoEntry
            } catch (e: IndexOutOfBoundsException) {
                // Could be a blank entry, or lacks eny :.
            }
        }

        if (chunkSizeBytes % 2 == 1) {
            // Data is padded to an even number of bytes:
            raFile.skipBytes(1)
        }

        return GuanoChunkInfo(guanoString, entriesList, entriesMap)
    }

    /**
     * Read value values according to the half open range provided.
     * Write the data to the buffer supplied, and the number of samples actually read.
     * The caller guarantees the buffer is big enough.
     */
    fun readData(
        raFile: RandomAccessFile,
        fmtChunk: FmtChunkInfo,
        range: HORange,
        dataBuffer: ShortArray,
        bufferOffset: Int = 0
    ): Int {
        val bytesPerValue = (fmtChunk.bitsPerSample / 8).toInt()
        val bytesPerSample = bytesPerValue * fmtChunk.numChannels

        val (start, end) = range
        val sampleCount = end - start
        val valueCount = sampleCount * fmtChunk.numChannels

        // Seek to the data we are interested in:
        check(startOfData != null)
        startOfData?.let { raFile.seek(it + start * bytesPerSample) }

        /**
         * Read the data.
         *
         * Sometimes wav files do end prematurely - I guess the code that writes them just
         * guesses the length to write up front, then the actual length is defined by the
         * amount of data in the file.
         */

        val byteArray = ByteArray(valueCount * 2)
        try {
            // Throws EOFException when we hit the end of the file. This is intentional
            // to read the number of bytes we want.
            raFile.readFully(byteArray)
        } catch (e: EOFException) {
            // If we hit EOF we'll return fewer data values than expect, either
            // on this call or the next one.
        }

        val actualValuesRead = (byteArray.size / 2).toInt()
        // This will be different from actualValuesRead when we do channels != 1:
        val actualSamplesRead = actualValuesRead
        // TODO: check this endianism:
        // Access the ByteArray as a Buffer:
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        // Create a ShortArray of the right size:
        // val shortArray = ShortArray(actualValuesRead)
        // Populate the shortArray using a bulk get for efficiency:
        byteBuffer.asShortBuffer().get(dataBuffer, bufferOffset, valueCount)

        /*
        // TODO support for multiple channels.
        if (fmtChunk.numChannels > 1) {
            /*
                if self._fmt_header.num_channels > 1:
                    actual_samples_read = int(actual_values_read / self._fmt_header.num_channels)
                    data = data.reshape((actual_samples_read, self._fmt_header.num_channels))
             */
        }
         */

        if ((actualSamplesRead * bytesPerSample) % 2 == 1) {
            raFile.skipBytes(1)
        }

        return actualSamplesRead
    }

    private fun skimDataChunk(raFile: RandomAccessFile, filename: String, fmtChunk: FmtChunkInfo)
            : DataChunkInfo {
        val dataByteCount = readInt32(raFile, filename, "ChunkSize")

        // Note where the data starts so we can come back for it later.
        startOfData = raFile.filePointer

        // Figure out the number of values we expect to find:
        val valueCount: Int = (dataByteCount * 8 / fmtChunk.bitsPerSample).toInt()
        val expectedSampleCount: Int = (valueCount / fmtChunk.numChannels).toInt()
        var (minValue, maxValue) = Pair<Short?, Short?>(null, null)

        /**
         * Skim though the data, in chunks to keep a lid on memory usage.
         * Track the min and max values as we go.
         */
        val portionSize = 50000 // # 50000 x 2 bytes is about 100K
        var samplesRead = 0

        // Preallocate a buffer that is big enough:
        val dataBuffer = ShortArray(portionSize * fmtChunk.numChannels)

        while (samplesRead < expectedSampleCount) {
            val count = minOf(expectedSampleCount - samplesRead, portionSize)
            val actualPortionCount = readData(
                raFile,
                fmtChunk,
                Pair(samplesRead, samplesRead + count),
                dataBuffer
            )
            samplesRead += actualPortionCount

            val (min, max) = Pair(dataBuffer.minOrNull(), dataBuffer.maxOrNull())
            min?.let { minValue = minOf<Short>(minValue ?: it, it) }
            max?.let { maxValue = maxOf<Short>(maxValue ?: it, it) }

            if (dataBuffer.size < count) {
                break      // We hit the end.
            }
        }

        // Sometimes headers are wrong, because they are written in advance of the data.
        // So, note the actual sample count. This is what we will use.
        val actualSampleCount = samplesRead

        // We have been seeking around in the file to read data, so we need
        // to seek to the end of data chunk now to not confuse the caller:
        startOfData?.let {
            raFile.seek(it + dataByteCount)
            if (dataByteCount % 2 == 1) {
                // Allow for padding to an even length. Actually this should never happen.
                raFile.skipBytes(1)
            }
        }

        return DataChunkInfo(
            actualSampleCount,
            Pair(minValue ?: Short.MIN_VALUE, maxValue ?: Short.MAX_VALUE),
            dataByteCount
        )
    }

    private fun readFormatChunk(raFile: RandomAccessFile, filename: String): FmtChunkInfo {
        val chunkSize = readInt32(raFile, filename, "fmt SubchunkSize")
        val formatTag = readInt16(raFile, filename, "AudioFormat")

        var numChannels = 0.toShort()
        var sampleRateHz = 0
        var byteRate = 0
        var blockAlign = 0.toShort()
        var bitsPerSample = 0.toShort()
        var requiredChunkSize = 0

        when (formatTag) {
            1.toShort() -> {
                // PCM no compression.
                requiredChunkSize = 14 + 2  // More data may be supplied, we will ignore it.
                checkValue(
                    filename,
                    "fmt SubchunkSize",
                    chunkSize,
                    "must be >= $requiredChunkSize"
                ) { v: Int ->
                    v >= requiredChunkSize
                }
                numChannels = readInt16(raFile, filename, "NumChannels")
                sampleRateHz = readInt32(raFile, filename, "SampleRate")
                byteRate = readInt32(raFile, filename, "ByteRate")
                blockAlign = readInt16(raFile, filename, "BlockAlign")
                bitsPerSample = readInt16(raFile, filename, "BitsPerSample")

            }

            65534.toShort() -> {
                // WAVE_FORMAT_EXTENSIBLE
                // This limited support is reverse engineered from sample files,
                // and from https://www.jensign.com/riffparse/

                requiredChunkSize = 36 // More data may be supplied, we will ignore it.
                checkValue(
                    filename,
                    "fmt SubchunkSize",
                    chunkSize,
                    "must be >= $requiredChunkSize"
                ) { v: Int ->
                    v >= requiredChunkSize
                }

                numChannels = readInt16(raFile, filename, "NumChannels")
                sampleRateHz = readInt32(raFile, filename, "SampleRate")
                byteRate = readInt32(raFile, filename, "ByteRate")
                blockAlign = readInt16(raFile, filename, "BlockAlign")
                bitsPerSample = readInt16(raFile, filename, "BitsPerSample")
                /*val cbSize = */ readInt16(raFile, filename, "ExtraSize")
                /*val validBitsPerSample = */ readInt16(raFile, filename, "validBitsPerSample")
                /* val channelMask = */ readInt32(raFile, filename, "ChannelMask")
                // Read and validate the 12 byte sub format in 3 pieces:
                /*val subformat1 = */ readInt32(
                    raFile,
                    filename,
                    "Subformat1",
                    expected = 0x00000001
                )
                /* val subformat2 = */ readInt32(
                    raFile,
                    filename,
                    "Subformat2",
                    expected = 0x00100000
                )
                /* val subformat3 = */ readInt32(
                    raFile, filename, "Subformat3",
                    expected = (0xAA000080.toLong() and 0xFFFFFFFF).toInt()
                )
            }

            else -> {
                throw WavFileException("Unknown wav file format: $formatTag.")
            }
        }

        /**
         * Sanity checks.
         */

        checkValue(filename, "NumChannels", numChannels, "must be > 0") { v: Short ->
            v > 0
        }
        checkValue(filename, "SampleRate", sampleRateHz, "must be > 0") { v: Int ->
            v > 0
        }
        checkValue(filename, "BitsPerSample", bitsPerSample, "must be 16 bits") { v: Short ->
            v == 16.toShort()
        }

        // Does it really matter if we get unexpected values for the following?

        val expectedI = (sampleRateHz * numChannels * bitsPerSample / 8).toInt()
        checkValue(filename, "ByteRate", byteRate, "must be $expectedI") { v: Int ->
            v == expectedI
        }

        val expectedS = (numChannels * bitsPerSample / 8).toShort()
        checkValue(filename, "BlockAlign", blockAlign, "must be $expectedS") { v: Short ->
            v == expectedS
        }

        /*
            Read and discard any extra data on the end of the chunk. Sometimes this is present, like this
            example:

                00000000  52 49 46 46 48 4f 4c 00  57 41 56 45 66 6d 74 20  |RIFFHOL.WAVEfmt |
                00000010  44 00 00 00 01 00 01 00  20 a1 07 00 40 42 0f 00  |D....... ...@B..|
                00000020  02 00 10 00 4d 4d 4d 4d  4d 4d 4d 4d 4d 28 23 35  |....MMMMMMMMM(#5|
                00000030  30 30 30 30 30 23 29 3c  26 31 26 3e 5b 21 35 30  |00000#)<&1&>[!50|
                00000040  30 21 5d 4c 6f 63 61 74  69 6f 6e 5b 53 42 4d 4d  |0!]Location[SBMM|
                00000050  4d 4d 4d 4d 4d 4d 4d 00  64 61 74 61 40 47 4c 00  |MMMMMMM.data@GL.|
        */

        val excessData = chunkSize - requiredChunkSize
        if (excessData > 0) {
            Log.i(this::class.simpleName, "Discarding $excessData excess bytes from the end of fmt")
            raFile.skipBytes(excessData)
        }

        if (chunkSize % 2 == 1) {
            // Odd chunks are padded to even:
            raFile.skipBytes(1)
        }

        return FmtChunkInfo(numChannels, sampleRateHz, bitsPerSample)
    }

    private fun <T> checkValue(
        filename: String,
        fieldName: String,
        value: T,
        text: String,
        check: (T) -> Boolean
    ) {
        if (!check(value)) {
            throw WavFileException("Unexpected value for $fieldName in $filename: found $value: $text.")
        }
    }

    private fun readTextBytes(
        raFile: RandomAccessFile,
        filename: String,
        length: Int,
        fieldName: String,
        expected: String? = null
    ): String {
        val b = ByteArray(length)
        raFile.read(b)      // May read fewer than the number of bytes required.
        val found = String(b, Charsets.UTF_8)
        expected?.let {
            if (found != expected) {
                throw WavFileException(
                    "Unexpected value for $fieldName in $filename: found $found, expected $expected."
                )
            }
        }
        return found
    }

    private fun readInt32(
        raFile: RandomAccessFile,
        filename: String,
        fieldName: String,
        expected: Int? = null
    ): Int {
        val b = ByteArray(4)
        val count = raFile.read(b)      // May read fewer than the number of bytes required.
        if (count != 4) {
            throw EOFException()
        }
        val found = (b[3].toInt() and 0xFF shl 24) or
                (b[2].toInt() and 0xFF shl 16) or
                (b[1].toInt() and 0xFF shl 8) or
                (b[0].toInt() and 0xFF)

        expected?.let {
            if (found != expected) {
                throw WavFileException(
                    "Unexpected value for $fieldName in $filename: found $found, expected $expected."
                )
            }
        }
        return found
    }

    private fun readInt16(
        raFile: RandomAccessFile,
        filename: String,
        fieldName: String,
        expected: Short? = null
    ): Short {
        val b = ByteArray(2)
        val count = raFile.read(b)      // May read fewer than the number of bytes required.
        if (count != 2) {
            throw EOFException()
        }
        val found: Short = ((b[1].toInt() and 0xFF shl 8) or
                (b[0].toInt() and 0xFF)).toShort()

        expected?.let {
            if (found != expected) {
                throw WavFileException(
                    "Unexpected value for $fieldName in $filename: found $found, expected $expected."
                )
            }
        }
        return found
    }

    /**
     *  Consume and ignore an entire WAV file chunk - for example, because its type is unknown.
     */
    private fun skipChunk(raFile: RandomAccessFile, filename: String) {
        var bytesToSkip = readInt32(raFile, filename, "fmt SubchunkSize")
        if (bytesToSkip % 2 == 1) {
            // Wav standard says to round up to even lengths:
            bytesToSkip += 1
        }

        raFile.skipBytes(bytesToSkip)
    }
}