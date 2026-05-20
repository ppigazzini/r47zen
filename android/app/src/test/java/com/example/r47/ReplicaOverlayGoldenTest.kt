package com.example.r47

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    @Test
    fun nativeChrome_matchesGoldenHash() {
        assertGoldenHash(
            ReplicaOverlay.CHROME_MODE_NATIVE,
            "d0201a46e253f9961dd261fd7c91aa75e30e6ee2073d4382d7d2fd0acc76d4f4",
        )
    }

    @Test
    fun backgroundChrome_matchesGoldenHash() {
        assertGoldenHash(
            ReplicaOverlay.CHROME_MODE_BACKGROUND,
            "160adba534fa2d82a8dbd91507a3228c06c9757666767e5b45a77a42ab749876",
        )
    }

    @Test
    fun textureChrome_matchesGoldenHash() {
        assertGoldenHash(
            ReplicaOverlay.CHROME_MODE_TEXTURE,
            "95bd1dbe228aa1d674d178f3898d5e5bbea899d9a0fd031c33d40ab611bc9ec9",
        )
    }

    private fun assertGoldenHash(mode: String, expectedHash: String) {
        val overlay = ReplicaOverlay(ApplicationProvider.getApplicationContext())
        overlay.setChromeMode(mode)
        overlay.setScalingMode("full_width")
        overlay.measure(exactly(1080), exactly(2160))
        overlay.layout(0, 0, 1080, 2160)
        overlay.updateLcd(sampleLcdPixels())

        val bitmap = Bitmap.createBitmap(1080, 2160, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(bitmap))
        val actualHash = pngSha256(bitmap)

        assertEquals("ReplicaOverlay golden changed for $mode: $actualHash", expectedHash, actualHash)
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