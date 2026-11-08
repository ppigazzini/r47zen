package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.edit

internal class HapticFeedbackController(
    private val context: Context,
    private val hapticFeedbackConstant: Int = HapticFeedbackConstants.VIRTUAL_KEY,
) {
    companion object {
        internal const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        internal const val KEY_HAPTIC_USE_ANDROID_DEFAULT = "haptic_use_android_default"
        internal const val KEY_HAPTIC_KEYPRESS_DURATION_MS = "haptic_keypress_duration_ms"
        internal const val DEFAULT_HAPTIC_USE_ANDROID_DEFAULT = true
        internal const val DEFAULT_HAPTIC_KEYPRESS_DURATION_MS = 0
        internal const val MAX_HAPTIC_KEYPRESS_DURATION_MS = 100

        internal fun normalizePreferences(preferences: SharedPreferences) {
            val storedDuration = preferences.getInt(
                KEY_HAPTIC_KEYPRESS_DURATION_MS,
                DEFAULT_HAPTIC_KEYPRESS_DURATION_MS,
            )
            val normalizedDuration = storedDuration.coerceIn(
                DEFAULT_HAPTIC_KEYPRESS_DURATION_MS,
                MAX_HAPTIC_KEYPRESS_DURATION_MS,
            )
            val hasUseAndroidDefault = preferences.contains(KEY_HAPTIC_USE_ANDROID_DEFAULT)
            val useAndroidDefault = if (hasUseAndroidDefault) {
                preferences.getBoolean(
                    KEY_HAPTIC_USE_ANDROID_DEFAULT,
                    DEFAULT_HAPTIC_USE_ANDROID_DEFAULT,
                )
            } else {
                normalizedDuration == DEFAULT_HAPTIC_KEYPRESS_DURATION_MS
            }

            if (storedDuration != normalizedDuration || !hasUseAndroidDefault) {
                preferences.edit {
                    if (storedDuration != normalizedDuration) {
                        putInt(KEY_HAPTIC_KEYPRESS_DURATION_MS, normalizedDuration)
                    }
                    if (!hasUseAndroidDefault) {
                        putBoolean(KEY_HAPTIC_USE_ANDROID_DEFAULT, useAndroidDefault)
                    }
                }
            }
        }
    }

    private var isEnabled = true
    private var useAndroidDefault = DEFAULT_HAPTIC_USE_ANDROID_DEFAULT
    private var customKeypressDurationMs = DEFAULT_HAPTIC_KEYPRESS_DURATION_MS

    fun syncFromPreferences(preferences: SharedPreferences) {
        normalizePreferences(preferences)
        isEnabled = preferences.getBoolean(KEY_HAPTIC_ENABLED, true)
        useAndroidDefault = preferences.getBoolean(
            KEY_HAPTIC_USE_ANDROID_DEFAULT,
            DEFAULT_HAPTIC_USE_ANDROID_DEFAULT,
        )
        customKeypressDurationMs = preferences.getInt(
            KEY_HAPTIC_KEYPRESS_DURATION_MS,
            DEFAULT_HAPTIC_KEYPRESS_DURATION_MS,
        )
    }

    fun onPreferenceChanged(preferences: SharedPreferences, key: String): Boolean {
        when (key) {
            KEY_HAPTIC_ENABLED -> {
                isEnabled = preferences.getBoolean(key, true)
                return true
            }
            KEY_HAPTIC_USE_ANDROID_DEFAULT, KEY_HAPTIC_KEYPRESS_DURATION_MS -> {
                normalizePreferences(preferences)
                useAndroidDefault = preferences.getBoolean(
                    KEY_HAPTIC_USE_ANDROID_DEFAULT,
                    DEFAULT_HAPTIC_USE_ANDROID_DEFAULT,
                )
                customKeypressDurationMs = preferences.getInt(
                    KEY_HAPTIC_KEYPRESS_DURATION_MS,
                    DEFAULT_HAPTIC_KEYPRESS_DURATION_MS,
                )
                return true
            }
            else -> return false
        }
    }

    fun performClick(targetView: View): Boolean {
        val customDurationMs = customKeypressDurationMs
            .takeIf { it > DEFAULT_HAPTIC_KEYPRESS_DURATION_MS }
            ?.toLong()
        return when {
            !isEnabled -> false
            useAndroidDefault -> performDefaultFeedback(targetView)
            customDurationMs == null -> false
            else -> performCustomPulse(customDurationMs)
        }
    }

    // The system's own feedback, or none: on API 32 and below the view declines when
    // the user turned touch feedback off, and a vibration then would override that
    // choice. Android's haptics guide recommends falling back to nothing.
    private fun performDefaultFeedback(targetView: View): Boolean {
        return targetView.performHapticFeedback(
            hapticFeedbackConstant,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
    }

    private fun performCustomPulse(durationMs: Long): Boolean {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }?.takeIf { it.hasVibrator() } ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }

        return true
    }
}
