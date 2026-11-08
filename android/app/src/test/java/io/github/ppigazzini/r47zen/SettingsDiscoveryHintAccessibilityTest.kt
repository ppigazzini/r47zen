package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

// The first-run hint is the only prose on the main screen. Its text follows the
// system font size, its pulse stops when the system turns animations off, and
// while it shows, the overlay says so, for the menu button to tell a TalkBack
// user what the painted card says.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsDiscoveryHintAccessibilityTest {
    @Test
    fun hintText_followsTheSystemFontSize() {
        val normal = hintTextSize(fontScale = 1f)
        val large = hintTextSize(fontScale = 2f)

        assertTrue("at font scale 2 the hint is ${large}px, at 1 ${normal}px", large >= normal * 1.5f)
    }

    @Test
    fun withAnimationsOff_theHintIsDrawnStillAndRequestsNoFrame() {
        val overlay = overlayShowingTheHint()
        var frames = 0
        overlay.requestHintFrame = { frames += 1 }
        overlay.areAnimationsEnabled = { false }

        val first = render(overlay)
        ShadowSystemClock.advanceBy(Duration.ofMillis(SettingsDiscoveryHintVisualPolicy.PULSE_PERIOD_MS / 4))
        val later = render(overlay)

        assertEquals("frames requested", 0, frames)
        assertTrue("the card changed between two draws", first.sameAs(later))
    }

    @Test
    fun withAnimationsOn_theHintPulses() {
        val overlay = overlayShowingTheHint()
        var frames = 0
        overlay.requestHintFrame = { frames += 1 }
        overlay.areAnimationsEnabled = { true }

        val first = render(overlay)
        ShadowSystemClock.advanceBy(Duration.ofMillis(SettingsDiscoveryHintVisualPolicy.PULSE_PERIOD_MS / 4))
        val later = render(overlay)

        assertEquals("frames requested", 2, frames)
        assertFalse("the card did not pulse", first.sameAs(later))
    }

    @Test
    fun theOverlaySaysWhenTheHintShowsAndWhenATouchDismissesIt() {
        val overlay = laidOutOverlay()
        val shown = mutableListOf<Boolean>()
        overlay.onSettingsDiscoveryHintShown = { shown += it }

        overlay.setShowSettingsDiscoveryHint(true)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, WIDTH / 2f, HEIGHT / 2f, 0)
        try {
            overlay.onInterceptTouchEvent(down)
        } finally {
            down.recycle()
        }

        assertEquals(listOf(true, false), shown)
    }

    @Test
    fun theMenuButtonCarriesTheWelcomeOnlyWhileTheHintShows() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val plain = MainMenuButtonDescription.of(context, hintShowing = false)
        val withHint = MainMenuButtonDescription.of(context, hintShowing = true)

        assertEquals(context.getString(R.string.main_menu_button_content_description), plain)
        assertTrue(withHint.startsWith(plain))
        assertTrue("the hint's description names Settings", withHint.contains("Settings"))
        assertFalse("a description cannot point by color", withHint.contains("orange"))
    }

    private fun hintTextSize(fontScale: Float): Float {
        RuntimeEnvironment.setFontScale(fontScale)
        val overlay = ReplicaOverlay(ApplicationProvider.getApplicationContext())
        val field = overlay.javaClass.getDeclaredField("settingsHintInfoTextPaint").apply { isAccessible = true }
        return (field.get(overlay) as TextPaint).textSize
    }

    private fun laidOutOverlay(): ReplicaOverlay {
        return ReplicaOverlay(ApplicationProvider.getApplicationContext()).apply {
            measure(exactly(WIDTH), exactly(HEIGHT))
            layout(0, 0, WIDTH, HEIGHT)
        }
    }

    private fun overlayShowingTheHint(): ReplicaOverlay {
        return laidOutOverlay().apply { setShowSettingsDiscoveryHint(true) }
    }

    private fun render(overlay: ReplicaOverlay): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(bitmap))
        return bitmap
    }

    private fun exactly(size: Int): Int = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2160
    }
}
