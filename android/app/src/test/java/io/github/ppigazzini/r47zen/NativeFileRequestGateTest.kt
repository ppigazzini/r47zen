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
    private var lost = 0
    private val gate = NativeFileRequestGate(
        post = { posted.addLast(it) },
        findStart = { fileType, isSave ->
            lookups += fileType to isSave
            startFolder(fileType, isSave)
        },
        launch = { isSave, defaultName, start -> launches += Triple(isSave, defaultName, start) },
        native = object : NativeFileAnswers {
            override fun select(fd: Int, name: String?): Boolean {
                selections += fd
                names += name
                return nativeWaiting
            }

            override fun cancel() {
                cancels++
            }
        },
        onLost = { lost++ },
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
        val transfer = gate.beginTransfer()

        gate.deliverTransferred(transfer, 7, "mystate.s47")

        assertEquals(listOf(7), selections)
        assertEquals(listOf<String?>("mystate.s47"), names)
        assertTrue(closedFds.isEmpty())
        assertEquals(0, cancels)
    }

    @Test
    fun aPickerTheUserCancels_cancelsNativeOnce() {
        gate.request(isSave = true, defaultName = "state.s47", fileType = 0)
        posted.removeFirst().run()

        gate.deliverCanceled()
        gate.deliverCanceled()

        assertEquals(1, cancels)
    }

    @Test
    fun aTransferThatFails_cancelsNativeOnce() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        val transfer = gate.beginTransfer()

        gate.deliverTransferFailed(transfer)
        gate.deliverTransferFailed(transfer)

        assertEquals(1, cancels)
        assertTrue(selections.isEmpty())
    }

    @Test
    fun stoppingDuringATransfer_cancelsNativeOnce_andClosesTheLateDescriptor() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        val transfer = gate.beginTransfer()

        gate.cancelUnanswered()
        assertEquals(1, cancels)

        gate.deliverTransferred(transfer, 7, null)
        assertTrue(selections.isEmpty())
        assertEquals(listOf(7), closedFds)
    }

    @Test
    fun stoppingDuringATransfer_aLateFailureCancelsNothingMore() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        val transfer = gate.beginTransfer()

        gate.cancelUnanswered()
        gate.deliverTransferFailed(transfer)

        assertEquals(1, cancels)
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

    // The next request's own copy may be in flight when the abandoned one's lands.
    @Test
    fun aLateCopy_answersNotTheNextRequestsTransfer() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        val abandoned = gate.beginTransfer()
        gate.cancelUnanswered()
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        val next = gate.beginTransfer()

        gate.deliverTransferred(abandoned, 7, null)
        gate.deliverTransferred(next, 9, null)

        assertEquals(listOf(9), selections)
        assertEquals(listOf(7), closedFds)
    }

    // A process killed behind the picker gets the answer in a fresh gate, and a request
    // the new calculator thread made may already wait in native before request() runs:
    // an answer this process never asked for must not reach it.
    @Test
    fun answerToAPickerThisProcessNeverLaunched_neverReachesNative() {
        gate.deliverSelected(7, null)

        assertTrue(selections.isEmpty())
        assertEquals(listOf(7), closedFds)
        posted.removeFirst().run()
        assertEquals(1, lost)
    }

    @Test
    fun aCopyFromBeforeAProcessDeath_answersNotARequestWaitingInNative() {
        val transfer = gate.beginTransfer()

        gate.deliverTransferred(transfer, 7, null)

        assertTrue(selections.isEmpty())
        assertEquals(listOf(7), closedFds)
        posted.removeFirst().run()
        assertEquals(1, lost)
    }

    @Test
    fun aStaleAnswer_whileARequestIsPosted_answersNotIt() {
        gate.request(isSave = false, defaultName = "", fileType = 1)

        gate.deliverSelected(7, null)
        gate.deliverCanceled()

        assertTrue(selections.isEmpty())
        assertEquals(0, cancels)
        assertEquals(listOf(7), closedFds)
        posted.removeFirst().run()
        posted.removeFirst().run()
        gate.deliverSelected(9, null)
        assertEquals(listOf(9), selections)
    }

    @Test
    fun anAnswerNoCoreThreadTakes_closesItsDescriptor() {
        // A process killed behind the picker comes back with the answer and no request.
        nativeWaiting = false

        gate.deliverSelected(7, null)

        assertEquals(listOf(7), closedFds)
    }

    // The load's copy arrives on the transfer worker, where no Toast can be made: the
    // user hears of a lost answer on the main thread, through the gate's post.
    @Test
    fun aLostAnswer_tellsTheUserOnlyThroughThePost() {
        nativeWaiting = false
        val transfer = gate.beginTransfer()

        gate.deliverTransferred(transfer, 7, null)

        assertEquals(0, lost)
        assertEquals(listOf(7), closedFds)
        posted.removeFirst().run()
        assertEquals(1, lost)
    }

    // A request abandoned while its copy is in flight is followed by the next one, which
    // the late copy must not answer with the wrong file.
    @Test
    fun aLateCopyOfAnAbandonedRequest_answersNotTheNextOne() {
        gate.request(isSave = false, defaultName = "", fileType = 1)
        posted.removeFirst().run()
        val abandoned = gate.beginTransfer()
        gate.cancelUnanswered()
        gate.request(isSave = true, defaultName = "state.s47", fileType = 0)
        posted.removeFirst().run()

        gate.deliverTransferred(abandoned, 7, null)
        gate.deliverTransferFailed(abandoned)

        assertTrue(selections.isEmpty())
        assertEquals(listOf(7), closedFds)
        assertEquals(1, cancels)
        assertEquals(0, lost)

        gate.deliverSelected(9, "state.s47")
        assertEquals(listOf(9), selections)
    }
}
