package io.github.ppigazzini.r47zen

import android.content.Context
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            "Make f-shift EXIT close R47 Zen and remove it from Recents.",
            forceCloseOnSummary,
        )

        prefs.edit().putBoolean("force_close_on_exit", false).commit()
        val forceCloseOffSummary = launchSettingsAndFindSwitch("force_close_on_exit").summary?.toString()
        assertEquals(
            "Make f-shift EXIT close R47 Zen and remove it from Recents.",
            forceCloseOffSummary,
        )
    }

    @Test
    fun mainKeyUserEntry_matchesPlainEnglishCopy() {
        val preference = launchSettingsAndFindList("main_key_dynamic_mode")

        assertEquals(
            "Dynamic USER f/g",
            preference.entries[2].toString(),
        )
    }

    @Test
    fun mainKeyZenEntry_isLastAndExplicitAboutBlankKeycaps() {
        val preference = launchSettingsAndFindList("main_key_dynamic_mode")

        assertEquals(
            "Zen",
            preference.entries.last().toString(),
        )
    }

    @Test
    fun mainKeyPreferenceTitle_usesPlainEnglishTitle() {
        val preference = launchSettingsAndFindList("main_key_dynamic_mode")

        assertEquals(
            "Main Keys Mode",
            preference.title?.toString(),
        )
    }

    @Test
    fun keypadLayoutCategoryTitle_matchesRequestedCopy() {
        assertEquals(
            "Keypad Layout",
            context.getString(R.string.settings_keypad_labels_category_title),
        )
    }

    @Test
    fun softkeyPreferenceTitleAndEntries_matchPlainEnglishCopy() {
        val preference = launchSettingsAndFindList("softkey_dynamic_mode")

        assertEquals(
            "Softkeys Mode",
            preference.title?.toString(),
        )
        assertEquals(
            listOf(
                "Dynamic Full UI",
                "Dynamic Graphics",
                "Zen",
            ),
            preference.entries.map { it.toString() },
        )
    }

    @Test
    fun settingsAboutUsesRequestedAppName() {
        assertEquals(
            "About R47 Zen",
            context.getString(R.string.settings_about_category_title),
        )
        assertTrue(context.getString(R.string.about_version_summary).startsWith("R47 Zen "))
    }

    @Test
    fun zenModeDisablesSoftkeyLayoutMenu() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("main_key_dynamic_mode", "virtuoso")
            .commit()

        val preference = launchSettingsAndFindList("softkey_dynamic_mode")

        assertFalse(preference.isEnabled)
    }

    @Test
    fun nonZenModesKeepSoftkeyLayoutMenuEnabled() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("main_key_dynamic_mode", "off")
            .commit()

        val preference = launchSettingsAndFindList("softkey_dynamic_mode")

        assertTrue(preference.isEnabled)
    }

    private fun launchSettingsAndFindSwitch(key: String): SwitchPreferenceCompat {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .setup()
            .get()

        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
        return requireNotNull(fragment.findPreference(key))
    }

    private fun launchSettingsAndFindList(key: String): ListPreference {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .setup()
            .get()

        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
        return requireNotNull(fragment.findPreference(key))
    }
}
