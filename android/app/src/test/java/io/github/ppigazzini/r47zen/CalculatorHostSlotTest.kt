package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorHostSlotTest {
    private val first = Any()
    private val second = Any()
    private val slot = CalculatorHostSlot<Any>()

    @Test
    fun aSecondInstanceFinishing_leavesTheFirstAsHost() {
        slot.attach(first)
        slot.attach(second)

        // Back from the second: Android starts the first, then destroys the second.
        slot.attach(first)
        assertFalse(slot.isCurrent(second))
        slot.detach(second, recreating = false)

        assertEquals(first, slot.current)
        assertTrue(slot.isCurrent(first))
    }

    @Test
    fun theLastInstanceFinishing_leavesNoHost() {
        slot.attach(first)
        slot.detach(first, recreating = false)

        assertNull(slot.current)
    }

    @Test
    fun aSecondInstanceFinishing_doesNotOwnThePickerTheFirstOpened() {
        slot.attach(first)
        slot.pickerLaunchedBy(first)
        slot.attach(second)

        assertFalse(slot.ownsPicker(second))
        slot.detach(second, recreating = false)
        assertTrue(slot.ownsPicker(first))
    }

    @Test
    fun aRecreatedActivity_takesOverThePicker_becauseItGetsTheAnswer() {
        slot.attach(first)
        slot.pickerLaunchedBy(first)

        slot.detach(first, recreating = true)
        slot.attach(second)

        assertTrue(slot.ownsPicker(second))
        assertFalse(slot.ownsPicker(first))
    }

    @Test
    fun aFinishingActivity_keepsNoClaimOnASuccessor() {
        slot.attach(first)
        slot.pickerLaunchedBy(first)

        slot.detach(first, recreating = false)
        slot.attach(second)

        assertFalse(slot.ownsPicker(second))
    }

    @Test
    fun aNewPicker_replacesTheOwnerARecreationLeftBehind() {
        slot.attach(first)
        slot.pickerLaunchedBy(first)
        slot.detach(first, recreating = true)
        slot.pickerLaunchedBy(second)
        slot.attach(first)

        assertTrue(slot.ownsPicker(second))
        assertFalse(slot.ownsPicker(first))
    }
}
