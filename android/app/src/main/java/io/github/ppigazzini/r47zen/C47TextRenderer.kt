package io.github.ppigazzini.r47zen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.roundToInt

internal object C47TextRenderer {
    private data class ResolvedTextMetrics(
        val textSize: Float,
        val width: Float,
        val ascent: Float,
        val descent: Float,
    )

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
        val resolvedMetrics = resolveTextMetrics(
            text = text,
            paint = paint,
            typeface = typeface,
            baseSize = textSize,
            align = align,
            textScaleX = textScaleX,
        ) ?: return null

        return LabelSpec(
            id = id,
            text = text,
            visible = visible,
            anchor = PointSpec(x, anchorY),
            bounds = boundsFromResolvedMetrics(
                resolvedMetrics = resolvedMetrics,
                x = x,
                anchorY = anchorY,
                align = align,
                verticalAnchor = verticalAnchor,
            ),
            typeface = typeface,
            textSize = resolvedMetrics.textSize,
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
        val resolvedMetrics = resolveTextMetrics(
            text = text,
            paint = paint,
            typeface = typeface,
            baseSize = baseSize,
            align = align,
            textScaleX = textScaleX,
            maxWidth = maxWidth,
            minScale = minScale,
        ) ?: return null

        return LabelSpec(
            id = id,
            text = text,
            visible = visible,
            anchor = PointSpec(x, anchorY),
            bounds = boundsFromResolvedMetrics(
                resolvedMetrics = resolvedMetrics,
                x = x,
                anchorY = anchorY,
                align = align,
                verticalAnchor = verticalAnchor,
            ),
            typeface = typeface,
            textSize = resolvedMetrics.textSize,
            color = color,
            align = align,
            verticalAnchor = verticalAnchor,
            alpha = alpha,
            textScaleX = textScaleX,
            underline = underline,
            strikeThrough = strikeThrough,
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
        val resolvedMetrics = resolveTextMetrics(
            text = text,
            paint = paint,
            typeface = typeface,
            baseSize = textSize,
            align = align,
            textScaleX = textScaleX,
        ) ?: return null

        return boundsFromResolvedMetrics(
            resolvedMetrics = resolvedMetrics,
            x = x,
            anchorY = anchorY,
            align = align,
            verticalAnchor = verticalAnchor,
        )
    }

    fun colorWithAlpha(color: Int, alpha: Float): Int {
        return applyAlpha(color, alpha)
    }

    private fun resolveTextMetrics(
        text: String,
        paint: Paint,
        typeface: Typeface?,
        baseSize: Float,
        align: Paint.Align,
        textScaleX: Float,
        maxWidth: Float? = null,
        minScale: Float? = null,
    ): ResolvedTextMetrics? {
        if (text.isBlank() || baseSize <= 0f) {
            return null
        }

        configureTextPaint(
            paint = paint,
            typeface = typeface,
            textSize = baseSize,
            align = align,
            color = Color.WHITE,
            textScaleX = textScaleX,
        )
        val baseWidth = paint.measureText(text)
        val resolvedTextSize = if (maxWidth != null && minScale != null && maxWidth > 0f) {
            if (baseWidth <= maxWidth || baseWidth <= 0f) {
                baseSize
            } else {
                (baseSize * (maxWidth / baseWidth)).coerceAtLeast(baseSize * minScale)
            }
        } else {
            baseSize
        }

        if (resolvedTextSize != baseSize) {
            paint.textSize = resolvedTextSize
        }
        val metrics = paint.fontMetrics
        val resolvedWidth = if (resolvedTextSize == baseSize || baseSize <= 0f) {
            baseWidth
        } else {
            paint.measureText(text)
        }
        return ResolvedTextMetrics(
            textSize = resolvedTextSize,
            width = resolvedWidth,
            ascent = metrics.ascent,
            descent = metrics.descent,
        )
    }

    private fun boundsFromResolvedMetrics(
        resolvedMetrics: ResolvedTextMetrics,
        x: Float,
        anchorY: Float,
        align: Paint.Align,
        verticalAnchor: Int,
    ): RectSpec {
        val left = when (align) {
            Paint.Align.LEFT -> x
            Paint.Align.RIGHT -> x - resolvedMetrics.width
            Paint.Align.CENTER -> x - (resolvedMetrics.width * 0.5f)
        }
        val top = when (verticalAnchor) {
            TEXT_ANCHOR_TOP -> anchorY
            TEXT_ANCHOR_BOTTOM -> anchorY - (-resolvedMetrics.ascent + resolvedMetrics.descent)
            else -> anchorY - ((resolvedMetrics.descent - resolvedMetrics.ascent) * 0.5f)
        }
        return RectSpec(
            left = left,
            top = top,
            right = left + resolvedMetrics.width,
            bottom = top + (resolvedMetrics.descent - resolvedMetrics.ascent),
        )
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
