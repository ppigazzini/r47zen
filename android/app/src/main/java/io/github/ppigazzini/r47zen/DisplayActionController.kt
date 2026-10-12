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

    private val paster = ClipboardPaster(mainHandler, offerCoreTask, sendSimFuncNative, sendSimKeyNative)

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
        // getPrimaryClip() can return a non-null ClipData with zero items on some
        // devices; guard the index so getItemAt(0) never throws
        // IndexOutOfBoundsException on the main thread (the copy path is likewise
        // defensive).
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) {
            return
        }
        val text = clip.getItemAt(0)?.text?.toString() ?: return

        paster.paste(text)
    }
}

// Types a pasted number into the core one keystroke at a time, the way the
// keypad would: each press, release, or function is its own core task,
// STEP_DELAY_MS apart, and the next is scheduled from the main thread, so the
// core thread runs keys and timer ticks between them instead of sleeping
// through the clip. Every method runs on the main thread.
internal class ClipboardPaster(
    private val mainHandler: Handler,
    private val offerCoreTask: (Runnable) -> Unit,
    private val sendSimFuncNative: (Int) -> Unit,
    private val sendSimKeyNative: (String, Boolean, Boolean) -> Unit,
) {
    companion object {
        // A clip replays at up to two steps per character. Only numeric-entry
        // glyphs are meaningful, so a few hundred characters is far more than
        // any real pasted number; anything longer is truncated.
        const val MAX_CHARS = 256
        const val STEP_DELAY_MS = 50L

        // The core's key ids for the glyphs a pasted number can hold.
        private val KEY_IDS = mapOf(
            '0' to "33", '1' to "28", '2' to "29", '3' to "30", '4' to "23",
            '5' to "24", '6' to "25", '7' to "18", '8' to "19", '9' to "20",
            '.' to "34", ',' to "34", '-' to "35", 'e' to SimKeyIds.EEX, '+' to "37",
        )

        // The keystrokes that enter text: i and j as their functions, every
        // other known glyph as a press and a release of its key, the rest
        // skipped.
        fun steps(text: String): List<Step> {
            val steps = ArrayList<Step>()
            for (char in text) {
                when (char) {
                    'i' -> steps.add(Step.Function(NativeShellCodes.FUNC_OP_I))
                    'j' -> steps.add(Step.Function(NativeShellCodes.FUNC_OP_J))
                    else -> KEY_IDS[char.lowercaseChar()]?.let { keyId ->
                        steps.add(Step.Key(keyId, isRelease = false))
                        steps.add(Step.Key(keyId, isRelease = true))
                    }
                }
            }
            return steps
        }
    }

    sealed interface Step {
        data class Function(val funcId: Int) : Step
        data class Key(val keyId: String, val isRelease: Boolean) : Step
    }

    // Bumped by every paste, so a paste started while another is replaying
    // stops the older one; pendingRelease is the release of a key that paste
    // pressed and has not let go, which the new paste offers first so no key
    // stays down.
    private var generation = 0
    private var pendingRelease: Step.Key? = null

    fun paste(text: String) {
        val steps = steps(text.take(MAX_CHARS))
        if (steps.isEmpty()) {
            return
        }
        generation += 1
        pendingRelease?.let { offer(it) }
        run(steps, 0, generation)
    }

    private fun run(steps: List<Step>, index: Int, runGeneration: Int) {
        if (runGeneration != generation || index >= steps.size) {
            return
        }
        offer(steps[index])
        mainHandler.postDelayed({ run(steps, index + 1, runGeneration) }, STEP_DELAY_MS)
    }

    private fun offer(step: Step) {
        pendingRelease = if (step is Step.Key && !step.isRelease) step.copy(isRelease = true) else null
        offerCoreTask(
            Runnable {
                when (step) {
                    is Step.Function -> sendSimFuncNative(step.funcId)
                    is Step.Key -> sendSimKeyNative(step.keyId, false, step.isRelease)
                }
            }
        )
    }
}
