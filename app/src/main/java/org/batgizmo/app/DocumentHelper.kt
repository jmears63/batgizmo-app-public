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

    data class UriData(val uri: Uri, val previousAvailable: Boolean, val nextAvailable: Boolean)

    companion object {

        /**
         * Get the display name for a file given its Uri.
         */
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

        /**
         * Create a document picker that the UI can use to have the user select
         * one or more documents.
         */
        @Composable
        fun composeDocumentPickerLauncher(
            documentHelper: DocumentHelper,
            onSelection: (UriData) -> Unit
        )
            : ManagedActivityResultLauncher<Array<String>, List<Uri>> {

            val context = LocalContext.current

            /*
             * We use OpenMultipleDocuments rather than the folder picker, because the folder picker
             * doesn't work on MyDrive or USB drives. OpenMultipleDocuments allows them to
             * be browsed, and as a "select all" feature that does the job of opening
             * the entire folder.
             */
            val launcher = rememberLauncherForActivityResult(
                // We use OpenDocument rather then GetContent is it allows multiple
                // MIME types:
                contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { list: List<Uri> ->

                // Timber.d("rememberLauncherForActivityResult callback invoked: list.size = ${list.size}")

                val initialUriData = documentHelper.initDocumentState(context, list)

                initialUriData?.let { it ->
                    val filename = getFileName(context, it.uri)
                    Timber.i("Selected file: $filename")

                    // The following callback calls resetUIMode():
                    onSelection(it)
                }
            }

            return launcher
        }
    }

    private var uriList: List<Uri>? = null
    private var currentIndex: Int? = null

    init {
        reset()
    }

    /**
     * Initialise based on the list of Uris provided,
     */
    private fun initDocumentState(context: Context, theList: List<Uri>): UriData? {
        reset()

        // Shallow copy:
        uriList = theList.toMutableList()

        // Timber.d("rememberLauncherForActivityResult callback invoked: uriList.size = ${uriList?.size ?: "null"}")

        uriList?.let { list ->
            if (list.isNotEmpty()) {
                val i = 0
                currentIndex = i
                // Timber.d("Setting currentIndex in initDocumentState() = $currentIndex")
                return UriData(list[i], i > 0, i < list.size - 1)
            }
        }
        return null
    }

    fun reset() {
        uriList = null
        currentIndex = null
        // Timber.d("Setting currentIndex in reset() = $currentIndex")
    }

    fun getPreviousFile(): UriData? {
        currentIndex?.let { i ->
            uriList?.let { list ->
                if (i > 0) {
                    val previous = i - 1
                    currentIndex = previous
                    // Timber.d("Setting currentIndex in getPreviousFile() = $currentIndex")
                    return UriData(list[previous], previous > 0, previous < list.size - 1)
                }
            }
        }
        return null
    }

    fun getNextFile(): UriData? {
        currentIndex?.let { i ->
            uriList?.let { list ->
                if (i < list.size - 1) {
                    val next = i + 1
                    currentIndex = next
                    // Timber.d("Setting currentIndex in getNextFile() = $currentIndex")
                    return UriData(list[next], next > 0, next < list.size - 1)
                }
            }
        }
        return null
    }
}
