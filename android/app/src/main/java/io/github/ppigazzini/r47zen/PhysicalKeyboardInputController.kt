package io.github.ppigazzini.r47zen

import android.view.KeyEvent

/**
 * Sends every PC key's press and release to the calculator, as GTK's keyPressed and
 * keyReleased push a PC key event, and keeps from Android only the keys that are no PC
 * key. What a key does is decided on the calculator thread, in the mode it lands in.
 *
 * Two keys also reach the app's own menu, which GTK has no counterpart of.
 * Ctrl+V pastes as the menu's Paste Number does; GTK binds nothing to it, so the key
 * still reaches the calculator, which only marks Ctrl as used, and the Ctrl release
 * then shifts nothing. The keyboard's Menu key, no PC key here, opens the main menu.
 */
internal class PhysicalKeyboardInputController(
    private val sendPcKey: (keysym: Int, state: Int, press: Boolean) -> Unit,
    private val onPaste: () -> Unit = {},
    private val onMainMenu: () -> Unit = {},
) {
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                onMainMenu()
            }
            return true
        }
        val isKeyEdge = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        val key = PcKeys.translate(event)?.takeIf { isKeyEdge }
        key?.let { sendPcKey(it.keysym, it.state, event.action == KeyEvent.ACTION_DOWN) }
        if (isPasteChord(event)) {
            onPaste()
        }
        return key != null
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
