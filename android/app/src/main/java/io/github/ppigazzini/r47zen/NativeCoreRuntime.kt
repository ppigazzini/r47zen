package io.github.ppigazzini.r47zen

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class NativeCoreRuntime(
    private val filesDirPath: String,
    private val currentSlotIdProvider: () -> Int,
    private val nativePreInit: (String) -> Unit,
    private val initNative: (String, Int) -> Unit,
    private val updateNativeActivityRef: () -> Unit,
    private val tick: () -> Int,
    private val saveStateNative: () -> Unit,
    private val forceRefreshNative: () -> Unit,
    private val getPackedDisplayGeneration: () -> Int,
    private val getPackedDisplayBuffer: (ByteArray) -> Boolean,
    private val getKeypadSnapshotGeneration: () -> Int,
    private val getMainKeyDynamicModeCode: () -> Int,
    private val refreshKeypadSnapshot: (Int) -> NativeKeypadSnapshotRefreshResult,
    private val onPackedLcd: (ByteArray) -> Boolean,
    private val onDynamicRefresh: (KeypadSnapshot) -> Unit,
    private val isPerformanceSnapshotEnabled: () -> Boolean = { true },
    private val getPerformanceWindowMillis: () -> Long = { NativeDisplayRefreshLoop.DEFAULT_PERFORMANCE_WINDOW_MILLIS },
    private val onPerformanceSnapshot: (DeveloperPerformanceSnapshot) -> Unit = {},
    private val displayRefreshLoop: DisplayRefreshLoop = NativeDisplayRefreshLoop(
        isAppRunning = { isAppRunningShared },
        isNativeInitialized = { isNativeInitializedShared },
        isPerformanceSnapshotEnabled = isPerformanceSnapshotEnabled,
        getPerformanceWindowMillis = getPerformanceWindowMillis,
        getPackedDisplayGeneration = getPackedDisplayGeneration,
        getPackedDisplayBuffer = getPackedDisplayBuffer,
        getKeypadSnapshotGeneration = getKeypadSnapshotGeneration,
        getMainKeyDynamicModeCode = getMainKeyDynamicModeCode,
        refreshKeypadSnapshot = refreshKeypadSnapshot,
        onPackedLcd = onPackedLcd,
        onDynamicRefresh = onDynamicRefresh,
        onPerformanceSnapshot = onPerformanceSnapshot,
    ),
    private val startCoreThread: (Runnable) -> Unit = { runnable ->
        Thread(runnable, CORE_THREAD_NAME).also { coreThread = it }.start()
    },
    private val joinCoreThread: (Long) -> Unit = { timeoutMillis ->
        val thread = coreThread
        // Never join from the core thread itself: a dispose() issued on the core
        // thread (see the awaitCoreTask deadline test) would otherwise deadlock,
        // and that thread is already unwinding its own loop.
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(timeoutMillis)
        }
    },
    private val awaitCoreTask: (Long) -> Runnable? = { timeoutMillis ->
        try {
            coreTasks.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    },
) {
    companion object {
        private const val TAG = "R47CoreRuntime"
        private const val CORE_THREAD_NAME = "R47CoreRuntime"

        // Upper bound on how long onPause blocks the main thread waiting for the
        // background state save to finish on the core thread. The save still
        // completes on the core thread past this fence; the bound only caps
        // main-thread jank during backgrounding.
        private const val SAVE_ON_PAUSE_FENCE_MILLIS = 750L

        // Upper bound on how long dispose(stopApp=true) blocks the main thread
        // joining the core thread. The join guarantees the core thread has
        // stopped reading the native activity globals before onDestroy releases
        // them, and before a factory reset resets shared state. Generous enough
        // for a normal tick to finish; a timeout logs loudly rather than hangs.
        private const val DISPOSE_JOIN_FENCE_MILLIS = 1_500L

        private val coreTasks = LinkedBlockingQueue<Runnable>()

        @Volatile
        private var coreThread: Thread? = null

        @Volatile
        private var isCoreThreadStarted = false

        @Volatile
        private var isAppRunningShared = false

        @Volatile
        private var isNativeInitializedShared = false

        fun isAppRunning(): Boolean = isAppRunningShared

        internal fun isCoreThreadStartedForTest(): Boolean = isCoreThreadStarted

        internal fun isNativeInitializedForTest(): Boolean = isNativeInitializedShared

        internal fun resetSharedState() {
            coreTasks.clear()
            isCoreThreadStarted = false
            isAppRunningShared = false
            isNativeInitializedShared = false
        }

        internal fun resetSharedStateForTest() {
            resetSharedState()
        }
    }

    fun attach() {
        isAppRunningShared = true
        startOrAttachCoreThread()
        displayRefreshLoop.start()
    }

    fun dispose(stopApp: Boolean) {
        displayRefreshLoop.stop()
        if (stopApp) {
            isAppRunningShared = false
            // Wake the core thread so it observes the stop. Do NOT clear the
            // queue: a pending onPause save must still run. The thread drains
            // any remaining tasks before exiting.
            coreTasks.offer(Runnable {})
            try {
                joinCoreThread(DISPOSE_JOIN_FENCE_MILLIS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e(TAG, "Interrupted while joining the core thread on dispose", error)
            }
            if (isCoreThreadStarted) {
                Log.w(TAG, "Core thread still running after the dispose join fence")
            }
        }
    }

    fun offerTask(task: Runnable) {
        if (isAppRunningShared) {
            coreTasks.offer(task)
        }
    }

    fun processCoreTasks() {
        drainCoreTasks()
    }

    fun requestForceRefresh() {
        if (isNativeInitializedShared) {
            offerTask(Runnable { forceRefreshNative() })
        }
    }

    fun saveStateOnPause(autoSaveEnabled: Boolean, timeoutMillis: Long = SAVE_ON_PAUSE_FENCE_MILLIS) {
        if (!autoSaveEnabled || !isNativeInitializedShared) {
            return
        }

        val latch = CountDownLatch(1)
        offerTask(
            Runnable {
                try {
                    saveStateNative()
                } finally {
                    latch.countDown()
                }
            }
        )

        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for state save on pause")
            }
        } catch (error: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for state save", error)
        }
    }

    private fun startOrAttachCoreThread() {
        if (!isCoreThreadStarted) {
            isCoreThreadStarted = true
            startCoreThread(
                Runnable {
                try {
                    Log.i(TAG, "Core thread starting; nativeInitialized=$isNativeInitializedShared")
                    if (!isNativeInitializedShared) {
                        nativePreInit(filesDirPath)
                        initNative(filesDirPath, currentSlotIdProvider())
                        isNativeInitializedShared = true
                    } else {
                        updateNativeActivityRef()
                    }

                    var lastTickLog = 0L
                    while (isAppRunningShared) {
                        val now = System.currentTimeMillis()
                        if (now - lastTickLog > 5000) {
                            Log.i(TAG, "Core thread heartbeat")
                            lastTickLog = now
                        }

                        drainCoreTasks()
                        val nextTickDelayMillis = tick().coerceAtLeast(0).toLong()
                        if (nextTickDelayMillis == 0L) {
                            continue
                        }

                        val queuedTask = awaitCoreTask(nextTickDelayMillis)
                        if (queuedTask != null) {
                            drainCoreTasks(queuedTask)
                        }
                    }
                    Log.i(TAG, "Core thread exiting")
                    // Flush tasks queued during shutdown (e.g. a pending onPause
                    // save) so dispose() cannot drop them.
                    drainCoreTasks()
                } catch (error: Exception) {
                    // A native-core exception means corrupted state that cannot
                    // be safely recovered in place. Stop the runtime and surface
                    // the failure loudly instead of leaving an interactive UI
                    // over a dead core.
                    Log.e(TAG, "Native core thread crashed; stopping the runtime", error)
                    isAppRunningShared = false
                    throw error
                } finally {
                    isCoreThreadStarted = false
                }
            }
            )
        } else {
            Log.i(TAG, "Core thread already running; updating activity ref on the core thread")
            // Run the ref swap on the core thread, serialized with the native
            // readers of the activity globals, instead of mutating them from the
            // main thread while the core thread is using them.
            offerTask(Runnable { updateNativeActivityRef() })
        }
    }

    private fun drainCoreTasks(initialTask: Runnable? = coreTasks.poll()) {
        var task = initialTask
        while (task != null) {
            runCoreTask(task)
            task = coreTasks.poll()
        }
    }

    private fun runCoreTask(task: Runnable) {
        try {
            task.run()
        } catch (error: Exception) {
            Log.e(TAG, "Core task failed", error)
        }
    }
}
