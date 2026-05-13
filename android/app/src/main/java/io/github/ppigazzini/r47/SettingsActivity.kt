package io.github.ppigazzini.r47

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(EnglishResourceContext.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        findViewById<MaterialToolbar>(R.id.top_app_bar)
            .configureScreenToolbar(
                titleText = getString(R.string.settings_title),
                actionLabel = getString(R.string.settings_return_action),
                onNavigateUp = ::finish,
                onAction = ::finish,
            )

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private data class StorageLocationDetails(
        val sessionSummary: String,
        val workDirectorySummary: String,
    )

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val displayPath = WorkDirectory.persistSelectedTreeUri(requireContext(), uri)
            updateStoragePreferences()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_work_directory_set_title)
                .setMessage(getString(R.string.settings_work_directory_set_message, displayPath))
                .setPositiveButton(R.string.gpl_license_dialog_close, null)
                .show()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = SlotStore.APP_PREFS_NAME
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        updateStoragePreferences()

        findPreference<Preference>("session_restore_storage")?.setOnPreferenceClickListener {
            showSessionStorageDialog()
            true
        }

        findPreference<Preference>("work_directory")?.setOnPreferenceClickListener {
            showWorkDirectoryBrowserDialog()
            true
        }

        findPreference<Preference>("factory_reset")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_factory_reset_confirm_title)
                .setMessage(R.string.settings_factory_reset_confirm_message)
                .setNegativeButton(R.string.settings_cancel_action, null)
                .setPositiveButton(R.string.settings_factory_reset_confirm_action) { _, _ ->
                    startActivity(MainActivity.createFactoryResetIntent(requireContext()))
                    requireActivity().finish()
                }
                .show()
            true
        }

        findPreference<Preference>("view_gpl_license")?.setOnPreferenceClickListener {
            startActivity(
                NoticeAssetActivity.createIntent(
                    requireContext(),
                    getString(R.string.about_gpl_license_title),
                    "COPYING",
                    "text/plain",
                    getString(R.string.about_gpl_license_intro)
                )
            )
            true
        }

        findPreference<Preference>("view_android_source")?.setOnPreferenceClickListener {
            openUrl(getString(R.string.android_source_repository_url))
            true
        }

        findPreference<Preference>("view_repo_notices")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), RepoNoticeIndexActivity::class.java))
            true
        }

        findPreference<Preference>("view_android_library_licenses")?.setOnPreferenceClickListener {
            OssLicensesMenuActivity.setActivityTitle(getString(R.string.about_android_library_licenses_title))
            startActivity(Intent(requireContext(), OssLicensesMenuActivity::class.java))
            true
        }

        findPreference<Preference>("view_third_party_inventory")?.setOnPreferenceClickListener {
            startActivity(
                NoticeAssetActivity.createIntent(
                    requireContext(),
                    getString(R.string.about_spdx_inventory_title),
                    "THIRD-PARTY.spdx.json",
                    "application/json"
                )
            )
            true
        }

        findPreference<Preference>("visit_gitlab")?.setOnPreferenceClickListener {
            openUrl("https://gitlab.com/rpncalculators/c43")
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val surfaceColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface)
        view.setBackgroundColor(surfaceColor)
        listView.setBackgroundColor(surfaceColor)
    }

    override fun onResume() {
        super.onResume()
        updateStoragePreferences()
    }

    private fun showWorkDirectoryBrowserDialog() {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_work_directory_browser_title)
            .setMessage(R.string.settings_work_directory_browser_message)
            .setNeutralButton(R.string.settings_cancel_action, null)
            .setPositiveButton(R.string.settings_work_directory_browser_set_action) { _, _ ->
                treeLauncher.launch(null)
            }
            .setNegativeButton(R.string.settings_work_directory_browser_default_action) { _, _ ->
                WorkDirectory.clearTreeUriString(context)
                updateStoragePreferences()
            }
            .show()
    }

    private fun showSessionStorageDialog() {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_session_storage_title)
            .setMessage(
                getString(
                    R.string.settings_session_storage_dialog_message,
                    context.filesDir.absolutePath,
                )
            )
            .setPositiveButton(R.string.gpl_license_dialog_close, null)
            .show()
    }

    private fun updateStoragePreferences() {
        val context = requireContext()
        val sessionStoragePreference = findPreference<Preference>("session_restore_storage")
        val workDirectoryPreference = findPreference<Preference>("work_directory") ?: return
        val details = resolveStorageLocationDetails(context)

        sessionStoragePreference?.summary = details.sessionSummary
        workDirectoryPreference.summary = details.workDirectorySummary
    }

    private fun resolveStorageLocationDetails(context: android.content.Context): StorageLocationDetails {
        val treeUriString = WorkDirectory.readTreeUriString(context)
        val sessionSummary = getString(R.string.settings_session_storage_summary)

        val workDirectorySummary = when {
            treeUriString == null -> getString(R.string.settings_work_directory_summary_unset)
            WorkDirectory.isAccessible(context.contentResolver, treeUriString) -> {
                val displayPath = try {
                    WorkDirectory.formatDisplayPath(Uri.parse(treeUriString).path)
                } catch (_: Exception) {
                    getString(R.string.settings_work_directory_summary_unknown_folder)
                }
                getString(R.string.settings_work_directory_summary_set, displayPath)
            }
            else -> getString(R.string.settings_work_directory_summary_inaccessible)
        }

        return StorageLocationDetails(
            sessionSummary = sessionSummary,
            workDirectorySummary = workDirectorySummary,
        )
    }
}
