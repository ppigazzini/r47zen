package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
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
class CalculatorSoftkeyPainterCanvasTest {
    @Test
    fun buildRenderSpecExposesValueFieldBoundsAndOverlayCenter() {
        val renderSpec = softkeyPainter().buildRenderSpec(
            keyState = KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "FILE",
                auxLabel = "LOAD",
                sceneFlags = KeypadSceneContract.SCENE_FLAG_SHOW_TEXT or
                    KeypadSceneContract.SCENE_FLAG_SHOW_VALUE or
                    KeypadSceneContract.SCENE_FLAG_SHOW_CB,
                showValue = 12,
                overlayState = KeypadSceneContract.OVERLAY_CB_TRUE,
            ),
            fontSet = KeypadFontSet(standard = null, numeric = null, tiny = null),
            width = WIDTH,
            height = HEIGHT,
            isPressed = false,
            drawKeySurfaces = true,
        )

        val geometry = renderSpec.geometry as SoftkeyGeometrySpec
        val valueFieldBounds = requireNotNull(geometry.valueFieldBounds)
        val overlayCenter = requireNotNull(geometry.overlayCenter)

        assertEquals(119.08f, valueFieldBounds.left, 0.01f)
        assertEquals(8f, valueFieldBounds.top, 0.01f)
        assertEquals(183f, valueFieldBounds.right, 0.01f)
        assertEquals(33.92f, valueFieldBounds.bottom, 0.01f)
        assertEquals(180f, overlayCenter.x, 0.01f)
        assertEquals(130f, overlayCenter.y, 0.01f)
    }

    @Test
    fun drawRendersReverseFillPreviewAndStrikePixels() {
        val bitmap = render(
            KeypadKeySnapshot.EMPTY.copy(
                sceneFlags = KeypadSceneContract.SCENE_FLAG_REVERSE_VIDEO or
                    KeypadSceneContract.SCENE_FLAG_PREVIEW_TARGET or
                    KeypadSceneContract.SCENE_FLAG_STRIKE_THROUGH or
                    KeypadSceneContract.SCENE_FLAG_STRIKE_OUT,
            ),
        )

        assertRegionContainsColor(bitmap, 20, 20, Color.rgb(96, 96, 96), tolerance = 4)
        assertRegionContainsColor(bitmap, 96, 138, Color.rgb(229, 171, 90), tolerance = 10)
        assertRegionContainsColor(bitmap, 96, 72, Color.WHITE, tolerance = 28)
        assertRegionContainsColor(bitmap, 30, 27, Color.WHITE, tolerance = 28)
    }

    @Test
    fun drawRendersCheckboxOverlayPixels() {
        val bitmap = render(
            KeypadKeySnapshot.EMPTY.copy(
                sceneFlags = KeypadSceneContract.SCENE_FLAG_SHOW_CB,
                overlayState = KeypadSceneContract.OVERLAY_CB_TRUE,
            ),
        )

        assertRegionContainsColor(bitmap, 176, 130, Color.WHITE, tolerance = 28)
        assertRegionContainsColor(bitmap, 183, 128, Color.WHITE, tolerance = 28)
    }

    @Test
    fun drawRendersRadioOverlayPixels() {
        val bitmap = render(
            KeypadKeySnapshot.EMPTY.copy(
                sceneFlags = KeypadSceneContract.SCENE_FLAG_SHOW_CB,
                overlayState = KeypadSceneContract.OVERLAY_RB_TRUE,
            ),
        )

        assertRegionContainsColor(bitmap, 180, 130, Color.WHITE, tolerance = 12)
        assertRegionContainsColor(bitmap, 180, 125, Color.WHITE, tolerance = 28)
    }

    @Test
    fun drawRendersMenuBadgeOverlayPixels() {
        val bitmap = render(
            KeypadKeySnapshot.EMPTY.copy(
                sceneFlags = KeypadSceneContract.SCENE_FLAG_SHOW_CB,
                overlayState = KeypadSceneContract.OVERLAY_MB_TRUE,
            ),
        )

        assertRegionContainsColor(bitmap, 180, 125, Color.WHITE, tolerance = 28)
        assertRegionContainsColor(bitmap, 183, 134, Color.WHITE, tolerance = 28)
    }

    private fun render(keyState: KeypadKeySnapshot): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        softkeyPainter().draw(
            Canvas(bitmap),
            keyState,
            KeypadFontSet(standard = null, numeric = null, tiny = null),
            WIDTH,
            HEIGHT,
            isPressed = false,
            drawKeySurfaces = true,
        )
        return bitmap
    }

    private fun softkeyPainter(): CalculatorSoftkeyPainter {
        return CalculatorSoftkeyPainter(
            defaultPrimaryColor = Color.WHITE,
            letterColor = Color.parseColor("#A5A5A5"),
            mainKeyFillColor = Color.rgb(64, 64, 64),
            mainKeyPressedColor = Color.parseColor("#744A2E"),
            softkeyReverseColor = Color.rgb(96, 96, 96),
            softkeyReversePressedColor = Color.parseColor("#744A2E"),
            softkeyLightTextColor = Color.parseColor("#F4F7F9"),
            softkeyMetaLightColor = Color.parseColor("#C9D0D6"),
            softkeyValueLightColor = Color.rgb(240, 191, 122),
            softkeyPreviewColor = Color.rgb(229, 171, 90),
        )
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

    private companion object {
        private const val WIDTH = 192
        private const val HEIGHT = 144
    }
}
