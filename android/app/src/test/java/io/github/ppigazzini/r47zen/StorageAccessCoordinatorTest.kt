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
    // The start folder comes found: building the intent asks no provider.
    @Test
    fun buildNativeFileRequestIntent_startsASaveInTheFolderItIsGiven() {
        val start = Uri.parse("content://io.github.ppigazzini.r47zen.documents/document/screens")

        val intent = coordinator().buildNativeFileRequestIntent(
            isSave = true,
            defaultName = "screen.bmp",
            start = start,
        )

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("image/bmp", intent.type)
        assertEquals("screen.bmp", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(
            start,
            intent.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
        )
    }

    @Test
    fun buildNativeFileRequestIntent_startsALoadInTheFolderItIsGiven() {
        val start = Uri.parse("content://io.github.ppigazzini.r47zen.documents/document/programs")

        val intent = coordinator().buildNativeFileRequestIntent(
            isSave = false,
            defaultName = "ignored.p47",
            start = start,
        )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertEquals(
            start,
            intent.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
        )
    }

    @Test
    fun buildNativeFileRequestIntent_withNoStartFolder_leavesThePickerWhereItWas() {
        val intent = coordinator().buildNativeFileRequestIntent(
            isSave = false,
            defaultName = "ignored.p47",
            start = null,
        )

        assertNull(
            intent.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
        )
    }

    private fun coordinator() = StorageAccessCoordinator(
        activity = buildActivity(),
        onNativeFileSelected = { _, _ -> },
        onNativeFileCanceled = {},
        providedSaveIntentLauncher = {},
        providedLoadIntentLauncher = {},
    )

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
            start = null,
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
            onLoadTransferStarted = {
                transfersStarted += 1
                0L
            },
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
            onLoadTransferStarted = {
                transfersStarted += 1
                0L
            },
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
        val intent = coordinator().buildNativeFileRequestIntent(isSave = true, defaultName = "data.d47", start = null)

        assertEquals("application/octet-stream", intent.type)
        assertEquals("data.d47", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    private fun buildActivity(): ComponentActivity {
        return Robolectric.buildActivity(ComponentActivity::class.java)
            .setup()
            .get()
    }
}
