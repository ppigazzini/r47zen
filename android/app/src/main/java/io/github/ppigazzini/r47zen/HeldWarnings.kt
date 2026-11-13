package io.github.ppigazzini.r47zen

/**
 * Core warnings that come while no Activity is the calculator's host, which wait for the
 * next one. The calculator outlives every Activity, so a program can run on, and warn,
 * with none: only the latest [capacity] wait. The main thread only.
 */
internal class HeldWarnings(private val capacity: Int) {
    private val held = ArrayDeque<String>()

    fun hold(text: String) {
        if (held.size == capacity) {
            held.removeFirst()
        }
        held.addLast(text)
    }

    /** Shows each held warning once, oldest first. */
    fun showTo(show: (String) -> Unit) {
        while (held.isNotEmpty()) {
            show(held.removeFirst())
        }
    }
}
