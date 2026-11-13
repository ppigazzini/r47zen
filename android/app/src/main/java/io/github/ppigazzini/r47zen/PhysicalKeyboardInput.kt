package io.github.ppigazzini.r47zen

import android.view.KeyCharacterMap
import android.view.KeyEvent

/** A PC key as upstream's GTK simulator receives it: the X11 keysym and the modifiers before the event. */
internal data class PcKey(val keysym: Int, val state: Int)

/**
 * Android key events in X11's terms, which pc_keyboard.c handles as GTK's keyPressedNow and
 * keyReleasedNow do. A character below 0x100 is its own keysym and any other is GDK's
 * Unicode keysym; a dead key is GDK's dead keysym, so the native side composes it as GTK
 * does. test_physical_keyboard_contract.py holds these keysyms to pc_keyboard.c's.
 */
internal object PcKeys {
    const val BACK_SPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PRIOR = 0xff55
    const val NEXT = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val NUM_LOCK = 0xff7f
    const val KP_ENTER = 0xff8d
    const val KP_HOME = 0xff95
    const val KP_LEFT = 0xff96
    const val KP_UP = 0xff97
    const val KP_RIGHT = 0xff98
    const val KP_DOWN = 0xff99
    const val KP_PRIOR = 0xff9a
    const val KP_NEXT = 0xff9b
    const val KP_END = 0xff9c
    const val KP_BEGIN = 0xff9d
    const val KP_INSERT = 0xff9e
    const val KP_DELETE = 0xff9f
    const val KP_MULTIPLY = 0xffaa
    const val KP_ADD = 0xffab
    const val KP_SEPARATOR = 0xffac
    const val KP_SUBTRACT = 0xffad
    const val KP_DECIMAL = 0xffae
    const val KP_DIVIDE = 0xffaf
    const val KP_0 = 0xffb0
    const val KP_EQUAL = 0xffbd
    const val F1 = 0xffbe
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val CAPS_LOCK = 0xffe5
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val SUPER_R = 0xffec
    const val DELETE = 0xffff
    const val DEAD_GRAVE = 0xfe50
    const val DEAD_ACUTE = 0xfe51
    const val DEAD_CIRCUMFLEX = 0xfe52
    const val DEAD_TILDE = 0xfe53
    const val DEAD_MACRON = 0xfe54
    const val DEAD_BREVE = 0xfe55
    const val DEAD_ABOVEDOT = 0xfe56
    const val DEAD_DIAERESIS = 0xfe57
    const val DEAD_ABOVERING = 0xfe58
    const val DEAD_DOUBLEACUTE = 0xfe59
    const val DEAD_CARON = 0xfe5a
    const val DEAD_CEDILLA = 0xfe5b
    const val DEAD_OGONEK = 0xfe5c
    const val UNICODE = 0x01000000

    // GDK's modifier bits: Mod1 is Alt, Mod2 NumLock, and Mod5 AltGr, as on Linux.
    const val SHIFT_MASK = 0x1
    const val LOCK_MASK = 0x2
    const val CONTROL_MASK = 0x4
    const val MOD1_MASK = 0x8
    const val MOD2_MASK = 0x10
    const val MOD5_MASK = 0x80

    private const val LATIN1_END = 0x100
    private const val FUNCTION_KEYS = 12

    private val keyCodeKeysyms: Map<Int, Int> = buildMap {
        put(KeyEvent.KEYCODE_DEL, BACK_SPACE)
        put(KeyEvent.KEYCODE_TAB, TAB)
        put(KeyEvent.KEYCODE_ENTER, RETURN)
        put(KeyEvent.KEYCODE_ESCAPE, ESCAPE)
        put(KeyEvent.KEYCODE_MOVE_HOME, HOME)
        put(KeyEvent.KEYCODE_DPAD_LEFT, LEFT)
        put(KeyEvent.KEYCODE_DPAD_UP, UP)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, RIGHT)
        put(KeyEvent.KEYCODE_DPAD_DOWN, DOWN)
        put(KeyEvent.KEYCODE_PAGE_UP, PRIOR)
        put(KeyEvent.KEYCODE_PAGE_DOWN, NEXT)
        put(KeyEvent.KEYCODE_MOVE_END, END)
        put(KeyEvent.KEYCODE_INSERT, INSERT)
        put(KeyEvent.KEYCODE_NUM_LOCK, NUM_LOCK)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, KP_ENTER)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, KP_MULTIPLY)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, KP_ADD)
        put(KeyEvent.KEYCODE_NUMPAD_COMMA, KP_SEPARATOR)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KP_SUBTRACT)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, KP_DIVIDE)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, KP_EQUAL)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, SHIFT_L)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, SHIFT_R)
        put(KeyEvent.KEYCODE_CTRL_LEFT, CONTROL_L)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, CONTROL_R)
        put(KeyEvent.KEYCODE_CAPS_LOCK, CAPS_LOCK)
        put(KeyEvent.KEYCODE_ALT_LEFT, ALT_L)
        put(KeyEvent.KEYCODE_ALT_RIGHT, ALT_R)
        put(KeyEvent.KEYCODE_META_LEFT, SUPER_L)
        put(KeyEvent.KEYCODE_META_RIGHT, SUPER_R)
        put(KeyEvent.KEYCODE_FORWARD_DEL, DELETE)
        for (n in 0 until FUNCTION_KEYS) {
            put(KeyEvent.KEYCODE_F1 + n, F1 + n)
        }
    }

    // X11's numeric keypad: the digits with NumLock on, and these moves without it, or
    // with Shift, which inverts NumLock there.
    private val keypadMoves = listOf(
        KP_INSERT, KP_END, KP_DOWN, KP_NEXT, KP_LEFT, KP_BEGIN, KP_RIGHT, KP_HOME, KP_UP, KP_PRIOR,
    )

    private val deadKeysyms = mapOf(
        0x300 to DEAD_GRAVE,
        0x301 to DEAD_ACUTE,
        0x302 to DEAD_CIRCUMFLEX,
        0x303 to DEAD_TILDE,
        0x304 to DEAD_MACRON,
        0x306 to DEAD_BREVE,
        0x307 to DEAD_ABOVEDOT,
        0x308 to DEAD_DIAERESIS,
        0x30a to DEAD_ABOVERING,
        0x30b to DEAD_DOUBLEACUTE,
        0x30c to DEAD_CARON,
        0x327 to DEAD_CEDILLA,
        0x328 to DEAD_OGONEK,
    )

    private val modifierMasks = mapOf(
        KeyEvent.KEYCODE_SHIFT_LEFT to SHIFT_MASK,
        KeyEvent.KEYCODE_SHIFT_RIGHT to SHIFT_MASK,
        KeyEvent.KEYCODE_CTRL_LEFT to CONTROL_MASK,
        KeyEvent.KEYCODE_CTRL_RIGHT to CONTROL_MASK,
        KeyEvent.KEYCODE_ALT_LEFT to MOD1_MASK,
        KeyEvent.KEYCODE_ALT_RIGHT to MOD5_MASK,
    )

    /** The keysym of a typed character. */
    fun keysymOf(codePoint: Int): Int = if (codePoint < LATIN1_END) codePoint else UNICODE or codePoint

    /** The dead keysym of a combining accent, or null for one GDK has none for. */
    fun deadKeysymOf(accent: Int): Int? = deadKeysyms[accent]

    /**
     * The PC key an event gives, or null for a key that is no PC key, such as Back or a volume key.
     * [characterOf] is the layout's character for the key under a meta state, 0 for none:
     * the platform's key character map, which a test replaces.
     */
    fun translate(event: KeyEvent, characterOf: (KeyEvent, Int) -> Int = KeyEvent::getUnicodeChar): PcKey? {
        val keysym = eventKeysym(event, characterOf) ?: return null
        var state = stateOf(event.metaState)
        modifierMasks[event.keyCode]?.let { own ->
            // X11 reports a modifier's own bit on its release, not on its press.
            state = if (event.action == KeyEvent.ACTION_DOWN) state and own.inv() else state or own
        }
        return PcKey(keysym, state)
    }

    private fun eventKeysym(event: KeyEvent, characterOf: (KeyEvent, Int) -> Int): Int? {
        val keypadDigit = event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
        val keypadDigits = event.isNumLockOn != event.isShiftPressed
        return when {
            event.keyCode in keyCodeKeysyms -> keyCodeKeysyms.getValue(event.keyCode)
            keypadDigit in keypadMoves.indices ->
                if (keypadDigits) KP_0 + keypadDigit else keypadMoves[keypadDigit]
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_DOT ->
                if (keypadDigits) KP_DECIMAL else KP_DELETE
            else -> characterKeysym(event, characterOf)
        }
    }

    private fun characterKeysym(event: KeyEvent, characterOf: (KeyEvent, Int) -> Int): Int? {
        // X11 keeps the keysym under Ctrl, Super, and the left Alt; the right Alt is AltGr.
        var meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv()
        val altGr = event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0
        if (!altGr) {
            meta = meta and KeyEvent.META_ALT_MASK.inv()
        }
        var unicode = characterOf(event, meta)
        // A layout with no character on the right Alt, such as US, has no AltGr: X11 then
        // types the plain key, as it does under the left Alt.
        if (unicode == 0 && altGr) {
            unicode = characterOf(event, meta and KeyEvent.META_ALT_MASK.inv())
        }
        return when {
            unicode == 0 -> null
            unicode and KeyCharacterMap.COMBINING_ACCENT != 0 ->
                deadKeysymOf(unicode and KeyCharacterMap.COMBINING_ACCENT_MASK)
            else -> keysymOf(unicode)
        }
    }

    private fun stateOf(meta: Int): Int {
        var state = 0
        if (meta and KeyEvent.META_SHIFT_ON != 0) state = state or SHIFT_MASK
        if (meta and KeyEvent.META_CAPS_LOCK_ON != 0) state = state or LOCK_MASK
        if (meta and KeyEvent.META_CTRL_ON != 0) state = state or CONTROL_MASK
        if (meta and KeyEvent.META_ALT_LEFT_ON != 0) state = state or MOD1_MASK
        if (meta and KeyEvent.META_NUM_LOCK_ON != 0) state = state or MOD2_MASK
        if (meta and KeyEvent.META_ALT_RIGHT_ON != 0) state = state or MOD5_MASK
        return state
    }
}
