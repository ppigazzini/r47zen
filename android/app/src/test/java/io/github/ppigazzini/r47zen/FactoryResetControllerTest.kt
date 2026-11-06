package io.github.ppigazzini.r47zen

import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FactoryResetControllerTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java)
        .setup()
        .get()
    private var sharedStateResets = 0
    private var releases = 0

    @Test
    fun resetDestroy_withTheCoreStopped_resetsSharedStateAndWipesFiles() {
        val savedState = seedSavedState()
        val controller = resettingController(coreThreadRunning = false)

        controller.handleDestroy(shouldStopApp = true)

        assertEquals(1, sharedStateResets)
        assertEquals(0, releases)
        assertFalse(savedState.exists())
    }

    @Test
    fun resetDestroy_withTheCoreStillRunning_keepsFilesAndSharedState() {
        val savedState = seedSavedState()
        val controller = resettingController(coreThreadRunning = true)

        controller.handleDestroy(shouldStopApp = true)

        // A live core thread would autosave into a wiped directory, and a shared
        // state reset would let the relaunch start a second core thread.
        assertEquals(0, sharedStateResets)
        assertEquals(1, releases)
        assertTrue(savedState.exists())
    }

    @Test
    fun finishingDestroy_withoutAReset_releasesOnlyAndKeepsFiles() {
        val savedState = seedSavedState()
        val controller = controller(coreThreadRunning = false)

        controller.handleDestroy(shouldStopApp = true)

        assertEquals(0, sharedStateResets)
        assertEquals(1, releases)
        assertTrue(savedState.exists())
    }

    @Test
    fun resetDestroy_forgetsTheSavedManualAndReleasesEveryDocumentGrant() {
        val manual = "content://test.documents/document/manual".toUri()
        val workDirectory = "content://test.documents/tree/work".toUri()
        SavedManual.remember(activity, manual)
        WorkDirectory.persistSelectedTreeUri(activity, workDirectory)
        val controller = resettingController(coreThreadRunning = false)

        controller.handleDestroy(shouldStopApp = true)

        assertNull(SavedManual.read(activity))
        assertNull(WorkDirectory.readTreeUriString(activity))
        assertTrue(activity.contentResolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun destroyWithoutAWipe_keepsTheDocumentGrants() {
        val manual = "content://test.documents/document/manual".toUri()
        SavedManual.remember(activity, manual)

        controller(coreThreadRunning = false).handleDestroy(shouldStopApp = true)
        resettingController(coreThreadRunning = true).handleDestroy(shouldStopApp = true)

        assertEquals(manual, SavedManual.read(activity))
        assertEquals(listOf(manual), activity.contentResolver.persistedUriPermissions.map { it.uri })
    }

    private fun seedSavedState(): File {
        return File(activity.filesDir, "r47-saved-state.bin").apply { writeText("state") }
    }

    private fun resettingController(coreThreadRunning: Boolean): FactoryResetController {
        return controller(coreThreadRunning).also { it.markResetInProgressForTest() }
    }

    private fun controller(coreThreadRunning: Boolean): FactoryResetController {
        return FactoryResetController(
            activity = activity,
            onResetRequested = {},
            onDestroyFactoryReset = { sharedStateResets++ },
            onDestroyFinish = { releases++ },
            isCoreThreadRunning = { coreThreadRunning },
        )
    }
}
