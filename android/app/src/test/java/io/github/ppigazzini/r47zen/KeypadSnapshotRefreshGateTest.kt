package io.github.ppigazzini.r47zen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// KeypadSnapshotRefreshGate is pure Kotlin, so this runs on the plain JVM.
class KeypadSnapshotRefreshGateTest {
    @Test
    fun refreshGateSkipsAnUnchangedSnapshotUntilReset() {
        val gate = KeypadSnapshotRefreshGate()
        val snapshot = keypadSnapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                isEnabled = true,
            ),
        )

        assertTrue(gate.shouldApply(snapshot))
        assertFalse(gate.shouldApply(snapshot))

        gate.reset()

        assertTrue(gate.shouldApply(snapshot))
    }
}
