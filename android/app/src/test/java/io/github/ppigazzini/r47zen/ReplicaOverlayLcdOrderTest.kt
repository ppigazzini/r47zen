package io.github.ppigazzini.r47zen

import android.graphics.Rect
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import io.github.ppigazzini.r47zen.LcdTestFrames.EDGE_PIXELS
import io.github.ppigazzini.r47zen.LcdTestFrames.expectedPixels
import io.github.ppigazzini.r47zen.LcdTestFrames.frameWith
import io.github.ppigazzini.r47zen.LcdTestFrames.palettes
import io.github.ppigazzini.r47zen.LcdTestFrames.pixelsOf
import kotlin.math.ceil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// The LCD the overlay holds and the redraws it requests, for each theme and polarity
// at the minimum, default, and maximum luminance, in each order the app reaches it
// once laid out: before any frame, the palette before the first frame of an activity
// recreated onto a running calculator (with no row flagged, one, or all), and
// palette changes after a first frame, then a later frame that flags one row; and a
// frame before the palette, which the app's start does not do but a reordered one
// would. After each step the test checks, the raster shows exactly the frame in the
// palette, and a step that repaints the whole raster requests a redraw of the whole
// LCD window. Before layout the overlay invalidates whole instead, as onCreate's
// palette does, and this test does not see it.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplicaOverlayLcdOrderTest {
    @Test
    fun beforeAnyFrame_theLcdIsThePaletteBackground() {
        assertArrayEquals(
            "default palette",
            expectedPixels(LcdThemePolicy.DEFAULT_PALETTE, emptySet()),
            lcdPixels(overlay()),
        )
        for ((name, palette) in palettes()) {
            val overlay = overlay()
            assertRedrawsTheWholeLcd(overlay, name) { setLcdPalette(palette) }
            assertArrayEquals(name, expectedPixels(palette, emptySet()), lcdPixels(overlay))
        }
    }

    @Test
    fun recreatedOntoARunningCalculator_theFirstFrameShowsWhole_howeverManyRowsItFlags() {
        val rowSets = listOf(emptySet(), setOf(137), (0 until R47LcdContract.PIXEL_HEIGHT).toSet())
        for ((name, palette) in palettes()) {
            for (flagged in rowSets) {
                val overlay = overlay().apply { setLcdPalette(palette) }
                val label = "$name, ${flagged.size} rows flagged"
                assertRedrawsTheWholeLcd(overlay, label) {
                    assertTrue(label, updatePackedLcd(frameWith(EDGE_PIXELS, flagged)))
                }
                assertArrayEquals(label, expectedPixels(palette, EDGE_PIXELS), lcdPixels(overlay))
            }
        }
    }

    @Test
    fun frameThenPalette_showsTheFrameInThePalette() {
        for ((name, palette) in palettes()) {
            val overlay = overlay().apply { updatePackedLcd(frameWith(EDGE_PIXELS, emptySet())) }
            assertRedrawsTheWholeLcd(overlay, name) { setLcdPalette(palette) }
            assertArrayEquals(name, expectedPixels(palette, EDGE_PIXELS), lcdPixels(overlay))
        }
    }

    @Test
    fun paletteChangesBetweenLaterFrames_eachShowTheFrameInThePalette() {
        for ((name, palette) in palettes()) {
            val swapped = LcdPalette(text = palette.background, background = palette.text)
            val overlay = overlay().apply {
                setLcdPalette(palette)
                updatePackedLcd(frameWith(EDGE_PIXELS, emptySet()))
            }
            for ((step, next) in listOf("again" to palette, "swapped" to swapped, "back" to palette)) {
                assertRedrawsTheWholeLcd(overlay, "$name $step") { setLcdPalette(next) }
                assertArrayEquals("$name $step", expectedPixels(next, EDGE_PIXELS), lcdPixels(overlay))
            }
            // A later frame copies only the rows it flags: row 137 gains a pixel,
            // and the unflagged row 50 keeps what it showed.
            overlay.updatePackedLcd(frameWith(EDGE_PIXELS + setOf(200 to 137, 300 to 50), setOf(137)))
            assertArrayEquals(
                "$name later frame",
                expectedPixels(palette, EDGE_PIXELS + (200 to 137)),
                lcdPixels(overlay),
            )
        }
    }

    private fun overlay(): ReplicaOverlay {
        return ReplicaOverlay(ApplicationProvider.getApplicationContext()).apply {
            measure(
                MeasureSpec.makeMeasureSpec(OVERLAY_WIDTH, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(OVERLAY_HEIGHT, MeasureSpec.EXACTLY),
            )
            layout(0, 0, OVERLAY_WIDTH, OVERLAY_HEIGHT)
        }
    }

    private fun lcdPixels(overlay: ReplicaOverlay): IntArray {
        val field = overlay.javaClass.getDeclaredField("lcd").apply { isAccessible = true }
        return pixelsOf(field.get(overlay) as PackedLcdRaster)
    }

    // Run a step that repaints the whole raster and check the last redraw it
    // requested covers the whole LCD window.
    private fun assertRedrawsTheWholeLcd(overlay: ReplicaOverlay, label: String, step: ReplicaOverlay.() -> Unit) {
        val requests = mutableListOf<Rect>()
        overlay.requestLcdRedraw = { rect -> requests += Rect(rect) }
        overlay.step()
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, overlay.width.toFloat(), overlay.height.toFloat())
        val left = projection.offsetX + spec.lcdWindowLeft * projection.scale
        val right = projection.offsetX + (spec.lcdWindowLeft + spec.lcdWindowWidth) * projection.scale
        val top = projection.offsetY + spec.lcdWindowTop * projection.scale
        val bottom = projection.offsetY + (spec.lcdWindowTop + spec.lcdWindowHeight) * projection.scale
        val request = requests.lastOrNull()
        assertTrue("$label: requests a redraw", request != null)
        checkNotNull(request)
        assertTrue("$label: from the LCD's left edge", request.left <= left.toInt())
        assertTrue("$label: to the LCD's right edge", request.right >= right.toInt())
        assertTrue("$label: from the LCD's top", request.top <= top.toInt())
        assertTrue("$label: to the LCD's bottom", request.bottom >= ceil(bottom).toInt())
    }

    private companion object {
        const val OVERLAY_WIDTH = 1080
        const val OVERLAY_HEIGHT = 2160
    }
}
