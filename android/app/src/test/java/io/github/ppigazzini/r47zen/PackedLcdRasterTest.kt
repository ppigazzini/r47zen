package io.github.ppigazzini.r47zen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// The expected pixels come from the set of lit coordinates, never from a second
// row decoder: a pixel is the text color exactly where the test lit it. The
// coordinate-to-byte rule in [frameWith] is the one
// keypad_generation_contract_test.c pins on the native side.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackedLcdRasterTest {
    @Test
    fun newRaster_paintsEveryPixelTheBackground_forEveryThemePolarityAndLuminanceBound() {
        for (theme in LcdTheme.entries) {
            for (isNegative in listOf(false, true)) {
                for (luminance in LUMINANCE_BOUNDS) {
                    val palette = LcdThemePolicy.resolve(theme.storageValue, luminance, isNegative)
                    val raster = PackedLcdRaster(palette)

                    assertArrayEquals(
                        "$theme negative=$isNegative luminance=$luminance",
                        expectedPixels(palette, emptySet()),
                        pixelsOf(raster),
                    )
                }
            }
        }
    }

    @Test
    fun firstFrame_decodesEveryRow_evenWithNoRowFlagged() {
        val raster = PackedLcdRaster(PALETTE)

        assertTrue(raster.applyFrame(frameWith(EDGE_PIXELS, flaggedRows = emptySet())))

        assertEquals(0, raster.firstChangedRow)
        assertEquals(R47LcdContract.PIXEL_HEIGHT - 1, raster.lastChangedRow)
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
        assertEquals(6, raster.firstChangedRow)
        assertEquals(6, raster.lastChangedRow)
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

        assertEquals(0, raster.firstChangedRow)
        assertEquals(R47LcdContract.PIXEL_HEIGHT - 1, raster.lastChangedRow)
        assertArrayEquals(expectedPixels(RECOLOR, EDGE_PIXELS), pixelsOf(raster))
    }

    @Test
    fun setPalette_toTheSamePalette_stillRepaints() {
        val raster = PackedLcdRaster(PALETTE)
        raster.applyFrame(frameWith(setOf(3 to 3), flaggedRows = emptySet()))

        raster.setPalette(PALETTE)

        assertEquals(0, raster.firstChangedRow)
        assertEquals(R47LcdContract.PIXEL_HEIGHT - 1, raster.lastChangedRow)
    }

    private fun frameWith(lit: Set<Pair<Int, Int>>, flaggedRows: Set<Int>): ByteArray {
        val buffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        for (row in flaggedRows) {
            buffer[row * R47LcdContract.PACKED_ROW_SIZE_BYTES] = 1
        }
        for ((x, y) in lit) {
            val byte = y * R47LcdContract.PACKED_ROW_SIZE_BYTES + R47LcdContract.PACKED_PIXEL_OFFSET +
                (R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - x / 8)
            buffer[byte] = (buffer[byte].toInt() or (0x80 ushr (x % 8))).toByte()
        }
        return buffer
    }

    private fun expectedPixels(palette: LcdPalette, lit: Set<Pair<Int, Int>>): IntArray {
        val pixels = IntArray(R47LcdContract.PIXEL_COUNT) { palette.background }
        for ((x, y) in lit) {
            pixels[y * R47LcdContract.PIXEL_WIDTH + x] = palette.text
        }
        return pixels
    }

    private fun pixelsOf(raster: PackedLcdRaster): IntArray {
        val pixels = IntArray(R47LcdContract.PIXEL_COUNT)
        raster.bitmap.getPixels(
            pixels,
            0,
            R47LcdContract.PIXEL_WIDTH,
            0,
            0,
            R47LcdContract.PIXEL_WIDTH,
            R47LcdContract.PIXEL_HEIGHT,
        )
        return pixels
    }

    private companion object {
        val PALETTE = LcdPalette(text = 0xFF20313D.toInt(), background = 0xFFDCECD0.toInt())
        val RECOLOR = LcdPalette(text = 0xFF112A4A.toInt(), background = 0xFFF1E6C5.toInt())
        val LUMINANCE_BOUNDS = listOf(
            LcdThemePolicy.MIN_LUMINANCE_PERCENT,
            LcdThemePolicy.DEFAULT_LUMINANCE_PERCENT,
            LcdThemePolicy.MAX_LUMINANCE_PERCENT,
        )

        // Each end of a row and of a byte, on the first, a middle, and the last
        // row: the coordinates the native layout check uses too.
        val EDGE_PIXELS = setOf(
            0 to 0, 1 to 0, 7 to 0, 8 to 0,
            199 to 137, 392 to 137,
            399 to 239,
        )
    }
}
