package io.github.ppigazzini.r47zen

import android.view.KeyEvent

internal class PhysicalKeyboardInputController(
    private val sendKey: (Int) -> Unit,
    private val showShellMenu: (Int) -> Unit,
) {
    private var isPhysicalShiftHeld = false
    private var isPhysicalCtrlHeld = false
    private var interceptedWhileHeld = false
    private val activeKeyIdMap = mutableMapOf<Int, PhysicalKeyboardAction>()

    fun onKeyEvent(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> onKeyUp(event.keyCode)
            else -> false
        }
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            isPhysicalShiftHeld = true
            interceptedWhileHeld = false
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            isPhysicalCtrlHeld = true
            interceptedWhileHeld = false
            return true
        }

        if (isPhysicalShiftHeld || isPhysicalCtrlHeld) {
            interceptedWhileHeld = true
        }

        if ((event?.repeatCount ?: 0) > 0) {
            return true
        }

        PhysicalKeyboardMapper.resolve(keyCode, event)?.let { action ->
            activeKeyIdMap[keyCode] = action
            when (action) {
                is PhysicalKeyboardAction.NativeKey -> {
                    sendKey(SimKeyIds.code(action.id, action.isFunctionKey))
                }

                is PhysicalKeyboardAction.Shortcut -> {
                    PhysicalKeyboardShortcuts.dispatch(action, sendKey, showShellMenu)
                }
            }
            return true
        }

        return false
    }

    fun onKeyUp(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (!interceptedWhileHeld) {
                tapModifier(SimKeyIds.F_SHIFT)
            }
            isPhysicalShiftHeld = false
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            if (!interceptedWhileHeld) {
                tapModifier(SimKeyIds.G_SHIFT)
            }
            isPhysicalCtrlHeld = false
            return true
        }

        activeKeyIdMap.remove(keyCode)?.let { action ->
            if (action is PhysicalKeyboardAction.NativeKey) {
                sendKey(CalculatorEngine.KEY_RELEASE)
            }
            return true
        }

        return false
    }

    private fun tapModifier(id: String) {
        sendKey(SimKeyIds.code(id, false))
        sendKey(CalculatorEngine.KEY_RELEASE)
    }

    private fun isPasteChord(event: KeyEvent): Boolean {
        return event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            event.keyCode == KeyEvent.KEYCODE_V &&
            event.isCtrlPressed &&
            !event.isShiftPressed &&
            !event.isAltPressed &&
            !event.isMetaPressed
    }
}
