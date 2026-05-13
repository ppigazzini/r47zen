package io.github.ppigazzini.r47

import android.content.Context
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class SettingsPreferenceSummaryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferencesBeforeTest() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun clearPreferencesAfterTest() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun fullscreenSummary_isFixedAndDescribesWhatOnDoes() {
        val prefs = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit().putBoolean("fullscreen_mode", true).commit()
        val fullscreenOnSummary = launchSettingsAndFindSwitch("fullscreen_mode").summary?.toString()
        assertEquals(
            "Hide the Android status and navigation bars.",
            fullscreenOnSummary,
        )

        prefs.edit().putBoolean("fullscreen_mode", false).commit()
        val fullscreenOffSummary = launchSettingsAndFindSwitch("fullscreen_mode").summary?.toString()
        assertEquals(
            "Hide the Android status and navigation bars.",
            fullscreenOffSummary,
        )
    }

    @Test
    fun forceCloseSummary_isFixedAndDescribesWhatOnDoes() {
        val prefs = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit().putBoolean("force_close_on_exit", true).commit()
        val forceCloseOnSummary = launchSettingsAndFindSwitch("force_close_on_exit").summary?.toString()
        assertEquals(
            "Make f-shift EXIT close R47 and remove it from Recents.",
            forceCloseOnSummary,
        )

        prefs.edit().putBoolean("force_close_on_exit", false).commit()
        val forceCloseOffSummary = launchSettingsAndFindSwitch("force_close_on_exit").summary?.toString()
        assertEquals(
            "Make f-shift EXIT close R47 and remove it from Recents.",
            forceCloseOffSummary,
        )
    }

    private fun launchSettingsAndFindSwitch(key: String): SwitchPreferenceCompat {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .setup()
            .get()

        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
        return requireNotNull(fragment.findPreference(key))
    }
}
