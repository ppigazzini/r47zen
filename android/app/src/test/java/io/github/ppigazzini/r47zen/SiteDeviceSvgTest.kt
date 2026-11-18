package io.github.ppigazzini.r47zen

import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Draw the replica, the default keypad scene and the main-menu marker on the
// shell, as MainActivity installs them, through
// SvgRecordingCanvas and write it to build/site/device.svg, the Android
// calculator the Pages site shows. The LCD window stays empty, an svg element of
// class lcd at the window the overlay draws its bitmap in, for
// scripts/site-screens/compose_device_svg.sh to fill with a rendered screen.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class SiteDeviceSvgTest {
    @Test
    fun replicaRecordsAsSvgWithTheLcdWindowWhereTheShellProjectsIt() {
        val chrome = ReplicaChromeLayout()
        val spec = chrome.currentChromeSpec()
        // The fit size makes the projection scale 1: the SVG is in the shell's
        // own units, less its visible-frame trim.
        val width = (spec.shellWidth - spec.scaledModeFitTrimLeft - spec.scaledModeFitTrimRight).roundToInt()
        val height = (spec.shellHeight - spec.scaledModeFitTrimTop - spec.scaledModeFitTrimBottom).roundToInt()
        val projection = chrome.computeProjection(width.toFloat(), height.toFloat())
        assertEquals(1f, projection.scale, SCALE_TOLERANCE)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Detached, so no window's layout pass replaces the size laid out here.
        val overlay = ReplicaOverlay(activity)
        val snapshot = KeypadFixtureResources.load("default-keypad").snapshot()
        ReplicaKeypadLayout.rebuild(activity, overlay, {}, {}) { snapshot }
        ReplicaKeypadLayout.updateDynamicKeys(overlay, snapshot)
        overlay.addReplicaView(
            MainMenuGlyphView(activity),
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_LEFT,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_TOP,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_WIDTH,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_HEIGHT,
        )
        repeat(2) {
            overlay.measure(exactly(width), exactly(height))
            overlay.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(width, overlay.width)
        assertEquals(height, overlay.height)

        val canvas = SvgRecordingCanvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))
        overlay.draw(canvas)

        assertEquals("one LCD bitmap", 1, canvas.lcdWindows.size)
        val window = canvas.lcdWindows.single()
        val expectedLeft = projection.offsetX + spec.lcdWindowLeft * projection.scale
        val expectedTop = projection.offsetY + spec.lcdWindowTop * projection.scale
        assertEquals(expectedLeft, window.left, POSITION_TOLERANCE)
        assertEquals(expectedTop, window.top, POSITION_TOLERANCE)
        assertEquals(spec.lcdWindowWidth * projection.scale, window.width(), POSITION_TOLERANCE)
        assertEquals(spec.lcdWindowHeight * projection.scale, window.height(), POSITION_TOLERANCE)
        // The window keeps the LCD's 400 x 240 aspect, so the screen fills it.
        assertTrue(abs(window.width() / window.height() - LCD_ASPECT) < ASPECT_TOLERANCE)

        val svg = canvas.toSvg()
        val keys = (0 until overlay.childCount).count { overlay.getChildAt(it) is CalculatorKeyView }
        assertTrue("the replica holds its keys", keys >= MIN_KEYS)
        val lcd = String.format(
            Locale.ROOT,
            "<svg class=\"lcd\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" viewBox=\"0 0 %d %d\"/>",
            window.left,
            window.top,
            window.width(),
            window.height(),
            R47LcdContract.PIXEL_WIDTH,
            R47LcdContract.PIXEL_HEIGHT,
        )
        val device = svg.replace("</svg>\n", "$lcd</svg>\n")
        File("build/site").mkdirs()
        File("build/site/device.svg").writeText(device)
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val SCALE_TOLERANCE = 0.001f
        const val POSITION_TOLERANCE = 0.5f
        const val LCD_ASPECT = 400f / 240f
        const val ASPECT_TOLERANCE = 0.01f
        const val MIN_KEYS = 43
    }
}
