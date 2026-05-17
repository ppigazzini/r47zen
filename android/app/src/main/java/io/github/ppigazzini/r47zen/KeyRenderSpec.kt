package io.github.ppigazzini.r47zen

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

internal data class PointSpec(
    val x: Float,
    val y: Float,
)

internal data class RectSpec(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    val centerX: Float
        get() = (left + right) * 0.5f

    val centerY: Float
        get() = (top + bottom) * 0.5f

    fun asRectF(): RectF {
        return RectF(left, top, right, bottom)
    }
}

internal data class LineSpec(
    val start: PointSpec,
    val end: PointSpec,
)

internal data class KeyChromeSpec(
    val bounds: RectSpec,
    val fillColor: Int,
    val cornerRadius: Float,
    val drawSurface: Boolean,
    val pressedAccents: List<LineAdornmentSpec> = emptyList(),
)

internal data class LabelSpec(
    val id: String,
    val text: String,
    val visible: Boolean,
    val anchor: PointSpec,
    val bounds: RectSpec?,
    val typeface: Typeface?,
    val textSize: Float,
    val color: Int,
    val align: Paint.Align,
    val verticalAnchor: Int,
    val alpha: Float = 1f,
    val textScaleX: Float = 1f,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
)

internal sealed interface KeyRenderLabelSlot {
    val id: String
}

internal enum class MainKeyLabelSlot(override val id: String) : KeyRenderLabelSlot {
    PRIMARY("main-primary"),
    F("main-f"),
    G("main-g"),
    LETTER("main-letter"),
}

internal enum class SoftkeyLabelSlot(override val id: String) : KeyRenderLabelSlot {
    VALUE("value"),
    PRIMARY("primary"),
    AUX("aux"),
    OVERLAY_MENU("overlay-menu"),
}

internal data class AccessibilitySpec(
    val contentDescription: String,
)

internal sealed interface AdornmentSpec

internal sealed interface KeyRenderAdornmentSlot {
    val id: String
}

internal enum class MainKeyAdornmentSlot(override val id: String) : KeyRenderAdornmentSlot {
    PRESSED_HIGHLIGHT("main-pressed-highlight"),
    PRESSED_SHADOW("main-pressed-shadow"),
}

internal enum class SoftkeyAdornmentSlot(override val id: String) : KeyRenderAdornmentSlot {
    PREVIEW("preview-line"),
    OVERLAY("overlay"),
    OVERLAY_UNDERLINE("overlay-underline"),
    STRIKE_THROUGH("strike-through"),
    STRIKE_OUT("strike-out"),
    PRESSED_HIGHLIGHT("pressed-highlight"),
    PRESSED_SHADOW("pressed-shadow"),
}

internal data class LineAdornmentSpec(
    val id: String,
    val line: LineSpec,
    val color: Int,
    val strokeWidth: Float,
    val strokeCap: Paint.Cap = Paint.Cap.ROUND,
) : AdornmentSpec

internal enum class SoftkeyOverlayKind {
    RADIO_FALSE,
    RADIO_TRUE,
    CHECKBOX_FALSE,
    CHECKBOX_TRUE,
    MENU_BADGE_FALSE,
    MENU_BADGE_TRUE,
}

internal data class SoftkeyOverlayAdornmentSpec(
    val id: String,
    val kind: SoftkeyOverlayKind,
    val center: PointSpec,
    val color: Int,
    val frameBounds: RectSpec? = null,
    val label: LabelSpec? = null,
    val underline: LineAdornmentSpec? = null,
) : AdornmentSpec

internal sealed interface GeometrySpec

internal data class TopLabelGroupSpec(
    val bounds: RectSpec,
    val groupCenterX: Float,
    val groupLeft: Float,
    val corridorLeft: Float,
    val corridorRight: Float,
    val gapWidth: Float,
    val fLeft: Float,
    val gRight: Float?,
    val baselineY: Float,
    val fScale: Float,
    val gScale: Float,
)

internal data class MainKeyGeometrySpec(
    val bodyBounds: RectSpec,
    val primaryAnchor: PointSpec,
    val topLabelGroup: TopLabelGroupSpec?,
    val fourthLabelAnchor: PointSpec?,
) : GeometrySpec

internal data class SoftkeyGeometrySpec(
    val bodyBounds: RectSpec,
    val valueFieldBounds: RectSpec? = null,
    val overlayCenter: PointSpec? = null,
    val previewLine: LineSpec? = null,
) : GeometrySpec

internal data class KeyRenderSpec(
    val chrome: KeyChromeSpec?,
    val labels: List<LabelSpec>,
    val adornments: List<AdornmentSpec>,
    val accessibility: AccessibilitySpec,
    val geometry: GeometrySpec? = null,
)

internal fun KeyRenderSpec.label(id: String): LabelSpec? {
    return labels.firstOrNull { it.id == id }
}

internal fun KeyRenderSpec.label(slot: KeyRenderLabelSlot): LabelSpec? {
    return label(slot.id)
}

internal fun KeyRenderSpec.adornment(id: String): AdornmentSpec? {
    return adornments.firstOrNull { adornment ->
        when (adornment) {
            is LineAdornmentSpec -> adornment.id == id
            is SoftkeyOverlayAdornmentSpec -> adornment.id == id
        }
    }
}

internal fun KeyRenderSpec.adornment(slot: KeyRenderAdornmentSlot): AdornmentSpec? {
    return adornment(slot.id)
}
