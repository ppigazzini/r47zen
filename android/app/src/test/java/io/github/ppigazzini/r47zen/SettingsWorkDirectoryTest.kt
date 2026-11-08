package io.github.ppigazzini.r47zen

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
class SettingsWorkDirectoryTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ACalc".toUri()
    private val queuedLookups = ArrayDeque<Runnable>()
    private val originalLookupWorker = ProviderLookup.worker

    @Before
    fun queueLookups() {
        ProviderLookup.worker = Executor { queuedLookups.addLast(it) }
    }

    @After
    fun restore() {
        ProviderLookup.worker = originalLookupWorker
        application.getSharedPreferences(WorkDirectory.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // Whether the folder is still reachable is a provider's answer, which can block: the
    // summary shows the stored folder at once, and only the worker's answer changes it.
    @Test
    fun theWorkDirectorySummary_asksTheProviderOffTheMainThread() {
        WorkDirectory.writeTreeUriString(application, treeUri)
        val preference = workDirectoryPreference()
        val stored = application.getString(
            R.string.settings_work_directory_summary_set,
            WorkDirectory.formatDisplayPath(treeUri.path, "?"),
        )

        assertEquals(stored, preference.summary)
        assertTrue("the check did not wait for the worker", queuedLookups.isNotEmpty())

        // No grant was ever taken, which the worker finds.
        while (queuedLookups.isNotEmpty()) {
            queuedLookups.removeFirst().run()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(application.getString(R.string.settings_work_directory_summary_inaccessible), preference.summary)
    }

    @Test
    fun withNoWorkDirectory_theSummarySaysSo_andAsksNoProvider() {
        val preference = workDirectoryPreference()

        assertEquals(application.getString(R.string.settings_work_directory_summary_unset), preference.summary)
        assertTrue(queuedLookups.isEmpty())
    }

    private fun workDirectoryPreference(): Preference {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
        return requireNotNull(fragment.findPreference<Preference>("work_directory"))
    }
}
