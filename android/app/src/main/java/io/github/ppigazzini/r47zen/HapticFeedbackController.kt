package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

internal class HapticFeedbackController(
    private val context: Context,
    defaultIntensity: Int,
) {
    companion object {
        private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
        private const val KEY_HAPTIC_HIFI_ENABLED = "haptic_hifi_enabled"
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val MIN_HAPTIC_INTENSITY = 0
        private const val MAX_HAPTIC_INTENSITY = 255
    }

    private val defaultIntensity =
        defaultIntensity.coerceIn(MIN_HAPTIC_INTENSITY, MAX_HAPTIC_INTENSITY)

    private var isEnabled = true
    private var isHighFidelityEnabled = true
    private var intensity = defaultIntensity

    fun syncFromPreferences(preferences: SharedPreferences) {
        isEnabled = preferences.getBoolean(KEY_HAPTIC_ENABLED, true)
        isHighFidelityEnabled = preferences.getBoolean(KEY_HAPTIC_HIFI_ENABLED, true)
        intensity = readNormalizedIntensity(preferences, KEY_HAPTIC_INTENSITY)
    }

    fun onPreferenceChanged(preferences: SharedPreferences, key: String): Boolean {
        when (key) {
            KEY_HAPTIC_ENABLED -> isEnabled = preferences.getBoolean(key, true)
            KEY_HAPTIC_HIFI_ENABLED -> isHighFidelityEnabled = preferences.getBoolean(key, true)
            KEY_HAPTIC_INTENSITY -> intensity = readNormalizedIntensity(preferences, key)
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

    private fun readNormalizedIntensity(preferences: SharedPreferences, key: String): Int {
        val storedIntensity = preferences.getInt(key, defaultIntensity)
        val normalizedIntensity =
            storedIntensity.coerceIn(MIN_HAPTIC_INTENSITY, MAX_HAPTIC_INTENSITY)
        if (storedIntensity != normalizedIntensity) {
            preferences.edit().putInt(key, normalizedIntensity).apply()
        }
        return normalizedIntensity
    }
}
