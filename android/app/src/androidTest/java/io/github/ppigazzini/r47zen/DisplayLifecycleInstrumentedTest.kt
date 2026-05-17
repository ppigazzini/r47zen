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
    fun backgroundSavePreservesSpiralkGraphSnapshot() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val targetProgramFile = File(targetContext.filesDir, "PROGRAMS/program.p47")
        val spiralkFixture = targetContext.assets.open(SPIRALK_ASSET_PATH).use { input -> input.readBytes() }

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for SPIRALk save coverage",
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
            ProgramLoadTestBridge.saveBackgroundStateForTest()
            val afterHash = ProgramLoadTestBridge.captureDisplayHash()

            assertEquals(
                "Background save should preserve the SPIRALk graph snapshot",
                beforeHash,
                afterHash,
            )
        }
    }

    @Test
    fun pauseResumePreservesSpiralkGraphSnapshot() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val targetProgramFile = File(targetContext.filesDir, "PROGRAMS/program.p47")
        val spiralkFixture = targetContext.assets.open(SPIRALK_ASSET_PATH).use { input -> input.readBytes() }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "Native runtime did not become ready for SPIRALk pause/resume coverage",
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
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
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
        private const val PGM_WAITING = 2
        private const val PGM_PAUSED = 3
        private const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val RUN_TIMEOUT_MS = 90_000L
        private const val POLL_INTERVAL_MS = 25L
        private const val PAUSE_RESUME_SETTLE_MS = 50L
        private const val PAUSE_RESUME_RETRY_MS = 1_000L
    }
}
