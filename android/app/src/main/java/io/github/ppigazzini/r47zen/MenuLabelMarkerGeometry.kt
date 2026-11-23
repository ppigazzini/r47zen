package io.github.ppigazzini.r47zen

import android.graphics.Color

// The mark that says an f or g faceplate label opens a menu, which
// KeypadKeySnapshot.opensMenu reads from the scene. The mark is a shape of its
// own beside the text, so no text path, label raster, or font decoration has
// to carry it.
internal object MenuLabelMarkerGeometry {
    // The standard font's post table puts its underline's top 0.25 em under the
    // baseline and makes it 0.10 em thick. MenuLabelMarkerTest holds both to
    // what the platform reads from the font.
    const val BAR_TOP_EM = 0.25f
    const val BAR_THICKNESS_EM = 0.10f

    // The standard font's capital height, the top of a box; MenuLabelMarkerTest
    // holds it to the ink of "H" as the platform measures it.
    const val CAP_HEIGHT_EM = 0.6f

    // A box sits this far outside the label's advance and outside the band of
    // its caps and descent, with corners of this radius, both at the label's
    // own size.
    const val BOX_PAD_EM = 0.09f
    const val BOX_CORNER_RADIUS_EM = 0.12f

    // The outline's stroke, at the unscaled top-label size, so a label the lane
    // solver shrinks keeps a full-weight outline.
    const val BOX_STROKE_EM = 0.06f

    // The tab's length, centered, at the unscaled top-label size, so every tab
    // is one length however far the solver shrank its label. MenuLabelMarkerTest
    // holds every exported menu label at least that long.
    const val TAB_WIDTH_EM = 0.8f

    // The label's color inside a reverse-video box: the box takes the label's
    // color, the text the shell's black, as the LCD inverts a submenu softkey.
    const val REVERSE_TEXT_COLOR = Color.BLACK

    // The pad a boxed style puts on each side of a marked label's advance, at
    // the label's size. The outline's stroke, from stroke(), adds half its width
    // outside that on each side.
    fun sidePad(style: MenuLabelMarkerStyle, labelSize: Float): Float {
        return if (style.boxed) BOX_PAD_EM * labelSize else 0f
    }

    fun stroke(style: MenuLabelMarkerStyle, unscaledLabelSize: Float): Float {
        return if (style == MenuLabelMarkerStyle.ROUNDRECT) BOX_STROKE_EM * unscaledLabelSize else 0f
    }

    fun textColor(style: MenuLabelMarkerStyle, labelColor: Int): Int {
        return if (style == MenuLabelMarkerStyle.REVERSE) REVERSE_TEXT_COLOR else labelColor
    }

    // Lay the style's marker around or under the label.
    fun marker(
        style: MenuLabelMarkerStyle,
        id: String,
        marked: MarkedLabel,
    ): MenuLabelMarkerSpec? {
        val bounds = marked.label.bounds ?: return null
        return if (style.boxed) box(style, id, marked, bounds) else underLabel(style, id, marked, bounds)
    }

    // Lay a zero-width shape on the bar's line: at the unscaled top-label size,
    // a fixed distance under the line-box bottom, which every label of a lane
    // shares, so one lane shows one weight on one line however far the solver
    // shrank a label, and under the label's advance, so it moves no label.
    // Unscaled, the bar is the font's underline, BAR_TOP_EM under the baseline.
    private fun underLabel(
        style: MenuLabelMarkerStyle,
        id: String,
        marked: MarkedLabel,
        bounds: RectSpec,
    ): MenuLabelMarkerSpec {
        val unscaled = marked.unscaledLabelSize
        val unscaledDescent = (bounds.bottom - marked.baselineY) * unscaled / marked.label.textSize
        val top = bounds.bottom + BAR_TOP_EM * unscaled - unscaledDescent
        val bar = RectSpec(bounds.left, top, bounds.right, top + BAR_THICKNESS_EM * unscaled)
        return when (style) {
            MenuLabelMarkerStyle.TAB -> {
                val half = TAB_WIDTH_EM * unscaled / 2f
                val tab = bar.copy(left = bounds.centerX - half, right = bounds.centerX + half)
                MenuLabelMarkerSpec(id, tab, marked.color)
            }
            else -> MenuLabelMarkerSpec(id, bar, marked.color)
        }
    }

    private fun box(
        style: MenuLabelMarkerStyle,
        id: String,
        marked: MarkedLabel,
        bounds: RectSpec,
    ): MenuLabelMarkerSpec {
        val size = marked.label.textSize
        val pad = sidePad(style, size)
        // Keep the height of the caps, the descent, and a pad above and below,
        // but center it on the caps, so a menu name, capitals almost always,
        // sits midway between the box's edges; a descender reaches past the
        // bottom edge by half the descent less the pad.
        val descent = bounds.bottom - marked.baselineY
        val capsCenter = marked.baselineY - CAP_HEIGHT_EM * size / 2f
        val halfHeight = (CAP_HEIGHT_EM * size + descent) / 2f + pad
        return MenuLabelMarkerSpec(
            id = id,
            bounds = RectSpec(
                left = bounds.left - pad,
                top = capsCenter - halfHeight,
                right = bounds.right + pad,
                bottom = capsCenter + halfHeight,
            ),
            color = marked.color,
            shape = if (style == MenuLabelMarkerStyle.REVERSE) {
                MenuLabelMarkerShape.FILL
            } else {
                MenuLabelMarkerShape.OUTLINE
            },
            cornerRadius = BOX_CORNER_RADIUS_EM * size,
            strokeWidth = stroke(style, marked.unscaledLabelSize),
        )
    }
}

// A label the key draws, where it sits, and what its marker is drawn in: the
// label's own color with its alpha. For reverse video the label's color is
// already the dark text, so color is the one the label had before.
internal data class MarkedLabel(
    val label: LabelSpec,
    val baselineY: Float,
    val color: Int,
    val unscaledLabelSize: Float,
)
