package io.github.ppigazzini.r47zen

import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test
    fun fullscreenOff_padsContentToTheSafeAreaUnderLightBarIcons() {
        val (activity, controller, root) = buildFittedShell()

        controller.applyFullscreenMode(false)
        dispatchSafeAreaInsets(root)

        // The cutout (80) is deeper than the status bar (63), so the top pads by
        // the deeper of the two; the bottom clears the navigation bar.
        assertEquals(listOf(0, 80, 0, 126), root.paddingList())
        val bars = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        assertFalse(bars.isAppearanceLightStatusBars)
        assertFalse(bars.isAppearanceLightNavigationBars)
    }

    @Test
    fun fullscreenOn_keepsTheWholeWindow() {
        val (_, controller, root) = buildFittedShell()

        controller.applyFullscreenMode(false)
        dispatchSafeAreaInsets(root)
        controller.applyFullscreenMode(true)
        dispatchSafeAreaInsets(root)

        assertEquals(listOf(0, 0, 0, 0), root.paddingList())
    }

    private fun buildFittedShell(): Triple<AppCompatActivity, WindowModeController, FrameLayout> {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .setup()
            .get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        val controller = WindowModeController(
            activity = activity,
            mainHandler = Handler(Looper.getMainLooper()),
            onPiPModeChanged = {},
        )
        controller.fitContentToSafeArea(root)
        return Triple(activity, controller, root)
    }

    private fun dispatchSafeAreaInsets(root: FrameLayout) {
        shadowOf(Looper.getMainLooper()).idle()
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 63, 0, 126))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 80, 0, 0))
                .build(),
        )
    }

    private fun FrameLayout.paddingList() = listOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
}
