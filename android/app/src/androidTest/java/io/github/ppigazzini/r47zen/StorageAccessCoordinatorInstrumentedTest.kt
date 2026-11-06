package io.github.ppigazzini.r47zen

import android.app.Activity
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StorageAccessCoordinatorInstrumentedTest {
    @Test
    fun deliverSaveResult_writesThePipeToTheDocumentOnARealPipe() {
        ActivityScenario.launch(StorageAccessTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val document = File(activity.cacheDir, "storage-access-save.txt")
                val written = CountDownLatch(1)
                val transfer = DocumentTransfer(
                    openInput = { null },
                    openOutput = { document.outputStream() },
                    tempDir = activity.cacheDir,
                    worker = Executors.newSingleThreadExecutor(),
                    reportFailure = {},
                )
                var selectedFd: Int? = null
                val coordinator = StorageAccessCoordinator(
                    activity = activity,
                    onNativeFileSelected = { selectedFd = it },
                    onNativeFileCanceled = {},
                    documentTransfer = transfer,
                )

                coordinator.deliverSaveResult(Activity.RESULT_OK, Uri.parse("content://test/save"))
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(selectedFd!!))
                    .use { it.write("r47".toByteArray()) }
                Executors.newSingleThreadExecutor().execute {
                    while (document.length() < 3) {
                        Thread.sleep(POLL_MILLIS)
                    }
                    written.countDown()
                }

                assertTrue(written.await(WAIT_SECONDS, TimeUnit.SECONDS))
                assertEquals("r47", document.readText())
            }
        }
    }

    @Test
    fun deliverLoadResult_forwardsAPrivateCopyOfTheDocument() {
        ActivityScenario.launch(StorageAccessTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val document = File(activity.cacheDir, "storage-access-load.txt").apply {
                    writeText("r47")
                }
                val transfer = DocumentTransfer(
                    openInput = { document.inputStream() },
                    openOutput = { null },
                    tempDir = activity.cacheDir,
                    worker = Runnable::run,
                    reportFailure = {},
                )
                var selectedFd: Int? = null
                var cancelCount = 0
                val coordinator = StorageAccessCoordinator(
                    activity = activity,
                    onNativeFileSelected = { selectedFd = it },
                    onNativeFileCanceled = { cancelCount += 1 },
                    documentTransfer = transfer,
                )

                coordinator.deliverLoadResult(Activity.RESULT_OK, Uri.parse("content://test/load"))

                assertEquals(0, cancelCount)
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(selectedFd!!))
                    .use { assertEquals("r47", it.readBytes().decodeToString()) }
            }
        }
    }

    @Test
    fun deliverLoadResult_cancelsOnMissingSelection() {
        ActivityScenario.launch(StorageAccessTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var selectedFd: Int? = null
                var cancelCount = 0
                val coordinator = StorageAccessCoordinator(
                    activity = activity,
                    onNativeFileSelected = { selectedFd = it },
                    onNativeFileCanceled = { cancelCount += 1 },
                )

                coordinator.deliverLoadResult(Activity.RESULT_CANCELED, null)

                assertNull(selectedFd)
                assertEquals(1, cancelCount)
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 10L
        const val WAIT_SECONDS = 10L
    }
}
