package io.github.ppigazzini.r47zen

import android.widget.ListView
import androidx.preference.Preference
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // A release build lists every Maven dependency, whose oss-licenses entry is
    // often only a link to its license; the screen must show the terms, not the
    // address. A debug variant holds the plugin's one placeholder entry.
    @Test
    fun everyLibraryLicenseEntry_namesAnAssetThatHoldsText() {
        val context = Robolectric.buildActivity(SettingsActivity::class.java).get()
        val index = context.assets.open(RepoNoticeIndexActivity.LIBRARY_LICENSES_INDEX)
            .bufferedReader().use { org.json.JSONObject(it.readText()) }
        val entries = index.getJSONArray("entries")
        val bareLink = Regex("""^https?://\S+$""")

        assertTrue(entries.length() > 0)
        for (position in 0 until entries.length()) {
            val entry = entries.getJSONObject(position)
            val text = context.assets.open(entry.getString("assetPath")).bufferedReader().use { it.readText() }
            assertTrue(entry.getString("title"), text.isNotBlank())
            assertFalse(
                "${entry.getString("title")} shows a link, not a license: ${text.trim()}",
                bareLink.matches(text.trim()),
            )
        }
    }

    // Every component the shipped SPDX inventory names outside Maven has a repo
    // notice under the license the inventory declares, every copyright it states
    // is in a notice the app shows, and an Apache-2.0 component comes with the
    // license's own text (section 4(a)). PCG was linked and in neither.
    @Test
    fun everyComponentOutsideMaven_hasARepoNoticeUnderItsLicense() {
        val assets = Robolectric.buildActivity(SettingsActivity::class.java).get().assets
        fun read(path: String) = assets.open(path).bufferedReader().use { it.readText() }
        val entries = JSONObject(read("repo-notices/NOTICE-INDEX.json")).getJSONArray("entries")
        val notices = (0 until entries.length()).map { entries.getJSONObject(it) }
        val textByLicense = notices.groupBy({ it.getString("declaredLicense") }) { read(it.getString("assetPath")) }
        val allNoticeText = textByLicense.values.flatten().joinToString("\n")
        val packages = JSONObject(read("THIRD-PARTY.spdx.json")).getJSONArray("packages")
        val outsideMaven = (0 until packages.length()).map { packages.getJSONObject(it) }
            .filterNot { it.getString("SPDXID").startsWith("SPDXRef-maven-") }

        assertTrue(outsideMaven.any { it.getString("name").startsWith("PCG") })
        for (component in outsideMaven) {
            val name = component.getString("name")
            val license = component.getString("licenseDeclared")
            assertTrue("$name: no repo notice under $license", license in textByLicense)
            val copyright = component.optString("copyrightText", "NOASSERTION")
            if (copyright != "NOASSERTION") {
                assertTrue("$name: no notice states $copyright", copyright in allNoticeText)
            }
        }
        assertTrue(
            "no Apache-2.0 notice holds the license's terms",
            textByLicense["Apache-2.0"].orEmpty().any { APACHE_TERMS_HEADING in it },
        )
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

    private companion object {
        const val APACHE_TERMS_HEADING = "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"
    }
}
