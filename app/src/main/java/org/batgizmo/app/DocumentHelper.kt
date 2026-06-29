package org.batgizmo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber

class DocumentHelper {

    data class UriData(val uri: Uri, val previousAvailable: Boolean, val nextAvailable: Boolean)

    companion object {

        // SAF provider that exposes shared/external storage; used to hint an initial folder.
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /**
         * A documents-provider URI pointing at the app's public recordings folder under Documents,
         * suitable as an EXTRA_INITIAL_URI hint for the document picker. The folder need not exist:
         * if it doesn't, the picker silently ignores the hint and opens at its default location.
         */
        private fun appRecordingsFolderUri(): Uri {
            val documentId =
                "primary:${Environment.DIRECTORY_DOCUMENTS}/${FileWriter.PUBLIC_FOLDER_NAME}"
            return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
        }

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
            // Hint the picker to open at the app's recordings folder, but only the first time it
            // is launched in this app run; subsequent launches let the picker reopen wherever the
            // user last browsed:
            val initialUri = remember { appRecordingsFolderUri() }

            val launcher = rememberLauncherForActivityResult(
                // We use OpenDocument rather then GetContent is it allows multiple
                // MIME types:
                contract = InitialFolderOpenDocuments(documentHelper, initialUri)
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

        /**
         * An OpenMultipleDocuments contract that hints the picker to start at [initialUri] the
         * first time it is launched in this app run (see [consumeInitialFolderHint]).
         */
        private class InitialFolderOpenDocuments(
            private val documentHelper: DocumentHelper,
            private val initialUri: Uri
        ) : ActivityResultContracts.OpenMultipleDocuments() {
            override fun createIntent(context: Context, input: Array<String>): Intent {
                val intent = super.createIntent(context, input)
                if (documentHelper.consumeInitialFolderHint()) {
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
                }
                return intent
            }
        }
    }

    private var uriList: List<Uri>? = null
    private var currentIndex: Int? = null

    // True until the document picker has been launched once in this app run, so we only steer it
    // to the app folder on first use and respect the picker's own memory afterwards:
    private var initialFolderHintPending = true

    /** Returns true at most once per app run, the first time the document picker is launched. */
    private fun consumeInitialFolderHint(): Boolean {
        if (!initialFolderHintPending)
            return false
        initialFolderHintPending = false
        return true
    }

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
