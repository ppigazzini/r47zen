package io.github.ppigazzini.r47zen

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphTouchStressInstrumentedTest {
    @Test
    fun extremePanZoomStressNeverCommitsInvalidBounds() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for graph-touch stress coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            assertTrue(
                "Extreme graph-touch stress detected non-finite or out-of-range bounds commit",
                ProgramLoadTestBridge.runExtremeGraphTouchStress(iterations = STRESS_ITERATIONS),
            )
        }
    }

    @Test
    fun restorePathSanitizesInvalidGraphBoundsBeforeRefresh() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for restore sanitization coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            assertTrue(
                "Restore-time graph-bound sanitization failed to recover invalid bounds",
                ProgramLoadTestBridge.restoreSanitizesInvalidGraphBounds(),
            )
        }
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
        private const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 25L
        private const val STRESS_ITERATIONS = 1_024
    }
}
