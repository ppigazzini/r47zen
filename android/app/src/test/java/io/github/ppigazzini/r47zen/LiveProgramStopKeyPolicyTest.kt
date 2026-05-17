package io.github.ppigazzini.r47zen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProgramStopKeyPolicyTest {
    @Test
    fun directStopKeysMatchDesktopRunLoopParity() {
        assertTrue(LiveProgramStopKeyPolicy.shouldPublishDirectStop(LiveProgramStopKeyPolicy.EXIT_KEY_CODE))
        assertTrue(LiveProgramStopKeyPolicy.shouldPublishDirectStop(LiveProgramStopKeyPolicy.RUN_STOP_KEY_CODE))
    }

    @Test
    fun nonStopKeysStayOnNormalQueuePath() {
        assertFalse(LiveProgramStopKeyPolicy.shouldPublishDirectStop(0))
        assertFalse(LiveProgramStopKeyPolicy.shouldPublishDirectStop(1))
        assertFalse(LiveProgramStopKeyPolicy.shouldPublishDirectStop(37))
        assertFalse(LiveProgramStopKeyPolicy.shouldPublishDirectStop(-LiveProgramStopKeyPolicy.EXIT_KEY_CODE))
    }
}
