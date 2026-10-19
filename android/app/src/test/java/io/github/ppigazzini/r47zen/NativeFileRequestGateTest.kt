package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFileRequestGateTest {
    private val posted = ArrayDeque<Runnable>()
    private val launches = mutableListOf<Triple<Boolean, String, Int>>()
    private var cancels = 0
    private val selections = mutableListOf<Int>()
    private val gate = NativeFileRequestGate(
        post = { posted.addLast(it) },
        launch = { isSave, defaultName, fileType -> launches += Triple(isSave, defaultName, fileType) },
        selectNative = { selections += it },
        cancelNative = { cancels++ },
    )

    @Test
    fun postedRequest_launchesOnce_andALaterDestroyCancelsNothing() {
        gate.request(isSave = true, defaultName = "state.s47", fileType = 3)
        posted.removeFirst().run()
        gate.cancelPending()

        assertEquals(listOf(Triple(true, "state.s47", 3)), launches)
        assertEquals(0, cancels)
    }

    @Test
    fun requestDroppedByDestroy_cancelsNativeOnce_andAStaleCallbackLaunchesNothing() {
        gate.request(isSave = false, defaultName = "", fileType = 1)

        // onDestroy removed the posted callback before it ran.
        gate.cancelPending()
        assertEquals(1, cancels)

        // Were the callback to run after all, it must not launch a second outcome.
        posted.removeFirst().run()
        gate.cancelPending()
        assertTrue(launches.isEmpty())
        assertEquals(1, cancels)
    }

    @Test
    fun cancelPending_withNoRequestInFlight_cancelsNothing() {
        gate.cancelPending()

        assertEquals(0, cancels)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun launchedPickerWithNoAnswer_isCanceledOnceWhenTheAppStops() {
        gate.request(isSave = true, defaultName = "state.s47", fileType = 0)
        posted.removeFirst().run()

        gate.cancelPending()
        assertEquals("a launched picker is not a dropped launch", 0, cancels)

        gate.cancelUnanswered()
        gate.cancelUnanswered()
        assertEquals(1, cancels)
    }

    @Test
    fun answeredPicker_isNotCanceledAgain() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()

        gate.deliverSelected(42)
        gate.cancelUnanswered()

        assertEquals(listOf(42), selections)
        assertEquals(0, cancels)
    }

    @Test
    fun answerReachingAGateThatNeverLaunched_stillReachesNative() {
        // After a recreation the picker answers the new Activity, whose gate
        // never launched, while the core thread still waits.
        gate.deliverSelected(7)
        gate.deliverCanceled()

        assertEquals(listOf(7), selections)
        assertEquals(1, cancels)
    }

    @Test
    fun cancelUnanswered_leavesAPostedRequestToCancelPending() {
        gate.request(isSave = true, defaultName = "program.p47", fileType = 1)

        gate.cancelUnanswered()
        assertEquals(0, cancels)

        gate.cancelPending()
        assertEquals(1, cancels)
    }
}
