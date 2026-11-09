package io.github.ppigazzini.r47zen

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

// The display for a screen reader: a node over the LCD, a polite live region,
// whose description is the text the calculator sends and whose error is an error it
// shows; the text is asked once after a burst of frames, and only while an accessibility
// service runs.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class LcdAccessibilityNodeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Attached, as in the app: a View runs what it posts only once it has a window.
    private val overlay = ReplicaOverlay(context).also { overlay ->
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(overlay)
        overlay.measure(exactly(WIDTH), exactly(HEIGHT))
        overlay.layout(0, 0, WIDTH, HEIGHT)
    }
    private val node = overlay.lcdAccessibilityNode

    @Test
    fun theNodeLiesOverTheLcd_andSpeaksPolitely() {
        val centerX = node.left + node.width / 2f
        val centerY = node.top + node.height / 2f

        assertTrue(node.width > 0 && node.height > 0)
        assertTrue(overlay.isPointInLcd(centerX, centerY))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, node.importantForAccessibility)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, node.accessibilityLiveRegion)
        assertEquals(context.getString(R.string.lcd_accessibility_label), node.contentDescription)
    }

    @Test
    fun theTextReplacesTheLabel_andAnErrorIsTheNodesError() {
        overlay.setDisplayDescription("12", isError = false)
        assertEquals("12", node.contentDescription)
        assertFalse(node.createAccessibilityNodeInfo().isContentInvalid)

        overlay.setDisplayDescription("Domain error", isError = true)
        val info = node.createAccessibilityNodeInfo()
        assertEquals("Domain error", node.contentDescription)
        assertTrue(info.isContentInvalid)
        assertEquals("Domain error", info.error)

        overlay.setDisplayDescription("  ", isError = false)
        assertEquals(context.getString(R.string.lcd_accessibility_label), node.contentDescription)
    }

    @Test
    fun aBurstOfFrames_asksOnceAfterItSettles_andNothingWithoutAService() {
        var asks = 0
        overlay.onDisplayDescriptionWanted = { asks++ }
        val accessibility = shadowOf(context.getSystemService(AccessibilityManager::class.java))

        accessibility.setEnabled(true)
        repeat(3) {
            overlay.updatePackedLcd(dirtyFrame())
            idle(GraphTouchVisualPolicy.DISPLAY_DESCRIPTION_SETTLE_MS / 3)
        }
        assertEquals(0, asks)
        idle(GraphTouchVisualPolicy.DISPLAY_DESCRIPTION_SETTLE_MS)
        assertEquals(1, asks)

        accessibility.setEnabled(false)
        overlay.updatePackedLcd(dirtyFrame())
        idle(GraphTouchVisualPolicy.DISPLAY_DESCRIPTION_SETTLE_MS * 2)
        assertEquals(1, asks)
    }

    @Test
    fun aKeypadRebuild_keepsTheNode() {
        ReplicaKeypadLayout.rebuild(
            context = context,
            overlay = overlay,
            performHapticClick = {},
            dispatchKey = {},
            initialSnapshotProvider = { KeypadSnapshot.EMPTY },
        )

        assertEquals(1, (0 until overlay.childCount).count { overlay.getChildAt(it) === node })
    }

    private fun dirtyFrame() = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE).apply { this[0] = 1 }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2160
    }
}
