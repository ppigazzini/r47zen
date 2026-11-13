package io.github.ppigazzini.r47zen

import android.content.ClipboardManager
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu

internal class DisplayActionController(
    private val context: Context,
    private val copyRegisters: (Int) -> Boolean,
    private val pasteNumberNative: (String) -> Boolean,
    private val enterPiP: () -> Unit,
) {
    companion object {
        const val MENU_SETTINGS = 1
        const val MENU_COPY = 2
        const val MENU_COPY_X_REGISTER = 3
        const val MENU_COPY_STACK_REGISTERS = 4
        const val MENU_COPY_ALL_REGISTERS = 5
        const val MENU_PASTE_NUMBER = 6
        const val MENU_PICTURE_IN_PICTURE = 7
    }

    internal fun popupMenuThemeContext(): Context {
        return ContextThemeWrapper(context, R.style.Theme_R47_PopupMenu)
    }

    private fun createPopupMenu(anchor: View): PopupMenu {
        return PopupMenu(popupMenuThemeContext(), anchor, Gravity.END)
    }

    fun showMainMenu(anchor: View, onOpenSettings: () -> Unit) {
        createPopupMenu(anchor).apply {
            populateMainMenu(menu)
            setOnMenuItemClickListener { item ->
                if (item.itemId == MENU_COPY) {
                    showCopyMenu(anchor)
                    true
                } else {
                    handleMainMenuSelection(item.itemId, onOpenSettings)
                }
            }
            show()
        }
    }

    internal fun populateMainMenu(menu: Menu) {
        menu.add(Menu.NONE, MENU_SETTINGS, 0, context.getString(R.string.main_menu_settings))
        menu.add(
            Menu.NONE,
            MENU_COPY,
            1,
            context.getString(R.string.main_menu_copy),
        )
        menu.add(
            Menu.NONE,
            MENU_PASTE_NUMBER,
            2,
            context.getString(R.string.main_menu_paste_number),
        )
        menu.add(
            Menu.NONE,
            MENU_PICTURE_IN_PICTURE,
            3,
            context.getString(R.string.main_menu_picture_in_picture),
        )
    }

    internal fun populateCopyMenu(menu: Menu) {
        menu.add(
            Menu.NONE,
            MENU_COPY_X_REGISTER,
            0,
            context.getString(R.string.main_menu_copy_x_register),
        )
        menu.add(
            Menu.NONE,
            MENU_COPY_STACK_REGISTERS,
            1,
            context.getString(R.string.main_menu_copy_stack_registers),
        )
        menu.add(
            Menu.NONE,
            MENU_COPY_ALL_REGISTERS,
            2,
            context.getString(R.string.main_menu_copy_all_registers),
        )
    }

    private fun showCopyMenu(anchor: View) {
        anchor.post {
            createPopupMenu(anchor).apply {
                populateCopyMenu(menu)
                setOnMenuItemClickListener { item -> handleCopyMenuSelection(item.itemId) }
                show()
            }
        }
    }

    internal fun handleMainMenuSelection(itemId: Int, onOpenSettings: () -> Unit): Boolean {
        return when (itemId) {
            MENU_SETTINGS -> {
                onOpenSettings()
                true
            }

            MENU_COPY -> true

            MENU_PASTE_NUMBER -> {
                pasteFromClipboard()
                true
            }

            MENU_PICTURE_IN_PICTURE -> {
                enterPiP()
                true
            }

            else -> false
        }
    }

    internal fun handleCopyMenuSelection(itemId: Int): Boolean {
        val what = when (itemId) {
            MENU_COPY_X_REGISTER -> CalculatorEngine.COPY_X
            MENU_COPY_STACK_REGISTERS -> CalculatorEngine.COPY_STACK
            MENU_COPY_ALL_REGISTERS -> CalculatorEngine.COPY_ALL
            else -> return false
        }
        // A full request queue refuses the copy, as it refuses a paste: say so.
        if (!copyRegisters(what)) {
            Toast.makeText(context, R.string.main_menu_copy_not_ready, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    // The menu's Paste Number, and Ctrl+V on a keyboard.
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // getPrimaryClip() can return a non-null ClipData with zero items on some
        // devices; guard the index so getItemAt(0) never throws
        // IndexOutOfBoundsException on the main thread (the copy path is likewise
        // defensive).
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?: return
        val tokens = PasteNumber.tokens(text)
        val failure = when {
            tokens == null -> R.string.main_menu_paste_not_a_number
            !pasteNumberNative(tokens) -> R.string.main_menu_paste_not_ready
            else -> null
        }
        failure?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
}
