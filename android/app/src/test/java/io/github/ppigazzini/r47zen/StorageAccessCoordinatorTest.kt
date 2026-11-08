package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageAccessCoordinatorTest {
    @Test
    fun buildNativeFileRequestIntent_routesSaveRequestsThroughResolvedSubfolder() {
        val activity = buildActivity()
        val resolvedUri = Uri.parse("content://io.github.ppigazzini.r47zen.documents/document/screens")
        var capturedTreeUri: String? = null
        var capturedFileType: Int? = null

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = { _, _ -> },
            onNativeFileCanceled = {},
            readWorkDirectoryTreeUri = { "content://io.github.ppigazzini.r47zen.documents/tree/root" },
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

        assertEquals("content://io.github.ppigazzini.r47zen.documents/tree/root", capturedTreeUri)
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
        val resolvedUri = Uri.parse("content://io.github.ppigazzini.r47zen.documents/document/programs")
        var capturedTreeUri: String? = null
        var capturedFileType: Int? = null

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = { _, _ -> },
            onNativeFileCanceled = {},
            readWorkDirectoryTreeUri = { "content://io.github.ppigazzini.r47zen.documents/tree/root" },
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

        assertEquals("content://io.github.ppigazzini.r47zen.documents/tree/root", capturedTreeUri)
        assertEquals(1, capturedFileType)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
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
            onNativeFileSelected = { _, _ -> },
            onNativeFileCanceled = {},
            readWorkDirectoryTreeUri = { "content://io.github.ppigazzini.r47zen.documents/tree/root" },
            resolveInitialUri = { _, _ ->
                resolveCallCount += 1
                Uri.parse("content://io.github.ppigazzini.r47zen.documents/document/programs")
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
            onNativeFileSelected = { _, _ -> },
            onNativeFileCanceled = { cancelCount += 1 },
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
    fun deliverLoadResult_marksTheTransferAndCancelsWhenTheDocumentCannotBeRead() {
        val activity = buildActivity()
        var cancelCount = 0
        var selectedFd: Int? = null
        var transfersStarted = 0

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = { fd, _ -> selectedFd = fd },
            onNativeFileCanceled = { cancelCount += 1 },
            onLoadTransferStarted = { transfersStarted += 1 },
            documentTransfer = DocumentTransfer(
                provider = FakeDocumentProvider(input = { throw SecurityException("no grant") }),
                tempDir = activity.cacheDir,
                worker = Runnable::run,
                reportFailure = {},
            ),
            providedSaveIntentLauncher = {},
            providedLoadIntentLauncher = {},
        )

        coordinator.deliverLoadResult(
            resultCode = android.app.Activity.RESULT_OK,
            uri = Uri.parse("content://io.github.ppigazzini.r47zen.documents/document/programs"),
        )

        assertNull(selectedFd)
        assertEquals(1, cancelCount)
        assertEquals(1, transfersStarted)
    }

    @Test
    fun deliverResults_cancelWithoutATransferWhenThePickerWasDismissed() {
        val activity = buildActivity()
        var cancelCount = 0
        var transfersStarted = 0

        val coordinator = StorageAccessCoordinator(
            activity = activity,
            onNativeFileSelected = { _, _ -> },
            onNativeFileCanceled = { cancelCount += 1 },
            onLoadTransferStarted = { transfersStarted += 1 },
            providedSaveIntentLauncher = {},
            providedLoadIntentLauncher = {},
        )

        coordinator.deliverSaveResult(android.app.Activity.RESULT_CANCELED, null)
        coordinator.deliverLoadResult(android.app.Activity.RESULT_CANCELED, null)

        assertEquals(2, cancelCount)
        assertEquals(0, transfersStarted)
    }

    @Test
    fun buildNativeFileRequestIntent_savesRegisterDataAsABinaryDocument() {
        val coordinator = StorageAccessCoordinator(
            activity = buildActivity(),
            onNativeFileSelected = { _, _ -> },
            onNativeFileCanceled = {},
            readWorkDirectoryTreeUri = { null },
            providedSaveIntentLauncher = {},
            providedLoadIntentLauncher = {},
        )

        val intent = coordinator.buildNativeFileRequestIntent(
            isSave = true,
            defaultName = "data.d47",
            fileType = 4,
        )

        assertEquals("application/octet-stream", intent.type)
        assertEquals("data.d47", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    private fun buildActivity(): ComponentActivity {
        return Robolectric.buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
    }
}
