package io.github.ppigazzini.r47zen

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
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
    }

    @After
    fun clearStateAfterTest() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun performClick_enabledDispatchesVirtualKeyHapticFeedbackOnTargetView() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val view = View(context)
        val controller = HapticFeedbackController()
        controller.syncFromPreferences(preferences)

        assertTrue(controller.performClick(view))

        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test
    fun performClick_disabledSuppressesViewBasedHapticFeedback() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putBoolean("haptic_enabled", false)
            .commit()
        val view = View(context)
        val controller = HapticFeedbackController()
        controller.syncFromPreferences(preferences)

        assertFalse(controller.performClick(view))

        assertEquals(-1, shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test
    fun onPreferenceChanged_returnsFalseForUnknownKey() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val controller = HapticFeedbackController()

        assertFalse(controller.onPreferenceChanged(preferences, "beeper_enabled"))
    }

    @Test
    fun onPreferenceChanged_hapticEnabledUpdatesViewBasedDispatchGate() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val view = View(context)
        val controller = HapticFeedbackController()

        controller.syncFromPreferences(preferences)
        preferences.edit().putBoolean("haptic_enabled", false).commit()

        assertTrue(controller.onPreferenceChanged(preferences, "haptic_enabled"))
        assertFalse(controller.performClick(view))

        assertEquals(-1, shadowOf(view).lastHapticFeedbackPerformed())
    }
}
