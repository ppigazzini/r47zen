package com.example.r47

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

internal typealias FirstRunPromptPresenter = (
    onSelectFolder: () -> Unit,
    onLater: () -> Unit,
) -> Unit

internal typealias MissingWorkDirectoryPromptPresenter = (
    message: String,
    onSet: () -> Unit,
) -> Unit

internal class StorageAccessCoordinator(
    private val activity: ComponentActivity,
    private val appPreferences: SharedPreferences,
    private val rootView: View,
    private val onNativeFileSelected: (Int) -> Unit,
    private val onNativeFileCancelled: () -> Unit,
    private val openFileDescriptor: (Uri, String) -> ParcelFileDescriptor? = { uri, mode ->
        activity.contentResolver.openFileDescriptor(uri, mode)
    },
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
    private val persistSelectedWorkDirectory: (Uri) -> String = { uri ->
        WorkDirectory.persistSelectedTreeUri(activity, uri)
    },
    private val providedSaveIntentLauncher: ((Intent) -> Unit)? = null,
    private val providedLoadIntentLauncher: ((Intent) -> Unit)? = null,
    private val providedWorkDirectoryIntentLauncher: ((Uri?) -> Unit)? = null,
    private val showFirstRunPrompt: FirstRunPromptPresenter? = null,
    private val showWorkDirectoryMissingPrompt: MissingWorkDirectoryPromptPresenter? = null,
) {
    companion object {
        private const val TAG = "R47StorageAccess"
    }

    private var saveIntentLauncher: ((Intent) -> Unit)? = providedSaveIntentLauncher

    private var loadIntentLauncher: ((Intent) -> Unit)? = providedLoadIntentLauncher

    private var workDirectoryIntentLauncher: ((Uri?) -> Unit)? = providedWorkDirectoryIntentLauncher

    fun registerLaunchers() {
        if (saveIntentLauncher == null) {
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                deliverNativeFileResult(
                    resultCode = result.resultCode,
                    uri = result.data?.data,
                    mode = "wt",
                )
            }
            saveIntentLauncher = launcher::launch
        }

        if (loadIntentLauncher == null) {
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                deliverNativeFileResult(
                    resultCode = result.resultCode,
                    uri = result.data?.data,
                    mode = "r",
                )
            }
            loadIntentLauncher = launcher::launch
        }

        if (workDirectoryIntentLauncher == null) {
            val launcher = activity.registerForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                deliverWorkDirectoryResult(uri)
            }
            workDirectoryIntentLauncher = launcher::launch
        }
    }

    fun handleResume() {
        val isFirstRun = appPreferences.getBoolean("first_setup", true)
        val hasWorkDirectory = readWorkDirectoryTreeUri() != null

        if (isFirstRun && !hasWorkDirectory) {
            showFirstRunDialog()
        } else {
            validateWorkDirectory()
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
            onNativeFileCancelled()
        }
    }

    fun requestWorkDirectory() {
        val launcher = checkNotNull(workDirectoryIntentLauncher) {
            "StorageAccessCoordinator.registerLaunchers() must be called before requesting the work directory."
        }

        try {
            launcher(null)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to launch work directory picker", error)
        }
    }

    internal fun buildNativeFileRequestIntent(
        isSave: Boolean,
        defaultName: String,
        fileType: Int,
    ): Intent {
        val initialUri = resolveInitialUri(readWorkDirectoryTreeUri(), fileType)
        return if (isSave) {
            buildSaveIntent(defaultName, initialUri)
        } else {
            buildLoadIntent(initialUri)
        }
    }

    internal fun deliverNativeFileResult(resultCode: Int, uri: Uri?, mode: String) {
        if (resultCode != Activity.RESULT_OK || uri == null) {
            onNativeFileCancelled()
            return
        }

        try {
            openFileDescriptor(uri, mode)?.use { fileDescriptor ->
                onNativeFileSelected(fileDescriptor.detachFd())
            } ?: onNativeFileCancelled()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to open selected SAF file", error)
            onNativeFileCancelled()
        }
    }

    internal fun deliverWorkDirectoryResult(uri: Uri?) {
        if (uri == null) {
            return
        }

        try {
            persistSelectedWorkDirectory(uri)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist selected work directory", error)
        }
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
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "text/plain"),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }

    private fun showFirstRunDialog() {
        val onSelectFolder = {
            appPreferences.edit().putBoolean("first_setup", false).apply()
            requestWorkDirectory()
        }
        val onLater = {
            appPreferences.edit().putBoolean("first_setup", false).apply()
            validateWorkDirectory()
        }

        showFirstRunPrompt?.let { presenter ->
            presenter(onSelectFolder, onLater)
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Welcome to R47")
            .setMessage(
                "To get started, please select a 'Work Directory'.\n\n" +
                    "This folder will be used to organize your Programs, State files, " +
                    "and Screenshots safely on your device storage."
            )
            .setPositiveButton("Select Folder") { _, _ -> onSelectFolder() }
            .setNegativeButton("Later") { _, _ -> onLater() }
            .setCancelable(false)
            .show()
    }

    private fun validateWorkDirectory() {
        val treeUriString = readWorkDirectoryTreeUri()
        if (treeUriString == null) {
            showWorkDirectoryMissingSnackbar("Work Directory not set")
            return
        }

        if (!isWorkDirectoryAccessible(treeUriString)) {
            showWorkDirectoryMissingSnackbar("Work Directory is no longer accessible")
        }
    }

    private fun showWorkDirectoryMissingSnackbar(message: String) {
        showWorkDirectoryMissingPrompt?.let { presenter ->
            presenter(message, ::requestWorkDirectory)
            return
        }

        Snackbar.make(rootView, message, Snackbar.LENGTH_INDEFINITE)
            .setAction("SET") {
                requestWorkDirectory()
            }
            .show()
    }
}