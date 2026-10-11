package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

// Own the LCD as the shell shows it: the last packed frame native delivered, the
// palette, and the bitmap decoded from both. Nothing else writes the bitmap, and
// every write here decodes from the frame in the current palette, so the bitmap
// always shows the frame in the palette: a new raster decodes its blank frame
// before anything can draw it, a palette change decodes every row again, and a
// delivered frame decodes exactly the rows it copies.
internal class PackedLcdRaster(palette: LcdPalette) {
    val bitmap: Bitmap = createBitmap(
        R47LcdContract.PIXEL_WIDTH,
        R47LcdContract.PIXEL_HEIGHT,
        Bitmap.Config.ARGB_8888,
    )

    // The span of rows the last call decoded, for the caller's invalidation.
    var firstChangedRow = 0
        private set
    var lastChangedRow = R47LcdContract.PIXEL_HEIGHT - 1
        private set

    private var palette = palette
    private val frame = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
    private val linePixels = IntArray(R47LcdContract.PIXEL_WIDTH)

    // Native flags only the rows that changed since the last copy, and a host
    // that attaches to a live runtime finds those flags already taken by its
    // predecessor. Every row's pixels in a copy are current, so the first frame
    // a raster receives is decoded whole, flagged or not.
    private var hasNativeFrame = false

    init {
        decodeAllRows()
    }

    fun setPalette(palette: LcdPalette) {
        this.palette = palette
        decodeAllRows()
    }

    // Copy the rows native flagged, or every row of the first frame, into the
    // frame, decode them, and return whether any row changed.
    fun applyFrame(buffer: ByteArray): Boolean {
        val wholeFrame = !hasNativeFrame
        hasNativeFrame = true
        var first = R47LcdContract.PIXEL_HEIGHT
        var last = -1
        for (row in 0 until R47LcdContract.PIXEL_HEIGHT) {
            val rowOffset = row * R47LcdContract.PACKED_ROW_SIZE_BYTES
            if (!wholeFrame && (buffer[rowOffset].toInt() and 0xFF) == 0) {
                continue
            }
            System.arraycopy(buffer, rowOffset, frame, rowOffset, R47LcdContract.PACKED_ROW_SIZE_BYTES)
            decodeRow(row)
            if (row < first) first = row
            last = row
        }
        firstChangedRow = first
        lastChangedRow = last
        return last >= 0
    }

    private fun decodeAllRows() {
        for (row in 0 until R47LcdContract.PIXEL_HEIGHT) {
            decodeRow(row)
        }
        firstChangedRow = 0
        lastChangedRow = R47LcdContract.PIXEL_HEIGHT - 1
    }

    // A set bit is a dark pixel. The row's last pixel byte holds its first eight
    // columns, most significant bit first: native packs the row mirrored, and
    // keypad_generation_contract_test.c pins that layout on the native side.
    private fun decodeRow(row: Int) {
        val (text, background) = palette
        val pixelOffset = row * R47LcdContract.PACKED_ROW_SIZE_BYTES + R47LcdContract.PACKED_PIXEL_OFFSET
        for (byteIndex in 0 until R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW) {
            val packedByte = frame[pixelOffset + byteIndex].toInt() and 0xFF
            val destX = (R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - byteIndex) * 8
            for (bit in 0 until 8) {
                linePixels[destX + bit] = if ((packedByte and (1 shl (7 - bit))) != 0) {
                    text
                } else {
                    background
                }
            }
        }
        bitmap.setPixels(linePixels, 0, R47LcdContract.PIXEL_WIDTH, 0, row, R47LcdContract.PIXEL_WIDTH, 1)
    }
}
