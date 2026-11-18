package io.github.ppigazzini.r47zen

/**
 * Coalesces LCD graph pan and pinch deltas into rate-limited requests to the calculator.
 *
 * Each request re-solves the graph (fnEqSolvGraph), a heavy upstream call. Between flushes,
 * further deltas coalesce in the accumulator, so the net motion is never lost, and flushes
 * are spaced at least [minFlushIntervalMs] apart.
 *
 * [addPan] and [addScale] run on the main thread, as does [postDelayed];
 * [applyBatch] pushes to the calculator, which merges batches while it is busy.
 */
internal class GraphGestureFlusher(
    private val accumulator: GraphGestureAccumulator,
    private val minFlushIntervalMs: Long,
    private val uptimeMillis: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
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
        var morePending = false
        val batch = synchronized(lock) {
            flushQueued = false
            accumulator.drainBatch().also { morePending = accumulator.hasPending() }
        }
        if (batch != null) {
            applyBatch(batch)
        }
        // A flush applies at most one LCD width of pan, and no later touch may come for the rest.
        if (morePending) {
            scheduleFlush()
        }
    }
}
