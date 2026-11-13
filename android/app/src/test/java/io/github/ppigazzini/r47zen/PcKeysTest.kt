package io.github.ppigazzini.r47zen

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Each expected keysym and state is what X11 gives GTK for the same key, from keysymdef.h.
@RunWith(RobolectricTestRunner::class)
class PcKeysTest {
    @Test
    fun lettersAreTheirCharactersAndShiftIsInTheState() {
        assertEquals(PcKey('a'.code, 0), translate(KeyEvent.KEYCODE_A))
        assertEquals(PcKey('A'.code, PcKeys.SHIFT_MASK), translate(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON))
    }

    // Robolectric's key character map reads only Shift, so these go through a lookup shaped
    // like AOSP's Generic.kcm, where a letter has no character under Ctrl, Alt, or Meta.
    @Test
    fun ctrlKeepsTheKeysymAndSetsTheControlBit() {
        assertEquals(
            PcKey('c'.code, PcKeys.CONTROL_MASK),
            translate(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, generic),
        )
    }

    @Test
    fun theLeftAltKeepsTheKeysymAndSetsMod1() {
        assertEquals(
            PcKey('a'.code, PcKeys.MOD1_MASK),
            translate(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, generic),
        )
    }

    @Test
    fun theRightAltTypesTheLayoutsAltGrCharacter() {
        val german: (KeyEvent, Int) -> Int = { event, meta ->
            if (event.keyCode == KeyEvent.KEYCODE_Q && meta and KeyEvent.META_ALT_RIGHT_ON != 0) {
                '@'.code
            } else {
                generic(event, meta)
            }
        }

        assertEquals(
            PcKey('@'.code, PcKeys.MOD5_MASK),
            translate(KeyEvent.KEYCODE_Q, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON, german),
        )
    }

    @Test
    fun specialKeysAreTheirX11Keysyms() {
        assertEquals(0xff0d, translate(KeyEvent.KEYCODE_ENTER)?.keysym)
        assertEquals(0xff08, translate(KeyEvent.KEYCODE_DEL)?.keysym)
        assertEquals(0xffff, translate(KeyEvent.KEYCODE_FORWARD_DEL)?.keysym)
        assertEquals(0xff1b, translate(KeyEvent.KEYCODE_ESCAPE)?.keysym)
        assertEquals(0xff09, translate(KeyEvent.KEYCODE_TAB)?.keysym)
        assertEquals(0xff52, translate(KeyEvent.KEYCODE_DPAD_UP)?.keysym)
        assertEquals(0xff54, translate(KeyEvent.KEYCODE_DPAD_DOWN)?.keysym)
        assertEquals(0xffbe, translate(KeyEvent.KEYCODE_F1)?.keysym)
        assertEquals(0xffc9, translate(KeyEvent.KEYCODE_F12)?.keysym)
    }

    @Test
    fun keypadDigitsFollowNumLockAsX11Does() {
        assertEquals(
            PcKey(0xffb7, PcKeys.MOD2_MASK),
            translate(KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.META_NUM_LOCK_ON),
        )
        assertEquals(0xff95, translate(KeyEvent.KEYCODE_NUMPAD_7)?.keysym)
        val shiftWithNumLock = KeyEvent.META_NUM_LOCK_ON or KeyEvent.META_SHIFT_ON
        assertEquals(0xff95, translate(KeyEvent.KEYCODE_NUMPAD_7, shiftWithNumLock)?.keysym)
        // X11's keypad rule: Shift inverts NumLock both ways, so with NumLock off it gives KP_7.
        assertEquals(0xffb7, translate(KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.META_SHIFT_ON)?.keysym)
        assertEquals(0xff9f, translate(KeyEvent.KEYCODE_NUMPAD_DOT)?.keysym)
        assertEquals(0xffab, translate(KeyEvent.KEYCODE_NUMPAD_ADD)?.keysym)
    }

    @Test
    fun aModifiersOwnBitIsInTheStateOnItsReleaseOnly() {
        val shiftOn = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals(PcKey(0xffe1, 0), translate(KeyEvent.KEYCODE_SHIFT_LEFT, shiftOn))
        assertEquals(
            PcKey(0xffe1, PcKeys.SHIFT_MASK),
            translate(KeyEvent.KEYCODE_SHIFT_LEFT, 0, action = KeyEvent.ACTION_UP),
        )
        assertEquals(
            PcKey(0xffe3, PcKeys.CONTROL_MASK),
            translate(KeyEvent.KEYCODE_CTRL_LEFT, 0, action = KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun charactersBeyondLatin1AreUnicodeKeysyms() {
        assertEquals(0xe9, PcKeys.keysymOf(0xe9))
        assertEquals(0x01000105, PcKeys.keysymOf(0x105))
    }

    @Test
    fun combiningAccentsAreGdkDeadKeysyms() {
        assertEquals(0xfe51, PcKeys.deadKeysymOf(0x301))
        assertEquals(0xfe5c, PcKeys.deadKeysymOf(0x328))
        assertNull(PcKeys.deadKeysymOf(0x41))
    }

    @Test
    fun keysThatAreNoPcKeyStayWithAndroid() {
        assertNull(translate(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(translate(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun theControllerSendsEachPressAndReleaseAndLeavesTheRest() {
        val sent = mutableListOf<Triple<Int, Int, Boolean>>()
        val controller = PhysicalKeyboardInputController(
            sendPcKey = { keysym, state, press -> sent += Triple(keysym, state, press) },
            cancelPcKey = { },
        )

        assertTrue(controller.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S)))
        assertTrue(controller.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S)))
        assertFalse(controller.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))

        assertEquals(listOf(Triple('s'.code, 0, true), Triple('s'.code, 0, false)), sent)
    }

    private fun translate(
        keyCode: Int,
        meta: Int = 0,
        characterOf: (KeyEvent, Int) -> Int = KeyEvent::getUnicodeChar,
        action: Int = KeyEvent.ACTION_DOWN,
    ): PcKey? {
        return PcKeys.translate(KeyEvent(0L, 0L, action, keyCode, 0, meta), characterOf)
    }

    // AOSP's Generic.kcm for the letters: base lower case, Shift or Caps Lock upper case, and
    // "ctrl, alt, meta: none".
    private val generic: (KeyEvent, Int) -> Int = { event, meta ->
        val letter = event.keyCode - KeyEvent.KEYCODE_A
        val none = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK
        when {
            letter !in 0..25 || meta and none != 0 -> 0
            meta and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON) != 0 -> 'A'.code + letter
            else -> 'a'.code + letter
        }
    }
}
