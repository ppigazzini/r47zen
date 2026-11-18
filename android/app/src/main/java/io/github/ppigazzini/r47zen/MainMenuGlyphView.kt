package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.google.android.material.color.MaterialColors

// The top-right orange-blue marker that opens the main menu: the two tabs
// SettingsMenuGlyph draws, right-aligned at the view's bottom in the theme's
// primary and secondary colors. MainActivity installs it on the replica.
internal class MainMenuGlyphView(context: Context) : View(context) {
    private val menuGlyphGeometry = SettingsMenuGlyph.MAIN_MENU_GEOMETRY
    private val orangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@MainMenuGlyphView, androidx.appcompat.R.attr.colorPrimary)
        style = Paint.Style.FILL
    }
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@MainMenuGlyphView, com.google.android.material.R.attr.colorSecondary)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        val tabHeight = menuGlyphGeometry.tabHeightPx(density)
        val gap = menuGlyphGeometry.gapPx(density)
        val top = height - tabHeight - (SettingsMenuGlyph.MAIN_MENU_BOTTOM_INSET_DP * density)

        SettingsMenuGlyph.drawRightAligned(
            canvas = canvas,
            right = width.toFloat(),
            top = top,
            tabHeight = tabHeight,
            gap = gap,
            orangePaint = orangePaint,
            bluePaint = bluePaint,
        )
    }
}
