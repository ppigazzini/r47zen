package io.github.ppigazzini.r47zen

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.triple
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Property-based coverage for the pure graph-gesture accumulator. The
// example-based GraphGestureAccumulatorTest pins specific
// boundary cases; these properties assert the clamp, split, drop, and
// termination invariants hold across a wide randomized input space the examples
// only sample at the endpoints. Kotest's checkAll runs each property from a
// fixed seed, so a failure reproduces, and shrinks a failing input to the
// smallest one that still fails before reporting it. Pure Kotlin, so this runs
// on the plain JVM with no Robolectric.
class GraphGestureAccumulatorPropertyTest {
    @Test
    fun pan_clampsEveryAppliedStepAndKeepsBoundedTotalForArbitraryFiniteInput() {
        val finitePan = Arb.int(-2_000_000..2_000_000).map { it / 1000f } // [-2000f, 2000f]

        runBlocking {
            checkAll(CONFIG, Arb.list(Arb.pair(finitePan, finitePan), 1..15)) { pans ->
                val accumulator = createAccumulator()
                pans.forEach { (dx, dy) -> accumulator.addPan(dx, dy) }

                var totalDx = 0f
                var totalDy = 0f
                var drains = 0
                while (accumulator.hasPending()) {
                    val batch = accumulator.drainBatch() ?: break
                    assertTrue(
                        "applied pan step exceeds the per-apply limit: $batch",
                        abs(batch.panDxNorm) <= PAN_APPLY_LIMIT + TOLERANCE &&
                            abs(batch.panDyNorm) <= PAN_APPLY_LIMIT + TOLERANCE,
                    )
                    totalDx += batch.panDxNorm
                    totalDy += batch.panDyNorm
                    drains++
                    assertTrue("drain did not terminate within the bounded step budget", drains <= MAX_DRAINS)
                }

                // The pending backlog is capped to panPendingLimit per axis, so the
                // total distance the accumulator can ever emit is bounded by it.
                assertTrue(
                    "total applied pan exceeds the pending backlog cap: ($totalDx, $totalDy)",
                    abs(totalDx) <= PAN_PENDING_LIMIT + TOLERANCE &&
                        abs(totalDy) <= PAN_PENDING_LIMIT + TOLERANCE,
                )
            }
        }
    }

    @Test
    fun scale_drainedFactorStaysWithinClampOrIsNeutralForArbitraryInput() {
        // [-0.1f, 200.0f]: spans non-positive (dropped), in-range, and oversized
        // (clamped) factors.
        val scale = Arb.int(-100..200_000).map { it / 1000f }

        runBlocking {
            checkAll(CONFIG, Arb.list(scale, 1..11)) { factors ->
                val accumulator = createAccumulator()
                factors.forEach { accumulator.addScale(it) }

                val batch = accumulator.drainBatch()
                if (batch != null) {
                    val factor = batch.scaleFactor
                    assertTrue(
                        "drained scale factor outside clamp and not neutral: $factor",
                        factor == 1f || factor in SCALE_FACTOR_MIN..SCALE_FACTOR_MAX,
                    )
                }
            }
        }
    }

    @Test
    fun scale_inRangeFactorsDrainAsTheirProduct() {
        // [0.8, 1.25]: up to three of them multiply to [0.512, 1.953], inside
        // the clamp, so the drained factor must be the plain product. The clamp
        // test above accepts any in-range value, including a pinch that was
        // pinned or dropped; this is the oracle for the value itself.
        val factor = Arb.int(800..1250).map { it / 1000f }

        runBlocking {
            checkAll(CONFIG, Arb.list(factor, 1..3)) { factors ->
                val accumulator = createAccumulator()
                factors.forEach { accumulator.addScale(it) }
                val product = factors.fold(1f) { total, next -> total * next }

                val batch = accumulator.drainBatch()
                if (abs(product - 1f) <= SCALE_FLUSH_EPSILON) {
                    assertNull("a neutral pinch produced a batch", batch)
                } else {
                    assertNotNull("an in-range pinch was dropped", batch)
                    assertEquals(
                        "drained pinch is not the product of its factors",
                        product,
                        batch!!.scaleFactor,
                        PRODUCT_TOLERANCE,
                    )
                }
                assertFalse("a drained pinch left pending state", accumulator.hasPending())
            }
        }
    }

    @Test
    fun nonFiniteInputIsAlwaysDroppedAndNeverThrows() {
        val nonFinite = Arb.of(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )

        runBlocking {
            checkAll(CONFIG, Arb.list(Arb.triple(nonFinite, nonFinite, nonFinite), 1..7)) { inputs ->
                val accumulator = createAccumulator()
                inputs.forEach { (dx, dy, scale) ->
                    accumulator.addPan(dx, dy)
                    accumulator.addScale(scale)
                }

                assertFalse("non-finite input left pending state", accumulator.hasPending())
                assertNull("non-finite input produced a batch", accumulator.drainBatch())
            }
        }
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
        const val SEED = 0x5234375AL // "R47Z"
        const val ITERATIONS = 2000
        val CONFIG = PropTestConfig(seed = SEED, iterations = ITERATIONS)
        const val TOLERANCE = 0.0005f
        const val PAN_FLUSH_EPSILON = 0.0005f
        const val PAN_APPLY_LIMIT = 1f
        const val PAN_PENDING_LIMIT = PAN_APPLY_LIMIT * 4f
        const val SCALE_FLUSH_EPSILON = 0.0001f
        const val SCALE_FACTOR_MIN = 0.4f
        const val SCALE_FACTOR_MAX = 2.5f
        const val PRODUCT_TOLERANCE = 1e-5f

        // Pending is capped at panPendingLimit and each batch removes at least
        // panApplyLimit until the remainder falls below the flush epsilon, so a
        // full drain terminates well inside this budget. A wider value still
        // catches a regression that fails to make progress.
        const val MAX_DRAINS = 8
    }
}
