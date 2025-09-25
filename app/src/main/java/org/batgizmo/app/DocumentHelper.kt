package org.batgizmo.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber

class DocumentHelper {

    fun getFileName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        var fileName: String? = null

        cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && it.moveToFirst()) {
                fileName = it.getString(nameIndex)  // Extract filename
            }
        }

        return fileName
    }

    @Composable
    fun composeDocumentPickerLauncher(onSelection: (Uri) -> Unit, onError: (String) -> Unit)
        : ManagedActivityResultLauncher<Array<String>, List<Uri>> {

        val context = LocalContext.current

        val launcher = rememberLauncherForActivityResult(
            // We use OpenDocument rather then GetContent is it allows multiple
            // MIME types:
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uriList: List<Uri> ->

            // Close the menu:
            // Use the first selection, if any:
            val uri = uriList.firstOrNull()
            uri?.let { it ->
                val filename = getFileName(context, it)
                Timber.i("Selected file: $filename")
                if (filename?.lowercase()?.endsWith(".wav") == true)
                    onSelection(it)
                else
                    onError("Sorry, only .wav files are supported.")
            }
        }

        return launcher
    }
}