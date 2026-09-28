package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
class ReplicaKeypadLayoutHapticsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun touchUp_dispatchesPressHapticOnlyAndKeyReset() {
        val pressedCodes = mutableListOf<Int>()
        val keyEvents = mutableListOf<Int>()
        val overlay = buildOverlay(
            onPress = { view -> pressedCodes += (view as CalculatorKeyView).keyCode },
            onKeyEvent = keyEvents::add,
        )
        val keyView = findKeyView(overlay, code = 1)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 4f, 4f, 0)
        val up = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, 4f, 4f, 0)

        try {
            assertTrue(keyView.dispatchTouchEvent(down))
            assertTrue(keyView.isPressed)
            assertTrue(keyView.dispatchTouchEvent(up))
        } finally {
            down.recycle()
            up.recycle()
        }

        assertFalse(keyView.isPressed)
        assertEquals(listOf(1), pressedCodes)
        assertEquals(listOf(1, 0), keyEvents)
    }

    @Test
    fun touchCancel_clearsPressedStateWithoutReleaseHaptic() {
        val pressedCodes = mutableListOf<Int>()
        val keyEvents = mutableListOf<Int>()
        val overlay = buildOverlay(
            onPress = { view -> pressedCodes += (view as CalculatorKeyView).keyCode },
            onKeyEvent = keyEvents::add,
        )
        val keyView = findKeyView(overlay, code = 1)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 4f, 4f, 0)
        val cancel = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_CANCEL, 4f, 4f, 0)

        try {
            assertTrue(keyView.dispatchTouchEvent(down))
            assertTrue(keyView.dispatchTouchEvent(cancel))
        } finally {
            down.recycle()
            cancel.recycle()
        }

        assertFalse(keyView.isPressed)
        assertEquals(listOf(1), pressedCodes)
        assertEquals(listOf(1, 0), keyEvents)
    }

    @Test
    fun secondFinger_onAnotherKey_neverPressesIt_firstLiftedLast() {
        assertEquals(listOf(1, 0), twoFingerKeyEvents(firstLifted = 1))
    }

    @Test
    fun secondFinger_onAnotherKey_neverPressesIt_firstLiftedFirst() {
        assertEquals(listOf(1, 0), twoFingerKeyEvents(firstLifted = 0))
    }

    /**
     * Native input keeps one pressed-key slot, as the hardware scans one key at
     * a time. Touch key 1, touch key 2 with a second finger, then lift the
     * finger at pointer index [firstLifted] and the other one; return the key
     * events the keypad dispatched.
     */
    private fun twoFingerKeyEvents(firstLifted: Int): List<Int> {
        val keyEvents = mutableListOf<Int>()
        val overlay = buildOverlay(onPress = {}, onKeyEvent = keyEvents::add)
        val first = centerOf(findKeyView(overlay, code = 1))
        val second = centerOf(findKeyView(overlay, code = 2))
        val pointers = arrayOf(pointer(0, first), pointer(1, second))
        val events = listOf(
            multiPointer(0L, MotionEvent.ACTION_DOWN, 0, arrayOf(pointers[0])),
            multiPointer(10L, MotionEvent.ACTION_POINTER_DOWN, 1, pointers),
            multiPointer(20L, MotionEvent.ACTION_POINTER_UP, firstLifted, pointers),
            multiPointer(30L, MotionEvent.ACTION_UP, 0, arrayOf(pointers[1 - firstLifted])),
        )
        try {
            events.forEach { overlay.dispatchTouchEvent(it) }
        } finally {
            events.forEach(MotionEvent::recycle)
        }
        return keyEvents
    }

    private data class Pointer(val id: Int, val x: Float, val y: Float)

    private fun pointer(id: Int, at: Pair<Float, Float>) = Pointer(id, at.first, at.second)

    private fun Array<Pointer>.props() = map { p ->
        MotionEvent.PointerProperties().apply {
            id = p.id
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
    }.toTypedArray()

    private fun Array<Pointer>.coords() = map { p ->
        MotionEvent.PointerCoords().apply {
            x = p.x
            y = p.y
            pressure = 1f
            size = 1f
        }
    }.toTypedArray()

    private fun multiPointer(time: Long, action: Int, index: Int, pointers: Array<Pointer>): MotionEvent {
        val indexedAction = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(
            0L, time, indexedAction, pointers.size, pointers.props(), pointers.coords(),
            0, 0, 1f, 1f, 0, 0, 0, 0,
        )
    }

    private fun centerOf(view: View): Pair<Float, Float> {
        val bounds = Rect()
        view.getHitRect(bounds)
        return bounds.exactCenterX() to bounds.exactCenterY()
    }

    private fun buildOverlay(
        onPress: (View) -> Unit,
        onKeyEvent: (Int) -> Unit,
    ): ReplicaOverlay {
        return ReplicaOverlay(context).apply {
            ReplicaKeypadLayout.rebuild(
                context = context,
                overlay = this,
                performHapticClick = onPress,
                dispatchKey = onKeyEvent,
                initialSnapshotProvider = { KeypadSnapshot.EMPTY },
            )
            measure(exactly(1080), exactly(2160))
            layout(0, 0, 1080, 2160)
        }
    }

    private fun findKeyView(overlay: ReplicaOverlay, code: Int): CalculatorKeyView {
        return (0 until overlay.childCount)
            .mapNotNull { overlay.getChildAt(it) as? CalculatorKeyView }
            .first { it.keyCode == code }
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}
