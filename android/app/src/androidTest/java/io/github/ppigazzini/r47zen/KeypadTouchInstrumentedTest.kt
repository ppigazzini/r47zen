package io.github.ppigazzini.r47zen

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ppigazzini.r47zen.KeypadTouches.KEY_1
import io.github.ppigazzini.r47zen.KeypadTouches.KEY_2
import io.github.ppigazzini.r47zen.KeypadTouches.KEY_3
import io.github.ppigazzini.r47zen.KeypadTouches.KEY_ENTER
import io.github.ppigazzini.r47zen.KeypadTouches.KEY_PLUS
import io.github.ppigazzini.r47zen.KeypadTouches.tap
import io.github.ppigazzini.r47zen.KeypadTouches.waitUntil
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// The real MainActivity from a touch to the core: each touch runs the key view's
// listener, the dispatch MainActivity wires to its core-task queue, JNI, and the
// core.
@RunWith(AndroidJUnit4::class)
class KeypadTouchInstrumentedTest {
    @Test
    fun touchedKeysComputeOnTheCore() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "Native runtime did not become ready",
                waitUntil(RUNTIME_READY_TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )
            ProgramLoadTestBridge.resetRuntime()
            for (code in listOf(KEY_1, KEY_2, KEY_ENTER, KEY_3, KEY_PLUS)) {
                tap(scenario, code)
            }
            // The keys are core tasks, which a bridge read does not wait for: poll X.
            var x = ""
            val computed = waitUntil(ANSWER_TIMEOUT_MS) {
                x = ProgramLoadTestBridge.getXRegisterClipboardText().trim()
                x == "15"
            }
            assertTrue("1 2 ENTER 3 + left X '$x'", computed)
        }
    }

    private companion object {
        const val RUNTIME_READY_TIMEOUT_MS = 15_000L
        const val ANSWER_TIMEOUT_MS = 15_000L
    }
}
