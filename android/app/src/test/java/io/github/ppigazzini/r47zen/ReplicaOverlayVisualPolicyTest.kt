package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class ReplicaOverlayVisualPolicyTest {
    @Test
    fun settingsDiscoveryHintLayoutCache_usesContractedCardAndGlyphMetrics() {
        val activity = buildThemedActivity()
        val overlay = configuredOverlay(activity).apply {
            setShowSettingsDiscoveryHint(true)
        }

        render(overlay)

        val layoutCache = readPrivateField<Any?>(overlay, "settingsHintLayoutCache")
        assertNotNull(layoutCache)
        val hintLayoutCache = layoutCache ?: error("settings hint layout cache missing")
        val infoCardRect = readPrivateField<RectF>(hintLayoutCache, "infoCardRect")
        val infoCornerRadius = readPrivateField<Float>(hintLayoutCache, "infoCornerRadius")
        val infoGlyphTop = readPrivateField<Float>(hintLayoutCache, "infoGlyphTop")
        val infoGlyphRight = readPrivateField<Float>(hintLayoutCache, "infoGlyphRight")
        val infoGlyphTabHeight = readPrivateField<Float>(hintLayoutCache, "infoGlyphTabHeight")
        val infoGlyphGap = readPrivateField<Float>(hintLayoutCache, "infoGlyphGap")
        val infoPaddingVertical = readPrivateField<Float>(hintLayoutCache, "infoPaddingVertical")
        val infoTextTop = readPrivateField<Float>(hintLayoutCache, "infoTextTop")

        val density = activity.resources.displayMetrics.density
        val onboardingGeometry = SettingsMenuGlyph.ONBOARDING_GEOMETRY
        val chromeLayout = ReplicaChromeLayout(activity.resources).apply {
            setScalingMode("full_width")
        }
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, overlay.width.toFloat(), overlay.height.toFloat())
        val shellRect = RectF(
            projection.offsetX,
            projection.offsetY,
            projection.offsetX + spec.shellWidth * projection.scale,
            projection.offsetY + spec.shellHeight * projection.scale,
        )
        val lcdDestRect = RectF(
            projection.offsetX + spec.lcdWindowLeft * projection.scale,
            projection.offsetY + spec.lcdWindowTop * projection.scale,
            projection.offsetX + (spec.lcdWindowLeft + spec.lcdWindowWidth) * projection.scale,
            projection.offsetY + (spec.lcdWindowTop + spec.lcdWindowHeight) * projection.scale,
        )
        val expectedCardWidth = min(
            shellRect.width() - dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_OUTER_MARGIN_DP),
            max(
                dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_MIN_WIDTH_DP),
                min(
                    dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_MAX_WIDTH_DP),
                    shellRect.width() * SettingsDiscoveryHintVisualPolicy.CARD_WIDTH_RATIO,
                ),
            ),
        )
        val expectedOuterMargin = dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_OUTER_MARGIN_DP)

        assertEquals(expectedCardWidth, infoCardRect.width(), 0.01f)
        assertTrue(
            infoCardRect.top >= max(shellRect.top + expectedOuterMargin, lcdDestRect.bottom + expectedOuterMargin),
        )
        assertEquals(onboardingGeometry.tabHeightPx(density), infoGlyphTabHeight, 0.01f)
        assertEquals(onboardingGeometry.gapPx(density), infoGlyphGap, 0.01f)
        assertEquals(
            dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_CORNER_RADIUS_DP),
            infoCornerRadius,
            0.01f,
        )
        assertEquals(infoCardRect.top + infoPaddingVertical, infoGlyphTop, 0.01f)
        assertEquals(
            infoGlyphTop + infoGlyphTabHeight + dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_GLYPH_TEXT_GAP_DP),
            infoTextTop,
            0.01f,
        )
        assertEquals(
            infoCardRect.centerX() + onboardingGeometry.totalWidthPx(density) / 2f,
            infoGlyphRight,
            0.01f,
        )
    }

    @Test
    fun settingsDiscoveryHintFallbackPalette_usesContractedColorsForUnthemedContexts() {
        val overlay = ReplicaOverlay(ApplicationProvider.getApplicationContext())

        val orangePaint = readPrivateField<Paint>(overlay, "settingsHintMenuOrangePaint")
        val bluePaint = readPrivateField<Paint>(overlay, "settingsHintMenuBluePaint")

        assertEquals(
            SettingsDiscoveryHintVisualPolicy.MENU_ORANGE_FALLBACK_COLOR_ARGB,
            orangePaint.color,
        )
        assertEquals(
            SettingsDiscoveryHintVisualPolicy.MENU_BLUE_FALLBACK_COLOR_ARGB,
            bluePaint.color,
        )
    }

    @Test
    fun touchZoneDebugPaint_usesContractedStrokePolicy() {
        val overlay = ReplicaOverlay(ApplicationProvider.getApplicationContext())
        val zonePaint = readPrivateField<Paint>(overlay, "zonePaint")
        val density = overlay.resources.displayMetrics.density

        assertEquals(
            TouchZoneDebugVisualPolicy.ZONE_STROKE_WIDTH_DP * density,
            zonePaint.strokeWidth,
            0.01f,
        )
        assertEquals(TouchZoneDebugVisualPolicy.STROKE_ALPHA, zonePaint.alpha)
    }

    @Test
    fun developerPerformanceHudDraw_usesContractedTextSizingBounds() {
        val activity = buildThemedActivity()
        val overlay = configuredOverlay(activity).apply {
            setShowDeveloperPerformanceHud(true)
            updateDeveloperPerformanceSnapshot(
                DeveloperPerformanceSnapshot(
                    uiFramesPerSecond = 59.8f,
                    lcdUpdatesPerSecond = 12.4f,
                    averageLcdUpdateMillis = 0.38f,
                    lcdUpdateSamples = 8,
                ),
            )
        }

        render(overlay)

        val hudPaint = readPrivateField<TextPaint>(overlay, "developerPerformanceTextPaint")
        val label = readPrivateField<String>(overlay, "developerPerformanceLabel")
        val lcdDestRect = readPrivateField<RectF>(overlay, "lcdDestRect")
        val shellRect = readPrivateField<RectF>(overlay, "shellRect")
        val expectedTextSize = (
            (lcdDestRect.top - shellRect.top)
                .coerceAtLeast(dp(activity, DeveloperPerformanceHudVisualPolicy.MIN_AVAILABLE_HEIGHT_DP))
                * DeveloperPerformanceHudVisualPolicy.TEXT_HEIGHT_RATIO
            ).coerceIn(
                dp(activity, DeveloperPerformanceHudVisualPolicy.MIN_TEXT_SIZE_DP),
                dp(activity, DeveloperPerformanceHudVisualPolicy.MAX_TEXT_SIZE_DP),
            )
        val maxLabelWidth = (
            lcdDestRect.width() - dp(activity, DeveloperPerformanceHudVisualPolicy.MAX_LABEL_HORIZONTAL_MARGIN_DP)
            ).coerceAtLeast(dp(activity, DeveloperPerformanceHudVisualPolicy.MIN_LABEL_WIDTH_DP))

        assertEquals(expectedTextSize, hudPaint.textSize, 0.01f)
        assertTrue(hudPaint.measureText(label) <= maxLabelWidth + 0.01f)
    }

    @Test
    fun settingsDiscoveryHint_firstTouchDismissesHintAndInvokesCompletionCallback() {
        val activity = buildThemedActivity()
        val overlay = configuredOverlay(activity).apply {
            setShowSettingsDiscoveryHint(true)
        }
        var completionCount = 0
        overlay.onSettingsDiscoveryCompleted = {
            completionCount += 1
        }
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 540f, 1080f, 0)

        try {
            assertFalse(overlay.onInterceptTouchEvent(down))
        } finally {
            down.recycle()
        }

        assertEquals(1, completionCount)
        assertFalse(readPrivateField(overlay, "showSettingsDiscoveryHint"))
        assertEquals(null, readPrivateField<Any?>(overlay, "settingsHintLayoutCache"))
    }

    private fun buildThemedActivity(): ThemedOverlayHostActivity {
        return Robolectric.buildActivity(ThemedOverlayHostActivity::class.java)
            .setup()
            .get()
    }

    private fun configuredOverlay(activity: AppCompatActivity): ReplicaOverlay {
        return ReplicaOverlay(activity).apply {
            setScalingMode("full_width")
            measure(exactly(1080), exactly(2160))
            layout(0, 0, 1080, 2160)
        }
    }

    private fun render(overlay: ReplicaOverlay) {
        val bitmap = Bitmap.createBitmap(1080, 2160, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(bitmap))
    }

    private fun dp(activity: AppCompatActivity, value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            activity.resources.displayMetrics,
        )
    }

    private fun exactly(size: Int): Int {
        return android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readPrivateField(instance: Any, fieldName: String): T {
        var currentClass: Class<*>? = instance.javaClass
        while (currentClass != null) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(instance) as T
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }

        error("Missing field $fieldName on ${instance.javaClass.name}")
    }

    private class ThemedOverlayHostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_R47)
            super.onCreate(savedInstanceState)
        }
    }
}
