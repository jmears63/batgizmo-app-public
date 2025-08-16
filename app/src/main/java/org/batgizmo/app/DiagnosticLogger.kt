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
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

val diagnosticLogger = DiagnosticLogger()

class DiagnosticLogger {
    companion object {
        private const val LARGE_FILE_SIZE = 50000
    }

    private val logTag = this::class.simpleName

    private val logFileName = "batgizmo_log.txt"
    private var logWriter: BufferedWriter? = null

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneOffset.UTC)
    private fun getUtcTimestamp(): String = formatter.format(Instant.now())

    private fun getFile(context: Context): File = File(context.filesDir, logFileName)

    fun startLogging(context: Context, append: Boolean = true) {
        // Idempotent:
        if (logWriter == null) {
            val f = getFile(context)
            logWriter = BufferedWriter(FileWriter(f, append))
            log { "Log started at ${System.currentTimeMillis()}" }
        }
    }

    fun stopLogging() {
        log { "Log stopped at ${System.currentTimeMillis()}" }
        // Idempotent:
        logWriter?.let {
            try {
                it.flush()
                it.close()
            } catch (e: IOException) {
                Log.w(logTag, "Unable to shutdown diagnostic logging cleanly: $e")
            }
        }
        logWriter = null
    }

    /**
     * The message to log is provided as lambda to avoid overheads when we are not logging.
     *
     * Note that this function flushes the log to file on each log, so don't log too often.
     */
    fun log(messageProducer: () -> String) {
        // If logging is not currently active, do nothing.
        logWriter?.let {
            try {
                val timestamp = getUtcTimestamp()
                val message = messageProducer()
                logWriter?.apply {
                    val lines = message.lines()

                    if (lines.isNotEmpty()) {
                        // Write first line with timestamp
                        write("[$timestamp] ${lines[0]}\n")

                        // Write subsequent lines with tab prefix
                        for (i in 1 until lines.size) {
                            write("\t${lines[i]}\n")
                        }
                    }
                    // Flush each time we log, because we can't reliably
                    // hook app shutdown:
                    it.flush()
                }
            } catch (e: IOException) {
                Log.w(logTag, "Unable to log diagnosticLogger: $e")
            }
        }
    }

    fun logFileExists(context: Context): Boolean {
        val f = getFile(context)
        return f.exists()
    }

    fun logFileIsLarge(context: Context): Boolean {
        val f = getFile(context)
        // This results in 0 if the file doesn't exist.
        val length = f.length()
        return length > DiagnosticLogger.LARGE_FILE_SIZE
    }

    private fun appendUtcTimestampToSubject(subject: String): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
            .withZone(ZoneOffset.UTC)  // important to set zone here
        val timeStampText = formatter.format(Instant.now())
        return "${subject}_$timeStampText"
    }

    fun shareDiagnosticsLog(context: Context) {

        logWriter?.flush()

        val file = getFile(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val subjectWithTimestamp = appendUtcTimestampToSubject("BatGizmo DiagnosticLogger")

        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subjectWithTimestamp)
            putExtra(Intent.EXTRA_TEXT, "Please find the attached BatGizmo app diagnosticLogger data.")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(emailIntent, "Share Batgizmo DiagnosticLogger")
        )
    }

    fun clearData(context: Context) {
        if (logWriter == null) {
            val file = getFile(context)
            // Fails quietly if there is no file, or for some reason we
            // can't delete it:
            file.delete()
        }
        else {
            // Close and reopen the file, truncating it:
            stopLogging()
            startLogging(context, append = false)
        }
    }

    /**
     * Called when the model is finished with.
     */
    fun shutdown() {
        stopLogging()
    }
}