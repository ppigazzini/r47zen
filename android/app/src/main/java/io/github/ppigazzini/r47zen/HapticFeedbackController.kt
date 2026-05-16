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
        internal const val KEY_HAPTIC_KEYPRESS_DURATION_MS = "haptic_keypress_duration_ms"
        internal const val DEFAULT_HAPTIC_KEYPRESS_DURATION_MS = 0
        internal const val MAX_HAPTIC_KEYPRESS_DURATION_MS = 20

        private const val DEFAULT_CLICK_FALLBACK_DURATION_MS = 15L
    }

    private var isEnabled = true
    private var customKeypressDurationMs = DEFAULT_HAPTIC_KEYPRESS_DURATION_MS

    fun syncFromPreferences(preferences: SharedPreferences) {
        isEnabled = preferences.getBoolean(KEY_HAPTIC_ENABLED, true)
        customKeypressDurationMs = readNormalizedKeypressDuration(preferences)
    }

    fun onPreferenceChanged(preferences: SharedPreferences, key: String): Boolean {
        when (key) {
            KEY_HAPTIC_ENABLED -> {
                isEnabled = preferences.getBoolean(key, true)
                return true
            }
            KEY_HAPTIC_KEYPRESS_DURATION_MS -> {
                customKeypressDurationMs = readNormalizedKeypressDuration(preferences)
                return true
            }
            else -> return false
        }
    }

    fun performClick(targetView: View): Boolean {
        return performFeedback(
            targetView = targetView,
            feedbackConstant = hapticFeedbackConstant,
            predefinedEffect = VibrationEffect.EFFECT_CLICK,
            defaultFallbackDurationMs = DEFAULT_CLICK_FALLBACK_DURATION_MS,
            fallbackOverrideDurationMs = resolveCustomClickDurationMs(),
        )
    }

    private fun performFeedback(
        targetView: View,
        feedbackConstant: Int,
        predefinedEffect: Int,
        defaultFallbackDurationMs: Long,
        fallbackOverrideDurationMs: Long?,
    ): Boolean {
        if (!isEnabled) {
            return false
        }

        if (fallbackOverrideDurationMs == null && targetView.performHapticFeedback(
                feedbackConstant,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        ) {
            return true
        }

        return performFallbackFeedback(
            predefinedEffect = predefinedEffect,
            durationMs = fallbackOverrideDurationMs ?: defaultFallbackDurationMs,
            useCustomDuration = fallbackOverrideDurationMs != null,
        )
    }

    private fun resolveCustomClickDurationMs(): Long? {
        return customKeypressDurationMs
            .takeIf { it > DEFAULT_HAPTIC_KEYPRESS_DURATION_MS }
            ?.toLong()
    }

    private fun readNormalizedKeypressDuration(preferences: SharedPreferences): Int {
        val storedDuration = preferences.getInt(
            KEY_HAPTIC_KEYPRESS_DURATION_MS,
            DEFAULT_HAPTIC_KEYPRESS_DURATION_MS,
        )
        val normalizedDuration = storedDuration.coerceIn(
            DEFAULT_HAPTIC_KEYPRESS_DURATION_MS,
            MAX_HAPTIC_KEYPRESS_DURATION_MS,
        )
        if (storedDuration != normalizedDuration) {
            preferences.edit().putInt(KEY_HAPTIC_KEYPRESS_DURATION_MS, normalizedDuration).apply()
        }
        return normalizedDuration
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
