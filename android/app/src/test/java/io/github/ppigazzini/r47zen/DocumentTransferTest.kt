package io.github.ppigazzini.r47zen

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class DocumentTransferTest {
    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val PRIVATE_FD = 42
    }

    private val uri = Uri.parse("content://test/document")
    private val failures = ArrayList<Int>()

    private fun transfer(
        openInput: (Uri) -> InputStream? = { null },
        openOutput: (Uri) -> OutputStream? = { null },
        queryName: (Uri) -> String? = { null },
    ) = DocumentTransfer(
        provider = FakeDocumentProvider(openInput, openOutput, queryName),
        tempDir = temp.root,
        worker = Runnable::run,
        reportFailure = { failures.add(it) },
    )

    private fun tempFiles() = temp.root.listFiles().orEmpty().toList()

    @Test
    fun load_handsNativeAPrivateFileCopyAndLeavesNoFileBehind() {
        val payload = "PROGRAM_FILE_FORMAT\n0\n".toByteArray()
        var opened: ByteArray? = null
        var selected: Int? = null
        val transfer = DocumentTransfer(
            provider = FakeDocumentProvider(input = { ByteArrayInputStream(payload) }),
            tempDir = temp.root,
            worker = Runnable::run,
            reportFailure = { failures.add(it) },
            openPrivateFile = { file ->
                opened = file.readBytes()
                PRIVATE_FD
            },
        )

        transfer.load(uri, onLoaded = { fd, _ -> selected = fd }, onFailed = { error("not expected") })

        assertArrayEquals(payload, opened)
        assertEquals(PRIVATE_FD, selected)
        assertEquals(emptyList<Any>(), tempFiles())
    }

    // STATE# reports the name of the state file loaded last, which native records
    // from the name the provider shows.
    @Test
    fun load_handsNativeTheNameTheProviderShows() {
        var loaded: Pair<Int, String?>? = null
        val transfer = DocumentTransfer(
            provider = FakeDocumentProvider(
                input = { ByteArrayInputStream(byteArrayOf(1)) },
                name = { "mystate.s47" },
            ),
            tempDir = temp.root,
            worker = Runnable::run,
            reportFailure = { failures.add(it) },
            openPrivateFile = { PRIVATE_FD },
        )

        transfer.load(uri, onLoaded = { fd, name -> loaded = fd to name }, onFailed = { error("not expected") })

        assertEquals(PRIVATE_FD to "mystate.s47", loaded)
    }

    // A provider owes no name, and may fail the query as Binder carries it back:
    // the document still loads, under no name.
    @Test
    fun load_handsNativeTheDocumentWhenTheProviderGivesNoName() {
        val queries = listOf<(Uri) -> String?>(
            { null },
            { throw SecurityException("the grant is gone") },
            { throw IllegalArgumentException("no such column") },
        )

        queries.forEach { queryName ->
            var loaded: Pair<Int, String?>? = null
            DocumentTransfer(
                provider = FakeDocumentProvider(
                    input = { ByteArrayInputStream(byteArrayOf(1)) },
                    name = queryName,
                ),
                tempDir = temp.root,
                worker = Runnable::run,
                reportFailure = { failures.add(it) },
                openPrivateFile = { PRIVATE_FD },
            ).load(uri, onLoaded = { fd, name -> loaded = fd to name }, onFailed = { error("not expected") })

            assertEquals(PRIVATE_FD to null, loaded)
            assertEquals(emptyList<Int>(), failures)
        }
    }

    @Test
    fun load_reportsAndCancelsWhenTheProviderFails() {
        val failingInputs = listOf<(Uri) -> InputStream?>(
            { null },
            { throw SecurityException("the grant is gone") },
            {
                object : InputStream() {
                    override fun read(): Int = throw IOException("the network is gone")
                }
            },
        )

        failingInputs.forEach { openInput ->
            failures.clear()
            var canceled = 0
            transfer(openInput = openInput)
                .load(uri, onLoaded = { _, _ -> error("not expected") }, onFailed = { canceled += 1 })

            assertEquals(1, canceled)
            assertEquals(listOf(R.string.file_read_failed), failures)
            assertEquals(emptyList<Any>(), tempFiles())
        }
    }

    @Test
    fun load_refusesADocumentPastTheLimit() {
        val huge = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
        }
        var canceled = 0

        transfer(openInput = { huge })
            .load(uri, onLoaded = { _, _ -> error("not expected") }, onFailed = { canceled += 1 })

        assertEquals(1, canceled)
        assertEquals(listOf(R.string.file_read_failed), failures)
        assertEquals(emptyList<Any>(), tempFiles())
    }

    // The limit itself is still a document: only a byte past it refuses.
    @Test
    fun load_takesADocumentOfExactlyTheLimit() {
        var remaining = DocumentTransfer.MAX_LOAD_BYTES
        val exact = object : InputStream() {
            override fun read(): Int {
                if (remaining <= 0) {
                    return -1
                }
                remaining -= 1
                return 0
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = minOf(length.toLong(), remaining).toInt()
                remaining -= count
                return when {
                    length == 0 -> 0
                    count == 0 -> -1
                    else -> count
                }
            }
        }
        var copied = -1L
        val transfer = DocumentTransfer(
            provider = FakeDocumentProvider(input = { exact }),
            tempDir = temp.root,
            worker = Runnable::run,
            reportFailure = { failures.add(it) },
            openPrivateFile = { file ->
                copied = file.length()
                PRIVATE_FD
            },
        )

        var selected: Int? = null
        transfer.load(uri, onLoaded = { fd, _ -> selected = fd }, onFailed = { error("not expected") })

        assertEquals(PRIVATE_FD, selected)
        assertEquals(DocumentTransfer.MAX_LOAD_BYTES, copied)
        assertEquals(emptyList<Int>(), failures)
    }

    @Test
    fun writeDocument_opensTheProviderOnlyOnceAllTheDataExists() {
        val payload = ByteArray(200_000) { it.toByte() }
        val output = ByteArrayOutputStream()
        var pipeEmptyWhenOpened = false
        val input = ByteArrayInputStream(payload)

        transfer(openOutput = {
            pipeEmptyWhenOpened = input.available() == 0
            output
        }).writeDocument(input, uri)

        assertEquals(true, pipeEmptyWhenOpened)
        assertArrayEquals(payload, output.toByteArray())
        assertEquals(emptyList<Any>(), tempFiles())
    }

    @Test
    fun writeDocument_survivesAndroidClearingTheCacheDirectory() {
        val output = ByteArrayOutputStream()
        temp.root.deleteRecursively()

        transfer(openOutput = { output }).writeDocument(ByteArrayInputStream(byteArrayOf(1)), uri)

        assertArrayEquals(byteArrayOf(1), output.toByteArray())
    }

    @Test
    fun writeDocument_closesTheProviderStreamAfterTheLastByte() {
        var bytesWhenClosed = -1
        val output = object : ByteArrayOutputStream() {
            override fun close() {
                bytesWhenClosed = size()
            }
        }

        transfer(openOutput = { output }).writeDocument(ByteArrayInputStream(byteArrayOf(1, 2, 3)), uri)

        assertEquals(3, bytesWhenClosed)
    }

    @Test
    fun writeDocument_savesToADocumentThatCannotSync() {
        // /dev/null takes every byte and refuses fsync, as a provider's pipe does.
        transfer(openOutput = { File("/dev/null").outputStream() })
            .writeDocument(ByteArrayInputStream(byteArrayOf(1)), uri)

        assertEquals(emptyList<Any>(), tempFiles())
    }

    @Test
    fun writeDocument_leavesTheDocumentAloneWhenNativeWroteNothing() {
        var opened = false

        transfer(openOutput = {
            opened = true
            ByteArrayOutputStream()
        }).writeDocument(ByteArrayInputStream(ByteArray(0)), uri)

        assertFalse(opened)
        assertEquals(emptyList<Any>(), tempFiles())
    }

    @Test
    fun writeDocument_propagatesAProviderFailureAndLeavesNoFileBehind() {
        var thrown: Exception? = null
        try {
            transfer(openOutput = { null }).writeDocument(ByteArrayInputStream(byteArrayOf(1)), uri)
        } catch (error: IOException) {
            thrown = error
        }

        assertEquals(true, thrown != null)
        assertEquals(emptyList<Any>(), tempFiles())
        assertNull(failures.firstOrNull())
    }
}
