package io.github.ppigazzini.r47zen

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** A folder a file can be written into by name. */
internal interface DocumentFolder {
    /** Replace the document named [name] with the bytes of [source], making it if missing. */
    fun write(name: String, source: File)
}

/**
 * Copy the files the core writes by a relative name to the work directory, where the
 * user can reach them: prints to its DATA folder and screen captures to SCREENS, as the
 * firmware files them.
 *
 * Upstream's prints append a line at a time to a file named relative to the working
 * directory, which `hal/io.c` makes the private [privateDir], often from a running
 * program; a SNAP with no name armed writes its capture there too. A provider call per
 * line could block that program, and a picker would stop it, so when the app stops this
 * copies each file the core changed since its last copy, whole, on a worker. A copy
 * caught by an append is whole again at the next stop, since the append changes the
 * file's stamp.
 */
internal class DataFolderMirror(
    private val privateDir: File,
    private val openFolder: (fileType: Int) -> DocumentFolder?,
    private val copies: SharedPreferences,
    private val worker: Executor,
    private val reportNoFolder: () -> Unit,
) {
    fun mirror() {
        worker.execute(::mirrorNow)
    }

    internal fun mirrorNow() {
        val changed = privateDir.listFiles().orEmpty()
            .filter { it.isFile && copies.getString(COPIED + it.name, null) != stampOf(it) }
        if (changed.isEmpty()) {
            return
        }
        val waiting = mutableListOf<File>()
        changed.groupBy(::fileTypeOf).forEach { (fileType, files) ->
            val folder = runCatching { openFolder(fileType) }
                .onFailure { error -> Log.w(TAG, "The work directory cannot be reached", error) }
                .getOrElse { return@forEach }
            if (folder == null) {
                waiting += files
            } else {
                files.forEach { file -> copy(file, folder) }
            }
        }
        if (waiting.isNotEmpty()) {
            noticeNoFolder(waiting)
        }
    }

    private fun copy(file: File, folder: DocumentFolder) {
        val stamp = stampOf(file)
        runCatching { folder.write(file.name, file) }
            .onSuccess { copies.edit { putString(COPIED + file.name, stamp) } }
            .onFailure { error -> Log.w(TAG, "${file.name} was not copied", error) }
    }

    private fun fileTypeOf(file: File): Int {
        return if (file.name.endsWith(".bmp", ignoreCase = true)) {
            WorkDirectory.FILE_TYPE_SCREENS
        } else {
            WorkDirectory.FILE_TYPE_DATA
        }
    }

    // Say so once for each new print, not at every stop while the user has no work
    // directory: the files stay, and the first stop with one copies them.
    private fun noticeNoFolder(changed: List<File>) {
        val newest = changed.maxOf { it.lastModified() }
        if (newest > copies.getLong(KEY_NOTICED, 0L)) {
            copies.edit { putLong(KEY_NOTICED, newest) }
            reportNoFolder()
        }
    }

    private fun stampOf(file: File) = "${file.lastModified()}:${file.length()}"

    companion object {
        private const val TAG = "R47DataFolderMirror"
        private const val PREFS_NAME = "R47DataFolderMirror"

        // The stamp a file had when it was last copied, by its name.
        private const val COPIED = "copied:"

        // The newest change the user was told about while no folder took it.
        private const val KEY_NOTICED = "noticed"

        @Volatile
        private var shared: DataFolderMirror? = null

        /** Return the process-wide instance, whose one worker copies in order. */
        fun shared(context: Context): DataFolderMirror {
            return shared ?: synchronized(this) {
                shared ?: create(context.applicationContext).also { shared = it }
            }
        }

        private fun create(context: Context): DataFolderMirror {
            val mainHandler = Handler(Looper.getMainLooper())
            return DataFolderMirror(
                privateDir = File(context.filesDir, "DATA"),
                openFolder = { fileType ->
                    TreeDocumentFolder.open(context.contentResolver, WorkDirectory.readTreeUriString(context), fileType)
                },
                copies = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                worker = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "R47DataFolderMirror").apply { isDaemon = true }
                },
                reportNoFolder = {
                    mainHandler.post {
                        Toast.makeText(context, R.string.print_files_need_work_directory, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }
}

/**
 * A folder of the work directory, written through the persisted tree grant. A new
 * document is made as `application/octet-stream`: Android's file providers keep the
 * name given for it, where another type gains an extension that matches the type.
 */
internal class TreeDocumentFolder(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val folderUri: Uri,
) : DocumentFolder {
    private val existing: Map<String, Uri> by lazy(::listChildren)

    override fun write(name: String, source: File) {
        val uri = existing[name]
            ?: DocumentsContract.createDocument(resolver, folderUri, OCTET_STREAM, name)
            ?: throw FileNotFoundException("$name cannot be made in $folderUri")
        // "wt" empties the document first: a provider that reads "w" literally keeps
        // the tail of a longer one.
        (resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("no output stream for $uri"))
            .use { output -> source.inputStream().use { input -> input.copyTo(output) } }
    }

    private fun listChildren(): Map<String, Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getDocumentId(folderUri),
        )
        val found = mutableMapOf<String, Uri>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                found[cursor.getString(0)] = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(1))
            }
        }
        return found
    }

    companion object {
        private const val OCTET_STREAM = "application/octet-stream"

        /**
         * Return the work directory's folder for [fileType], made if missing, or null
         * when there is no work directory the app can write, or no such folder in it.
         */
        fun open(resolver: ContentResolver, treeUriString: String?, fileType: Int): DocumentFolder? {
            val treeUri = treeUriString?.takeIf { WorkDirectory.isAccessible(resolver, it) }?.toUri()
            return treeUri?.let { tree ->
                WorkDirectory.resolveSubfolder(resolver, treeUriString, fileType)
                    ?.let { folder -> TreeDocumentFolder(resolver, tree, folder) }
            }
        }
    }
}
