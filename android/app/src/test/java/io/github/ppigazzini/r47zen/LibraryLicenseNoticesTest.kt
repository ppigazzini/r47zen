package io.github.ppigazzini.r47zen

import android.widget.ListView
import androidx.preference.Preference
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The library license texts come from the oss-licenses plugin through
// generate<Variant>LibraryLicenseAssets, and the app shows them on its own
// notice screens. These tests read the assets the build merged, so a variant
// whose index is missing or empty fails here, not on a device.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
class LibraryLicenseNoticesTest {
    @Test
    fun libraryLicensesEntry_opensTheNoticeIndexOnTheLibraryLicenses() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
        val preference = requireNotNull(fragment.findPreference<Preference>("view_android_library_licenses"))

        requireNotNull(preference.onPreferenceClickListener).onPreferenceClick(preference)

        val started = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(RepoNoticeIndexActivity::class.java.name, started.component?.className)
        val index = Robolectric.buildActivity(RepoNoticeIndexActivity::class.java, started).setup().get()
        assertEquals(
            activity.getString(R.string.about_android_library_licenses_title),
            index.findViewById<MaterialToolbar>(R.id.top_app_bar).title,
        )
        assertTrue(index.findViewById<ListView>(R.id.notice_list).adapter.count > 0)
    }

    @Test
    fun everyLibraryLicenseEntry_namesAnAssetThatHoldsText() {
        val context = Robolectric.buildActivity(SettingsActivity::class.java).get()
        val index = context.assets.open(RepoNoticeIndexActivity.LIBRARY_LICENSES_INDEX)
            .bufferedReader().use { org.json.JSONObject(it.readText()) }
        val entries = index.getJSONArray("entries")

        assertTrue(entries.length() > 0)
        for (position in 0 until entries.length()) {
            val entry = entries.getJSONObject(position)
            val text = context.assets.open(entry.getString("assetPath")).bufferedReader().use { it.readText() }
            assertTrue(entry.getString("title"), text.isNotBlank())
        }
    }

    @Test
    fun noticeIndex_withoutExtras_stillListsTheRepoNotices() {
        val index = Robolectric.buildActivity(RepoNoticeIndexActivity::class.java).setup().get()

        assertNotNull(index.findViewById<ListView>(R.id.notice_list).adapter)
        assertEquals(
            index.getString(R.string.repo_notice_catalog_title),
            index.findViewById<MaterialToolbar>(R.id.top_app_bar).title,
        )
    }
}
