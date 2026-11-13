package io.github.ppigazzini.r47zen

/**
 * Offers a way out of a calculator that no key can stop. Upstream runs the function a
 * program's SOLVE or INT calls as a nested engine, which never polls, so EXIT and R/S do
 * nothing and only the process's end stops it. When the calculator has run with no poll
 * for [thresholdMs], [offerRestart] asks the user, once per such silence. The main thread.
 */
internal class CalculatorWatchdog(
    private val unansweredMs: () -> Long,
    private val offerRestart: () -> Unit,
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
) {
    private var offered = false

    fun check() {
        if (unansweredMs() < thresholdMs) {
            offered = false
        } else if (!offered) {
            offered = true
            offerRestart()
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 10_000L
        const val CHECK_INTERVAL_MS = 1_000L
    }
}
