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

import android.app.Application
import android.content.Context
import android.util.Log
import timber.log.Timber
import uk.org.gimell.batgimzoapp.BuildConfig
import java.io.PrintWriter
import java.io.StringWriter

class LoggingExceptionHandler(
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            diagnosticLogger.log { buildLog(throwable) }
        } catch (e: Exception) {
            // You can log this to Logcat as a fallback
        } finally {
            // Delegate to the default handler (to let the system show crash dialog etc.)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildLog(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return """
            |Uncaught exception in thread ${Thread.currentThread().name}
            |${sw}
            """.trimMargin()
    }
}

class ReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Ignore debug and verbose logs
        if (priority == Log.VERBOSE || priority == Log.DEBUG)
            return

        /*
        // Optionally send warnings and errors to Firebase Crashlytics
        FirebaseCrashlytics.getInstance().log("$tag: $message")
        t?.let { FirebaseCrashlytics.getInstance().recordException(it) }
         */
    }
}

class BatGizmoApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree()) // Full logging in debug
        } else {
            Timber.plant(ReleaseTree())      // Restricted logging in release
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val handler = LoggingExceptionHandler(defaultHandler, this)

        Thread.setDefaultUncaughtExceptionHandler(handler)
    }
}