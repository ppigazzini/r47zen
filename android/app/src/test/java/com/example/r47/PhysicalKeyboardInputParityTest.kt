package com.example.r47

import android.view.KeyEvent
import org.junit.Assert.assertEquals
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
        assertShortcut(alphaAction, "SEQ_ALPHA")

        val homeAction = PhysicalKeyboardMapper.resolve(
            KeyEvent.KEYCODE_H,
            keyEvent(KeyEvent.KEYCODE_H, KeyEvent.META_SHIFT_ON),
        )
        assertShortcut(homeAction, "SEQ_HOME")

        val functionAction = PhysicalKeyboardMapper.resolve(
            KeyEvent.KEYCODE_F1,
            keyEvent(KeyEvent.KEYCODE_F1),
        )
        assertNativeKey(functionAction, id = "1", isFunctionKey = true)
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
            dispatchShortcut("SEQ_ALPHA", expectedOperations = 4),
        )

        assertEquals(
            listOf("menu:-1921"),
            dispatchShortcut("SEQ_HOME", expectedOperations = 1),
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

    private fun dispatchShortcut(id: String, expectedOperations: Int): List<String> {
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

    private fun assertShortcut(action: PhysicalKeyboardAction?, expectedId: String) {
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