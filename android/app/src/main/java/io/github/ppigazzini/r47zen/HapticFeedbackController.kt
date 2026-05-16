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
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    }

    private var isEnabled = true

    fun syncFromPreferences(preferences: SharedPreferences) {
        isEnabled = preferences.getBoolean(KEY_HAPTIC_ENABLED, true)
    }

    fun onPreferenceChanged(preferences: SharedPreferences, key: String): Boolean {
        if (key != KEY_HAPTIC_ENABLED) {
            return false
        }

        isEnabled = preferences.getBoolean(key, true)
        return true
    }

    fun performClick(targetView: View): Boolean {
        return performFeedback(
            targetView = targetView,
            feedbackConstant = hapticFeedbackConstant,
            predefinedEffect = VibrationEffect.EFFECT_CLICK,
            fallbackDurationMs = 15L,
        )
    }

    fun performRelease(targetView: View): Boolean {
        return performFeedback(
            targetView = targetView,
            feedbackConstant = HapticFeedbackConstants.VIRTUAL_KEY_RELEASE,
            predefinedEffect = VibrationEffect.EFFECT_TICK,
            fallbackDurationMs = 10L,
        )
    }

    private fun performFeedback(
        targetView: View,
        feedbackConstant: Int,
        predefinedEffect: Int,
        fallbackDurationMs: Long,
    ): Boolean {
        if (!isEnabled) {
            return false
        }

        if (targetView.performHapticFeedback(
                feedbackConstant,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        ) {
            return true
        }

        return performFallbackFeedback(predefinedEffect, fallbackDurationMs)
    }

    private fun performFallbackFeedback(predefinedEffect: Int, fallbackDurationMs: Long): Boolean {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(VibratorManager::class.java)
            vibratorManager?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        } ?: return false

        if (!vibrator.hasVibrator()) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(predefinedEffect))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    fallbackDurationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(fallbackDurationMs)
        }

        return true
    }
}
