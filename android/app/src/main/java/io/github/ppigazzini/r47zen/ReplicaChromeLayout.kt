package io.github.ppigazzini.r47zen

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

internal data class ReplicaProjection(val scale: Float, val offsetX: Float, val offsetY: Float)

internal data class ReplicaChromeSpec(
    val shellWidth: Float,
    val shellHeight: Float,
    val topBezelSettingsTapHeight: Float,
    val lcdWindowLeft: Float,
    val lcdWindowTop: Float,
    val lcdWindowWidth: Float,
    val lcdWindowHeight: Float,
    val scaledModeFitTrimLeft: Float = 0f,
    val scaledModeFitTrimTop: Float = 0f,
    val scaledModeFitTrimRight: Float = 0f,
    val scaledModeFitTrimBottom: Float = 0f,
)

internal class ReplicaChromeLayout(
    @Suppress("UNUSED_PARAMETER") resources: Resources,
) {
    private val nativeChromeSpec = ReplicaChromeSpec(
        shellWidth = R47ReferenceGeometry.LOGICAL_CANVAS_WIDTH,
        shellHeight = R47ReferenceGeometry.LOGICAL_CANVAS_HEIGHT,
        topBezelSettingsTapHeight = R47AndroidChromeGeometry.TOP_BEZEL_SETTINGS_TAP_HEIGHT,
        lcdWindowLeft = R47AndroidChromeGeometry.NATIVE_LCD_WINDOW_LEFT,
        lcdWindowTop = R47AndroidChromeGeometry.NATIVE_LCD_WINDOW_TOP,
        lcdWindowWidth = R47AndroidChromeGeometry.NATIVE_LCD_WINDOW_WIDTH,
        lcdWindowHeight = R47AndroidChromeGeometry.NATIVE_LCD_WINDOW_HEIGHT,
        scaledModeFitTrimLeft = R47AndroidChromeGeometry.SCALED_MODE_FIT_TRIM_LEFT,
        scaledModeFitTrimTop = R47AndroidChromeGeometry.SCALED_MODE_FIT_TRIM_TOP,
        scaledModeFitTrimRight = R47AndroidChromeGeometry.SCALED_MODE_FIT_TRIM_RIGHT,
        scaledModeFitTrimBottom = R47AndroidChromeGeometry.SCALED_MODE_FIT_TRIM_BOTTOM,
    )

    fun currentChromeSpec(): ReplicaChromeSpec {
        return nativeChromeSpec
    }

    fun computeProjection(availableWidth: Float, availableHeight: Float): ReplicaProjection {
        return computeProjection(currentChromeSpec(), availableWidth, availableHeight)
    }

    fun computeProjection(
        spec: ReplicaChromeSpec,
        availableWidth: Float,
        availableHeight: Float,
    ): ReplicaProjection {
        val fitLeft = spec.scaledModeFitTrimLeft
        val fitTop = spec.scaledModeFitTrimTop
        val fitWidth = spec.shellWidth - spec.scaledModeFitTrimLeft - spec.scaledModeFitTrimRight
        val fitHeight = spec.shellHeight - spec.scaledModeFitTrimTop - spec.scaledModeFitTrimBottom
        val fitScale = min(availableWidth / fitWidth, availableHeight / fitHeight)
        val offsetX = (availableWidth - fitWidth * fitScale) / 2f - fitLeft * fitScale
        val offsetY = (availableHeight - fitHeight * fitScale) / 2f - fitTop * fitScale
        return ReplicaProjection(fitScale, offsetX, offsetY)
    }

    fun drawShellBackground(
        canvas: Canvas,
        spec: ReplicaChromeSpec,
        rect: RectF,
        projectionScale: Float,
    ) {
        // Native-only chrome keeps the calculator surface borderless. The
        // shell rect still anchors the LCD, touch strip, and discovery hint.
    }
}
