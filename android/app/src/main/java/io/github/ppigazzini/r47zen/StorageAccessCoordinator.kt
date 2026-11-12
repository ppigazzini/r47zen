package io.github.ppigazzini.r47zen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import java.io.IOException

internal class StorageAccessCoordinator(
    private val activity: ComponentActivity,
    private val onNativeFileSelected: (fd: Int, name: String?) -> Unit,
    private val onNativeFileCanceled: () -> Unit,
    // A load's copy reaches native from the transfer worker, with the ticket its start
    // gave, so a copy that outlives its request answers no other.
    private val onLoadTransferStarted: () -> Long = { 0L },
    private val onLoadTransferred: (ticket: Long, fd: Int, name: String?) -> Unit =
        { _, fd, name -> onNativeFileSelected(fd, name) },
    private val onLoadTransferFailed: (ticket: Long) -> Unit = { onNativeFileCanceled() },
    private val documentTransfer: DocumentTransfer = DocumentTransfer.shared(activity),
    providedSaveIntentLauncher: ((Intent) -> Unit)? = null,
    providedLoadIntentLauncher: ((Intent) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "R47StorageAccess"
    }

    private var saveIntentLauncher: ((Intent) -> Unit)? = providedSaveIntentLauncher

    private var loadIntentLauncher: ((Intent) -> Unit)? = providedLoadIntentLauncher

    fun registerLaunchers() {
        if (saveIntentLauncher == null) {
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                deliverSaveResult(result.resultCode, result.data?.data)
            }
            saveIntentLauncher = launcher::launch
        }

        if (loadIntentLauncher == null) {
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                deliverLoadResult(result.resultCode, result.data?.data)
            }
            loadIntentLauncher = launcher::launch
        }
    }

    fun requestNativeFile(isSave: Boolean, defaultName: String, start: Uri?) {
        val launcher = if (isSave) {
            checkNotNull(saveIntentLauncher) {
                "StorageAccessCoordinator.registerLaunchers() must be called before requesting files."
            }
        } else {
            checkNotNull(loadIntentLauncher) {
                "StorageAccessCoordinator.registerLaunchers() must be called before requesting files."
            }
        }

        try {
            launcher(buildNativeFileRequestIntent(isSave, defaultName, start))
        } catch (error: Exception) {
            Log.e(TAG, "Failed to launch SAF", error)
            onNativeFileCanceled()
        }
    }

    internal fun buildNativeFileRequestIntent(isSave: Boolean, defaultName: String, start: Uri?): Intent {
        return if (isSave) {
            buildSaveIntent(defaultName, start)
        } else {
            buildLoadIntent(start)
        }
    }

    internal fun deliverSaveResult(resultCode: Int, uri: Uri?) {
        if (resultCode != Activity.RESULT_OK || uri == null) {
            onNativeFileCanceled()
            return
        }

        try {
            onNativeFileSelected(documentTransfer.openForSave(uri), null)
        } catch (error: IOException) {
            Log.e(TAG, "Failed to open the save pipe", error)
            onNativeFileCanceled()
        }
    }

    internal fun deliverLoadResult(resultCode: Int, uri: Uri?) {
        if (resultCode != Activity.RESULT_OK || uri == null) {
            onNativeFileCanceled()
            return
        }

        val ticket = onLoadTransferStarted()
        documentTransfer.load(
            uri,
            { fd, name -> onLoadTransferred(ticket, fd, name) },
            { onLoadTransferFailed(ticket) },
        )
    }

    private fun buildSaveIntent(defaultName: String, initialUri: Uri?): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                defaultName.endsWith(".bmp") -> "image/bmp"
                defaultName.endsWith(".rtf") -> "application/rtf"
                defaultName.endsWith(".s47") -> "application/octet-stream"
                defaultName.endsWith(".p47") -> "application/octet-stream"
                defaultName.endsWith(".sav") -> "application/octet-stream"
                defaultName.endsWith(".d47") -> "application/octet-stream"
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_TITLE, defaultName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }

    private fun buildLoadIntent(initialUri: Uri?): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }
}

/**
 * Find the folder a picker starts in: the work directory's folder for the file type,
 * made for a save, which writes there, and only found for a load, which a user may
 * cancel and must leave no folder behind; with no such folder, the work directory.
 * Every call asks a provider, so it never runs on the main thread.
 */
internal class PickerStart(
    private val readTreeUri: () -> String?,
    private val isAccessible: (treeUri: String) -> Boolean,
    private val resolveFolder: (treeUri: String, fileType: Int, create: Boolean) -> Uri?,
) {
    fun find(fileType: Int, isSave: Boolean): Uri? {
        return readTreeUri()?.takeIf(isAccessible)?.let { tree ->
            resolveFolder(tree, fileType, isSave) ?: tree.toUri()
        }
    }

    companion object {
        fun of(context: Context): PickerStart {
            val resolver = context.contentResolver
            return PickerStart(
                readTreeUri = { WorkDirectory.readTreeUriString(context) },
                isAccessible = { WorkDirectory.isAccessible(resolver, it) },
                resolveFolder = { treeUri, fileType, create ->
                    WorkDirectory.resolveSubfolder(resolver, treeUri, fileType, create = create)
                },
            )
        }
    }
}
