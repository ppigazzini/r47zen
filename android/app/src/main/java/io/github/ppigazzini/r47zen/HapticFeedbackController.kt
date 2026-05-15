package io.github.ppigazzini.r47zen

import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.View

internal class HapticFeedbackController(
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

        return targetView.performHapticFeedback(hapticFeedbackConstant)
    }
}
