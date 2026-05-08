package com.example.r47

import android.view.KeyEvent

internal data class PhysicalKeyboardCharacterBinding(
    val character: Char,
    val action: PhysicalKeyboardAction,
)

internal data class PhysicalKeyboardKeyCodeBinding(
    val keyCode: Int,
    val action: PhysicalKeyboardAction,
)

internal object PhysicalKeyboardBindingTables {
    val characterBindings = listOf(
        characterBinding('+', nativeKey("36")),
        characterBinding('-', nativeKey("31")),
        characterBinding('*', nativeKey("26")),
        characterBinding('/', nativeKey("21")),
        characterBinding('.', nativeKey("34")),
        characterBinding('=', shortcut("SEQ_DOTD")),
        characterBinding('0', nativeKey("33")),
        characterBinding('1', nativeKey("28")),
        characterBinding('2', nativeKey("29")),
        characterBinding('3', nativeKey("30")),
        characterBinding('4', nativeKey("23")),
        characterBinding('5', nativeKey("24")),
        characterBinding('6', nativeKey("25")),
        characterBinding('7', nativeKey("18")),
        characterBinding('&', shortcut("SEQ_toI")),
        characterBinding('8', nativeKey("19")),
        characterBinding('9', nativeKey("20")),
        characterBinding('%', shortcut("SEQ_PERCENT")),
        characterBinding('!', shortcut("SEQ_FACTORIAL")),
        characterBinding('@', shortcut("SEQ_DOTD")),
        characterBinding('#', shortcut("SEQ_HASH")),
        characterBinding('$', shortcut("SEQ_MS")),
        characterBinding('^', nativeKey("03")),
        characterBinding('q', nativeKey("01")),
        characterBinding('Q', nativeKey("00")),
        characterBinding('w', nativeKey("13")),
        characterBinding('W', shortcut("SEQ_LASTX")),
        characterBinding('e', nativeKey("15")),
        characterBinding('E', shortcut("SEQ_ECONST")),
        characterBinding('r', nativeKey("07")),
        characterBinding('R', shortcut("SEQ_toREC")),
        characterBinding('t', shortcut("SEQ_TAN")),
        characterBinding('T', shortcut("SEQ_ATAN")),
        characterBinding('y', shortcut("SEQ_XTHROOT")),
        characterBinding('Y', nativeKey("03")),
        characterBinding('u', shortcut("SEQ_UNDO")),
        characterBinding('U', shortcut("SEQ_USER")),
        characterBinding('i', shortcut("SEQ_IMAG_J")),
        characterBinding('I', shortcut("SEQ_DISP")),
        characterBinding('o', nativeKey("04")),
        characterBinding('O', shortcut("SEQ_10X")),
        characterBinding('p', shortcut("SEQ_PI")),
        characterBinding('P', shortcut("SEQ_toPOL")),
        characterBinding('a', shortcut("SEQ_SIGMAP")),
        characterBinding('A', shortcut("SEQ_ANGLE")),
        characterBinding('s', shortcut("SEQ_SIN")),
        characterBinding('S', shortcut("SEQ_ASIN")),
        characterBinding('d', nativeKey("08")),
        characterBinding('D', shortcut("SEQ_RUP")),
        characterBinding('f', nativeKey("10")),
        characterBinding('F', shortcut("SEQ_PREFIX")),
        characterBinding('g', nativeKey("11")),
        characterBinding('G', shortcut("SEQ_GTO")),
        characterBinding('H', shortcut("SEQ_HOME")),
        characterBinding('j', shortcut("SEQ_IMAG_J")),
        characterBinding('J', shortcut("SEQ_EXP")),
        characterBinding('k', shortcut("SEQ_IMAG_POL")),
        characterBinding('K', shortcut("SEQ_STK")),
        characterBinding('l', nativeKey("05")),
        characterBinding('L', shortcut("SEQ_EXP_E")),
        characterBinding('z', nativeKey("35")),
        characterBinding('Z', shortcut("SEQ_ABS")),
        characterBinding('x', nativeKey("17")),
        characterBinding('X', shortcut("SEQ_COMPLEX")),
        characterBinding('c', shortcut("SEQ_COS")),
        characterBinding('C', shortcut("SEQ_ACOS")),
        characterBinding('v', nativeKey("02")),
        characterBinding('V', nativeKey("02")),
        characterBinding('b', shortcut("SEQ_LBL")),
        characterBinding('B', shortcut("SEQ_MYMENU")),
        characterBinding('n', nativeKey("14")),
        characterBinding('N', shortcut("SEQ_PRGM")),
        characterBinding('m', nativeKey("06")),
        characterBinding('M', shortcut("SEQ_PREF")),
        characterBinding(',', nativeKey("34")),
        characterBinding('<', shortcut("SEQ_RTN")),
        characterBinding('>', shortcut("SEQ_DRG")),
        characterBinding(':', shortcut("SEQ_TGLFRT")),
        characterBinding('\'', shortcut("SEQ_ALPHA")),
        characterBinding('"', shortcut("SEQ_HASH")),
        characterBinding('\\', nativeKey("35")),
        characterBinding('|', shortcut("SEQ_ABS")),
    )

    val keyCodeBindings = listOf(
        keyCodeBinding(KeyEvent.KEYCODE_ENTER, nativeKey("12")),
        keyCodeBinding(KeyEvent.KEYCODE_NUMPAD_ENTER, nativeKey("12")),
        keyCodeBinding(KeyEvent.KEYCODE_ESCAPE, nativeKey("32")),
        keyCodeBinding(KeyEvent.KEYCODE_DEL, nativeKey("16")),
        keyCodeBinding(KeyEvent.KEYCODE_FORWARD_DEL, nativeKey("16")),
        keyCodeBinding(KeyEvent.KEYCODE_TAB, nativeKey("13")),
        keyCodeBinding(KeyEvent.KEYCODE_DPAD_UP, nativeKey("22")),
        keyCodeBinding(KeyEvent.KEYCODE_DPAD_DOWN, nativeKey("27")),
        keyCodeBinding(KeyEvent.KEYCODE_F1, functionKey("1")),
        keyCodeBinding(KeyEvent.KEYCODE_F2, functionKey("2")),
        keyCodeBinding(KeyEvent.KEYCODE_F3, functionKey("3")),
        keyCodeBinding(KeyEvent.KEYCODE_F4, functionKey("4")),
        keyCodeBinding(KeyEvent.KEYCODE_F5, functionKey("5")),
        keyCodeBinding(KeyEvent.KEYCODE_F6, functionKey("6")),
        keyCodeBinding(KeyEvent.KEYCODE_F7, shortcut("SEQ_SI_n")),
        keyCodeBinding(KeyEvent.KEYCODE_F8, shortcut("SEQ_SI_u")),
        keyCodeBinding(KeyEvent.KEYCODE_F9, shortcut("SEQ_SI_m")),
        keyCodeBinding(KeyEvent.KEYCODE_F10, shortcut("SEQ_SI_k")),
        keyCodeBinding(KeyEvent.KEYCODE_F11, shortcut("SEQ_SI_M")),
        keyCodeBinding(KeyEvent.KEYCODE_NUMPAD_ADD, nativeKey("36")),
        keyCodeBinding(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, nativeKey("31")),
        keyCodeBinding(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, nativeKey("26")),
        keyCodeBinding(KeyEvent.KEYCODE_NUMPAD_DIVIDE, nativeKey("21")),
    )

    private fun characterBinding(character: Char, action: PhysicalKeyboardAction) =
        PhysicalKeyboardCharacterBinding(character, action)

    private fun keyCodeBinding(keyCode: Int, action: PhysicalKeyboardAction) =
        PhysicalKeyboardKeyCodeBinding(keyCode, action)

    private fun nativeKey(id: String) = PhysicalKeyboardAction.NativeKey(id)

    private fun functionKey(id: String) = PhysicalKeyboardAction.NativeKey(
        id,
        isFunctionKey = true,
    )

    private fun shortcut(id: String) = PhysicalKeyboardAction.Shortcut(id)
}