package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorHostSlotTest {
    // An Activity, and the id it keeps across a recreation.
    private class Host(val id: String)

    private val first = Host("first")
    private val second = Host("second")
    private val slot = CalculatorHostSlot<Host> { it.id }

    @Test
    fun aSecondInstanceFinishing_leavesTheFirstAsHost() {
        slot.attach(first)
        slot.attach(second)

        // Back from the second: Android starts the first, then destroys the second.
        slot.attach(first)
        assertFalse(slot.isCurrent(second))
        assertFalse(slot.stop(second))
        slot.detach(second, recreating = false)

        assertEquals(first, slot.current)
        assertTrue(slot.isCurrent(first))
    }

    // Two windows side by side, on a large screen or in desktop windowing: the one
    // closed stops, and the other, still on screen, keeps the clock and the host.
    @Test
    fun aWindowClosedBesideAnother_parksNothing_andLeavesTheOtherAsHost() {
        slot.attach(first)
        slot.attach(second)

        assertFalse(slot.stop(second))
        slot.detach(second, recreating = false)

        assertEquals(first, slot.current)
    }

    @Test
    fun onlyTheLastWindowToStop_leavesNoneInFront() {
        slot.attach(first)
        slot.attach(second)

        assertFalse(slot.stop(first))
        assertTrue(slot.stop(second))
    }

    @Test
    fun aDestroyedHost_fallsBackToTheLastStartedOne_beforeAStoppedOne() {
        slot.attach(first)
        slot.attach(second)
        val third = Host("third")
        slot.attach(third)
        slot.stop(second)

        slot.detach(third, recreating = false)

        assertEquals(first, slot.current)
    }

    @Test
    fun aDestroyedHost_fallsBackToALiveStoppedOne_whenNoneIsStarted() {
        slot.attach(first)
        slot.attach(second)
        slot.stop(first)
        slot.stop(second)

        slot.detach(second, recreating = false)

        assertEquals(first, slot.current)
    }

    @Test
    fun theLastInstanceFinishing_leavesNoHost() {
        slot.attach(first)
        slot.stop(first)
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

    // Android delivers a picker's answer to the Activity it recreates from the one that
    // launched the picker, which keeps that one's id.
    @Test
    fun aRecreatedActivity_keepsThePicker_becauseItGetsTheAnswer() {
        slot.attach(first)
        slot.pickerLaunchedBy(first)

        slot.detach(first, recreating = true)
        val recreated = Host(first.id)
        slot.attach(recreated)

        assertTrue(slot.ownsPicker(recreated))
    }

    @Test
    fun anotherWindowStartedDuringARecreation_doesNotTakeThePicker() {
        slot.attach(first)
        slot.attach(second)
        slot.attach(first)
        slot.pickerLaunchedBy(first)

        slot.detach(first, recreating = true)
        slot.attach(second)
        val recreated = Host(first.id)
        slot.attach(recreated)

        assertFalse(slot.ownsPicker(second))
        assertTrue(slot.ownsPicker(recreated))
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
    fun aFinishingActivity_isNoLongerThePickerOwner() {
        slot.attach(first)
        slot.pickerLaunchedBy(first)

        slot.detach(first, recreating = false)

        assertFalse(slot.ownsPicker(first))
    }

    @Test
    fun aNewPicker_replacesTheOwnerARecreationLeftBehind() {
        slot.attach(first)
        slot.pickerLaunchedBy(first)
        slot.detach(first, recreating = true)
        slot.pickerLaunchedBy(second)
        slot.attach(Host(first.id))

        assertTrue(slot.ownsPicker(second))
        assertFalse(slot.ownsPicker(first))
    }

    // The slot outlives every Activity, so it names none that was destroyed.
    @Test
    fun aDestroyedHost_isNeverCurrentAgain() {
        slot.attach(first)
        slot.attach(second)
        slot.detach(first, recreating = false)
        slot.detach(second, recreating = false)

        assertNull(slot.current)
        assertFalse(slot.isCurrent(first))
    }
}
