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
        characterBinding('=', shortcut(PhysicalKeyboardShortcutId.DOTD)),
        characterBinding('0', nativeKey("33")),
        characterBinding('1', nativeKey("28")),
        characterBinding('2', nativeKey("29")),
        characterBinding('3', nativeKey("30")),
        characterBinding('4', nativeKey("23")),
        characterBinding('5', nativeKey("24")),
        characterBinding('6', nativeKey("25")),
        characterBinding('7', nativeKey("18")),
        characterBinding('&', shortcut(PhysicalKeyboardShortcutId.TO_I)),
        characterBinding('8', nativeKey("19")),
        characterBinding('9', nativeKey("20")),
        characterBinding('%', shortcut(PhysicalKeyboardShortcutId.PERCENT)),
        characterBinding('!', shortcut(PhysicalKeyboardShortcutId.FACTORIAL)),
        characterBinding('@', shortcut(PhysicalKeyboardShortcutId.DOTD)),
        characterBinding('#', shortcut(PhysicalKeyboardShortcutId.HASH)),
        characterBinding('$', shortcut(PhysicalKeyboardShortcutId.MS)),
        characterBinding('^', nativeKey("03")),
        characterBinding('q', nativeKey("01")),
        characterBinding('Q', nativeKey("00")),
        characterBinding('w', nativeKey("13")),
        characterBinding('W', shortcut(PhysicalKeyboardShortcutId.LASTX)),
        characterBinding('e', nativeKey("15")),
        characterBinding('E', shortcut(PhysicalKeyboardShortcutId.ECONST)),
        characterBinding('r', nativeKey("07")),
        characterBinding('R', shortcut(PhysicalKeyboardShortcutId.TO_REC)),
        characterBinding('t', shortcut(PhysicalKeyboardShortcutId.TAN)),
        characterBinding('T', shortcut(PhysicalKeyboardShortcutId.ATAN)),
        characterBinding('y', shortcut(PhysicalKeyboardShortcutId.XTHROOT)),
        characterBinding('Y', nativeKey("03")),
        characterBinding('u', shortcut(PhysicalKeyboardShortcutId.UNDO)),
        characterBinding('U', shortcut(PhysicalKeyboardShortcutId.USER)),
        characterBinding('i', shortcut(PhysicalKeyboardShortcutId.IMAG_J)),
        characterBinding('I', shortcut(PhysicalKeyboardShortcutId.DISP)),
        characterBinding('o', nativeKey("04")),
        characterBinding('O', shortcut(PhysicalKeyboardShortcutId.TEN_TO_X)),
        characterBinding('p', shortcut(PhysicalKeyboardShortcutId.PI)),
        characterBinding('P', shortcut(PhysicalKeyboardShortcutId.TO_POL)),
        characterBinding('a', shortcut(PhysicalKeyboardShortcutId.SIGMAP)),
        characterBinding('A', shortcut(PhysicalKeyboardShortcutId.ANGLE)),
        characterBinding('s', shortcut(PhysicalKeyboardShortcutId.SIN)),
        characterBinding('S', shortcut(PhysicalKeyboardShortcutId.ASIN)),
        characterBinding('d', nativeKey("08")),
        characterBinding('D', shortcut(PhysicalKeyboardShortcutId.RUP)),
        characterBinding('f', nativeKey("10")),
        characterBinding('F', shortcut(PhysicalKeyboardShortcutId.PREFIX)),
        characterBinding('g', nativeKey("11")),
        characterBinding('G', shortcut(PhysicalKeyboardShortcutId.GTO)),
        characterBinding('H', shortcut(PhysicalKeyboardShortcutId.HOME)),
        characterBinding('j', shortcut(PhysicalKeyboardShortcutId.IMAG_J)),
        characterBinding('J', shortcut(PhysicalKeyboardShortcutId.EXP)),
        characterBinding('k', shortcut(PhysicalKeyboardShortcutId.IMAG_POL)),
        characterBinding('K', shortcut(PhysicalKeyboardShortcutId.STK)),
        characterBinding('l', nativeKey("05")),
        characterBinding('L', shortcut(PhysicalKeyboardShortcutId.EXP_E)),
        characterBinding('z', nativeKey("35")),
        characterBinding('Z', shortcut(PhysicalKeyboardShortcutId.ABS)),
        characterBinding('x', nativeKey("17")),
        characterBinding('X', shortcut(PhysicalKeyboardShortcutId.COMPLEX)),
        characterBinding('c', shortcut(PhysicalKeyboardShortcutId.COS)),
        characterBinding('C', shortcut(PhysicalKeyboardShortcutId.ACOS)),
        characterBinding('v', nativeKey("02")),
        characterBinding('V', nativeKey("02")),
        characterBinding('b', shortcut(PhysicalKeyboardShortcutId.LBL)),
        characterBinding('B', shortcut(PhysicalKeyboardShortcutId.MYMENU)),
        characterBinding('n', nativeKey("14")),
        characterBinding('N', shortcut(PhysicalKeyboardShortcutId.PRGM)),
        characterBinding('m', nativeKey("06")),
        characterBinding('M', shortcut(PhysicalKeyboardShortcutId.PREF)),
        characterBinding(',', nativeKey("34")),
        characterBinding('<', shortcut(PhysicalKeyboardShortcutId.RTN)),
        characterBinding('>', shortcut(PhysicalKeyboardShortcutId.DRG)),
        characterBinding(':', shortcut(PhysicalKeyboardShortcutId.TGLFRT)),
        characterBinding('\'', shortcut(PhysicalKeyboardShortcutId.ALPHA)),
        characterBinding('"', shortcut(PhysicalKeyboardShortcutId.HASH)),
        characterBinding('\\', nativeKey("35")),
        characterBinding('|', shortcut(PhysicalKeyboardShortcutId.ABS)),
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
        keyCodeBinding(KeyEvent.KEYCODE_F7, shortcut(PhysicalKeyboardShortcutId.SI_N)),
        keyCodeBinding(KeyEvent.KEYCODE_F8, shortcut(PhysicalKeyboardShortcutId.SI_U)),
        keyCodeBinding(KeyEvent.KEYCODE_F9, shortcut(PhysicalKeyboardShortcutId.SI_M)),
        keyCodeBinding(KeyEvent.KEYCODE_F10, shortcut(PhysicalKeyboardShortcutId.SI_K)),
        keyCodeBinding(KeyEvent.KEYCODE_F11, shortcut(PhysicalKeyboardShortcutId.SI_MEGA)),
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

    private fun shortcut(id: PhysicalKeyboardShortcutId) = PhysicalKeyboardAction.Shortcut(id)
}