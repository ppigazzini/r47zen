package io.github.ppigazzini.r47zen

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class WindowModeControllerTest {

    private fun buildController(
        enterPipMode: (android.app.PictureInPictureParams) -> Boolean,
    ): WindowModeController {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .create()
            .get()
        return WindowModeController(
            activity = activity,
            mainHandler = Handler(Looper.getMainLooper()),
            onPiPModeChanged = {},
            enterPipMode = enterPipMode,
        )
    }

    @Test
    fun refusedPipEntry_leavesAutoSaveEnabled() {
        val controller = buildController(enterPipMode = { false })

        controller.enterPictureInPicture()

        // A refused entry must not latch the "entering PiP" flag, or onPause
        // would skip auto-save for the rest of the process lifetime.
        assertFalse(
            "Refused PiP entry must not report as entering PiP",
            controller.isEnteringPictureInPicture(),
        )
    }

    @Test
    fun throwingPipEntry_leavesAutoSaveEnabled() {
        val controller = buildController(
            enterPipMode = { throw IllegalStateException("PiP unavailable") },
        )

        controller.enterPictureInPicture()

        assertFalse(
            "Throwing PiP entry must not report as entering PiP",
            controller.isEnteringPictureInPicture(),
        )
    }

    @Test
    fun acceptedPipEntry_reportsEnteringUntilCallback() {
        val controller = buildController(enterPipMode = { true })

        controller.enterPictureInPicture()
        assertTrue(
            "Accepted PiP entry must report as entering PiP until the callback",
            controller.isEnteringPictureInPicture(),
        )

        controller.handlePictureInPictureModeChanged(true)
        assertFalse(
            "The PiP callback clears the transient entering flag",
            controller.isEnteringPictureInPicture(),
        )
    }
}
