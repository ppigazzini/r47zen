package io.github.ppigazzini.r47zen

/**
 * Coalesces LCD graph pan and pinch deltas into rate-limited re-solves on the
 * core thread.
 *
 * Each flush re-solves the graph (fnEqSolvGraph), a heavy upstream call. While a
 * flush is queued, further deltas coalesce in the accumulator instead of queuing
 * more tasks, so the net motion is never lost; flushes are spaced at least
 * [minFlushIntervalMs] apart; and each flush applies one batch, so a continuous
 * drag interleaves with the other queued core tasks instead of re-solving back
 * to back ahead of them.
 *
 * [addPan] and [addScale] run on the main thread, as do [post] and [postDelayed];
 * [applyBatch] runs on the core thread, inside the task [offerCoreTask] queues.
 */
internal class GraphGestureFlusher(
    private val accumulator: GraphGestureAccumulator,
    private val minFlushIntervalMs: Long,
    private val uptimeMillis: () -> Long,
    private val post: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val offerCoreTask: (Runnable) -> Unit,
    private val applyBatch: (GraphGestureBatch) -> Unit,
) {
    private val lock = Any()
    private var flushQueued = false

    @Volatile
    private var lastFlushUptimeMs = 0L

    fun addPan(dxNorm: Float, dyNorm: Float) {
        synchronized(lock) { accumulator.addPan(dxNorm, dyNorm) }
        scheduleFlush()
    }

    fun addScale(scaleFactor: Float) {
        synchronized(lock) { accumulator.addScale(scaleFactor) }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        val shouldEnqueue = synchronized(lock) {
            if (flushQueued) {
                false
            } else {
                flushQueued = true
                true
            }
        }
        if (!shouldEnqueue) {
            return
        }

        val sinceLast = uptimeMillis() - lastFlushUptimeMs
        if (sinceLast >= minFlushIntervalMs) {
            offerFlush()
        } else {
            postDelayed(Runnable(::offerFlush), minFlushIntervalMs - sinceLast)
        }
    }

    private fun offerFlush() {
        lastFlushUptimeMs = uptimeMillis()
        offerCoreTask(Runnable(::flushOnCoreThread))
    }

    private fun flushOnCoreThread() {
        val batch = synchronized(lock) { accumulator.drainBatch() }
        if (batch != null) {
            applyBatch(batch)
        }
        // Deltas that arrived during the re-solve wait for the next
        // rate-limited flush rather than re-solving here at once.
        val hasPending = synchronized(lock) {
            flushQueued = false
            accumulator.hasPending()
        }
        if (hasPending) {
            post(Runnable(::scheduleFlush))
        }
    }
}
