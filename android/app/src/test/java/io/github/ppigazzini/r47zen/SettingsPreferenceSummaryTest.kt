package io.github.ppigazzini.r47zen

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
class SettingsPreferenceSummaryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferencesBeforeTest() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun clearPreferencesAfterTest() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun fullscreenSummary_isFixedAndDescribesWhatOnDoes() {
        val prefs = context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)

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
        val prefs = context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)

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
    fun lcdThemePreference_usesClearTitleAndCuratedEntries() {
        val preference = launchSettingsAndFindList("lcd_theme")

        assertEquals(
            "LCD Color Theme",
            preference.title?.toString(),
        )
        assertEquals(
            listOf(
                "Gray Matrix",
                "Green Vintage",
                "Amber Quartz",
                "Blue Liquid",
            ),
            preference.entries.map { it.toString() },
        )
        assertEquals(
            listOf(
                "high_contrast",
                "vintage",
                "amber",
                "blue",
            ),
            preference.entryValues.map { it.toString() },
        )
    }

    @Test
    fun displaySettings_omitLegacyScalingPreference() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .setup()
            .get()

        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment

        assertNull(fragment.findPreference<Preference>("scaling_mode"))
    }

    @Test
    fun lcdThemePreferenceSummary_reflectsSelectedEntry() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("lcd_theme", "amber")
            .commit()

        val preference = launchSettingsAndFindList("lcd_theme")

        assertEquals("Amber Quartz", preference.summary?.toString())
    }

    @Test
    fun negativeLcdPreference_usesPlainEnglishTitleAndSummary() {
        val preference = launchSettingsAndFindSwitch("lcd_negative")

        assertEquals(
            "Negative LCD",
            preference.title?.toString(),
        )
        assertEquals(null, preference.summary)
    }

    @Test
    fun lcdPreferences_declareTheControllerLimitsAndDefaults() {
        // Hold root_preferences.xml to the controller: Settings shows the XML
        // values while nothing is stored, and the controller applies its own,
        // so a drift shows one LCD setting and draws another.
        val settings = launchSettings()
        val luminance = requireNotNull(settings.findPreference<SeekBarPreference>("lcd_luminance"))
        val theme = requireNotNull(settings.findPreference<ListPreference>("lcd_theme"))
        val negative = requireNotNull(settings.findPreference<SwitchPreferenceCompat>("lcd_negative"))

        assertEquals(MainActivityPreferenceController.MIN_LCD_LUMINANCE, luminance.min)
        assertEquals(MainActivityPreferenceController.MAX_LCD_LUMINANCE, luminance.max)
        assertEquals(MainActivityPreferenceController.DEFAULT_LCD_LUMINANCE, luminance.value)
        assertEquals(MainActivityPreferenceController.DEFAULT_LCD_THEME, theme.value)
        assertEquals(MainActivityPreferenceController.DEFAULT_LCD_NEGATIVE, negative.isChecked)
    }

    // Settings shows a preference's XML default while nothing is stored, and the shell
    // reads the key with its own default: a drift shows one value and applies another,
    // and a seek bar then stores the shown one on first open. Every preference that
    // holds a value is paired here, so a new one cannot miss the check.
    @Test
    fun everySetting_showsTheDefaultTheShellReads() {
        val main = MainActivityPreferenceController
        val haptic = HapticFeedbackController
        val shellDefaults = mapOf<String, Any>(
            main.KEY_FULLSCREEN_MODE to main.DEFAULT_FULLSCREEN_MODE,
            main.KEY_LCD_GRAPH_TOUCH_ENABLED to main.DEFAULT_LCD_GRAPH_TOUCH_ENABLED,
            main.KEY_LCD_THEME to main.DEFAULT_LCD_THEME,
            main.KEY_LCD_NEGATIVE to main.DEFAULT_LCD_NEGATIVE,
            main.KEY_LCD_LUMINANCE to main.DEFAULT_LCD_LUMINANCE,
            main.KEY_MAIN_KEY_DYNAMIC_MODE to main.DEFAULT_MAIN_KEY_DYNAMIC_MODE.storageValue,
            main.KEY_SOFTKEY_DYNAMIC_MODE to main.DEFAULT_SOFTKEY_DYNAMIC_MODE.storageValue,
            haptic.KEY_HAPTIC_ENABLED to haptic.DEFAULT_HAPTIC_ENABLED,
            haptic.KEY_HAPTIC_USE_ANDROID_DEFAULT to haptic.DEFAULT_HAPTIC_USE_ANDROID_DEFAULT,
            haptic.KEY_HAPTIC_KEYPRESS_DURATION_MS to haptic.DEFAULT_HAPTIC_KEYPRESS_DURATION_MS,
            main.KEY_BEEPER_ENABLED to main.DEFAULT_BEEPER_ENABLED,
            main.KEY_BEEPER_VOLUME to main.DEFAULT_BEEPER_VOLUME,
            main.KEY_KEEP_SCREEN_ON to main.DEFAULT_KEEP_SCREEN_ON,
            main.KEY_AUTO_SAVE_MINIMIZE to main.DEFAULT_AUTO_SAVE_MINIMIZE,
            main.KEY_FORCE_CLOSE_ON_EXIT to main.DEFAULT_FORCE_CLOSE_ON_EXIT,
            main.KEY_SHOW_DEVELOPER_PERFORMANCE_HUD to main.DEFAULT_SHOW_DEVELOPER_PERFORMANCE_HUD,
            main.KEY_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS to main.DEFAULT_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS,
            main.KEY_SHOW_TOUCH_ZONES to main.DEFAULT_SHOW_TOUCH_ZONES,
        )

        val shown = valuedPreferences(launchSettings().preferenceScreen).associate { it.key to shownValue(it) }

        assertEquals(shellDefaults.keys, shown.keys)
        shown.forEach { (key, value) -> assertEquals(key, shellDefaults[key], value) }
    }

    private fun valuedPreferences(group: PreferenceGroup): List<Preference> {
        return (0 until group.preferenceCount).map(group::getPreference).flatMap { preference ->
            when (preference) {
                is PreferenceGroup -> valuedPreferences(preference)
                is TwoStatePreference, is SeekBarPreference, is ListPreference, is EditTextPreference ->
                    listOf(preference)
                else -> emptyList()
            }
        }
    }

    private fun shownValue(preference: Preference): Any? {
        return when (preference) {
            is TwoStatePreference -> preference.isChecked
            is SeekBarPreference -> preference.value
            is ListPreference -> preference.value
            is EditTextPreference -> preference.text
            else -> error("${preference.key} holds no value this test reads")
        }
    }

    @Test
    fun lcdLuminancePreference_usesTitleWithoutRedundantSummary() {
        val preference = launchSettingsAndFindSeekBar("lcd_luminance")

        assertEquals(
            "LCD Luminance",
            preference.title?.toString(),
        )
        assertEquals(null, preference.summary)
    }

    @Test
    fun performanceStatsPreference_usesFixedDeveloperCopy() {
        val preference = launchSettingsAndFindSwitch("show_developer_performance_hud")

        assertEquals(
            "HUD Telemetry",
            preference.title?.toString(),
        )
        assertEquals(
            "UI Hz, LCD Hz, Dirty-Row %, and LCD update ms.",
            preference.summary?.toString(),
        )
    }

    @Test
    fun performanceStatsWindowPreference_defaultsToDisabledDeveloperSlider() {
        val preference = launchSettingsAndFindSeekBar("developer_performance_hud_window_millis")

        assertEquals(
            "HUD Sample Window (ms)",
            preference.title?.toString(),
        )
        assertEquals(MainActivityPreferenceController.MAX_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS, preference.max)
        assertEquals(MainActivityPreferenceController.DEFAULT_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS, preference.value)
        assertFalse(preference.isEnabled)
    }

    @Test
    fun performanceStatsWindowPreference_reflectsStoredValueWhenHudIsEnabled() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("show_developer_performance_hud", true)
            .putInt("developer_performance_hud_window_millis", 750)
            .commit()

        val preference = launchSettingsAndFindSeekBar("developer_performance_hud_window_millis")

        assertEquals(750, preference.value)
        assertTrue(preference.isEnabled)
    }

    @Test
    fun lcdGraphTouchPreference_usesRequestedSummaryCopy() {
        val preference = launchSettingsAndFindSwitch("lcd_graph_touch_enabled")

        assertEquals(
            "Drag and pinch gestures on graph screens.",
            preference.summary?.toString(),
        )
    }

    @Test
    fun hapticToggle_usesShortMasterTitle() {
        val preference = launchSettingsAndFindSwitch("haptic_enabled")

        assertEquals(
            "Haptic feedback",
            preference.title?.toString(),
        )
    }

    @Test
    fun hapticSystemDurationToggle_defaultsOnAndDependsOnHapticMaster() {
        val preference = launchSettingsAndFindSwitch("haptic_use_android_default")

        assertEquals(
            "Use system duration",
            preference.title?.toString(),
        )
        assertTrue(preference.isChecked)
        assertTrue(preference.isEnabled)
    }

    @Test
    fun hapticDurationPreference_defaultsToDisabledCustomSlider() {
        val preference = launchSettingsAndFindSeekBar("haptic_keypress_duration_ms")

        assertEquals(
            "Custom duration (ms)",
            preference.title?.toString(),
        )
        assertEquals(100, preference.max)
        assertEquals(0, preference.value)
        assertFalse(preference.isEnabled)
    }

    @Test
    fun hapticStrengthPreference_reflectsStoredCustomDurationWhenAndroidDefaultIsOff() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("haptic_use_android_default", false)
            .putInt("haptic_keypress_duration_ms", 73)
            .commit()

        val preference = launchSettingsAndFindSeekBar("haptic_keypress_duration_ms")

        assertEquals(73, preference.value)
        assertTrue(preference.isEnabled)
    }

    @Test
    fun hapticMasterToggle_disablesAndroidDefaultToggleAndCustomSlider() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("haptic_enabled", false)
            .putBoolean("haptic_use_android_default", false)
            .putInt("haptic_keypress_duration_ms", 73)
            .commit()

        val defaultPreference = launchSettingsAndFindSwitch("haptic_use_android_default")
        val durationPreference = launchSettingsAndFindSeekBar("haptic_keypress_duration_ms")

        assertFalse(defaultPreference.isEnabled)
        assertFalse(durationPreference.isEnabled)
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
        // Both commits, as the published file names carry them: the app line names
        // the Android commit, the core line the core's release and commit, or
        // upstream's "custom build" label for a commit that is no release.
        val summary = context.getString(R.string.about_version_summary)
        val commit = "([0-9a-f]{8}|unknown)"
        assertTrue(
            summary,
            Regex(
                "R47 Zen [0-9A-Za-z.-]+ \\(\\d+\\), $commit(-mod)?\n" +
                    "Core: C47 (custom build $commit|[0-9A-Za-z.-]+, $commit)",
            ).matches(summary),
        )
    }

    @Test
    fun zenModeDisablesSoftkeyLayoutMenu() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("main_key_dynamic_mode", "virtuoso")
            .commit()

        val preference = launchSettingsAndFindList("softkey_dynamic_mode")

        assertFalse(preference.isEnabled)
    }

    @Test
    fun nonZenModesKeepSoftkeyLayoutMenuEnabled() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("main_key_dynamic_mode", "off")
            .commit()

        val preference = launchSettingsAndFindList("softkey_dynamic_mode")

        assertTrue(preference.isEnabled)
    }

    private fun launchSettingsAndFindSwitch(key: String): SwitchPreferenceCompat =
        requireNotNull(launchSettings().findPreference(key))

    private fun launchSettingsAndFindList(key: String): ListPreference =
        requireNotNull(launchSettings().findPreference(key))

    private fun launchSettingsAndFindSeekBar(key: String): SeekBarPreference =
        requireNotNull(launchSettings().findPreference(key))

    private fun launchSettings(): SettingsFragment {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java)
            .setup()
            .get()

        return activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
    }
}
