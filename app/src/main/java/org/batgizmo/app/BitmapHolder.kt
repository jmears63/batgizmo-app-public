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

import android.graphics.Bitmap
import java.util.concurrent.Semaphore

/**
 * This class has a simple job which is to own the bitmap, if any, that is currently
 * being used to render the UI. A single global instance of this holder contains the reference
 * to the bitmap, which can therefore be updated an become immediately available to
 * parts of the code that need it.
 *
 * Client code that accesses bitmapHolders from multiple threads need to provide
 * synchronization - this class doesn't provide it.
 */
class BitmapHolder {
    /** Used to signal that the bitmap has been updated and a redraw is therefore due. */
    private val spectrogramUpdateSemaphore = Semaphore(0)

    /**
     * The bitmap, if any, that is being shared between the pipeline and the graph
     * renderer.
     *
     * The bitmap is accessed from both the pipeline thread and the renderer thread.
     * Therefore, both those threads must synchronize access based on the holder object
     * (not the bitmap object, as we might be able to null it).
     *
     *  synchronized (holder) {
     *      // Access the bitmap here for read and write.
     *  }
     */
    var bitmap: Bitmap? = null

    /**
     * If a cursor should be displayed, set this to the time position required.
     */
    var cursorTime: Float? = null

    /**
     * This method is thread safe.
     *
     * This method is called by the code that updates the bitmap to signal to the
     * renderer that it is time re-render the screen.
     */
    fun signalUpdate() {
        // Log.d(this::class.simpleName, "signalUpdate called")
        spectrogramUpdateSemaphore.release()
    }

    /**
     * This method is thread safe.
     *
     * This method is called by the code that renders the bitmap to the screen to block
     * until an update is required.
     */
    fun waitForUpdate() {
        spectrogramUpdateSemaphore.acquire()
        // Log.d(this::class.simpleName, "Semaphore queue length before drain is ${dataUpdateSemaphore.queueLength}")
        // Prevent a backlog building up (but note the potential race condition that might miss an update):
        spectrogramUpdateSemaphore.drainPermits()
        // Log.d(this::class.simpleName, "Semaphore queue length is ${dataUpdateSemaphore.queueLength}")
    }

}