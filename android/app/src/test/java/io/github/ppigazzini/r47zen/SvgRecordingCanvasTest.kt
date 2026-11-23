package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// Hold each text decoration SvgRecordingCanvas writes to the one the platform's
// Canvas.drawText draws for the same call. The oracle is the bitmap: the pixels
// a decorated drawText changes over the plain one are the decoration, drawn by
// the platform and never by the recorder. The recorded decoration is the rect
// the decorated SVG path carries after the plain one. It runs in the platform's
// default typeface and in the C47 standard font the keypad draws its f and g
// labels in, whose own underline metrics put the line elsewhere.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgRecordingCanvasTest {
    private val typefaces = mapOf(
        "default" to Typeface.DEFAULT,
        "standard" to Typeface.createFromAsset(
            ApplicationProvider.getApplicationContext<Context>().assets,
            STANDARD_FONT,
        ),
    )

    @Test
    fun underlineSpansTheTextWhereTheCanvasDrawsItAtEveryAlignment() {
        assertDecorationMatchesTheCanvasEverywhere { isUnderlineText = true }
    }

    @Test
    fun strikeThroughSpansTheTextWhereTheCanvasDrawsItAtEveryAlignment() {
        assertDecorationMatchesTheCanvasEverywhere { isStrikeThruText = true }
    }

    private fun assertDecorationMatchesTheCanvasEverywhere(decorate: Paint.() -> Unit) {
        typefaces.forEach { (name, typeface) ->
            Paint.Align.entries.forEach { align ->
                assertDecorationMatchesTheCanvas("$name $align", typeface, align, decorate)
            }
        }
    }

    private fun assertDecorationMatchesTheCanvas(
        case: String,
        face: Typeface,
        align: Paint.Align,
        decorate: Paint.() -> Unit,
    ) {
        val plain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = face
            textSize = TEXT_SIZE
            textAlign = align
        }
        val decorated = Paint(plain).apply(decorate)

        val drawn = changedPixels(drawnPixels(plain), drawnPixels(decorated))
        val recorded = recordedDecoration(recordedPath(plain), recordedPath(decorated))

        val label = "$case decoration"
        assertEquals("$label left", drawn.left.toFloat(), recorded.left, TOLERANCE)
        assertEquals("$label right", drawn.right.toFloat(), recorded.right, TOLERANCE)
        assertEquals("$label top", drawn.top.toFloat(), recorded.top, TOLERANCE)
        assertEquals("$label bottom", drawn.bottom.toFloat(), recorded.bottom, TOLERANCE)
    }

    private fun drawnPixels(paint: Paint): IntArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(TEXT, ANCHOR_X, BASELINE_Y, paint)
        return IntArray(WIDTH * HEIGHT).also { bitmap.getPixels(it, 0, WIDTH, 0, 0, WIDTH, HEIGHT) }
    }

    private fun recordedPath(paint: Paint): String {
        val canvas = SvgRecordingCanvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888))
        canvas.drawText(TEXT, ANCHOR_X, BASELINE_Y, paint)
        val paths = PATH_DATA.findAll(canvas.toSvg()).map { it.groupValues[1] }.toList()
        assertEquals("one run of one color", 1, paths.size)
        return paths.single()
    }

    // The bounds of the pixels that differ, right and bottom exclusive, so they
    // compare with the edges of the rect that covers them.
    private fun changedPixels(before: IntArray, after: IntArray): Bounds {
        val changed = before.indices.filter { before[it] != after[it] }
        assertTrue("the decoration changes the drawn pixels", changed.isNotEmpty())
        return Bounds(
            left = changed.minOf { it % WIDTH },
            top = changed.minOf { it / WIDTH },
            right = changed.maxOf { it % WIDTH } + 1,
            bottom = changed.maxOf { it / WIDTH } + 1,
        )
    }

    private fun recordedDecoration(plain: String, decorated: String): RecordedRect {
        assertTrue("the decoration follows the glyphs in one path", decorated.startsWith(plain))
        val rect = decorated.removePrefix(plain)
        assertTrue("the decoration is one closed rect: $rect", RECT.matches(rect))
        val numbers = NUMBER.findAll(rect).map { it.value.toFloat() }.toList()
        val xs = numbers.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = numbers.filterIndexed { index, _ -> index % 2 == 1 }
        return RecordedRect(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private data class RecordedRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private companion object {
        // A three-letter g label in the keypad's font, at a size whose
        // decoration spans several pixels.
        const val TEXT = "TRG"
        const val TEXT_SIZE = 48f
        const val STANDARD_FONT = "fonts/C47__StandardFont.ttf"
        const val WIDTH = 400
        const val HEIGHT = 120
        const val ANCHOR_X = 200f
        const val BASELINE_Y = 80f

        // The canvas antialiases the decoration's edges into the pixel each
        // one crosses, so a pixel bound sits up to one pixel outside the edge.
        const val TOLERANCE = 1f

        val PATH_DATA = Regex("""<path fill="#[0-9a-f]{6}" d="([^"]*)"/>""")
        val NUMBER = Regex("""-?\d+(?:\.\d+)?""")
        val RECT = Regex("""M[-\d. ]+(?:L[-\d. ]+){3}Z""")
    }
}
