package io.github.ppigazzini.r47zen

internal object LiveKeyRouter {
    /**
     * Routes one live keypad press between the out-of-band direct stop and the
     * normal core queue, and returns nothing because the consequence is the
     * side effect (swallow vs forward).
     *
     * Contract (the consumer half of the REPORT-23 direct-stop seam, which the
     * native [LiveProgramStopKeyPolicy] key-code check and the run-state gate
     * only cover in halves):
     *
     * - [queryDirectStopGate] is the side-effecting native publisher; it is
     *   consulted ONLY for stop-eligible keys (R/S, EXIT). Non-stop keys must
     *   never trigger it.
     * - If the key is stop-eligible AND the gate accepts (the program is busy),
     *   the key is consumed as a direct stop and is NOT forwarded.
     * - Otherwise the key is forwarded to the core via [forwardToCore]. This is
     *   the path that must survive while a program is parked in an interactive
     *   wait: a declined gate means R/S replots and EXIT leaves the f/g/I/O menu
     *   instead of being swallowed.
     */
    fun route(
        keyCode: Int,
        queryDirectStopGate: () -> Boolean,
        forwardToCore: (Int) -> Unit,
    ) {
        val consumedAsDirectStop =
            LiveProgramStopKeyPolicy.shouldPublishDirectStop(keyCode) && queryDirectStopGate()
        if (!consumedAsDirectStop) {
            forwardToCore(keyCode)
        }
    }
}
