package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.max

internal data class KeypadFontSet(
    val standard: Typeface?,
    val numeric: Typeface?,
    val tiny: Typeface?,
)

private data class MainKeyStyleSpec(
    val fontSize: Float,
    val primaryTextColor: Int,
    val idleFillColor: Int,
    val pressedFillColor: Int,
)

internal object KeyVisualPolicy {
    // Softkey-only presentation tuning values.
    const val SOFTKEY_DECOR_STROKE_WIDTH = 2f
    const val SOFTKEY_OUTER_INSET = 2f
    const val SOFTKEY_PREVIEW_LINE_SIDE_INSET = 10f
    const val SOFTKEY_PREVIEW_LINE_BOTTOM_INSET = 4f
    const val SOFTKEY_VALUE_TEXT_SIZE_RATIO = 0.18f
    const val SOFTKEY_VALUE_WIDTH_RATIO = 0.34f
    const val SOFTKEY_VALUE_RIGHT_INSET = 7f
    const val SOFTKEY_VALUE_TOP_INSET = 6f
    const val SOFTKEY_OVERLAY_CENTER_RIGHT_INSET = 10f
    const val SOFTKEY_OVERLAY_CENTER_BOTTOM_INSET = 12f
    const val SOFTKEY_PRIMARY_TOP_RATIO = 0.28f
    const val SOFTKEY_PRIMARY_SIDE_INSET = 8f
    const val SOFTKEY_PRIMARY_RIGHT_RESERVE_WITH_OVERLAY = 16f
    const val SOFTKEY_AUX_TEXT_SIZE_RATIO = 0.16f
    const val SOFTKEY_AUX_SIDE_INSET = 12f
    const val SOFTKEY_AUX_BOTTOM_INSET = 11f
    const val SOFTKEY_STRIKE_SIDE_INSET = 8f
    const val SOFTKEY_STRIKE_OUT_SIDE_INSET = 7f
    const val SOFTKEY_STRIKE_OUT_VERTICAL_INSET = 10f
    const val SOFTKEY_OVERLAY_SIZE = 7f
    const val SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO = 0.7f
    const val SOFTKEY_OVERLAY_MARK_DOT_RATIO = 0.28f
    const val SOFTKEY_OVERLAY_CHECK_LEFT_X = 3f
    const val SOFTKEY_OVERLAY_CHECK_MID_X = 1f
    const val SOFTKEY_OVERLAY_CHECK_DELTA_Y = 3f
    const val SOFTKEY_OVERLAY_CHECK_RIGHT_X = 4f
    const val SOFTKEY_OVERLAY_MB_HALF_WIDTH = 6.5f
    const val SOFTKEY_OVERLAY_MB_HALF_HEIGHT = 5.2f
    const val SOFTKEY_OVERLAY_MB_CORNER_RADIUS = 3f
    const val SOFTKEY_OVERLAY_MB_TEXT_SIZE_RATIO = 0.12f
    const val SOFTKEY_OVERLAY_MB_TEXT_MAX_WIDTH = 9f
    const val SOFTKEY_OVERLAY_MB_TEXT_BASELINE_OFFSET = 0.5f
    const val SOFTKEY_OVERLAY_MB_UNDERLINE_START_X = 1f
    const val SOFTKEY_OVERLAY_MB_UNDERLINE_END_X = 6f
    const val SOFTKEY_OVERLAY_MB_UNDERLINE_Y = 4f
}

class CalculatorKeyView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val LABEL_ID_MAIN_PRIMARY = "main-primary"
        private const val LABEL_ID_MAIN_F = "main-f"
        private const val LABEL_ID_MAIN_G = "main-g"
        private const val LABEL_ID_MAIN_LETTER = "main-letter"
        private const val ADORNMENT_ID_MAIN_PRESSED_HIGHLIGHT = "main-pressed-highlight"
        private const val ADORNMENT_ID_MAIN_PRESSED_SHADOW = "main-pressed-shadow"

        private val defaultPrimaryColor = Color.WHITE
        private val defaultPrimaryDarkColor = Color.BLACK
        private val fAccentColor = Color.rgb(255, 195, 111)
        private val fPressedColor = Color.rgb(255, 216, 162)
        private val gAccentColor = Color.rgb(142, 218, 254)
        private val gPressedColor = Color.rgb(193, 235, 254)
        private val fgAccentColor = fAccentColor
        private val fgPressedColor = fPressedColor
        private val alphaAccentColor = Color.parseColor("#E36C50")
        private val letterColor = Color.parseColor("#A5A5A5")
        private val fourthLabelColor = Color.rgb(223, 223, 223)
        private val longPressColor = Color.parseColor("#D4D8DD")
        private val mainKeyFillColor = Color.rgb(64, 64, 64)
        private val mainKeyPressedColor = Color.parseColor("#744A2E")
        private val softkeyReverseColor = Color.rgb(96, 96, 96)
        private val softkeyReversePressedColor = mainKeyPressedColor
        private val softkeyLightTextColor = Color.parseColor("#F4F7F9")
        private val softkeyMetaLightColor = Color.parseColor("#C9D0D6")
        private val softkeyValueLightColor = Color.rgb(240, 191, 122)
        private val softkeyPreviewColor = Color.rgb(229, 171, 90)
    }

    private val buttonView = View(context)
    private val mainKeyLabelMirrors = MainKeyLabelMirrors(
        context = context,
        fourthLabelColor = fourthLabelColor,
        fAccentColor = fAccentColor,
        gAccentColor = gAccentColor,
    )
    val primaryLabel: TextView
        get() = mainKeyLabelMirrors.primaryLabel
    val fLabel: TextView
        get() = mainKeyLabelMirrors.fLabel
    val gLabel: TextView
        get() = mainKeyLabelMirrors.gLabel
    val alphaLabel = TextView(context)
    val letterLabel: TextView
        get() = mainKeyLabelMirrors.letterLabel

    var keyCode: Int = 0
    private var isFnKey: Boolean = false
    private var lastLayoutClass: Int? = null
    private var usesLetterSpacer = true
    private var keepLetterSpacerInvisible = false
    private var fontSet = KeypadFontSet(null, null, null)
    private var softkeyState = KeypadKeySnapshot.EMPTY
    private var mainKeyState = KeypadKeySnapshot.EMPTY
    private var mainKeyRenderSpec: KeyRenderSpec? = null
    private var currentShiftFOn = false
    private var currentShiftGOn = false
    private var topLabelPlacement = TopLabelLanePlacement.DEFAULT
    private var designCellWidth = R47ReferenceGeometry.STANDARD_PITCH
    private var designButtonWidth = R47ReferenceGeometry.STANDARD_KEY_WIDTH
    private var drawKeySurfaces = true
    private val mainKeyRect = RectF()
    private val mainKeyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val mainKeyLabelPaint = C47TextRenderer.newTextPaint()
    private val mainKeyMeasurementPaint = C47TextRenderer.newTextPaint()
    private val mainKeyPressedHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val softkeyPainter = CalculatorSoftkeyPainter(
        defaultPrimaryColor = defaultPrimaryColor,
        letterColor = letterColor,
        mainKeyFillColor = mainKeyFillColor,
        mainKeyPressedColor = mainKeyPressedColor,
        softkeyReverseColor = softkeyReverseColor,
        softkeyReversePressedColor = softkeyReversePressedColor,
        softkeyLightTextColor = softkeyLightTextColor,
        softkeyMetaLightColor = softkeyMetaLightColor,
        softkeyValueLightColor = softkeyValueLightColor,
        softkeyPreviewColor = softkeyPreviewColor,
    )
    init {
        // Critical: Allow drawing outside bounds
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)

        buttonView.id = View.generateViewId()
        val btnParams = LayoutParams(0, 0)
        btnParams.topToTop = LayoutParams.PARENT_ID
        btnParams.bottomToBottom = LayoutParams.PARENT_ID
        btnParams.startToStart = LayoutParams.PARENT_ID
        btnParams.endToEnd = LayoutParams.PARENT_ID
        btnParams.horizontalBias = 0f
        btnParams.verticalBias = 0f
        btnParams.matchConstraintPercentHeight = R47KeySurfacePolicy.MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW
        buttonView.setBackgroundColor(Color.TRANSPARENT)
        addView(buttonView, btnParams)

        primaryLabel.setTextColor(Color.WHITE)

        alphaLabel.id = View.generateViewId()
        alphaLabel.visibility = View.GONE

        isClickable = false
        buttonView.isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFontSize(currentShiftFOn, currentShiftGOn)
        updateFaceplateOffsets()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateFaceplateOffsets()
    }

    private fun updateFontSize(fOn: Boolean, gOn: Boolean) {
        currentShiftFOn = fOn
        currentShiftGOn = gOn

        if (isFnKey || width <= 0) {
            return
        }

        val referenceCellToViewWidthScale = if (designCellWidth > 0f) width.toFloat() / designCellWidth else 1f
        val primarySize = mainKeyStyleSpec(mainKeyState.styleRole).fontSize * referenceCellToViewWidthScale
        val topLabelTextSize = R47LabelLayoutPolicy.TOP_F_G_LABEL_TEXT_SIZE * referenceCellToViewWidthScale
        val primaryMaxWidth = primaryLabelMaxWidthPx(referenceCellToViewWidthScale)
        val resolvedPrimarySize = normalizedTextSizePx(primaryLabel, primarySize)
        val resolvedFLabelTextSize = normalizedTextSizePx(fLabel, topLabelTextSize * topLabelPlacement.fScale)
        val resolvedGLabelTextSize = normalizedTextSizePx(gLabel, topLabelTextSize * topLabelPlacement.gScale)
        val resolvedLetterLabelTextSize = normalizedTextSizePx(
            letterLabel,
            R47LabelLayoutPolicy.FOURTH_LABEL_TEXT_SIZE * referenceCellToViewWidthScale,
        )

        primaryLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            fittedTextSizePx(primaryLabel, resolvedPrimarySize, primaryMaxWidth),
        )
        primaryLabel.textScaleX = 1f
        fLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, resolvedFLabelTextSize)
        gLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, resolvedGLabelTextSize)
        letterLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, resolvedLetterLabelTextSize)
        refreshMainKeyRenderSpec()
    }

    private fun fittedTextSizePx(labelView: TextView, baseSize: Float, maxWidth: Float): Float {
        val text = labelView.text?.toString().orEmpty()
        if (text.isBlank() || maxWidth <= 0f) {
            return baseSize
        }

        configureMeasurementPaint(
            labelView = labelView,
            typeface = labelView.typeface,
            textSize = baseSize,
        )
        return C47TextRenderer.fittedTextSize(
            text,
            mainKeyMeasurementPaint,
            baseSize = baseSize,
            maxWidth = maxWidth,
            minScale = R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE,
        )
    }

    private fun normalizedTextSizePx(labelView: TextView, requestedSize: Float): Float {
        if (requestedSize <= 0f) {
            return requestedSize
        }

        val referenceTypeface = fontSet.standard ?: return requestedSize
        val targetTypeface = labelView.typeface ?: return requestedSize
        if (targetTypeface == referenceTypeface) {
            return requestedSize
        }

        val referenceHeight = fontMetricsHeight(labelView, referenceTypeface, requestedSize)
        val targetHeight = fontMetricsHeight(labelView, targetTypeface, requestedSize)
        if (referenceHeight <= 0f || targetHeight <= 0f) {
            return requestedSize
        }

        // Preserve the configured label-size envelope when a taller font is selected.
        return (requestedSize * (referenceHeight / targetHeight))
            .coerceAtMost(requestedSize)
    }

    private fun configureMeasurementPaint(
        labelView: TextView,
        typeface: Typeface?,
        textSize: Float,
    ) {
        C47TextRenderer.configureTextPaint(
            mainKeyMeasurementPaint,
            typeface = typeface,
            textSize = textSize,
            align = Paint.Align.LEFT,
            color = labelView.currentTextColor,
            textScaleX = labelView.textScaleX,
        )
    }

    private fun primaryLabelMaxWidthPx(referenceCellToViewWidthScale: Float): Float {
        val buttonWidth = when {
            buttonView.width > 0 -> buttonView.width.toFloat()
            designButtonWidth > 0f -> designButtonWidth * referenceCellToViewWidthScale
            else -> 0f
        }
        if (buttonWidth <= 0f) {
            return 0f
        }

        val referenceBodyToViewWidthScale =
            if (designButtonWidth > 0f) buttonWidth / designButtonWidth else 1f
        val bodyWidth = if (buttonView.width > 0) {
            updateMainKeySurfaceRect(mainKeyRect, referenceBodyToViewWidthScale)
            mainKeyRect.width()
        } else {
            buttonWidth - (2f * referenceBodyToViewWidthScale) +
                (R47KeySurfacePolicy.MAIN_KEY_BODY_OPTICAL_WIDTH_DELTA * referenceBodyToViewWidthScale)
        }
        val horizontalPadding =
            R47LabelLayoutPolicy.PRIMARY_LEGEND_HORIZONTAL_PADDING * referenceBodyToViewWidthScale
        return (bodyWidth - horizontalPadding).coerceAtLeast(0f)
    }

    private fun measureTextWidth(labelView: TextView, textSize: Float = labelView.textSize): Float {
        val text = labelView.text?.toString().orEmpty()
        if (text.isBlank()) {
            return 0f
        }
        configureMeasurementPaint(
            labelView = labelView,
            typeface = labelView.typeface,
            textSize = textSize,
        )
        return mainKeyMeasurementPaint.measureText(text)
    }

    private fun fontMetricsHeight(labelView: TextView, typeface: Typeface?, textSize: Float): Float {
        configureMeasurementPaint(
            labelView = labelView,
            typeface = typeface,
            textSize = textSize,
        )
        return C47TextRenderer.fontMetricsHeight(
            mainKeyMeasurementPaint,
            typeface = typeface,
            textSize = textSize,
        )
    }

    private fun textBottomOffset(
        labelView: TextView,
        textSize: Float = labelView.textSize,
        typeface: Typeface? = labelView.typeface,
    ): Float {
        configureMeasurementPaint(
            labelView = labelView,
            typeface = typeface,
            textSize = textSize,
        )
        return C47TextRenderer.textBottomOffset(
            mainKeyMeasurementPaint,
            typeface = typeface,
            textSize = textSize,
        )
    }

    private fun resetLabelLayout() {
        mainKeyLabelMirrors.resetGeometry()
    }

    private fun updateFaceplateOffsets() {
        refreshMainKeyRenderSpec()
    }

    private fun updateFourthLabelOffset(referenceCellToViewWidthScale: Float) {
        val letterLeft =
            mainKeyRect.right +
                R47LabelLayoutPolicy.FOURTH_LABEL_X_OFFSET_FROM_MAIN_KEY_BODY_RIGHT * referenceCellToViewWidthScale
        val letterTop =
            R47LabelLayoutPolicy.FOURTH_LABEL_Y_OFFSET_FROM_MAIN_KEY_BODY_TOP * referenceCellToViewWidthScale
        letterLabel.translationX = letterLeft - letterLabel.left.toFloat()
        letterLabel.translationY = letterTop - letterLabel.top.toFloat()
    }

    private fun updateMainKeySurfaceRect(targetRect: RectF, referenceBodyToViewWidthScale: Float) {
        val inset = referenceBodyToViewWidthScale
        val widthBonus = R47KeySurfacePolicy.MAIN_KEY_BODY_OPTICAL_WIDTH_DELTA * referenceBodyToViewWidthScale
        val halfWidthBonus = widthBonus * 0.5f
        targetRect.set(
            (buttonView.left.toFloat() + inset - halfWidthBonus).coerceAtLeast(inset),
            buttonView.top.toFloat() + inset,
            (buttonView.right.toFloat() - inset + halfWidthBonus).coerceAtMost(width.toFloat() - inset),
            buttonView.bottom.toFloat() - inset,
        )
    }

    internal fun currentMainKeyBodyWidthForTest(): Float {
        if (isFnKey) {
            return 0f
        }

        val buttonWidth = buttonView.width.toFloat()
        if (buttonWidth <= 0f || buttonView.height <= 0) {
            return 0f
        }

        val referenceBodyToViewWidthScale = if (designButtonWidth > 0f) {
            buttonWidth / designButtonWidth
        } else {
            1f
        }
        updateMainKeySurfaceRect(mainKeyRect, referenceBodyToViewWidthScale)
        return mainKeyRect.width()
    }

    private fun updateLayoutPositioning(layoutClass: Int) {
        if (lastLayoutClass == layoutClass) return
        lastLayoutClass = layoutClass
    }

    internal fun setKey(slot: KeypadSlotSpec, fonts: KeypadFontSet) {
        this.keyCode = slot.code
        this.isFnKey = slot.isFunctionKey
        this.fontSet = fonts
        mainKeyLabelMirrors.applyFonts(fonts)

        if (slot.isFunctionKey) {
            softkeyState = KeypadKeySnapshot.EMPTY
            mainKeyState = KeypadKeySnapshot.EMPTY
            fLabel.visibility = View.GONE
            gLabel.visibility = View.GONE
            letterLabel.visibility = View.GONE
            primaryLabel.visibility = View.GONE
            buttonView.visibility = View.INVISIBLE
            mainKeyRenderSpec = null
            invalidate()
        } else {
            buttonView.visibility = View.VISIBLE
            fLabel.visibility = View.VISIBLE
            gLabel.visibility = View.VISIBLE
            letterLabel.visibility = View.VISIBLE
            primaryLabel.visibility = View.VISIBLE
            lastLayoutClass = null
            resetLabelLayout()
            configureMainKeySurface(slot.family)
            usesLetterSpacer = slot.usesLetterSpacer
            keepLetterSpacerInvisible = slot.keepLetterSpacerInvisible

            if (!slot.usesLetterSpacer) {
                letterLabel.visibility = View.GONE
            } else {
                letterLabel.visibility = if (keepLetterSpacerInvisible) View.INVISIBLE else View.VISIBLE
            }

            primaryLabel.setTextColor(defaultPrimaryColor)
            mainKeyState = KeypadKeySnapshot.EMPTY
            mainKeyRenderSpec = null
        }
    }

    internal fun setDrawKeySurfaces(draw: Boolean) {
        if (drawKeySurfaces == draw) {
            return
        }
        drawKeySurfaces = draw
        if (!isFnKey) {
            refreshMainKeyRenderSpec()
        }
        invalidate()
    }

    private fun mainKeyStyleSpec(styleRole: Int): MainKeyStyleSpec {
        return when (styleRole) {
            KeypadSceneContract.STYLE_SHIFT_F -> MainKeyStyleSpec(
                fontSize = R47LabelLayoutPolicy.SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE,
                primaryTextColor = defaultPrimaryDarkColor,
                idleFillColor = fAccentColor,
                pressedFillColor = fPressedColor,
            )

            KeypadSceneContract.STYLE_SHIFT_G -> MainKeyStyleSpec(
                fontSize = R47LabelLayoutPolicy.SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE,
                primaryTextColor = defaultPrimaryDarkColor,
                idleFillColor = gAccentColor,
                pressedFillColor = gPressedColor,
            )

            KeypadSceneContract.STYLE_SHIFT_FG -> MainKeyStyleSpec(
                fontSize = R47LabelLayoutPolicy.SHIFT_STYLE_PRIMARY_LEGEND_TEXT_SIZE,
                primaryTextColor = defaultPrimaryDarkColor,
                idleFillColor = fgAccentColor,
                pressedFillColor = fgPressedColor,
            )

            KeypadSceneContract.STYLE_ALPHA -> MainKeyStyleSpec(
                fontSize = R47LabelLayoutPolicy.DEFAULT_PRIMARY_LEGEND_TEXT_SIZE,
                primaryTextColor = defaultPrimaryDarkColor,
                idleFillColor = alphaAccentColor,
                pressedFillColor = alphaAccentColor,
            )

            KeypadSceneContract.STYLE_NUMERIC -> MainKeyStyleSpec(
                fontSize = R47LabelLayoutPolicy.NUMERIC_PRIMARY_LEGEND_TEXT_SIZE,
                primaryTextColor = defaultPrimaryColor,
                idleFillColor = mainKeyFillColor,
                pressedFillColor = mainKeyPressedColor,
            )

            else -> MainKeyStyleSpec(
                fontSize = R47LabelLayoutPolicy.DEFAULT_PRIMARY_LEGEND_TEXT_SIZE,
                primaryTextColor = defaultPrimaryColor,
                idleFillColor = mainKeyFillColor,
                pressedFillColor = mainKeyPressedColor,
            )
        }
    }

    private fun configureMainKeySurface(family: KeypadKeyFamily) {
        val letterRatio: Float
        when (family) {
            KeypadKeyFamily.STANDARD -> {
                designCellWidth = R47ReferenceGeometry.STANDARD_PITCH
                designButtonWidth = R47ReferenceGeometry.STANDARD_KEY_WIDTH
                letterRatio = R47KeySurfacePolicy.STANDARD_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION
            }

            KeypadKeyFamily.ENTER -> {
                designCellWidth = R47ReferenceGeometry.ENTER_WIDTH
                designButtonWidth = R47ReferenceGeometry.ENTER_WIDTH
                letterRatio = 0f
            }

            KeypadKeyFamily.NUMERIC_MATRIX -> {
                designCellWidth = R47ReferenceGeometry.MATRIX_PITCH
                designButtonWidth = R47ReferenceGeometry.MATRIX_KEY_WIDTH
                letterRatio = R47KeySurfacePolicy.MATRIX_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION
            }

            KeypadKeyFamily.BASE_OPERATOR -> {
                designCellWidth = R47ReferenceGeometry.STANDARD_KEY_WIDTH
                designButtonWidth = R47ReferenceGeometry.STANDARD_KEY_WIDTH
                letterRatio = 0f
            }

            KeypadKeyFamily.SOFTKEY -> error("Softkeys use the dedicated function-key drawing path")
        }

        val buttonParams = buttonView.layoutParams as LayoutParams
        buttonParams.matchConstraintPercentWidth = (1f - letterRatio).coerceIn(0f, 1f)
        buttonParams.horizontalBias = 0f
        buttonParams.endToEnd = LayoutParams.PARENT_ID

        buttonView.layoutParams = buttonParams
    }

    private fun primaryTypefaceFor(): Typeface? {
        return C47TypefacePolicy.standardFirst(
            text = mainKeyState.primaryLabel,
            fontSet = fontSet,
        )
    }

    private fun applyLabelRole(labelView: TextView, role: Int, defaultColor: Int) {
        var paintFlags = labelView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        if (
            role == KeypadSceneContract.TEXT_ROLE_F_UNDERLINE ||
                role == KeypadSceneContract.TEXT_ROLE_G_UNDERLINE
        ) {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
        }
        labelView.paintFlags = paintFlags
        labelView.typeface = when (labelView) {
            primaryLabel -> primaryTypefaceFor()
            fLabel, gLabel, letterLabel -> C47TypefacePolicy.standardFirst(
                text = labelView.text,
                fontSet = fontSet,
            )
            else -> fontSet.standard
        }
        labelView.setTextColor(
            when (role) {
                KeypadSceneContract.TEXT_ROLE_LONGPRESS -> longPressColor
                else -> defaultColor
            }
        )
    }

    private fun applySceneStyling(keyState: KeypadKeySnapshot) {
        primaryLabel.setTextColor(mainKeyStyleSpec(keyState.styleRole).primaryTextColor)
        primaryLabel.typeface = primaryTypefaceFor()
        applyLabelRole(
            fLabel,
            keyState.labelRole(KeypadSceneContract.LABEL_F),
            if (keyState.layoutClass == KeypadSceneContract.LAYOUT_CLASS_STATIC_SINGLE) letterColor else fAccentColor,
        )
        applyLabelRole(
            gLabel,
            keyState.labelRole(KeypadSceneContract.LABEL_G),
            gAccentColor,
        )
        applyLabelRole(
            letterLabel,
            keyState.labelRole(KeypadSceneContract.LABEL_LETTER),
            fourthLabelColor,
        )
    }

    private fun applyLabelVisibility(keyState: KeypadKeySnapshot) {
        primaryLabel.visibility = View.VISIBLE
        val hasFLabel = keyState.fLabel.isNotBlank()
        fLabel.visibility = if (hasFLabel) View.VISIBLE else View.INVISIBLE
        gLabel.visibility = if (hasFLabel && keyState.gLabel.isNotBlank()) View.VISIBLE else View.INVISIBLE
        if (!usesLetterSpacer) {
            letterLabel.visibility = View.GONE
        } else if (
            keyState.layoutClass == KeypadSceneContract.LAYOUT_CLASS_ALPHA ||
                keepLetterSpacerInvisible ||
                keyState.letterLabel.isBlank()
        ) {
            letterLabel.visibility = View.INVISIBLE
        } else {
            letterLabel.visibility = View.VISIBLE
        }

        if (keyState.layoutClass == KeypadSceneContract.LAYOUT_CLASS_STATIC_SINGLE) {
            gLabel.visibility = View.GONE
        }
    }

    private fun applyEnabledState(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (isFnKey || enabled) 1f else 0.45f
        if (!isFnKey) {
            buttonView.alpha = if (enabled) 1f else 0.45f
            mainKeyLabelMirrors.applyEnabledAlpha(enabled)
        }
    }

    internal fun updateLabels(snapshot: KeypadSnapshot) {
        val keyState = snapshot.keyStateFor(keyCode)
        val resolvedShiftFOn = snapshot.shiftF
        val resolvedShiftGOn = snapshot.shiftG || snapshot.alphaOn

        applyEnabledState(keyState.isEnabled)

        if (isFnKey) {
            softkeyState = keyState
            mainKeyState = KeypadKeySnapshot.EMPTY
            contentDescription = softkeyPainter.buildContentDescription(keyState)
            invalidate()
        } else {
            mainKeyState = keyState
            primaryLabel.text = keyState.primaryLabel
            fLabel.text = keyState.fLabel
            gLabel.text = keyState.gLabel
            letterLabel.text = keyState.letterLabel
            currentShiftFOn = resolvedShiftFOn
            currentShiftGOn = resolvedShiftGOn
            updateLayoutPositioning(keyState.layoutClass)
            applySceneStyling(keyState)
            applyLabelVisibility(keyState)
            updateFontSize(currentShiftFOn, currentShiftGOn)
            refreshMainKeyRenderSpec()
            requestLayout()
            invalidate()
            contentDescription = mainKeyRenderSpec?.accessibility?.contentDescription ?: buildString {
                append(keyState.primaryLabel)
                if (keyState.fLabel.isNotBlank()) {
                    append(", f ")
                    append(keyState.fLabel)
                }
                if (keyState.fLabel.isNotBlank() && keyState.gLabel.isNotBlank()) {
                    append(", g ")
                    append(keyState.gLabel)
                }
            }
        }
    }

    internal fun buildTopLabelLaneInput(
        minLeftEdge: Float = Float.NEGATIVE_INFINITY,
        maxRightEdge: Float = Float.POSITIVE_INFINITY,
    ): TopLabelLaneGroupInput? {
        if (isFnKey || width <= 0) {
            return null
        }

        val hasFLabel = fLabel.visibility == View.VISIBLE && fLabel.text.isNotBlank()
        val hasGLabel = hasFLabel && gLabel.visibility == View.VISIBLE && gLabel.text.isNotBlank()
        if (!hasFLabel) {
            return null
        }

        val buttonWidth = buttonView.width.toFloat()
        if (buttonWidth <= 0f || buttonView.height <= 0) {
            return null
        }

        val referenceCellToViewWidthScale = if (designCellWidth > 0f) {
            width.toFloat() / designCellWidth
        } else {
            1f
        }
        val referenceBodyToViewWidthScale = if (designButtonWidth > 0f) {
            buttonWidth / designButtonWidth
        } else {
            1f
        }
        updateMainKeySurfaceRect(mainKeyRect, referenceBodyToViewWidthScale)

        val baseTopLabelTextSize = R47LabelLayoutPolicy.TOP_F_G_LABEL_TEXT_SIZE * referenceCellToViewWidthScale
        val fTextWidth = measureTextWidth(
            fLabel,
            normalizedTextSizePx(fLabel, baseTopLabelTextSize),
        )
        val gTextWidth = if (hasGLabel) {
            measureTextWidth(
                gLabel,
                normalizedTextSizePx(gLabel, baseTopLabelTextSize),
            )
        } else {
            0f
        }
        val gapWidth = if (hasGLabel) {
            R47LabelLayoutPolicy.TOP_F_G_LABEL_HORIZONTAL_GAP * referenceCellToViewWidthScale
        } else {
            0f
        }
        return TopLabelLaneGroupInput(
            code = keyCode,
            centerX = left + mainKeyRect.centerX(),
            bodyWidth = mainKeyRect.width(),
            fTextWidth = fTextWidth,
            gTextWidth = gTextWidth,
            gapWidth = gapWidth,
            maxShift = mainKeyRect.width() * R47TopLabelSolverPolicy.TOP_F_G_LABEL_MAX_SHIFT_FRACTION,
            minLeftEdge = minLeftEdge,
            maxRightEdge = maxRightEdge,
        )
    }

    internal fun currentMainKeyBodyHorizontalBounds(): Pair<Float, Float>? {
        if (isFnKey) {
            return null
        }

        val buttonWidth = buttonView.width.toFloat()
        if (buttonWidth <= 0f || buttonView.height <= 0) {
            return null
        }

        val referenceBodyToViewWidthScale = if (designButtonWidth > 0f) {
            buttonWidth / designButtonWidth
        } else {
            1f
        }
        updateMainKeySurfaceRect(mainKeyRect, referenceBodyToViewWidthScale)
        return (left + mainKeyRect.left) to (left + mainKeyRect.right)
    }

    internal fun hasMeasuredTopLabelAnchors(): Boolean {
        return width > 0 && buttonView.width > 0 && buttonView.height > 0
    }

    internal fun applyTopLabelPlacement(placement: TopLabelLanePlacement?) {
        val resolvedPlacement = placement ?: TopLabelLanePlacement.DEFAULT
        if (topLabelPlacement == resolvedPlacement) {
            return
        }
        val scaleChanged =
            topLabelPlacement.fScale != resolvedPlacement.fScale ||
                topLabelPlacement.gScale != resolvedPlacement.gScale
        topLabelPlacement = resolvedPlacement
        if (scaleChanged) {
            updateFontSize(currentShiftFOn, currentShiftGOn)
            requestLayout()
        } else {
            updateFaceplateOffsets()
        }
        invalidate()
    }

    internal fun currentTopLabelPlacementForTest(): TopLabelLanePlacement {
        return topLabelPlacement
    }

    internal fun currentMainKeyRenderSpecForTest(): KeyRenderSpec? {
        refreshMainKeyRenderSpec()
        return mainKeyRenderSpec
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (!isFnKey) {
            refreshMainKeyRenderSpec()
        }
        invalidate()
    }

    private fun refreshMainKeyRenderSpec() {
        if (isFnKey) {
            mainKeyRenderSpec = null
            return
        }
        mainKeyRenderSpec = buildMainKeyRenderSpec()
        syncLabelMirrorGeometry(mainKeyRenderSpec)
        mainKeyRenderSpec?.let { renderSpec ->
            contentDescription = renderSpec.accessibility.contentDescription
        }
    }

    private fun buildMainKeyRenderSpec(): KeyRenderSpec? {
        if (width <= 0) {
            return null
        }

        val buttonWidth = buttonView.width.toFloat()
        if (buttonWidth <= 0f || buttonView.height <= 0) {
            return null
        }

        val referenceCellToViewWidthScale = if (designCellWidth > 0f) {
            width.toFloat() / designCellWidth
        } else {
            1f
        }
        val referenceBodyToViewWidthScale = if (designButtonWidth > 0f) {
            buttonWidth / designButtonWidth
        } else {
            1f
        }
        val cornerRadius = R47KeySurfacePolicy.MAIN_KEY_DRAW_CORNER_RADIUS * referenceBodyToViewWidthScale
        updateMainKeySurfaceRect(mainKeyRect, referenceBodyToViewWidthScale)
        val bodyBounds = RectSpec(
            left = mainKeyRect.left,
            top = mainKeyRect.top,
            right = mainKeyRect.right,
            bottom = mainKeyRect.bottom,
        )
        val styleSpec = mainKeyStyleSpec(mainKeyState.styleRole)
        val chrome = KeyChromeSpec(
            bounds = bodyBounds,
            fillColor = if (isPressed) styleSpec.pressedFillColor else styleSpec.idleFillColor,
            cornerRadius = cornerRadius,
            drawSurface = drawKeySurfaces,
            pressedAccents = if (drawKeySurfaces && isPressed) {
                buildMainPressedAccentSpecs(bodyBounds, referenceBodyToViewWidthScale)
            } else {
                emptyList()
            },
        )

        val labels = mutableListOf<LabelSpec>()
        val primarySpec = C47TextRenderer.buildLabelSpec(
            id = LABEL_ID_MAIN_PRIMARY,
            text = primaryLabel.text?.toString().orEmpty(),
            paint = mainKeyMeasurementPaint,
            typeface = primaryLabel.typeface,
            textSize = primaryLabel.textSize,
            x = bodyBounds.centerX,
            anchorY = bodyBounds.centerY,
            color = primaryLabel.currentTextColor,
            alpha = primaryLabel.alpha,
            textScaleX = primaryLabel.textScaleX,
            underline = primaryLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0,
            strikeThrough = primaryLabel.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0,
            visible = primaryLabel.visibility == View.VISIBLE,
        )
        primarySpec?.let(labels::add)

        val topFgLabelHorizontalGap = R47LabelLayoutPolicy.TOP_F_G_LABEL_HORIZONTAL_GAP * referenceCellToViewWidthScale
        val hasFLabel = fLabel.visibility == View.VISIBLE && fLabel.text.isNotBlank()
        val hasGLabel = hasFLabel && gLabel.visibility == View.VISIBLE && gLabel.text.isNotBlank()
        val fWidth = if (hasFLabel) measureTextWidth(fLabel) else 0f
        val gWidth = if (hasGLabel) measureTextWidth(gLabel) else 0f
        val groupWidth = when {
            hasGLabel -> fWidth + topFgLabelHorizontalGap + gWidth
            hasFLabel -> fWidth
            else -> 0f
        }
        val groupLeft = bodyBounds.centerX - groupWidth / 2f + topLabelPlacement.centerShift
        val topFgLabelVerticalLift = R47LabelLayoutPolicy.TOP_F_G_LABEL_VERTICAL_LIFT * referenceCellToViewWidthScale
        val topLabelTranslationY = -topFgLabelVerticalLift
        val baseTopLabelTextSize = R47LabelLayoutPolicy.TOP_F_G_LABEL_TEXT_SIZE * referenceCellToViewWidthScale
        val targetTextBottomY = topLabelTranslationY + textBottomOffset(
            fLabel,
            baseTopLabelTextSize,
            fontSet.standard ?: fLabel.typeface,
        )

        val fSpec = if (hasFLabel) {
            C47TextRenderer.buildLabelSpec(
                id = LABEL_ID_MAIN_F,
                text = fLabel.text?.toString().orEmpty(),
                paint = mainKeyMeasurementPaint,
                typeface = fLabel.typeface,
                textSize = fLabel.textSize,
                x = groupLeft,
                anchorY = targetTextBottomY,
                color = fLabel.currentTextColor,
                align = Paint.Align.LEFT,
                verticalAnchor = C47TextRenderer.TEXT_ANCHOR_BOTTOM,
                alpha = fLabel.alpha,
                textScaleX = fLabel.textScaleX,
                underline = fLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0,
                strikeThrough = fLabel.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0,
                visible = true,
            )
        } else {
            null
        }
        val gSpec = if (hasGLabel) {
            C47TextRenderer.buildLabelSpec(
                id = LABEL_ID_MAIN_G,
                text = gLabel.text?.toString().orEmpty(),
                paint = mainKeyMeasurementPaint,
                typeface = gLabel.typeface,
                textSize = gLabel.textSize,
                x = groupLeft + fWidth + topFgLabelHorizontalGap + gWidth,
                anchorY = targetTextBottomY,
                color = gLabel.currentTextColor,
                align = Paint.Align.RIGHT,
                verticalAnchor = C47TextRenderer.TEXT_ANCHOR_BOTTOM,
                alpha = gLabel.alpha,
                textScaleX = gLabel.textScaleX,
                underline = gLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0,
                strikeThrough = gLabel.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0,
                visible = true,
            )
        } else {
            null
        }
        fSpec?.let(labels::add)
        gSpec?.let(labels::add)

        val topLabelGroup = when {
            fSpec?.bounds != null && gSpec?.bounds != null -> {
                val fBounds = requireNotNull(fSpec.bounds)
                val gBounds = requireNotNull(gSpec.bounds)
                TopLabelGroupSpec(
                    bounds = RectSpec(
                        left = fBounds.left,
                        top = minOf(fBounds.top, gBounds.top),
                        right = gBounds.right,
                        bottom = maxOf(fBounds.bottom, gBounds.bottom),
                    ),
                    groupCenterX = bodyBounds.centerX + topLabelPlacement.centerShift,
                    groupLeft = groupLeft,
                    corridorLeft = bodyBounds.left,
                    corridorRight = bodyBounds.right,
                    gapWidth = topFgLabelHorizontalGap,
                    fLeft = groupLeft,
                    gRight = gBounds.right,
                    baselineY = targetTextBottomY,
                    fScale = topLabelPlacement.fScale,
                    gScale = topLabelPlacement.gScale,
                )
            }

            fSpec?.bounds != null -> {
                val fBounds = requireNotNull(fSpec.bounds)
                TopLabelGroupSpec(
                    bounds = fBounds,
                    groupCenterX = bodyBounds.centerX + topLabelPlacement.centerShift,
                    groupLeft = groupLeft,
                    corridorLeft = bodyBounds.left,
                    corridorRight = bodyBounds.right,
                    gapWidth = 0f,
                    fLeft = groupLeft,
                    gRight = null,
                    baselineY = targetTextBottomY,
                    fScale = topLabelPlacement.fScale,
                    gScale = topLabelPlacement.gScale,
                )
            }

            else -> null
        }

        val fourthLabelAnchor = if (letterLabel.visibility == View.VISIBLE && letterLabel.text.isNotBlank()) {
            PointSpec(
                x = bodyBounds.right +
                    R47LabelLayoutPolicy.FOURTH_LABEL_X_OFFSET_FROM_MAIN_KEY_BODY_RIGHT * referenceCellToViewWidthScale,
                y = R47LabelLayoutPolicy.FOURTH_LABEL_Y_OFFSET_FROM_MAIN_KEY_BODY_TOP * referenceCellToViewWidthScale,
            )
        } else {
            null
        }
        val letterSpec = fourthLabelAnchor?.let { anchor ->
            C47TextRenderer.buildLabelSpec(
                id = LABEL_ID_MAIN_LETTER,
                text = letterLabel.text?.toString().orEmpty(),
                paint = mainKeyMeasurementPaint,
                typeface = letterLabel.typeface,
                textSize = letterLabel.textSize,
                x = anchor.x,
                anchorY = anchor.y,
                color = letterLabel.currentTextColor,
                align = Paint.Align.LEFT,
                verticalAnchor = C47TextRenderer.TEXT_ANCHOR_TOP,
                alpha = letterLabel.alpha,
                textScaleX = letterLabel.textScaleX,
                underline = letterLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0,
                strikeThrough = letterLabel.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG != 0,
                visible = true,
            )
        }
        letterSpec?.let(labels::add)

        return KeyRenderSpec(
            chrome = chrome,
            labels = labels,
            adornments = emptyList(),
            accessibility = buildMainKeyAccessibilitySpec(primarySpec, fSpec, gSpec),
            geometry = MainKeyGeometrySpec(
                bodyBounds = bodyBounds,
                primaryAnchor = PointSpec(bodyBounds.centerX, bodyBounds.centerY),
                topLabelGroup = topLabelGroup,
                fourthLabelAnchor = fourthLabelAnchor,
            ),
        )
    }

    private fun buildMainKeyAccessibilitySpec(
        primarySpec: LabelSpec?,
        fSpec: LabelSpec?,
        gSpec: LabelSpec?,
    ): AccessibilitySpec {
        return AccessibilitySpec(
            buildString {
                append(primarySpec?.text.orEmpty())
                if (fSpec != null) {
                    append(", f ")
                    append(fSpec.text)
                }
                if (gSpec != null) {
                    append(", g ")
                    append(gSpec.text)
                }
            }
        )
    }

    private fun syncLabelMirrorGeometry(renderSpec: KeyRenderSpec?) {
        mainKeyLabelMirrors.syncGeometry(
            primarySpec = renderSpec?.label(LABEL_ID_MAIN_PRIMARY),
            fSpec = renderSpec?.label(LABEL_ID_MAIN_F),
            gSpec = renderSpec?.label(LABEL_ID_MAIN_G),
            letterSpec = renderSpec?.label(LABEL_ID_MAIN_LETTER),
        )
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (!isFnKey && mainKeyLabelMirrors.isMirrorView(child)) {
            return false
        }
        return super.drawChild(canvas, child, drawingTime)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isFnKey) {
            softkeyPainter.draw(
                canvas = canvas,
                keyState = softkeyState,
                fontSet = fontSet,
                width = width,
                height = height,
                isPressed = isPressed,
                drawKeySurfaces = drawKeySurfaces,
            )
        } else {
            drawMainKey(canvas)
        }
    }

    private fun drawMainKey(canvas: Canvas) {
        val renderSpec = mainKeyRenderSpec ?: run {
            refreshMainKeyRenderSpec()
            mainKeyRenderSpec
        } ?: return

        renderSpec.chrome?.let { chrome ->
            KeyRenderPainter.drawChrome(
                canvas = canvas,
                chrome = chrome,
                fillPaint = mainKeyFillPaint,
                strokePaint = mainKeyPressedHighlightPaint,
            )
        }
        drawMainKeyLabels(canvas)
    }

    private fun drawMainKeyLabels(canvas: Canvas) {
        val renderSpec = mainKeyRenderSpec ?: run {
            refreshMainKeyRenderSpec()
            mainKeyRenderSpec
        } ?: return
        drawMainKeyLabels(canvas, renderSpec)
    }

    private fun drawMainKeyLabels(canvas: Canvas, renderSpec: KeyRenderSpec) {
        renderSpec.label(LABEL_ID_MAIN_PRIMARY)?.let { label ->
            KeyRenderPainter.drawLabel(canvas, label, mainKeyLabelPaint)
        }
        renderSpec.label(LABEL_ID_MAIN_F)?.let { label ->
            KeyRenderPainter.drawLabel(canvas, label, mainKeyLabelPaint)
        }
        renderSpec.label(LABEL_ID_MAIN_G)?.let { label ->
            KeyRenderPainter.drawLabel(canvas, label, mainKeyLabelPaint)
        }
        renderSpec.label(LABEL_ID_MAIN_LETTER)?.let { label ->
            KeyRenderPainter.drawLabel(canvas, label, mainKeyLabelPaint)
        }
    }

    private fun buildMainPressedAccentSpecs(
        rect: RectSpec,
        referenceBodyToViewWidthScale: Float,
    ): List<LineAdornmentSpec> {
        val edgeInset = max(4f * referenceBodyToViewWidthScale, 2f)
        val topY = rect.top + max(6f * referenceBodyToViewWidthScale, 2f)
        val bottomY = rect.bottom - max(6f * referenceBodyToViewWidthScale, 2f)

        return listOf(
            LineAdornmentSpec(
                id = ADORNMENT_ID_MAIN_PRESSED_HIGHLIGHT,
                line = LineSpec(
                    start = PointSpec(rect.left + edgeInset, topY),
                    end = PointSpec(rect.right - edgeInset, topY),
                ),
                color = Color.argb(112, 255, 244, 224),
                strokeWidth = max(2.2f * referenceBodyToViewWidthScale, 1.5f),
            ),
            LineAdornmentSpec(
                id = ADORNMENT_ID_MAIN_PRESSED_SHADOW,
                line = LineSpec(
                    start = PointSpec(rect.left + edgeInset, bottomY),
                    end = PointSpec(rect.right - edgeInset, bottomY),
                ),
                color = Color.argb(128, 18, 12, 8),
                strokeWidth = max(2.8f * referenceBodyToViewWidthScale, 1.5f),
            ),
        )
    }
}
