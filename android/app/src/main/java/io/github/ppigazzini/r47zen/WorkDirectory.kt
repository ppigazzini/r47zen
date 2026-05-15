package io.github.ppigazzini.r47zen

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

internal data class WorkDirectoryDocumentEntry(
    val displayName: String,
    val documentId: String,
)

internal interface WorkDirectoryDocumentAccess {
    fun parseTreeUri(treeUriString: String): Uri?
    fun hasPersistedWritePermission(contentResolver: ContentResolver, treeUri: Uri): Boolean
    fun canQueryChildren(contentResolver: ContentResolver, treeUri: Uri): Boolean
    fun listChildren(contentResolver: ContentResolver, treeUri: Uri): List<WorkDirectoryDocumentEntry>
    fun buildDocumentUri(treeUri: Uri, documentId: String): Uri
    fun createDirectory(contentResolver: ContentResolver, treeUri: Uri, displayName: String): Uri?
}

private object AndroidWorkDirectoryDocumentAccess : WorkDirectoryDocumentAccess {
    override fun parseTreeUri(treeUriString: String): Uri? {
        return try {
            Uri.parse(treeUriString)
        } catch (_: Exception) {
            null
        }
    }

    override fun hasPersistedWritePermission(
        contentResolver: ContentResolver,
        treeUri: Uri,
    ): Boolean {
        return contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
    }

    override fun canQueryChildren(contentResolver: ContentResolver, treeUri: Uri): Boolean {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        return contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use {
            true
        } ?: false
    }

    override fun listChildren(
        contentResolver: ContentResolver,
        treeUri: Uri,
    ): List<WorkDirectoryDocumentEntry> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val children = mutableListOf<WorkDirectoryDocumentEntry>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                children += WorkDirectoryDocumentEntry(
                    displayName = cursor.getString(0),
                    documentId = cursor.getString(1),
                )
            }
        }
        return children
    }

    override fun buildDocumentUri(treeUri: Uri, documentId: String): Uri {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }

    override fun createDirectory(
        contentResolver: ContentResolver,
        treeUri: Uri,
        displayName: String,
    ): Uri? {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        return DocumentsContract.createDocument(
            contentResolver,
            rootDocumentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            displayName,
        )
    }
}

internal object WorkDirectory {
    private const val TAG = "R47WorkDir"

    const val PREFS_NAME = "R47WorkDirPrefs"
    private const val LEGACY_PREFS_NAME = SlotStore.APP_PREFS_NAME
    const val KEY_TREE_URI = "work_directory_uri"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun legacyPrefs(context: Context) =
        context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    private fun migrateLegacyTreeUriIfNeeded(context: Context): String? {
        val migratedValue = legacyPrefs(context).getString(KEY_TREE_URI, null) ?: return null
        prefs(context).edit().putString(KEY_TREE_URI, migratedValue).commit()
        legacyPrefs(context).edit().remove(KEY_TREE_URI).apply()
        return migratedValue
    }

    fun readTreeUriString(context: Context): String? {
        val currentValue = prefs(context).getString(KEY_TREE_URI, null)
        return currentValue ?: migrateLegacyTreeUriIfNeeded(context)
    }

    fun writeTreeUriString(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_TREE_URI, uri.toString()).apply()
        legacyPrefs(context).edit().remove(KEY_TREE_URI).apply()
    }

    fun clearTreeUriString(context: Context) {
        val treeUri = readTreeUriString(context)?.let { storedValue ->
            AndroidWorkDirectoryDocumentAccess.parseTreeUri(storedValue)
        }
        if (treeUri != null) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                Log.w(TAG, "No persisted permission to release for cleared work directory")
            }
        }

        prefs(context).edit().remove(KEY_TREE_URI).apply()
        legacyPrefs(context).edit().remove(KEY_TREE_URI).apply()
    }

    fun persistSelectedTreeUri(context: Context, uri: Uri): String {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        writeTreeUriString(context, uri)
        return formatDisplayPath(uri.path)
    }

    fun formatDisplayPath(uriPath: String?): String {
        if (uriPath == null) {
            return "Select a folder"
        }

        return uriPath.replaceFirst("^/tree/.*?:".toRegex(), "/")
    }

    fun isAccessible(
        contentResolver: ContentResolver,
        treeUriString: String?,
        documentAccess: WorkDirectoryDocumentAccess = AndroidWorkDirectoryDocumentAccess,
    ): Boolean {
        if (treeUriString.isNullOrEmpty()) {
            return false
        }

        val treeUri = documentAccess.parseTreeUri(treeUriString)
        if (treeUri == null) {
            Log.w(TAG, "Invalid work directory URI")
            return false
        }

        return try {
            if (!documentAccess.hasPersistedWritePermission(contentResolver, treeUri)) {
                return false
            }

            documentAccess.canQueryChildren(contentResolver, treeUri)
        } catch (error: Exception) {
            Log.w(TAG, "Work directory validation failed: ${error.message}")
            false
        }
    }

    fun resolveSubfolder(
        contentResolver: ContentResolver,
        treeUriString: String?,
        fileType: Int,
        documentAccess: WorkDirectoryDocumentAccess = AndroidWorkDirectoryDocumentAccess,
    ): Uri? {
        if (treeUriString.isNullOrEmpty()) {
            return null
        }

        val treeUri = documentAccess.parseTreeUri(treeUriString)
        if (treeUri == null) {
            Log.e(TAG, "Invalid work directory URI")
            return null
        }

        val subfolderName = subfolderNameFor(fileType) ?: return treeUri

        return try {
            var folderUri = documentAccess.listChildren(contentResolver, treeUri)
                .firstOrNull { it.displayName == subfolderName }
                ?.let { documentAccess.buildDocumentUri(treeUri, it.documentId) }
            if (folderUri == null) {
                folderUri = documentAccess.createDirectory(contentResolver, treeUri, subfolderName)
            }

            folderUri ?: treeUri
        } catch (error: Exception) {
            Log.e(TAG, "Error resolving subfolder $subfolderName", error)
            treeUri
        }
    }

    private fun subfolderNameFor(fileType: Int): String? {
        return when (fileType) {
            0 -> "STATE"
            1 -> "PROGRAMS"
            2 -> "SAVFILES"
            3 -> "SCREENS"
            else -> null
        }
    }
}
