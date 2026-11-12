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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// The real MainActivity from a touch to the core: each touch runs the key view's
// listener, the dispatch MainActivity wires to CalculatorEngine, JNI, the key buffer,
// and the core.
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
            // A read at rest runs behind the keys pushed before it.
            assertEquals("1 2 ENTER 3 + left X", "15", ProgramLoadTestBridge.getXRegisterClipboardText().trim())
        }
    }

    private companion object {
        const val RUNTIME_READY_TIMEOUT_MS = 15_000L
    }
}
