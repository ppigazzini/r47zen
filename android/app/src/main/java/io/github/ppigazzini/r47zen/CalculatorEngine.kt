package io.github.ppigazzini.r47zen

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep

/** What the calculator asks of the screen in front of it. */
internal interface CalculatorHost {
    /** The id this Activity keeps across a recreation, which owns a picker it launched. */
    val hostId: String

    /** Opens the picker for a file request, in [start] if found. Arrives on the main thread. */
    fun launchFilePicker(isSave: Boolean, defaultName: String, start: Uri?)

    /** A picker answered with no request waiting. Arrives on the main thread. */
    fun onFileRequestLost()

    /** A message from the core, whole. Arrives on the main thread. */
    fun showWarning(text: String)

    /**
     * What the display shows, for a screen reader, after [CalculatorEngine.describeDisplayNative];
     * [isError] when it is the error the screen shows. Arrives on the main thread.
     */
    fun showDisplayText(isError: Boolean, text: String)

    // The rest arrive on the calculator thread.
    fun onCalculatorOff()

    fun onClipboardText(what: Int, text: String)

    fun setClipboardText(text: String)

    fun onPasteResult(pasted: Boolean)
}

/**
 * The calculator: upstream's main loop on a thread of its own, for the life of the process.
 *
 * Only that thread runs core code, and [runCalculatorNative] is its body. Every other external
 * pushes a key or a request, copies something the calculator thread already published, sets a
 * flag that thread reads (a stop, the host's visibility, autosave), reads whether it is ready, or
 * hands it a picked file, so none of them can touch core state or wait for a computation. It is an
 * object so that it outlives each Activity, which registers as the [CalculatorHost] while it
 * exists.
 */
// One function per JNI binding, and JNI binds them all to this one class.
@Suppress("TooManyFunctions")
internal object CalculatorEngine {
    private const val TAG = "R47Calculator"
    private const val THREAD_NAME = "R47Calculator"

    // The Linux simulator's calculator thread, from GLib's g_thread_new, gets glibc's default
    // stack, which glibc sizes from the stack limit: 8 MiB under the usual ulimit -s of 8192.
    private const val THREAD_STACK_BYTES = 8L * 1024L * 1024L

    // Upper bound on how long the stop of the Activity blocks the main thread for a save. A save
    // the calculator cannot take yet stays queued and runs at its next rest or step-boundary
    // pause, or, once the host has stopped, at its next poll.
    private const val SAVE_FENCE_MILLIS = 750

    // Upper bound on how long a factory reset waits for the thread to end.
    private const val STOP_JOIN_FENCE_MILLIS = 1_500L

    /** sendKey's release: the core has one code for every key's release. */
    const val KEY_RELEASE = 0

    /** Passed where a key code goes, for a touch the platform canceled: the press does nothing. */
    const val KEY_CANCEL = -1

    const val COPY_X = 0
    const val COPY_STACK = 1
    const val COPY_ALL = 2

    init {
        System.loadLibrary("r47zen")
    }

    private val hostSlot = CalculatorHostSlot<CalculatorHost> { it.hostId }

    private val host: CalculatorHost?
        get() = hostSlot.current

    private var thread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    /**
     * One request at a time reaches the one calculator thread, whichever Activity is in front when
     * it answers, so the gate lives as long as the thread does.
     */
    val fileRequestGate: NativeFileRequestGate = NativeFileRequestGate(
        post = { mainHandler.post(it) },
        findStart = { fileType, isSave -> appContext?.let { PickerStart.of(it).find(fileType, isSave) } },
        launch = { isSave, defaultName, start ->
            val current = host
            if (current == null) {
                fileRequestGate.deliverCanceled()
            } else {
                hostSlot.pickerLaunchedBy(current)
                current.launchFilePicker(isSave, defaultName, start)
            }
        },
        native = object : NativeFileAnswers {
            override fun select(fd: Int, name: String?): Boolean = onFileSelectedNative(fd, name)

            override fun cancel() = onFileCanceledNative()
        },
        onLost = { host?.onFileRequestLost() },
    )

    @Synchronized
    fun start(context: Context) {
        if (thread != null) {
            return
        }
        appContext = context.applicationContext
        val filesDirPath = context.filesDir.absolutePath
        thread = Thread(null, { runCalculatorNative(filesDirPath) }, THREAD_NAME, THREAD_STACK_BYTES)
            .also(Thread::start)
    }

    fun attachHost(newHost: CalculatorHost) {
        hostSlot.attach(newHost)
    }

    /**
     * A launched picker whose result has not arrived is canceled when the Activity that
     * opened it finishes: a recreated Activity still receives that result, but a finishing
     * one never does, and the calculator thread would stay parked in requestAndroidFile.
     * The cancel comes first, while the slot still names the picker's owner.
     */
    fun detachHost(oldHost: CalculatorHost, recreating: Boolean) {
        if (!recreating) {
            cancelFileRequestOf(oldHost)
        }
        hostSlot.detach(oldHost, recreating)
    }

    fun hasHost(): Boolean = host != null

    /** A finishing Activity never gets its picker's answer, so its own request ends with it. */
    private fun cancelFileRequestOf(finishing: CalculatorHost) {
        if (hostSlot.ownsPicker(finishing)) {
            fileRequestGate.cancelUnanswered()
        }
    }

    fun isThreadRunning(): Boolean = thread?.isAlive == true

    fun isReady(): Boolean = thread != null && isReadyNative()

    /** Routes one key from the keypad or the keyboard: a press, a release, or [KEY_CANCEL]. */
    fun dispatchKey(keyCode: Int) {
        if (keyCode == KEY_CANCEL) {
            cancelKey()
        } else {
            sendKey(keyCode)
        }
    }

    fun onHostStarted() {
        onHostStoppedNative(false)
    }

    /**
     * The Activity is about to stop. When it was the last window started, the process may die
     * from here on, so the state goes to disk and the clock parks. Otherwise another window,
     * coming back or still beside it, speaks for the calculator: it may have a key down, and it
     * needs the clock.
     */
    fun onHostStopped(stopping: CalculatorHost, saveState: Boolean) {
        if (!hostSlot.stop(stopping)) {
            return
        }
        releaseKeyIfDown()
        if (saveState && isReady() && !saveStateNative(SAVE_FENCE_MILLIS)) {
            Log.w(TAG, "State not saved: busy, it saves at its next poll or rest; refused, at a checkpoint")
        }
        onHostStoppedNative(true)
    }

    /** Ends the thread with no save, for a factory reset. False if it outlived the fence. */
    fun stopForFactoryReset(): Boolean {
        stopCalculatorNative(false)
        try {
            thread?.join(STOP_JOIN_FENCE_MILLIS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Interrupted while joining the calculator thread", error)
        }
        return !isThreadRunning()
    }

    @JvmStatic private external fun runCalculatorNative(storagePath: String)
    @JvmStatic private external fun stopCalculatorNative(save: Boolean)
    @JvmStatic external fun setAutoSaveNative(enabled: Boolean)
    @JvmStatic private external fun isReadyNative(): Boolean
    @JvmStatic private external fun onHostStoppedNative(stopped: Boolean)
    @JvmStatic external fun sendKey(keyCode: Int)
    @JvmStatic external fun cancelKey()
    @JvmStatic external fun releaseKeyIfDown()
    @JvmStatic external fun sendPcKeyNative(keysym: Int, state: Int, press: Boolean)
    @JvmStatic external fun cancelPcKeyNative(keysym: Int)
    @JvmStatic external fun graphGestureNative(dxNorm: Float, dyNorm: Float, scale: Float)
    @JvmStatic external fun resetGraphNative()
    @JvmStatic external fun pasteNumberNative(tokens: String): Boolean
    @JvmStatic private external fun saveStateNative(timeoutMs: Int): Boolean
    @JvmStatic external fun copyRegistersNative(what: Int): Boolean

    // Asks for a read of the display's text, which arrives through onDisplayText once the
    // calculator is at rest. Requests made while one waits are that one.
    @JvmStatic external fun describeDisplayNative()
    @JvmStatic external fun getPackedDisplayGeneration(): Int
    @JvmStatic external fun getPackedDisplayBuffer(buffer: ByteArray): Boolean
    @JvmStatic external fun getKeypadSnapshotGeneration(): Int
    @JvmStatic external fun copyKeypadSnapshotNative(
        mainKeyDynamicMode: Int,
        metaBuffer: IntArray,
        labelsBuffer: Array<String>,
    ): Boolean
    @JvmStatic external fun onFileSelectedNative(fd: Int, name: String?): Boolean
    @JvmStatic external fun onFileCanceledNative()

    @JvmStatic
    @Keep
    fun requestFile(isSave: Boolean, defaultName: String, fileType: Int) {
        fileRequestGate.request(isSave, defaultName, fileType)
    }

    @JvmStatic
    @Keep
    fun playTone(milliHz: Int, durationMs: Int) {
        AudioEngine.playTone(milliHz, durationMs)
    }

    @JvmStatic
    @Keep
    fun clearTones() {
        AudioEngine.clearQueuedTones()
    }

    @JvmStatic
    @Keep
    fun onFileWriteFailed() {
        val context = appContext ?: return
        mainHandler.post {
            Toast.makeText(context, R.string.file_write_failed, Toast.LENGTH_LONG).show()
        }
    }

    @JvmStatic
    @Keep
    fun onCalculatorOff() {
        host?.onCalculatorOff()
    }

    @JvmStatic
    @Keep
    fun onClipboardText(what: Int, text: String) {
        host?.onClipboardText(what, text)
    }

    @JvmStatic
    @Keep
    fun setClipboardText(text: String) {
        host?.setClipboardText(text)
    }

    @JvmStatic
    @Keep
    fun onPasteResult(pasted: Boolean) {
        host?.onPasteResult(pasted)
    }

    @JvmStatic
    @Keep
    fun onDisplayText(isError: Boolean, text: String) {
        mainHandler.post { host?.showDisplayText(isError, text) }
    }

    @JvmStatic
    @Keep
    fun onWarning(text: String) {
        mainHandler.post {
            val current = host
            if (current == null) {
                Log.w(TAG, text)
            } else {
                current.showWarning(text.trim())
            }
        }
    }
}
