package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

// Own the LCD as the shell shows it: the last packed frame native delivered, the
// palette, and the bitmaps decoded from both. Nothing else writes a bitmap, and
// every write here decodes from the frame in the current palette, so each bitmap
// always shows the frame in the palette: a new raster decodes its blank frame
// before anything can draw it, a palette change decodes every row again, and a
// delivered frame decodes exactly the rows it copies. With smoothing on, the
// smooth layer follows the same three paths from the same frame.
internal class PackedLcdRaster(palette: LcdPalette) {
    val bitmap: Bitmap = createBitmap(
        R47LcdContract.PIXEL_WIDTH,
        R47LcdContract.PIXEL_HEIGHT,
        Bitmap.Config.ARGB_8888,
    )

    // The bitmap the overlay draws: the smooth layer while smoothing is on,
    // else the 1:1 bitmap.
    val displayBitmap: Bitmap
        get() = smooth?.bitmap ?: bitmap

    private var palette = palette
    private val frame = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
    private val linePixels = IntArray(R47LcdContract.PIXEL_WIDTH)

    private var smooth: SmoothLcdLayer? = null

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
        smooth?.repaint(palette)
    }

    // Turn the smooth layer on or off. Turning it on magnifies the held frame
    // whole before anything can draw the layer. Turning it off drops it without
    // recycle(): a display list the render thread has not replayed yet may still
    // name its bitmap, and replaying a recycled bitmap throws.
    fun setSmoothing(enabled: Boolean) {
        if (enabled == (smooth != null)) {
            return
        }
        if (!enabled) {
            smooth = null
            return
        }
        val layer = SmoothLcdLayer()
        smooth = layer
        // The layer's ink exists only while it does: decode every row once to
        // fill it from the held frame.
        decodeAllRows()
        layer.update(0 until R47LcdContract.PIXEL_HEIGHT, palette)
    }

    // Copy the rows native flagged, or every row of the first frame, into the
    // frame, decode them, and return whether any row changed.
    fun applyFrame(buffer: ByteArray): Boolean {
        val wholeFrame = !hasNativeFrame
        hasNativeFrame = true
        var firstChanged = -1
        var lastChanged = -1
        for (row in 0 until R47LcdContract.PIXEL_HEIGHT) {
            val rowOffset = row * R47LcdContract.PACKED_ROW_SIZE_BYTES
            if (!wholeFrame && (buffer[rowOffset].toInt() and 0xFF) == 0) {
                continue
            }
            System.arraycopy(buffer, rowOffset, frame, rowOffset, R47LcdContract.PACKED_ROW_SIZE_BYTES)
            decodeRow(row)
            if (firstChanged < 0) {
                firstChanged = row
            }
            lastChanged = row
        }
        if (firstChanged < 0) {
            return false
        }
        smooth?.update(firstChanged..lastChanged, palette)
        return true
    }

    private fun decodeAllRows() {
        for (row in 0 until R47LcdContract.PIXEL_HEIGHT) {
            decodeRow(row)
        }
    }

    // A set bit is a dark pixel. The row's last pixel byte holds its first eight
    // columns, most significant bit first: native packs the row mirrored, and
    // keypad_generation_contract_test.c pins that layout on the native side.
    private fun decodeRow(row: Int) {
        val (text, background) = palette
        val pixelOffset = row * R47LcdContract.PACKED_ROW_SIZE_BYTES + R47LcdContract.PACKED_PIXEL_OFFSET
        val ink = smooth?.ink
        val inkOffset = row * R47LcdContract.PIXEL_WIDTH
        for (byteIndex in 0 until R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW) {
            val packedByte = frame[pixelOffset + byteIndex].toInt() and 0xFF
            val destX = (R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - byteIndex) * 8
            for (bit in 0 until 8) {
                val isText = (packedByte and (1 shl (7 - bit))) != 0
                linePixels[destX + bit] = if (isText) text else background
                ink?.set(inkOffset + destX + bit, if (isText) LcdMmpx.INK else LcdMmpx.PAPER)
            }
        }
        bitmap.setPixels(linePixels, 0, R47LcdContract.PIXEL_WIDTH, 0, row, R47LcdContract.PIXEL_WIDTH, 1)
    }
}

// The LCD magnified 4x by two MMPX passes, for a display that draws the LCD
// several times its native size: the curves and diagonals of the calculator's
// glyphs come out smooth where the 1:1 bitmap scaled up shows square pixels.
// Each pass rebuilds a row's blocks from up to LcdMmpx.REACH rows around it, so
// an update widens the changed rows by that reach at each pass.
private class SmoothLcdLayer {
    val bitmap: Bitmap = createBitmap(WIDTH_4X, HEIGHT_4X, Bitmap.Config.ARGB_8888).apply {
        // The overlay draws this layer smaller than its size in picture-in-
        // picture and on small windows; mipmaps keep that minification from
        // dropping thin strokes.
        setHasMipMap(true)
    }

    // The frame's text pixels, one byte each, as LcdMmpx reads them; the
    // raster's decodeRow writes them. The layer magnifies these, never the
    // palette colors, so a negative palette still smooths the text and not the
    // background.
    val ink = ByteArray(R47LcdContract.PIXEL_WIDTH * R47LcdContract.PIXEL_HEIGHT)

    private val magnifier = LcdMmpx()
    private val ink2x = ByteArray(WIDTH_2X * HEIGHT_2X)
    private val ink4x = ByteArray(WIDTH_4X * HEIGHT_4X)
    private val linePixels = IntArray(WIDTH_4X)

    // Magnify the changed source rows, and every row whose blocks they reach,
    // then decode the 4x rows that moved.
    fun update(changedRows: IntRange, palette: LcdPalette) {
        val rows2x = widen(changedRows, R47LcdContract.PIXEL_HEIGHT)
        magnifier.magnify(ink, R47LcdContract.PIXEL_WIDTH, R47LcdContract.PIXEL_HEIGHT, ink2x, rows2x)
        val changed2x = (2 * rows2x.first)..(2 * rows2x.last + 1)
        val rows4x = widen(changed2x, HEIGHT_2X)
        magnifier.magnify(ink2x, WIDTH_2X, HEIGHT_2X, ink4x, rows4x)
        decodeRows((2 * rows4x.first)..(2 * rows4x.last + 1), palette)
    }

    fun repaint(palette: LcdPalette) {
        decodeRows(0 until HEIGHT_4X, palette)
    }

    private fun widen(rows: IntRange, height: Int): IntRange =
        (rows.first - LcdMmpx.REACH).coerceAtLeast(0)..(rows.last + LcdMmpx.REACH).coerceAtMost(height - 1)

    private fun decodeRows(rows: IntRange, palette: LcdPalette) {
        val (text, background) = palette
        for (row in rows) {
            val offset = row * WIDTH_4X
            for (column in 0 until WIDTH_4X) {
                linePixels[column] = if (ink4x[offset + column] == LcdMmpx.INK) text else background
            }
            bitmap.setPixels(linePixels, 0, WIDTH_4X, 0, row, WIDTH_4X, 1)
        }
    }

    private companion object {
        const val WIDTH_2X = R47LcdContract.PIXEL_WIDTH * 2
        const val HEIGHT_2X = R47LcdContract.PIXEL_HEIGHT * 2
        const val WIDTH_4X = R47LcdContract.PIXEL_WIDTH * 4
        const val HEIGHT_4X = R47LcdContract.PIXEL_HEIGHT * 4
    }
}
