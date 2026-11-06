package io.github.ppigazzini.r47zen

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Move a document between a provider and native file I/O on one worker thread.
 *
 * Native never holds a provider's descriptor. A provider may answer with a pipe,
 * which READP cannot seek, or need a download first, which must not run on the
 * main thread; and a descriptor detached from its ParcelFileDescriptor cuts the
 * close signal a provider acts on, such as the media scan or an upload. So a load
 * copies the document to a private file and hands native that file, and a save
 * hands native a pipe and writes the provider once the pipe ends.
 *
 * One thread runs every transfer in order, so a load picked right after a save
 * reads the finished document. Native closing the pipe ends the save's first step,
 * and `hal/io.c` closes any open file before it requests the next one, so a save
 * never blocks the transfer behind it for longer than its own upload.
 */
internal class DocumentTransfer(
    private val openInput: (Uri) -> InputStream?,
    private val openOutput: (Uri) -> OutputStream?,
    private val tempDir: File,
    private val worker: Executor,
    private val reportFailure: (messageId: Int) -> Unit,
    private val openPrivateFile: (File) -> Int = { file ->
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
    },
) {
    /** Return the descriptor native writes the document to. Throw if no pipe opens. */
    fun openForSave(uri: Uri): Int {
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        worker.execute { saveFromPipe(readEnd, uri) }
        return writeEnd.detachFd()
    }

    /** Call [onLoaded] with a seekable descriptor of the document, or [onFailed]. */
    fun load(uri: Uri, onLoaded: (Int) -> Unit, onFailed: () -> Unit) {
        worker.execute {
            runCatching { readIntoPrivateFile(uri) }
                .onSuccess(onLoaded)
                .onFailure { error ->
                    Log.e(TAG, "Failed to read the selected document", error)
                    reportFailure(R.string.file_read_failed)
                    onFailed()
                }
        }
    }

    private fun saveFromPipe(readEnd: ParcelFileDescriptor, uri: Uri) {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input -> writeDocument(input, uri) }
        }.onFailure { error ->
            Log.e(TAG, "Failed to write the selected document", error)
            reportFailure(R.string.file_write_failed)
        }
    }

    // Drain the pipe before touching the provider: a slow provider must not stall the
    // core thread in fwrite, and "wt" empties the document, so it runs only once all
    // the data exists. An empty pipe means native wrote nothing, which leaves the
    // document as it is.
    internal fun writeDocument(input: InputStream, uri: Uri) {
        val temp = createTempFile("save")
        try {
            temp.outputStream().use { output -> input.copyTo(output) }
            if (temp.length() > 0) {
                (openOutput(uri) ?: throw FileNotFoundException("no output stream for $uri")).use { output ->
                    temp.inputStream().use { saved -> saved.copyTo(output) }
                }
            }
        } finally {
            temp.delete()
        }
    }

    // The descriptor outlives the unlink, so nothing is left to clean up after it.
    private fun readIntoPrivateFile(uri: Uri): Int {
        val temp = createTempFile("load")
        try {
            (openInput(uri) ?: throw FileNotFoundException("no input stream for $uri")).use { input ->
                temp.outputStream().use { output -> copyWithinLimit(input, output) }
            }
            return openPrivateFile(temp)
        } finally {
            temp.delete()
        }
    }

    // Android may clear the cache partition at any time, and the directory with it.
    private fun createTempFile(prefix: String): File {
        tempDir.mkdirs()
        return File.createTempFile(prefix, null, tempDir)
    }

    // The picker offers every file, and a wrong tap on a large one must not fill the
    // cache partition.
    private fun copyWithinLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                return
            }
            total += count
            if (total > MAX_LOAD_BYTES) {
                throw IOException("the document is larger than $MAX_LOAD_BYTES bytes")
            }
            output.write(buffer, 0, count)
        }
    }

    companion object {
        private const val TAG = "R47DocumentTransfer"
        private const val TEMP_DIR_NAME = "document-transfer"

        // Far above any calculator file: a state file holds one calculator's memory.
        internal const val MAX_LOAD_BYTES = 64L * 1024 * 1024

        @Volatile
        private var shared: DocumentTransfer? = null

        /**
         * Return the process-wide instance. It outlives an Activity, because a
         * transfer must survive a recreation, and building it clears what a killed
         * process left behind: no transfer can be running yet.
         */
        fun shared(context: Context): DocumentTransfer {
            return shared ?: synchronized(this) {
                shared ?: create(context.applicationContext).also { shared = it }
            }
        }

        private fun create(context: Context): DocumentTransfer {
            val tempDir = File(context.cacheDir, TEMP_DIR_NAME).apply {
                deleteRecursively()
                mkdirs()
            }
            val mainHandler = Handler(Looper.getMainLooper())
            return DocumentTransfer(
                openInput = context.contentResolver::openInputStream,
                openOutput = { uri -> context.contentResolver.openOutputStream(uri, "wt") },
                tempDir = tempDir,
                worker = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "R47DocumentTransfer").apply { isDaemon = true }
                },
                reportFailure = { messageId ->
                    mainHandler.post {
                        Toast.makeText(context, messageId, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }
}
