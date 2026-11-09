package io.github.ppigazzini.r47zen

import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
class CoreWarningTest {
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
