package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.IOException

class RepoNoticeIndexActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(EnglishResourceContext.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBarInsets.drawEdgeToEdge(this)
        setContentView(R.layout.activity_repo_notice_index)
        SystemBarInsets.padContentToSafeArea(this)

        findViewById<MaterialToolbar>(R.id.top_app_bar)
            .configureScreenToolbar(
                titleText = intent.getStringExtra(EXTRA_TITLE)
                    ?: getString(R.string.repo_notice_catalog_title),
                onNavigateUp = ::finish,
            )

        val entries = try {
            loadEntries(intent.getStringExtra(EXTRA_INDEX_PATH) ?: REPO_NOTICES_INDEX)
        } catch (e: IOException) {
            showMissingAndFinish(getString(R.string.repo_notice_catalog_missing_message))
            return
        }

        val noticeList = findViewById<ListView>(R.id.notice_list)
        noticeList.adapter = object : ArrayAdapter<RepoNoticeEntry>(
            this,
            R.layout.item_notice_entry,
            R.id.notice_entry_title,
            entries
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val entry = getItem(position) ?: return view
                view.findViewById<TextView>(R.id.notice_entry_title).text = entry.title
                view.findViewById<TextView>(R.id.notice_entry_summary).apply {
                    text = entry.summary
                    visibility = if (entry.summary.isBlank()) View.GONE else View.VISIBLE
                }
                return view
            }
        }

        noticeList.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            startActivity(
                NoticeAssetActivity.createIntent(
                    this,
                    entry.title,
                    entry.assetPath,
                    entry.contentType
                )
            )
        }
    }

    private fun loadEntries(indexPath: String): List<RepoNoticeEntry> {
        val rawText = assets.open(indexPath).bufferedReader().use { it.readText() }
        val jsonObject = JSONObject(rawText)
        val entriesArray = jsonObject.getJSONArray("entries")
        val entries = mutableListOf<RepoNoticeEntry>()

        for (index in 0 until entriesArray.length()) {
            val entry = entriesArray.getJSONObject(index)
            entries += RepoNoticeEntry(
                title = entry.optString("title"),
                summary = entry.optString("summary"),
                assetPath = entry.optString("assetPath"),
                contentType = entry.optString("contentType", "text/plain")
            )
        }

        return entries
    }

    private fun showMissingAndFinish(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.repo_notice_catalog_title)
            .setMessage(message)
            .setPositiveButton(R.string.gpl_license_dialog_close) { _, _ ->
                finish()
            }
            .setOnDismissListener {
                finish()
            }
            .show()
    }

    private data class RepoNoticeEntry(
        val title: String,
        val summary: String,
        val assetPath: String,
        val contentType: String
    )

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_INDEX_PATH = "index_path"

        // Written by the generateRepoNoticeAssets and
        // generate<Variant>LibraryLicenseAssets tasks in app/build.gradle.
        const val REPO_NOTICES_INDEX = "repo-notices/NOTICE-INDEX.json"
        const val LIBRARY_LICENSES_INDEX = "library-licenses/NOTICE-INDEX.json"

        fun createIntent(context: Context, title: String, indexPath: String): Intent =
            Intent(context, RepoNoticeIndexActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_INDEX_PATH, indexPath)
            }
    }
}
