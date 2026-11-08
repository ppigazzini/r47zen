package io.github.ppigazzini.r47zen

import io.github.ppigazzini.r47zen.LcdTestFrames.EDGE_PIXELS
import io.github.ppigazzini.r47zen.LcdTestFrames.expectedPixels
import io.github.ppigazzini.r47zen.LcdTestFrames.frameWith
import io.github.ppigazzini.r47zen.LcdTestFrames.palettes
import io.github.ppigazzini.r47zen.LcdTestFrames.pixelsOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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

    private companion object {
        val PALETTE = LcdPalette(text = 0xFF20313D.toInt(), background = 0xFFDCECD0.toInt())
        val RECOLOR = LcdPalette(text = 0xFF112A4A.toInt(), background = 0xFFF1E6C5.toInt())
        const val STRAY = 0xFFFF00FF.toInt()
    }
}
