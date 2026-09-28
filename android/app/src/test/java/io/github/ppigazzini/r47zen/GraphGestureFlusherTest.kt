package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphGestureFlusherTest {

    /** A single-threaded stand-in for the main looper and the core queue. */
    private class Harness(minFlushIntervalMs: Long = 16L) {
        var now = 1_000L
        val coreTasks = ArrayDeque<Runnable>()
        val mainTasks = mutableListOf<Pair<Long, Runnable>>()
        val applied = mutableListOf<GraphGestureBatch>()
        var onApply: () -> Unit = {}

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
            post = { mainTasks += now to it },
            postDelayed = { task, delayMs -> mainTasks += (now + delayMs) to task },
            offerCoreTask = { coreTasks.addLast(it) },
            applyBatch = { batch ->
                applied += batch
                onApply()
            },
        )

        fun runCoreTask() = coreTasks.removeFirst().run()

        /** Advance the clock to the next main-thread task and run it. */
        fun runNextMainTask() {
            val next = mainTasks.minByOrNull { it.first }!!
            mainTasks.remove(next)
            now = maxOf(now, next.first)
            next.second.run()
        }
    }

    @Test
    fun deltasWhileAFlushIsQueued_coalesceIntoOneTask() {
        val h = Harness()

        h.flusher.addPan(0.1f, 0f)
        h.flusher.addPan(0.2f, 0f)
        h.flusher.addScale(1.5f)

        assertEquals(1, h.coreTasks.size)
        h.runCoreTask()
        assertEquals(1, h.applied.size)
        assertEquals(0.3f, h.applied.single().panDxNorm, 1e-6f)
        assertEquals(1.5f, h.applied.single().scaleFactor, 1e-6f)
    }

    @Test
    fun deltasDuringAReSolve_waitForTheNextFlushInsteadOfLooping() {
        val h = Harness()
        var injected = false
        // A drag keeps moving while the core thread re-solves.
        h.onApply = {
            if (!injected) {
                injected = true
                h.flusher.addPan(0.05f, 0f)
            }
        }

        h.flusher.addPan(0.1f, 0f)
        h.runCoreTask()

        assertEquals("one flush task applies one batch", 1, h.applied.size)
        assertEquals("the remainder goes back through the main-thread scheduler", 1, h.mainTasks.size)
        assertEquals(0, h.coreTasks.size)

        h.runNextMainTask()
        h.runNextMainTask()
        assertEquals(1, h.coreTasks.size)
        h.runCoreTask()
        assertEquals(2, h.applied.size)
        assertEquals(0.05f, h.applied[1].panDxNorm, 1e-6f)
    }

    @Test
    fun flushesAreSpacedByTheMinimumInterval() {
        val h = Harness(minFlushIntervalMs = 16L)

        h.flusher.addPan(0.1f, 0f)
        val firstOfferAt = h.now
        h.runCoreTask()
        h.now += 5
        h.flusher.addPan(0.1f, 0f)

        assertEquals("no core task before the interval elapses", 0, h.coreTasks.size)
        assertEquals(firstOfferAt + 16L, h.mainTasks.single().first)
        h.runNextMainTask()
        assertEquals(1, h.coreTasks.size)
    }

    @Test
    fun noMotionIsLostAcrossFlushes() {
        val h = Harness()
        var total = 0f
        repeat(10) { step ->
            h.flusher.addPan(0.01f * (step + 1), 0f)
            total += 0.01f * (step + 1)
            h.now += 3
            while (h.mainTasks.isNotEmpty() && h.mainTasks.minOf { it.first } <= h.now) {
                h.runNextMainTask()
            }
            while (h.coreTasks.isNotEmpty()) {
                h.runCoreTask()
            }
        }
        while (h.mainTasks.isNotEmpty() || h.coreTasks.isNotEmpty()) {
            if (h.coreTasks.isNotEmpty()) h.runCoreTask() else h.runNextMainTask()
        }

        assertEquals(total, h.applied.sumOf { it.panDxNorm.toDouble() }.toFloat(), 1e-5f)
    }
}
