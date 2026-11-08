package io.github.ppigazzini.r47zen

// Packed LCD frames and the pixels they must show, for the raster's tests and the
// overlay's. The coordinate-to-byte rule is the one
// keypad_generation_contract_test.c pins on the native side, and an expected pixel
// is the text color exactly where a test lit it, never what a second decoder says.
internal object LcdTestFrames {
    val LUMINANCE_LEVELS = listOf(
        LcdThemePolicy.MIN_LUMINANCE_PERCENT,
        LcdThemePolicy.DEFAULT_LUMINANCE_PERCENT,
        LcdThemePolicy.MAX_LUMINANCE_PERCENT,
    )

    // Each end of a row and of a byte, on the first, a middle, and the last row.
    val EDGE_PIXELS = setOf(
        0 to 0, 1 to 0, 7 to 0, 8 to 0,
        199 to 137, 392 to 137,
        399 to 239,
    )

    // Each theme and polarity at the minimum, default, and maximum luminance, named.
    fun palettes(): List<Pair<String, LcdPalette>> = LcdTheme.entries.flatMap { theme ->
        listOf(false, true).flatMap { isNegative ->
            LUMINANCE_LEVELS.map { luminance ->
                "$theme negative=$isNegative luminance=$luminance" to
                    LcdThemePolicy.resolve(theme.storageValue, luminance, isNegative)
            }
        }
    }

    fun frameWith(lit: Set<Pair<Int, Int>>, flaggedRows: Set<Int>): ByteArray {
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

    fun expectedPixels(palette: LcdPalette, lit: Set<Pair<Int, Int>>): IntArray {
        val pixels = IntArray(R47LcdContract.PIXEL_COUNT) { palette.background }
        for ((x, y) in lit) {
            pixels[y * R47LcdContract.PIXEL_WIDTH + x] = palette.text
        }
        return pixels
    }

    fun pixelsOf(raster: PackedLcdRaster): IntArray {
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
}
