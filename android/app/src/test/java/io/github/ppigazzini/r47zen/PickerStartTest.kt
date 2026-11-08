package io.github.ppigazzini.r47zen

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PickerStartTest {
    private val tree = "content://io.github.ppigazzini.r47zen.documents/tree/root"
    private val folder = Uri.parse("content://io.github.ppigazzini.r47zen.documents/document/root%2FPROGRAMS")
    private val resolved = mutableListOf<Triple<String, Int, Boolean>>()

    private fun pickerStart(accessible: Boolean, treeUri: String? = tree) = PickerStart(
        readTreeUri = { treeUri },
        isAccessible = { accessible },
        resolveFolder = { treeUriString, fileType, create ->
            resolved += Triple(treeUriString, fileType, create)
            folder
        },
    )

    @Test
    fun aSaveStartsInItsFileTypesFolder_madeIfMissing() {
        assertEquals(folder, pickerStart(accessible = true).find(fileType = 3, isSave = true))
        assertEquals(listOf(Triple(tree, 3, true)), resolved)
    }

    // A load the user cancels must not leave a folder behind.
    @Test
    fun aLoadStartsInItsFileTypesFolder_butMakesNone() {
        assertEquals(folder, pickerStart(accessible = true).find(fileType = 1, isSave = false))
        assertEquals(listOf(Triple(tree, 1, false)), resolved)
    }

    // With no folder for the file type, found or made, the picker starts at the work
    // directory itself.
    @Test
    fun noFolderForTheFileType_startsAtTheWorkDirectory() {
        val start = PickerStart(
            readTreeUri = { tree },
            isAccessible = { true },
            resolveFolder = { _, _, _ -> null },
        ).find(fileType = 1, isSave = false)

        assertEquals(Uri.parse(tree), start)
    }

    @Test
    fun aWorkDirectoryTheAppCannotUse_isNotAskedForAFolder() {
        assertNull(pickerStart(accessible = false).find(fileType = 1, isSave = false))
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun noWorkDirectory_noStartFolder() {
        assertNull(pickerStart(accessible = true, treeUri = null).find(fileType = 1, isSave = false))
        assertTrue(resolved.isEmpty())
    }
}
