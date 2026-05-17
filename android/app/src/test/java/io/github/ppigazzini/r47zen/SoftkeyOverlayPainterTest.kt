package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class SoftkeyOverlayPainterTest {
    @Test
    fun drawRendersMenuBadgeLabelAndUnderline() {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val overlay = SoftkeyOverlayAdornmentSpec(
            id = SoftkeyAdornmentSlot.OVERLAY.id,
            kind = SoftkeyOverlayKind.MENU_BADGE_TRUE,
            center = PointSpec(24f, 24f),
            color = Color.WHITE,
            frameBounds = RectSpec(17.5f, 18.8f, 30.5f, 29.2f),
            label = LabelSpec(
                id = SoftkeyLabelSlot.OVERLAY_MENU.id,
                text = "M",
                visible = true,
                anchor = PointSpec(24f, 23.5f),
                bounds = null,
                typeface = null,
                textSize = 8f,
                color = Color.WHITE,
                align = Paint.Align.CENTER,
                verticalAnchor = C47TextRenderer.TEXT_ANCHOR_CENTER,
            ),
            underline = LineAdornmentSpec(
                id = SoftkeyAdornmentSlot.OVERLAY_UNDERLINE.id,
                line = LineSpec(
                    start = PointSpec(25f, 28f),
                    end = PointSpec(30f, 28f),
                ),
                color = Color.WHITE,
                strokeWidth = KeyVisualPolicy.SOFTKEY_DECOR_STROKE_WIDTH,
            ),
        )

        SoftkeyOverlayPainter.draw(
            canvas = Canvas(bitmap),
            overlay = overlay,
            decorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = KeyVisualPolicy.SOFTKEY_DECOR_STROKE_WIDTH
            },
            dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            },
            auxPaint = C47TextRenderer.newTextPaint(Paint.Align.CENTER),
        )

        assertRegionContainsColor(bitmap, 24, 24, Color.WHITE, tolerance = 32)
        assertRegionContainsColor(bitmap, 28, 28, Color.WHITE, tolerance = 20)
    }

    private fun assertRegionContainsColor(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        expectedColor: Int,
        radius: Int = 3,
        tolerance: Int,
    ) {
        val foundMatch = ((centerX - radius)..(centerX + radius)).any { x ->
            ((centerY - radius)..(centerY + radius)).any { y ->
                x in 0 until bitmap.width &&
                    y in 0 until bitmap.height &&
                    colorsMatch(bitmap.getPixel(x, y), expectedColor, tolerance)
            }
        }

        assertTrue(
            "Expected $expectedColor near ($centerX, $centerY)",
            foundMatch,
        )
    }

    private fun colorsMatch(actual: Int, expected: Int, tolerance: Int): Boolean {
        return abs(Color.alpha(actual) - Color.alpha(expected)) <= tolerance &&
            abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            abs(Color.blue(actual) - Color.blue(expected)) <= tolerance
    }
}
