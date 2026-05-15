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
        if (!isEnabled) {
            return false
        }

        if (targetView.performHapticFeedback(
                hapticFeedbackConstant,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        ) {
            return true
        }

        return performFallbackClick()
    }

    private fun performFallbackClick(): Boolean {
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
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(15L)
        }

        return true
    }
}
