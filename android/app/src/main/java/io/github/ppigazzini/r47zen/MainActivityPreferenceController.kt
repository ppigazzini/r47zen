package io.github.ppigazzini.r47zen

import android.content.SharedPreferences
import android.view.Window
import android.view.WindowManager

internal class MainActivityPreferenceController(
    private val preferences: SharedPreferences,
    private val window: Window,
    private val hapticFeedbackController: HapticFeedbackController,
    private val windowModeController: WindowModeController,
    private val syncAudioSettings: (Boolean, Int) -> Unit,
    private val applyLcdTheme: (String, Int, Boolean) -> Unit,
    private val applyScalingMode: (String) -> Unit,
    private val applyShowTouchZones: (Boolean) -> Unit,
    private val applyKeypadLabelModes: (MainKeyDynamicMode, SoftkeyDynamicMode) -> Unit,
) {
    companion object {
        const val DEFAULT_BEEPER_VOLUME = 20
        const val MIN_BEEPER_VOLUME = 0
        const val MAX_BEEPER_VOLUME = 100
        const val DEFAULT_LCD_NEGATIVE = false
        const val DEFAULT_LCD_THEME = DEFAULT_LCD_THEME_STORAGE_VALUE
        const val DEFAULT_LCD_LUMINANCE = 100
        val DEFAULT_MAIN_KEY_DYNAMIC_MODE = MainKeyDynamicMode.DEFAULT
        const val MIN_LCD_LUMINANCE = 20
        const val MAX_LCD_LUMINANCE = 120
        const val DEFAULT_SCALING_MODE = "full_width"
        val DEFAULT_SOFTKEY_DYNAMIC_MODE = SoftkeyDynamicMode.DEFAULT

        private const val KEY_BEEPER_ENABLED = "beeper_enabled"
        private const val KEY_BEEPER_VOLUME = "beeper_volume"
        private const val KEY_FULLSCREEN_MODE = "fullscreen_mode"
        private const val KEY_LCD_NEGATIVE = "lcd_negative"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_LCD_THEME = "lcd_theme"
        private const val KEY_LCD_LUMINANCE = "lcd_luminance"
        private const val KEY_MAIN_KEY_DYNAMIC_MODE = "main_key_dynamic_mode"
        private const val KEY_SCALING_MODE = "scaling_mode"
        private const val KEY_SHOW_TOUCH_ZONES = "show_touch_zones"
        private const val KEY_SOFTKEY_DYNAMIC_MODE = "softkey_dynamic_mode"
        private const val LEGACY_KEY_LCD_MODE = "lcd_mode"
    }

    var beeperVolume = DEFAULT_BEEPER_VOLUME
        private set

    var isBeeperEnabled = true
        private set

    var lcdTheme = DEFAULT_LCD_THEME
        private set

    var lcdLuminance = DEFAULT_LCD_LUMINANCE
        private set

    var isLcdNegative = DEFAULT_LCD_NEGATIVE
        private set

    var mainKeyDynamicMode = DEFAULT_MAIN_KEY_DYNAMIC_MODE
        private set

    var scalingMode = DEFAULT_SCALING_MODE
        private set

    var showTouchZones = false
        private set

    var softkeyDynamicMode = DEFAULT_SOFTKEY_DYNAMIC_MODE
        private set

    fun applyInitialPreferences() {
        hapticFeedbackController.syncFromPreferences(preferences)

        beeperVolume = readNormalizedBeeperVolume()
        isBeeperEnabled = preferences.getBoolean(KEY_BEEPER_ENABLED, true)
        lcdTheme = readNormalizedLcdTheme()
        lcdLuminance = readNormalizedLcdLuminance()
        isLcdNegative = preferences.getBoolean(KEY_LCD_NEGATIVE, DEFAULT_LCD_NEGATIVE)
        mainKeyDynamicMode = readNormalizedMainKeyDynamicMode()
        scalingMode =
            preferences.getString(KEY_SCALING_MODE, DEFAULT_SCALING_MODE) ?: DEFAULT_SCALING_MODE
        showTouchZones = preferences.getBoolean(KEY_SHOW_TOUCH_ZONES, false)
        softkeyDynamicMode = readNormalizedSoftkeyDynamicMode()

        applyKeepScreenOn(preferences.getBoolean(KEY_KEEP_SCREEN_ON, false))
        windowModeController.applyFullscreenMode(preferences.getBoolean(KEY_FULLSCREEN_MODE, true))
        syncAudioSettings(isBeeperEnabled, beeperVolume)
        applyKeypadLabelModes(mainKeyDynamicMode, softkeyDynamicMode)
    }

    fun applyDeferredOverlayPreferences() {
        applyShowTouchZones(showTouchZones)
        applyScalingMode(scalingMode)
        applyLcdTheme(lcdTheme, lcdLuminance, isLcdNegative)
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
                applyKeepScreenOn(preferences.getBoolean(key, false))
            }
            KEY_BEEPER_ENABLED -> {
                isBeeperEnabled = preferences.getBoolean(key, true)
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
            KEY_MAIN_KEY_DYNAMIC_MODE -> {
                mainKeyDynamicMode = readNormalizedMainKeyDynamicMode()
                applyKeypadLabelModes(mainKeyDynamicMode, softkeyDynamicMode)
            }
            KEY_SCALING_MODE -> {
                scalingMode = preferences.getString(key, DEFAULT_SCALING_MODE) ?: DEFAULT_SCALING_MODE
                applyScalingMode(scalingMode)
            }
            KEY_SHOW_TOUCH_ZONES -> {
                showTouchZones = preferences.getBoolean(key, false)
                applyShowTouchZones(showTouchZones)
            }
            KEY_SOFTKEY_DYNAMIC_MODE -> {
                softkeyDynamicMode = readNormalizedSoftkeyDynamicMode()
                applyKeypadLabelModes(mainKeyDynamicMode, softkeyDynamicMode)
            }
            KEY_FULLSCREEN_MODE -> {
                windowModeController.applyFullscreenMode(preferences.getBoolean(key, true))
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
            preferences.edit().putInt(key, normalizedVolume).apply()
        }
        return normalizedVolume
    }

    private fun readNormalizedLcdLuminance(): Int {
        val storedLuminance = preferences.getInt(KEY_LCD_LUMINANCE, DEFAULT_LCD_LUMINANCE)
        val normalizedLuminance = storedLuminance.coerceIn(MIN_LCD_LUMINANCE, MAX_LCD_LUMINANCE)
        if (storedLuminance != normalizedLuminance) {
            preferences.edit().putInt(KEY_LCD_LUMINANCE, normalizedLuminance).apply()
        }
        return normalizedLuminance
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
            preferences.edit()
                .putString(KEY_LCD_THEME, normalizedTheme)
                .remove(LEGACY_KEY_LCD_MODE)
                .apply()
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
            preferences.edit().putString(KEY_MAIN_KEY_DYNAMIC_MODE, normalizedMode.storageValue).apply()
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
            preferences.edit().putString(KEY_SOFTKEY_DYNAMIC_MODE, normalizedMode.storageValue).apply()
        }
        return normalizedMode
    }
}
