package com.example.r47

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeCoreRuntimeTest {
    @Before
    fun setUp() {
        NativeCoreRuntime.resetSharedStateForTest()
    }

    @After
    fun tearDown() {
        NativeCoreRuntime.resetSharedStateForTest()
    }

    @Test
    fun attach_initializesOnce_processesTasks_andRefreshesOnReattach() {
        val initCalls = AtomicInteger(0)
        val updateCalls = AtomicInteger(0)
        val initLatch = CountDownLatch(1)
        val taskLatch = CountDownLatch(1)
        val runtime = createRuntime(
            onInit = {
                initCalls.incrementAndGet()
                initLatch.countDown()
            },
            onUpdateActivityRef = {
                updateCalls.incrementAndGet()
            },
        )

        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))

        runtime.offerTask(Runnable { taskLatch.countDown() })
        assertTrue(taskLatch.await(2, TimeUnit.SECONDS))

        runtime.attach()
        waitUntil("core reattach update", 2_000) { updateCalls.get() == 1 }

        runtime.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }

        assertEquals(1, initCalls.get())
        assertEquals(1, updateCalls.get())
        assertFalse(NativeCoreRuntime.isAppRunning())
    }

    @Test
    fun saveStateOnPause_runsOnCoreThread_andShutdownRejectsNewTasks() {
        val initLatch = CountDownLatch(1)
        val saveCalls = AtomicInteger(0)
        val taskLatch = CountDownLatch(1)
        val runtime = createRuntime(
            onInit = { initLatch.countDown() },
            onSaveState = { saveCalls.incrementAndGet() },
        )

        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        waitUntil("native init", 2_000) { NativeCoreRuntime.isNativeInitializedForTest() }

        runtime.saveStateOnPause(autoSaveEnabled = true, timeoutSeconds = 1)
        assertEquals(1, saveCalls.get())

        runtime.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }

        runtime.offerTask(Runnable { taskLatch.countDown() })
        assertFalse(taskLatch.await(200, TimeUnit.MILLISECONDS))
        assertFalse(NativeCoreRuntime.isAppRunning())
    }

    private fun createRuntime(
        onInit: () -> Unit = {},
        onUpdateActivityRef: () -> Unit = {},
        onSaveState: () -> Unit = {},
    ): NativeCoreRuntime {
        return NativeCoreRuntime(
            filesDirPath = "/tmp/r47-tests",
            currentSlotIdProvider = { 7 },
            nativePreInit = {},
            initNative = { _, slotId ->
                assertEquals(7, slotId)
                onInit()
            },
            updateNativeActivityRef = onUpdateActivityRef,
            tick = {},
            saveStateNative = onSaveState,
            forceRefreshNative = {},
            getPackedDisplayBuffer = {},
            getKeypadMetaNative = { _ -> IntArray(0) },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            getKeypadSnapshot = { KeypadSnapshot.EMPTY },
            onPackedLcd = { false },
            onDynamicRefresh = {},
            displayRefreshLoop = object : DisplayRefreshLoop {
                override fun start() {}

                override fun stop() {}
            },
        )
    }

    private fun waitUntil(label: String, timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }
        assertTrue(label, condition())
    }
}
