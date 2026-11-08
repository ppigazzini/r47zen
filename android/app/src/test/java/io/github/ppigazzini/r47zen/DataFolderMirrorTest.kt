package io.github.ppigazzini.r47zen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DataFolderMirrorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val copies = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("data-folder-mirror-test", Context.MODE_PRIVATE)
    private val written = mutableListOf<Pair<String, String>>()
    private var failNext = false
    private val folder = object : DocumentFolder {
        override fun write(name: String, source: File) {
            if (failNext) {
                failNext = false
                throw IOException("the provider is gone")
            }
            written += name to source.readText()
        }
    }
    private var openFolder: (Int) -> DocumentFolder? = { folder }
    private var notices = 0
    private val mirror by lazy {
        DataFolderMirror(temp.root, { fileType -> openFolder(fileType) }, copies, Runnable::run, { notices += 1 })
    }

    @Before
    fun clearCopies() {
        copies.edit().clear().commit()
    }

    // The core appends a line at a time; each append moves the file's stamp.
    private fun print(name: String, line: String, modified: Long) {
        File(temp.root, name).apply {
            appendText(line)
            setLastModified(modified)
        }
    }

    @Test
    fun aNewPrintIsCopiedWhole_andAnUnchangedOneIsNotCopiedAgain() {
        print("20261108-10000000.REGS.TSV", "1\t2\n", modified = 1_000)

        mirror.mirrorNow()
        mirror.mirrorNow()

        assertEquals(listOf("20261108-10000000.REGS.TSV" to "1\t2\n"), written)
    }

    @Test
    fun aPrintThatGrewIsCopiedAgainWhole() {
        print("20261108-10000000.REGS.TSV", "1\n", modified = 1_000)
        mirror.mirrorNow()
        print("20261108-10000000.REGS.TSV", "2\n", modified = 2_000)

        mirror.mirrorNow()

        assertEquals("1\n2\n", written.last().second)
        assertEquals(2, written.size)
    }

    @Test
    fun aFailedCopyIsTriedAgainAtTheNextStop() {
        print("20261108-10000000.STAT.TSV", "3\t4\n", modified = 1_000)
        failNext = true

        mirror.mirrorNow()
        mirror.mirrorNow()

        assertEquals(listOf("20261108-10000000.STAT.TSV" to "3\t4\n"), written)
    }

    // A user with no work directory hears once for each new print, not at every stop,
    // and the first stop with one copies what waited.
    @Test
    fun withNoFolder_theUserIsToldOncePerNewPrint_andThePrintsWaitForOne() {
        openFolder = { null }
        print("20261108-10000000.REGS.TSV", "1\n", modified = 1_000)
        print("20261108-10000100.bmp", "BM", modified = 1_000)
        mirror.mirrorNow()
        mirror.mirrorNow()
        assertEquals(1, notices)

        print("20261108-10000000.REGS.TSV", "2\n", modified = 2_000)
        mirror.mirrorNow()
        assertEquals(2, notices)

        openFolder = { folder }
        mirror.mirrorNow()
        assertEquals(
            setOf("20261108-10000000.REGS.TSV" to "1\n2\n", "20261108-10000100.bmp" to "BM"),
            written.toSet(),
        )
        assertEquals(2, notices)
    }

    // A provider that cannot answer now is no missing work directory: nothing is said,
    // and the next stop tries again.
    @Test
    fun aWorkDirectoryThatCannotBeReached_isTriedAgainWithNoNotice() {
        openFolder = { throw SecurityException("the grant is gone") }
        print("20261108-10000000.REGS.TSV", "1\n", modified = 1_000)
        mirror.mirrorNow()

        openFolder = { folder }
        mirror.mirrorNow()

        assertEquals(0, notices)
        assertEquals(listOf("20261108-10000000.REGS.TSV" to "1\n"), written)
    }

    // As the firmware files them: a print in DATA, a capture a SNAP left unnamed in SCREENS.
    @Test
    fun aPrintGoesToData_andACaptureToScreens() {
        val filed = mutableListOf<Pair<Int, String>>()
        openFolder = { fileType ->
            object : DocumentFolder {
                override fun write(name: String, source: File) {
                    filed += fileType to name
                }
            }
        }
        print("20261108-10000000.REGS.TSV", "1\n", modified = 1_000)
        print("20261108-10000100.bmp", "BM", modified = 1_000)

        mirror.mirrorNow()

        assertEquals(
            setOf(
                WorkDirectory.FILE_TYPE_DATA to "20261108-10000000.REGS.TSV",
                WorkDirectory.FILE_TYPE_SCREENS to "20261108-10000100.bmp",
            ),
            filed.toSet(),
        )
    }

    @Test
    fun nothingPrinted_asksNoProvider() {
        var opened = 0
        openFolder = {
            opened += 1
            folder
        }

        mirror.mirrorNow()

        assertEquals(0, opened)
    }
}
