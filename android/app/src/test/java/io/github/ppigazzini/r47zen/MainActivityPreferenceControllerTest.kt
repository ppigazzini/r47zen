package io.github.ppigazzini.r47zen

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class MainActivityPreferenceControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferencesBeforeTest() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun clearPreferencesAfterTest() {
        context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun applyInitialPreferences_clampsBeeperVolumeBeforeAudioDispatch() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("beeper_volume", 999)
            .commit()

        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()

        assertEquals(100, controllerState.controller.beeperVolume)
        assertEquals(100, preferences.getInt("beeper_volume", -1))
        assertEquals(listOf(true to 100), controllerState.audioSettingsCalls)
    }

    @Test
    fun onPreferenceChanged_clampsNegativeBeeperVolumeAndDispatchesNormalizedAudioState() {
        val preferences = context.getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
        val controllerState = buildController(preferences)

        controllerState.controller.applyInitialPreferences()
        controllerState.audioSettingsCalls.clear()

        preferences.edit()
            .putInt("beeper_volume", -4)
            .commit()

        assertTrue(controllerState.controller.onPreferenceChanged("beeper_volume"))

        assertEquals(0, controllerState.controller.beeperVolume)
        assertEquals(0, preferences.getInt("beeper_volume", -1))
        assertEquals(listOf(true to 0), controllerState.audioSettingsCalls)
    }

    private fun buildController(preferences: android.content.SharedPreferences): ControllerState {
        val activity = Robolectric.buildActivity(PreferenceControllerActivity::class.java)
            .setup()
            .get()
        val audioSettingsCalls = mutableListOf<Pair<Boolean, Int>>()

        val controller = MainActivityPreferenceController(
            preferences = preferences,
            window = activity.window,
            hapticFeedbackController = HapticFeedbackController(activity),
            windowModeController = WindowModeController(
                activity = activity,
                mainHandler = Handler(Looper.getMainLooper()),
                onPiPModeChanged = {},
            ),
            syncAudioSettings = { enabled, volume -> audioSettingsCalls += enabled to volume },
            applyLcdMode = { _, _ -> },
            applyScalingMode = {},
            applyShowTouchZones = {},
            applyKeypadLabelModes = { _, _ -> },
        )

        return ControllerState(controller = controller, audioSettingsCalls = audioSettingsCalls)
    }

    private data class ControllerState(
        val controller: MainActivityPreferenceController,
        val audioSettingsCalls: MutableList<Pair<Boolean, Int>>,
    )

    private class PreferenceControllerActivity : AppCompatActivity()
}
