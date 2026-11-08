package io.github.ppigazzini.r47zen

import android.content.SharedPreferences
import android.view.Window
import android.view.WindowManager
import androidx.core.content.edit

internal class MainActivityPreferenceController(
    private val preferences: SharedPreferences,
    private val window: Window,
    private val hapticFeedbackController: HapticFeedbackController,
    private val windowModeController: WindowModeController,
    private val syncAudioSettings: (Boolean, Int) -> Unit,
    private val applyLcdTheme: (String, Int, Boolean) -> Unit,
    private val applyLcdGraphTouchEnabled: (Boolean) -> Unit,
    private val applyShowTouchZones: (Boolean) -> Unit,
    private val applyShowDeveloperPerformanceHud: (Boolean) -> Unit,
    private val applyKeypadLabelModes: (MainKeyDynamicMode, SoftkeyDynamicMode) -> Unit,
) {
    companion object {
        const val DEFAULT_BEEPER_VOLUME = 20
        const val DEFAULT_BEEPER_ENABLED = true
        const val DEFAULT_FULLSCREEN_MODE = true
        const val DEFAULT_KEEP_SCREEN_ON = false
        const val DEFAULT_AUTO_SAVE_MINIMIZE = true
        const val DEFAULT_FORCE_CLOSE_ON_EXIT = false
        const val DEFAULT_SHOW_DEVELOPER_PERFORMANCE_HUD = false
        const val DEFAULT_SHOW_TOUCH_ZONES = false
        const val MIN_BEEPER_VOLUME = 0
        const val MAX_BEEPER_VOLUME = 100
        const val DEFAULT_LCD_NEGATIVE = LcdThemePolicy.DEFAULT_NEGATIVE
        const val DEFAULT_LCD_THEME = DEFAULT_LCD_THEME_STORAGE_VALUE
        const val DEFAULT_LCD_LUMINANCE = LcdThemePolicy.DEFAULT_LUMINANCE_PERCENT
        const val DEFAULT_LCD_GRAPH_TOUCH_ENABLED = true
        const val DEFAULT_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS = 500
        val DEFAULT_MAIN_KEY_DYNAMIC_MODE = MainKeyDynamicMode.DEFAULT
        const val MIN_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS = 100
        const val MIN_LCD_LUMINANCE = LcdThemePolicy.MIN_LUMINANCE_PERCENT
        const val MAX_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS = 1000
        const val MAX_LCD_LUMINANCE = LcdThemePolicy.MAX_LUMINANCE_PERCENT
        val DEFAULT_SOFTKEY_DYNAMIC_MODE = SoftkeyDynamicMode.DEFAULT

        internal const val KEY_AUTO_SAVE_MINIMIZE = "auto_save_minimize"
        internal const val KEY_FORCE_CLOSE_ON_EXIT = "force_close_on_exit"
        internal const val KEY_BEEPER_ENABLED = "beeper_enabled"
        internal const val KEY_BEEPER_VOLUME = "beeper_volume"
        internal const val KEY_FULLSCREEN_MODE = "fullscreen_mode"
        internal const val KEY_LCD_NEGATIVE = "lcd_negative"
        internal const val KEY_LCD_GRAPH_TOUCH_ENABLED = "lcd_graph_touch_enabled"
        internal const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        internal const val KEY_LCD_THEME = "lcd_theme"
        internal const val KEY_LCD_LUMINANCE = "lcd_luminance"
        internal const val KEY_MAIN_KEY_DYNAMIC_MODE = "main_key_dynamic_mode"
        internal const val KEY_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS = "developer_performance_hud_window_millis"
        internal const val KEY_SHOW_DEVELOPER_PERFORMANCE_HUD = "show_developer_performance_hud"
        internal const val KEY_SHOW_TOUCH_ZONES = "show_touch_zones"
        internal const val KEY_SOFTKEY_DYNAMIC_MODE = "softkey_dynamic_mode"
        private const val LEGACY_KEY_LCD_MODE = "lcd_mode"
    }

    var beeperVolume = DEFAULT_BEEPER_VOLUME
        private set

    var isBeeperEnabled = DEFAULT_BEEPER_ENABLED
        private set

    var lcdTheme = DEFAULT_LCD_THEME
        private set

    var lcdLuminance = DEFAULT_LCD_LUMINANCE
        private set

    var isLcdNegative = DEFAULT_LCD_NEGATIVE
        private set

    var isLcdGraphTouchEnabled = DEFAULT_LCD_GRAPH_TOUCH_ENABLED
        private set

    var developerPerformanceHudWindowMillis = DEFAULT_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS
        private set

    var mainKeyDynamicMode = DEFAULT_MAIN_KEY_DYNAMIC_MODE
        private set

    var showDeveloperPerformanceHud = DEFAULT_SHOW_DEVELOPER_PERFORMANCE_HUD
        private set

    var showTouchZones = DEFAULT_SHOW_TOUCH_ZONES
        private set

    var softkeyDynamicMode = DEFAULT_SOFTKEY_DYNAMIC_MODE
        private set

    fun applyInitialPreferences() {
        hapticFeedbackController.syncFromPreferences(preferences)

        beeperVolume = readNormalizedBeeperVolume()
        isBeeperEnabled = preferences.getBoolean(KEY_BEEPER_ENABLED, DEFAULT_BEEPER_ENABLED)
        lcdTheme = readNormalizedLcdTheme()
        lcdLuminance = readNormalizedLcdLuminance()
        isLcdNegative = preferences.getBoolean(KEY_LCD_NEGATIVE, DEFAULT_LCD_NEGATIVE)
        isLcdGraphTouchEnabled = preferences.getBoolean(KEY_LCD_GRAPH_TOUCH_ENABLED, DEFAULT_LCD_GRAPH_TOUCH_ENABLED)
        developerPerformanceHudWindowMillis = readNormalizedDeveloperPerformanceHudWindowMillis()
        mainKeyDynamicMode = readNormalizedMainKeyDynamicMode()
        showDeveloperPerformanceHud =
            preferences.getBoolean(KEY_SHOW_DEVELOPER_PERFORMANCE_HUD, DEFAULT_SHOW_DEVELOPER_PERFORMANCE_HUD)
        showTouchZones = preferences.getBoolean(KEY_SHOW_TOUCH_ZONES, DEFAULT_SHOW_TOUCH_ZONES)
        softkeyDynamicMode = readNormalizedSoftkeyDynamicMode()

        applyKeepScreenOn(preferences.getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON))
        windowModeController.applyFullscreenMode(preferences.getBoolean(KEY_FULLSCREEN_MODE, DEFAULT_FULLSCREEN_MODE))
        syncAudioSettings(isBeeperEnabled, beeperVolume)
        applyKeypadLabelModes(mainKeyDynamicMode, softkeyDynamicMode)
        // Here, not in the deferred overlay pass: MainActivity runs this before
        // the overlay's first draw, so no frame shows the default palette under
        // another stored theme.
        applyLcdTheme(lcdTheme, lcdLuminance, isLcdNegative)
    }

    fun applyDeferredOverlayPreferences() {
        applyShowTouchZones(showTouchZones)
        applyShowDeveloperPerformanceHud(showDeveloperPerformanceHud)
        applyLcdGraphTouchEnabled(isLcdGraphTouchEnabled)
    }

    fun onPreferenceChanged(key: String): Boolean {
        if (hapticFeedbackController.onPreferenceChanged(preferences, key)) {
            return true
        }

        when (key) {
            KEY_BEEPER_VOLUME -> {
                beeperVolume = readNormalizedBeeperVolume(key)
                syncAudioSettings(isBeeperEnabled, beeperVolume)
            }
            KEY_KEEP_SCREEN_ON -> {
                applyKeepScreenOn(preferences.getBoolean(key, DEFAULT_KEEP_SCREEN_ON))
            }
            KEY_BEEPER_ENABLED -> {
                isBeeperEnabled = preferences.getBoolean(key, DEFAULT_BEEPER_ENABLED)
                syncAudioSettings(isBeeperEnabled, beeperVolume)
            }
            KEY_LCD_THEME,
            LEGACY_KEY_LCD_MODE,
            -> {
                lcdTheme = readNormalizedLcdTheme()
                applyLcdTheme(lcdTheme, lcdLuminance, isLcdNegative)
            }
            KEY_LCD_LUMINANCE -> {
                lcdLuminance = readNormalizedLcdLuminance()
                applyLcdTheme(lcdTheme, lcdLuminance, isLcdNegative)
            }
            KEY_LCD_NEGATIVE -> {
                isLcdNegative = preferences.getBoolean(key, DEFAULT_LCD_NEGATIVE)
                applyLcdTheme(lcdTheme, lcdLuminance, isLcdNegative)
            }
            KEY_LCD_GRAPH_TOUCH_ENABLED -> {
                isLcdGraphTouchEnabled = preferences.getBoolean(key, DEFAULT_LCD_GRAPH_TOUCH_ENABLED)
                applyLcdGraphTouchEnabled(isLcdGraphTouchEnabled)
            }
            KEY_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS -> {
                developerPerformanceHudWindowMillis = readNormalizedDeveloperPerformanceHudWindowMillis()
            }
            KEY_MAIN_KEY_DYNAMIC_MODE -> {
                mainKeyDynamicMode = readNormalizedMainKeyDynamicMode()
                applyKeypadLabelModes(mainKeyDynamicMode, softkeyDynamicMode)
            }
            KEY_SHOW_DEVELOPER_PERFORMANCE_HUD -> {
                showDeveloperPerformanceHud = preferences.getBoolean(key, DEFAULT_SHOW_DEVELOPER_PERFORMANCE_HUD)
                applyShowDeveloperPerformanceHud(showDeveloperPerformanceHud)
            }
            KEY_SHOW_TOUCH_ZONES -> {
                showTouchZones = preferences.getBoolean(key, DEFAULT_SHOW_TOUCH_ZONES)
                applyShowTouchZones(showTouchZones)
            }
            KEY_SOFTKEY_DYNAMIC_MODE -> {
                softkeyDynamicMode = readNormalizedSoftkeyDynamicMode()
                applyKeypadLabelModes(mainKeyDynamicMode, softkeyDynamicMode)
            }
            KEY_FULLSCREEN_MODE -> {
                windowModeController.applyFullscreenMode(preferences.getBoolean(key, DEFAULT_FULLSCREEN_MODE))
            }
            else -> return false
        }

        return true
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun readNormalizedBeeperVolume(key: String = KEY_BEEPER_VOLUME): Int {
        val storedVolume = preferences.getInt(key, DEFAULT_BEEPER_VOLUME)
        val normalizedVolume = storedVolume.coerceIn(MIN_BEEPER_VOLUME, MAX_BEEPER_VOLUME)
        if (storedVolume != normalizedVolume) {
            preferences.edit { putInt(key, normalizedVolume) }
        }
        return normalizedVolume
    }

    private fun readNormalizedLcdLuminance(): Int {
        val storedLuminance = preferences.getInt(KEY_LCD_LUMINANCE, DEFAULT_LCD_LUMINANCE)
        val normalizedLuminance = storedLuminance.coerceIn(MIN_LCD_LUMINANCE, MAX_LCD_LUMINANCE)
        if (storedLuminance != normalizedLuminance) {
            preferences.edit { putInt(KEY_LCD_LUMINANCE, normalizedLuminance) }
        }
        return normalizedLuminance
    }

    private fun readNormalizedDeveloperPerformanceHudWindowMillis(): Int {
        val storedWindowMillis = preferences.getInt(
            KEY_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS,
            DEFAULT_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS,
        )
        val normalizedWindowMillis = storedWindowMillis.coerceIn(
            MIN_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS,
            MAX_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS,
        )
        if (storedWindowMillis != normalizedWindowMillis) {
            preferences.edit {
                putInt(KEY_DEVELOPER_PERFORMANCE_HUD_WINDOW_MILLIS, normalizedWindowMillis)
            }
        }
        return normalizedWindowMillis
    }

    private fun readNormalizedLcdTheme(): String {
        val hasCurrentKey = preferences.contains(KEY_LCD_THEME)
        val storedTheme = if (hasCurrentKey) {
            preferences.getString(KEY_LCD_THEME, DEFAULT_LCD_THEME)
        } else {
            preferences.getString(LEGACY_KEY_LCD_MODE, DEFAULT_LCD_THEME)
        }
        val normalizedTheme = LcdThemePolicy.normalizeStorageValue(storedTheme)
        val needsWriteBack = !hasCurrentKey || storedTheme != normalizedTheme || preferences.contains(LEGACY_KEY_LCD_MODE)
        if (needsWriteBack) {
            preferences.edit {
                putString(KEY_LCD_THEME, normalizedTheme)
                remove(LEGACY_KEY_LCD_MODE)
            }
        }
        return normalizedTheme
    }

    private fun readNormalizedMainKeyDynamicMode(): MainKeyDynamicMode {
        val storedMode = preferences.getString(
            KEY_MAIN_KEY_DYNAMIC_MODE,
            DEFAULT_MAIN_KEY_DYNAMIC_MODE.storageValue,
        )
        val normalizedMode = MainKeyDynamicMode.fromStorageValue(storedMode)
        if (storedMode != normalizedMode.storageValue) {
            preferences.edit { putString(KEY_MAIN_KEY_DYNAMIC_MODE, normalizedMode.storageValue) }
        }
        return normalizedMode
    }

    private fun readNormalizedSoftkeyDynamicMode(): SoftkeyDynamicMode {
        val storedMode = preferences.getString(
            KEY_SOFTKEY_DYNAMIC_MODE,
            DEFAULT_SOFTKEY_DYNAMIC_MODE.storageValue,
        )
        val normalizedMode = SoftkeyDynamicMode.fromStorageValue(storedMode)
        if (storedMode != normalizedMode.storageValue) {
            preferences.edit { putString(KEY_SOFTKEY_DYNAMIC_MODE, normalizedMode.storageValue) }
        }
        return normalizedMode
    }
}
