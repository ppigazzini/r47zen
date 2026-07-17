package io.github.ppigazzini.r47zen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the LCD pinch-significance contract that the ReplicaOverlay scale
 * gesture listener depends on: a pinch only drives the LCD zoom when its scale
 * factor is finite and differs from 1 by at least PINCH_EPSILON. isSignificantPinch decomposes it into a pure,
 * testable predicate.
 */
class GraphTouchVisualPolicyTest {
    @Test
    fun significantPinch_acceptsFiniteFactorsBeyondEpsilon() {
        assertTrue(GraphTouchVisualPolicy.isSignificantPinch(1.5f))
        assertTrue(GraphTouchVisualPolicy.isSignificantPinch(0.5f))
        assertTrue(
            GraphTouchVisualPolicy.isSignificantPinch(1f + GraphTouchVisualPolicy.PINCH_EPSILON * 2f),
        )
        assertTrue(
            GraphTouchVisualPolicy.isSignificantPinch(1f - GraphTouchVisualPolicy.PINCH_EPSILON * 2f),
        )
    }

    @Test
    fun significantPinch_rejectsNoOpFactorsWithinEpsilon() {
        assertFalse(GraphTouchVisualPolicy.isSignificantPinch(1f))
        assertFalse(
            GraphTouchVisualPolicy.isSignificantPinch(1f + GraphTouchVisualPolicy.PINCH_EPSILON / 2f),
        )
        assertFalse(
            GraphTouchVisualPolicy.isSignificantPinch(1f - GraphTouchVisualPolicy.PINCH_EPSILON / 2f),
        )
    }

    @Test
    fun significantPinch_rejectsNonFiniteFactors() {
        assertFalse(GraphTouchVisualPolicy.isSignificantPinch(Float.NaN))
        assertFalse(GraphTouchVisualPolicy.isSignificantPinch(Float.POSITIVE_INFINITY))
        assertFalse(GraphTouchVisualPolicy.isSignificantPinch(Float.NEGATIVE_INFINITY))
    }
}
