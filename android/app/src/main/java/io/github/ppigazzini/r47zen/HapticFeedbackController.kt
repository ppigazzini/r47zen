package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

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

        private const val DEFAULT_CLICK_FALLBACK_DURATION_MS = 15L

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
                preferences.edit().apply {
                    if (storedDuration != normalizedDuration) {
                        putInt(KEY_HAPTIC_KEYPRESS_DURATION_MS, normalizedDuration)
                    }
                    if (!hasUseAndroidDefault) {
                        putBoolean(KEY_HAPTIC_USE_ANDROID_DEFAULT, useAndroidDefault)
                    }
                }.apply()
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
            KEY_HAPTIC_USE_ANDROID_DEFAULT -> {
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
            KEY_HAPTIC_KEYPRESS_DURATION_MS -> {
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
        if (!isEnabled) {
            return false
        }

        if (useAndroidDefault) {
            return performDefaultFeedback(targetView)
        }

        val customDurationMs = customKeypressDurationMs
            .takeIf { it > DEFAULT_HAPTIC_KEYPRESS_DURATION_MS }
            ?.toLong()
            ?: return false

        return performFallbackFeedback(
            predefinedEffect = VibrationEffect.EFFECT_CLICK,
            durationMs = customDurationMs,
            useCustomDuration = true,
        )
    }

    private fun performDefaultFeedback(targetView: View): Boolean {
        if (targetView.performHapticFeedback(
                hapticFeedbackConstant,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        ) {
            return true
        }

        return performFallbackFeedback(
            predefinedEffect = VibrationEffect.EFFECT_CLICK,
            durationMs = DEFAULT_CLICK_FALLBACK_DURATION_MS,
            useCustomDuration = false,
        )
    }

    private fun performFallbackFeedback(
        predefinedEffect: Int,
        durationMs: Long,
        useCustomDuration: Boolean,
    ): Boolean {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(VibratorManager::class.java)
            vibratorManager?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return false

        if (!vibrator.hasVibrator()) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (!useCustomDuration && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(predefinedEffect)
            } else {
                VibrationEffect.createOneShot(
                    durationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }

        return true
    }
}
