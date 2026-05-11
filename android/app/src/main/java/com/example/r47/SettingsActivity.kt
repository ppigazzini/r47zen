package com.example.r47

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "Settings"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

class SettingsFragment : PreferenceFragmentCompat() {

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val displayPath = WorkDirectory.persistSelectedTreeUri(requireContext(), uri)
            findPreference<Preference>("work_directory")?.summary = displayPath
            
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Work Directory Set")
                .setMessage("Folder selected: $displayPath\nSubfolders (STATE, PROGRAMS, SAVFILES, SCREENS) will be created automatically.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = SlotStore.APP_PREFS_NAME
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        val currentUriStr = WorkDirectory.readTreeUriString(requireContext())
        if (currentUriStr != null) {
            try {
                val uri = Uri.parse(currentUriStr)
                findPreference<Preference>("work_directory")?.summary = WorkDirectory.formatDisplayPath(uri.path)
            } catch (e: Exception) {
                findPreference<Preference>("work_directory")?.summary = "Select a folder"
            }
        }

        findPreference<Preference>("work_directory")?.setOnPreferenceClickListener {
            treeLauncher.launch(null)
            true
        }

        findPreference<Preference>("factory_reset")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Confirm Reset")
                .setMessage("Wipe all internal app data and relaunch R47?\n\nNote: This will NOT delete any files in your selected Work Directory (STATE, PROGRAMS, SAVFILES, SCREENS).")
                .setPositiveButton("Reset") { _, _ ->
                    startActivity(MainActivity.createFactoryResetIntent(requireContext()))
                    requireActivity().finish()
                }
                .setNegativeButton("Cancel", null)
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
}
