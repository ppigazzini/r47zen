package io.github.ppigazzini.r47zen

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class CalculatorAnswersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // Before Android 13 the system shows no copy confirmation, so the app does.
    private val answers = CalculatorAnswers(context, Handler(Looper.getMainLooper()), sdkInt = 32)

    private fun clipboardText() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun fromAndroid13TheSystemAloneConfirmsACopy() {
        val onAndroid13 = CalculatorAnswers(context, Handler(Looper.getMainLooper()), sdkInt = 33)

        onAndroid13.onClipboardText(CalculatorEngine.COPY_X, "2.5")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("2.5", clipboardText())
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun onClipboardText_putsTheAnswerOnTheClipboardAndConfirms() {
        answers.onClipboardText(CalculatorEngine.COPY_X, "1.234")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("1.234", clipboardText())
        assertEquals(
            context.getString(R.string.main_menu_copy_x_register_copied),
            ShadowToast.getTextOfLatestToast(),
        )

        answers.onClipboardText(CalculatorEngine.COPY_STACK, "K = 9.\nX = 1.234")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("K = 9.\nX = 1.234", clipboardText())

        answers.onClipboardText(CalculatorEngine.COPY_ALL, "K = 9.\nR00 = 0.")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("K = 9.\nR00 = 0.", clipboardText())
    }

    @Test
    fun aCopyTheClipboardRefusesSaysSoAndDoesNotCrash() {
        // Android throws from setPrimaryClip when the text is over the binder limit.
        val refusing = CalculatorAnswers(context, Handler(Looper.getMainLooper()), sdkInt = 33) {
            throw IllegalStateException("android.os.TransactionTooLargeException")
        }
        refusing.onClipboardText(CalculatorEngine.COPY_ALL, "R00 = 1.")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(context.getString(R.string.main_menu_copy_failed), ShadowToast.getTextOfLatestToast())

        refusing.setClipboardText("7.")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(context.getString(R.string.main_menu_copy_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun anEmptyCopyLeavesTheClipboardAndSaysSo() {
        answers.setClipboardText("kept")
        shadowOf(Looper.getMainLooper()).idle()

        // The calculator answers an empty text when it could not build the copy.
        answers.onClipboardText(CalculatorEngine.COPY_ALL, "")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("kept", clipboardText())
        assertEquals(context.getString(R.string.main_menu_copy_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun setClipboardText_copiesWithNoConfirmation() {
        answers.setClipboardText("7.")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("7.", clipboardText())
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun onPasteResult_tellsTheUserOnlyWhenTheCalculatorRefused() {
        answers.onPasteResult(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowToast.getLatestToast())

        answers.onPasteResult(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            context.getString(R.string.main_menu_paste_not_ready),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun aWarning_showsEveryLineOfTheCoresMessage() {
        val message = "This is a WP43 program\nWP43 program support is experimental\n" +
            "Some instructions may not be \ncompatible with the C47 and may\ncrash the calculator."
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        showCalculatorWarning(activity, message)
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(message, dialog.findViewById<TextView>(android.R.id.message)?.text.toString())
        assertNull(ShadowToast.getLatestToast())
    }
}
