package io.github.ppigazzini.r47zen

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FactorsInstrumentedTest {
    @Test
    fun factorsRunsThroughAndroidRuntime() {
        val failures = mutableListOf<String>()

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for FACTORS coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            ProgramLoadTestBridge.resetRuntime()
            val resetState = ProgramLoadTestBridge.snapshotState()
            if (resetState.lastErrorCode != ERROR_NONE) {
                failures += buildFailure(
                    phase = "reset",
                    details = "reset left calculator error ${resetState.lastErrorCode}",
                )
            }

            if (!ProgramLoadTestBridge.seedLargeFactorsInput()) {
                failures += buildFailure(
                    phase = "seed",
                    details = "failed to seed the large FACTORS input into X",
                )
            }

            if (failures.isEmpty()) {
                val started = ProgramLoadTestBridge.beginSimFunction(ITM_FACTORS)
                if (!started) {
                    failures += buildFailure(
                        phase = "run",
                        details = "failed to start the asynchronous FACTORS worker",
                    )
                } else {
                    val completed = waitUntil(FACTORS_TIMEOUT_MS) {
                        !ProgramLoadTestBridge.isSimFunctionRunning()
                    }
                    val finalState = ProgramLoadTestBridge.snapshotState()
                    val xRegisterType = ProgramLoadTestBridge.getXRegisterType()
                    val xRegisterString = ProgramLoadTestBridge.getXRegisterString()
                    if (!completed || ProgramLoadTestBridge.isSimFunctionRunning()) {
                        failures += buildFailure(
                            phase = "run",
                            details = "FACTORS did not return within ${FACTORS_TIMEOUT_MS} ms",
                        )
                    } else if (finalState.lastErrorCode != ERROR_NONE) {
                        failures += buildFailure(
                            phase = "run",
                            details = "FACTORS hit calculator error ${finalState.lastErrorCode}",
                        )
                    } else if (xRegisterType != DT_REAL34_MATRIX) {
                        failures += buildFailure(
                            phase = "result",
                            details = "FACTORS left X in type $xRegisterType with value '$xRegisterString'",
                        )
                    }
                }
            }
        }

        assertTrue(
            "FACTORS instrumentation failures:\n${failures.joinToString(separator = "\n")}",
            failures.isEmpty(),
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

    private fun buildFailure(
        phase: String,
        details: String,
    ): String {
        val message = "FACTORS [$phase]: $details"
        reportStatus("$message\n")
        return message
    }

    private fun reportStatus(message: String) {
        System.out.print(message)
        System.out.flush()
        val statusBundle = Bundle().apply {
            putString("stream", message)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, statusBundle)
    }

    companion object {
        private const val ITM_FACTORS = 1477
        private const val DT_REAL34_MATRIX = 6
        private const val ERROR_NONE = 0
        private const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        private const val FACTORS_TIMEOUT_MS = 45_000L
        private const val POLL_INTERVAL_MS = 25L
    }
}
