package io.github.ppigazzini.r47zen

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

private const val AUTHORITY = "io.github.ppigazzini.r47zen.test.documents"

/** A document provider that knows the names it is given and can fail on request. */
class FakeDocuments : ContentProvider() {
    val names = mutableMapOf<Uri, String>()
    var failure: RuntimeException? = null
    var queries = 0

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queries++
        failure?.let { throw it }
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
        names[uri]?.let { cursor.addRow(arrayOf(it)) }
        return cursor
    }

    override fun getType(uri: Uri): String = SavedManual.MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

/** A picker result with no persistable grant, which the platform reports this way. */
@Implements(ContentResolver::class)
class RefusingContentResolverShadow : ShadowContentResolver() {
    @Implementation
    override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
        throw SecurityException("No persistable permission grants found")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
class SavedManualTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val documents = Robolectric.setupContentProvider(FakeDocuments::class.java, AUTHORITY)
    private val firstCopy = "content://$AUTHORITY/document/first".toUri()
    private val secondCopy = "content://$AUTHORITY/document/second".toUri()

    @Before
    @After
    fun forgetEverything() {
        application.getSharedPreferences(WorkDirectory.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        application.contentResolver.persistedUriPermissions.toList().forEach {
            application.contentResolver.releasePersistableUriPermission(
                it.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    @Test
    fun remember_keepsReadAccessOnly() {
        assertTrue(SavedManual.remember(application, firstCopy))

        val grant = application.contentResolver.persistedUriPermissions.single()
        assertEquals(firstCopy, SavedManual.read(application))
        assertEquals(firstCopy, grant.uri)
        assertTrue(grant.isReadPermission)
        assertFalse(grant.isWritePermission)
    }

    @Test
    fun remember_keepsTheNameThePickedFileHad() {
        documents.names[firstCopy] = "manual.pdf"
        SavedManual.remember(application, firstCopy)
        assertEquals("manual.pdf", SavedManual.name(application))

        // A provider that shows no name still leaves something to show.
        SavedManual.remember(application, secondCopy)
        assertEquals("second", SavedManual.name(application))
    }

    @Test
    fun remember_anotherFile_givesUpThePreviousAccess() {
        SavedManual.remember(application, firstCopy)

        assertTrue(SavedManual.remember(application, secondCopy))

        assertEquals(secondCopy, SavedManual.read(application))
        assertEquals(listOf(secondCopy), application.contentResolver.persistedUriPermissions.map { it.uri })
    }

    @Test
    fun remember_theSameFileAgain_keepsItsAccess() {
        SavedManual.remember(application, firstCopy)

        assertTrue(SavedManual.remember(application, firstCopy))

        assertEquals(listOf(firstCopy), application.contentResolver.persistedUriPermissions.map { it.uri })
    }

    @Test
    fun remember_leavesAWorkDirectoryGrantAlone() {
        val tree = "content://$AUTHORITY/tree/work".toUri()
        application.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        SavedManual.remember(application, firstCopy)

        SavedManual.remember(application, secondCopy)

        assertEquals(
            setOf(tree, secondCopy),
            application.contentResolver.persistedUriPermissions.map { it.uri }.toSet(),
        )
    }

    @Test
    @Config(shadows = [RefusingContentResolverShadow::class])
    fun remember_whenTheProviderRefuses_changesNothing() {
        assertFalse(SavedManual.remember(application, firstCopy))

        assertNull(SavedManual.read(application))
    }

    @Test
    fun displayName_readsTheProvidersName() {
        documents.names[firstCopy] = "C47_R47_calc_Crop (1).pdf"

        assertEquals("C47_R47_calc_Crop (1).pdf", SavedManual.displayName(application, firstCopy))
    }

    @Test
    fun displayName_isNullWhenTheDocumentCannotBeReached() {
        // No row, as MediaStore answers for a deleted file.
        assertNull(SavedManual.displayName(application, firstCopy))
        // No provider, as after the app holding the file is removed.
        assertNull(SavedManual.displayName(application, "content://nobody.here/document/1".toUri()))

        val failures = listOf(SecurityException("revoked"), IllegalArgumentException("bad id"), IllegalStateException())
        for (failure in failures) {
            documents.failure = failure
            assertNull(failure.toString(), SavedManual.displayName(application, firstCopy))
        }
    }

    @Test
    fun viewIntent_grantsThePdfAppReadAccessOnly() {
        val intent = SavedManual.viewIntent(firstCopy)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(firstCopy, intent.data)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0)
    }

    @Test
    fun withNothingSaved_theEntryOffersAPdfPickerAndNoReplacement() {
        val settings = openSettings()

        assertEquals(
            application.getString(R.string.about_saved_manual_summary_unset),
            settings.preference("open_saved_manual").summary,
        )
        assertFalse(settings.preference("choose_saved_manual").isVisible)

        settings.tap("open_saved_manual")

        val picker = shadowOf(settings.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertEquals(listOf("application/pdf"), picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }

    @Test
    fun aPickedFile_isRememberedShownAndOpened() {
        documents.names[firstCopy] = "manual.pdf"
        val settings = openSettings()
        settings.tap("open_saved_manual")

        settings.answerPicker(firstCopy)

        assertEquals(firstCopy, SavedManual.read(application))
        assertEquals("manual.pdf", settings.preference("open_saved_manual").summary)
        assertTrue(settings.preference("choose_saved_manual").isVisible)
        assertViewedInPdfApp(firstCopy)
    }

    @Test
    fun aDismissedPicker_changesNothing() {
        SavedManual.remember(application, firstCopy)
        val settings = openSettings()
        settings.tap("choose_saved_manual")

        settings.answerPicker(null)

        assertEquals(firstCopy, SavedManual.read(application))
        assertEquals(listOf(Intent.ACTION_OPEN_DOCUMENT), startedIntents().map { it.action })
    }

    @Test
    fun aSavedFile_opensInOneTapWithNoPicker_inANewSettingsScreen() {
        documents.names[firstCopy] = "manual.pdf"
        SavedManual.remember(application, firstCopy)
        val settings = openSettings()

        settings.tap("open_saved_manual")

        val opened = startedIntents().single()
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(firstCopy, opened.data)
    }

    @Test
    fun aMissingFile_saysSoOffersThePickerAndStaysRememberedUntilReplaced() {
        documents.names[firstCopy] = "manual.pdf"
        SavedManual.remember(application, firstCopy)
        documents.names.remove(firstCopy)
        val settings = openSettings()

        settings.tap("open_saved_manual")

        assertEquals(
            application.getString(R.string.about_saved_manual_missing),
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals(listOf(Intent.ACTION_OPEN_DOCUMENT), startedIntents().map { it.action })
        assertEquals(firstCopy, SavedManual.read(application))

        settings.answerPicker(secondCopy)

        assertEquals(secondCopy, SavedManual.read(application))
        assertEquals(listOf(secondCopy), application.contentResolver.persistedUriPermissions.map { it.uri })
    }

    @Test
    fun openingAndReturningToSettings_asksTheProviderNothing() {
        documents.names[firstCopy] = "manual.pdf"
        SavedManual.remember(application, firstCopy)
        documents.queries = 0

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        controller.pause()
        controller.resume()

        val fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
        assertEquals("manual.pdf", requireNotNull(fragment.findPreference<Preference>("open_saved_manual")).summary)
        assertEquals(0, documents.queries)
    }

    @Test
    fun chooseAnother_replacesTheFile_evenWhenTheOldOneStillOpens() {
        documents.names[firstCopy] = "manual.pdf"
        documents.names[secondCopy] = "manual (1).pdf"
        SavedManual.remember(application, firstCopy)
        val settings = openSettings()

        settings.tap("choose_saved_manual")
        settings.answerPicker(secondCopy)

        assertEquals(secondCopy, SavedManual.read(application))
        assertEquals("manual (1).pdf", settings.preference("open_saved_manual").summary)
        assertViewedInPdfApp(secondCopy)
    }

    @Test
    @Config(shadows = [RefusingContentResolverShadow::class])
    fun aFileWithNoLastingAccess_opensOnceAndSaysSo() {
        val settings = openSettings()
        settings.tap("open_saved_manual")

        settings.answerPicker(firstCopy)

        assertEquals(
            application.getString(R.string.about_saved_manual_not_kept),
            ShadowToast.getTextOfLatestToast(),
        )
        assertNull(SavedManual.read(application))
        assertViewedInPdfApp(firstCopy)
    }

    @Test
    fun withNoPdfApp_aDialogNamesTheFileAndOpensNothingUntilAsked() {
        val settings = openSettingsWithABrowserAndNoPdfApp()

        settings.tap("open_saved_manual")

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text.toString()
        assertTrue(message, message.contains("manual.pdf"))
        assertFalse(startedIntents().any { it.dataString == hostedManual() })
    }

    @Test
    fun withNoPdfApp_viewOnlineOpensTheHostedManual() {
        val settings = openSettingsWithABrowserAndNoPdfApp()
        settings.tap("open_saved_manual")

        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(hostedManual(), shadowOf(application).nextStartedActivity.dataString)
    }

    @Test
    fun withNoPdfApp_closeOpensNothing() {
        val settings = openSettingsWithABrowserAndNoPdfApp()
        settings.tap("open_saved_manual")
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
        assertFalse(startedIntents().any { it.dataString == hostedManual() })
    }

    private fun hostedManual() = application.getString(R.string.about_manual_url)

    private fun openSettingsWithABrowserAndNoPdfApp(): SettingsHarness {
        documents.names[firstCopy] = "manual.pdf"
        SavedManual.remember(application, firstCopy)
        val browser = ComponentName("test.browser", "Browser")
        val webLinks = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("https")
        }
        shadowOf(application.packageManager).addActivityIfNotPresent(browser)
        shadowOf(application.packageManager).addIntentFilterForActivity(browser, webLinks)
        shadowOf(application).checkActivities(true)
        return openSettings()
    }

    @Test
    fun withNoPicker_theEntryShowsAToastInsteadOfCrashing() {
        shadowOf(application).checkActivities(true)
        val settings = openSettings()

        for (key in listOf("open_saved_manual", "choose_saved_manual")) {
            ShadowToast.reset()

            settings.tap(key)

            assertEquals(
                key,
                application.getString(R.string.about_saved_manual_no_picker),
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    // Every activity started so far, picker launches included.
    private fun startedIntents(): List<Intent> {
        return generateSequence<Intent> { shadowOf(application).nextStartedActivity }.toList()
    }

    private fun assertViewedInPdfApp(uri: Uri) {
        val intent = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
    }

    private fun openSettings(): SettingsHarness {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        return SettingsHarness(
            activity,
            activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment,
        )
    }

    private class SettingsHarness(val activity: SettingsActivity, val fragment: SettingsFragment) {
        fun preference(key: String): Preference {
            return requireNotNull(fragment.findPreference<Preference>(key)) { key }
        }

        fun tap(key: String) {
            val preference = preference(key)
            requireNotNull(preference.onPreferenceClickListener) { key }.onPreferenceClick(preference)
        }

        fun answerPicker(uri: Uri?) {
            val request = shadowOf(activity).nextStartedActivityForResult.intent
            if (uri == null) {
                shadowOf(activity).receiveResult(request, Activity.RESULT_CANCELED, null)
            } else {
                shadowOf(activity).receiveResult(request, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
    }
}
