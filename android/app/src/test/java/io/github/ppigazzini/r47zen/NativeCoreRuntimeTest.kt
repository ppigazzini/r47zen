package io.github.ppigazzini.r47zen

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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

        runtime.saveStateOnPause(autoSaveEnabled = true, timeoutMillis = 1_000)
        assertEquals(1, saveCalls.get())

        runtime.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }

        runtime.offerTask(Runnable { taskLatch.countDown() })
        assertFalse(taskLatch.await(200, TimeUnit.MILLISECONDS))
        assertFalse(NativeCoreRuntime.isAppRunning())
    }

    @Test
    fun attach_waitsOnNativeDeadlineInsteadOfFixedSleep() {
        val waitObserved = AtomicLong(-1)
        val waitLatch = CountDownLatch(1)
        lateinit var runtime: NativeCoreRuntime

        runtime = createRuntime(
            tickDelayMillis = 37,
            awaitCoreTask = { timeoutMillis ->
                waitObserved.set(timeoutMillis)
                waitLatch.countDown()
                runtime.dispose(stopApp = true)
                null
            },
        )

        runtime.attach()

        assertTrue(waitLatch.await(2, TimeUnit.SECONDS))
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }
        assertEquals(37L, waitObserved.get())
    }

    @Test
    fun reattach_updatesActivityRefOnCoreThread_notCaller() {
        val initLatch = CountDownLatch(1)
        val updateThreadName = AtomicReference<String?>(null)
        val runtime = createRuntime(
            onInit = { initLatch.countDown() },
            onUpdateActivityRef = { updateThreadName.set(Thread.currentThread().name) },
        )

        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))

        runtime.attach()
        waitUntil("reattach update ran", 2_000) { updateThreadName.get() != null }

        // The native activity-ref swap must run on the core thread, serialized
        // with the native readers of those globals, not on the caller thread.
        assertEquals("R47CoreRuntime", updateThreadName.get())

        runtime.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }
    }

    @Test
    fun dispose_flushesQueuedSaveInsteadOfDroppingIt() {
        val initLatch = CountDownLatch(1)
        val tickGate = CountDownLatch(1)
        val tickParked = CountDownLatch(1)
        val saveRan = CountDownLatch(1)
        val parkedOnce = AtomicBoolean(false)
        val runtime = createRuntime(
            onInit = { initLatch.countDown() },
            onSaveState = { saveRan.countDown() },
            tickOverride = {
                // Park the core thread inside tick on the first call so a queued
                // save cannot be drained by the poll loop before dispose runs.
                if (parkedOnce.compareAndSet(false, true)) {
                    tickParked.countDown()
                    tickGate.await()
                }
                10
            },
        )

        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        waitUntil("native init", 2_000) { NativeCoreRuntime.isNativeInitializedForTest() }
        assertTrue(tickParked.await(2, TimeUnit.SECONDS))

        // Queue the save while the core thread is parked in tick; a short fence
        // lets saveStateOnPause return without waiting.
        runtime.saveStateOnPause(autoSaveEnabled = true, timeoutMillis = 50)

        // Dispose on a separate thread (it joins the core thread) so this thread
        // can release the park.
        val disposeThread = Thread { runtime.dispose(stopApp = true) }
        disposeThread.start()
        Thread.sleep(100)
        tickGate.countDown()

        // The queued save must run during shutdown drain, not be dropped.
        assertTrue("queued save must be flushed on shutdown", saveRan.await(2, TimeUnit.SECONDS))
        disposeThread.join(2_000)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }
    }

    @Test
    fun coreThreadCrash_stopsAppInsteadOfSilentZombie() {
        val initLatch = CountDownLatch(1)
        val crashArmed = AtomicBoolean(false)
        val runtime = createRuntime(
            onInit = { initLatch.countDown() },
            tickOverride = {
                if (crashArmed.compareAndSet(false, true)) {
                    throw RuntimeException("simulated native core failure")
                }
                10
            },
        )

        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        waitUntil("core thread stops after crash", 2_000) {
            !NativeCoreRuntime.isCoreThreadStartedForTest()
        }

        // A crashed core must clear the running flag, not leave an interactive
        // UI over a dead core.
        assertFalse("crash must clear the running flag", NativeCoreRuntime.isAppRunning())
    }

    private fun createRuntime(
        onInit: () -> Unit = {},
        onUpdateActivityRef: () -> Unit = {},
        onSaveState: () -> Unit = {},
        tickDelayMillis: Int = 10,
        tickOverride: (() -> Int)? = null,
        awaitCoreTask: ((Long) -> Runnable?)? = null,
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
            tick = tickOverride ?: { tickDelayMillis },
            saveStateNative = onSaveState,
            forceRefreshNative = {},
            getPackedDisplayGeneration = { 0 },
            getPackedDisplayBuffer = { true },
            getKeypadSnapshotGeneration = { 0 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                NativeKeypadSnapshotRefreshResult(
                    observedGeneration = 0,
                    snapshot = null,
                    isUpToDate = true,
                )
            },
            onPackedLcd = { false },
            onDynamicRefresh = {},
            isPerformanceSnapshotEnabled = { true },
            onPerformanceSnapshot = {},
            displayRefreshLoop = object : DisplayRefreshLoop {
                override fun start() {}

                override fun stop() {}
            },
            awaitCoreTask = awaitCoreTask ?: { null },
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
