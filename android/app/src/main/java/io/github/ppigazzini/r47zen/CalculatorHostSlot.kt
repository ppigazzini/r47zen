package io.github.ppigazzini.r47zen

/**
 * Which Activity speaks for the calculator, which outlives each of them.
 *
 * Two instances can run side by side, on a large screen or in desktop windowing, and Android
 * starts the Activity coming back before it stops or destroys the one leaving. So the slot keeps
 * every live Activity and those started, and the last one started speaks for the calculator.
 * One that stops while another is started neither parks the clock nor saves, since a window is
 * still in front; one destroyed hands the host to the last started, else the last live.
 *
 * A picker's answer goes only to the Activity that launched it, or to the one Android recreates
 * from it, which keeps its id ([idOf]): that id owns the request.
 */
internal class CalculatorHostSlot<H : Any>(private val idOf: (H) -> String) {
    @Volatile
    var current: H? = null
        private set

    // The main thread only, as is everything that changes them. Oldest first.
    private val live = mutableListOf<H>()
    private val started = mutableListOf<H>()
    private var pickerOwnerId: String? = null

    /** onStart: [host] is in front, and speaks for the calculator. */
    fun attach(host: H) {
        live.remove(host)
        live.add(host)
        started.remove(host)
        started.add(host)
        current = host
    }

    /** onStop. True when no window is started any more, so nothing of the app is in front. */
    fun stop(host: H): Boolean {
        started.remove(host)
        if (current === host && started.isNotEmpty()) {
            current = started.last()
        }
        return started.isEmpty()
    }

    /** onDestroy. The slot outlives every Activity, so it keeps none that was destroyed. */
    fun detach(host: H, recreating: Boolean) {
        live.remove(host)
        started.remove(host)
        if (current === host) {
            current = started.lastOrNull() ?: live.lastOrNull()
        }
        if (!recreating && pickerOwnerId == idOf(host)) {
            pickerOwnerId = null
        }
    }

    fun isCurrent(host: H): Boolean = current === host

    fun pickerLaunchedBy(host: H) {
        pickerOwnerId = idOf(host)
    }

    fun ownsPicker(host: H): Boolean = pickerOwnerId == idOf(host)
}
