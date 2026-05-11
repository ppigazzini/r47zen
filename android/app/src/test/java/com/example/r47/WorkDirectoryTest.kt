package com.example.r47

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkDirectoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val contentResolver = context.contentResolver
    private val treeUri = Uri.parse("content://com.example.r47.documents/tree/root")

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences(WorkDirectory.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun isAccessible_returnsFalseWhenTreeUriCannotBeParsed() {
        val documents = FakeWorkDirectoryDocuments(parsedUri = null)

        assertFalse(WorkDirectory.isAccessible(contentResolver, "not-a-tree", documents))
    }

    @Test
    fun isAccessible_requiresPersistedWritePermission() {
        val documents = FakeWorkDirectoryDocuments(
            parsedUri = treeUri,
            hasWritePermission = false,
            canQueryChildren = true,
        )

        assertFalse(WorkDirectory.isAccessible(contentResolver, treeUri.toString(), documents))
    }

    @Test
    fun resolveSubfolder_returnsExistingDirectoryWhenPresent() {
        val existingProgramsUri = Uri.parse("content://com.example.r47.documents/document/programs")
        val documents = FakeWorkDirectoryDocuments(
            parsedUri = treeUri,
            children = listOf(WorkDirectoryDocumentEntry("PROGRAMS", "programs")),
            builtDocumentUris = mapOf("programs" to existingProgramsUri),
        )

        val resolvedUri = WorkDirectory.resolveSubfolder(
            contentResolver = contentResolver,
            treeUriString = treeUri.toString(),
            fileType = 1,
            documentAccess = documents,
        )

        assertEquals(existingProgramsUri, resolvedUri)
        assertNull(documents.createdDirectoryName)
    }

    @Test
    fun resolveSubfolder_createsMissingDirectory() {
        val createdUri = Uri.parse("content://com.example.r47.documents/document/state")
        val documents = FakeWorkDirectoryDocuments(
            parsedUri = treeUri,
            createdDirectoryUri = createdUri,
        )

        val resolvedUri = WorkDirectory.resolveSubfolder(
            contentResolver = contentResolver,
            treeUriString = treeUri.toString(),
            fileType = 0,
            documentAccess = documents,
        )

        assertEquals(createdUri, resolvedUri)
        assertEquals("STATE", documents.createdDirectoryName)
    }

    @Test
    fun readTreeUriString_migratesLegacyPreference() {
        val legacyPrefs = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit().putString(WorkDirectory.KEY_TREE_URI, treeUri.toString()).commit()

        val migratedValue = WorkDirectory.readTreeUriString(context)

        assertEquals(treeUri.toString(), migratedValue)
        assertEquals(
            treeUri.toString(),
            context.getSharedPreferences(WorkDirectory.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(WorkDirectory.KEY_TREE_URI, null),
        )
        assertNull(legacyPrefs.getString(WorkDirectory.KEY_TREE_URI, null))
    }

    @Test
    fun clearTreeUriString_removesStoredValue() {
        context.getSharedPreferences(WorkDirectory.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(WorkDirectory.KEY_TREE_URI, treeUri.toString())
            .commit()

        WorkDirectory.clearTreeUriString(context)

        assertNull(WorkDirectory.readTreeUriString(context))
    }

    @Test
    fun formatDisplayPath_stripsTreePrefix() {
        assertEquals("/R47/PROGRAMS", WorkDirectory.formatDisplayPath("/tree/primary:R47/PROGRAMS"))
        assertEquals("Select a folder", WorkDirectory.formatDisplayPath(null))
    }

    @Test
    fun resolveSubfolder_returnsTreeUriForUnknownFileType() {
        val documents = FakeWorkDirectoryDocuments(parsedUri = treeUri)

        val resolvedUri = WorkDirectory.resolveSubfolder(
            contentResolver = contentResolver,
            treeUriString = treeUri.toString(),
            fileType = 99,
            documentAccess = documents,
        )

        assertEquals(treeUri, resolvedUri)
        assertNull(documents.createdDirectoryName)
    }

    @Test
    fun resolveSubfolder_fallsBackToTreeUriWhenCreateDirectoryReturnsNull() {
        val documents = FakeWorkDirectoryDocuments(
            parsedUri = treeUri,
            createdDirectoryUri = null,
        )

        val resolvedUri = WorkDirectory.resolveSubfolder(
            contentResolver = contentResolver,
            treeUriString = treeUri.toString(),
            fileType = 2,
            documentAccess = documents,
        )

        assertEquals(treeUri, resolvedUri)
        assertEquals("SAVFILES", documents.createdDirectoryName)
    }

    private class FakeWorkDirectoryDocuments(
        private val parsedUri: Uri?,
        private val hasWritePermission: Boolean = true,
        private val canQueryChildren: Boolean = false,
        private val children: List<WorkDirectoryDocumentEntry> = emptyList(),
        private val builtDocumentUris: Map<String, Uri> = emptyMap(),
        private val createdDirectoryUri: Uri? = null,
    ) : WorkDirectoryDocumentAccess {
        var createdDirectoryName: String? = null

        override fun parseTreeUri(treeUriString: String): Uri? = parsedUri

        override fun hasPersistedWritePermission(
            contentResolver: ContentResolver,
            treeUri: Uri,
        ): Boolean = hasWritePermission

        override fun canQueryChildren(contentResolver: ContentResolver, treeUri: Uri): Boolean {
            return canQueryChildren
        }

        override fun listChildren(
            contentResolver: ContentResolver,
            treeUri: Uri,
        ): List<WorkDirectoryDocumentEntry> = children

        override fun buildDocumentUri(treeUri: Uri, documentId: String): Uri {
            return builtDocumentUris.getValue(documentId)
        }

        override fun createDirectory(
            contentResolver: ContentResolver,
            treeUri: Uri,
            displayName: String,
        ): Uri? {
            createdDirectoryName = displayName
            return createdDirectoryUri
        }
    }
}