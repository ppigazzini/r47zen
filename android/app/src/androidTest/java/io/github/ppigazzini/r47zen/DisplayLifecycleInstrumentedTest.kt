package io.github.ppigazzini.r47zen

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DisplayLifecycleInstrumentedTest {
    @Test
    fun backgroundSavePreservesDisplaySnapshot() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for background-save coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            ProgramLoadTestBridge.resetRuntime()
            ProgramLoadTestBridge.forceRefresh()

            val beforeHash = ProgramLoadTestBridge.captureDisplayHash()
            ProgramLoadTestBridge.saveBackgroundStateForTest()
            val afterHash = ProgramLoadTestBridge.captureDisplayHash()

            assertEquals(
                "Background save should preserve the packed LCD snapshot",
                beforeHash,
                afterHash,
            )
        }
    }

    @Test
    fun backgroundSavePreservesInjectedDisplaySnapshot() {
        // DE-FLAKE (REPORT-24 Milestone 4b Slice B): the former
        // backgroundSavePreservesSpiralkGraphSnapshot ran SPIRALk emergently (the
        // 90 s polling scenario) only to obtain a non-trivial framebuffer before
        // asserting that the background save does not corrupt it. But
        // r47_save_background_state_locked merely calls saveCalc(), which
        // serializes calculator state and never touches packedDisplayBuffer
        // (jni_lifecycle.c), so the contract holds for ANY framebuffer. The bridge
        // now injects a deterministic non-trivial pattern, hashes the framebuffer,
        // runs the background save, and re-hashes -- all under screenMutex so no
        // async redraw can interleave -- with no program run and no timeout. The
        // pause/resume and recreation snapshot tests keep SPIRALk because they
        // re-render from calculator state, which an injected buffer cannot stand
        // in for.
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for background-save coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )
            ProgramLoadTestBridge.resetRuntime()

            assertTrue(
                "a background save must not corrupt a non-trivial display framebuffer",
                ProgramLoadTestBridge.backgroundSaveKeepsInjectedDisplayBuffer(),
            )
        }
    }

    @Test
    fun pauseResumePreservesSpiralkGraphSnapshot() {
        // REGRESSION FIX (REPORT-24 §32): a Settings-style pause/resume used to be
        // display-passive against the previously pinned upstream, so an injected
        // framebuffer survived it byte-for-byte. After CI advanced to the latest
        // upstream HEAD (REPORT-24 §25), onResume re-renders packedDisplayBuffer
        // from calculator state, so an injected arbitrary buffer is overwritten by
        // the state-derived render -- the same display-active behaviour the
        // recreation test already accounts for (§22). Drive this from SPIRALk
        // state instead: pause/resume must preserve the calculator state, so a
        // force-refresh render of that state is byte-identical before and after,
        // whether or not onResume itself re-renders. forceRefresh on both sides
        // routes both captures through the same synchronous, screenMutex-guarded
        // render so the comparison cannot race an async redraw.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val targetProgramFile = File(targetContext.filesDir, "PROGRAMS/program.p47")
        val spiralkFixture = targetContext.assets.open(SPIRALK_ASSET_PATH).use { input -> input.readBytes() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "Native runtime did not become ready for pause/resume coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            ProgramLoadTestBridge.resetRuntime()
            val loadState = loadProgramFixture(targetProgramFile, spiralkFixture)
            assertTrue(
                "failed to seed SPIRALk register J with 2",
                ProgramLoadTestBridge.seedSpiralkInput(),
            )
            runSpiralkScenario(loadState)

            ProgramLoadTestBridge.forceRefresh()
            val beforeHash = ProgramLoadTestBridge.captureDisplayHash()
            assertTrue("the SPIRALk display snapshot must be non-trivial", beforeHash != 0L)

            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(
                "Native runtime did not stay ready across pause/resume",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            ProgramLoadTestBridge.forceRefresh()
            val afterHash = ProgramLoadTestBridge.captureDisplayHash()

            assertEquals(
                "Settings-style pause/resume should preserve the SPIRALk graph snapshot",
                beforeHash,
                afterHash,
            )
        }
    }

    @Test
    fun activityRecreationPreservesSpiralkGraphSnapshot() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val targetProgramFile = File(targetContext.filesDir, "PROGRAMS/program.p47")
        val spiralkFixture = targetContext.assets.open(SPIRALK_ASSET_PATH).use { input -> input.readBytes() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "Native runtime did not become ready for SPIRALk recreation coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            ProgramLoadTestBridge.resetRuntime()
            val loadState = loadProgramFixture(targetProgramFile, spiralkFixture)
            assertTrue(
                "failed to seed SPIRALk register J with 2",
                ProgramLoadTestBridge.seedSpiralkInput(),
            )
            runSpiralkScenario(loadState)

            val beforeHash = ProgramLoadTestBridge.captureDisplayHash()
            scenario.recreate()
            assertTrue(
                "Native runtime did not become ready after MainActivity recreation",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )
            val afterHash = ProgramLoadTestBridge.captureDisplayHash()

            assertEquals(
                "Activity recreation should preserve the SPIRALk graph snapshot",
                beforeHash,
                afterHash,
            )
        }
    }

    @Test
    fun directStopGateDeclinesInteractiveWaitStates() {
        // REGRESSION GUARD (REPORT-23 §30):
        //
        // The out-of-band direct stop must fire ONLY for the genuinely-busy run
        // states PGM_RUNNING (executing) and PGM_PAUSED (inside a timed PSE
        // loop) -- the states that cannot drain the queued sendKey in time. The
        // interactive parked states PGM_WAITING and PGM_RESUMING (a graphing
        // program holding its plot, a program between PSE/VIEW steps, an open
        // f/g/I/O menu) must DECLINE it, so MainActivity.dispatchLiveKey forwards
        // R/S(36)/EXIT(33) to the core instead of swallowing them: the second
        // R/S replots and EXIT leaves the menu.
        //
        // Commit 643b20a widened the native gate to accept PGM_WAITING (and
        // af617e6 PGM_RESUMING) to make an over-eager direct-stop snapshot test
        // pass; that codified the bug. This asserts the gate decision
        // deterministically across every run state -- it does not depend on a
        // real program reaching a specific (timing-dependent) state -- so the
        // regression cannot return through a green CI run. It would have failed
        // on 643b20a.
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for direct-stop gate coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            assertTrue(
                "PGM_RUNNING is busy and must accept the out-of-band direct stop",
                ProgramLoadTestBridge.directStopAllowedForRunState(PGM_RUNNING),
            )
            assertTrue(
                "PGM_PAUSED (timed PSE loop) must accept the out-of-band direct stop",
                ProgramLoadTestBridge.directStopAllowedForRunState(PGM_PAUSED),
            )
            assertEquals(
                "PGM_WAITING is an interactive wait and must DECLINE the out-of-band direct stop",
                false,
                ProgramLoadTestBridge.directStopAllowedForRunState(PGM_WAITING),
            )
            assertEquals(
                "PGM_RESUMING is an interactive wait and must DECLINE the out-of-band direct stop",
                false,
                ProgramLoadTestBridge.directStopAllowedForRunState(PGM_RESUMING),
            )
            assertEquals(
                "PGM_STOPPED has no running program and must DECLINE the out-of-band direct stop",
                false,
                ProgramLoadTestBridge.directStopAllowedForRunState(PGM_STOPPED),
            )
        }
    }

    @Test
    fun requestStopProgramHonorsRunStateGateEndToEnd() {
        // DE-FLAKE (REPORT-24 Milestone 4b): this replaces the former
        // busySpiralkAcceptsLiveDirectStop, which waited up to 90 s for a graphing
        // program to emergently reach a busy state just to prove that the REAL
        // requestStopProgramNative honours the gate. That emergent dependency is
        // the §31 trap (a test gated on a program reaching a timing-dependent
        // state). It is replaced by deterministic run-state injection: the live
        // gate is exercised end to end (onUIActivity + r47_direct_stop_allowed +
        // fnStopProgram) for EVERY run state, with no program run and no timeout.
        // Injection is side-effect-safe because fnStopProgram only sets
        // programRunStop = PGM_WAITING. The pure predicate is still covered by
        // directStopGateDeclinesInteractiveWaitStates; this proves the full JNI
        // function, not just the predicate.
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for run-state gate coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )
            ProgramLoadTestBridge.resetRuntime()

            // Genuinely-busy states accept the out-of-band stop end to end.
            for (busyState in listOf(PGM_RUNNING, PGM_PAUSED)) {
                ProgramLoadTestBridge.setProgramRunStop(busyState)
                assertTrue(
                    "requestStopProgram must accept the live direct stop for busy run state $busyState",
                    ProgramLoadTestBridge.requestStopProgram(),
                )
            }

            // Interactive waits and the idle state decline, so dispatchLiveKey
            // forwards R/S/EXIT to the core instead of swallowing them.
            for (nonBusyState in listOf(PGM_WAITING, PGM_RESUMING, PGM_STOPPED)) {
                ProgramLoadTestBridge.setProgramRunStop(nonBusyState)
                assertEquals(
                    "requestStopProgram must decline the live direct stop for run state $nonBusyState",
                    false,
                    ProgramLoadTestBridge.requestStopProgram(),
                )
            }

            ProgramLoadTestBridge.resetRuntime()
        }
    }

    private fun loadProgramFixture(targetProgramFile: File, content: ByteArray): ProgramLoadState {
        targetProgramFile.parentFile?.mkdirs()
        targetProgramFile.delete()
        targetProgramFile.outputStream().use { output ->
            output.write(content)
        }

        val resetState = ProgramLoadTestBridge.snapshotState()
        val stagedProgramFd = ParcelFileDescriptor.open(
            targetProgramFile,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )?.detachFd()
        requireNotNull(stagedProgramFd) {
            "failed to open staged SPIRALk program file"
        }

        ProgramLoadTestBridge.setNextLoadProgramFd(stagedProgramFd)
        try {
            assertTrue(
                "failed to start the asynchronous READP worker for SPIRALk",
                ProgramLoadTestBridge.beginSimFunction(ITM_READP),
            )

            val loaded = waitUntil(LOAD_TIMEOUT_MS) {
                if (ProgramLoadTestBridge.isSimFunctionRunning()) {
                    return@waitUntil false
                }
                val state = ProgramLoadTestBridge.snapshotState()
                state.lastErrorCode != ERROR_NONE ||
                    state.numberOfPrograms > resetState.numberOfPrograms ||
                    state.temporaryInformation == TI_PROGRAM_LOADED
            }

            assertTrue("READP did not finish loading SPIRALk in time", loaded)
            val loadState = ProgramLoadTestBridge.snapshotState()
            assertEquals("READP hit a calculator error while loading SPIRALk", ERROR_NONE, loadState.lastErrorCode)
            assertTrue(
                "SPIRALk load did not change the program state",
                loadState.numberOfPrograms > resetState.numberOfPrograms ||
                    loadState.temporaryInformation == TI_PROGRAM_LOADED,
            )
            return loadState
        } finally {
            ProgramLoadTestBridge.clearLoadProgramFdOverride()
        }
    }

    private fun runSpiralkScenario(loadState: ProgramLoadState) {
        assertTrue(
            "failed to start the asynchronous MainActivity R/S key worker for SPIRALk",
            ProgramLoadTestBridge.beginMainActivityKeySequence(RS_KEY_CODE),
        )

        val loadStep = loadState.currentLocalStepNumber
        var maxStep = loadStep
        var sawPause = false
        var sawWaiting = false
        var sawView = false
        var sawLcdRefresh = false
        var wasPaused = false
        var resumeAttempts = 0
        var lastResumeAttemptAtMs = 0L

        val completed = waitUntil(RUN_TIMEOUT_MS) {
            val state = ProgramLoadTestBridge.snapshotState()
            if (state.currentLocalStepNumber > maxStep) {
                maxStep = state.currentLocalStepNumber
            }
            val isPaused = state.programRunStop == PGM_PAUSED
            sawPause = sawPause || isPaused
            sawWaiting = sawWaiting || state.programRunStop == PGM_WAITING
            sawView = sawView || state.temporaryInformation == TI_VIEW_REGISTER
            sawLcdRefresh = sawLcdRefresh || state.lcdRefreshCount > 0

            val now = SystemClock.elapsedRealtime()
            if (isPaused && (!wasPaused || now - lastResumeAttemptAtMs >= PAUSE_RESUME_RETRY_MS)) {
                ProgramLoadTestBridge.sendSimKey("00", isFn = false, isRelease = false)
                SystemClock.sleep(PAUSE_RESUME_SETTLE_MS)
                ProgramLoadTestBridge.sendSimKey("00", isFn = false, isRelease = true)
                resumeAttempts += 1
                lastResumeAttemptAtMs = SystemClock.elapsedRealtime()
            }

            wasPaused = isPaused
            state.lastErrorCode != ERROR_NONE || !ProgramLoadTestBridge.isSimFunctionRunning()
        }

        val finalState = ProgramLoadTestBridge.snapshotState()
        val stillRunning = ProgramLoadTestBridge.isSimFunctionRunning()
        assertTrue(
            "RUN did not finish SPIRALk in time (loadStep=$loadStep, maxStep=$maxStep, pause=$sawPause, waiting=$sawWaiting, view=$sawView, lcdRefresh=$sawLcdRefresh, resumeAttempts=$resumeAttempts, finalRunStop=${finalState.programRunStop}, finalStep=${finalState.currentLocalStepNumber})",
            completed && !stillRunning,
        )
        assertEquals("RUN hit a calculator error while drawing SPIRALk", ERROR_NONE, finalState.lastErrorCode)
        assertTrue(
            "SPIRALk did not show any run-side display activity",
            maxStep > loadStep || sawPause || sawWaiting || sawView || sawLcdRefresh,
        )
    }

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
        private const val SPIRALK_ASSET_PATH = "program-fixtures/PROGRAMS/SPIRALk.p47"
        private const val ITM_READP = 1567
        private const val RS_KEY_CODE = 36
        private const val ERROR_NONE = 0
        private const val TI_VIEW_REGISTER = 15
        private const val TI_PROGRAM_LOADED = 86
        private const val PGM_STOPPED = 0
        private const val PGM_RUNNING = 1
        private const val PGM_WAITING = 2
        private const val PGM_PAUSED = 3
        private const val PGM_RESUMING = 5
        private const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val RUN_TIMEOUT_MS = 90_000L
        private const val POLL_INTERVAL_MS = 25L
        private const val PAUSE_RESUME_SETTLE_MS = 50L
        private const val PAUSE_RESUME_RETRY_MS = 1_000L
    }
}
