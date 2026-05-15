package io.github.ppigazzini.r47zen

import android.content.Context
import android.os.Vibrator
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVibrator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HapticFeedbackControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearStateBeforeTest() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ShadowVibrator.reset()
    }

    @After
    fun clearStateAfterTest() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ShadowVibrator.reset()
    }

    @Test
    fun syncFromPreferences_clampsAboveMaxIntensityBeforeWaveformDispatch() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("haptic_intensity", 999)
            .commit()

        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        val shadowVibrator = shadowOf(vibratorManager.defaultVibrator)
        shadowVibrator.setHasVibrator(true)

        val controller = HapticFeedbackController(context, 64)
        controller.syncFromPreferences(preferences)
        controller.performClick()

        assertEquals(255, preferences.getInt("haptic_intensity", -1))
        assertTrue(shadowVibrator.isVibrating)
    }

    @Test
    fun onPreferenceChanged_clampsNegativeIntensityToZeroAndSuppressesClick() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("haptic_intensity", -7)
            .commit()

        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        val shadowVibrator = shadowOf(vibratorManager.defaultVibrator)
        shadowVibrator.setHasVibrator(true)

        val controller = HapticFeedbackController(context, 64)

        assertTrue(controller.onPreferenceChanged(preferences, "haptic_intensity"))

        controller.performClick()

        assertEquals(0, preferences.getInt("haptic_intensity", -1))
        assertFalse(shadowVibrator.isVibrating)
    }

    @Test
    @Config(sdk = [25])
    fun performClick_preOUsesLegacyDuration() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadowVibrator = shadowOf(vibrator)
        shadowVibrator.setHasVibrator(true)

        val controller = HapticFeedbackController(context, 64)
        controller.syncFromPreferences(preferences)
        controller.performClick()

        assertEquals(15L, shadowVibrator.milliseconds)
        assertTrue(shadowVibrator.isVibrating)
    }

    @Test
    fun onPreferenceChanged_returnsFalseForUnknownKey() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val controller = HapticFeedbackController(context, 64)

        assertFalse(controller.onPreferenceChanged(preferences, "beeper_enabled"))
    }
}
