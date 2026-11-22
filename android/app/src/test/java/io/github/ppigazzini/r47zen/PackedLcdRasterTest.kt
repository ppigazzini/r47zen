package io.github.ppigazzini.r47zen

import io.github.ppigazzini.r47zen.LcdTestFrames.EDGE_PIXELS
import io.github.ppigazzini.r47zen.LcdTestFrames.expectedPixels
import io.github.ppigazzini.r47zen.LcdTestFrames.frameWith
import io.github.ppigazzini.r47zen.LcdTestFrames.palettes
import io.github.ppigazzini.r47zen.LcdTestFrames.pixelsOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// The frames and expected pixels come from LcdTestFrames: a pixel is the text color
// exactly where the test lit it, never what a second row decoder says.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackedLcdRasterTest {
    @Test
    fun newRaster_paintsEveryPixelTheBackground_forEveryThemePolarityAndLuminanceLevel() {
        for ((name, palette) in palettes()) {
            assertArrayEquals(name, expectedPixels(palette, emptySet()), pixelsOf(PackedLcdRaster(palette)))
        }
    }

    @Test
    fun firstFrame_decodesEveryRow_evenWithNoRowFlagged() {
        val raster = PackedLcdRaster(PALETTE)

        assertTrue(raster.applyFrame(frameWith(EDGE_PIXELS, flaggedRows = emptySet())))

        assertArrayEquals(expectedPixels(PALETTE, EDGE_PIXELS), pixelsOf(raster))
    }

    @Test
    fun laterFrame_decodesOnlyTheFlaggedRows() {
        val raster = PackedLcdRaster(PALETTE)
        raster.applyFrame(frameWith(setOf(10 to 5), flaggedRows = emptySet()))

        val changed = raster.applyFrame(
            frameWith(setOf(20 to 6, 30 to 7), flaggedRows = setOf(6)),
        )

        assertTrue(changed)
        assertArrayEquals(expectedPixels(PALETTE, setOf(10 to 5, 20 to 6)), pixelsOf(raster))
    }

    @Test
    fun laterFrame_withNoFlaggedRow_changesNothing() {
        val raster = PackedLcdRaster(PALETTE)
        raster.applyFrame(frameWith(setOf(10 to 5), flaggedRows = emptySet()))

        assertFalse(raster.applyFrame(frameWith(setOf(40 to 9), flaggedRows = emptySet())))
        assertArrayEquals(expectedPixels(PALETTE, setOf(10 to 5)), pixelsOf(raster))
    }

    @Test
    fun setPalette_repaintsTheHeldFrameInTheNewPalette() {
        val raster = PackedLcdRaster(PALETTE)
        raster.applyFrame(frameWith(EDGE_PIXELS, flaggedRows = emptySet()))

        raster.setPalette(RECOLOR)

        assertArrayEquals(expectedPixels(RECOLOR, EDGE_PIXELS), pixelsOf(raster))
    }

    @Test
    fun setPalette_toTheSamePalette_stillRepaints() {
        val raster = PackedLcdRaster(PALETTE)
        raster.applyFrame(frameWith(setOf(3 to 3), flaggedRows = emptySet()))
        // Pixels no decode wrote, top row and bottom row, so only a repaint of
        // every row puts the frame back.
        raster.bitmap.setPixel(0, 0, STRAY)
        raster.bitmap.setPixel(R47LcdContract.PIXEL_WIDTH - 1, R47LcdContract.PIXEL_HEIGHT - 1, STRAY)

        raster.setPalette(PALETTE)

        assertArrayEquals(expectedPixels(PALETTE, setOf(3 to 3)), pixelsOf(raster))
    }

    @Test
    fun smoothingOff_drawsTheOneToOneBitmap() {
        val raster = PackedLcdRaster(PALETTE)

        assertSame(raster.bitmap, raster.displayBitmap)
    }

    @Test
    fun smoothingOn_drawsAFourTimesLayer_thatKeepsASinglePixelSquare() {
        val raster = PackedLcdRaster(PALETTE)
        raster.setSmoothing(true)
        raster.applyFrame(frameWith(setOf(100 to 50), flaggedRows = emptySet()))

        val layer = raster.displayBitmap
        assertEquals(R47LcdContract.PIXEL_WIDTH * SMOOTH_SCALE, layer.width)
        assertEquals(R47LcdContract.PIXEL_HEIGHT * SMOOTH_SCALE, layer.height)
        // An isolated pixel has no diagonal to rebuild: it is the 4x4 block of
        // text and nothing around it.
        for (y in 196 until 204) {
            for (x in 396 until 404) {
                val lit = x in 400 until 404 && y in 200 until 204
                assertEquals("($x, $y)", if (lit) PALETTE.text else PALETTE.background, layer.getPixel(x, y))
            }
        }
    }

    @Test
    fun smoothingOn_aDiagonalGainsInBetweenPixels_thatTheOneToOneBitmapScaledUpLacks() {
        val raster = PackedLcdRaster(PALETTE)
        raster.setSmoothing(true)
        val diagonal = (0 until 8).map { 40 + it to 20 + it }.toSet()
        raster.applyFrame(frameWith(diagonal, flaggedRows = emptySet()))

        // Between (43, 23) and (44, 24) a plain 4x scale leaves the corner
        // pixels at 4x (175, 96) and (172, 99) paper; MMPX fills them.
        val layer = raster.displayBitmap
        assertEquals(PALETTE.text, layer.getPixel(176, 95))
        assertEquals(PALETTE.text, layer.getPixel(175, 96))
    }

    @Test
    fun smoothLayer_afterARowUpdate_equalsTheWholeFrameMagnifiedAfresh() {
        val before = (0 until 30).map { 60 + it to 100 + it }.toSet() + EDGE_PIXELS
        val after = (0 until 30).map { 90 - it to 100 + it }.toSet() + EDGE_PIXELS
        val updated = PackedLcdRaster(PALETTE).apply {
            setSmoothing(true)
            applyFrame(frameWith(before, flaggedRows = emptySet()))
            applyFrame(frameWith(after, flaggedRows = (100 until 130).toSet()))
        }
        val fresh = PackedLcdRaster(PALETTE).apply {
            setSmoothing(true)
            applyFrame(frameWith(after, flaggedRows = emptySet()))
        }

        assertArrayEquals(layerPixels(fresh), layerPixels(updated))
    }

    @Test
    fun smoothingTurnedOnLate_magnifiesTheHeldFrame() {
        val late = PackedLcdRaster(PALETTE).apply {
            applyFrame(frameWith(EDGE_PIXELS, flaggedRows = emptySet()))
            setSmoothing(true)
        }
        val early = PackedLcdRaster(PALETTE).apply {
            setSmoothing(true)
            applyFrame(frameWith(EDGE_PIXELS, flaggedRows = emptySet()))
        }

        assertArrayEquals(layerPixels(early), layerPixels(late))
    }

    @Test
    fun setPalette_repaintsTheSmoothLayer() {
        val raster = PackedLcdRaster(PALETTE)
        raster.setSmoothing(true)
        raster.applyFrame(frameWith(setOf(100 to 50), flaggedRows = emptySet()))

        raster.setPalette(RECOLOR)

        assertEquals(RECOLOR.text, raster.displayBitmap.getPixel(401, 201))
        assertEquals(RECOLOR.background, raster.displayBitmap.getPixel(0, 0))
    }

    private fun layerPixels(raster: PackedLcdRaster): IntArray {
        val layer = raster.displayBitmap
        val pixels = IntArray(layer.width * layer.height)
        layer.getPixels(pixels, 0, layer.width, 0, 0, layer.width, layer.height)
        return pixels
    }

    private companion object {
        const val SMOOTH_SCALE = 4
        val PALETTE = LcdPalette(text = 0xFF20313D.toInt(), background = 0xFFDCECD0.toInt())
        val RECOLOR = LcdPalette(text = 0xFF112A4A.toInt(), background = 0xFFF1E6C5.toInt())
        const val STRAY = 0xFFFF00FF.toInt()
    }
}
