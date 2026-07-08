package io.github.ppigazzini.r47zen

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlotSessionControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun seedTwoSlots() {
        SlotStore(context).saveSlots(
            listOf(
                CalculatorSlot(0, "Slot A", null),
                CalculatorSlot(1, "Slot B", null),
            ),
        )
    }

    private fun buildController(
        calls: MutableList<String>,
        offerCoreTask: (Runnable) -> Unit,
    ): SlotSessionController {
        return SlotSessionController(
            context = context,
            mainHandler = Handler(Looper.getMainLooper()),
            offerCoreTask = offerCoreTask,
            saveStateNative = { calls.add("save") },
            loadStateNative = { calls.add("load") },
            setSlotNative = { calls.add("set:$it") },
        )
    }

    @Test
    fun switchSlot_savesThenSetsThenLoadsAndPersistsSelection() {
        seedTwoSlots()
        val calls = mutableListOf<String>()
        val controller = buildController(calls) { it.run() }

        controller.switchSlot(1)
        shadowOf(Looper.getMainLooper()).idle()

        // Old state must be saved before the slot switches and the new state
        // loaded only after, or a switch loses or cross-contaminates state.
        assertEquals(listOf("save", "set:1", "load"), calls)
        assertEquals(1, controller.currentSlotId())
        assertEquals(1, SlotStore(context).readCurrentSlotId())
    }

    @Test
    fun switchSlot_ignoresOutOfRangeId() {
        seedTwoSlots()
        val calls = mutableListOf<String>()
        var offered = 0
        val controller = buildController(calls) {
            offered += 1
            it.run()
        }

        controller.switchSlot(99)

        assertEquals(0, offered)
        assertTrue(calls.isEmpty())
        assertEquals(0, controller.currentSlotId())
    }

    @Test
    fun switchSlot_ignoresTheCurrentId() {
        seedTwoSlots()
        val calls = mutableListOf<String>()
        var offered = 0
        val controller = buildController(calls) {
            offered += 1
            it.run()
        }

        controller.switchSlot(controller.currentSlotId())

        assertEquals(0, offered)
        assertTrue(calls.isEmpty())
    }
}
