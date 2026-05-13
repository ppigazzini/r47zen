package io.github.ppigazzini.r47

import android.app.Activity
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageAccessCoordinatorInstrumentedTest {
    @Test
    fun deliverNativeFileResult_detachesFd_andForwardsSelection() {
        ActivityScenario.launch(StorageAccessTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val backingFile = File(activity.cacheDir, "storage-access-success.txt").apply {
                    writeText("r47")
                }
                var selectedFd: Int? = null
                var cancelCount = 0
                var openedDescriptor: ParcelFileDescriptor? = null
                val coordinator = StorageAccessCoordinator(
                    activity = activity,
                    onNativeFileSelected = { selectedFd = it },
                    onNativeFileCancelled = { cancelCount += 1 },
                    openFileDescriptor = { _, _ ->
                        ParcelFileDescriptor.open(backingFile, ParcelFileDescriptor.MODE_READ_ONLY)
                            .also { openedDescriptor = it }
                    },
                )

                coordinator.deliverNativeFileResult(
                    resultCode = Activity.RESULT_OK,
                    uri = Uri.parse("content://io.github.ppigazzini.r47.test/success"),
                    mode = "r",
                )

                assertNotNull(selectedFd)
                assertEquals(0, cancelCount)
                assertNotNull(openedDescriptor)
                ParcelFileDescriptor.adoptFd(selectedFd!!).close()
            }
        }
    }

    @Test
    fun deliverNativeFileResult_cancelsOnMissingSelection() {
        ActivityScenario.launch(StorageAccessTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var selectedFd: Int? = null
                var cancelCount = 0
                val coordinator = StorageAccessCoordinator(
                    activity = activity,
                    onNativeFileSelected = { selectedFd = it },
                    onNativeFileCancelled = { cancelCount += 1 },
                )

                coordinator.deliverNativeFileResult(
                    resultCode = Activity.RESULT_CANCELED,
                    uri = null,
                    mode = "r",
                )

                assertNull(selectedFd)
                assertEquals(1, cancelCount)
            }
        }
    }
}
