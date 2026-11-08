package io.github.ppigazzini.r47zen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The copy of the manual the user keeps, reopened from Settings in one tap.
 *
 * The browser downloads the file and the system picker grants read access to that
 * one document, so the app needs neither a network nor a storage permission. The
 * user's PDF app displays it: this object holds the grant and the reference, and
 * asks the provider for the file's name and nothing else.
 */
internal object SavedManual {
    const val MIME_TYPE = "application/pdf"

    private const val TAG = "R47SavedManual"

    // Beside the Work Directory: a document grant means nothing on another device,
    // and that file is already left out of backup and deleted by a factory reset.
    private const val PREFS_NAME = WorkDirectory.PREFS_NAME
    private const val KEY_DOCUMENT_URI = "saved_manual_uri"
    private const val KEY_DOCUMENT_NAME = "saved_manual_name"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(context: Context): Uri? {
        return prefs(context).getString(KEY_DOCUMENT_URI, null)?.toUri()
    }

    /** Return the name the copy had when it was picked, so showing it asks no provider. */
    fun name(context: Context): String? {
        return prefs(context).getString(KEY_DOCUMENT_NAME, null)
    }

    /** Keep [uri] in place of the current copy, or return false and change nothing. */
    fun remember(context: Context, uri: Uri): Boolean {
        val contentResolver = context.contentResolver
        val previous = read(context)
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: SecurityException) {
            Log.w(TAG, "The picker gave no lasting access", error)
            return false
        }

        val name = nameToShow(context, uri)
        prefs(context).edit {
            putString(KEY_DOCUMENT_URI, uri.toString())
            putString(KEY_DOCUMENT_NAME, name)
        }
        if (previous != null && previous != uri) {
            try {
                contentResolver.releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                Log.w(TAG, "No lasting access to release for the previous copy")
            }
        }
        return true
    }

    /**
     * Return the name the provider shows for [uri], or null when it cannot reach
     * the document now. That may pass, as with a card taken out, so the caller keeps
     * the reference until the user picks another file. A provider can also answer for
     * a file that is gone, as Downloads does for a raw path: the PDF app then reports
     * it, and Choose Another replaces it.
     */
    // A provider is another app, and Binder carries its failures back as several
    // runtime exception types, depending on the provider and the Android version.
    @Suppress("TooGenericExceptionCaught")
    fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.queryDisplayName(uri)
        } catch (error: RuntimeException) {
            Log.w(TAG, "The saved manual cannot be reached", error)
            null
        }
    }

    /** Return the provider's name for [uri], or the end of its path if it shows none. */
    fun nameToShow(context: Context, uri: Uri): String {
        return displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
    }

    fun viewIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_TYPE)
            // Its own Recents entry, so the user can flip between manual and calculator.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}

/**
 * The two Settings entries for the saved manual.
 *
 * Construct this while [fragment] is being created, because it registers the
 * picker. With no PDF app the file cannot be shown, so a dialog offers
 * [viewOnline].
 */
internal class SavedManualEntries(
    private val fragment: PreferenceFragmentCompat,
    private val viewOnline: () -> Unit,
) {
    // A picker that gives no lasting access still gave this one answer access, so the
    // file opens now even though the app cannot reopen it later.
    private val picker = fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (!SavedManual.remember(fragment.requireContext(), uri)) {
                showToast(R.string.about_saved_manual_not_kept)
            }
            refresh()
            openDocument(uri)
        }
    }

    fun bind() {
        fragment.findPreference<Preference>(KEY_OPEN)?.setOnPreferenceClickListener {
            openSaved()
            true
        }
        fragment.findPreference<Preference>(KEY_CHOOSE)?.setOnPreferenceClickListener {
            choose()
            true
        }
        refresh()
    }

    private fun refresh() {
        val context = fragment.requireContext()
        fragment.findPreference<Preference>(KEY_OPEN)?.summary = SavedManual.name(context)
            ?: context.getString(R.string.about_saved_manual_summary_unset)
        fragment.findPreference<Preference>(KEY_CHOOSE)?.isVisible = SavedManual.read(context) != null
    }

    private fun openSaved() {
        val context = fragment.requireContext()
        val uri = SavedManual.read(context)
        when {
            uri == null -> choose()
            SavedManual.displayName(context, uri) != null -> openDocument(uri)
            else -> {
                showToast(R.string.about_saved_manual_missing)
                choose()
            }
        }
    }

    private fun choose() {
        try {
            picker.launch(arrayOf(SavedManual.MIME_TYPE))
        } catch (_: ActivityNotFoundException) {
            showToast(R.string.about_saved_manual_no_picker)
        }
    }

    private fun openDocument(uri: Uri) {
        try {
            fragment.startActivity(SavedManual.viewIntent(uri))
        } catch (_: ActivityNotFoundException) {
            showNoPdfAppDialog(uri)
        }
    }

    // The user asked for the copy on the phone. The hosted manual needs a network and
    // may be another version, so it opens only if the user chooses it.
    private fun showNoPdfAppDialog(uri: Uri) {
        val context = fragment.requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.about_saved_manual_no_pdf_app_title)
            .setMessage(
                context.getString(
                    R.string.about_saved_manual_no_pdf_app_message,
                    SavedManual.nameToShow(context, uri),
                ),
            )
            .setNegativeButton(R.string.gpl_license_dialog_close, null)
            .setPositiveButton(R.string.about_saved_manual_view_online) { _, _ -> viewOnline() }
            .show()
    }

    private fun showToast(message: Int) {
        Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val KEY_OPEN = "open_saved_manual"
        const val KEY_CHOOSE = "choose_saved_manual"
    }
}
