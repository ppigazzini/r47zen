package io.github.ppigazzini.r47zen

import android.view.KeyEvent
import java.util.concurrent.Executors

internal sealed interface PhysicalKeyboardAction {
    data class NativeKey(
        val id: String,
        val isFunctionKey: Boolean = false,
    ) : PhysicalKeyboardAction

    data class Shortcut(val id: PhysicalKeyboardShortcutId) : PhysicalKeyboardAction
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

internal object PhysicalKeyboardShortcuts {
    private const val TAP_DELAY_MS = 50L
    private const val LONG_PAUSE_MS = 100L

    private val shortcutExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "R47ShortcutDispatch").apply {
            isDaemon = true
        }
    }

    fun dispatch(
        action: PhysicalKeyboardAction.Shortcut,
        offerCoreTask: (Runnable) -> Unit,
        sendKey: (String, Boolean, Boolean) -> Unit,
        sendMenu: (Int) -> Unit,
    ) {
        shortcutExecutor.execute {
            val enqueueKey = { id: String, isFunctionKey: Boolean, isRelease: Boolean ->
                offerCoreTask(Runnable { sendKey(id, isFunctionKey, isRelease) })
            }
            val enqueueMenu = { menuId: Int ->
                offerCoreTask(Runnable { sendMenu(menuId) })
            }

            dispatchShortcut(action.id, enqueueKey, enqueueMenu)
        }
    }

    private fun dispatchShortcut(
        id: PhysicalKeyboardShortcutId,
        enqueueKey: (String, Boolean, Boolean) -> Unit,
        enqueueMenu: (Int) -> Unit,
    ): Unit = when (id) {
        PhysicalKeyboardShortcutId.PERCENT -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "07")
        }
        PhysicalKeyboardShortcutId.FACTORIAL -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "02")
        }
        PhysicalKeyboardShortcutId.DOTD -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "03")
        }
        PhysicalKeyboardShortcutId.HASH -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "05")
        }
        PhysicalKeyboardShortcutId.MS -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "02")
        }
        PhysicalKeyboardShortcutId.LASTX -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "13")
        }
        PhysicalKeyboardShortcutId.ECONST -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "36", pauseAfterReleaseMs = LONG_PAUSE_MS)
            tap(
                enqueueKey,
                "2",
                isFunctionKey = true,
                pauseAfterReleaseMs = LONG_PAUSE_MS,
            )
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "2", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.TO_REC -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "00")
        }
        PhysicalKeyboardShortcutId.TAN -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "20")
        }
        PhysicalKeyboardShortcutId.ATAN -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "20")
        }
        PhysicalKeyboardShortcutId.XTHROOT -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "03")
        }
        PhysicalKeyboardShortcutId.UNDO -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "16")
        }
        PhysicalKeyboardShortcutId.USER -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "09")
        }
        PhysicalKeyboardShortcutId.IMAG_J -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "00")
        }
        PhysicalKeyboardShortcutId.DISP -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "14")
        }
        PhysicalKeyboardShortcutId.TEN_TO_X -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "04")
        }
        PhysicalKeyboardShortcutId.PI -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "08")
        }
        PhysicalKeyboardShortcutId.TO_I -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "04")
        }
        PhysicalKeyboardShortcutId.HOME -> enqueueMenu(-1921)
        PhysicalKeyboardShortcutId.MYMENU -> enqueueMenu(-1349)
        PhysicalKeyboardShortcutId.TO_POL -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "01")
        }
        PhysicalKeyboardShortcutId.IMAG_POL -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "01")
        }
        PhysicalKeyboardShortcutId.ALPHA,
        PhysicalKeyboardShortcutId.AIM -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "17")
        }
        PhysicalKeyboardShortcutId.SIGMAP -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "21", pauseAfterReleaseMs = LONG_PAUSE_MS)
            tap(enqueueKey, "1", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.ANGLE -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "06")
        }
        PhysicalKeyboardShortcutId.SIN -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "18")
        }
        PhysicalKeyboardShortcutId.ASIN -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "18")
        }
        PhysicalKeyboardShortcutId.RUP -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "08")
        }
        PhysicalKeyboardShortcutId.PREFIX -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "15")
        }
        PhysicalKeyboardShortcutId.GTO -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "17")
        }
        PhysicalKeyboardShortcutId.EXP -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "15")
        }
        PhysicalKeyboardShortcutId.COMPLEX -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "12")
        }
        PhysicalKeyboardShortcutId.STK -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "13")
        }
        PhysicalKeyboardShortcutId.EXP_E -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "05")
        }
        PhysicalKeyboardShortcutId.COS -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "19")
        }
        PhysicalKeyboardShortcutId.ACOS -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "19")
        }
        PhysicalKeyboardShortcutId.LBL -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "05")
        }
        PhysicalKeyboardShortcutId.PRGM -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "35")
        }
        PhysicalKeyboardShortcutId.PREF -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "28")
        }
        PhysicalKeyboardShortcutId.RTN -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "35")
        }
        PhysicalKeyboardShortcutId.DRG -> press(enqueueKey, "09")
        PhysicalKeyboardShortcutId.SI_N -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "3", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.SI_U -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "2", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.SI_M -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "1", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.SI_K -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
            tap(enqueueKey, "1", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.SI_MEGA -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
            tap(enqueueKey, "2", isFunctionKey = true)
        }
        PhysicalKeyboardShortcutId.TGLFRT -> {
            tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "34")
        }
        PhysicalKeyboardShortcutId.ABS -> {
            tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
            tap(enqueueKey, "06")
        }
    }

    private fun tap(
        sendKey: (String, Boolean, Boolean) -> Unit,
        id: String,
        isFunctionKey: Boolean = false,
        pauseAfterReleaseMs: Long = 0L,
    ) {
        press(sendKey, id, isFunctionKey)
        Thread.sleep(TAP_DELAY_MS)
        sendKey(id, isFunctionKey, true)
        if (pauseAfterReleaseMs > 0L) {
            Thread.sleep(pauseAfterReleaseMs)
        }
    }

    private fun press(
        sendKey: (String, Boolean, Boolean) -> Unit,
        id: String,
        isFunctionKey: Boolean = false,
    ) {
        sendKey(id, isFunctionKey, false)
    }
}
