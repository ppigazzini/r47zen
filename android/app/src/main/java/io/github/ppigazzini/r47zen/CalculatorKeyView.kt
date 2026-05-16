package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
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
    val primaryLabel = TextView(context)
    val fLabel = TextView(context)
    val gLabel = TextView(context)
    val alphaLabel = TextView(context)
    val letterLabel = TextView(context)

    var keyCode: Int = 0
    private var isFnKey: Boolean = false
    private var lastLayoutClass: Int? = null
    private var usesLetterSpacer = true
    private var keepLetterSpacerInvisible = false
    private var fontSet = KeypadFontSet(null, null, null)
    private var softkeyState = KeypadKeySnapshot.EMPTY
    private var mainKeyState = KeypadKeySnapshot.EMPTY
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
    private val mainKeyPressedHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val mainKeyPressedShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

        // Letter label (Right side of the view, bottom aligned with button)
        letterLabel.id = View.generateViewId()
        letterLabel.setTextColor(fourthLabelColor)
        letterLabel.gravity = Gravity.START or Gravity.TOP
        letterLabel.includeFontPadding = false
        letterLabel.maxLines = 1
        enableKeyTextRendering(letterLabel)
        val letterParams = LayoutParams(0, 0)
        letterParams.topToTop = buttonView.id
        letterParams.endToEnd = LayoutParams.PARENT_ID
        letterParams.bottomToBottom = LayoutParams.PARENT_ID
        letterParams.matchConstraintPercentWidth = R47KeySurfacePolicy.STANDARD_KEY_FOURTH_LABEL_STRIP_WIDTH_FRACTION
        letterParams.matchConstraintPercentHeight = R47KeySurfacePolicy.MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW
        addView(letterLabel, letterParams)

        // Button background view (Left side)
        buttonView.id = View.generateViewId()
        val btnParams = LayoutParams(0, 0)
        btnParams.topToTop = LayoutParams.PARENT_ID
        btnParams.bottomToBottom = LayoutParams.PARENT_ID
        btnParams.startToStart = LayoutParams.PARENT_ID
        btnParams.endToStart = letterLabel.id
        btnParams.verticalBias = 0f
        btnParams.matchConstraintPercentHeight = R47KeySurfacePolicy.MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW
        buttonView.setBackgroundColor(Color.TRANSPARENT)
        addView(buttonView, btnParams)

        // Primary label (Center of button)
        primaryLabel.id = View.generateViewId()
        primaryLabel.setTextColor(Color.WHITE)
        primaryLabel.gravity = Gravity.CENTER
        primaryLabel.includeFontPadding = false
        primaryLabel.maxLines = 1
        enableKeyTextRendering(primaryLabel)
        val primaryParams = LayoutParams(0, 0)
        primaryParams.topToTop = buttonView.id
        primaryParams.bottomToBottom = buttonView.id
        primaryParams.startToStart = buttonView.id
        primaryParams.endToEnd = buttonView.id
        addView(primaryLabel, primaryParams)

        // F label (Above button)
        fLabel.id = View.generateViewId()
        fLabel.setTextColor(fAccentColor)
        fLabel.gravity = Gravity.START or Gravity.TOP
        fLabel.includeFontPadding = false
        fLabel.maxLines = 1
        enableKeyTextRendering(fLabel)
        val fParams = LayoutParams(LayoutParams.WRAP_CONTENT, 0)
        fParams.topToTop = buttonView.id
        fParams.bottomToBottom = buttonView.id
        fParams.startToStart = LayoutParams.PARENT_ID
        fParams.endToStart = gLabel.id
        fParams.horizontalChainStyle = LayoutParams.CHAIN_PACKED
        addView(fLabel, fParams)

        // G label (Above button)
        gLabel.id = View.generateViewId()
        gLabel.setTextColor(gAccentColor)
        gLabel.gravity = Gravity.END or Gravity.TOP
        gLabel.includeFontPadding = false
        gLabel.maxLines = 1
        enableKeyTextRendering(gLabel)
        val gParams = LayoutParams(LayoutParams.WRAP_CONTENT, 0)
        gParams.topToTop = buttonView.id
        gParams.bottomToBottom = buttonView.id
        gParams.startToEnd = fLabel.id
        gParams.endToEnd = LayoutParams.PARENT_ID
        addView(gLabel, gParams)

        // Alpha label (NOT USED inside key)
        alphaLabel.id = View.generateViewId()
        alphaLabel.visibility = View.GONE
        addView(alphaLabel)

        isClickable = false
        buttonView.isClickable = false
    }

    private fun enableKeyTextRendering(labelView: TextView) {
        labelView.paint.isSubpixelText = true
        labelView.paint.isLinearText = true
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

        primaryLabel.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            fittedTextSizePx(primaryLabel, primarySize, primaryMaxWidth),
        )
        primaryLabel.textScaleX = 1f
        fLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, topLabelTextSize * topLabelPlacement.fScale)
        gLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, topLabelTextSize * topLabelPlacement.gScale)
        letterLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, R47LabelLayoutPolicy.FOURTH_LABEL_TEXT_SIZE * referenceCellToViewWidthScale)
    }

    private fun fittedTextSizePx(labelView: TextView, baseSize: Float, maxWidth: Float): Float {
        val text = labelView.text?.toString().orEmpty()
        if (text.isBlank() || maxWidth <= 0f) {
            return baseSize
        }

        val paint = Paint(labelView.paint)
        paint.textSize = baseSize
        val measured = paint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) {
            return baseSize
        }

        return (baseSize * (maxWidth / measured))
            .coerceAtLeast(baseSize * R47LabelLayoutPolicy.FITTED_TEXT_MIN_SCALE)
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
        val horizontalPadding = 12f * referenceBodyToViewWidthScale
        return (bodyWidth - horizontalPadding).coerceAtLeast(0f)
    }

    private fun measureTextWidth(labelView: TextView, textSize: Float = labelView.textSize): Float {
        val text = labelView.text?.toString().orEmpty()
        if (text.isBlank()) {
            return 0f
        }
        val paint = Paint(labelView.paint)
        paint.textSize = textSize
        return paint.measureText(text)
    }

    private fun textBottomOffset(labelView: TextView, textSize: Float = labelView.textSize): Float {
        val paint = Paint(labelView.paint)
        paint.textSize = textSize
        val metrics = paint.fontMetrics
        return -metrics.ascent + metrics.descent
    }

    private fun resetLabelLayout() {
        val fParams = fLabel.layoutParams as LayoutParams
        val gParams = gLabel.layoutParams as LayoutParams

        fParams.horizontalChainStyle = LayoutParams.CHAIN_PACKED
        fParams.horizontalBias = 0.5f
        fParams.marginStart = 0
        fParams.marginEnd = 0
        gParams.horizontalBias = 0.5f
        gParams.marginEnd = 0
        gParams.marginStart = 0
        fParams.startToStart = LayoutParams.PARENT_ID
        fParams.endToStart = gLabel.id
        gParams.startToEnd = fLabel.id
        gParams.endToEnd = LayoutParams.PARENT_ID

        fLabel.layoutParams = fParams
        gLabel.layoutParams = gParams
    }

    private fun updateFaceplateOffsets() {
        if (isFnKey || width <= 0) {
            return
        }

        val referenceCellToViewWidthScale = if (designCellWidth > 0f) width.toFloat() / designCellWidth else 1f
        val topFgLabelVerticalLift = R47LabelLayoutPolicy.TOP_F_G_LABEL_VERTICAL_LIFT * referenceCellToViewWidthScale
        val topFgLabelHorizontalGap = R47LabelLayoutPolicy.TOP_F_G_LABEL_HORIZONTAL_GAP * referenceCellToViewWidthScale
        val buttonWidth = buttonView.width.toFloat()
        if (buttonWidth <= 0f || buttonView.height <= 0) {
            return
        }

        val referenceBodyToViewWidthScale = if (designButtonWidth > 0f) buttonWidth / designButtonWidth else 1f
        updateMainKeySurfaceRect(mainKeyRect, referenceBodyToViewWidthScale)
        val buttonCenterX = mainKeyRect.centerX()
        val rawButtonCenterX = buttonView.left + buttonWidth / 2f
        primaryLabel.translationX = buttonCenterX - rawButtonCenterX
        val fWidth = measureTextWidth(fLabel)
        val gWidth = measureTextWidth(gLabel)
        val hasFLabel = fLabel.visibility == View.VISIBLE && fLabel.text.isNotBlank()
        val hasGLabel = hasFLabel && gLabel.visibility == View.VISIBLE && gLabel.text.isNotBlank()
        val groupWidth = when {
            hasGLabel -> fWidth + topFgLabelHorizontalGap + gWidth
            hasFLabel -> fWidth
            else -> 0f
        }
        val groupLeft = buttonCenterX - groupWidth / 2f + topLabelPlacement.centerShift

        when {
            hasGLabel -> {
                val fLeft = groupLeft
                val gLeft = groupLeft + fWidth + topFgLabelHorizontalGap
                fLabel.translationX = fLeft - fLabel.left.toFloat()
                gLabel.translationX = gLeft - gLabel.left.toFloat()
            }

            hasFLabel -> {
                fLabel.translationX = groupLeft - fLabel.left.toFloat()
                gLabel.translationX = 0f
            }

            else -> {
                fLabel.translationX = 0f
                gLabel.translationX = 0f
            }
        }

        val topLabelTranslationY = -topFgLabelVerticalLift
        val baseTopLabelTextSize = R47LabelLayoutPolicy.TOP_F_G_LABEL_TEXT_SIZE * referenceCellToViewWidthScale
        val targetTextBottomY = topLabelTranslationY + textBottomOffset(fLabel, baseTopLabelTextSize)
        fLabel.translationY = targetTextBottomY - textBottomOffset(fLabel) - fLabel.top.toFloat()
        gLabel.translationY = targetTextBottomY - textBottomOffset(gLabel) - gLabel.top.toFloat()

        updateFourthLabelOffset(referenceCellToViewWidthScale)
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

        resetLabelLayout()
        val fParams = fLabel.layoutParams as LayoutParams
        val gParams = gLabel.layoutParams as LayoutParams
        val buttonParams = buttonView.layoutParams as LayoutParams

        when (layoutClass) {
            KeypadSceneContract.LAYOUT_CLASS_STATIC_SINGLE -> {
                fParams.endToStart = LayoutParams.UNSET
                fParams.endToEnd = LayoutParams.PARENT_ID
                fParams.horizontalBias = 0.5f
                gLabel.visibility = View.GONE
            }
        }

        if (usesLetterSpacer) {
            buttonParams.endToStart = letterLabel.id
            buttonParams.endToEnd = LayoutParams.UNSET
        } else {
            buttonParams.endToStart = LayoutParams.UNSET
            buttonParams.endToEnd = LayoutParams.PARENT_ID
        }

        buttonView.layoutParams = buttonParams
        fLabel.layoutParams = fParams
        gLabel.layoutParams = gParams
        requestLayout()
    }

    internal fun setKey(slot: KeypadSlotSpec, fonts: KeypadFontSet) {
        this.keyCode = slot.code
        this.isFnKey = slot.isFunctionKey
        this.fontSet = fonts
        primaryLabel.typeface = fonts.standard
        fLabel.typeface = fonts.standard
        gLabel.typeface = fonts.standard
        letterLabel.typeface = fonts.standard

        if (slot.isFunctionKey) {
            softkeyState = KeypadKeySnapshot.EMPTY
            mainKeyState = KeypadKeySnapshot.EMPTY
            fLabel.visibility = View.GONE
            gLabel.visibility = View.GONE
            letterLabel.visibility = View.GONE
            primaryLabel.visibility = View.GONE
            buttonView.visibility = View.INVISIBLE
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
                val lp = buttonView.layoutParams as LayoutParams
                lp.endToStart = LayoutParams.UNSET
                lp.endToEnd = LayoutParams.PARENT_ID
                buttonView.layoutParams = lp
            } else {
                val lp = buttonView.layoutParams as LayoutParams
                lp.endToStart = letterLabel.id
                lp.endToEnd = LayoutParams.UNSET
                buttonView.layoutParams = lp
                letterLabel.visibility = if (keepLetterSpacerInvisible) View.INVISIBLE else View.VISIBLE
            }

            primaryLabel.setTextColor(defaultPrimaryColor)
            mainKeyState = KeypadKeySnapshot.EMPTY
        }
    }

    internal fun setDrawKeySurfaces(draw: Boolean) {
        if (drawKeySurfaces == draw) {
            return
        }
        drawKeySurfaces = draw
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
        val letterParams = letterLabel.layoutParams as LayoutParams
        letterParams.topToTop = buttonView.id
        letterParams.bottomToBottom = buttonView.id
        letterParams.matchConstraintPercentWidth = letterRatio
        letterParams.matchConstraintPercentHeight = R47KeySurfacePolicy.MAIN_KEY_BODY_HEIGHT_FRACTION_OF_VIEW

        if (letterRatio > 0f) {
            buttonParams.endToStart = letterLabel.id
            buttonParams.endToEnd = LayoutParams.UNSET
        } else {
            buttonParams.endToStart = LayoutParams.UNSET
            buttonParams.endToEnd = LayoutParams.PARENT_ID
        }

        buttonView.layoutParams = buttonParams
        letterLabel.layoutParams = letterParams
    }

    private fun primaryTypefaceFor(): Typeface? {
        return fontSet.standard
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
            fLabel, gLabel, letterLabel -> fontSet.standard
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
            primaryLabel.alpha = if (enabled) 1f else 0.6f
            fLabel.alpha = if (enabled) 1f else 0.6f
            gLabel.alpha = if (enabled) 1f else 0.6f
            letterLabel.alpha = if (enabled) 1f else 0.6f
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
            requestLayout()
            invalidate()
            contentDescription = buildString {
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
        val fTextWidth = measureTextWidth(fLabel, baseTopLabelTextSize)
        val gTextWidth = if (hasGLabel) measureTextWidth(gLabel, baseTopLabelTextSize) else 0f
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

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
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
        if (buttonView.width <= 0 || buttonView.height <= 0) {
            return
        }

        val keyState = mainKeyState
        val referenceBodyToViewWidthScale = if (designButtonWidth > 0f) {
            buttonView.width.toFloat() / designButtonWidth
        } else {
            1f
        }
        val cornerRadius = R47KeySurfacePolicy.MAIN_KEY_DRAW_CORNER_RADIUS * referenceBodyToViewWidthScale
        updateMainKeySurfaceRect(mainKeyRect, referenceBodyToViewWidthScale)

        val styleSpec = mainKeyStyleSpec(keyState.styleRole)
        val fillColor = if (isPressed) styleSpec.pressedFillColor else styleSpec.idleFillColor

        if (drawKeySurfaces) {
            drawKeyChrome(
                canvas = canvas,
                rect = mainKeyRect,
                fillPaint = mainKeyFillPaint,
                fillColor = fillColor,
                cornerRadius = cornerRadius,
            )
            if (isPressed) {
                drawPressedKeyAccent(
                    canvas = canvas,
                    rect = mainKeyRect,
                    referenceBodyToViewWidthScale = referenceBodyToViewWidthScale,
                )
            }
        }
    }

    private fun drawPressedKeyAccent(
        canvas: Canvas,
        rect: RectF,
        referenceBodyToViewWidthScale: Float,
    ) {
        val edgeInset = max(4f * referenceBodyToViewWidthScale, 2f)
        val topY = rect.top + max(6f * referenceBodyToViewWidthScale, 2f)
        val bottomY = rect.bottom - max(6f * referenceBodyToViewWidthScale, 2f)

        mainKeyPressedHighlightPaint.color = Color.argb(112, 255, 244, 224)
        mainKeyPressedHighlightPaint.strokeWidth = max(2.2f * referenceBodyToViewWidthScale, 1.5f)
        mainKeyPressedShadowPaint.color = Color.argb(128, 18, 12, 8)
        mainKeyPressedShadowPaint.strokeWidth = max(2.8f * referenceBodyToViewWidthScale, 1.5f)

        canvas.drawLine(
            rect.left + edgeInset,
            topY,
            rect.right - edgeInset,
            topY,
            mainKeyPressedHighlightPaint,
        )
        canvas.drawLine(
            rect.left + edgeInset,
            bottomY,
            rect.right - edgeInset,
            bottomY,
            mainKeyPressedShadowPaint,
        )
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
}
