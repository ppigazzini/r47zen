package io.github.ppigazzini.r47zen

import android.graphics.Canvas
import android.graphics.Paint

internal object SettingsMenuGlyph {
    data class GeometryDp(
        val tabHeightDp: Float,
        val gapDp: Float,
    ) {
        fun tabHeightPx(density: Float): Float = tabHeightDp * density

        fun gapPx(density: Float): Float = gapDp * density

        fun totalWidthPx(density: Float): Float = totalWidth(tabHeightPx(density), gapPx(density))
    }

    private const val TAB_WIDTH_TO_HEIGHT_RATIO = 1f

    const val MAIN_MENU_TAB_HEIGHT_DP = 11f
    const val MAIN_MENU_GAP_DP = 4f
    const val MAIN_MENU_BOTTOM_INSET_DP = 4f
    const val ONBOARDING_TAB_HEIGHT_DP = 18f
    const val ONBOARDING_GAP_DP = 6f

    val MAIN_MENU_GEOMETRY = GeometryDp(
        tabHeightDp = MAIN_MENU_TAB_HEIGHT_DP,
        gapDp = MAIN_MENU_GAP_DP,
    )

    val ONBOARDING_GEOMETRY = GeometryDp(
        tabHeightDp = ONBOARDING_TAB_HEIGHT_DP,
        gapDp = ONBOARDING_GAP_DP,
    )

    fun totalWidth(tabHeight: Float, gap: Float): Float {
        val tabWidth = tabHeight * TAB_WIDTH_TO_HEIGHT_RATIO
        return (tabWidth * 2f) + gap
    }

    fun drawRightAligned(
        canvas: Canvas,
        right: Float,
        top: Float,
        tabHeight: Float,
        gap: Float,
        orangePaint: Paint,
        bluePaint: Paint,
    ) {
        val tabWidth = tabHeight * TAB_WIDTH_TO_HEIGHT_RATIO
        val blueLeft = right - tabWidth
        val orangeLeft = blueLeft - gap - tabWidth
        val bottom = top + tabHeight

        canvas.drawRect(orangeLeft, top, orangeLeft + tabWidth, bottom, orangePaint)
        canvas.drawRect(blueLeft, top, right, bottom, bluePaint)
    }
}
