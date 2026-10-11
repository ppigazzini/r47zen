package io.github.ppigazzini.r47zen

import android.app.Application
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
class SettingsLinkTest {
    @Test
    fun externalLinks_withNoAppToOpenThem_showAToastInsteadOfCrashing() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // Resolve intents the way a device does: no browser, no activity.
        shadowOf(application).checkActivities(true)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment

        for (key in listOf("view_android_source", "visit_gitlab")) {
            ShadowToast.reset()
            val preference = requireNotNull(fragment.findPreference<Preference>(key)) { key }

            requireNotNull(preference.onPreferenceClickListener) { key }.onPreferenceClick(preference)

            assertEquals(
                key,
                application.getString(R.string.settings_no_app_for_link),
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }
}
