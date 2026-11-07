package io.github.ppigazzini.r47zen

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhysicalKeyboardInputParityTest {
    @Test
    fun mapperResolvesPrintableAndFunctionBindings() {
        val alphaAction = PhysicalKeyboardMapper.resolve(
            KeyEvent.KEYCODE_APOSTROPHE,
            keyEvent(KeyEvent.KEYCODE_APOSTROPHE),
        )
        assertShortcut(alphaAction, PhysicalKeyboardShortcutId.ALPHA)

        val homeAction = PhysicalKeyboardMapper.resolve(
            KeyEvent.KEYCODE_H,
            keyEvent(KeyEvent.KEYCODE_H, KeyEvent.META_SHIFT_ON),
        )
        assertShortcut(homeAction, PhysicalKeyboardShortcutId.HOME)

        val functionAction = PhysicalKeyboardMapper.resolve(
            KeyEvent.KEYCODE_F1,
            keyEvent(KeyEvent.KEYCODE_F1),
        )
        assertNativeKey(functionAction, id = "1", isFunctionKey = true)

        assertNull(
            PhysicalKeyboardMapper.resolve(
                KeyEvent.KEYCODE_SHIFT_LEFT,
                keyEvent(KeyEvent.KEYCODE_SHIFT_LEFT),
            ),
        )
    }

    @Test
    fun mapperResolvesNumpadOperatorsAndSiShortcutBindings() {
        assertNativeKey(
            PhysicalKeyboardMapper.resolve(
                KeyEvent.KEYCODE_NUMPAD_ADD,
                keyEvent(KeyEvent.KEYCODE_NUMPAD_ADD),
            ),
            id = "36",
            isFunctionKey = false,
        )
        assertNativeKey(
            PhysicalKeyboardMapper.resolve(
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
                keyEvent(KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
            ),
            id = "31",
            isFunctionKey = false,
        )
        assertNativeKey(
            PhysicalKeyboardMapper.resolve(
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
                keyEvent(KeyEvent.KEYCODE_NUMPAD_MULTIPLY),
            ),
            id = "26",
            isFunctionKey = false,
        )
        assertNativeKey(
            PhysicalKeyboardMapper.resolve(
                KeyEvent.KEYCODE_NUMPAD_DIVIDE,
                keyEvent(KeyEvent.KEYCODE_NUMPAD_DIVIDE),
            ),
            id = "21",
            isFunctionKey = false,
        )

        assertShortcut(
            PhysicalKeyboardMapper.resolve(KeyEvent.KEYCODE_F7, keyEvent(KeyEvent.KEYCODE_F7)),
            PhysicalKeyboardShortcutId.SI_N,
        )
        assertShortcut(
            PhysicalKeyboardMapper.resolve(KeyEvent.KEYCODE_F8, keyEvent(KeyEvent.KEYCODE_F8)),
            PhysicalKeyboardShortcutId.SI_U,
        )
        assertShortcut(
            PhysicalKeyboardMapper.resolve(KeyEvent.KEYCODE_F9, keyEvent(KeyEvent.KEYCODE_F9)),
            PhysicalKeyboardShortcutId.SI_M,
        )
        assertShortcut(
            PhysicalKeyboardMapper.resolve(KeyEvent.KEYCODE_F10, keyEvent(KeyEvent.KEYCODE_F10)),
            PhysicalKeyboardShortcutId.SI_K,
        )
        assertShortcut(
            PhysicalKeyboardMapper.resolve(KeyEvent.KEYCODE_F11, keyEvent(KeyEvent.KEYCODE_F11)),
            PhysicalKeyboardShortcutId.SI_MEGA,
        )
    }

    @Test
    fun shortcutDispatchReplaysAlphaAndHomeSequences() {
        assertEquals(
            listOf(
                "key:10:false:false",
                "key:10:false:true",
                "key:17:false:false",
                "key:17:false:true",
            ),
            dispatchShortcut(PhysicalKeyboardShortcutId.ALPHA),
        )

        assertEquals(
            listOf("menu:${NativeShellCodes.MENU_HOME}"),
            dispatchShortcut(PhysicalKeyboardShortcutId.HOME),
        )
        assertEquals(
            listOf("menu:${NativeShellCodes.MENU_MY_MENU}"),
            dispatchShortcut(PhysicalKeyboardShortcutId.MYMENU),
        )
    }

    @Test
    fun shiftTapOnlyFiresWhenModifierWasNotUsed() {
        val sentKeys = mutableListOf<String>()
        val controller = recordingController(sentKeys)

        assertTrue(controller.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.KEYCODE_SHIFT_LEFT)))
        assertTrue(controller.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(
            listOf("10:false:false", "10:false:true"),
            sentKeys,
        )

        sentKeys.clear()

        assertTrue(controller.onKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT, keyEvent(KeyEvent.KEYCODE_SHIFT_LEFT)))
        assertTrue(controller.onKeyDown(KeyEvent.KEYCODE_Q, keyEvent(KeyEvent.KEYCODE_Q, KeyEvent.META_SHIFT_ON)))
        assertTrue(controller.onKeyUp(KeyEvent.KEYCODE_Q))
        assertTrue(controller.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(
            listOf("00:false:false", "00:false:true"),
            sentKeys,
        )
    }

    @Test
    fun ctrlTapOnlyFiresWhenModifierWasNotUsed() {
        val sentActions = mutableListOf<String>()
        val controller = recordingController(sentActions)

        assertTrue(controller.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.KEYCODE_CTRL_LEFT)))
        assertTrue(controller.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(
            listOf("11:false:false", "11:false:true"),
            sentActions,
        )

        sentActions.clear()

        assertTrue(controller.onKeyDown(KeyEvent.KEYCODE_CTRL_LEFT, keyEvent(KeyEvent.KEYCODE_CTRL_LEFT)))
        assertTrue(controller.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, keyEvent(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_CTRL_ON)))
        assertTrue(controller.onKeyUp(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(controller.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(
            listOf("22:false:false", "22:false:true"),
            sentActions,
        )
    }

    @Test
    fun keyEventEntryPointDispatchesDownAndUpActions() {
        val sentActions = mutableListOf<String>()
        val controller = recordingController(sentActions)

        assertTrue(controller.onKeyEvent(keyEvent(KeyEvent.KEYCODE_0, action = KeyEvent.ACTION_DOWN)))
        assertTrue(controller.onKeyEvent(keyEvent(KeyEvent.KEYCODE_0, action = KeyEvent.ACTION_UP)))
        assertEquals(
            listOf("33:false:false", "33:false:true"),
            sentActions,
        )
    }

    @Test
    fun keyEventEntryPointIgnoresUnsupportedActions() {
        val controller = recordingController(mutableListOf())

        assertFalse(
            controller.onKeyEvent(
                keyEvent(
                    keyCode = KeyEvent.KEYCODE_A,
                    action = 99,
                    repeatCount = 1,
                ),
            ),
        )
    }

    // Spells each key code as the id it came from, and a release as the key it lets go.
    private fun recordKeys(record: (String) -> Unit): (Int) -> Unit {
        var pressed = ""
        return { code ->
            if (code == CalculatorEngine.KEY_RELEASE) {
                record("$pressed:true")
            } else {
                pressed = if (code >= 38) {
                    "${code - 37}:true"
                } else {
                    "%02d:false".format(java.util.Locale.ROOT, code - 1)
                }
                record("$pressed:false")
            }
        }
    }

    private fun recordingController(sent: MutableList<String>): PhysicalKeyboardInputController {
        return PhysicalKeyboardInputController(
            sendKey = recordKeys { sent += it },
            showShellMenu = { menuId -> sent += "menu:$menuId" },
        )
    }

    private fun dispatchShortcut(id: PhysicalKeyboardShortcutId): List<String> {
        val operations = mutableListOf<String>()
        PhysicalKeyboardShortcuts.dispatch(
            PhysicalKeyboardAction.Shortcut(id),
            sendKey = recordKeys { operations += "key:$it" },
            showShellMenu = { menuId -> operations += "menu:$menuId" },
        )
        return operations
    }

    private fun keyEvent(
        keyCode: Int,
        metaState: Int = 0,
        action: Int = KeyEvent.ACTION_DOWN,
        repeatCount: Int = 0,
    ): KeyEvent {
        return KeyEvent(0L, 0L, action, keyCode, repeatCount, metaState)
    }

    private fun assertShortcut(action: PhysicalKeyboardAction?, expectedId: PhysicalKeyboardShortcutId) {
        assertNotNull(action)
        assertTrue(action is PhysicalKeyboardAction.Shortcut)
        assertEquals(expectedId, (action as PhysicalKeyboardAction.Shortcut).id)
    }

    private fun assertNativeKey(action: PhysicalKeyboardAction?, id: String, isFunctionKey: Boolean) {
        assertNotNull(action)
        assertTrue(action is PhysicalKeyboardAction.NativeKey)
        action as PhysicalKeyboardAction.NativeKey
        assertEquals(id, action.id)
        assertEquals(isFunctionKey, action.isFunctionKey)
    }
}
