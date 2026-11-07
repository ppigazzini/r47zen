package io.github.ppigazzini.r47zen

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// The real file loop on the real MainActivity: the core asks for a file, the activity
// launches the picker, a stub answers it with a document of RecordingDocumentProvider,
// and DocumentTransfer moves the bytes between that provider and the core, which
// waits in requestAndroidFile until the answer comes. No descriptor is handed in
// through the test bridge. The failure Toast is not observed.
// ActivityMonitor's onStartActivity, which answers the picker, is API 26.
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class DocumentLoopInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pickerIntents = mutableListOf<Intent>()
    private var picker: Instrumentation.ActivityMonitor? = null

    @Before
    fun clearDocuments() {
        RecordingDocumentProvider.clear(context.filesDir)
    }

    @After
    fun removePicker() {
        picker?.let(instrumentation::removeMonitor)
    }

    // A save asks the provider for "wt" and replaces the whole document, a longer one
    // left there included; the loaded state then gives X back, and STATE# the name
    // the provider shows for the document.
    @Test
    fun stateSavedThroughAProviderLoadsBack() {
        val stale = RecordingDocumentProvider.document(context.filesDir, "state")
        stale.parentFile?.mkdirs()
        stale.writeBytes(ByteArray(STALE_BYTES) { STALE_BYTE })

        ActivityScenario.launch(MainActivity::class.java).use {
            startFromReset()
            assertTrue("seed X", ProgramLoadTestBridge.seedXLongInteger("4242"))
            answerPicker(Intent.ACTION_CREATE_DOCUMENT, documentUri("state"))
            runToRest(ITM_SAVEST, "SAVEST")

            val saved = finishedDocument(documentUri("state"))
            assertEquals("every write open asked for", listOf("wt"), writeModes())
            assertTrue("the save left ${saved.size} bytes, the stale tail included", saved.size < STALE_BYTES)
            assertTrue("the save wrote a state file", saved.isNotEmpty() && saved[0] != STALE_BYTE)

            assertTrue("seed X", ProgramLoadTestBridge.seedXLongInteger("0"))
            answerPicker(Intent.ACTION_OPEN_DOCUMENT, documentUri("state"))
            runToRest(ITM_LOADST, "LOADST")
            assertEquals("LOADST restored X", "4242", ProgramLoadTestBridge.getXRegisterClipboardText().trim())
            runToRest(ITM_LOADEDFILE, "STATE#")
            val reported = ProgramLoadTestBridge.getXRegisterClipboardText().trim()
            assertTrue("STATE# reported '$reported'", reported.endsWith(": state"))
            assertEquals("each picker asked once", 2, synchronized(pickerIntents) { pickerIntents.size })
        }
    }

    // requestAndroidFile waits until the activity answers; a document the provider
    // refuses must answer it with a cancel, or the calculator thread waits for ever.
    @Test
    fun aRefusedLoadLeavesTheCalculatorRunning() {
        ActivityScenario.launch(MainActivity::class.java).use {
            startFromReset()
            answerPicker(Intent.ACTION_OPEN_DOCUMENT, documentUri(RecordingDocumentProvider.FAILING_DOCUMENT))
            runToRest(ITM_LOADST, "LOADST of a refused document")
            assertEquals("the picker was asked", 1, synchronized(pickerIntents) { pickerIntents.size })
            assertEquals("the provider was asked to read", listOf("r"), RecordingDocumentProvider.modes())
            assertTrue("seed X after the refusal", ProgramLoadTestBridge.seedXLongInteger("7"))
        }
    }

    private fun startFromReset() {
        assertTrue(
            "Native runtime did not become ready",
            waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
        )
        ProgramLoadTestBridge.resetRuntime()
    }

    // Answer the next picker with uri, as a user picking that document would.
    private fun answerPicker(action: String, uri: Uri) {
        picker?.let(instrumentation::removeMonitor)
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != action) {
                    return null
                }
                synchronized(pickerIntents) { pickerIntents += intent }
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        picker = monitor
    }

    private fun runToRest(function: Int, label: String) {
        assertTrue("$label did not start", ProgramLoadTestBridge.beginSimFunction(function))
        assertTrue(
            "$label did not return within $RUN_TIMEOUT_MS ms",
            waitUntil(RUN_TIMEOUT_MS) { !ProgramLoadTestBridge.isSimFunctionRunning() },
        )
    }

    // DocumentTransfer runs every transfer on one thread in order, so a load queued
    // after a save reads the document that save finished.
    private fun finishedDocument(uri: Uri): ByteArray {
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null
        DocumentTransfer.shared(context).load(
            uri,
            onLoaded = { fd, _ ->
                bytes = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))
                    .use { it.readBytes() }
                latch.countDown()
            },
            onFailed = { latch.countDown() },
        )
        assertTrue("the document did not load back", latch.await(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return checkNotNull(bytes) { "the provider refused to read $uri back" }
    }

    private fun writeModes(): List<String> = RecordingDocumentProvider.modes().filter { 'w' in it }

    private fun documentUri(name: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(RecordingDocumentProvider.authority(context.packageName))
        .appendPath(name)
        .build()

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) {
                return true
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private companion object {
        const val ITM_SAVEST = 2387
        const val ITM_LOADST = 2388
        const val ITM_LOADEDFILE = 1446
        // Longer than the state file the save writes, which the size check holds.
        const val STALE_BYTES = 4 * 1024 * 1024
        const val STALE_BYTE: Byte = 0x7F
        const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        const val RUN_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 25L
    }
}
