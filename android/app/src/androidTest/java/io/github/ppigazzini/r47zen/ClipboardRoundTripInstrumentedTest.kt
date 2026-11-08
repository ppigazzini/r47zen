package io.github.ppigazzini.r47zen

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ppigazzini.r47zen.KeypadTouches.KEY_ENTER
import io.github.ppigazzini.r47zen.KeypadTouches.tap
import io.github.ppigazzini.r47zen.KeypadTouches.waitUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Copy X and Paste Number on the real MainActivity, through JNI, the core, and the
// system clipboard. The test runs the handlers the two menu items call; the popup
// menu's own drawing is not exercised. It is a class of its own, which the connected
// wrapper runs in a selection of its own, so the key test's touches never follow its
// copy in one run.
//
// From API 33 a copy shows a clipboard overlay, and while another window holds the
// focus the platform refuses the app's clipboard reads, the test's and the paste's
// alike. On CI's emulator the test saw no copy in 15 s while a SystemUI window held
// the focus. So the test reads, and lets the app read, with the shell's
// READ_CLIPBOARD_IN_BACKGROUND, for its own run only.
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ClipboardRoundTripInstrumentedTest {
    @Test
    fun copyXThenPasteNumberBringsTheValueBack() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(READ_CLIPBOARD_IN_BACKGROUND)
        try {
            roundTrip()
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun roundTrip() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "Native runtime did not become ready",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )
            ProgramLoadTestBridge.resetRuntime()
            assertTrue("seed X", ProgramLoadTestBridge.seedXReal("-12.5"))
            val copied = ProgramLoadTestBridge.getXRegisterClipboardText()
            assertEquals("X as the clipboard copies it", "-12.5", copied)

            onDisplayActions(scenario) {
                it.handleCopyMenuSelection(DisplayActionController.MENU_COPY_X_REGISTER)
            }
            assertTrue(
                "Copy X put '$copied' on the clipboard",
                waitUntil(ANSWER_TIMEOUT_MS) { clipboardText() == copied },
            )

            assertTrue("seed X", ProgramLoadTestBridge.seedXReal("0"))
            onDisplayActions(scenario) {
                it.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}
            }
            // The paste and the touched ENTER are core tasks, run in order: the paste
            // opens number entry with X still 0, and ENTER closes it. A bridge read waits
            // for neither, so poll X.
            tap(scenario, KEY_ENTER)
            var x = ""
            val pasted = waitUntil(ANSWER_TIMEOUT_MS) {
                x = ProgramLoadTestBridge.getXRegisterClipboardText()
                x == copied
            }
            assertTrue("Paste Number then ENTER left X '$x'", pasted)
        }
    }

    // The activity's own controller, which its menu items call, on the main thread.
    // The field is read by name, which the connected lane's unminified release keeps.
    private fun onDisplayActions(
        scenario: ActivityScenario<MainActivity>,
        action: (DisplayActionController) -> Unit,
    ) {
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("displayActionController")
            field.isAccessible = true
            action(field.get(activity) as DisplayActionController)
        }
    }

    private fun clipboard(): ClipboardManager {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    // The text of the clip Copy X makes, labeled as DisplayActionController labels it,
    // so a clip left from before the copy does not count.
    private fun clipboardText(): String? {
        val clip = clipboard().primaryClip ?: return null
        val copiedByTheApp = clip.description?.label == COPY_X_LABEL && clip.itemCount > 0
        return if (copiedByTheApp) clip.getItemAt(0).text?.toString() else null
    }

    private companion object {
        const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"
        const val COPY_X_LABEL = "R47 X Register"
        const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        // The copy is a core task, then a post to the main thread.
        const val ANSWER_TIMEOUT_MS = 15_000L
    }
}
