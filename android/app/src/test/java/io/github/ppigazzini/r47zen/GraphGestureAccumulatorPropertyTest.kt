package io.github.ppigazzini.r47zen

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Property-based coverage for the pure graph-gesture accumulator. The
// example-based GraphGestureAccumulatorTest pins specific
// boundary cases; these properties assert the clamp, split, drop, and
// termination invariants hold across a wide randomized input space the examples
// only sample at the endpoints. The random source is seeded so any failure
// reproduces deterministically. Pure Kotlin, so this runs on the plain JVM with
// no Robolectric.
class GraphGestureAccumulatorPropertyTest {
    @Test
    fun pan_clampsEveryAppliedStepAndKeepsBoundedTotalForArbitraryFiniteInput() {
        val rs = RandomSource.seeded(SEED)
        val finitePan = Arb.int(-2_000_000..2_000_000).map { it / 1000f } // [-2000f, 2000f]

        repeat(ITERATIONS) {
            val accumulator = createAccumulator()
            val pans = (0 until rs.random.nextInt(1, 16)).map {
                finitePan.sample(rs).value to finitePan.sample(rs).value
            }
            pans.forEach { (dx, dy) -> accumulator.addPan(dx, dy) }

            var totalDx = 0f
            var totalDy = 0f
            var drains = 0
            while (accumulator.hasPending()) {
                val batch = accumulator.drainBatch() ?: break
                assertTrue(
                    "applied pan step exceeds the per-apply limit: $batch (seed=$SEED, input=$pans)",
                    abs(batch.panDxNorm) <= PAN_APPLY_LIMIT + TOLERANCE &&
                        abs(batch.panDyNorm) <= PAN_APPLY_LIMIT + TOLERANCE,
                )
                totalDx += batch.panDxNorm
                totalDy += batch.panDyNorm
                drains++
                assertTrue(
                    "drain did not terminate within the bounded step budget " +
                        "(seed=$SEED, input=$pans)",
                    drains <= MAX_DRAINS,
                )
            }

            // The pending backlog is capped to panPendingLimit per axis, so the
            // total distance the accumulator can ever emit is bounded by it.
            assertTrue(
                "total applied pan exceeds the pending backlog cap: " +
                    "($totalDx, $totalDy) (seed=$SEED, input=$pans)",
                abs(totalDx) <= PAN_PENDING_LIMIT + TOLERANCE &&
                    abs(totalDy) <= PAN_PENDING_LIMIT + TOLERANCE,
            )
        }
    }

    @Test
    fun scale_drainedFactorStaysWithinClampOrIsNeutralForArbitraryInput() {
        val rs = RandomSource.seeded(SEED)
        // [-0.1f, 200.0f]: spans non-positive (dropped), in-range, and oversized
        // (clamped) factors.
        val scale = Arb.int(-100..200_000).map { it / 1000f }

        repeat(ITERATIONS) {
            val accumulator = createAccumulator()
            val factors = (0 until rs.random.nextInt(1, 12)).map { scale.sample(rs).value }
            factors.forEach { accumulator.addScale(it) }

            val batch = accumulator.drainBatch()
            if (batch != null) {
                val factor = batch.scaleFactor
                assertTrue(
                    "drained scale factor outside clamp and not neutral: " +
                        "$factor (seed=$SEED, input=$factors)",
                    factor == 1f || factor in SCALE_FACTOR_MIN..SCALE_FACTOR_MAX,
                )
            }
        }
    }

    @Test
    fun nonFiniteInputIsAlwaysDroppedAndNeverThrows() {
        val rs = RandomSource.seeded(SEED)
        val nonFinite = Arb.of(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )

        repeat(ITERATIONS) {
            val accumulator = createAccumulator()
            repeat(rs.random.nextInt(1, 8)) {
                accumulator.addPan(nonFinite.sample(rs).value, nonFinite.sample(rs).value)
                accumulator.addScale(nonFinite.sample(rs).value)
            }

            assertFalse("non-finite input left pending state (seed=$SEED)", accumulator.hasPending())
            assertNull("non-finite input produced a batch (seed=$SEED)", accumulator.drainBatch())
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
        const val TOLERANCE = 0.0005f
        const val PAN_FLUSH_EPSILON = 0.0005f
        const val PAN_APPLY_LIMIT = 1f
        const val PAN_PENDING_LIMIT = PAN_APPLY_LIMIT * 4f
        const val SCALE_FLUSH_EPSILON = 0.0001f
        const val SCALE_FACTOR_MIN = 0.4f
        const val SCALE_FACTOR_MAX = 2.5f

        // Pending is capped at panPendingLimit and each batch removes at least
        // panApplyLimit until the remainder falls below the flush epsilon, so a
        // full drain terminates well inside this budget. A wider value still
        // catches a regression that fails to make progress.
        const val MAX_DRAINS = 8
    }
}
