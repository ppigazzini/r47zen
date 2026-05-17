package io.github.ppigazzini.r47zen

import android.graphics.Canvas
import android.graphics.Paint
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
    private val softkeyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val softkeyTextPaint = C47TextRenderer.newTextPaint(Paint.Align.CENTER)
    private val softkeyAuxPaint = C47TextRenderer.newTextPaint(Paint.Align.CENTER)
    private val softkeyValuePaint = C47TextRenderer.newTextPaint(Paint.Align.RIGHT)
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
        val renderSpec = buildRenderSpec(
            keyState = keyState,
            fontSet = fontSet,
            width = width,
            height = height,
            isPressed = isPressed,
            drawKeySurfaces = drawKeySurfaces,
        )
        drawRenderSpec(canvas, renderSpec)
    }

    internal fun buildRenderSpec(
        keyState: KeypadKeySnapshot,
        fontSet: KeypadFontSet,
        width: Int,
        height: Int,
        isPressed: Boolean,
        drawKeySurfaces: Boolean,
    ): KeyRenderSpec {
        val reverseVideo = keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_REVERSE_VIDEO)
        val showText = keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT) &&
            keyState.auxLabel.isNotBlank()
        val showValue = keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_VALUE) &&
            keyState.showValue != KeypadKeySnapshot.NO_VALUE
        val showOverlay = keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_CB) &&
            keyState.overlayState >= 0

        val softkeyBounds = RectSpec(
            left = KeyVisualPolicy.SOFTKEY_OUTER_INSET,
            top = KeyVisualPolicy.SOFTKEY_OUTER_INSET,
            right = width - KeyVisualPolicy.SOFTKEY_OUTER_INSET,
            bottom = height - KeyVisualPolicy.SOFTKEY_OUTER_INSET,
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
        val chrome = KeyChromeSpec(
            bounds = softkeyBounds,
            fillColor = fillColor,
            cornerRadius = cornerRadius,
            drawSurface = drawKeySurfaces,
            pressedAccents = if (drawKeySurfaces && isPressed) {
                buildPressedAccentSpecs(
                    bounds = softkeyBounds,
                    width = width,
                    reverseVideo = reverseVideo,
                )
            } else {
                emptyList()
            },
        )

        val labels = mutableListOf<LabelSpec>()
        val adornments = mutableListOf<AdornmentSpec>()

        val previewLine = if (
            drawKeySurfaces &&
            keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_PREVIEW_TARGET)
        ) {
            LineSpec(
                start = PointSpec(
                    softkeyBounds.left + KeyVisualPolicy.SOFTKEY_PREVIEW_LINE_SIDE_INSET,
                    softkeyBounds.bottom - KeyVisualPolicy.SOFTKEY_PREVIEW_LINE_BOTTOM_INSET,
                ),
                end = PointSpec(
                    softkeyBounds.right - KeyVisualPolicy.SOFTKEY_PREVIEW_LINE_SIDE_INSET,
                    softkeyBounds.bottom - KeyVisualPolicy.SOFTKEY_PREVIEW_LINE_BOTTOM_INSET,
                ),
            )
        } else {
            null
        }
        previewLine?.let { line ->
            adornments += LineAdornmentSpec(
                id = ADORNMENT_ID_PREVIEW,
                line = line,
                color = softkeyPreviewColor,
                strokeWidth = KeyVisualPolicy.SOFTKEY_DECOR_STROKE_WIDTH,
            )
        }

        val valueFieldBounds = if (showValue) {
            val valueFieldRight = softkeyBounds.right - KeyVisualPolicy.SOFTKEY_VALUE_RIGHT_INSET
            val valueFieldWidth = softkeyBounds.width * KeyVisualPolicy.SOFTKEY_VALUE_WIDTH_RATIO
            RectSpec(
                left = valueFieldRight - valueFieldWidth,
                top = softkeyBounds.top + KeyVisualPolicy.SOFTKEY_VALUE_TOP_INSET,
                right = valueFieldRight,
                bottom = softkeyBounds.top + KeyVisualPolicy.SOFTKEY_VALUE_TOP_INSET +
                    (height * KeyVisualPolicy.SOFTKEY_VALUE_TEXT_SIZE_RATIO),
            )
        } else {
            null
        }
        val valueText = formatSoftkeyValue(keyState.showValue)
        if (showValue && valueText.isNotBlank()) {
            C47TextRenderer.buildFittedLabelSpec(
                id = LABEL_ID_VALUE,
                text = valueText,
                paint = softkeyValuePaint,
                typeface = C47TypefacePolicy.standardFirst(
                    text = valueText,
                    fontSet = fontSet,
                ),
                baseSize = height * KeyVisualPolicy.SOFTKEY_VALUE_TEXT_SIZE_RATIO,
                maxWidth = softkeyBounds.width * KeyVisualPolicy.SOFTKEY_VALUE_WIDTH_RATIO,
                x = softkeyBounds.right - KeyVisualPolicy.SOFTKEY_VALUE_RIGHT_INSET,
                anchorY = softkeyBounds.top + KeyVisualPolicy.SOFTKEY_VALUE_TOP_INSET,
                color = valueTextColor,
                minScale = R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE,
                align = Paint.Align.RIGHT,
                verticalAnchor = C47TextRenderer.TEXT_ANCHOR_TOP,
            )?.let(labels::add)
        }

        val overlayCenter = if (showOverlay) {
            PointSpec(
                softkeyBounds.right - KeyVisualPolicy.SOFTKEY_OVERLAY_CENTER_RIGHT_INSET,
                softkeyBounds.bottom - KeyVisualPolicy.SOFTKEY_OVERLAY_CENTER_BOTTOM_INSET,
            )
        } else {
            null
        }
        overlayCenter?.let { center ->
            adornments += buildSoftkeyOverlaySpec(
                overlayState = keyState.overlayState,
                fontSet = fontSet,
                height = height,
                center = center,
                color = decorColor,
            )
        }

        if (keyState.primaryLabel.isNotBlank()) {
            val primaryCenterY = if (showText) {
                softkeyBounds.top + (softkeyBounds.height * KeyVisualPolicy.SOFTKEY_PRIMARY_TOP_RATIO)
            } else {
                softkeyBounds.centerY
            }
            val reservedRight = if (showOverlay) {
                KeyVisualPolicy.SOFTKEY_PRIMARY_RIGHT_RESERVE_WITH_OVERLAY
            } else {
                KeyVisualPolicy.SOFTKEY_PRIMARY_SIDE_INSET
            }
            C47TextRenderer.buildFittedLabelSpec(
                id = LABEL_ID_PRIMARY,
                text = keyState.primaryLabel,
                paint = softkeyTextPaint,
                typeface = C47TypefacePolicy.standardFirst(
                    text = keyState.primaryLabel,
                    fontSet = fontSet,
                ),
                baseSize = R47LabelLayoutPolicy.DEFAULT_PRIMARY_LEGEND_TEXT_SIZE * softkeySurfaceScale,
                maxWidth = softkeyBounds.width - reservedRight - KeyVisualPolicy.SOFTKEY_PRIMARY_SIDE_INSET,
                x = softkeyBounds.centerX,
                anchorY = primaryCenterY,
                color = primaryTextColor,
                minScale = R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE,
            )?.let(labels::add)
        }

        if (showText) {
            C47TextRenderer.buildFittedLabelSpec(
                id = LABEL_ID_AUX,
                text = keyState.auxLabel,
                paint = softkeyAuxPaint,
                typeface = C47TypefacePolicy.standardFirst(
                    text = keyState.auxLabel,
                    fontSet = fontSet,
                ),
                baseSize = height * KeyVisualPolicy.SOFTKEY_AUX_TEXT_SIZE_RATIO,
                maxWidth = softkeyBounds.width - KeyVisualPolicy.SOFTKEY_AUX_SIDE_INSET,
                x = softkeyBounds.centerX,
                anchorY = softkeyBounds.bottom - KeyVisualPolicy.SOFTKEY_AUX_BOTTOM_INSET,
                color = metaTextColor,
                minScale = R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE,
                verticalAnchor = C47TextRenderer.TEXT_ANCHOR_BOTTOM,
            )?.let(labels::add)
        }

        if (keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_THROUGH)) {
            adornments += LineAdornmentSpec(
                id = ADORNMENT_ID_STRIKE_THROUGH,
                line = LineSpec(
                    start = PointSpec(
                        softkeyBounds.left + KeyVisualPolicy.SOFTKEY_STRIKE_SIDE_INSET,
                        softkeyBounds.centerY,
                    ),
                    end = PointSpec(
                        softkeyBounds.right - KeyVisualPolicy.SOFTKEY_STRIKE_SIDE_INSET,
                        softkeyBounds.centerY,
                    ),
                ),
                color = decorColor,
                strokeWidth = KeyVisualPolicy.SOFTKEY_DECOR_STROKE_WIDTH,
            )
        }
        if (keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_OUT)) {
            adornments += LineAdornmentSpec(
                id = ADORNMENT_ID_STRIKE_OUT,
                line = LineSpec(
                    start = PointSpec(
                        softkeyBounds.left + KeyVisualPolicy.SOFTKEY_STRIKE_OUT_SIDE_INSET,
                        softkeyBounds.top + KeyVisualPolicy.SOFTKEY_STRIKE_OUT_VERTICAL_INSET,
                    ),
                    end = PointSpec(
                        softkeyBounds.right - KeyVisualPolicy.SOFTKEY_STRIKE_OUT_SIDE_INSET,
                        softkeyBounds.bottom - KeyVisualPolicy.SOFTKEY_STRIKE_OUT_VERTICAL_INSET,
                    ),
                ),
                color = decorColor,
                strokeWidth = KeyVisualPolicy.SOFTKEY_DECOR_STROKE_WIDTH,
            )
        }

        return KeyRenderSpec(
            chrome = chrome,
            labels = labels,
            adornments = adornments,
            accessibility = buildAccessibilitySpec(keyState),
            geometry = SoftkeyGeometrySpec(
                bodyBounds = softkeyBounds,
                valueFieldBounds = valueFieldBounds,
                overlayCenter = overlayCenter,
                previewLine = previewLine,
            ),
        )
    }

    fun buildContentDescription(keyState: KeypadKeySnapshot): String {
        return buildAccessibilitySpec(keyState).contentDescription
    }

    private fun buildAccessibilitySpec(keyState: KeypadKeySnapshot): AccessibilitySpec {
        return AccessibilitySpec(buildString {
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
        })
    }

    private fun buildPressedAccentSpecs(
        bounds: RectSpec,
        width: Int,
        reverseVideo: Boolean,
    ): List<LineAdornmentSpec> {
        val edgeInset = width * 0.055f
        val topY = bounds.top + width * 0.045f
        val bottomY = bounds.bottom - width * 0.04f
        val highlightColor = if (reverseVideo) {
            C47TextRenderer.colorWithAlpha(softkeyLightTextColor, 84f / 255f)
        } else {
            C47TextRenderer.colorWithAlpha(softkeyValueLightColor, 112f / 255f)
        }
        val shadowColor = C47TextRenderer.colorWithAlpha(mainKeyFillColor, 132f / 255f)

        return listOf(
            LineAdornmentSpec(
                id = ADORNMENT_ID_PRESSED_HIGHLIGHT,
                line = LineSpec(
                    start = PointSpec(bounds.left + edgeInset, topY),
                    end = PointSpec(bounds.right - edgeInset, topY),
                ),
                color = highlightColor,
                strokeWidth = width * 0.012f,
            ),
            LineAdornmentSpec(
                id = ADORNMENT_ID_PRESSED_SHADOW,
                line = LineSpec(
                    start = PointSpec(bounds.left + edgeInset, bottomY),
                    end = PointSpec(bounds.right - edgeInset, bottomY),
                ),
                color = shadowColor,
                strokeWidth = width * 0.014f,
            ),
        )
    }

    private fun buildSoftkeyOverlaySpec(
        overlayState: Int,
        fontSet: KeypadFontSet,
        height: Int,
        center: PointSpec,
        color: Int,
    ): SoftkeyOverlayAdornmentSpec {
        val size = KeyVisualPolicy.SOFTKEY_OVERLAY_SIZE
        return when (overlayState) {
            KeypadSceneContract.OVERLAY_RB_FALSE -> SoftkeyOverlayAdornmentSpec(
                id = ADORNMENT_ID_OVERLAY,
                kind = SoftkeyOverlayKind.RADIO_FALSE,
                center = center,
                color = color,
            )

            KeypadSceneContract.OVERLAY_RB_TRUE -> SoftkeyOverlayAdornmentSpec(
                id = ADORNMENT_ID_OVERLAY,
                kind = SoftkeyOverlayKind.RADIO_TRUE,
                center = center,
                color = color,
            )

            KeypadSceneContract.OVERLAY_CB_FALSE -> SoftkeyOverlayAdornmentSpec(
                id = ADORNMENT_ID_OVERLAY,
                kind = SoftkeyOverlayKind.CHECKBOX_FALSE,
                center = center,
                color = color,
                frameBounds = overlayMarkBounds(center, size),
            )

            KeypadSceneContract.OVERLAY_CB_TRUE -> SoftkeyOverlayAdornmentSpec(
                id = ADORNMENT_ID_OVERLAY,
                kind = SoftkeyOverlayKind.CHECKBOX_TRUE,
                center = center,
                color = color,
                frameBounds = overlayMarkBounds(center, size),
            )

            KeypadSceneContract.OVERLAY_MB_FALSE,
            KeypadSceneContract.OVERLAY_MB_TRUE -> {
                val frameBounds = RectSpec(
                    left = center.x - KeyVisualPolicy.SOFTKEY_OVERLAY_MB_HALF_WIDTH,
                    top = center.y - KeyVisualPolicy.SOFTKEY_OVERLAY_MB_HALF_HEIGHT,
                    right = center.x + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_HALF_WIDTH,
                    bottom = center.y + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_HALF_HEIGHT,
                )
                val label = C47TextRenderer.buildFittedLabelSpec(
                    id = LABEL_ID_OVERLAY_MENU,
                    text = "M",
                    paint = softkeyAuxPaint,
                    typeface = C47TypefacePolicy.standardFirst(
                        text = "M",
                        fontSet = fontSet,
                    ),
                    baseSize = height * KeyVisualPolicy.SOFTKEY_OVERLAY_MB_TEXT_SIZE_RATIO,
                    maxWidth = KeyVisualPolicy.SOFTKEY_OVERLAY_MB_TEXT_MAX_WIDTH,
                    x = center.x,
                    anchorY = center.y - KeyVisualPolicy.SOFTKEY_OVERLAY_MB_TEXT_BASELINE_OFFSET,
                    color = color,
                    minScale = R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE,
                )
                val underline = if (overlayState == KeypadSceneContract.OVERLAY_MB_TRUE) {
                    LineAdornmentSpec(
                        id = ADORNMENT_ID_OVERLAY_UNDERLINE,
                        line = LineSpec(
                            start = PointSpec(
                                center.x + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_UNDERLINE_START_X,
                                center.y + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_UNDERLINE_Y,
                            ),
                            end = PointSpec(
                                center.x + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_UNDERLINE_END_X,
                                center.y + KeyVisualPolicy.SOFTKEY_OVERLAY_MB_UNDERLINE_Y,
                            ),
                        ),
                        color = color,
                        strokeWidth = KeyVisualPolicy.SOFTKEY_DECOR_STROKE_WIDTH,
                    )
                } else {
                    null
                }
                SoftkeyOverlayAdornmentSpec(
                    id = ADORNMENT_ID_OVERLAY,
                    kind = if (overlayState == KeypadSceneContract.OVERLAY_MB_TRUE) {
                        SoftkeyOverlayKind.MENU_BADGE_TRUE
                    } else {
                        SoftkeyOverlayKind.MENU_BADGE_FALSE
                    },
                    center = center,
                    color = color,
                    frameBounds = frameBounds,
                    label = label,
                    underline = underline,
                )
            }

            else -> SoftkeyOverlayAdornmentSpec(
                id = ADORNMENT_ID_OVERLAY,
                kind = SoftkeyOverlayKind.RADIO_FALSE,
                center = center,
                color = color,
            )
        }
    }

    private fun overlayMarkBounds(center: PointSpec, size: Float): RectSpec {
        val extent = size * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO
        return RectSpec(
            left = center.x - extent,
            top = center.y - extent,
            right = center.x + extent,
            bottom = center.y + extent,
        )
    }

    private fun drawRenderSpec(
        canvas: Canvas,
        renderSpec: KeyRenderSpec,
    ) {
        renderSpec.chrome?.let { chrome ->
            KeyRenderPainter.drawChrome(
                canvas = canvas,
                chrome = chrome,
                fillPaint = softkeyFillPaint,
                strokePaint = softkeyDecorPaint,
            )
        }

        renderSpec.adornment(ADORNMENT_ID_PREVIEW)?.let { preview ->
            if (preview is LineAdornmentSpec) {
                KeyRenderPainter.drawLine(canvas, preview, softkeyDecorPaint)
            }
        }

        renderSpec.label(LABEL_ID_VALUE)?.let { valueLabel ->
            KeyRenderPainter.drawLabel(canvas, valueLabel, softkeyValuePaint)
        }

        renderSpec.adornment(ADORNMENT_ID_OVERLAY)?.let { overlay ->
            if (overlay is SoftkeyOverlayAdornmentSpec) {
                drawSoftkeyOverlay(canvas, overlay)
            }
        }

        renderSpec.label(LABEL_ID_PRIMARY)?.let { primaryLabel ->
            KeyRenderPainter.drawLabel(canvas, primaryLabel, softkeyTextPaint)
        }

        renderSpec.label(LABEL_ID_AUX)?.let { auxLabel ->
            KeyRenderPainter.drawLabel(canvas, auxLabel, softkeyAuxPaint)
        }

        renderSpec.adornment(ADORNMENT_ID_STRIKE_THROUGH)?.let { strike ->
            if (strike is LineAdornmentSpec) {
                KeyRenderPainter.drawLine(canvas, strike, softkeyDecorPaint)
            }
        }
        renderSpec.adornment(ADORNMENT_ID_STRIKE_OUT)?.let { strike ->
            if (strike is LineAdornmentSpec) {
                KeyRenderPainter.drawLine(canvas, strike, softkeyDecorPaint)
            }
        }
    }

    private fun drawSoftkeyOverlay(
        canvas: Canvas,
        overlay: SoftkeyOverlayAdornmentSpec,
    ) {
        softkeyDecorPaint.color = overlay.color
        softkeyDotPaint.color = overlay.color

        when (overlay.kind) {
            SoftkeyOverlayKind.RADIO_FALSE -> {
                canvas.drawCircle(
                    overlay.center.x,
                    overlay.center.y,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_SIZE * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    softkeyDecorPaint,
                )
            }

            SoftkeyOverlayKind.RADIO_TRUE -> {
                canvas.drawCircle(
                    overlay.center.x,
                    overlay.center.y,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_SIZE * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    softkeyDecorPaint,
                )
                canvas.drawCircle(
                    overlay.center.x,
                    overlay.center.y,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_SIZE * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_DOT_RATIO,
                    softkeyDotPaint,
                )
            }

            SoftkeyOverlayKind.CHECKBOX_FALSE -> {
                val frame = requireNotNull(overlay.frameBounds)
                canvas.drawRect(frame.asRectF(), softkeyDecorPaint)
            }

            SoftkeyOverlayKind.CHECKBOX_TRUE -> {
                val frame = requireNotNull(overlay.frameBounds)
                canvas.drawRect(frame.asRectF(), softkeyDecorPaint)
                canvas.drawLine(
                    overlay.center.x - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_LEFT_X,
                    overlay.center.y,
                    overlay.center.x - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_MID_X,
                    overlay.center.y + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    softkeyDecorPaint,
                )
                canvas.drawLine(
                    overlay.center.x - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_MID_X,
                    overlay.center.y + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    overlay.center.x + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_RIGHT_X,
                    overlay.center.y - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    softkeyDecorPaint,
                )
            }

            SoftkeyOverlayKind.MENU_BADGE_FALSE,
            SoftkeyOverlayKind.MENU_BADGE_TRUE -> {
                val frame = requireNotNull(overlay.frameBounds)
                canvas.drawRoundRect(
                    frame.asRectF(),
                    KeyVisualPolicy.SOFTKEY_OVERLAY_MB_CORNER_RADIUS,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_MB_CORNER_RADIUS,
                    softkeyDecorPaint,
                )
                overlay.label?.let { label ->
                    KeyRenderPainter.drawLabel(canvas, label, softkeyAuxPaint)
                }
                overlay.underline?.let { underline ->
                    KeyRenderPainter.drawLine(canvas, underline, softkeyDecorPaint)
                }
            }
        }
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

    private companion object {
        private const val LABEL_ID_VALUE = "value"
        private const val LABEL_ID_PRIMARY = "primary"
        private const val LABEL_ID_AUX = "aux"
        private const val LABEL_ID_OVERLAY_MENU = "overlay-menu"
        private const val ADORNMENT_ID_PREVIEW = "preview-line"
        private const val ADORNMENT_ID_OVERLAY = "overlay"
        private const val ADORNMENT_ID_OVERLAY_UNDERLINE = "overlay-underline"
        private const val ADORNMENT_ID_STRIKE_THROUGH = "strike-through"
        private const val ADORNMENT_ID_STRIKE_OUT = "strike-out"
        private const val ADORNMENT_ID_PRESSED_HIGHLIGHT = "pressed-highlight"
        private const val ADORNMENT_ID_PRESSED_SHADOW = "pressed-shadow"
    }
}
