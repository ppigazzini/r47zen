package com.example.r47

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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