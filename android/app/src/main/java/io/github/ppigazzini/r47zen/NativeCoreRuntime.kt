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
    private val releaseNativeRuntime: () -> Unit,
    private val tick: () -> Int,
    private val saveStateNative: () -> Unit,
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
        // joining the core thread, so a factory reset that follows sees the
        // thread stopped. Generous enough for a normal tick to finish; a timeout
        // logs loudly rather than hangs, and the thread finishes on its own.
        private const val DISPOSE_JOIN_FENCE_MILLIS = 1_500L

        // How long a parked core thread waits for a task before it looks at the
        // running flag again. A task, dispose, or onHostStarted wakes it sooner.
        private const val PARKED_WAIT_MILLIS = 60_000L

        private val coreTasks = LinkedBlockingQueue<Runnable>()

        @Volatile
        private var coreThread: Thread? = null

        @Volatile
        private var isCoreThreadStarted = false

        @Volatile
        private var isAppRunningShared = false

        @Volatile
        private var isNativeInitializedShared = false

        // True while no host is visible (between onStop and onStart): the loop
        // then waits for tasks instead of ticking every 5 ms. Upstream's timers
        // are deadlines against the clock, so a tick after the park fires every
        // one that came due during it.
        @Volatile
        private var isHostStopped = false

        // Orders attach() against the core thread's exit, so a host that
        // attaches while the thread is leaving its loop either finds it running
        // or gets a fresh one from it, never neither.
        private val lifecycleLock = Any()

        // The runtime the core thread calls into, republished by every attach().
        // The core thread outlives each Activity, and every runtime's lambdas are
        // bound member references to the Activity that built it, so the thread
        // must reach its host only through this field: a recreated Activity's
        // predecessor then becomes collectable as soon as the successor attaches.
        @Volatile
        private var activeRuntime: NativeCoreRuntime? = null

        // Built in the companion so the thread's Runnable captures no runtime.
        private val coreLoop = Runnable { runCoreLoop() }

        fun isAppRunning(): Boolean = isAppRunningShared

        // The calculator host is invisible; let the core thread stop ticking.
        fun onHostStopped() {
            isHostStopped = true
        }

        // The calculator host is visible again; wake a parked core thread.
        fun onHostStarted() {
            isHostStopped = false
            coreTasks.offer(Runnable {})
        }

        // True once initNative has returned on the core thread; native gates its
        // UI-thread entry points on the same point (r47_runtime_ready).
        fun isNativeInitialized(): Boolean = isNativeInitializedShared

        // True until the core thread has left its loop and drained its queue.
        fun isCoreThreadRunning(): Boolean = isCoreThreadStarted

        internal fun isCoreThreadStartedForTest(): Boolean = isCoreThreadStarted

        internal fun isNativeInitializedForTest(): Boolean = isNativeInitializedShared

        internal fun resetSharedState() {
            coreTasks.clear()
            isCoreThreadStarted = false
            isAppRunningShared = false
            isNativeInitializedShared = false
            isHostStopped = false
            activeRuntime = null
        }

        private fun runCoreLoop() {
            var crashed = false
            try {
                if (!initializeOrReattach()) {
                    return
                }
                while (isAppRunningShared) {
                    if (!runCoreIteration()) {
                        break
                    }
                }
                Log.i(TAG, "Core thread exiting")
                releaseFinishedHost()
                // Flush tasks queued during shutdown (e.g. a pending onPause
                // save) so dispose() cannot drop them.
                drainCoreTasks()
            } catch (error: Throwable) {
                // Any throwable here, an Error included, leaves the native core
                // in a state that cannot be recovered in place. Stop the runtime
                // and surface the failure loudly instead of leaving an
                // interactive UI over a dead core.
                crashed = true
                Log.e(TAG, "Native core thread crashed; stopping the runtime", error)
                isAppRunningShared = false
                throw error
            } finally {
                finishCoreThread(restartIfAttached = !crashed)
            }
        }

        // The core thread is the only reader of the native activity globals,
        // so it is also their only releaser: a release from the main thread can
        // delete a reference this thread is about to call through. It releases
        // only when no host is attached. When dispose's join fence gave up on
        // this thread, activeRuntime is already null and the globals keep the
        // finished host until the next attach replaces them.
        private fun releaseFinishedHost() {
            val finishedHost = synchronized(lifecycleLock) {
                if (isAppRunningShared) null else activeRuntime
            }
            finishedHost?.releaseNativeRuntime()
        }

        private fun finishCoreThread(restartIfAttached: Boolean) {
            synchronized(lifecycleLock) {
                val host = activeRuntime
                if (restartIfAttached && isAppRunningShared && host != null) {
                    // A host attached after this thread left its loop and saw it
                    // still started. Hand it a fresh thread instead of none.
                    Log.i(TAG, "A host attached while the core thread exited; restarting it")
                    host.startCoreThread(coreLoop)
                } else {
                    isCoreThreadStarted = false
                }
            }
        }

        // Each call reads activeRuntime afresh and keeps it only for its own
        // frame, so no long-lived local on the core thread pins an old host.
        private fun initializeOrReattach(): Boolean {
            val runtime = activeRuntime ?: return false
            Log.i(TAG, "Core thread starting; nativeInitialized=$isNativeInitializedShared")
            if (!isNativeInitializedShared) {
                runtime.nativePreInit(runtime.filesDirPath)
                runtime.initNative(runtime.filesDirPath, runtime.currentSlotIdProvider())
                isNativeInitializedShared = true
            } else {
                runtime.updateNativeActivityRef()
            }
            return true
        }

        private fun runCoreIteration(): Boolean {
            drainCoreTasks()
            val runtime = activeRuntime ?: return false
            if (isHostStopped) {
                // A running program never reaches here: it runs inside a task
                // and yields through processCoreTasks. Only the idle loop parks.
                runtime.awaitCoreTask(PARKED_WAIT_MILLIS)?.let { drainCoreTasks(it) }
                return true
            }
            val nextTickDelayMillis = runtime.tick().coerceAtLeast(0).toLong()
            if (nextTickDelayMillis == 0L) {
                return true
            }
            val queuedTask = runtime.awaitCoreTask(nextTickDelayMillis)
            if (queuedTask != null) {
                drainCoreTasks(queuedTask)
            }
            return true
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

        internal fun resetSharedStateForTest() {
            resetSharedState()
        }
    }

    fun attach() {
        synchronized(lifecycleLock) {
            activeRuntime = this
            isAppRunningShared = true
            startOrAttachCoreThread()
        }
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
            if (activeRuntime === this) {
                activeRuntime = null
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
            startCoreThread(coreLoop)
        } else {
            Log.i(TAG, "Core thread already running; updating the activity ref on the core thread")
            // Run the ref swap on the core thread, serialized with the native
            // readers of the activity globals, instead of mutating them from the
            // main thread while the core thread is using them.
            offerTask(Runnable { updateNativeActivityRef() })
        }
    }
}
