package io.github.ppigazzini.r47zen

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

// A document provider for the storage instrumented tests, in the app's own process. It
// keeps each document as a private file named by the URI's last segment, shows that
// segment as the document's name, and records the mode of every open. It truncates
// only when the mode holds "t", as a provider that reads "w" literally does, so a save
// that asks for "w" leaves the old tail. A document named "fail" refuses every open.
class RecordingDocumentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        synchronized(modes) { modes += mode }
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no document in $uri")
        if (name == FAILING_DOCUMENT) {
            throw FileNotFoundException("$uri refuses every open")
        }
        val access = when {
            mode.startsWith("rw") -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            mode.startsWith("w") -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        val truncate = if ('t' in mode) ParcelFileDescriptor.MODE_TRUNCATE else 0
        return ParcelFileDescriptor.open(File(directory(), name), access or truncate)
    }

    private fun directory(): File = File(checkNotNull(context).filesDir, DIRECTORY).apply { mkdirs() }

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
        addRow(arrayOf(uri.lastPathSegment))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val FAILING_DOCUMENT = "fail"
        private const val DIRECTORY = "recording-documents"
        private val modes = mutableListOf<String>()

        fun authority(packageName: String): String = "$packageName.recording-documents"

        fun modes(): List<String> = synchronized(modes) { modes.toList() }

        fun clear(filesDir: File) {
            synchronized(modes) { modes.clear() }
            File(filesDir, DIRECTORY).deleteRecursively()
        }

        fun document(filesDir: File, name: String): File = File(File(filesDir, DIRECTORY), name)
    }
}
