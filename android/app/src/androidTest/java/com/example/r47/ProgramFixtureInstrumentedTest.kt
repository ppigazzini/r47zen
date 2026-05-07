package com.example.r47

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
    fun loadAndRunRequestedProgramsThroughAndroidRuntime() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = collectProgramFixtures(targetContext.assets)
        val failures = mutableListOf<String>()
        val targetProgramFile = File(targetContext.filesDir, "PROGRAMS/program.p47")
        val fixtureCount = fixtures.size

        assertTrue("No staged program fixtures found in APK assets", fixtureCount > 0)
        reportStatus("Collected $fixtureCount staged PROGRAMS fixtures for Android READP load-and-run coverage\n")

        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(
                    "Native runtime did not become ready for PROGRAMS fixture coverage",
                    waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
                )
                reportStatus("Native runtime ready; loading and running $fixtureCount staged PROGRAMS fixtures\n")

                for ((index, fixture) in fixtures.withIndex()) {
                    reportStatus("Program fixture ${index + 1}/$fixtureCount: ${fixture.displayName}\n")
                    ProgramLoadTestBridge.resetRuntime()
                    val resetState = ProgramLoadTestBridge.snapshotState()
                    if (resetState.lastErrorCode != ERROR_NONE || resetState.numberOfPrograms != EMPTY_RESET_PROGRAM_COUNT) {
                        failures += buildFailure(
                            fixture = fixture,
                            phase = "reset",
                            state = resetState,
                            details = "expected the empty reset baseline before staging the next fixture",
                        )
                        break
                    }

                    writeFixtureToProgramFile(fixture, targetProgramFile)
                    if (ProgramLoadTestBridge.isSimFunctionRunning()) {
                        failures += buildFailure(
                            fixture = fixture,
                            phase = "load",
                            state = null,
                            details = "READP worker was still running before the next fixture started",
                        )
                        break
                    }

                    val stagedProgramFd = try {
                        ParcelFileDescriptor.open(targetProgramFile, ParcelFileDescriptor.MODE_READ_ONLY)?.detachFd()
                    } catch (error: Exception) {
                        failures += buildFailure(
                            fixture = fixture,
                            phase = "load",
                            state = null,
                            details = "failed to open staged program file: ${error.message ?: error.javaClass.simpleName}",
                        )
                        break
                    }
                    if (stagedProgramFd == null) {
                        failures += buildFailure(
                            fixture = fixture,
                            phase = "load",
                            state = null,
                            details = "failed to open staged program file",
                        )
                        break
                    }

                    var loadState: ProgramLoadState? = null
                    var loadFailed = false
                    ProgramLoadTestBridge.setNextLoadProgramFd(stagedProgramFd)
                    try {
                        val started = ProgramLoadTestBridge.beginSimFunction(ITM_READP)
                        if (!started) {
                            failures += buildFailure(
                                fixture = fixture,
                                phase = "load",
                                state = null,
                                details = "failed to start the asynchronous READP worker",
                            )
                            loadFailed = true
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
                            if (ProgramLoadTestBridge.isSimFunctionRunning()) {
                                failures += buildFailure(
                                    fixture = fixture,
                                    phase = "load",
                                    state = null,
                                    details = "READP did not return within ${LOAD_TIMEOUT_MS} ms",
                                )
                                loadFailed = true
                            }

                            loadState = ProgramLoadTestBridge.snapshotState()
                            val programLoaded = loadState.temporaryInformation == TI_PROGRAM_LOADED
                            if (!loaded || loadState.lastErrorCode != ERROR_NONE ||
                                (!programLoaded && loadState.numberOfPrograms <= resetState.numberOfPrograms)
                            ) {
                                failures += buildFailure(
                                    fixture = fixture,
                                    phase = "load",
                                    state = loadState,
                                    details = "loaded=$loaded",
                                )
                                loadFailed = true
                            }
                        }
                    } finally {
                        ProgramLoadTestBridge.clearLoadProgramFdOverride()
                    }

                    if (loadFailed || loadState == null) {
                        continue
                    }

                    if (!seedFixtureRuntime(fixture)) {
                        failures += buildFailure(
                            fixture = fixture,
                            phase = "seed",
                            state = loadState,
                            details = fixture.seedFailureMessage,
                        )
                        continue
                    }

                    val runFailure = runFixtureScenario(fixture, loadState)
                    if (runFailure != null) {
                        failures += runFailure
                    }
                }
            }
        } finally {
            ProgramLoadTestBridge.clearLoadProgramFdOverride()
        }

        assertTrue(
            "Program fixture load-and-run failures:\n${failures.joinToString(separator = "\n")}",
            failures.isEmpty(),
        )
    }

    private fun collectProgramFixtures(assets: AssetManager): List<ProgramFixture> {
        return REQUIRED_FIXTURE_SCENARIOS.map { scenario ->
            val assetPath = "$PROGRAMS_ASSET_ROOT/${scenario.fileName}"
            ProgramFixture(
                displayName = "PROGRAMS/${scenario.fileName}",
                content = assets.open(assetPath).use { input -> input.readBytes() },
                scenario = scenario,
            )
        }
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
        val started = ProgramLoadTestBridge.beginSimFunction(ITM_RS)
        if (!started) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = loadState,
                details = "failed to start the asynchronous RUN worker",
            )
        }

        val loadStep = loadState.currentLocalStepNumber
        var maxStep = loadStep
        var sawPause = false
        var sawWaiting = false
        var sawView = false
        var sawLcdRefresh = false
        var sentResumeKey = false

        val completed = waitUntil(fixture.scenario.timeoutMs) {
            val state = ProgramLoadTestBridge.snapshotState()
            if (state.currentLocalStepNumber > maxStep) {
                maxStep = state.currentLocalStepNumber
            }
            sawPause = sawPause || state.programRunStop == PGM_PAUSED
            sawWaiting = sawWaiting || state.programRunStop == PGM_WAITING
            sawView = sawView || state.temporaryInformation == TI_VIEW_REGISTER
            sawLcdRefresh = sawLcdRefresh || state.lcdRefreshCount > 0

            if (state.programRunStop == PGM_PAUSED &&
                fixture.scenario.resumePauseWithZeroKey &&
                !sentResumeKey
            ) {
                ProgramLoadTestBridge.sendSimKey("00", isFn = false, isRelease = false)
                SystemClock.sleep(PAUSE_RESUME_SETTLE_MS)
                ProgramLoadTestBridge.sendSimKey("00", isFn = false, isRelease = true)
                sentResumeKey = true
            }

            state.lastErrorCode != ERROR_NONE || !ProgramLoadTestBridge.isSimFunctionRunning()
        }

        val finalState = ProgramLoadTestBridge.snapshotState()
        sawPause = sawPause || finalState.programRunStop == PGM_PAUSED
        sawWaiting = sawWaiting || finalState.programRunStop == PGM_WAITING
        sawView = sawView || finalState.temporaryInformation == TI_VIEW_REGISTER
        sawLcdRefresh = sawLcdRefresh || finalState.lcdRefreshCount > 0

        if (!completed || ProgramLoadTestBridge.isSimFunctionRunning()) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = finalState,
                details = "RUN did not return within ${fixture.scenario.timeoutMs} ms",
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
        if (maxStep <= loadStep && !sawPause && !sawWaiting && !sawView && !sawLcdRefresh) {
            return buildFailure(
                fixture = fixture,
                phase = "run",
                state = finalState,
                details = "workload never showed run activity after load (load_step=$loadStep, max_step=$maxStep, pause=$sawPause, waiting=$sawWaiting, view=$sawView, lcdRefresh=$sawLcdRefresh)",
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
            "${fixture.displayName} ran (load_step=$loadStep, max_step=$maxStep, pause=${yesNo(sawPause)}, waiting=${yesNo(sawWaiting)}, view=${yesNo(sawView)}, lcdRefresh=${yesNo(sawLcdRefresh)})\n",
        )
        return null
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
        val resumePauseWithZeroKey: Boolean,
        val seedMode: SeedMode,
    )

    private enum class SeedMode {
        NONE,
        SPIRALK_J_EQUALS_2,
    }

    companion object {
        private const val PROGRAMS_ASSET_ROOT = "program-fixtures/PROGRAMS"
        private const val ITM_READP = 1567
        private const val ITM_RS = 1725
        private const val ERROR_NONE = 0
        private const val EMPTY_RESET_PROGRAM_COUNT = 1
        private const val TI_VIEW_REGISTER = 15
        private const val TI_PROGRAM_LOADED = 86
        private const val PGM_RUNNING = 1
        private const val PGM_WAITING = 2
        private const val PGM_PAUSED = 3
        private const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val RUN_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 25L
        private const val PAUSE_RESUME_SETTLE_MS = 20L
        private val REQUIRED_FIXTURE_SCENARIOS = listOf(
            ProgramFixtureScenario(
                fileName = "BinetV3.p47",
                timeoutMs = RUN_TIMEOUT_MS,
                resumePauseWithZeroKey = false,
                seedMode = SeedMode.NONE,
            ),
            ProgramFixtureScenario(
                fileName = "GudrmPL.p47",
                timeoutMs = RUN_TIMEOUT_MS,
                resumePauseWithZeroKey = false,
                seedMode = SeedMode.NONE,
            ),
            ProgramFixtureScenario(
                fileName = "NQueens.p47",
                timeoutMs = RUN_TIMEOUT_MS,
                resumePauseWithZeroKey = false,
                seedMode = SeedMode.NONE,
            ),
            ProgramFixtureScenario(
                fileName = "SPIRALk.p47",
                timeoutMs = RUN_TIMEOUT_MS,
                resumePauseWithZeroKey = true,
                seedMode = SeedMode.SPIRALK_J_EQUALS_2,
            ),
        )
    }
}