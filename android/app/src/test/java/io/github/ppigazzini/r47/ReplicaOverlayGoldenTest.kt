package io.github.ppigazzini.r47

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class ReplicaOverlayGoldenTest {
    private companion object {
        const val LEGACY_CHROME_MODE_BACKGROUND = "r47_background"
        const val LEGACY_CHROME_MODE_TEXTURE = "r47_texture"
    }

    @Test
    fun packedLcd_matchesArgbRendering() {
        val textColor = 0xFF20313D.toInt()
        val backgroundColor = 0xFFDCECD0.toInt()
        val packedBuffer = samplePackedBuffer()
        val expectedPixels = decodePackedBuffer(packedBuffer, textColor, backgroundColor)

        val packedOverlay = configuredOverlay().apply {
            setLcdColors(textColor, backgroundColor)
        }
        assertTrue(packedOverlay.updatePackedLcd(packedBuffer.copyOf()))

        val argbOverlay = configuredOverlay().apply {
            setLcdColors(textColor, backgroundColor)
            updateLcd(expectedPixels)
        }

        assertEquals(renderHash(argbOverlay), renderHash(packedOverlay))
    }

    @Test
    fun packedLcd_recolorsExistingSnapshot() {
        val initialText = 0xFF20313D.toInt()
        val initialBackground = 0xFFDCECD0.toInt()
        val recolorText = 0xFF112A4A.toInt()
        val recolorBackground = 0xFFF1E6C5.toInt()
        val packedBuffer = samplePackedBuffer()

        val overlay = configuredOverlay().apply {
            setLcdColors(initialText, initialBackground)
        }
        assertTrue(overlay.updatePackedLcd(packedBuffer.copyOf()))
        overlay.setLcdColors(recolorText, recolorBackground)

        val expectedOverlay = configuredOverlay().apply {
            updateLcd(decodePackedBuffer(packedBuffer, recolorText, recolorBackground))
        }

        assertEquals(renderHash(expectedOverlay), renderHash(overlay))
    }

    @Test
    fun nativeChrome_matchesGoldenHash() {
        assertGoldenHash(
            ReplicaOverlay.CHROME_MODE_NATIVE,
            "a689a5afbca4244237523b95f91554c9e5cbff18687d212916cbc6e353ebf83a",
        )
    }

    @Test
    fun legacyImageBackedModes_fallBackToNativeChrome() {
        val nativeHash = renderHash(configuredOverlay(ReplicaOverlay.CHROME_MODE_NATIVE).apply {
            updateLcd(sampleLcdPixels())
        })

        for (legacyMode in listOf(
            LEGACY_CHROME_MODE_BACKGROUND,
            LEGACY_CHROME_MODE_TEXTURE,
        )) {
            val legacyHash = renderHash(configuredOverlay(legacyMode).apply {
                updateLcd(sampleLcdPixels())
            })
            assertEquals(
                "Legacy chrome mode should resolve to native output for $legacyMode",
                nativeHash,
                legacyHash,
            )
        }
    }

    private fun assertGoldenHash(mode: String, expectedHash: String) {
        val overlay = configuredOverlay(mode)
        overlay.updateLcd(sampleLcdPixels())
        val actualHash = renderHash(overlay)

        assertEquals("ReplicaOverlay golden changed for $mode: $actualHash", expectedHash, actualHash)
    }

    private fun configuredOverlay(mode: String = ReplicaOverlay.CHROME_MODE_NATIVE): ReplicaOverlay {
        return ReplicaOverlay(ApplicationProvider.getApplicationContext()).apply {
            setChromeMode(mode)
            setScalingMode("full_width")
            measure(exactly(1080), exactly(2160))
            layout(0, 0, 1080, 2160)
        }
    }

    private fun renderHash(overlay: ReplicaOverlay): String {
        val bitmap = Bitmap.createBitmap(1080, 2160, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(bitmap))
        return pngSha256(bitmap)
    }

    private fun samplePackedBuffer(): ByteArray {
        val buffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(buffer, x = 0, y = 0)
        setPackedPixel(buffer, x = 17, y = 0)
        setPackedPixel(buffer, x = 80, y = 60)
        setPackedPixel(buffer, x = 215, y = 60)
        setPackedPixel(buffer, x = 399, y = 120)
        setPackedPixel(buffer, x = 121, y = 239)
        return buffer
    }

    private fun setPackedPixel(buffer: ByteArray, x: Int, y: Int) {
        val rowOffset = y * R47LcdContract.PACKED_ROW_SIZE_BYTES
        buffer[rowOffset] = 1
        buffer[rowOffset + 1] = (R47LcdContract.PIXEL_HEIGHT - y - 1).toByte()
        val byteIndex = R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - (x / 8)
        val bitMask = 1 shl (7 - (x % 8))
        buffer[rowOffset + 2 + byteIndex] =
            (buffer[rowOffset + 2 + byteIndex].toInt() or bitMask).toByte()
    }

    private fun decodePackedBuffer(buffer: ByteArray, textColor: Int, backgroundColor: Int): IntArray {
        val pixels = IntArray(R47LcdContract.PIXEL_COUNT) { backgroundColor }
        for (bufferRow in 0 until R47LcdContract.PIXEL_HEIGHT) {
            val rowOffset = bufferRow * R47LcdContract.PACKED_ROW_SIZE_BYTES
            val rowId = buffer[rowOffset + 1].toInt() and 0xFF
            val displayRow = (R47LcdContract.PIXEL_HEIGHT - rowId - 1)
                .coerceIn(0, R47LcdContract.PIXEL_HEIGHT - 1)
            for (byteIndex in 0 until R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW) {
                val packedByte = buffer[rowOffset + 2 + byteIndex].toInt() and 0xFF
                val destX = (R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - byteIndex) * 8
                for (bit in 0 until 8) {
                    val x = destX + bit
                    val index = displayRow * R47LcdContract.PIXEL_WIDTH + x
                    pixels[index] = if ((packedByte and (1 shl (7 - bit))) != 0) {
                        textColor
                    } else {
                        backgroundColor
                    }
                }
            }
        }
        return pixels
    }

    private fun sampleLcdPixels(): IntArray {
        return IntArray(R47LcdContract.PIXEL_COUNT) { index ->
            val x = index % R47LcdContract.PIXEL_WIDTH
            val y = index / R47LcdContract.PIXEL_WIDTH
            when {
                y < 80 -> 0xFF1E3A5F.toInt()
                x < 133 -> 0xFF89A8B2.toInt()
                ((x / 24) + (y / 24)) % 2 == 0 -> 0xFFE7F2E4.toInt()
                else -> 0xFF2B3A33.toInt()
            }
        }
    }

    private fun pngSha256(bitmap: Bitmap): String {
        val pngBytes = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(pngBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun exactly(size: Int): Int {
        return android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
    }
}
