package io.github.ppigazzini.r47zen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Shows what the calculator answers to a copy or a paste. Each answer arrives on the calculator thread. */
internal class CalculatorAnswers(
    private val context: Context,
    private val mainHandler: Handler,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val putClip: (ClipData) -> Unit = { clip ->
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    },
) {
    fun onClipboardText(what: Int, text: String) {
        val (clipLabel, confirmation) = when (what) {
            CalculatorEngine.COPY_X ->
                "R47 X Register" to R.string.main_menu_copy_x_register_copied
            CalculatorEngine.COPY_STACK ->
                "R47 Stack Registers" to R.string.main_menu_copy_stack_registers_copied
            CalculatorEngine.COPY_ALL ->
                "R47 All Registers" to R.string.main_menu_copy_all_registers_copied
            else -> return
        }
        mainHandler.post {
            // An empty text is the calculator's answer to a copy it could not build.
            val copied = text.isNotEmpty() && putOnClipboard(clipLabel, text)
            // From Android 13 the system confirms every copy itself, so a toast of the
            // app's would say it twice; a failure it does not report still gets one.
            if (!copied) {
                Toast.makeText(context, R.string.main_menu_copy_failed, Toast.LENGTH_SHORT).show()
            } else if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Text the core itself copies, as GTK's clipboard takes it: with no confirmation. */
    fun setClipboardText(text: String) {
        mainHandler.post {
            if (!putOnClipboard("R47", text)) {
                Toast.makeText(context, R.string.main_menu_copy_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onPasteResult(pasted: Boolean) {
        if (!pasted) {
            mainHandler.post {
                Toast.makeText(context, R.string.main_menu_paste_not_ready, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // A copy as large as all registers holding big matrices can exceed what a binder
    // call carries, and setPrimaryClip then throws: the system rethrows the binder's
    // failure as a plain RuntimeException, so there is no narrower type to catch.
    @Suppress("TooGenericExceptionCaught")
    private fun putOnClipboard(clipLabel: String, text: String): Boolean {
        return try {
            putClip(ClipData.newPlainText(clipLabel, text))
            true
        } catch (refused: RuntimeException) {
            Log.w(TAG, "the clipboard refused a copy of ${text.length} characters", refused)
            false
        }
    }

    private companion object {
        const val TAG = "CalculatorAnswers"
    }
}

/**
 * Shows a message from the core in a dialog. The core's messages run to five lines, and a Toast
 * shows two from Android 12 on: "This is not a C47 program" would lose "It will not be loaded."
 */
internal fun showCalculatorWarning(activityContext: Context, text: String) {
    MaterialAlertDialogBuilder(activityContext)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}
