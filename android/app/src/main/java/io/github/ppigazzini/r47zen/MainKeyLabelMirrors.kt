package io.github.ppigazzini.r47zen

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.TextView

internal class MainKeyLabelMirrors(
    context: Context,
    fourthLabelColor: Int,
    fAccentColor: Int,
    gAccentColor: Int,
) {
    val primaryLabel = TextView(context).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
    }
    val fLabel = TextView(context).apply {
        id = View.generateViewId()
        setTextColor(fAccentColor)
        gravity = Gravity.START or Gravity.TOP
        includeFontPadding = false
        maxLines = 1
    }
    val gLabel = TextView(context).apply {
        id = View.generateViewId()
        setTextColor(gAccentColor)
        gravity = Gravity.END or Gravity.TOP
        includeFontPadding = false
        maxLines = 1
    }
    val letterLabel = TextView(context).apply {
        id = View.generateViewId()
        setTextColor(fourthLabelColor)
        gravity = Gravity.START or Gravity.TOP
        includeFontPadding = false
        maxLines = 1
    }

    fun applyFonts(fonts: KeypadFontSet) {
        forEachMirror { labelView ->
            labelView.typeface = fonts.standard
        }
    }

    fun applyEnabledAlpha(enabled: Boolean) {
        val mirrorAlpha = if (enabled) 1f else 0.6f
        forEachMirror { labelView ->
            labelView.alpha = mirrorAlpha
        }
    }

    fun resetGeometry() {
        forEachMirror { labelView ->
            labelView.translationX = 0f
            labelView.translationY = 0f
        }
    }

    fun syncGeometry(
        primarySpec: LabelSpec?,
        fSpec: LabelSpec?,
        gSpec: LabelSpec?,
        letterSpec: LabelSpec?,
    ) {
        syncMirrorFromLabelSpec(primaryLabel, primarySpec)
        syncMirrorFromLabelSpec(fLabel, fSpec)
        syncMirrorFromLabelSpec(gLabel, gSpec)
        syncMirrorFromLabelSpec(letterLabel, letterSpec)
    }

    fun isMirrorView(child: View): Boolean {
        return child === primaryLabel || child === fLabel || child === gLabel || child === letterLabel
    }

    private inline fun forEachMirror(action: (TextView) -> Unit) {
        action(primaryLabel)
        action(fLabel)
        action(gLabel)
        action(letterLabel)
    }

    private fun syncMirrorFromLabelSpec(labelView: TextView, labelSpec: LabelSpec?) {
        val bounds = labelSpec?.bounds
        if (bounds == null) {
            labelView.translationX = 0f
            labelView.translationY = 0f
            return
        }
        labelView.translationX = bounds.left
        labelView.translationY = bounds.top
    }
}
