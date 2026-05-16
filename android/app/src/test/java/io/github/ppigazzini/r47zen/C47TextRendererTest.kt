package io.github.ppigazzini.r47zen

import android.graphics.Paint
import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class C47TextRendererTest {
    @Test
    fun newTextPaintEnablesAntialiasAndSubpixelWithoutLinearText() {
        val paint = C47TextRenderer.newTextPaint(Paint.Align.RIGHT)

        assertTrue(paint.isAntiAlias)
        assertTrue(paint.isSubpixelText)
        assertFalse(paint.isLinearText)
        assertEquals(Paint.Align.RIGHT, paint.textAlign)
    }

    @Test
    fun fittedTextSizeHonorsConfiguredMinimumScale() {
        val paint = C47TextRenderer.newTextPaint()
        C47TextRenderer.configureTextPaint(
            paint,
            typeface = Typeface.MONOSPACE,
            textSize = 100f,
            align = Paint.Align.LEFT,
            color = 0xFFFFFFFF.toInt(),
        )

        val fitted = C47TextRenderer.fittedTextSize(
            text = "LONG LABEL",
            paint = paint,
            baseSize = 100f,
            maxWidth = 1f,
            minScale = R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE,
        )

        assertEquals(58f, fitted, 0.01f)
    }
}
