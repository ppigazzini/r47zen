package io.github.ppigazzini.r47zen

import android.view.KeyEvent

internal sealed interface PhysicalKeyboardAction {
    data class NativeKey(
        val id: String,
        val isFunctionKey: Boolean = false,
    ) : PhysicalKeyboardAction

    data class Shortcut(val id: PhysicalKeyboardShortcutId) : PhysicalKeyboardAction
}

// The core's ids for the keys the shortcut sequences press most: the f and g
// shift keys, and EEX, the exponent key.
internal object SimKeyIds {
    const val F_SHIFT = "10"
    const val G_SHIFT = "11"
    const val EEX = "15"

    /** The key code of a key id: "00" to "36" for the keys, 1 to 37, and "1" to "6" for the softkeys, 38 to 43. */
    fun code(id: String, isFunctionKey: Boolean): Int {
        return if (isFunctionKey) id.toInt() + FIRST_SOFTKEY_CODE - 1 else id.toInt() + 1
    }

    private const val FIRST_SOFTKEY_CODE = 38
}

internal object PhysicalKeyboardMapper {
    private val ignoredModifierKeyCodes = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
    )

    private val characterBindings = PhysicalKeyboardBindingTables.characterBindings.associate {
        it.character to it.action
    }

    private val keyCodeBindings = PhysicalKeyboardBindingTables.keyCodeBindings.associate {
        it.keyCode to it.action
    }

    fun resolve(keyCode: Int, event: KeyEvent?): PhysicalKeyboardAction? {
        if (event == null || keyCode in ignoredModifierKeyCodes) {
            return null
        }

        val unicode = event.getUnicodeChar(event.metaState)
        if (unicode != 0) {
            characterBindings[unicode.toChar()]?.let { return it }
        }

        return keyCodeBindings[keyCode]
    }
}

// A shortcut is a run of keys on one FIFO, so nothing typed afterward can land inside it.
internal object PhysicalKeyboardShortcuts {
    fun dispatch(
        action: PhysicalKeyboardAction.Shortcut,
        sendKey: (Int) -> Unit,
        showShellMenu: (Int) -> Unit,
    ) {
        dispatchShortcut(action.id, sendKey, showShellMenu)
    }

    private fun dispatchShortcut(
        id: PhysicalKeyboardShortcutId,
        enqueueKey: (Int) -> Unit,
        enqueueMenu: (Int) -> Unit,
    ): Unit = when (id) {
        PhysicalKeyboardShortcutId.PERCENT -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "07")
        }
        PhysicalKeyboardShortcutId.FACTORIAL -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "02")
        }
        PhysicalKeyboardShortcutId.DOTD -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "03")
        }
        PhysicalKeyboardShortcutId.HASH -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "05")
        }
        PhysicalKeyboardShortcutId.MS -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "02")
        }
        PhysicalKeyboardShortcutId.LASTX -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "13")
        }
        PhysicalKeyboardShortcutId.ECONST -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "36")
            tap(
                enqueueKey,
                "2",
                isFunctionKey = true)
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "2", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.TO_REC -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "00")
        }
        PhysicalKeyboardShortcutId.TAN -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "20")
        }
        PhysicalKeyboardShortcutId.ATAN -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "20")
        }
        PhysicalKeyboardShortcutId.XTHROOT -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "03")
        }
        PhysicalKeyboardShortcutId.UNDO -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "16")
        }
        PhysicalKeyboardShortcutId.USER -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "09")
        }
        PhysicalKeyboardShortcutId.IMAG_J -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "00")
        }
        PhysicalKeyboardShortcutId.DISP -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "14")
        }
        PhysicalKeyboardShortcutId.TEN_TO_X -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "04")
        }
        PhysicalKeyboardShortcutId.PI -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "08")
        }
        PhysicalKeyboardShortcutId.TO_I -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "04")
        }
        PhysicalKeyboardShortcutId.HOME -> enqueueMenu(NativeShellCodes.MENU_HOME)
        PhysicalKeyboardShortcutId.MYMENU -> enqueueMenu(NativeShellCodes.MENU_MY_MENU)
        PhysicalKeyboardShortcutId.TO_POL -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "01")
        }
        PhysicalKeyboardShortcutId.IMAG_POL -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "01")
        }
        PhysicalKeyboardShortcutId.ALPHA,
        PhysicalKeyboardShortcutId.AIM -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "17")
        }
        PhysicalKeyboardShortcutId.SIGMAP -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "21")
            tap(enqueueKey, "1", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.ANGLE -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "06")
        }
        PhysicalKeyboardShortcutId.SIN -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "18")
        }
        PhysicalKeyboardShortcutId.ASIN -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "18")
        }
        PhysicalKeyboardShortcutId.RUP -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "08")
        }
        PhysicalKeyboardShortcutId.PREFIX -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, SimKeyIds.EEX)
        }
        PhysicalKeyboardShortcutId.GTO -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "17")
        }
        PhysicalKeyboardShortcutId.EXP -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, SimKeyIds.EEX)
        }
        PhysicalKeyboardShortcutId.COMPLEX -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "12")
        }
        PhysicalKeyboardShortcutId.STK -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "13")
        }
        PhysicalKeyboardShortcutId.EXP_E -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "05")
        }
        PhysicalKeyboardShortcutId.COS -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "19")
        }
        PhysicalKeyboardShortcutId.ACOS -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "19")
        }
        PhysicalKeyboardShortcutId.LBL -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "05")
        }
        PhysicalKeyboardShortcutId.PRGM -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "35")
        }
        PhysicalKeyboardShortcutId.PREF -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "28")
        }
        PhysicalKeyboardShortcutId.RTN -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "35")
        }
        PhysicalKeyboardShortcutId.DRG -> tap(enqueueKey, "09")
        PhysicalKeyboardShortcutId.SI_N -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, SimKeyIds.EEX)
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "3", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.SI_U -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, SimKeyIds.EEX)
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "2", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.SI_M -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, SimKeyIds.EEX)
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "1", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.SI_K -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, SimKeyIds.EEX)
            tap(enqueueKey, "1", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.SI_MEGA -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, SimKeyIds.EEX)
            tap(enqueueKey, "2", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.TGLFRT -> {
            tap(enqueueKey, SimKeyIds.G_SHIFT)
            tap(enqueueKey, "34")
        }
        PhysicalKeyboardShortcutId.ABS -> {
            tap(enqueueKey, SimKeyIds.F_SHIFT)
            tap(enqueueKey, "06")
        }
    }

    private fun tap(
        sendKey: (Int) -> Unit,
        id: String,
        isFunctionKey: Boolean = false,
    ) {
        sendKey(SimKeyIds.code(id, isFunctionKey))
        sendKey(CalculatorEngine.KEY_RELEASE)
    }
}
