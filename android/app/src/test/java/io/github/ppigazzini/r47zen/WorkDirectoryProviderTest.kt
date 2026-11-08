package io.github.ppigazzini.r47zen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

private const val TREE_AUTHORITY = "io.github.ppigazzini.r47zen.test.tree"

/** A tree document provider: lists the children it holds and creates a folder on request. */
class FakeTreeDocuments : ContentProvider() {
    val children = mutableListOf<Pair<String, String>>()
    val created = mutableListOf<String>()

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        )
        children.forEach { (name, id) -> cursor.addRow(arrayOf(name, id)) }
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val name = extras?.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty()
        created += name
        val treeUri = "content://$TREE_AUTHORITY/tree/root".toUri()
        // "uri" is DocumentsContract.EXTRA_URI, the key createDocument reads, which the SDK hides.
        return Bundle().apply {
            putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(treeUri, "root/$name"))
        }
    }

    override fun getType(uri: Uri): String = DocumentsContract.Document.MIME_TYPE_DIR
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

// WorkDirectory through its real document access, the calls the app makes on a device,
// against a provider that answers as a tree provider does.
@RunWith(RobolectricTestRunner::class)
class WorkDirectoryProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver
    private val documents = Robolectric.setupContentProvider(FakeTreeDocuments::class.java, TREE_AUTHORITY)
    private val treeUri = "content://$TREE_AUTHORITY/tree/root".toUri()

    @Test
    fun aTreeIsAccessibleOnlyWithAWriteGrantAndChildrenItCanList() {
        assertFalse(WorkDirectory.isAccessible(resolver, treeUri.toString()))

        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(WorkDirectory.isAccessible(resolver, treeUri.toString()))

        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        assertTrue(WorkDirectory.isAccessible(resolver, treeUri.toString()))
    }

    @Test
    fun aFileTypesFolderIsFoundByItsName() {
        documents.children += "STATE" to "root/STATE"
        documents.children += "PROGRAMS" to "root/PROGRAMS"

        val folder = WorkDirectory.resolveSubfolder(resolver, treeUri.toString(), fileType = 1)

        assertEquals(DocumentsContract.buildDocumentUriUsingTree(treeUri, "root/PROGRAMS"), folder)
        assertTrue(documents.created.isEmpty())
    }

    @Test
    fun aMissingFileTypesFolderIsCreated() {
        documents.children += "STATE" to "root/STATE"

        val folder = WorkDirectory.resolveSubfolder(resolver, treeUri.toString(), fileType = 3)

        assertEquals(listOf("SCREENS"), documents.created)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(treeUri, "root/SCREENS"), folder)
    }
}
