package io.github.ppigazzini.r47

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
            dispatchShortcut(PhysicalKeyboardShortcutId.ALPHA, expectedOperations = 4),
        )

        assertEquals(
            listOf("menu:-1921"),
            dispatchShortcut(PhysicalKeyboardShortcutId.HOME, expectedOperations = 1),
        )
    }

    @Test
    fun shiftTapOnlyFiresWhenModifierWasNotUsed() {
        val sentKeys = mutableListOf<String>()
        val controller = PhysicalKeyboardInputController(
            offerCoreTask = { runnable -> runnable.run() },
            sendSimKeyNative = { id, isFunctionKey, isRelease ->
                sentKeys += "${id}:${isFunctionKey}:${isRelease}"
            },
            sendSimMenuNative = { _ -> },
        )

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
        val controller = PhysicalKeyboardInputController(
            offerCoreTask = { runnable -> runnable.run() },
            sendSimKeyNative = { id, isFunctionKey, isRelease ->
                sentActions += "${id}:${isFunctionKey}:${isRelease}"
            },
            sendSimMenuNative = { menuId ->
                sentActions += "menu:${menuId}"
            },
        )

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

    private fun dispatchShortcut(id: PhysicalKeyboardShortcutId, expectedOperations: Int): List<String> {
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(expectedOperations)

        PhysicalKeyboardShortcuts.dispatch(
            PhysicalKeyboardAction.Shortcut(id),
            offerCoreTask = { runnable ->
                runnable.run()
                latch.countDown()
            },
            sendKey = { keyId, isFunctionKey, isRelease ->
                operations += "key:${keyId}:${isFunctionKey}:${isRelease}"
            },
            sendMenu = { menuId ->
                operations += "menu:${menuId}"
            },
        )

        assertTrue("Timed out waiting for $id", latch.await(2, TimeUnit.SECONDS))
        return operations.toList()
    }

    private fun keyEvent(keyCode: Int, metaState: Int = 0): KeyEvent {
        return KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
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
