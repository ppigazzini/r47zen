package io.github.ppigazzini.r47zen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException

internal class StorageAccessCoordinator(
    private val activity: ComponentActivity,
    private val onNativeFileSelected: (fd: Int, name: String?) -> Unit,
    private val onNativeFileCanceled: () -> Unit,
    private val onLoadTransferStarted: () -> Unit = {},
    private val documentTransfer: DocumentTransfer = DocumentTransfer.shared(activity),
    private val readWorkDirectoryTreeUri: () -> String? = {
        WorkDirectory.readTreeUriString(activity)
    },
    private val resolveInitialUri: (String?, Int) -> Uri? = { treeUriString, fileType ->
        WorkDirectory.resolveSubfolder(
            contentResolver = activity.contentResolver,
            treeUriString = treeUriString,
            fileType = fileType,
        )
    },
    private val isWorkDirectoryAccessible: (String?) -> Boolean = { treeUriString ->
        WorkDirectory.isAccessible(activity.contentResolver, treeUriString)
    },
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

    fun requestNativeFile(isSave: Boolean, defaultName: String, fileType: Int) {
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
            launcher(buildNativeFileRequestIntent(isSave, defaultName, fileType))
        } catch (error: Exception) {
            Log.e(TAG, "Failed to launch SAF", error)
            onNativeFileCanceled()
        }
    }

    internal fun buildNativeFileRequestIntent(
        isSave: Boolean,
        defaultName: String,
        fileType: Int,
    ): Intent {
        val treeUriString = readWorkDirectoryTreeUri()
        val initialUri = if (treeUriString != null && isWorkDirectoryAccessible(treeUriString)) {
            resolveInitialUri(treeUriString, fileType)
        } else {
            null
        }
        return if (isSave) {
            buildSaveIntent(defaultName, initialUri)
        } else {
            buildLoadIntent(initialUri)
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

        onLoadTransferStarted()
        documentTransfer.load(uri, onNativeFileSelected, onNativeFileCanceled)
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
