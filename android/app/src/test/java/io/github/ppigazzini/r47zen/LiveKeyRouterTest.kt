package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveKeyRouterTest {
    // The consumer half of the direct-stop seam.
    // The native run-state gate predicate is covered deterministically by
    // DisplayLifecycleInstrumentedTest.directStopGateDeclinesInteractiveWaitStates,
    // and the key-code policy by LiveProgramStopKeyPolicyTest. Neither proves the
    // ROUTING CONSEQUENCE: that MainActivity.dispatchLiveKey forwards R/S/EXIT to
    // the core when the gate declines and swallows them only when it accepts.
    // That gap is exactly where the swallowed-key regression lived. These tests
    // exercise the extracted pure router with spies so the consequence is
    // asserted without an emulator.

    private val forwarded = mutableListOf<Int>()
    private var gateQueries = 0

    private fun route(keyCode: Int, gateAccepts: Boolean) {
        LiveKeyRouter.route(
            keyCode,
            queryDirectStopGate = {
                gateQueries += 1
                gateAccepts
            },
            forwardToCore = { code -> forwarded += code },
        )
    }

    @Test
    fun nonStopKeysForwardWithoutQueryingTheSideEffectingGate() {
        // The direct-stop gate is the native fnStopProgram publisher. It must never
        // be consulted for a non-stop key, even if it would accept -- otherwise an
        // ordinary keypress could publish a spurious stop.
        for (keyCode in listOf(-1, 0, 1, 32, 34, 35, 37, 38, 64)) {
            forwarded.clear()
            gateQueries = 0
            route(keyCode, gateAccepts = true)
            assertEquals("gate must not be queried for non-stop key $keyCode", 0, gateQueries)
            assertEquals("non-stop key $keyCode must be forwarded", listOf(keyCode), forwarded)
        }
    }

    @Test
    fun stopKeysForwardToCoreWhenTheGateDeclines() {
        // The regression contract: while a program is parked in an
        // interactive wait the gate declines, so R/S(36) and EXIT(33) MUST reach
        // the core (replot / leave the f/g/I/O menu), not be swallowed.
        for (keyCode in listOf(LiveProgramStopKeyPolicy.EXIT_KEY_CODE, LiveProgramStopKeyPolicy.RUN_STOP_KEY_CODE)) {
            forwarded.clear()
            gateQueries = 0
            route(keyCode, gateAccepts = false)
            assertEquals("gate must be queried for stop key $keyCode", 1, gateQueries)
            assertEquals("declined stop key $keyCode must be forwarded", listOf(keyCode), forwarded)
        }
    }

    @Test
    fun stopKeysAreSwallowedOnlyWhenTheGateAccepts() {
        // While the program is genuinely busy the gate accepts, so the out-of-band
        // stop consumes R/S/EXIT and they are NOT forwarded to the core.
        for (keyCode in listOf(LiveProgramStopKeyPolicy.EXIT_KEY_CODE, LiveProgramStopKeyPolicy.RUN_STOP_KEY_CODE)) {
            forwarded.clear()
            gateQueries = 0
            route(keyCode, gateAccepts = true)
            assertEquals("gate must be queried for stop key $keyCode", 1, gateQueries)
            assertEquals("accepted stop key $keyCode must be swallowed", emptyList<Int>(), forwarded)
        }
    }
}
