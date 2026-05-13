package io.github.ppigazzini.r47

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "notnight")
class MainShellThemeTest {

    @Test
    fun fullscreenOff_usesDarkVisibleSystemBars() {
        val activity = buildThemedActivity()
        val controller = WindowModeController(
            activity = activity,
            mainHandler = Handler(Looper.getMainLooper()),
            onPiPModeChanged = {},
        )

        controller.applyFullscreenMode(false)

        val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        val expectedBarColor = Color.rgb(18, 21, 26)

        assertEquals(expectedBarColor, activity.window.statusBarColor)
        assertEquals(expectedBarColor, activity.window.navigationBarColor)
        assertFalse(insetsController.isAppearanceLightStatusBars)
        assertFalse(insetsController.isAppearanceLightNavigationBars)
    }

    @Test
    fun settingsDiscoveryHint_keepsDarkCardsInLightSystemMode() {
        val activity = buildThemedActivity()
        val overlay = ReplicaOverlay(activity).apply {
            setScalingMode("full_width")
            setShowSettingsDiscoveryHint(true)
            measure(exactly(1080), exactly(2160))
            layout(0, 0, 1080, 2160)
        }
        val bitmap = Bitmap.createBitmap(1080, 2160, Bitmap.Config.ARGB_8888)

        overlay.draw(Canvas(bitmap))

        val samplePoints = computeHintSamplePoints(activity, overlay)
        val topBannerColor = bitmap.getPixel(samplePoints.topX, samplePoints.topY)
        val infoCardColor = bitmap.getPixel(samplePoints.infoX, samplePoints.infoY)

        assertTrue(ColorUtils.calculateLuminance(topBannerColor) < 0.15)
        assertTrue(ColorUtils.calculateLuminance(infoCardColor) < 0.15)
    }

    private fun buildThemedActivity(): ThemedShellActivity {
        return Robolectric.buildActivity(ThemedShellActivity::class.java)
            .setup()
            .get()
    }

    private fun computeHintSamplePoints(
        activity: ThemedShellActivity,
        overlay: ReplicaOverlay,
    ): HintSamplePoints {
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
        val bannerWidth = min(
            shellRect.width() - dp(activity, 24f),
            max(dp(activity, 220f), min(dp(activity, 360f), shellRect.width() * 0.72f)),
        )
        val topBannerRect = RectF(
            shellRect.centerX() - bannerWidth / 2f,
            projection.offsetY + dp(activity, 8f),
            shellRect.centerX() + bannerWidth / 2f,
            projection.offsetY + spec.topBezelSettingsTapHeight * projection.scale - dp(activity, 8f),
        )

        val infoPaddingHorizontal = dp(activity, 18f)
        val infoPaddingVertical = dp(activity, 16f)
        val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F7F3EA")
            textSize = dp(activity, 14f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        }
        val infoMessage = activity.getString(R.string.settings_entry_hint_message)
        val infoTextWidth = (bannerWidth - infoPaddingHorizontal * 2f).roundToInt().coerceAtLeast(1)
        val infoLayout = StaticLayout.Builder
            .obtain(infoMessage, 0, infoMessage.length, infoPaint, infoTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(dp(activity, 4f), 1f)
            .build()
        val infoCardHeight = infoLayout.height + infoPaddingVertical * 2f
        val minInfoTop = max(topBannerRect.bottom + dp(activity, 24f), lcdDestRect.bottom + dp(activity, 24f))
        val maxInfoTop = shellRect.bottom - infoCardHeight - dp(activity, 24f)
        val preferredInfoTop = shellRect.centerY() - infoCardHeight / 2f
        val infoTop = if (maxInfoTop > minInfoTop) {
            preferredInfoTop.coerceIn(minInfoTop, maxInfoTop)
        } else {
            minInfoTop
        }
        val infoCardRect = RectF(
            shellRect.centerX() - bannerWidth / 2f,
            infoTop,
            shellRect.centerX() + bannerWidth / 2f,
            infoTop + infoCardHeight,
        )

        return HintSamplePoints(
            topX = topBannerRect.centerX().roundToInt(),
            topY = (topBannerRect.top + dp(activity, 12f)).roundToInt(),
            infoX = infoCardRect.centerX().roundToInt(),
            infoY = (infoCardRect.top + dp(activity, 12f)).roundToInt(),
        )
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

    private data class HintSamplePoints(
        val topX: Int,
        val topY: Int,
        val infoX: Int,
        val infoY: Int,
    )

    private class ThemedShellActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_R47)
            super.onCreate(savedInstanceState)
        }
    }
}
