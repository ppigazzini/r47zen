package com.example.r47

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
    private val tick: () -> Unit,
    private val saveStateNative: () -> Unit,
    private val forceRefreshNative: () -> Unit,
    private val getPackedDisplayBuffer: (ByteArray) -> Unit,
    private val getKeypadMetaNative: (Boolean) -> IntArray,
    private val useSceneDrivenKeypadProvider: () -> Boolean,
    private val getKeypadSnapshot: (IntArray) -> KeypadSnapshot,
    private val onPackedLcd: (ByteArray) -> Boolean,
    private val onDynamicRefresh: (KeypadSnapshot) -> Unit,
    private val displayRefreshLoop: DisplayRefreshLoop = NativeDisplayRefreshLoop(
        isAppRunning = { isAppRunningShared },
        isNativeInitialized = { isNativeInitializedShared },
        getPackedDisplayBuffer = getPackedDisplayBuffer,
        getKeypadMetaNative = getKeypadMetaNative,
        useSceneDrivenKeypadProvider = useSceneDrivenKeypadProvider,
        getKeypadSnapshot = getKeypadSnapshot,
        onPackedLcd = onPackedLcd,
        onDynamicRefresh = onDynamicRefresh,
    ),
    private val startCoreThread: (Runnable) -> Unit = { runnable ->
        Thread(runnable, "R47CoreRuntime").start()
    },
    private val sleepMillis: (Long) -> Unit = { durationMs -> Thread.sleep(durationMs) },
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
                        tick()
                        sleepMillis(10)
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

    private fun drainCoreTasks() {
        var task = coreTasks.poll()
        while (task != null) {
            try {
                task.run()
            } catch (error: Exception) {
                Log.e(TAG, "Core task failed", error)
            }
            task = coreTasks.poll()
        }
    }
}