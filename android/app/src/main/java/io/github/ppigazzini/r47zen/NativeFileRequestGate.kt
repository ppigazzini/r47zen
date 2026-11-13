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
 * wrong file.
 *
 * Only an answer to the picker this gate launched reaches native. A process killed
 * behind the picker gets the answer in a fresh gate, and its new calculator thread may
 * already wait in native for a request [request] has not seen yet, so such an answer,
 * or one that comes while a request is only posted, never reaches native: its
 * descriptor closes and [onLost] tells the user, posted to the main thread whichever
 * thread the answer came on. So does an answer native refuses.
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

        // The ticket of a transfer this gate never launched a picker for: no request has it.
        const val NO_TICKET = -1L
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

    fun deliverSelected(fd: Int, name: String?) {
        answer(if (synchronized(lock) { endLaunched() }) ANSWER else LOST, fd, name)
    }

    fun deliverCanceled() {
        if (synchronized(lock) { endLaunched() }) {
            native.cancel()
        }
    }

    /** The load's private copy is on its way; its answer carries the returned ticket. */
    fun beginTransfer(): Long = synchronized(lock) {
        if (state == LAUNCHED) {
            state = TRANSFERRING
            ticket
        } else {
            NO_TICKET
        }
    }

    fun deliverTransferred(transfer: Long, fd: Int, name: String?) {
        answer(synchronized(lock) { endTransfer(transfer) }, fd, name)
    }

    fun deliverTransferFailed(transfer: Long) {
        if (synchronized(lock) { endTransfer(transfer) } == ANSWER) {
            native.cancel()
        }
    }

    fun cancelUnanswered() {
        val cancel = synchronized(lock) {
            (state == LAUNCHED || state == TRANSFERRING).also { if (it) state = IDLE }
        }
        if (cancel) {
            native.cancel()
        }
    }

    // Under the lock: whether the answer is the launched picker's, which ends the request.
    private fun endLaunched(): Boolean = (state == LAUNCHED).also { if (it) state = IDLE }

    // Under the lock: what a transfer's answer is. Its own request's, still in flight,
    // ends that request; one the app stopped was answered by its cancel, as was an older
    // request's; one this gate never launched is lost.
    private fun endTransfer(transfer: Long): Int = when {
        transfer != ticket -> if (transfer == NO_TICKET) LOST else LATE
        state == TRANSFERRING -> ANSWER.also { state = IDLE }
        else -> LATE
    }

    private fun answer(verdict: Int, fd: Int, name: String?) {
        if (verdict == ANSWER && native.select(fd, name)) {
            return
        }
        closeLateFd(fd)
        if (verdict != LATE) {
            post(Runnable(onLost))
        }
    }
}

// What becomes of a picker's answer: it goes to native, it is lost and the user hears
// so, or it is late for a request a cancel already answered.
private const val ANSWER = 0
private const val LOST = 1
private const val LATE = 2
