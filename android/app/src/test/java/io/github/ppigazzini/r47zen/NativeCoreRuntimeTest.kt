package io.github.ppigazzini.r47zen

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class NativeCoreRuntimeTest {
    private companion object {
        const val CORE_THREAD_NAME = "R47CoreRuntime"
    }

    @Before
    fun setUp() {
        NativeCoreRuntime.resetSharedStateForTest()
    }

    @After
    fun tearDown() {
        NativeCoreRuntime.resetSharedStateForTest()
    }

    @Test
    fun attach_initializesOnce_processesTasks_andUpdatesTheActivityRefOnReattach() {
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
        // initNative binds the first host itself.
        assertEquals(0, updateCalls.get())

        runtime.attach()
        waitUntil("core reattach activity ref", 2_000) { updateCalls.get() == 1 }

        runtime.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }

        assertEquals(1, initCalls.get())
        assertEquals(1, updateCalls.get())
        assertFalse(NativeCoreRuntime.isAppRunning())
    }

    @Test
    fun reattach_afterCoreThreadRestart_updatesTheActivityRefInsteadOfInit() {
        val initLatch = CountDownLatch(1)
        val first = createRuntime(onInit = { initLatch.countDown() })
        first.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        first.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }

        // The native runtime outlives the stopped thread, so the restarted thread
        // rebinds the new host instead of initializing it.
        val calls = CopyOnWriteArrayList<String>()
        val second = createRuntime(
            onInit = { calls += "init" },
            onUpdateActivityRef = { calls += "activityRef" },
        )
        second.attach()
        waitUntil("restart rebind", 2_000) { calls.isNotEmpty() }
        // A task round trip after the rebind, so a late init would be seen.
        val drained = CountDownLatch(1)
        second.offerTask(Runnable { drained.countDown() })
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("activityRef"), calls.toList())

        second.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }
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

    @Test
    fun reattach_releasesThePreviousHost_soARecreatedActivityIsCollectable() {
        val firstHost = attachThenDetachFirstRuntime()
        val updateLatch = CountDownLatch(1)
        val second = createRuntime(onUpdateActivityRef = { updateLatch.countDown() })

        second.attach()
        assertTrue(updateLatch.await(2, TimeUnit.SECONDS))

        // The core thread outlives every Activity. Once the successor attaches, it
        // must hold nothing that reaches the predecessor, or each recreation leaks
        // one Activity with its view tree for the life of the process.
        waitUntil("the first runtime's host is collected", 5_000) {
            System.gc()
            firstHost.get() == null
        }

        second.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }
    }

    // A separate frame, so no local in the test method keeps the first host alive.
    private fun attachThenDetachFirstRuntime(): WeakReference<Any> {
        val host = Any()
        val initLatch = CountDownLatch(1)
        val runtime = createRuntime(onInit = { initLatch.countDown() }, host = host)
        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        runtime.dispose(stopApp = false)
        return WeakReference(host)
    }

    @Test
    fun dispose_releasesTheActivityGlobals_onTheCoreThread() {
        val initLatch = CountDownLatch(1)
        val releaseThreads = CopyOnWriteArrayList<String>()
        val runtime = createRuntime(
            onInit = { initLatch.countDown() },
            onRelease = { releaseThreads += Thread.currentThread().name },
        )
        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))

        runtime.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }

        assertEquals(listOf(CORE_THREAD_NAME), releaseThreads.toList())
    }

    @Test
    fun dispose_pastTheJoinFence_neverReleasesFromTheCallingThread() {
        val initLatch = CountDownLatch(1)
        val taskEntered = CountDownLatch(1)
        val taskGate = CountDownLatch(1)
        val releaseThreads = CopyOnWriteArrayList<String>()
        val runtime = createRuntime(
            onInit = { initLatch.countDown() },
            onRelease = { releaseThreads += Thread.currentThread().name },
            // Gives up at once, as the production fence does on a long program.
            joinCoreThread = {},
        )
        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        runtime.offerTask(
            Runnable {
                taskEntered.countDown()
                taskGate.await(2, TimeUnit.SECONDS)
            },
        )
        assertTrue(taskEntered.await(2, TimeUnit.SECONDS))

        runtime.dispose(stopApp = true)
        assertTrue("the core thread is still busy", NativeCoreRuntime.isCoreThreadStartedForTest())
        taskGate.countDown()
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }

        // dispose cleared the host before the thread left its loop, so nothing
        // released the globals: the next attach replaces them on the core thread.
        assertEquals(emptyList<String>(), releaseThreads.toList())
    }

    @Test
    fun attach_whileTheCoreThreadExits_restartsTheCoreThreadForTheNewHost() {
        val initLatch = CountDownLatch(1)
        val releaseEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val joinGate = CountDownLatch(1)
        val first = createRuntime(
            onInit = { initLatch.countDown() },
            onRelease = {
                releaseEntered.countDown()
                releaseGate.await(2, TimeUnit.SECONDS)
            },
            joinCoreThread = { joinGate.await(2, TimeUnit.SECONDS) },
        )
        first.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))

        // dispose waits in its join, so the core thread, past its loop, finds
        // the first host and stops inside the release: the exit window a new
        // host can attach into.
        val disposer = Thread { first.dispose(stopApp = true) }.apply { start() }
        assertTrue(releaseEntered.await(2, TimeUnit.SECONDS))

        val secondTicks = AtomicInteger(0)
        val secondRefUpdates = AtomicInteger(0)
        val second = createRuntime(
            onUpdateActivityRef = { secondRefUpdates.incrementAndGet() },
            tickOverride = {
                secondTicks.incrementAndGet()
                10
            },
        )
        second.attach()
        releaseGate.countDown()
        joinGate.countDown()
        disposer.join(2_000)

        waitUntil("the new host's core thread ticks", 2_000) { secondTicks.get() > 0 }
        assertTrue(NativeCoreRuntime.isCoreThreadStartedForTest())
        assertTrue("the new host's activity ref is set", secondRefUpdates.get() >= 1)

        second.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }
    }

    @Test
    fun hostStopped_runsTasksWithoutTicking_andHostStartedResumesTicking() {
        val initLatch = CountDownLatch(1)
        val ticks = AtomicInteger(0)
        val runtime = createRuntime(
            onInit = { initLatch.countDown() },
            tickOverride = {
                ticks.incrementAndGet()
                5
            },
        )
        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        waitUntil("ticks before the park", 2_000) { ticks.get() > 0 }

        NativeCoreRuntime.onHostStopped()
        // The iteration that runs this task checks the park after it, so no
        // tick follows; a tick already in flight finished before it.
        roundTrip(runtime)
        val parkedTicks = ticks.get()
        roundTrip(runtime)
        roundTrip(runtime)
        assertEquals("a parked core thread runs tasks but never ticks", parkedTicks, ticks.get())

        NativeCoreRuntime.onHostStarted()
        waitUntil("ticks after the park", 2_000) { ticks.get() > parkedTicks }

        runtime.dispose(stopApp = true)
        waitUntil("core thread stop", 2_000) { !NativeCoreRuntime.isCoreThreadStartedForTest() }
    }

    @Test
    fun coreThreadError_stopsTheRuntimeLikeAnException() {
        val initLatch = CountDownLatch(1)
        val errorArmed = AtomicBoolean(false)
        val runtime = createRuntime(
            onInit = { initLatch.countDown() },
            tickOverride = {
                if (errorArmed.compareAndSet(false, true)) {
                    throw StackOverflowError("simulated native core stack overflow")
                }
                10
            },
        )

        runtime.attach()
        assertTrue(initLatch.await(2, TimeUnit.SECONDS))
        waitUntil("core thread stops after the error", 2_000) {
            !NativeCoreRuntime.isCoreThreadStartedForTest()
        }

        assertFalse("an Error must clear the running flag too", NativeCoreRuntime.isAppRunning())
    }

    private fun roundTrip(runtime: NativeCoreRuntime) {
        val ran = CountDownLatch(1)
        runtime.offerTask(Runnable { ran.countDown() })
        assertTrue("the core thread ran a task", ran.await(2, TimeUnit.SECONDS))
    }

    private fun createRuntime(
        onInit: () -> Unit = {},
        onUpdateActivityRef: () -> Unit = {},
        onRelease: () -> Unit = {},
        onSaveState: () -> Unit = {},
        tickDelayMillis: Int = 10,
        tickOverride: (() -> Int)? = null,
        awaitCoreTask: ((Long) -> Runnable?)? = null,
        // Replaces the production join, which waits on the real core thread;
        // null keeps it.
        joinCoreThread: ((Long) -> Unit)? = null,
        // Stands in for the Activity whose bound member references the production
        // runtime's lambdas capture.
        host: Any? = null,
    ): NativeCoreRuntime {
        val initNative: (String, Int) -> Unit = { _, slotId ->
            assertEquals(7, slotId)
            onInit()
        }
        val tick: () -> Int = tickOverride ?: host?.let { captured ->
            { System.identityHashCode(captured); tickDelayMillis }
        } ?: { tickDelayMillis }
        val refreshKeypadSnapshot: (Int) -> NativeKeypadSnapshotRefreshResult = {
            NativeKeypadSnapshotRefreshResult(
                observedGeneration = 0,
                snapshot = null,
                isUpToDate = true,
            )
        }
        val displayRefreshLoop = object : DisplayRefreshLoop {
            override fun start() {}

            override fun stop() {}
        }
        val await = awaitCoreTask ?: { null }
        return if (joinCoreThread == null) {
            NativeCoreRuntime(
                filesDirPath = "/tmp/r47-tests",
                currentSlotIdProvider = { 7 },
                nativePreInit = {},
                initNative = initNative,
                updateNativeActivityRef = onUpdateActivityRef,
                releaseNativeRuntime = onRelease,
                tick = tick,
                saveStateNative = onSaveState,
                getPackedDisplayGeneration = { 0 },
                getPackedDisplayBuffer = { true },
                getKeypadSnapshotGeneration = { 0 },
                getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
                refreshKeypadSnapshot = refreshKeypadSnapshot,
                onPackedLcd = { false },
                onDynamicRefresh = {},
                displayRefreshLoop = displayRefreshLoop,
                awaitCoreTask = await,
            )
        } else {
            NativeCoreRuntime(
                filesDirPath = "/tmp/r47-tests",
                currentSlotIdProvider = { 7 },
                nativePreInit = {},
                initNative = initNative,
                updateNativeActivityRef = onUpdateActivityRef,
                releaseNativeRuntime = onRelease,
                tick = tick,
                saveStateNative = onSaveState,
                getPackedDisplayGeneration = { 0 },
                getPackedDisplayBuffer = { true },
                getKeypadSnapshotGeneration = { 0 },
                getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
                refreshKeypadSnapshot = refreshKeypadSnapshot,
                onPackedLcd = { false },
                onDynamicRefresh = {},
                displayRefreshLoop = displayRefreshLoop,
                joinCoreThread = joinCoreThread,
                awaitCoreTask = await,
            )
        }
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
