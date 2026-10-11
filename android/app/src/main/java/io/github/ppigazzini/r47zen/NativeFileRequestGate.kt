package io.github.ppigazzini.r47zen

import java.util.concurrent.atomic.AtomicInteger

/**
 * Hand a core-thread file request to the main thread, and its answer back,
 * exactly once.
 *
 * Native `requestAndroidFile` parks the core thread until a file result or a
 * cancel arrives, so every request must end in exactly one of the two. The
 * request reaches the main thread as a posted callback, and
 * `MainActivity.onDestroy` drops every pending callback: [cancelPending] cancels
 * a request whose launch never ran. A launched picker is answered through
 * [deliverSelected] or [deliverCancelled]; when the app stops before it answers,
 * the result never arrives, and [cancelUnanswered] cancels instead. A request
 * posted after `onDestroy` needs no help: its launcher is already unregistered,
 * the launch throws, and [StorageAccessCoordinator.requestNativeFile] cancels.
 */
internal class NativeFileRequestGate(
    private val post: (Runnable) -> Unit,
    private val launch: (isSave: Boolean, defaultName: String, fileType: Int) -> Unit,
    private val selectNative: (Int) -> Unit,
    private val cancelNative: () -> Unit,
) {
    private companion object {
        const val IDLE = 0
        const val POSTED = 1
        const val LAUNCHED = 2
    }

    private val state = AtomicInteger(IDLE)

    fun request(isSave: Boolean, defaultName: String, fileType: Int) {
        state.set(POSTED)
        post(
            Runnable {
                if (state.compareAndSet(POSTED, LAUNCHED)) {
                    launch(isSave, defaultName, fileType)
                }
            },
        )
    }

    // Forward the picker's answer whatever this gate's state: after a
    // recreation the answer reaches the new Activity's gate, which never
    // launched, while the core thread still waits for it.
    fun deliverSelected(fd: Int) {
        state.set(IDLE)
        selectNative(fd)
    }

    fun deliverCancelled() {
        state.set(IDLE)
        cancelNative()
    }

    fun cancelPending() {
        if (state.compareAndSet(POSTED, IDLE)) {
            cancelNative()
        }
    }

    fun cancelUnanswered() {
        if (state.compareAndSet(LAUNCHED, IDLE)) {
            cancelNative()
        }
    }
}
