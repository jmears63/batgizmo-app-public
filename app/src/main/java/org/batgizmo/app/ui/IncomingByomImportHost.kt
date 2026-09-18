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

package org.batgizmo.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.batgizmo.app.UIModel
import org.batgizmo.app.ml.ByomCommitException
import org.batgizmo.app.ml.MlCatalog
import timber.log.Timber

/**
 * Handles BYOM zips opened via VIEW / SEND: peek, optional overwrite confirm,
 * then [UIModel.importByomClassifier].
 */
@Composable
fun IncomingByomImportHost(model: UIModel) {
    val incoming by model.incomingByomZip.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var successLabel by remember { mutableStateOf<String?>(null) }
    var pendingOverwrite by remember {
        mutableStateOf<IncomingByomOverwrite?>(null)
    }

    fun versionLabel(version: String?): String =
        version?.takeIf { it.isNotBlank() } ?: "unknown"

    suspend fun runImport(uri: Uri, replaceExisting: Boolean) {
        busy = true
        error = null
        try {
            val installed = model.importByomClassifier(uri, replaceExisting)
            successLabel = installed.familyDisplayName
        } catch (e: Exception) {
            Timber.e(e, "Incoming BYOM import failed")
            error = when (e) {
                is ByomCommitException ->
                    e.message ?: "Failed to install classifier"
                else ->
                    e.message ?: e.javaClass.simpleName
            }
        } finally {
            busy = false
            model.consumeIncomingByomZip()
        }
    }

    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        pendingOverwrite = null
        successLabel = null
        busy = true
        error = null
        try {
            withContext(Dispatchers.IO) {
                MlCatalog.ensureInitialized(appContext)
            }
            val summary = withContext(Dispatchers.IO) {
                MlCatalog.peekByomZip(appContext, uri)
            }
            val existing = MlCatalog.installedByomSummary(summary.familyId)
            if (existing != null) {
                busy = false
                pendingOverwrite = IncomingByomOverwrite(
                    uri = uri,
                    incoming = summary,
                    existingVersionName = existing.versionName,
                )
            } else {
                runImport(uri, replaceExisting = false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Incoming BYOM peek failed")
            busy = false
            error = e.message ?: e.javaClass.simpleName
            model.consumeIncomingByomZip()
        }
    }

    if (busy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing classifier") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Please wait…")
                }
            },
            confirmButton = {},
        )
    }

    pendingOverwrite?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pendingOverwrite = null
                model.consumeIncomingByomZip()
            },
            title = { Text("Replace classifier?") },
            text = {
                Text(
                    "\"${pending.incoming.familyId}\" " +
                        "(version ${versionLabel(pending.existingVersionName)}) " +
                        "is already installed. Replace it with version " +
                        "${versionLabel(pending.incoming.versionName)}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pending.uri
                        pendingOverwrite = null
                        scope.launch {
                            runImport(uri, replaceExisting = true)
                        }
                    }
                ) {
                    Text("Overwrite")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingOverwrite = null
                        model.consumeIncomingByomZip()
                    }
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Import failed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }) {
                    Text("OK")
                }
            },
        )
    }

    successLabel?.let { name ->
        AlertDialog(
            onDismissRequest = { successLabel = null },
            title = { Text("Classifier installed") },
            text = {
                Text("\"$name\" is ready under Auto Id in Settings.")
            },
            confirmButton = {
                TextButton(onClick = { successLabel = null }) {
                    Text("OK")
                }
            },
        )
    }
}

private data class IncomingByomOverwrite(
    val uri: Uri,
    val incoming: MlCatalog.ByomManifestSummary,
    val existingVersionName: String?,
)
