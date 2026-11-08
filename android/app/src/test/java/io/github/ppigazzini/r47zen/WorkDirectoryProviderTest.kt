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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream

private const val TREE_AUTHORITY = "io.github.ppigazzini.r47zen.test.tree"

/** A tree document provider: lists the children it holds and creates a document on request. */
class FakeTreeDocuments : ContentProvider() {
    val children = mutableListOf<Pair<String, String>>()
    val created = mutableListOf<String>()
    val createdTypes = mutableListOf<String?>()

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
        createdTypes += extras?.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
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

    @get:Rule
    val temp = TemporaryFolder()

    private fun grantTree() {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    private fun printFile(text: String) = temp.newFile().apply { writeText(text) }

    private fun dataFolder() = TreeDocumentFolder.open(resolver, treeUri.toString(), WorkDirectory.FILE_TYPE_DATA)

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

    // A print reaches the DATA folder as a new document, made as octet-stream so a
    // provider keeps its .TSV name, and holds the whole file.
    @Test
    fun aPrintIsMadeInTheDataFolderAndWrittenWhole() {
        grantTree()
        documents.children += "DATA" to "root/DATA"
        val output = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "root/20261108-10000000.REGS.TSV"),
            output,
        )

        val folder = checkNotNull(dataFolder())
        folder.write("20261108-10000000.REGS.TSV", printFile("1\t2\n"))

        assertEquals(listOf("20261108-10000000.REGS.TSV"), documents.created)
        assertEquals(listOf<String?>("application/octet-stream"), documents.createdTypes)
        assertEquals("1\t2\n", output.toString())
    }

    @Test
    fun aPrintAlreadyThereIsReplacedNotMadeAgain() {
        grantTree()
        documents.children += "DATA" to "root/DATA"
        documents.children += "20261108-10000000.REGS.TSV" to "root/DATA/old"
        val output = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "root/DATA/old"),
            output,
        )

        checkNotNull(dataFolder())
            .write("20261108-10000000.REGS.TSV", printFile("3\n"))

        assertTrue(documents.created.isEmpty())
        assertEquals("3\n", output.toString())
    }

    @Test
    fun aMissingDataFolderIsMade() {
        grantTree()

        assertTrue(dataFolder() != null)

        assertEquals(listOf("DATA"), documents.created)
    }

    @Test
    fun noDataFolderWithoutAWorkDirectoryTheAppCanWrite() {
        assertNull(dataFolder())
        assertNull(TreeDocumentFolder.open(resolver, null, WorkDirectory.FILE_TYPE_DATA))
        assertTrue(documents.created.isEmpty())
    }
}
