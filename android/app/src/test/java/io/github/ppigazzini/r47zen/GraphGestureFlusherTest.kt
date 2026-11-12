package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphGestureFlusherTest {

    /** A single-threaded stand-in for the main looper. */
    private class Harness(minFlushIntervalMs: Long = 16L) {
        var now = 1_000L
        val delayedTasks = mutableListOf<Pair<Long, Runnable>>()
        val applied = mutableListOf<GraphGestureBatch>()

        val flusher = GraphGestureFlusher(
            accumulator = GraphGestureAccumulator(
                panFlushEpsilon = 0.0005f,
                panApplyLimit = 1f,
                panPendingLimit = 4f,
                scaleFlushEpsilon = 0.0001f,
                scaleFactorMin = 0.4f,
                scaleFactorMax = 2.5f,
            ),
            minFlushIntervalMs = minFlushIntervalMs,
            uptimeMillis = { now },
            postDelayed = { task, delayMs -> delayedTasks += (now + delayMs) to task },
            applyBatch = { batch -> applied += batch },
        )

        /** Advance the clock to the next delayed task and run it. */
        fun runNextDelayedTask() {
            val next = delayedTasks.minByOrNull { it.first }!!
            delayedTasks.remove(next)
            now = maxOf(now, next.first)
            next.second.run()
        }
    }

    @Test
    fun panPastTheApplyLimit_arrivesWithNoFurtherTouch() {
        val h = Harness()

        h.flusher.addPan(1.5f, 0f)
        while (h.delayedTasks.isNotEmpty()) {
            h.runNextDelayedTask()
        }

        assertEquals(1.5f, h.applied.sumOf { it.panDxNorm.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun aFirstDelta_flushesAtOnce() {
        val h = Harness()

        h.flusher.addPan(0.1f, 0f)

        assertEquals(1, h.applied.size)
        assertEquals(0.1f, h.applied.single().panDxNorm, 1e-6f)
    }

    @Test
    fun deltasInsideTheInterval_coalesceIntoOneBatch() {
        val h = Harness()
        h.flusher.addPan(0.01f, 0f)
        h.now += 5

        h.flusher.addPan(0.1f, 0f)
        h.flusher.addPan(0.2f, 0f)
        h.flusher.addScale(1.5f)

        assertEquals("one flush so far", 1, h.applied.size)
        assertEquals("one flush waiting", 1, h.delayedTasks.size)
        h.runNextDelayedTask()
        assertEquals(2, h.applied.size)
        assertEquals(0.3f, h.applied[1].panDxNorm, 1e-6f)
        assertEquals(1.5f, h.applied[1].scaleFactor, 1e-6f)
    }

    @Test
    fun flushesAreSpacedByTheMinimumInterval() {
        val h = Harness(minFlushIntervalMs = 16L)

        h.flusher.addPan(0.1f, 0f)
        val firstFlushAt = h.now
        h.now += 5
        h.flusher.addPan(0.1f, 0f)

        assertEquals("no second flush before the interval elapses", 1, h.applied.size)
        assertEquals(firstFlushAt + 16L, h.delayedTasks.single().first)
    }

    @Test
    fun noMotionIsLostAcrossFlushes() {
        val h = Harness()
        var total = 0f
        repeat(10) { step ->
            h.flusher.addPan(0.01f * (step + 1), 0f)
            total += 0.01f * (step + 1)
            h.now += 3
            while (h.delayedTasks.isNotEmpty() && h.delayedTasks.minOf { it.first } <= h.now) {
                h.runNextDelayedTask()
            }
        }
        while (h.delayedTasks.isNotEmpty()) {
            h.runNextDelayedTask()
        }

        assertEquals(total, h.applied.sumOf { it.panDxNorm.toDouble() }.toFloat(), 1e-5f)
    }
}
