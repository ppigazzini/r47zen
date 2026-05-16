package io.github.ppigazzini.r47zen

import android.content.Context
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun performClick_enabledDispatchesVirtualKeyHapticFeedbackOnTargetView() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val view = AcceptingHapticView(context)
        val controller = HapticFeedbackController(context)
        controller.syncFromPreferences(preferences)

        assertTrue(controller.performClick(view))

        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, view.lastFeedbackConstant)
        assertEquals(HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING, view.lastFeedbackFlags)
    }

    @Test
    fun performClick_disabledSuppressesViewBasedHapticFeedback() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putBoolean("haptic_enabled", false)
            .commit()
        val view = AcceptingHapticView(context)
        val controller = HapticFeedbackController(context)
        controller.syncFromPreferences(preferences)

        assertFalse(controller.performClick(view))

        assertNull(view.lastFeedbackConstant)
    }

    @Test
    fun performClick_fallsBackToVibratorWhenViewFeedbackReturnsFalse() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        val shadowVibrator = shadowOf(vibratorManager.defaultVibrator)
        shadowVibrator.setHasVibrator(true)
        val view = RejectingHapticView(context)
        val controller = HapticFeedbackController(context)
        controller.syncFromPreferences(preferences)

        assertTrue(controller.performClick(view))

        assertTrue(shadowVibrator.isVibrating)
    }

    @Test
    fun performClick_customDurationOverridesViewFeedbackAndUsesAppOwnedPulse() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putInt(HapticFeedbackController.KEY_HAPTIC_KEYPRESS_DURATION_MS, 6)
            .commit()
        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        val shadowVibrator = shadowOf(vibratorManager.defaultVibrator)
        shadowVibrator.setHasVibrator(true)
        val view = AcceptingHapticView(context)
        val controller = HapticFeedbackController(context)
        controller.syncFromPreferences(preferences)

        assertTrue(controller.performClick(view))

        assertNull(view.lastFeedbackConstant)
        assertTrue(shadowVibrator.isVibrating)
    }

    @Test
    fun performClick_customDurationStillUsesAppOwnedPulseWhenViewFeedbackReturnsFalse() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putInt(HapticFeedbackController.KEY_HAPTIC_KEYPRESS_DURATION_MS, 6)
            .commit()
        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        val shadowVibrator = shadowOf(vibratorManager.defaultVibrator)
        shadowVibrator.setHasVibrator(true)
        val view = RejectingHapticView(context)
        val controller = HapticFeedbackController(context)
        controller.syncFromPreferences(preferences)

        assertTrue(controller.performClick(view))

        assertTrue(shadowVibrator.isVibrating)
    }

    @Test
    fun syncFromPreferences_clampsStoredCustomDurationIntoSupportedRange() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putInt(HapticFeedbackController.KEY_HAPTIC_KEYPRESS_DURATION_MS, 99)
            .commit()
        val controller = HapticFeedbackController(context)

        controller.syncFromPreferences(preferences)

        assertEquals(
            HapticFeedbackController.MAX_HAPTIC_KEYPRESS_DURATION_MS,
            preferences.getInt(HapticFeedbackController.KEY_HAPTIC_KEYPRESS_DURATION_MS, -1),
        )
    }

    @Test
    fun onPreferenceChanged_returnsFalseForUnknownKey() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val controller = HapticFeedbackController(context)

        assertFalse(controller.onPreferenceChanged(preferences, "beeper_enabled"))
    }

    @Test
    fun onPreferenceChanged_hapticEnabledUpdatesViewBasedDispatchGate() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val view = AcceptingHapticView(context)
        val controller = HapticFeedbackController(context)

        controller.syncFromPreferences(preferences)
        preferences.edit().putBoolean("haptic_enabled", false).commit()

        assertTrue(controller.onPreferenceChanged(preferences, "haptic_enabled"))
        assertFalse(controller.performClick(view))

        assertNull(view.lastFeedbackConstant)
    }

    @Test
    fun onPreferenceChanged_customDurationUpdatesControllerState() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val vibratorManager = context.getSystemService(VibratorManager::class.java)
        val shadowVibrator = shadowOf(vibratorManager.defaultVibrator)
        shadowVibrator.setHasVibrator(true)
        val view = AcceptingHapticView(context)
        val controller = HapticFeedbackController(context)

        controller.syncFromPreferences(preferences)
        preferences.edit()
            .putInt(HapticFeedbackController.KEY_HAPTIC_KEYPRESS_DURATION_MS, 4)
            .commit()

        assertTrue(
            controller.onPreferenceChanged(
                preferences,
                HapticFeedbackController.KEY_HAPTIC_KEYPRESS_DURATION_MS,
            )
        )
        assertTrue(controller.performClick(view))

        assertNull(view.lastFeedbackConstant)
        assertTrue(shadowVibrator.isVibrating)
    }

    private class AcceptingHapticView(context: Context) : View(context) {
        var lastFeedbackConstant: Int? = null
            private set
        var lastFeedbackFlags: Int? = null
            private set

        override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
            lastFeedbackConstant = feedbackConstant
            lastFeedbackFlags = flags
            return true
        }
    }

    private class RejectingHapticView(context: Context) : View(context) {
        override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean = false
    }
}
