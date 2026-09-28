package io.github.ppigazzini.r47zen

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hand a core-thread file request to the main thread exactly once.
 *
 * Native `requestAndroidFile` parks the core thread until a file result or a
 * cancel arrives. The request reaches the main thread as a posted callback, and
 * `MainActivity.onDestroy` drops every pending callback, so a request posted
 * just before a recreation would never launch and never cancel: the core thread
 * would wait for the life of the process. Either the posted launch runs or
 * [cancelPending] cancels it, never both and never neither. A request posted
 * after `onDestroy` needs no help: its launcher is already unregistered, the
 * launch throws, and [StorageAccessCoordinator.requestNativeFile] cancels.
 */
internal class NativeFileRequestGate(
    private val post: (Runnable) -> Unit,
    private val launch: (isSave: Boolean, defaultName: String, fileType: Int) -> Unit,
    private val cancelNative: () -> Unit,
) {
    private val pending = AtomicBoolean(false)

    fun request(isSave: Boolean, defaultName: String, fileType: Int) {
        pending.set(true)
        post(
            Runnable {
                if (pending.compareAndSet(true, false)) {
                    launch(isSave, defaultName, fileType)
                }
            },
        )
    }

    fun cancelPending() {
        if (pending.compareAndSet(true, false)) {
            cancelNative()
        }
    }
}
