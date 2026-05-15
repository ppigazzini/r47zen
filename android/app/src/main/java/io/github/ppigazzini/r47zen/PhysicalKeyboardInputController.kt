package io.github.ppigazzini.r47zen

import android.view.KeyEvent

internal class PhysicalKeyboardInputController(
    private val offerCoreTask: (Runnable) -> Unit,
    private val sendSimKeyNative: (String, Boolean, Boolean) -> Unit,
    private val sendSimMenuNative: (Int) -> Unit,
) {
    private var isPhysicalShiftHeld = false
    private var isPhysicalCtrlHeld = false
    private var interceptedWhileHeld = false
    private val activeKeyIdMap = mutableMapOf<Int, PhysicalKeyboardAction>()

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

        if (event?.repeatCount ?: 0 > 0) {
            return true
        }

        PhysicalKeyboardMapper.resolve(keyCode, event)?.let { action ->
            activeKeyIdMap[keyCode] = action
            when (action) {
                is PhysicalKeyboardAction.NativeKey -> {
                    offerCoreTask(Runnable {
                        sendSimKeyNative(action.id, action.isFunctionKey, false)
                    })
                }

                is PhysicalKeyboardAction.Shortcut -> {
                    PhysicalKeyboardShortcuts.dispatch(
                        action,
                        offerCoreTask,
                        sendSimKeyNative,
                        sendSimMenuNative,
                    )
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
                tapModifier("10")
            }
            isPhysicalShiftHeld = false
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            if (!interceptedWhileHeld) {
                tapModifier("11")
            }
            isPhysicalCtrlHeld = false
            return true
        }

        activeKeyIdMap.remove(keyCode)?.let { action ->
            if (action is PhysicalKeyboardAction.NativeKey) {
                offerCoreTask(Runnable {
                    sendSimKeyNative(action.id, action.isFunctionKey, true)
                })
            }
            return true
        }

        return false
    }

    private fun tapModifier(id: String) {
        sendSimKeyNative(id, false, false)
        sendSimKeyNative(id, false, true)
    }
}
