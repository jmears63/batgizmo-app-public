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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import org.batgizmo.app.ui.TopLevelUI
import timber.log.Timber

/**
 * A data store for persistent storage of settings.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "preferences")

class MainActivity : ComponentActivity() {

    /**
     * Get the ViewModel that we use to contain non-UI logic and persistent data
     * that survives UI reconfigurations. The following line creates a single instance
     * and returns that same instance following UI configurations such as rotation.
     */
    private val viewModel: UIModel by viewModels()
    private lateinit var uiTopLevel: TopLevelUI

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Timber.i("Location permission granted: $granted")
            if (granted) {
                viewModel.locationTracker.requestOneTimeLocation()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()

        super.onCreate(savedInstanceState)



        // Create the model exactly once. If we are not careful below concurrency
        // and races can result in more than one instance being created.
        // We use a factory so that we can pass constructor parameters to the model.
        val factory = UIModelFactory(application, dataStore)
        val model = ViewModelProvider(this, factory).get(UIModel::class.java)

        // Suppress the high-rate mic startup helper when opened via VIEW (e.g. a .wav).
        if (intent?.action == Intent.ACTION_VIEW) {
            model.noteLaunchedForFileView()
        }

        requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)

        // RECORD_AUDIO is requested when the user connects with the internal microphone.

        // Tell Android we'll manage insets:
        WindowCompat.setDecorFitsSystemWindows(window, false)

        uiTopLevel = TopLevelUI(model)

        setContent {
            // We have to remember if the intent has been processed, to avoid processing
            // it again if the device is rotated. Duh.
            var intentProcessed by rememberSaveable { mutableStateOf(false) }
            if (!intentProcessed) {
                handleIncomingIntent(intent)
                intentProcessed = true
            }

            uiTopLevel.Compose(model)
        }

        /*
         * Important: this code must come *after* setContent to avoid a null pointer
         * exception. Me neither.
         *
         * Use AndroidX WindowInsetsControllerCompat so this works on API 29 as well
         * as API 30+ (platform WindowInsetsController is API 30-only).
         */
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Avoid annoying bounce of the app display on first display, and return
            // from the document selector:
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            Timber.i("handleIncomingIntent called.")   // This gets called repeatedly.
            val fileUri: Uri? = intent.data
            fileUri?.let { uri ->
                uiTopLevel.processViewIntent(this, lifecycleScope, viewModel, uri)
            }
        }
    }
}
