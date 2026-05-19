package io.github.ppigazzini.r47zen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import java.util.Locale

internal class DisplayActionController(
    private val context: Context,
    private val mainHandler: Handler,
    private val offerCoreTask: (Runnable) -> Unit,
    private val getXRegisterNative: () -> String,
    private val sendSimFuncNative: (Int) -> Unit,
    private val sendSimKeyNative: (String, Boolean, Boolean) -> Unit,
    private val enterPiP: () -> Unit,
) {
    companion object {
        private const val TAG = "R47DisplayActions"
        private const val MENU_SETTINGS = 1
        private const val MENU_COPY_X_REGISTER = 2
        private const val MENU_PASTE_NUMBER = 3
        private const val MENU_PICTURE_IN_PICTURE = 4
    }

    fun showMainMenu(anchor: View, onOpenSettings: () -> Unit) {
        PopupMenu(context, anchor, Gravity.END).apply {
            menu.add(Menu.NONE, MENU_SETTINGS, 0, context.getString(R.string.main_menu_settings))
            menu.add(
                Menu.NONE,
                MENU_COPY_X_REGISTER,
                1,
                context.getString(R.string.main_menu_copy_x_register),
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
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SETTINGS -> {
                        onOpenSettings()
                        true
                    }

                    MENU_COPY_X_REGISTER -> {
                        copyXToClipboard()
                        true
                    }

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
            show()
        }
    }

    private fun copyXToClipboard() {
        offerCoreTask(
            Runnable {
                try {
                    val xRegisterValue = getXRegisterNative()
                    mainHandler.post {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("R47 X Register", xRegisterValue)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(
                            context,
                            "Copied: $xRegisterValue",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
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
