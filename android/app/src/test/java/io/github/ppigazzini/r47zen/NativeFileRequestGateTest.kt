package io.github.ppigazzini.r47zen

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeFileRequestGateTest {
    private val posted = ArrayDeque<Runnable>()
    private val launches = mutableListOf<Triple<Boolean, String, Uri?>>()
    private val lookups = mutableListOf<Pair<Int, Boolean>>()
    private var startFolder: (fileType: Int, isSave: Boolean) -> Uri? = { _, _ -> null }
    private var cancels = 0
    private val selections = mutableListOf<Int>()
    private val names = mutableListOf<String?>()
    private val closedFds = mutableListOf<Int>()
    private var nativeWaiting = true
    private val gate = NativeFileRequestGate(
        post = { posted.addLast(it) },
        findStart = { fileType, isSave ->
            lookups += fileType to isSave
            startFolder(fileType, isSave)
        },
        launch = { isSave, defaultName, start -> launches += Triple(isSave, defaultName, start) },
        selectNative = { fd, name ->
            selections += fd
            names += name
            nativeWaiting
        },
        cancelNative = { cancels++ },
        closeLateFd = { closedFds += it },
    )

    // The provider calls that find where a picker starts run on the thread that asks,
    // the calculator thread, which waits for the answer anyway: the main thread only
    // launches, with the folder already found.
    @Test
    fun theStartFolderIsFoundBeforeTheLaunchIsPosted() {
        val programs = Uri.parse("content://tree/document/root%2FPROGRAMS")
        startFolder = { _, _ ->
            assertTrue("a launch was posted before the lookup ended", posted.isEmpty())
            programs
        }

        gate.request(isSave = false, defaultName = "", fileType = 1)
        assertEquals(listOf(1 to false), lookups)
        assertTrue(launches.isEmpty())

        posted.removeFirst().run()

        assertEquals(listOf(Triple(false, "", programs)), launches)
    }

    // A request must end in one answer, so a lookup that fails still launches the
    // picker, where it would have started with no work directory.
    @Test
    fun aLookupThatThrows_stillLaunches_withNoStartFolder() {
        startFolder = { _, _ -> throw SecurityException("the grant is gone") }

        gate.request(isSave = true, defaultName = "state.s47", fileType = 0)
        posted.removeFirst().run()

        assertEquals(listOf(Triple(true, "state.s47", null as Uri?)), launches)
        assertEquals(0, cancels)
    }

    @Test
    fun aTransferredDocument_reachesNative() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        gate.beginTransfer()

        gate.deliverSelected(7, "mystate.s47")

        assertEquals(listOf(7), selections)
        assertEquals(listOf<String?>("mystate.s47"), names)
        assertTrue(closedFds.isEmpty())
        assertEquals(0, cancels)
    }

    @Test
    fun stoppingDuringATransfer_cancelsNativeOnce_andClosesTheLateDescriptor() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        gate.beginTransfer()

        gate.cancelUnanswered()
        assertEquals(1, cancels)

        gate.deliverSelected(7, null)
        assertTrue(selections.isEmpty())
        assertEquals(listOf(7), closedFds)
    }

    @Test
    fun stoppingDuringATransfer_aLateFailureCancelsNothingMore() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        gate.beginTransfer()

        gate.cancelUnanswered()
        gate.deliverCanceled()

        assertEquals(1, cancels)
    }

    @Test
    fun aDocumentAnsweredAfterRecreation_stillReachesNative() {
        // The recreated Activity's gate never launched the picker its answer reaches.
        gate.beginTransfer()

        gate.deliverSelected(7, null)

        assertEquals(listOf(7), selections)
    }

    @Test
    fun launchedPickerWithNoAnswer_isCanceledOnceWhenTheAppStops() {
        gate.request(isSave = true, defaultName = "state.s47", fileType = 0)
        posted.removeFirst().run()

        gate.cancelUnanswered()
        gate.cancelUnanswered()
        assertEquals(1, cancels)
    }

    @Test
    fun answeredPicker_isNotCanceledAgain() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()

        gate.deliverSelected(42, null)
        gate.cancelUnanswered()

        assertEquals(listOf(42), selections)
        assertEquals(0, cancels)
    }

    @Test
    fun answerReachingAGateThatNeverLaunched_stillReachesNative() {
        // After a recreation the picker answers the new Activity, whose gate
        // never launched, while the core thread still waits.
        gate.deliverSelected(7, null)
        gate.deliverCanceled()

        assertEquals(listOf(7), selections)
        assertEquals(1, cancels)
    }

    @Test
    fun anAnswerNoCoreThreadTakes_closesItsDescriptor() {
        // A process killed behind the picker comes back with the answer and no request.
        nativeWaiting = false

        gate.deliverSelected(7, null)

        assertEquals(listOf(7), closedFds)
    }
}
