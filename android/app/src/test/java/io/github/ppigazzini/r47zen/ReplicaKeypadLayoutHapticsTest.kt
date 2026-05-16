package io.github.ppigazzini.r47zen

import android.content.Context
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
    fun touchUp_dispatchesPressReleaseHapticsAndKeyReset() {
        val pressedCodes = mutableListOf<Int>()
        val releasedCodes = mutableListOf<Int>()
        val keyEvents = mutableListOf<Int>()
        val overlay = buildOverlay(
            onPress = { view -> pressedCodes += (view as CalculatorKeyView).keyCode },
            onRelease = { view -> releasedCodes += (view as CalculatorKeyView).keyCode },
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
        assertEquals(listOf(1), releasedCodes)
        assertEquals(listOf(1, 0), keyEvents)
    }

    @Test
    fun touchCancel_clearsPressedStateWithoutReleaseHaptic() {
        val pressedCodes = mutableListOf<Int>()
        val releasedCodes = mutableListOf<Int>()
        val keyEvents = mutableListOf<Int>()
        val overlay = buildOverlay(
            onPress = { view -> pressedCodes += (view as CalculatorKeyView).keyCode },
            onRelease = { view -> releasedCodes += (view as CalculatorKeyView).keyCode },
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
        assertTrue(releasedCodes.isEmpty())
        assertEquals(listOf(1, 0), keyEvents)
    }

    private fun buildOverlay(
        onPress: (View) -> Unit,
        onRelease: (View) -> Unit,
        onKeyEvent: (Int) -> Unit,
    ): ReplicaOverlay {
        return ReplicaOverlay(context).apply {
            setScalingMode("full_width")
            ReplicaKeypadLayout.rebuild(
                context = context,
                overlay = this,
                performHapticClick = onPress,
                performHapticRelease = onRelease,
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
