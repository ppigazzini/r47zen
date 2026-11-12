package io.github.ppigazzini.r47zen

import android.net.Uri
import android.os.ParcelFileDescriptor

/** The calculator thread's end of a file request, which waits in native. */
internal interface NativeFileAnswers {
    /** Hand the waiting request its file; false when no request waits for one. */
    fun select(fd: Int, name: String?): Boolean

    fun cancel()
}

/**
 * Hand a calculator-thread file request to the main thread, and its answer back,
 * exactly once.
 *
 * Native `requestAndroidFile` parks the calculator thread until a file result or a
 * cancel arrives, so every request must end in exactly one of the two. The request
 * comes on that thread, which waits for the answer anyway, so the provider calls that
 * find where the picker starts run there, and the main thread only launches it. It lives as long as
 * the thread, in [CalculatorEngine], so a posted launch always runs: with no Activity to
 * open a picker it cancels. A launched picker is answered through [deliverSelected] or
 * [deliverCanceled]; when the app stops before it answers, the result never arrives, and
 * [cancelUnanswered] cancels instead.
 *
 * A picked document to load reaches native later, from the transfer worker, so
 * [beginTransfer] marks it in flight and gives the request's ticket, which the
 * transfer's answer carries back. If the app stops first, [cancelUnanswered] cancels
 * the request, and the late descriptor is closed: an answer whose ticket is not the
 * waiting request's never reaches native, so it cannot answer a newer request with the
 * wrong file. A process killed behind the picker has no request either: [native]
 * refuses the answer, its descriptor closes the same way, and [onLost] tells the user,
 * posted to the main thread whichever thread the answer came on.
 */
internal class NativeFileRequestGate(
    private val post: (Runnable) -> Unit,
    private val findStart: (fileType: Int, isSave: Boolean) -> Uri?,
    private val launch: (isSave: Boolean, defaultName: String, start: Uri?) -> Unit,
    private val native: NativeFileAnswers,
    private val onLost: () -> Unit = {},
    private val closeLateFd: (Int) -> Unit = { fd -> ParcelFileDescriptor.adoptFd(fd).close() },
) {
    private companion object {
        const val IDLE = 0
        const val POSTED = 1
        const val LAUNCHED = 2
        const val TRANSFERRING = 3
        const val ABANDONED = 4
    }

    // The calculator thread, the main thread, and the transfer worker all change these,
    // so every change is under the lock; no call leaves the gate while it is held.
    private val lock = Any()
    private var state = IDLE
    private var ticket = 0L

    fun request(isSave: Boolean, defaultName: String, fileType: Int) {
        synchronized(lock) {
            ticket++
            state = POSTED
        }
        // A lookup that fails must still end in a launch, or nothing answers the request.
        val start = runCatching { findStart(fileType, isSave) }.getOrNull()
        post(
            Runnable {
                val launching = synchronized(lock) {
                    (state == POSTED).also { if (it) state = LAUNCHED }
                }
                if (launching) {
                    launch(isSave, defaultName, start)
                }
            },
        )
    }

    // Forward the picker's answer whatever this gate's state but abandoned: the
    // calculator thread is what waits for it.
    fun deliverSelected(fd: Int, name: String?) {
        answer(synchronized(lock) { end() }, fd, name)
    }

    fun deliverCanceled() {
        if (synchronized(lock) { end() }) {
            native.cancel()
        }
    }

    /** The load's private copy is on its way; its answer carries the returned ticket. */
    fun beginTransfer(): Long = synchronized(lock) {
        state = TRANSFERRING
        ticket
    }

    fun deliverTransferred(transfer: Long, fd: Int, name: String?) {
        answer(synchronized(lock) { transfer == ticket && end() }, fd, name)
    }

    fun deliverTransferFailed(transfer: Long) {
        if (synchronized(lock) { transfer == ticket && end() }) {
            native.cancel()
        }
    }

    fun cancelUnanswered() {
        val cancel = synchronized(lock) {
            when (state) {
                LAUNCHED -> true.also { state = IDLE }
                TRANSFERRING -> true.also { state = ABANDONED }
                else -> false
            }
        }
        if (cancel) {
            native.cancel()
        }
    }

    // Under the lock: the request ends. False when it was abandoned, since its cancel
    // already answered the calculator thread.
    private fun end(): Boolean {
        val was = state
        state = IDLE
        return was != ABANDONED
    }

    private fun answer(current: Boolean, fd: Int, name: String?) {
        if (current && native.select(fd, name)) {
            return
        }
        closeLateFd(fd)
        if (current) {
            post(Runnable(onLost))
        }
    }
}
