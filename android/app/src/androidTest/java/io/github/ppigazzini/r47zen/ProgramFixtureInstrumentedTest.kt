package io.github.ppigazzini.r47zen

import android.content.res.AssetManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProgramFixtureInstrumentedTest {
    @Test
    fun loadAndRunBinetV4ThroughAndroidRuntime() {
        runRequestedProgramFixture("BinetV4.p47")
    }

    @Test
    fun loadAndRunGudrmPLThroughAndroidRuntime() {
        runRequestedProgramFixture("GudrmPL.p47")
    }

    @Test
    fun loadAndRunMANSLV2ThroughAndroidRuntime() {
        runRequestedProgramFixture("MANSLV2.p47")
    }

    @Test
    fun loadAndRunNQueensThroughAndroidRuntime() {
        runRequestedProgramFixture("NQueens.p47")
    }

    @Test
    fun loadAndRunSPIRALkThroughAndroidRuntime() {
        runRequestedProgramFixture("SPIRALk.p47")
    }

    private fun runRequestedProgramFixture(fileName: String) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = loadProgramFixture(targetContext.assets, requireProgramFixtureScenario(fileName))
        val targetProgramFile = File(targetContext.filesDir, "PROGRAMS/program.p47")
        var failure: String? = null

        reportStatus("Program fixture: ${fixture.displayName}\n")

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(
                    "Native runtime did not become ready for PROGRAMS fixture coverage",
                    waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
                )
                reportStatus("Native runtime ready; loading and running ${fixture.displayName}\n")
                try {
                    failure = exerciseFixture(fixture, targetProgramFile)
                } finally {
                    cleanupFixtureRuntime(fixture)
                }
            }
        } finally {
            ProgramLoadTestBridge.clearLoadProgramFdOverride()
        }

        assertTrue(
            "Program fixture load-and-run failure:\n${failure ?: "unknown failure"}",
            failure == null,
        )
    }

    private fun loadProgramFixture(
        assets: AssetManager,
        scenario: ProgramFixtureScenario,
    ): ProgramFixture {
        val assetPath = "$PROGRAMS_ASSET_ROOT/${scenario.fileName}"
        return ProgramFixture(
            displayName = "PROGRAMS/${scenario.fileName}",
            content = assets.open(assetPath).use { input -> input.readBytes() },
            scenario = scenario,
        )
    }

    private fun requireProgramFixtureScenario(fileName: String): ProgramFixtureScenario {
        return REQUIRED_FIXTURE_SCENARIOS_BY_NAME[fileName]
            ?: error("Missing PROGRAMS fixture scenario for $fileName")
    }

    private fun exerciseFixture(
        fixture: ProgramFixture,
        targetProgramFile: File,
    ): String? {
        ProgramLoadTestBridge.resetRuntime()
        val resetState = ProgramLoadTestBridge.snapshotState()
        if (resetState.lastErrorCode != ERROR_NONE || resetState.numberOfPrograms != EMPTY_RESET_PROGRAM_COUNT) {
            return buildFailure(
                fixture = fixture,
                phase = "reset",
                state = resetState,
                details = "expected the empty reset baseline before staging the next fixture",
            )
        }

        writeFixtureToProgramFile(fixture, targetProgramFile)
        if (ProgramLoadTestBridge.isSimFunctionRunning()) {
            return buildFailure(
                fixture = fixture,
                phase = "load",
                state = null,
                details = "READP worker was still running before the fixture started",
            )
        }

        val stagedProgramFd = try {
            ParcelFileDescriptor.open(targetProgramFile, ParcelFileDescriptor.MODE_READ_ONLY)?.detachFd()
        } catch (error: Exception) {
            return buildFailure(
                fixture = fixture,
                phase = "load",
                state = null,
                details = "failed to open staged program file: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        if (stagedProgramFd == null) {
            return buildFailure(
                fixture = fixture,
                phase = "load",
                state = null,
                details = "failed to open staged program file",
            )
        }

        var loadState: ProgramLoadState? = null
        var loadFailure: String? = null
        ProgramLoadTestBridge.setNextLoadProgramFd(stagedProgramFd)
        try {
            val started = ProgramLoadTestBridge.beginSimFunction(ITM_READP)
            if (!started) {
                loadFailure = buildFailure(
                    fixture = fixture,
                    phase = "load",
                    state = null,
                    details = "failed to start the asynchronous READP worker",
                )
            } else {
                val loaded = waitUntil(LOAD_TIMEOUT_MS) {
                    if (ProgramLoadTestBridge.isSimFunctionRunning()) {
                        return@waitUntil false
                    }
                    val state = ProgramLoadTestBridge.snapshotState()
                    state.lastErrorCode != ERROR_NONE ||
                        state.numberOfPrograms > resetState.numberOfPrograms ||
                        state.temporaryInformation == TI_PROGRAM_LOADED
                }
                val workerStillRunning = ProgramLoadTestBridge.isSimFunctionRunning()
                loadState = if (workerStillRunning) {
                    ProgramLoadTestBridge.trySnapshotState() ?: resetState
                } else {
                    ProgramLoadTestBridge.snapshotState()
                }
                val currentLoadState = loadState
                val programLoaded = currentLoadState.temporaryInformation == TI_PROGRAM_LOADED
                if (workerStillRunning) {
                    loadFailure = buildFailure(
                        fixture = fixture,
                        phase = "load",
                        state = currentLoadState,
                        details = "READP did not return within ${LOAD_TIMEOUT_MS} ms",
                    )
                } else if (!loaded || currentLoadState.lastErrorCode != ERROR_NONE ||
                    (!programLoaded && currentLoadState.numberOfPrograms <= resetState.numberOfPrograms)
                ) {
                    loadFailure = buildFailure(
                        fixture = fixture,
                        phase = "load",
                        state = currentLoadState,
                        details = "loaded=$loaded",
                    )
                }
            }
        } finally {
            ProgramLoadTestBridge.clearLoadProgramFdOverride()
        }

        if (loadFailure != null) {
            return loadFailure
        }

        val loadedFixtureState = loadState
            ?: return buildFailure(
                fixture = fixture,
                phase = "load",
                state = null,
                details = "fixture load did not produce a terminal state snapshot",
            )

        if (!seedFixtureRuntime(fixture)) {
            return buildFailure(
                fixture = fixture,
                phase = "seed",
                state = loadedFixtureState,
                details = fixture.seedFailureMessage,
            )
        }

        return runFixtureScenario(fixture, loadedFixtureState)
    }

    private fun seedFixtureRuntime(fixture: ProgramFixture): Boolean {
        return when (fixture.scenario.seedMode) {
            SeedMode.NONE -> true
            SeedMode.SPIRALK_J_EQUALS_2 -> ProgramLoadTestBridge.seedSpiralkInput()
        }
    }

    private fun runFixtureScenario(
        fixture: ProgramFixture,
        loadState: ProgramLoadState,
    ): String? {
        val started = ProgramLoadTestBridge.beginMainActivityKeySequence(RS_KEY_CODE)
        if (!started) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = loadState,
                details = "failed to start the asynchronous MainActivity R/S key worker",
            )
        }

        val loadStep = loadState.currentLocalStepNumber
        var maxStep = loadStep
        var sawPause = false
        var sawWaiting = false
        var sawView = false
        var sawLcdRefresh = false
        var activityStartedAtMs = 0L
        var requestedDirectStop = false
        var wasPaused = false
        var directStopRequests = 0
        var resumeAttempts = 0
        var lastDirectStopAttemptAtMs = 0L
        var lastResumeAttemptAtMs = 0L
        var snapshotMisses = 0
        val runStartedAtMs = SystemClock.elapsedRealtime()
        var lastObservedState = loadState
        var sawAcceptedDirectStop = false
        var directStopAcceptedWhileNotBusy = false

        fun maybeRequestDirectStop(now: Long, observedRunStop: Int?) {
            if (fixture.scenario.stopPolicy != StopPolicy.DIRECT_STOP_AFTER_ACTIVITY) {
                return
            }

            val directStopAnchorMs = if (activityStartedAtMs != 0L) {
                activityStartedAtMs
            } else {
                runStartedAtMs
            }
            if (now - directStopAnchorMs < fixture.scenario.stopAfterActivityMs ||
                now - lastDirectStopAttemptAtMs < DIRECT_STOP_RETRY_MS
            ) {
                return
            }

            if (ProgramLoadTestBridge.requestStopProgram()) {
                requestedDirectStop = true
                sawAcceptedDirectStop = true
                directStopRequests += 1
                // REPORT-24 W2 guard: the out-of-band direct stop must only be
                // accepted while the program is genuinely busy (PGM_RUNNING /
                // PGM_PAUSED). If it is ever accepted while an OBSERVED interactive
                // wait holds, the native gate has regressed exactly as in the
                // swallowed-key bug -- it would steal live R/S/EXIT. Record that as
                // a failure signal instead of letting the acceptance masquerade as
                // run activity (the original liveness-OR conflation). Only observed
                // states are classified, so a null snapshot never false-fails.
                if (observedRunStop != null &&
                    observedRunStop != PGM_RUNNING &&
                    observedRunStop != PGM_PAUSED
                ) {
                    directStopAcceptedWhileNotBusy = true
                }
                if (activityStartedAtMs == 0L) {
                    activityStartedAtMs = now
                }
            }
            lastDirectStopAttemptAtMs = now
        }

        val completed = waitUntil(fixture.scenario.timeoutMs) {
            val now = SystemClock.elapsedRealtime()
            val state = ProgramLoadTestBridge.trySnapshotState()
            if (state == null) {
                snapshotMisses += 1

                maybeRequestDirectStop(now, observedRunStop = null)

                return@waitUntil !ProgramLoadTestBridge.isSimFunctionRunning()
            }

            lastObservedState = state
            if (state.currentLocalStepNumber > maxStep) {
                maxStep = state.currentLocalStepNumber
            }
            val isPaused = state.programRunStop == PGM_PAUSED
            sawPause = sawPause || isPaused
            sawWaiting = sawWaiting || state.programRunStop == PGM_WAITING
            sawView = sawView || state.temporaryInformation == TI_VIEW_REGISTER
            sawLcdRefresh = sawLcdRefresh || state.lcdRefreshCount > 0
            val hasRunActivity =
                maxStep > loadStep || sawPause || sawWaiting || sawView || sawLcdRefresh ||
                    sawAcceptedDirectStop

            if (hasRunActivity && activityStartedAtMs == 0L) {
                activityStartedAtMs = now
            }

            if (
                isPaused &&
                (!wasPaused || now - lastResumeAttemptAtMs >= PAUSE_RESUME_RETRY_MS) &&
                fixture.scenario.pauseResumePolicy == PauseResumePolicy.RESUME_ZERO_ON_PAUSE_EDGE
            ) {
                ProgramLoadTestBridge.sendSimKey("00", isFn = false, isRelease = false)
                SystemClock.sleep(PAUSE_RESUME_SETTLE_MS)
                ProgramLoadTestBridge.sendSimKey("00", isFn = false, isRelease = true)
                resumeAttempts += 1
                lastResumeAttemptAtMs = SystemClock.elapsedRealtime()
            }

            maybeRequestDirectStop(now, observedRunStop = state.programRunStop)

            wasPaused = isPaused

            state.lastErrorCode != ERROR_NONE || !ProgramLoadTestBridge.isSimFunctionRunning()
        }

        val workerStillRunning = ProgramLoadTestBridge.isSimFunctionRunning()
        val finalState = if (workerStillRunning) {
            ProgramLoadTestBridge.trySnapshotState() ?: lastObservedState
        } else {
            ProgramLoadTestBridge.snapshotState()
        }
        sawPause = sawPause || finalState.programRunStop == PGM_PAUSED
        sawWaiting = sawWaiting || finalState.programRunStop == PGM_WAITING
        sawView = sawView || finalState.temporaryInformation == TI_VIEW_REGISTER
        sawLcdRefresh = sawLcdRefresh || finalState.lcdRefreshCount > 0

        if (!completed || workerStillRunning) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = finalState,
                details = "RUN did not return within ${fixture.scenario.timeoutMs} ms (load_step=$loadStep, max_step=$maxStep, pause=$sawPause, waiting=$sawWaiting, view=$sawView, lcdRefresh=$sawLcdRefresh, directStopAccepted=$sawAcceptedDirectStop, resumeAttempts=$resumeAttempts, snapshotMisses=$snapshotMisses, finalRunStop=${finalState.programRunStop}, finalStep=${finalState.currentLocalStepNumber})",
            )
        }
        if (finalState.lastErrorCode != ERROR_NONE) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = finalState,
                details = "RUN hit calculator error ${finalState.lastErrorCode}",
            )
        }
        if (
            maxStep <= loadStep &&
            !sawPause &&
            !sawWaiting &&
            !sawView &&
            !sawLcdRefresh &&
            !sawAcceptedDirectStop
        ) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = finalState,
                details = "workload never showed run activity after load (load_step=$loadStep, max_step=$maxStep, pause=$sawPause, waiting=$sawWaiting, view=$sawView, lcdRefresh=$sawLcdRefresh, directStopAccepted=$sawAcceptedDirectStop)",
            )
        }
        if (fixture.scenario.stopPolicy == StopPolicy.DIRECT_STOP_AFTER_ACTIVITY && !requestedDirectStop) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = finalState,
                details = "workload finished before the maintained direct-stop probe ran",
            )
        }
        if (directStopAcceptedWhileNotBusy) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = finalState,
                details = "out-of-band direct stop was accepted while the program was parked in an interactive wait (not PGM_RUNNING/PGM_PAUSED); the native run-state gate has regressed and would swallow live R/S/EXIT",
            )
        }
        if (finalState.programRunStop == PGM_RUNNING || finalState.programRunStop == PGM_PAUSED) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = finalState,
                details = "workload finished in unexpected run state ${finalState.programRunStop}",
            )
        }

        reportStatus(
            "${fixture.displayName} ran (load_step=$loadStep, max_step=$maxStep, pause=${yesNo(sawPause)}, waiting=${yesNo(sawWaiting)}, view=${yesNo(sawView)}, lcdRefresh=${yesNo(sawLcdRefresh)}, directStop=${yesNo(requestedDirectStop)}, directStopAccepted=${yesNo(sawAcceptedDirectStop)}, directStopRequests=$directStopRequests, snapshotMisses=$snapshotMisses)\n",
        )
        return null
    }

    private fun cleanupFixtureRuntime(fixture: ProgramFixture) {
        // Only a genuinely-busy program (PGM_RUNNING / PGM_PAUSED) can be drained
        // with the out-of-band direct stop; that is exactly the gate contract in
        // jni_input.c. A program parked in an interactive wait (PGM_WAITING /
        // PGM_RESUMING) deliberately declines the direct stop -- on the live
        // keypad those states must keep receiving R/S/EXIT -- so we never spin on
        // them here. The forceful resetRuntime() (doFnReset) below clears any
        // parked program regardless of run state, so cleanup only needs to wait
        // for the async worker thread to return.
        val cleaned = waitUntil(FIXTURE_CLEANUP_TIMEOUT_MS) {
            if (!ProgramLoadTestBridge.isSimFunctionRunning()) {
                return@waitUntil true
            }

            val state = ProgramLoadTestBridge.trySnapshotState()
            val busy = state != null &&
                (state.programRunStop == PGM_RUNNING || state.programRunStop == PGM_PAUSED)
            if (busy) {
                ProgramLoadTestBridge.requestStopProgram()
                ProgramLoadTestBridge.forceRefresh()
            }

            false
        }

        if (cleaned) {
            ProgramLoadTestBridge.resetRuntime()
            return
        }

        val state = ProgramLoadTestBridge.trySnapshotState()
        val details = if (state == null) {
            "state=unavailable"
        } else {
            "runStop=${state.programRunStop}, localStep=${state.currentLocalStepNumber}, tempInfo=${state.temporaryInformation}, error=${state.lastErrorCode}"
        }
        reportStatus("Cleanup warning for ${fixture.displayName}: $details\n")
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private fun writeFixtureToProgramFile(
        fixture: ProgramFixture,
        targetProgramFile: File,
    ) {
        targetProgramFile.parentFile?.mkdirs()
        targetProgramFile.delete()

        targetProgramFile.outputStream().use { output ->
            output.write(fixture.content)
        }
    }

    private fun reportStatus(message: String) {
        System.out.print(message)
        System.out.flush()
        val statusBundle = Bundle().apply {
            putString("stream", message)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, statusBundle)
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

    private fun buildFailure(
        fixture: ProgramFixture,
        phase: String,
        state: ProgramLoadState?,
        details: String,
    ): String {
        return buildString {
            append(fixture.displayName)
            append(" [")
            append(phase)
            append("]: ")
            append(details)
            if (state != null) {
                append(", error=")
                append(state.lastErrorCode)
                append(", tempInfo=")
                append(state.temporaryInformation)
                append(", runStop=")
                append(state.programRunStop)
                append(", programs=")
                append(state.numberOfPrograms)
                append(", localStep=")
                append(state.currentLocalStepNumber)
                append(", currentProgram=")
                append(state.currentProgramNumber)
                append(", lcdRefresh=")
                append(state.lcdRefreshCount)
            }
        }
    }

    private data class ProgramFixture(
        val displayName: String,
        val content: ByteArray,
        val scenario: ProgramFixtureScenario,
    ) {
        val seedFailureMessage: String
            get() = when (scenario.seedMode) {
                SeedMode.NONE -> "runtime seeding was not required"
                SeedMode.SPIRALK_J_EQUALS_2 -> "failed to seed SPIRALk register J with 2"
            }
    }

    private data class ProgramFixtureScenario(
        val fileName: String,
        val timeoutMs: Long,
        val pauseResumePolicy: PauseResumePolicy,
        val seedMode: SeedMode,
        val stopPolicy: StopPolicy,
        val stopAfterActivityMs: Long,
    )

    private enum class SeedMode {
        NONE,
        SPIRALK_J_EQUALS_2,
    }

    private enum class PauseResumePolicy {
        NONE,
        RESUME_ZERO_ON_PAUSE_EDGE,
    }

    private enum class StopPolicy {
        NONE,
        DIRECT_STOP_AFTER_ACTIVITY,
    }

    companion object {
        private const val PROGRAMS_ASSET_ROOT = "program-fixtures/PROGRAMS"
        private const val ITM_READP = 1567
        private const val RS_KEY_CODE = 36
        private const val ERROR_NONE = 0
        private const val EMPTY_RESET_PROGRAM_COUNT = 1
        private const val TI_VIEW_REGISTER = 15
        private const val TI_PROGRAM_LOADED = 86
        private const val PGM_RUNNING = 1
        private const val PGM_WAITING = 2
        private const val PGM_PAUSED = 3
        private const val PGM_RESUMING = 5
        private const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val RUN_TIMEOUT_MS = 20_000L
        private const val DIRECT_STOP_RETRY_MS = 250L
        private const val POLL_INTERVAL_MS = 25L
        private const val PAUSE_RESUME_SETTLE_MS = 50L
        private const val PAUSE_RESUME_RETRY_MS = 1_000L
        private const val FIXTURE_CLEANUP_TIMEOUT_MS = 5_000L
        private val REQUIRED_FIXTURE_SCENARIOS = listOf(
            ProgramFixtureScenario(
                fileName = "BinetV4.p47",
                timeoutMs = RUN_TIMEOUT_MS,
                pauseResumePolicy = PauseResumePolicy.NONE,
                seedMode = SeedMode.NONE,
                stopPolicy = StopPolicy.NONE,
                stopAfterActivityMs = 0L,
            ),
            ProgramFixtureScenario(
                fileName = "GudrmPL.p47",
                timeoutMs = RUN_TIMEOUT_MS,
                pauseResumePolicy = PauseResumePolicy.NONE,
                seedMode = SeedMode.NONE,
                stopPolicy = StopPolicy.NONE,
                stopAfterActivityMs = 0L,
            ),
            ProgramFixtureScenario(
                fileName = "MANSLV2.p47",
                timeoutMs = 30_000L,
                pauseResumePolicy = PauseResumePolicy.NONE,
                seedMode = SeedMode.NONE,
                stopPolicy = StopPolicy.DIRECT_STOP_AFTER_ACTIVITY,
                stopAfterActivityMs = 3_000L,
            ),
            ProgramFixtureScenario(
                fileName = "NQueens.p47",
                timeoutMs = RUN_TIMEOUT_MS,
                pauseResumePolicy = PauseResumePolicy.NONE,
                seedMode = SeedMode.NONE,
                stopPolicy = StopPolicy.NONE,
                stopAfterActivityMs = 0L,
            ),
            ProgramFixtureScenario(
                fileName = "SPIRALk.p47",
                timeoutMs = RUN_TIMEOUT_MS,
                pauseResumePolicy = PauseResumePolicy.RESUME_ZERO_ON_PAUSE_EDGE,
                seedMode = SeedMode.SPIRALK_J_EQUALS_2,
                stopPolicy = StopPolicy.NONE,
                stopAfterActivityMs = 0L,
            ),
        )
        private val REQUIRED_FIXTURE_SCENARIOS_BY_NAME = REQUIRED_FIXTURE_SCENARIOS.associateBy { it.fileName }
    }
}
