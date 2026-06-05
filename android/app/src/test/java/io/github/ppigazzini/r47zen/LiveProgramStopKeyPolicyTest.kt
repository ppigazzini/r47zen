package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveProgramStopKeyPolicyTest {
    // The live-stop key codes are an upstream-core contract: src/c47/programming/
    // input.c treats `key == 36 || key == 33` as a stop request while a program is
    // PGM_RUNNING. Pin the policy to those LITERAL codes so widening the policy, or
    // editing a constant, fails here instead of silently following whatever the
    // production constant happens to be (the REPORT-24 W1 tautology, where the test
    // fed the function its own constants and could never fail). The cross-source
    // lock against the actual upstream input.c lives in
    // scripts/r47_contracts/test_live_stop_key_policy_contract.py.

    @Test
    fun stopKeyConstantsMatchUpstreamRunLoopCodes() {
        assertEquals("EXIT must stay upstream key code 33", 33, LiveProgramStopKeyPolicy.EXIT_KEY_CODE)
        assertEquals("R/S must stay upstream key code 36", 36, LiveProgramStopKeyPolicy.RUN_STOP_KEY_CODE)
    }

    @Test
    fun onlyExitAndRunStopPublishDirectStopAcrossTheKeySpace() {
        // Pinning to literal 33/36 (not the policy's own constants) and sweeping the
        // whole plausible key space proves the direct-stop set is EXACTLY {33, 36}:
        // a widened policy, a changed constant, or a hijacked neighbour all fail.
        val directStopCodes = setOf(33, 36)
        for (keyCode in -8..64) {
            val expected = keyCode in directStopCodes
            assertEquals(
                "shouldPublishDirectStop($keyCode) must be $expected",
                expected,
                LiveProgramStopKeyPolicy.shouldPublishDirectStop(keyCode),
            )
        }
    }
}
