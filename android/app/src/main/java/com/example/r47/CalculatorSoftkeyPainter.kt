package com.example.r47

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs

internal class CalculatorSoftkeyPainter(
    private val defaultPrimaryColor: Int,
    private val letterColor: Int,
    private val mainKeyFillColor: Int,
    private val mainKeyPressedColor: Int,
    private val softkeyReverseColor: Int,
    private val softkeyReversePressedColor: Int,
    private val softkeyLightTextColor: Int,
    private val softkeyMetaLightColor: Int,
    private val softkeyValueLightColor: Int,
    private val softkeyPreviewColor: Int,
) {
    private companion object {
        private const val TEXT_ANCHOR_CENTER = 0
        private const val TEXT_ANCHOR_TOP = 1
        private const val TEXT_ANCHOR_BOTTOM = 2
    }

    private val softkeyRect = RectF()
    private val softkeyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val softkeyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val softkeyAuxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val softkeyValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val softkeyDecorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = KeyVisualPolicy.SOFTKEY_DECOR_STROKE_WIDTH
    }
    private val softkeyDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun draw(
        canvas: Canvas,
        keyState: KeypadKeySnapshot,
        fontSet: KeypadFontSet,
        width: Int,
        height: Int,
        isPressed: Boolean,
        drawKeySurfaces: Boolean,
    ) {
        val reverseVideo = keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_REVERSE_VIDEO)
        val showText = keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT) &&
            keyState.auxLabel.isNotBlank()
        val showValue = keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_VALUE) &&
            keyState.showValue != KeypadKeySnapshot.NO_VALUE
        val showOverlay = keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_CB) &&
            keyState.overlayState >= 0

        softkeyRect.set(
            KeyVisualPolicy.SOFTKEY_OUTER_INSET,
            KeyVisualPolicy.SOFTKEY_OUTER_INSET,
            width - KeyVisualPolicy.SOFTKEY_OUTER_INSET,
            height - KeyVisualPolicy.SOFTKEY_OUTER_INSET,
        )

        val fillColor = when {
            reverseVideo && isPressed -> softkeyReversePressedColor
            reverseVideo -> softkeyReverseColor
            isPressed -> mainKeyPressedColor
            else -> mainKeyFillColor
        }
        val decorColor = if (reverseVideo) softkeyLightTextColor else defaultPrimaryColor
        val primaryTextColor = if (reverseVideo) softkeyLightTextColor else defaultPrimaryColor
        val metaTextColor = if (reverseVideo) softkeyMetaLightColor else letterColor
        val valueTextColor = softkeyValueLightColor

        softkeyDecorPaint.color = decorColor
        softkeyDotPaint.color = decorColor

        val softkeySurfaceScale = width / R47ReferenceGeometry.STANDARD_KEY_WIDTH
        val cornerRadius = R47KeySurfacePolicy.SOFTKEY_DRAW_CORNER_RADIUS * softkeySurfaceScale
        if (drawKeySurfaces) {
            drawKeyChrome(
                canvas = canvas,
                rect = softkeyRect,
                fillPaint = softkeyFillPaint,
                fillColor = fillColor,
                cornerRadius = cornerRadius,
            )
        }
        if (drawKeySurfaces && keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_PREVIEW_TARGET)) {
            softkeyDecorPaint.color = softkeyPreviewColor
            canvas.drawLine(
                softkeyRect.left + KeyVisualPolicy.SOFTKEY_PREVIEW_LINE_SIDE_INSET,
                softkeyRect.bottom - KeyVisualPolicy.SOFTKEY_PREVIEW_LINE_BOTTOM_INSET,
                softkeyRect.right - KeyVisualPolicy.SOFTKEY_PREVIEW_LINE_SIDE_INSET,
                softkeyRect.bottom - KeyVisualPolicy.SOFTKEY_PREVIEW_LINE_BOTTOM_INSET,
                softkeyDecorPaint,
            )
            softkeyDecorPaint.color = decorColor
        }

        if (showValue) {
            val valueText = formatSoftkeyValue(keyState.showValue)
            if (valueText.isNotBlank()) {
                drawFittedText(
                    canvas = canvas,
                    text = valueText,
                    paint = softkeyValuePaint,
                    typeface = fontSet.numeric ?: fontSet.tiny ?: fontSet.standard,
                    baseSize = height * KeyVisualPolicy.SOFTKEY_VALUE_TEXT_SIZE_RATIO,
                    maxWidth = softkeyRect.width() * KeyVisualPolicy.SOFTKEY_VALUE_WIDTH_RATIO,
                    x = softkeyRect.right - KeyVisualPolicy.SOFTKEY_VALUE_RIGHT_INSET,
                    anchorY = softkeyRect.top + KeyVisualPolicy.SOFTKEY_VALUE_TOP_INSET,
                    color = valueTextColor,
                    align = Paint.Align.RIGHT,
                    verticalAnchor = TEXT_ANCHOR_TOP,
                )
            }
        }

        if (showOverlay) {
            drawSoftkeyOverlay(
                canvas = canvas,
                overlayState = keyState.overlayState,
                fontSet = fontSet,
                height = height,
                centerX = softkeyRect.right - KeyVisualPolicy.SOFTKEY_OVERLAY_CENTER_RIGHT_INSET,
                centerY = softkeyRect.bottom - KeyVisualPolicy.SOFTKEY_OVERLAY_CENTER_BOTTOM_INSET,
                color = decorColor,
            )
        }

        if (keyState.primaryLabel.isNotBlank()) {
            val primaryCenterY = if (showText) {
                softkeyRect.top + (softkeyRect.height() * KeyVisualPolicy.SOFTKEY_PRIMARY_TOP_RATIO)
            } else {
                softkeyRect.centerY()
            }
            val reservedRight = when {
                showOverlay -> KeyVisualPolicy.SOFTKEY_PRIMARY_RIGHT_RESERVE_WITH_OVERLAY
                else -> KeyVisualPolicy.SOFTKEY_PRIMARY_SIDE_INSET
            }
            drawFittedText(
                canvas = canvas,
                text = keyState.primaryLabel,
                paint = softkeyTextPaint,
                typeface = fontSet.standard,
                baseSize = R47LabelLayoutPolicy.DEFAULT_PRIMARY_LEGEND_TEXT_SIZE * softkeySurfaceScale,
                maxWidth = softkeyRect.width() - reservedRight - KeyVisualPolicy.SOFTKEY_PRIMARY_SIDE_INSET,
                x = softkeyRect.centerX(),
                anchorY = primaryCenterY,
                color = primaryTextColor,
            )
        }

        if (showText) {
            drawFittedText(
                canvas = canvas,
                text = keyState.auxLabel,
                paint = softkeyAuxPaint,
                typeface = fontSet.tiny ?: fontSet.standard,
                baseSize = height * KeyVisualPolicy.SOFTKEY_AUX_TEXT_SIZE_RATIO,
                maxWidth = softkeyRect.width() - KeyVisualPolicy.SOFTKEY_AUX_SIDE_INSET,
                x = softkeyRect.centerX(),
                anchorY = softkeyRect.bottom - KeyVisualPolicy.SOFTKEY_AUX_BOTTOM_INSET,
                color = metaTextColor,
                verticalAnchor = TEXT_ANCHOR_BOTTOM,
            )
        }

        if (keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_THROUGH)) {
            canvas.drawLine(
                softkeyRect.left + KeyVisualPolicy.SOFTKEY_STRIKE_SIDE_INSET,
                softkeyRect.centerY(),
                softkeyRect.right - KeyVisualPolicy.SOFTKEY_STRIKE_SIDE_INSET,
                softkeyRect.centerY(),
                softkeyDecorPaint,
            )
        }
        if (keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_OUT)) {
            canvas.drawLine(
                softkeyRect.left + KeyVisualPolicy.SOFTKEY_STRIKE_OUT_SIDE_INSET,
                softkeyRect.top + KeyVisualPolicy.SOFTKEY_STRIKE_OUT_VERTICAL_INSET,
                softkeyRect.right - KeyVisualPolicy.SOFTKEY_STRIKE_OUT_SIDE_INSET,
                softkeyRect.bottom - KeyVisualPolicy.SOFTKEY_STRIKE_OUT_VERTICAL_INSET,
                softkeyDecorPaint,
            )
        }
    }

    fun buildContentDescription(keyState: KeypadKeySnapshot): String {
        return buildString {
            append(keyState.primaryLabel)
            if (keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT) && keyState.auxLabel.isNotBlank()) {
                append(", ")
                append(keyState.auxLabel)
            }
            val valueText = formatSoftkeyValue(keyState.showValue)
            if (valueText.isNotBlank()) {
                append(", ")
                append(valueText)
            }
        }
    }

    private fun drawKeyChrome(
        canvas: Canvas,
        rect: RectF,
        fillPaint: Paint,
        fillColor: Int,
        cornerRadius: Float,
    ) {
        fillPaint.color = fillColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
    }

    private fun drawSoftkeyOverlay(
        canvas: Canvas,
        overlayState: Int,
        fontSet: KeypadFontSet,
        height: Int,
        centerX: Float,
        centerY: Float,
        color: Int,
    ) {
        val size = KeyVisualPolicy.SOFTKEY_OVERLAY_SIZE
        softkeyDecorPaint.color = color

        when (overlayState) {
            KeypadSceneContract.OVERLAY_RB_FALSE -> {
                canvas.drawCircle(centerX, centerY, size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO, softkeyDecorPaint)
            }
            KeypadSceneContract.OVERLAY_RB_TRUE -> {
                canvas.drawCircle(centerX, centerY, size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO, softkeyDecorPaint)
                canvas.drawCircle(centerX, centerY, size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_DOT_RATIO, softkeyDotPaint.apply { this.color = color })
            }
            KeypadSceneContract.OVERLAY_CB_FALSE -> {
                canvas.drawRect(
                    centerX - size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    centerY - size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    centerX + size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    centerY + size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    softkeyDecorPaint,
                )
            }
            KeypadSceneContract.OVERLAY_CB_TRUE -> {
                canvas.drawRect(
                    centerX - size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    centerY - size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    centerX + size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    centerY + size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    softkeyDecorPaint,
                )
                canvas.drawLine(
                    centerX - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_LEFT_X,
                    centerY,
                    centerX - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_MID_X,
                    centerY + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    softkeyDecorPaint,
                )
                canvas.drawLine(
                    centerX - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_MID_X,
                    centerY + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    centerX + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_RIGHT_X,
                    centerY - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    softkeyDecorPaint,
                )
            }
            KeypadSceneContract.OVERLAY_MB_FALSE,
            KeypadSceneContract.OVERLAY_MB_TRUE -> {
                val rect = RectF(
                    centerX - KeyVisualPolicy.SOFTKEY_OVERLAY_MB_HALF_WIDTH,
                    centerY - KeyVisualPolicy.SOFTKEY_OVERLAY_MB_HALF_HEIGHT,
                    centerX + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_HALF_WIDTH,
                    centerY + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_HALF_HEIGHT,
                )
                canvas.drawRoundRect(
                    rect,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_MB_CORNER_RADIUS,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_MB_CORNER_RADIUS,
                    softkeyDecorPaint,
                )
                drawFittedText(
                    canvas = canvas,
                    text = "M",
                    paint = softkeyAuxPaint,
                    typeface = fontSet.tiny ?: fontSet.standard,
                    baseSize = height * KeyVisualPolicy.SOFTKEY_OVERLAY_MB_TEXT_SIZE_RATIO,
                    maxWidth = KeyVisualPolicy.SOFTKEY_OVERLAY_MB_TEXT_MAX_WIDTH,
                    x = centerX,
                    anchorY = centerY - KeyVisualPolicy.SOFTKEY_OVERLAY_MB_TEXT_BASELINE_OFFSET,
                    color = color,
                )
                if (overlayState == KeypadSceneContract.OVERLAY_MB_TRUE) {
                    canvas.drawLine(
                        centerX + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_UNDERLINE_START_X,
                        centerY + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_UNDERLINE_Y,
                        centerX + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_UNDERLINE_END_X,
                        centerY + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_UNDERLINE_Y,
                        softkeyDecorPaint,
                    )
                }
            }
        }
    }

    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        typeface: Typeface?,
        baseSize: Float,
        maxWidth: Float,
        x: Float,
        anchorY: Float,
        color: Int,
        align: Paint.Align = Paint.Align.CENTER,
        verticalAnchor: Int = TEXT_ANCHOR_CENTER,
    ) {
        if (text.isBlank()) {
            return
        }

        paint.typeface = typeface
        paint.textAlign = align
        paint.color = color
        paint.textSize = baseSize

        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            paint.textSize =
                (baseSize * (maxWidth / measured)).coerceAtLeast(baseSize * R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE)
        }

        val metrics = paint.fontMetrics
        val baseline = when (verticalAnchor) {
            TEXT_ANCHOR_TOP -> anchorY - metrics.ascent
            TEXT_ANCHOR_BOTTOM -> anchorY - metrics.descent
            else -> anchorY - ((metrics.ascent + metrics.descent) / 2f)
        }

        canvas.drawText(text, x, baseline, paint)
    }

    private fun formatSoftkeyValue(showValue: Int): String {
        return when (showValue) {
            KeypadKeySnapshot.NO_VALUE,
            -127 -> ""
            else -> {
                val prefix = if (showValue < 0) "-" else ""
                prefix + abs(showValue).toString()
            }
        }
    }
}
