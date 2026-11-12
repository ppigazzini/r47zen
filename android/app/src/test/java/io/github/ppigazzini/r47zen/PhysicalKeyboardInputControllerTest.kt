package io.github.ppigazzini.r47zen

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The app's two keyboard entries beside the calculator's keys: Ctrl+V pastes in place
// of the V key, and a Ctrl so used shifts nothing on its release; the Menu key opens
// the main menu and reaches nothing else.
@RunWith(RobolectricTestRunner::class)
class PhysicalKeyboardInputControllerTest {
    private val sent = mutableListOf<Pair<String, Boolean>>()
    private var pastes = 0
    private var menus = 0
    private val controller = PhysicalKeyboardInputController(
        offerCoreTask = { it.run() },
        sendSimKeyNative = { id, _, release -> sent += id to release },
        sendSimMenuNative = {},
        onPaste = { pastes++ },
        onMainMenu = { menus++ },
    )

    @Test
    fun ctrlV_pastesOnce_andSendsNoKey() {
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, CTRL)))
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, CTRL)))
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, CTRL, repeat = 1)))
        controller.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, CTRL))
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT)))

        assertEquals(1, pastes)
        assertEquals(emptyList<Pair<String, Boolean>>(), sent)
    }

    @Test
    fun v_withoutCtrlOrWithAnotherModifier_doesNotPaste() {
        controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V))
        controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, CTRL or KeyEvent.META_SHIFT_ON))
        controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, CTRL or KeyEvent.META_ALT_ON))
        controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, CTRL))

        assertEquals(0, pastes)
    }

    @Test
    fun menuKey_opensTheMainMenuOnRelease_andSendsNothing() {
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)))
        assertEquals(0, menus)
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)))

        assertEquals(1, menus)
        assertEquals(emptyList<Pair<String, Boolean>>(), sent)
    }

    // Android cancels a key's up when focus leaves the window, for a dialog, the main
    // menu, or another app: the key was not let go on purpose.
    @Test
    fun aCanceledShiftOrCtrlUp_shiftsNothing() {
        for (code in listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT)) {
            assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, code)))
            assertTrue(controller.onKeyEvent(canceledUp(code)))
        }

        assertEquals(emptyList<Pair<String, Boolean>>(), sent)
    }

    @Test
    fun aCanceledShiftUp_leavesTheNextShiftWorking() {
        controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        controller.onKeyEvent(canceledUp(KeyEvent.KEYCODE_SHIFT_LEFT))
        controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        controller.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))

        assertEquals(listOf(SimKeyIds.F_SHIFT to false, SimKeyIds.F_SHIFT to true), sent)
    }

    private fun key(action: Int, code: Int, meta: Int = 0, repeat: Int = 0): KeyEvent {
        return KeyEvent(0L, 0L, action, code, repeat, meta)
    }

    private fun canceledUp(code: Int): KeyEvent {
        return KeyEvent(0L, 0L, KeyEvent.ACTION_UP, code, 0, 0, 0, 0, KeyEvent.FLAG_CANCELED)
    }

    private companion object {
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    }
}
