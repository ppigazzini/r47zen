package com.example.r47

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal class HapticFeedbackController(
    private val context: Context,
    private val defaultIntensity: Int,
) {
    private var isEnabled = true
    private var isHighFidelityEnabled = true
    private var intensity = defaultIntensity

    fun syncFromPreferences(preferences: SharedPreferences) {
        isEnabled = preferences.getBoolean("haptic_enabled", true)
        isHighFidelityEnabled = preferences.getBoolean("haptic_hifi_enabled", true)
        intensity = preferences.getInt("haptic_intensity", defaultIntensity)
    }

    fun onPreferenceChanged(preferences: SharedPreferences, key: String): Boolean {
        when (key) {
            "haptic_enabled" -> isEnabled = preferences.getBoolean(key, true)
            "haptic_hifi_enabled" -> isHighFidelityEnabled = preferences.getBoolean(key, true)
            "haptic_intensity" -> intensity = preferences.getInt(key, defaultIntensity)
            else -> return false
        }

        return true
    }

    fun performClick() {
        if (!isEnabled || intensity <= 0) {
            return
        }

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (isHighFidelityEnabled) {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 10, 20, 5),
                    intArrayOf(0, intensity, 0, intensity / 2),
                    -1,
                )
            } else {
                VibrationEffect.createOneShot(15, intensity)
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(15)
        }
    }
}