package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GraphGestureAccumulatorTest {
    @Test
    fun drainBatch_splitsLargePanIntoBoundedStepsWithoutDroppingDistance() {
        val accumulator = createAccumulator()
        accumulator.addPan(dxNorm = 2.25f, dyNorm = -1.25f)

        val batches = mutableListOf<GraphGestureBatch>()
        while (accumulator.hasPending()) {
            batches += requireNotNull(accumulator.drainBatch())
        }

        assertEquals(3, batches.size)
        assertTrue(batches.all { abs(it.panDxNorm) <= PAN_APPLY_LIMIT })
        assertTrue(batches.all { abs(it.panDyNorm) <= PAN_APPLY_LIMIT })
        assertEquals(2.25f, batches.sumOf { it.panDxNorm.toDouble() }.toFloat(), 0.0001f)
        assertEquals(-1.25f, batches.sumOf { it.panDyNorm.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun addPan_capsPendingBacklogToRecentBoundedRange() {
        val accumulator = createAccumulator()
        accumulator.addPan(dxNorm = 9f, dyNorm = -9f)

        val batches = mutableListOf<GraphGestureBatch>()
        while (accumulator.hasPending()) {
            batches += requireNotNull(accumulator.drainBatch())
        }

        assertEquals(4, batches.size)
        assertTrue(batches.all { abs(it.panDxNorm) <= PAN_APPLY_LIMIT })
        assertTrue(batches.all { abs(it.panDyNorm) <= PAN_APPLY_LIMIT })
        assertEquals(PAN_PENDING_LIMIT, batches.sumOf { it.panDxNorm.toDouble() }.toFloat(), 0.0001f)
        assertEquals(-PAN_PENDING_LIMIT, batches.sumOf { it.panDyNorm.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun addPan_dropsNonFiniteInputBeforeItCanReachNativeFlush() {
        val accumulator = createAccumulator()

        accumulator.addPan(dxNorm = Float.NaN, dyNorm = 0.1f)
        accumulator.addPan(dxNorm = Float.POSITIVE_INFINITY, dyNorm = 0.1f)
        accumulator.addPan(dxNorm = 0.1f, dyNorm = Float.NEGATIVE_INFINITY)

        assertFalse(accumulator.hasPending())
        assertEquals(null, accumulator.drainBatch())
    }

    @Test
    fun addScale_clampsPendingScaleAndDrainsOnce() {
        val accumulator = createAccumulator()

        accumulator.addScale(10f)

        val batch = requireNotNull(accumulator.drainBatch())
        assertEquals(2.5f, batch.scaleFactor, 0.0001f)
        assertFalse(accumulator.hasPending())
    }

    private fun createAccumulator(): GraphGestureAccumulator {
        return GraphGestureAccumulator(
            panFlushEpsilon = PAN_FLUSH_EPSILON,
            panApplyLimit = PAN_APPLY_LIMIT,
            panPendingLimit = PAN_PENDING_LIMIT,
            scaleFlushEpsilon = SCALE_FLUSH_EPSILON,
            scaleFactorMin = SCALE_FACTOR_MIN,
            scaleFactorMax = SCALE_FACTOR_MAX,
        )
    }

    private companion object {
        private const val PAN_FLUSH_EPSILON = 0.0005f
        private const val PAN_APPLY_LIMIT = 1f
        private const val PAN_PENDING_LIMIT = PAN_APPLY_LIMIT * 4f
        private const val SCALE_FLUSH_EPSILON = 0.0001f
        private const val SCALE_FACTOR_MIN = 0.4f
        private const val SCALE_FACTOR_MAX = 2.5f
    }
}
