package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathIterator
import android.graphics.Rect
import android.graphics.RectF
import java.util.Locale

// A Canvas that also writes what is drawn on it as SVG: every shape and every
// glyph becomes a filled path in the canvas's current matrix, so the SVG is the
// app's own drawing, not a second renderer of it. It draws on its bitmap too, so
// a view that reads back from the canvas still sees its pixels.
//
// It covers the calls the replica makes: rects, rounded rects, lines, circles,
// paths, and drawText, the last as the glyph outlines Paint.getTextPath gives,
// with underline and strike-through as the rects drawText draws;
// SvgRecordingCanvasTest holds those rects to the platform's pixels. A
// drawBitmap records where the bitmap lands in lcdWindows and draws nothing in
// the SVG; the page lays the LCD screen there. Shadows, shaders, and clips are
// not recorded.
internal class SvgRecordingCanvas(private val bitmap: Bitmap) : Canvas(bitmap) {
    private val runs = mutableListOf<Pair<Int, StringBuilder>>()
    val lcdWindows = mutableListOf<RectF>()

    override fun drawRect(rect: RectF, paint: Paint) {
        super.drawRect(rect, paint)
        record(Path().apply { addRect(rect, Path.Direction.CW) }, paint)
    }

    override fun drawRect(rect: Rect, paint: Paint) {
        super.drawRect(rect, paint)
        record(Path().apply { addRect(RectF(rect), Path.Direction.CW) }, paint)
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        super.drawRect(left, top, right, bottom, paint)
        record(Path().apply { addRect(left, top, right, bottom, Path.Direction.CW) }, paint)
    }

    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        super.drawRoundRect(rect, rx, ry, paint)
        record(Path().apply { addRoundRect(rect, rx, ry, Path.Direction.CW) }, paint)
    }

    override fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rx: Float,
        ry: Float,
        paint: Paint,
    ) {
        super.drawRoundRect(left, top, right, bottom, rx, ry, paint)
        record(Path().apply { addRoundRect(left, top, right, bottom, rx, ry, Path.Direction.CW) }, paint)
    }

    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        super.drawCircle(cx, cy, radius, paint)
        record(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }, paint)
    }

    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        super.drawLine(startX, startY, stopX, stopY, paint)
        val line = Path().apply {
            moveTo(startX, startY)
            lineTo(stopX, stopY)
        }
        val stroke = Paint(paint).apply { style = Paint.Style.STROKE }
        record(line, stroke)
    }

    override fun drawPath(path: Path, paint: Paint) {
        super.drawPath(path, paint)
        record(Path(path), paint)
    }

    override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        super.drawText(text, x, y, paint)
        recordText(text, x, y, paint)
    }

    override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        super.drawBitmap(bitmap, src, dst, paint)
        val window = RectF(dst)
        currentMatrix().mapRect(window)
        lcdWindows += window
    }

    private fun recordText(text: String, x: Float, y: Float, paint: Paint) {
        val glyphs = Path()
        val fill = Paint(paint).apply { style = Paint.Style.FILL }
        fill.getTextPath(text, 0, text.length, x, y, glyphs)
        if (paint.isUnderlineText || paint.isStrikeThruText) {
            // Draw each decoration where Canvas.drawText draws it: across the
            // text's advance from the start its alignment gives, the same start
            // getTextPath gives the glyphs, at the paint's own position and
            // thickness, and never thinner than one pixel. Anchored at x
            // instead, a centered or right-aligned label's line lands beside
            // its glyphs.
            val width = paint.measureText(text)
            val left = when (paint.textAlign) {
                Paint.Align.LEFT -> x
                Paint.Align.CENTER -> x - width / 2f
                Paint.Align.RIGHT -> x - width
            }
            if (paint.isUnderlineText) {
                val top = y + paint.underlinePosition
                glyphs.addRect(left, top, left + width, top + maxOf(1f, paint.underlineThickness), Path.Direction.CW)
            }
            if (paint.isStrikeThruText) {
                val top = y + paint.strikeThruPosition
                glyphs.addRect(left, top, left + width, top + maxOf(1f, paint.strikeThruThickness), Path.Direction.CW)
            }
        }
        record(glyphs, fill)
    }

    // Turn the shape into the outline the paint fills, put it in canvas
    // coordinates, and append it to the run of its color.
    private fun record(shape: Path, paint: Paint) {
        val color = paint.color
        if (Color.alpha(color) == 0) {
            return
        }
        val outline = Path()
        if (paint.style == Paint.Style.FILL) {
            outline.set(shape)
        } else {
            paint.getFillPath(shape, outline)
        }
        outline.transform(currentMatrix())
        val data = svgPathData(outline)
        if (data.isEmpty()) {
            return
        }
        val last = runs.lastOrNull()
        if (last != null && last.first == color) {
            last.second.append(data)
        } else {
            runs += color to StringBuilder(data)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentMatrix(): Matrix = Matrix().also { getMatrix(it) }

    fun toSvg(): String = buildString {
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
        append(bitmap.width).append(' ').append(bitmap.height)
        append("\" width=\"").append(bitmap.width)
        append("\" height=\"").append(bitmap.height).append("\">")
        runs.forEach { (color, data) ->
            append("<path fill=\"").append(hex(color)).append('"')
            val alpha = Color.alpha(color)
            if (alpha < OPAQUE) {
                append(" fill-opacity=\"").append(format(alpha / OPAQUE.toFloat())).append('"')
            }
            append(" d=\"").append(data).append("\"/>")
        }
        append("</svg>\n")
    }

    private companion object {
        const val OPAQUE = 255

        fun hex(color: Int): String = String.format(Locale.ROOT, "#%06x", color and 0xFFFFFF)

        fun format(value: Float): String {
            val rounded = String.format(Locale.ROOT, "%.1f", value)
            return if (rounded.endsWith(".0")) rounded.dropLast(2) else rounded
        }

        // Write the path's segments as SVG commands. SVG has no conic, the
        // segment Skia draws arcs with, so each becomes the cubic whose controls
        // sit k = 4w / (3(1 + w)) of the way to the conic's control point: for a
        // quarter circle, w = sqrt(2) / 2, that is the usual 0.5523.
        fun svgPathData(path: Path): String = buildString {
            val points = FloatArray(PATH_POINTS)
            val iterator = path.pathIterator
            while (iterator.hasNext()) {
                when (iterator.next(points, 0)) {
                    PathIterator.VERB_MOVE -> append('M').append(xy(points, 0))
                    PathIterator.VERB_LINE -> append('L').append(xy(points, 2))
                    PathIterator.VERB_QUAD -> append('Q').append(xy(points, 2)).append(' ').append(xy(points, 4))
                    PathIterator.VERB_CUBIC -> append('C').append(xy(points, 2)).append(' ')
                        .append(xy(points, 4)).append(' ').append(xy(points, 6))
                    PathIterator.VERB_CONIC -> append(conicAsCubic(points))
                    PathIterator.VERB_CLOSE -> append('Z')
                    else -> Unit
                }
            }
        }

        fun xy(points: FloatArray, at: Int): String = format(points[at]) + " " + format(points[at + 1])

        // PathIterator.next writes up to four points, and a conic's weight after
        // its three.
        private const val PATH_POINTS = 8
        private const val CONIC_WEIGHT_INDEX = 6

        fun conicAsCubic(points: FloatArray): String {
            val weight = points[CONIC_WEIGHT_INDEX]
            val k = 4f * weight / (3f * (1f + weight))
            val c1x = points[0] + k * (points[2] - points[0])
            val c1y = points[1] + k * (points[3] - points[1])
            val c2x = points[4] + k * (points[2] - points[4])
            val c2y = points[5] + k * (points[3] - points[5])
            return "C" + format(c1x) + " " + format(c1y) + " " + format(c2x) + " " + format(c2y) + " " +
                xy(points, 4)
        }
    }
}
