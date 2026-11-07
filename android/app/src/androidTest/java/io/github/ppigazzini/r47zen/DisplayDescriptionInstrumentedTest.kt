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

// The display for a screen reader, from the core to the LCD's node: the number
// being typed, which X does not hold yet, then X, then the error the screen shows,
// each through describeDisplayNative, the calculator thread, onDisplayText, and the
// node MainActivity hands it to.
@RunWith(AndroidJUnit4::class)
class DisplayDescriptionInstrumentedTest {
    @Test
    fun theLcdsNodeSaysWhatTheDisplayShows() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(
                "Native runtime did not become ready",
                waitUntil(TIMEOUT_MS) { ProgramLoadTestBridge.isRuntimeReady() },
            )
            ProgramLoadTestBridge.resetRuntime()

            tapAll(scenario, KEY_1, KEY_2)
            assertNodeSays(scenario, "the number being typed", isError = false) { it.contains("12") }

            tapAll(scenario, KEY_ENTER, KEY_3, KEY_PLUS)
            assertNodeSays(scenario, "X after 1 2 ENTER 3 +", isError = false) { it == "15" }

            tapAll(scenario, KEY_0, KEY_INVERT)
            assertNodeSays(scenario, "1/x of 0", isError = true) {
                it == "An argument exceeds the function domain"
            }
        }
    }

    private fun tapAll(scenario: ActivityScenario<MainActivity>, vararg codes: Int) {
        codes.forEach { tap(scenario, it) }
    }

    private fun assertNodeSays(
        scenario: ActivityScenario<MainActivity>,
        what: String,
        isError: Boolean,
        matches: (String) -> Boolean,
    ) {
        var said = ""
        var invalid = !isError
        // The read runs at rest, behind the keys pushed before it.
        assertTrue("the request queue refused the read", CalculatorEngine.describeDisplayNative())
        val heard = waitUntil(TIMEOUT_MS) {
            scenario.onActivity { activity ->
                val node = activity.findViewById<ReplicaOverlay>(R.id.replica_overlay).lcdAccessibilityNode
                said = node.contentDescription?.toString().orEmpty()
                invalid = node.createAccessibilityNodeInfo().isContentInvalid
            }
            matches(said) && invalid == isError
        }
        assertTrue("$what: the node said '$said', error $invalid", heard)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val KEY_0 = 34
        const val KEY_INVERT = 3
    }
}
