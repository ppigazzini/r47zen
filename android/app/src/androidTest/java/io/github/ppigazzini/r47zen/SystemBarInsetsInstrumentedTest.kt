package io.github.ppigazzini.r47zen

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.core.content.edit
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves on a device that no screen draws under the system bars. The window is
 * edge to edge on every API level, so the insets here are the real bar and
 * cutout sizes; a root that ignored them would put its bounds inside them.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarInsetsInstrumentedTest {
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences(SlotStore.APP_PREFS_NAME, Context.MODE_PRIVATE)
    private var savedFullscreen: Boolean? = null

    @Before
    fun showTheSystemBars() {
        savedFullscreen = preferences.takeIf { it.contains(KEY_FULLSCREEN) }?.getBoolean(KEY_FULLSCREEN, true)
        preferences.edit(commit = true) { putBoolean(KEY_FULLSCREEN, false) }
    }

    @After
    fun restoreFullscreenPreference() {
        preferences.edit(commit = true) {
            savedFullscreen?.let { putBoolean(KEY_FULLSCREEN, it) } ?: remove(KEY_FULLSCREEN)
        }
    }

    @Test
    fun mainScreenKeepsTheCalculatorOutOfTheSystemBars() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertInsideSafeArea(scenario, "the calculator view") { it.findViewById(R.id.replica_overlay) }
        }
    }

    @Test
    fun settingsKeepsItsToolbarAndContentOutOfTheSystemBars() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            assertInsideSafeArea(scenario, "the Settings toolbar") { it.findViewById(R.id.top_app_bar) }
            assertInsideSafeArea(scenario, "the Settings list") { it.findViewById(R.id.settings) }
        }
    }

    private fun <A : Activity> assertInsideSafeArea(
        scenario: ActivityScenario<A>,
        label: String,
        find: (A) -> View,
    ) {
        var sample: Sample? = null
        val deadline = SystemClock.elapsedRealtime() + LAYOUT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity -> sample = measure(activity, find(activity)) }
            if (sample?.isInside() == true) {
                break
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        val measured = checkNotNull(sample) { "$label was never laid out with window insets" }
        assertTrue(
            "the status bar has no inset with fullscreen off, so this check proves nothing: $measured",
            measured.safe.top > 0,
        )
        assertTrue("$label reaches into the system bars or cutout: $measured", measured.isInside())
    }

    private fun measure(activity: Activity, view: View): Sample? {
        val decor = activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor) ?: return null
        if (view.width == 0 || view.height == 0) {
            return null
        }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return Sample(
            bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height),
            window = Rect(0, 0, decor.width, decor.height),
            safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()),
        )
    }

    private data class Sample(val bounds: Rect, val window: Rect, val safe: Insets) {
        fun isInside(): Boolean =
            bounds.left >= window.left + safe.left &&
                bounds.top >= window.top + safe.top &&
                bounds.right <= window.right - safe.right &&
                bounds.bottom <= window.bottom - safe.bottom
    }

    private companion object {
        private const val KEY_FULLSCREEN = MainActivityPreferenceController.KEY_FULLSCREEN_MODE
        private const val LAYOUT_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 50L
    }
}
