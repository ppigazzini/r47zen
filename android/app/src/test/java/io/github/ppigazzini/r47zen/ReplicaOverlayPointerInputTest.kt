package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A mouse on the replica, as Android's large-screen input guidance tests it: a
// right click presses no key and asks for the menu, a left click presses as a tap does,
// the wheel over the LCD zooms the graph as a pinch does, and every key shows the hand.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class ReplicaOverlayPointerInputTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keyEvents = mutableListOf<Int>()
    private val pinches = mutableListOf<Float>()
    private var secondaryClicks = 0
    private val overlay = ReplicaOverlay(context).apply {
        ReplicaKeypadLayout.rebuild(
            context = context,
            overlay = this,
            performHapticClick = {},
            dispatchKey = keyEvents::add,
            initialSnapshotProvider = { KeypadSnapshot.EMPTY },
        )
        onLcdPinchListener = pinches::add
        onSecondaryClick = { secondaryClicks++ }
        measure(exactly(WIDTH), exactly(HEIGHT))
        layout(0, 0, WIDTH, HEIGHT)
    }

    @Test
    fun rightClickOnAKey_pressesNoKey_andAsksForTheMenuOnRelease() {
        click(centerOfKey(1), MotionEvent.BUTTON_SECONDARY)

        assertEquals(emptyList<Int>(), keyEvents)
        assertEquals(1, secondaryClicks)
    }

    // The overlay leaves a left click to the key, whose touch zone presses for it as
    // for a tap. Robolectric routes a synthetic mouse event to no child of a ViewGroup,
    // so the zone gets the event here directly.
    @Test
    fun leftClickOnAKey_isLeftToTheKey_whichPressesForIt() {
        val at = centerOfKey(1)
        val down = mouse(MotionEvent.ACTION_DOWN, at, MotionEvent.BUTTON_PRIMARY, time = 0L)
        val zone = (0 until overlay.childCount).map(overlay::getChildAt).first { view ->
            Rect().also(view::getHitRect).contains(at.first.toInt(), at.second.toInt())
        }
        val local = MotionEvent.obtain(down).apply { offsetLocation(-zone.left.toFloat(), -zone.top.toFloat()) }

        try {
            assertFalse(overlay.onInterceptTouchEvent(down))
            assertTrue(zone.dispatchTouchEvent(local))
        } finally {
            down.recycle()
            local.recycle()
        }

        assertEquals(listOf(1), keyEvents)
        assertEquals(0, secondaryClicks)
    }

    @Test
    fun wheelOverTheLcd_zoomsInUpAndOutDown() {
        assertTrue(wheel(lcdCenter(), notches = 1f))
        assertTrue(wheel(lcdCenter(), notches = -1f))

        val notch = GraphTouchVisualPolicy.WHEEL_NOTCH_SCALE
        assertEquals(listOf(notch, 1f / notch), pinches)
        assertTrue(pinches[0] > 1f && pinches[1] < 1f)
    }

    @Test
    fun wheelOffTheLcdOrWithTheGraphSwitchOff_zoomsNothing() {
        wheel(centerOfKey(1), notches = 1f)
        overlay.setLcdGraphTouchEnabled(false)
        wheel(lcdCenter(), notches = 1f)

        assertEquals(emptyList<Float>(), pinches)
    }

    @Test
    fun everyReplicaView_showsTheHand_butTheLcdsNode() {
        val hand = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
        val views = (0 until overlay.childCount).map(overlay::getChildAt)
            .filter { it !== overlay.lcdAccessibilityNode }

        assertTrue(views.isNotEmpty())
        assertTrue(views.all { it.pointerIcon == hand })
        assertTrue(overlay.lcdAccessibilityNode.pointerIcon != hand)
    }

    private fun click(at: Pair<Float, Float>, button: Int) {
        val events = listOf(
            mouse(MotionEvent.ACTION_DOWN, at, button, time = 0L),
            mouse(MotionEvent.ACTION_UP, at, buttons = 0, time = 10L),
        )
        try {
            events.forEach { overlay.dispatchTouchEvent(it) }
        } finally {
            events.forEach(MotionEvent::recycle)
        }
    }

    private fun wheel(at: Pair<Float, Float>, notches: Float): Boolean {
        val event = mouse(MotionEvent.ACTION_SCROLL, at, buttons = 0, time = 0L, vscroll = notches)
        return try {
            overlay.dispatchGenericMotionEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun mouse(
        action: Int,
        at: Pair<Float, Float>,
        buttons: Int,
        time: Long,
        vscroll: Float = 0f,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = at.first
            y = at.second
            pressure = 1f
            size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
        }
        return MotionEvent.obtain(
            0L, time, action, 1, arrayOf(properties), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    private fun centerOfKey(code: Int): Pair<Float, Float> {
        val key = (0 until overlay.childCount)
            .mapNotNull { overlay.getChildAt(it) as? CalculatorKeyView }
            .first { it.keyCode == code }
        val bounds = Rect()
        key.getHitRect(bounds)
        return bounds.exactCenterX() to bounds.exactCenterY()
    }

    private fun lcdCenter(): Pair<Float, Float> {
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(overlay.width.toFloat(), overlay.height.toFloat())
        val x = projection.offsetX + (spec.lcdWindowLeft + spec.lcdWindowWidth * 0.5f) * projection.scale
        val y = projection.offsetY + (spec.lcdWindowTop + spec.lcdWindowHeight * 0.5f) * projection.scale
        return x to y
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2160
    }
}
