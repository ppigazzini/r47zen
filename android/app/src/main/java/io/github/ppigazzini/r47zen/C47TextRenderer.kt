package io.github.ppigazzini.r47zen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.roundToInt

internal object C47TextRenderer {
    const val TEXT_ANCHOR_CENTER = 0
    const val TEXT_ANCHOR_TOP = 1
    const val TEXT_ANCHOR_BOTTOM = 2

    fun newTextPaint(align: Paint.Align = Paint.Align.LEFT): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = align
            isSubpixelText = true
            isLinearText = false
        }
    }

    fun configureTextPaint(
        paint: Paint,
        typeface: Typeface?,
        textSize: Float,
        align: Paint.Align,
        color: Int,
        alpha: Float = 1f,
        textScaleX: Float = 1f,
        underline: Boolean = false,
        strikeThrough: Boolean = false,
    ) {
        paint.typeface = typeface
        paint.textSize = textSize
        paint.textAlign = align
        paint.color = applyAlpha(color, alpha)
        paint.textScaleX = textScaleX
        paint.isUnderlineText = underline
        paint.isStrikeThruText = strikeThrough
    }

    fun fittedTextSize(
        text: String,
        paint: Paint,
        baseSize: Float,
        maxWidth: Float,
        minScale: Float,
    ): Float {
        if (text.isBlank() || maxWidth <= 0f) {
            return baseSize
        }

        paint.textSize = baseSize
        val measured = paint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) {
            return baseSize
        }

        return (baseSize * (maxWidth / measured)).coerceAtLeast(baseSize * minScale)
    }

    fun buildLabelSpec(
        id: String,
        text: String,
        paint: Paint,
        typeface: Typeface?,
        textSize: Float,
        x: Float,
        anchorY: Float,
        color: Int,
        align: Paint.Align = Paint.Align.CENTER,
        verticalAnchor: Int = TEXT_ANCHOR_CENTER,
        alpha: Float = 1f,
        textScaleX: Float = 1f,
        underline: Boolean = false,
        strikeThrough: Boolean = false,
        visible: Boolean = true,
    ): LabelSpec? {
        if (text.isBlank() || textSize <= 0f) {
            return null
        }

        val bounds = resolveTextBounds(
            text = text,
            paint = paint,
            typeface = typeface,
            textSize = textSize,
            x = x,
            anchorY = anchorY,
            align = align,
            verticalAnchor = verticalAnchor,
            textScaleX = textScaleX,
        )
        return LabelSpec(
            id = id,
            text = text,
            visible = visible,
            anchor = PointSpec(x, anchorY),
            bounds = bounds,
            typeface = typeface,
            textSize = textSize,
            color = color,
            align = align,
            verticalAnchor = verticalAnchor,
            alpha = alpha,
            textScaleX = textScaleX,
            underline = underline,
            strikeThrough = strikeThrough,
        )
    }

    fun buildFittedLabelSpec(
        id: String,
        text: String,
        paint: Paint,
        typeface: Typeface?,
        baseSize: Float,
        maxWidth: Float,
        x: Float,
        anchorY: Float,
        color: Int,
        minScale: Float,
        align: Paint.Align = Paint.Align.CENTER,
        verticalAnchor: Int = TEXT_ANCHOR_CENTER,
        alpha: Float = 1f,
        textScaleX: Float = 1f,
        underline: Boolean = false,
        strikeThrough: Boolean = false,
        visible: Boolean = true,
    ): LabelSpec? {
        if (text.isBlank()) {
            return null
        }

        configureTextPaint(
            paint = paint,
            typeface = typeface,
            textSize = baseSize,
            align = align,
            color = color,
            alpha = alpha,
            textScaleX = textScaleX,
            underline = underline,
            strikeThrough = strikeThrough,
        )
        val resolvedTextSize = fittedTextSize(
            text = text,
            paint = paint,
            baseSize = baseSize,
            maxWidth = maxWidth,
            minScale = minScale,
        )
        return buildLabelSpec(
            id = id,
            text = text,
            paint = paint,
            typeface = typeface,
            textSize = resolvedTextSize,
            x = x,
            anchorY = anchorY,
            color = color,
            align = align,
            verticalAnchor = verticalAnchor,
            alpha = alpha,
            textScaleX = textScaleX,
            underline = underline,
            strikeThrough = strikeThrough,
            visible = visible,
        )
    }

    fun fontMetricsHeight(
        paint: Paint,
        typeface: Typeface?,
        textSize: Float,
    ): Float {
        paint.typeface = typeface
        paint.textSize = textSize
        val metrics = paint.fontMetrics
        return metrics.descent - metrics.ascent
    }

    fun textBottomOffset(
        paint: Paint,
        typeface: Typeface?,
        textSize: Float,
    ): Float {
        paint.typeface = typeface
        paint.textSize = textSize
        val metrics = paint.fontMetrics
        return -metrics.ascent + metrics.descent
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        typeface: Typeface?,
        textSize: Float,
        x: Float,
        anchorY: Float,
        color: Int,
        align: Paint.Align = Paint.Align.CENTER,
        verticalAnchor: Int = TEXT_ANCHOR_CENTER,
        alpha: Float = 1f,
        textScaleX: Float = 1f,
        underline: Boolean = false,
        strikeThrough: Boolean = false,
    ) {
        if (text.isBlank() || textSize <= 0f) {
            return
        }

        configureTextPaint(
            paint,
            typeface = typeface,
            textSize = textSize,
            align = align,
            color = color,
            alpha = alpha,
            textScaleX = textScaleX,
            underline = underline,
            strikeThrough = strikeThrough,
        )
        val metrics = paint.fontMetrics
        val baseline = when (verticalAnchor) {
            TEXT_ANCHOR_TOP -> anchorY - metrics.ascent
            TEXT_ANCHOR_BOTTOM -> anchorY - metrics.descent
            else -> anchorY - ((metrics.ascent + metrics.descent) / 2f)
        }
        canvas.drawText(text, x, baseline, paint)
    }

    fun resolveTextBounds(
        text: String,
        paint: Paint,
        typeface: Typeface?,
        textSize: Float,
        x: Float,
        anchorY: Float,
        align: Paint.Align = Paint.Align.CENTER,
        verticalAnchor: Int = TEXT_ANCHOR_CENTER,
        textScaleX: Float = 1f,
    ): RectSpec? {
        if (text.isBlank() || textSize <= 0f) {
            return null
        }

        configureTextPaint(
            paint = paint,
            typeface = typeface,
            textSize = textSize,
            align = align,
            color = Color.WHITE,
            textScaleX = textScaleX,
        )
        val metrics = paint.fontMetrics
        val width = paint.measureText(text)
        val left = when (align) {
            Paint.Align.LEFT -> x
            Paint.Align.RIGHT -> x - width
            Paint.Align.CENTER -> x - (width * 0.5f)
        }
        val top = when (verticalAnchor) {
            TEXT_ANCHOR_TOP -> anchorY
            TEXT_ANCHOR_BOTTOM -> anchorY - (-metrics.ascent + metrics.descent)
            else -> anchorY - ((metrics.descent - metrics.ascent) * 0.5f)
        }
        return RectSpec(
            left = left,
            top = top,
            right = left + width,
            bottom = top + (metrics.descent - metrics.ascent),
        )
    }

    fun colorWithAlpha(color: Int, alpha: Float): Int {
        return applyAlpha(color, alpha)
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        val resolvedAlpha = (Color.alpha(color) * clampedAlpha).roundToInt()
        return Color.argb(
            resolvedAlpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }
}
