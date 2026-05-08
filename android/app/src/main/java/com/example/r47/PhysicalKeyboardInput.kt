package com.example.r47

import android.view.KeyEvent
import java.util.concurrent.Executors

internal sealed interface PhysicalKeyboardAction {
    data class NativeKey(
        val id: String,
        val isFunctionKey: Boolean = false,
    ) : PhysicalKeyboardAction

    data class Shortcut(val id: String) : PhysicalKeyboardAction
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

            when (action.id) {
                "SEQ_PERCENT" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "07")
                }
                "SEQ_FACTORIAL" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "02")
                }
                "SEQ_DOTD" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "03")
                }
                "SEQ_HASH" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "05")
                }
                "SEQ_MS" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "02")
                }
                "SEQ_LASTX" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "13")
                }
                "SEQ_ECONST" -> {
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
                "SEQ_toREC" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "00")
                }
                "SEQ_TAN" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "20")
                }
                "SEQ_ATAN" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "20")
                }
                "SEQ_XTHROOT" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "03")
                }
                "SEQ_UNDO" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "16")
                }
                "SEQ_USER" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "09")
                }
                "SEQ_IMAG_J" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "00")
                }
                "SEQ_DISP" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "14")
                }
                "SEQ_10X" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "04")
                }
                "SEQ_PI" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "08")
                }
                "SEQ_toI" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "04")
                }
                "SEQ_HOME" -> enqueueMenu(-1921)
                "SEQ_MYMENU" -> enqueueMenu(-1349)
                "SEQ_toPOL" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "01")
                }
                "SEQ_IMAG_POL" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "01")
                }
                "SEQ_ALPHA" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "17")
                }
                "SEQ_SIGMAP" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "21", pauseAfterReleaseMs = LONG_PAUSE_MS)
                    tap(enqueueKey, "1", isFunctionKey = true)
                }
                "SEQ_ANGLE" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "06")
                }
                "SEQ_SIN" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "18")
                }
                "SEQ_ASIN" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "18")
                }
                "SEQ_RUP" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "08")
                }
                "SEQ_PREFIX" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "15")
                }
                "SEQ_GTO" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "17")
                }
                "SEQ_EXP" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "15")
                }
                "SEQ_COMPLEX" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "12")
                }
                "SEQ_STK" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "13")
                }
                "SEQ_EXP_E" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "05")
                }
                "SEQ_COS" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "19")
                }
                "SEQ_ACOS" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "19")
                }
                "SEQ_LBL" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "05")
                }
                "SEQ_PRGM" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "35")
                }
                "SEQ_PREF" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "28")
                }
                "SEQ_RTN" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "35")
                }
                "SEQ_DRG" -> press(enqueueKey, "09")
                "SEQ_SI_n" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "3", isFunctionKey = true)
                }
                "SEQ_SI_u" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "2", isFunctionKey = true)
                }
                "SEQ_SI_m" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "1", isFunctionKey = true)
                }
                "SEQ_SI_k" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
                    tap(enqueueKey, "1", isFunctionKey = true)
                }
                "SEQ_SI_M" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "15", pauseAfterReleaseMs = LONG_PAUSE_MS)
                    tap(enqueueKey, "2", isFunctionKey = true)
                }
                "SEQ_TGLFRT" -> {
                    tap(enqueueKey, "11", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "34")
                }
                "SEQ_AIM" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "17")
                }
                "SEQ_ABS" -> {
                    tap(enqueueKey, "10", pauseAfterReleaseMs = TAP_DELAY_MS)
                    tap(enqueueKey, "06")
                }
                else -> return@execute
            }
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