package io.github.ppigazzini.r47zen

import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast

internal class SlotSessionController(
    context: Context,
    private val mainHandler: Handler,
    private val offerCoreTask: (Runnable) -> Unit,
    private val saveStateNative: () -> Unit,
    private val loadStateNative: () -> Unit,
    private val setSlotNative: (Int) -> Unit,
) {
    companion object {
        private const val TAG = "R47SlotSession"
    }

    private val appContext = context.applicationContext
    private val slotStore = SlotStore(appContext)
    private val slots = slotStore.loadSlots()

    private var selectedSlotId = slotStore.readCurrentSlotId().normalize()

    init {
        slotStore.writeCurrentSlotId(selectedSlotId)
    }

    fun currentSlotId(): Int = selectedSlotId

    fun switchSlot(id: Int) {
        if (id !in slots.indices || id == selectedSlotId) return

        val targetName = slots[id].name
        offerCoreTask(
            Runnable {
                try {
                    saveStateNative()
                    selectedSlotId = id
                    setSlotNative(id)
                    loadStateNative()
                    mainHandler.post {
                        slotStore.writeCurrentSlotId(id)
                        Toast.makeText(
                            appContext,
                            "Switched to $targetName",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Slot switch failed", error)
                }
            },
        )
    }

    private fun Int.normalize(): Int {
        return if (this in slots.indices) this else 0
    }
}
