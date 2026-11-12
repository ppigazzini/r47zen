package io.github.ppigazzini.r47zen

/**
 * Which Activity speaks for the calculator, which outlives each of them.
 *
 * Android starts the Activity coming back before it stops or destroys the one leaving, so the last
 * one started is the one in front. [isCurrent] tells a leaving Activity that it no longer speaks
 * for the calculator: its stop must not park the clock, and its end must not take the audio with
 * it, because another Activity is using both.
 *
 * A picker's answer goes only to the Activity that launched it, or to what Android recreates from
 * it, so that one owns the request.
 */
internal class CalculatorHostSlot<H : Any> {
    @Volatile
    var current: H? = null
        private set

    // The main thread only, as is everything that changes them.
    private var pickerOwner: H? = null
    private var pickerWaitsForRecreatedOwner = false

    fun attach(host: H) {
        current = host
        if (pickerWaitsForRecreatedOwner) {
            pickerOwner = host
            pickerWaitsForRecreatedOwner = false
        }
    }

    fun detach(host: H, recreating: Boolean) {
        if (current === host) {
            current = null
        }
        // The slot outlives every Activity, so one it still named would stay reachable,
        // its views with it, until the next picker.
        if (pickerOwner === host) {
            pickerOwner = null
            pickerWaitsForRecreatedOwner = recreating
        }
    }

    fun isCurrent(host: H): Boolean = current === host

    fun pickerLaunchedBy(host: H) {
        pickerOwner = host
        pickerWaitsForRecreatedOwner = false
    }

    fun ownsPicker(host: H): Boolean = pickerOwner === host
}
