package io.github.ppigazzini.r47

import android.content.SharedPreferences
import android.view.Window
import android.view.WindowManager

internal class MainActivityPreferenceController(
    private val preferences: SharedPreferences,
    private val window: Window,
    private val hapticFeedbackController: HapticFeedbackController,
    private val windowModeController: WindowModeController,
    private val syncAudioSettings: (Boolean, Int) -> Unit,
    private val applyLcdMode: (String, Int) -> Unit,
    private val applyChromeMode: (String) -> Unit,
    private val applyScalingMode: (String) -> Unit,
    private val applyShowTouchZones: (Boolean) -> Unit,
    private val applyKeypadLabelModes: (MainKeyDynamicMode, SoftkeyDynamicMode) -> Unit,
    private val normalizeChromeMode: (String?) -> String,
) {
    companion object {
        const val DEFAULT_BEEPER_VOLUME = 20
        const val DEFAULT_CHROME_MODE = ReplicaOverlay.CHROME_MODE_NATIVE
        const val DEFAULT_LCD_MODE = "high_contrast"
        const val DEFAULT_LCD_LUMINANCE = 100
        val DEFAULT_MAIN_KEY_DYNAMIC_MODE = MainKeyDynamicMode.DEFAULT
        const val MIN_LCD_LUMINANCE = 60
        const val MAX_LCD_LUMINANCE = 120
        const val DEFAULT_SCALING_MODE = "full_width"
        val DEFAULT_SOFTKEY_DYNAMIC_MODE = SoftkeyDynamicMode.DEFAULT

        private const val KEY_BEEPER_ENABLED = "beeper_enabled"
        private const val KEY_BEEPER_VOLUME = "beeper_volume"
        private const val KEY_CHROME_MODE = "chrome_mode"
        private const val KEY_FULLSCREEN_MODE = "fullscreen_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_LCD_MODE = "lcd_mode"
        private const val KEY_LCD_LUMINANCE = "lcd_luminance"
        private const val KEY_MAIN_KEY_DYNAMIC_MODE = "main_key_dynamic_mode"
        private const val KEY_SCALING_MODE = "scaling_mode"
        private const val KEY_SHOW_TOUCH_ZONES = "show_touch_zones"
        private const val KEY_SOFTKEY_DYNAMIC_MODE = "softkey_dynamic_mode"
    }

    var beeperVolume = DEFAULT_BEEPER_VOLUME
        private set

    var chromeMode = DEFAULT_CHROME_MODE
        private set

    var isBeeperEnabled = true
        private set

    var lcdMode = DEFAULT_LCD_MODE
        private set

    var lcdLuminance = DEFAULT_LCD_LUMINANCE
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

        beeperVolume = preferences.getInt(KEY_BEEPER_VOLUME, DEFAULT_BEEPER_VOLUME)
        isBeeperEnabled = preferences.getBoolean(KEY_BEEPER_ENABLED, true)
        chromeMode = normalizeAndPersistChromeMode()
        lcdMode = preferences.getString(KEY_LCD_MODE, DEFAULT_LCD_MODE) ?: DEFAULT_LCD_MODE
        lcdLuminance = readNormalizedLcdLuminance()
        mainKeyDynamicMode = readNormalizedMainKeyDynamicMode()
        scalingMode =
            preferences.getString(KEY_SCALING_MODE, DEFAULT_SCALING_MODE) ?: DEFAULT_SCALING_MODE
        showTouchZones = preferences.getBoolean(KEY_SHOW_TOUCH_ZONES, false)
        softkeyDynamicMode = readNormalizedSoftkeyDynamicMode()

        applyKeepScreenOn(preferences.getBoolean(KEY_KEEP_SCREEN_ON, false))
        windowModeController.applyFullscreenMode(preferences.getBoolean(KEY_FULLSCREEN_MODE, true))
        syncAudioSettings(isBeeperEnabled, beeperVolume)
        applyKeypadLabelModes(mainKeyDynamicMode, softkeyDynamicMode)
        applyChromeMode(chromeMode)
    }

    fun applyDeferredOverlayPreferences() {
        applyShowTouchZones(showTouchZones)
        applyScalingMode(scalingMode)
        applyLcdMode(lcdMode, lcdLuminance)
    }

    fun onPreferenceChanged(key: String): Boolean {
        if (hapticFeedbackController.onPreferenceChanged(preferences, key)) {
            return true
        }

        when (key) {
            KEY_BEEPER_VOLUME -> {
                beeperVolume = preferences.getInt(key, DEFAULT_BEEPER_VOLUME)
                syncAudioSettings(isBeeperEnabled, beeperVolume)
            }
            KEY_KEEP_SCREEN_ON -> {
                applyKeepScreenOn(preferences.getBoolean(key, false))
            }
            KEY_BEEPER_ENABLED -> {
                isBeeperEnabled = preferences.getBoolean(key, true)
                syncAudioSettings(isBeeperEnabled, beeperVolume)
            }
            KEY_LCD_MODE -> {
                lcdMode = preferences.getString(key, DEFAULT_LCD_MODE) ?: DEFAULT_LCD_MODE
                applyLcdMode(lcdMode, lcdLuminance)
            }
            KEY_LCD_LUMINANCE -> {
                lcdLuminance = readNormalizedLcdLuminance()
                applyLcdMode(lcdMode, lcdLuminance)
            }
            KEY_CHROME_MODE -> {
                chromeMode = normalizeAndPersistChromeMode()
                applyChromeMode(chromeMode)
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

    private fun normalizeAndPersistChromeMode(): String {
        val storedMode = preferences.getString(KEY_CHROME_MODE, DEFAULT_CHROME_MODE)
        val normalizedMode = normalizeChromeMode(storedMode)
        if (storedMode != normalizedMode) {
            preferences.edit().putString(KEY_CHROME_MODE, normalizedMode).apply()
        }
        return normalizedMode
    }

    private fun readNormalizedLcdLuminance(): Int {
        val storedLuminance = preferences.getInt(KEY_LCD_LUMINANCE, DEFAULT_LCD_LUMINANCE)
        val normalizedLuminance = storedLuminance.coerceIn(MIN_LCD_LUMINANCE, MAX_LCD_LUMINANCE)
        if (storedLuminance != normalizedLuminance) {
            preferences.edit().putInt(KEY_LCD_LUMINANCE, normalizedLuminance).apply()
        }
        return normalizedLuminance
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
