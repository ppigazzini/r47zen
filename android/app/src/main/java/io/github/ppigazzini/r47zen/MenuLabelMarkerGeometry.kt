package io.github.ppigazzini.r47zen

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

    // Lay the bar under the label's advance, at the label's own size, in the
    // label's color and alpha.
    fun bar(
        id: String,
        label: LabelSpec,
        baselineY: Float,
    ): MenuLabelMarkerSpec? {
        val bounds = label.bounds ?: return null
        val top = baselineY + BAR_TOP_EM * label.textSize
        return MenuLabelMarkerSpec(
            id = id,
            bounds = RectSpec(
                left = bounds.left,
                top = top,
                right = bounds.right,
                bottom = top + BAR_THICKNESS_EM * label.textSize,
            ),
            color = C47TextRenderer.colorWithAlpha(label.color, label.alpha),
        )
    }
}
