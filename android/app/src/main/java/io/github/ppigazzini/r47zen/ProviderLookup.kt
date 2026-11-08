package io.github.ppigazzini.r47zen

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The one thread for the short provider calls a screen waits on: whether the work
 * directory can still be reached, whether the saved manual can. A provider is another
 * app, and a binder call to one can block for as long as it takes to answer, so none
 * runs on the main thread, where it would hold every touch and frame behind it.
 */
internal object ProviderLookup {
    private val main = Handler(Looper.getMainLooper())

    @VisibleForTesting
    internal var worker: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "R47ProviderLookup").apply { isDaemon = true }
    }

    /**
     * Run [lookup] on the worker, then hand its answer to [then] on the main thread. A
     * lookup answers its provider's failures itself, as each caller's does: one that
     * throws gives [then] nothing.
     */
    fun <T> run(lookup: () -> T, then: (T) -> Unit) {
        worker.execute {
            val answer = lookup()
            main.post { then(answer) }
        }
    }
}
