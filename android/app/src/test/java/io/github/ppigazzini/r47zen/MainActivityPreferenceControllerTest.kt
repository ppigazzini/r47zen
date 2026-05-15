package io.github.ppigazzini.r47zen

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.After
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
class MainActivityPreferenceControllerTest {

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
    fun applyInitialPreferences_clampsBeeperVolumeBeforeAudioDispatch() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("beeper_volume", 999)
            .commit()

        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()

        assertEquals(100, controllerState.controller.beeperVolume)
        assertEquals(100, preferences.getInt("beeper_volume", -1))
        assertEquals(listOf(true to 100), controllerState.audioSettingsCalls)
    }

    @Test
    fun onPreferenceChanged_clampsNegativeBeeperVolumeAndDispatchesNormalizedAudioState() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()
        controllerState.audioSettingsCalls.clear()

        preferences.edit()
            .putInt("beeper_volume", -4)
            .commit()

        assertTrue(controllerState.controller.onPreferenceChanged("beeper_volume"))

        assertEquals(0, controllerState.controller.beeperVolume)
        assertEquals(0, preferences.getInt("beeper_volume", -1))
        assertEquals(listOf(true to 0), controllerState.audioSettingsCalls)
    }

    @Test
    fun applyInitialPreferences_normalizesUnknownLcdThemeBeforeDeferredOverlayDispatch() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putString("lcd_theme", "unknown")
            .commit()

        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()
        controllerState.controller.applyDeferredOverlayPreferences()

        assertEquals(MainActivityPreferenceController.DEFAULT_LCD_THEME, controllerState.controller.lcdTheme)
        assertEquals(
            MainActivityPreferenceController.DEFAULT_LCD_THEME,
            preferences.getString("lcd_theme", null),
        )
        assertEquals(
            listOf(
                LcdThemeCall(
                    MainActivityPreferenceController.DEFAULT_LCD_THEME,
                    MainActivityPreferenceController.DEFAULT_LCD_LUMINANCE,
                    MainActivityPreferenceController.DEFAULT_LCD_NEGATIVE,
                ),
            ),
            controllerState.lcdThemeCalls,
        )
    }

    @Test
    fun onPreferenceChanged_normalizesUnknownLcdThemeAndDispatchesDefaultTheme() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()
        controllerState.lcdThemeCalls.clear()

        preferences.edit()
            .putString("lcd_theme", "retro_blue")
            .commit()

        assertTrue(controllerState.controller.onPreferenceChanged("lcd_theme"))

        assertEquals(MainActivityPreferenceController.DEFAULT_LCD_THEME, controllerState.controller.lcdTheme)
        assertEquals(
            MainActivityPreferenceController.DEFAULT_LCD_THEME,
            preferences.getString("lcd_theme", null),
        )
        assertEquals(
            listOf(
                LcdThemeCall(
                    MainActivityPreferenceController.DEFAULT_LCD_THEME,
                    MainActivityPreferenceController.DEFAULT_LCD_LUMINANCE,
                    MainActivityPreferenceController.DEFAULT_LCD_NEGATIVE,
                ),
            ),
            controllerState.lcdThemeCalls,
        )
    }

    @Test
    fun applyInitialPreferences_clampsLowLcdLuminanceAndDispatchesNormalizedValue() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("lcd_luminance", -10)
            .commit()

        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()
        controllerState.controller.applyDeferredOverlayPreferences()

        assertEquals(MainActivityPreferenceController.MIN_LCD_LUMINANCE, controllerState.controller.lcdLuminance)
        assertEquals(
            MainActivityPreferenceController.MIN_LCD_LUMINANCE,
            preferences.getInt("lcd_luminance", -1),
        )
        assertEquals(
            listOf(
                LcdThemeCall(
                    MainActivityPreferenceController.DEFAULT_LCD_THEME,
                    MainActivityPreferenceController.MIN_LCD_LUMINANCE,
                    MainActivityPreferenceController.DEFAULT_LCD_NEGATIVE,
                ),
            ),
            controllerState.lcdThemeCalls,
        )
    }

    @Test
    fun onPreferenceChanged_dispatchesNegativeLcdState() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()
        controllerState.lcdThemeCalls.clear()

        preferences.edit()
            .putBoolean("lcd_negative", true)
            .commit()

        assertTrue(controllerState.controller.onPreferenceChanged("lcd_negative"))

        assertEquals(true, controllerState.controller.isLcdNegative)
        assertEquals(
            listOf(
                LcdThemeCall(
                    MainActivityPreferenceController.DEFAULT_LCD_THEME,
                    MainActivityPreferenceController.DEFAULT_LCD_LUMINANCE,
                    true,
                ),
            ),
            controllerState.lcdThemeCalls,
        )
    }

    @Test
    fun applyInitialPreferences_migratesLegacyLcdModeKeyToLcdTheme() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putString("lcd_mode", "amber")
            .commit()

        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()

        assertEquals("amber", controllerState.controller.lcdTheme)
        assertEquals("amber", preferences.getString("lcd_theme", null))
        assertEquals(false, preferences.contains("lcd_mode"))
    }

    private fun buildController(preferences: android.content.SharedPreferences): ControllerState {
        val activity = Robolectric.buildActivity(PreferenceControllerActivity::class.java)
            .setup()
            .get()
        val audioSettingsCalls = mutableListOf<Pair<Boolean, Int>>()
        val lcdThemeCalls = mutableListOf<LcdThemeCall>()

        val controller = MainActivityPreferenceController(
            preferences = preferences,
            window = activity.window,
            hapticFeedbackController = HapticFeedbackController(activity),
            windowModeController = WindowModeController(
                activity = activity,
                mainHandler = Handler(Looper.getMainLooper()),
                onPiPModeChanged = {},
            ),
            syncAudioSettings = { enabled, volume -> audioSettingsCalls += enabled to volume },
            applyLcdTheme = { theme, luminance, isNegative ->
                lcdThemeCalls += LcdThemeCall(theme, luminance, isNegative)
            },
            applyScalingMode = {},
            applyShowTouchZones = {},
            applyKeypadLabelModes = { _, _ -> },
        )

        return ControllerState(
            controller = controller,
            audioSettingsCalls = audioSettingsCalls,
            lcdThemeCalls = lcdThemeCalls,
        )
    }

    private data class ControllerState(
        val controller: MainActivityPreferenceController,
        val audioSettingsCalls: MutableList<Pair<Boolean, Int>>,
        val lcdThemeCalls: MutableList<LcdThemeCall>,
    )

    private data class LcdThemeCall(
        val theme: String,
        val luminance: Int,
        val isNegative: Boolean,
    )

    private class PreferenceControllerActivity : AppCompatActivity()
}
