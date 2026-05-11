package com.example.r47

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageAccessCoordinatorTest {
    @Test
    fun buildNativeFileRequestIntent_routesSaveRequestsThroughResolvedSubfolder() {
        val activity = buildActivity()
        val resolvedUri = Uri.parse("content://com.example.r47.documents/document/screens")
        var capturedTreeUri: String? = null
        var capturedFileType: Int? = null

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = {},
            onNativeFileCancelled = {},
            readWorkDirectoryTreeUri = { "content://com.example.r47.documents/tree/root" },
            resolveInitialUri = { treeUriString, fileType ->
                capturedTreeUri = treeUriString
                capturedFileType = fileType
                resolvedUri
            },
            isWorkDirectoryAccessible = { true },
            providedSaveIntentLauncher = {},
            providedLoadIntentLauncher = {},
        )

        val intent = coordinator.buildNativeFileRequestIntent(
            isSave = true,
            defaultName = "screen.bmp",
            fileType = 3,
        )

        assertEquals("content://com.example.r47.documents/tree/root", capturedTreeUri)
        assertEquals(3, capturedFileType)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("image/bmp", intent.type)
        assertEquals("screen.bmp", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(
            resolvedUri,
            intent.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
        )
    }

    @Test
    fun buildNativeFileRequestIntent_routesLoadRequestsThroughResolvedSubfolder() {
        val activity = buildActivity()
        val resolvedUri = Uri.parse("content://com.example.r47.documents/document/programs")
        var capturedTreeUri: String? = null
        var capturedFileType: Int? = null

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = {},
            onNativeFileCancelled = {},
            readWorkDirectoryTreeUri = { "content://com.example.r47.documents/tree/root" },
            resolveInitialUri = { treeUriString, fileType ->
                capturedTreeUri = treeUriString
                capturedFileType = fileType
                resolvedUri
            },
            isWorkDirectoryAccessible = { true },
            providedSaveIntentLauncher = {},
            providedLoadIntentLauncher = {},
        )

        val intent = coordinator.buildNativeFileRequestIntent(
            isSave = false,
            defaultName = "ignored.p47",
            fileType = 1,
        )

        assertEquals("content://com.example.r47.documents/tree/root", capturedTreeUri)
        assertEquals(1, capturedFileType)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertArrayEquals(
            arrayOf("application/octet-stream", "text/plain"),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        assertEquals(
            resolvedUri,
            intent.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
        )
    }

    @Test
    fun buildNativeFileRequestIntent_ignoresInaccessibleSavedWorkDirectory() {
        val activity = buildActivity()
        var resolveCallCount = 0

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = {},
            onNativeFileCancelled = {},
            readWorkDirectoryTreeUri = { "content://com.example.r47.documents/tree/root" },
            resolveInitialUri = { _, _ ->
                resolveCallCount += 1
                Uri.parse("content://com.example.r47.documents/document/programs")
            },
            isWorkDirectoryAccessible = { false },
            providedSaveIntentLauncher = {},
            providedLoadIntentLauncher = {},
        )

        val intent = coordinator.buildNativeFileRequestIntent(
            isSave = false,
            defaultName = "ignored.p47",
            fileType = 1,
        )

        assertEquals(0, resolveCallCount)
        assertNull(
            intent.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
        )
    }

    @Test
    fun requestNativeFile_cancelsWhenLauncherThrows() {
        val activity = buildActivity()
        var cancelCount = 0

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = {},
            onNativeFileCancelled = { cancelCount += 1 },
            providedSaveIntentLauncher = { throw IllegalStateException("boom") },
            providedLoadIntentLauncher = {},
        )

        coordinator.requestNativeFile(
            isSave = true,
            defaultName = "state.s47",
            fileType = 0,
        )

        assertEquals(1, cancelCount)
    }

    @Test
    fun handleResume_doesNotPromptWhenWorkDirectoryIsUnset() {
        val activity = buildActivity()
        var pickerLaunchCount = 0

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = {},
            onNativeFileCancelled = {},
            readWorkDirectoryTreeUri = { null },
            providedSaveIntentLauncher = {},
            providedLoadIntentLauncher = {},
            providedWorkDirectoryIntentLauncher = { pickerLaunchCount += 1 },
        )

        coordinator.handleResume()

        assertEquals(0, pickerLaunchCount)
    }

    @Test
    fun deliverNativeFileResult_cancelsWhenDescriptorOpenFails() {
        val activity = buildActivity()
        var cancelCount = 0
        var selectedFd: Int? = null

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = { selectedFd = it },
            onNativeFileCancelled = { cancelCount += 1 },
            openFileDescriptor = { _, _ -> throw IllegalStateException("open failed") },
            providedSaveIntentLauncher = {},
            providedLoadIntentLauncher = {},
        )

        coordinator.deliverNativeFileResult(
            resultCode = android.app.Activity.RESULT_OK,
            uri = Uri.parse("content://com.example.r47.documents/document/programs"),
            mode = "r",
        )

        assertNull(selectedFd)
        assertEquals(1, cancelCount)
    }

    private fun buildActivity(): StorageAccessTestActivity {
        return Robolectric.buildActivity(StorageAccessTestActivity::class.java)
            .setup()
            .get()
    }
}