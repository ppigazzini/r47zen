package io.github.ppigazzini.r47zen

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

// The replica is a fixed physical layout: an RTL window, from an Arabic, Hebrew,
// Persian, or Urdu system language or Force RTL, lays every key's body out where
// an LTR window does. The overlay places its children by absolute position, so a
// body that mirrored inside its cell would leave the rows split.
@RunWith(RobolectricTestRunner::class)
class ReplicaOverlayLayoutDirectionTest {
    @Test
    fun anRtlWindow_laysEveryKeyBodyOutAsAnLtrWindowDoes() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val fixture = KeypadFixtureResources.loadAll().first()

        val ltr = keyBodies(activity, fixture, View.LAYOUT_DIRECTION_LTR)
        val rtl = keyBodies(activity, fixture, View.LAYOUT_DIRECTION_RTL)

        assertTrue("no key body was laid out", ltr.isNotEmpty())
        assertEquals(ltr, rtl)
    }

    // Each main key's body, left and right, in a window of the given direction.
    private fun keyBodies(
        activity: Activity,
        fixture: ExportedKeypadFixture,
        direction: Int,
    ): Map<Int, Pair<Float, Float>> {
        val overlay = ReplicaOverlay(activity)
        ReplicaKeypadLayout.rebuild(activity, overlay, {}, {}, fixture::snapshot)
        val window = FrameLayout(activity).apply {
            layoutDirection = direction
            addView(overlay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        window.measure(exactly(WIDTH), exactly(HEIGHT))
        window.layout(0, 0, WIDTH, HEIGHT)
        return (0 until overlay.childCount)
            .map(overlay::getChildAt)
            .filterIsInstance<CalculatorKeyView>()
            .mapNotNull { key -> key.currentMainKeyBodyHorizontalBounds()?.let { key.keyCode to it } }
            .toMap()
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400
    }
}
