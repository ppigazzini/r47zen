package io.github.ppigazzini.r47

import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class SettingsActivityThemeTest {

    @Test
    fun settingsActivity_keepsDarkSurfacesInLightSystemMode() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .setup()
            .get()

        val systemNightMode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val surface = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorSurface,
            Color.MAGENTA,
        )
        val onSurface = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorOnSurface,
            Color.MAGENTA,
        )
        val primary = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorPrimary,
            Color.MAGENTA,
        )

        assertEquals(Configuration.UI_MODE_NIGHT_NO, systemNightMode)
        assertEquals(Color.parseColor("#FFC36F"), primary)
        assertTrue(ColorUtils.calculateLuminance(surface) < 0.15)
        assertTrue(ColorUtils.calculateLuminance(onSurface) > 0.7)
    }

    @Test
    fun settingsDialogs_inheritDarkSurfaceTheme() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .setup()
            .get()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Settings")
            .setMessage("Theme check")
            .create()

        val dialogSurface = MaterialColors.getColor(
            dialog.context,
            com.google.android.material.R.attr.colorSurface,
            Color.MAGENTA,
        )
        val dialogOnSurface = MaterialColors.getColor(
            dialog.context,
            com.google.android.material.R.attr.colorOnSurface,
            Color.MAGENTA,
        )

        assertTrue(ColorUtils.calculateLuminance(dialogSurface) < 0.15)
        assertTrue(ColorUtils.calculateLuminance(dialogOnSurface) > 0.7)
    }
}
