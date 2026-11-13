package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorWatchdogTest {
    private var unansweredMs = 0L
    private var offers = 0
    private val watchdog = CalculatorWatchdog(
        unansweredMs = { unansweredMs },
        offerRestart = { offers++ },
        thresholdMs = 10_000L,
    )

    @Test
    fun aCalculatorThatAnswers_isLeftAlone() {
        unansweredMs = 9_999L

        watchdog.check()

        assertEquals(0, offers)
    }

    // A program's SOLVE or INT that never converges, which nothing stops.
    @Test
    fun aCalculatorSilentPastTheThreshold_isOfferedARestartOnce() {
        unansweredMs = 10_000L
        watchdog.check()
        unansweredMs = 25_000L
        watchdog.check()

        assertEquals(1, offers)
    }

    @Test
    fun aCalculatorThatAnswersAgain_isWatchedAfresh() {
        unansweredMs = 12_000L
        watchdog.check()
        unansweredMs = 0L
        watchdog.check()
        unansweredMs = 11_000L
        watchdog.check()

        assertEquals(2, offers)
    }
}
