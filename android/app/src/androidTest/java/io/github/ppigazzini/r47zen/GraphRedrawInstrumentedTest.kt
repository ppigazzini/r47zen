package io.github.ppigazzini.r47zen

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraphRedrawInstrumentedTest {
    @Test
    fun forceRefreshRearmsNativeGraphRedrawGate() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "Native runtime did not become ready for redraw-gate coverage",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )

            ProgramLoadTestBridge.setRedrawFlagForTest(false)
            assertFalse(ProgramLoadTestBridge.isRedrawFlagSetForTest())

            ProgramLoadTestBridge.forceRefresh()

            assertTrue(
                "forceRefreshNative should rearm the native graph redraw gate",
                ProgramLoadTestBridge.isRedrawFlagSetForTest(),
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
    }
}
