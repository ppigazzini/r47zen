package io.github.ppigazzini.r47zen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import java.util.Locale

internal class DisplayActionController(
    private val context: Context,
    private val mainHandler: Handler,
    private val offerCoreTask: (Runnable) -> Unit,
    private val getClipboardXRegisterNative: () -> String,
    private val getClipboardStackRegistersNative: () -> String,
    private val getClipboardAllRegistersNative: () -> String,
    private val sendSimFuncNative: (Int) -> Unit,
    private val sendSimKeyNative: (String, Boolean, Boolean) -> Unit,
    private val enterPiP: () -> Unit,
) {
    companion object {
        private const val TAG = "R47DisplayActions"
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
        mainHandler.post {
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
        return when (itemId) {
            MENU_COPY_X_REGISTER -> {
                copyTextToClipboard(
                    clipLabel = "R47 X Register",
                    textProvider = getClipboardXRegisterNative,
                    confirmation = context.getString(R.string.main_menu_copy_x_register_copied),
                )
                true
            }

            MENU_COPY_STACK_REGISTERS -> {
                copyTextToClipboard(
                    clipLabel = "R47 Stack Registers",
                    textProvider = getClipboardStackRegistersNative,
                    confirmation = context.getString(R.string.main_menu_copy_stack_registers_copied),
                )
                true
            }

            MENU_COPY_ALL_REGISTERS -> {
                copyTextToClipboard(
                    clipLabel = "R47 All Registers",
                    textProvider = getClipboardAllRegistersNative,
                    confirmation = context.getString(R.string.main_menu_copy_all_registers_copied),
                )
                true
            }

            else -> false
        }
    }

    private fun copyTextToClipboard(
        clipLabel: String,
        textProvider: () -> String,
        confirmation: String,
    ) {
        offerCoreTask(
            Runnable {
                try {
                    val clipboardText = textProvider()
                    mainHandler.post {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(clipLabel, clipboardText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Copy error", error)
                }
            }
        )
    }

    private fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val text = item?.text?.toString() ?: return

        offerCoreTask(
            Runnable {
                for (char in text) {
                    if (char == 'i' || char == 'j') {
                        sendSimFuncNative(if (char == 'i') 1159 else 1160)
                        Thread.sleep(50)
                        continue
                    }

                    val simIdNumber = when (char.lowercaseChar()) {
                        '0' -> 33
                        '1' -> 28
                        '2' -> 29
                        '3' -> 30
                        '4' -> 23
                        '5' -> 24
                        '6' -> 25
                        '7' -> 18
                        '8' -> 19
                        '9' -> 20
                        '.', ',' -> 34
                        '-' -> 35
                        'e' -> 15
                        '+' -> 37
                        else -> null
                    }

                    if (simIdNumber != null) {
                        val simId = String.format(Locale.US, "%02d", simIdNumber)
                        sendSimKeyNative(simId, false, false)
                        Thread.sleep(50)
                        sendSimKeyNative(simId, false, true)
                        Thread.sleep(50)
                    }
                }
            }
        )
    }
}
