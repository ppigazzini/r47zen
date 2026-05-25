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
        Thread(runnable, "R47CoreRuntime").start()
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

        private val coreTasks = LinkedBlockingQueue<Runnable>()

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
            coreTasks.clear()
            coreTasks.offer(Runnable {})
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

    fun saveStateOnPause(autoSaveEnabled: Boolean, timeoutSeconds: Long = 2) {
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
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
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
                } catch (error: Exception) {
                    Log.e(TAG, "Native core thread crashed", error)
                } finally {
                    isCoreThreadStarted = false
                }
            }
            )
        } else {
            Log.i(TAG, "Core thread already running; updating activity ref")
            updateNativeActivityRef()
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
