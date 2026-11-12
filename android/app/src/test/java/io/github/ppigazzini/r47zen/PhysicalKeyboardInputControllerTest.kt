package io.github.ppigazzini.r47zen

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The app's two keyboard entries beside the PC keys GTK has: Ctrl+V pastes and
// still reaches the calculator, which marks Ctrl as used; the Menu key opens the main
// menu and reaches nothing else. A Shift or Ctrl up Android cancels goes as a cancel.
@RunWith(RobolectricTestRunner::class)
class PhysicalKeyboardInputControllerTest {
    private val sent = mutableListOf<Triple<Int, Int, Boolean>>()
    private val canceled = mutableListOf<Int>()
    private var pastes = 0
    private var menus = 0
    private val controller = PhysicalKeyboardInputController(
        sendPcKey = { keysym, state, press -> sent += Triple(keysym, state, press) },
        cancelPcKey = { canceled += it },
        onPaste = { pastes++ },
        onMainMenu = { menus++ },
    )

    @Test
    fun ctrlV_pastesOnce_andStillSendsTheKey() {
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, CTRL)))
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, CTRL, repeat = 1)))
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, CTRL)))

        assertEquals(1, pastes)
        assertEquals(
            listOf('v'.code to true, 'v'.code to true, 'v'.code to false),
            sent.map { it.first to it.third },
        )
        assertTrue(sent.all { it.second and PcKeys.CONTROL_MASK != 0 })
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
        assertEquals(emptyList<Triple<Int, Int, Boolean>>(), sent)
    }

    // Android cancels a key's up when focus leaves the window, for a dialog, the main
    // menu, or another app: the key was not let go on purpose.
    @Test
    fun aCanceledShiftOrCtrlUp_goesAsACancel_notARelease() {
        for (code in listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT)) {
            assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, code)))
            assertTrue(controller.onKeyEvent(canceledUp(code)))
        }

        assertEquals(listOf(PcKeys.SHIFT_L to true, PcKeys.CONTROL_L to true), sent.map { it.first to it.third })
        assertEquals(listOf(PcKeys.SHIFT_L, PcKeys.CONTROL_L), canceled)
    }

    @Test
    fun anyOtherCanceledUp_isStillARelease() {
        assertTrue(controller.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1)))
        assertTrue(controller.onKeyEvent(canceledUp(KeyEvent.KEYCODE_F1)))

        assertEquals(listOf(PcKeys.F1 to true, PcKeys.F1 to false), sent.map { it.first to it.third })
        assertEquals(emptyList<Int>(), canceled)
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
