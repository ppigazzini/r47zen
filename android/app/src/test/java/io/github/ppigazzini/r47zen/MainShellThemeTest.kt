package io.github.ppigazzini.r47zen

import android.app.PictureInPictureParams
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
import android.util.Rational
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.MaterialColors
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

    companion object {
        private const val INFO_CARD_SAMPLE_INSET_DP = 14f
    }

    @Test
    fun enterPictureInPicture_usesLcdAspectRatio() {
        val activity = Robolectric.buildActivity(PiPCapturingActivity::class.java)
            .setup()
            .get()
        val controller = WindowModeController(
            activity = activity,
            mainHandler = Handler(Looper.getMainLooper()),
            onPiPModeChanged = {},
        )

        controller.enterPictureInPicture()

        assertEquals(Rational(400, 240), activity.lastPictureInPictureParams?.aspectRatio)
    }

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
            setShowSettingsDiscoveryHint(true)
            measure(exactly(1080), exactly(2160))
            layout(0, 0, 1080, 2160)
        }
        val bitmap = Bitmap.createBitmap(1080, 2160, Bitmap.Config.ARGB_8888)

        overlay.draw(Canvas(bitmap))

        val samplePoints = computeHintSamplePoints(activity, overlay)
        val infoCardColor = bitmap.getPixel(samplePoints.infoX, samplePoints.infoY)

        assertTrue(ColorUtils.calculateLuminance(infoCardColor) < 0.15)
    }

    @Test
    fun mainShellTheme_keepsDarkSurfacesInLightSystemMode() {
        val activity = buildThemedActivity()
        val surface = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorSurface,
            Color.MAGENTA,
        )
        val onSurface = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorOnSurface,
            Color.MAGENTA,
        )

        assertTrue(ColorUtils.calculateLuminance(surface) < 0.15)
        assertTrue(ColorUtils.calculateLuminance(onSurface) > 0.7)
    }

    @Test
    fun settingsDiscoveryHint_copyReferencesTopRightMenu() {
        val activity = buildThemedActivity()

        assertEquals(
            "Welcome to R47 Zen\nTap the orange and blue rectangles at top right to open the menu for Settings, Copy Actions, and Paste Number.\nSet Keypad Layout and Working Directory there.",
            activity.getString(R.string.settings_entry_hint_message),
        )
    }

    @Test
    fun mainMenuCopy_usesFixedShellStrings() {
        val activity = buildThemedActivity()

        assertEquals("Open top-right menu", activity.getString(R.string.main_menu_button_content_description))
        assertEquals("Settings", activity.getString(R.string.main_menu_settings))
        assertEquals("Copy...", activity.getString(R.string.main_menu_copy))
        assertEquals("Copy X Register", activity.getString(R.string.main_menu_copy_x_register))
        assertEquals("Copy Stack Registers", activity.getString(R.string.main_menu_copy_stack_registers))
        assertEquals("Copy All Registers", activity.getString(R.string.main_menu_copy_all_registers))
        assertEquals("Paste Number", activity.getString(R.string.main_menu_paste_number))
        assertEquals("Picture in Picture", activity.getString(R.string.main_menu_picture_in_picture))
    }

    @Test
    fun mainMenuGeometry_staysRightAlignedToLcdInsideTopBezel() {
        val activity = buildThemedActivity()
        val chromeLayout = ReplicaChromeLayout(activity.resources)
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, 1080f, 2160f)

        val menuLeft = projection.offsetX + R47AndroidChromeGeometry.MAIN_MENU_BUTTON_LEFT * projection.scale
        val menuTop = projection.offsetY + R47AndroidChromeGeometry.MAIN_MENU_BUTTON_TOP * projection.scale
        val menuRight = projection.offsetX +
            (R47AndroidChromeGeometry.MAIN_MENU_BUTTON_LEFT + R47AndroidChromeGeometry.MAIN_MENU_BUTTON_WIDTH) * projection.scale
        val menuBottom = projection.offsetY +
            (R47AndroidChromeGeometry.MAIN_MENU_BUTTON_TOP + R47AndroidChromeGeometry.MAIN_MENU_BUTTON_HEIGHT) * projection.scale
        val lcdLeft = projection.offsetX + spec.lcdWindowLeft * projection.scale
        val lcdTop = projection.offsetY + spec.lcdWindowTop * projection.scale
        val lcdRight = projection.offsetX + (spec.lcdWindowLeft + spec.lcdWindowWidth) * projection.scale
        val shellTop = projection.offsetY

        assertEquals(shellTop, menuTop, 0.01f)
        assertEquals(lcdTop, menuBottom, 0.01f)
        assertEquals(lcdRight, menuRight, 0.01f)
        assertTrue(menuLeft >= lcdLeft)
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
        val chromeLayout = ReplicaChromeLayout(activity.resources)
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
        val infoCardWidth = min(
            shellRect.width() - dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_OUTER_MARGIN_DP),
            max(
                dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_MIN_WIDTH_DP),
                min(
                    dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_MAX_WIDTH_DP),
                    shellRect.width() * SettingsDiscoveryHintVisualPolicy.CARD_WIDTH_RATIO,
                ),
            ),
        )
        val overlayDensity = activity.resources.displayMetrics.density
        val infoGlyphTabHeight = SettingsMenuGlyph.ONBOARDING_GEOMETRY.tabHeightPx(overlayDensity)
        val infoGlyphTextGap = dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_GLYPH_TEXT_GAP_DP)

        val infoPaddingHorizontal = dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_HORIZONTAL_PADDING_DP)
        val infoPaddingVertical = dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_VERTICAL_PADDING_DP)
        val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F7F3EA")
            textSize = dp(activity, SettingsDiscoveryHintVisualPolicy.INFO_TEXT_SIZE_DP)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        }
        val infoMessage = activity.getString(R.string.settings_entry_hint_message)
        val infoTextWidth = (infoCardWidth - infoPaddingHorizontal * 2f).roundToInt().coerceAtLeast(1)
        val infoLayout = StaticLayout.Builder
            .obtain(infoMessage, 0, infoMessage.length, infoPaint, infoTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_LINE_SPACING_DP), 1f)
            .build()
        val infoCardHeight = infoLayout.height + infoPaddingVertical * 2f + infoGlyphTabHeight + infoGlyphTextGap
        val infoCardOuterMargin = dp(activity, SettingsDiscoveryHintVisualPolicy.CARD_OUTER_MARGIN_DP)
        val minInfoTop = max(shellRect.top + infoCardOuterMargin, lcdDestRect.bottom + infoCardOuterMargin)
        val maxInfoTop = shellRect.bottom - infoCardHeight - infoCardOuterMargin
        val preferredInfoTop = shellRect.centerY() - infoCardHeight / 2f
        val infoTop = if (maxInfoTop > minInfoTop) {
            preferredInfoTop.coerceIn(minInfoTop, maxInfoTop)
        } else {
            minInfoTop
        }
        val infoCardRect = RectF(
            shellRect.centerX() - infoCardWidth / 2f,
            infoTop,
            shellRect.centerX() + infoCardWidth / 2f,
            infoTop + infoCardHeight,
        )

        return HintSamplePoints(
            infoX = (infoCardRect.left + dp(activity, INFO_CARD_SAMPLE_INSET_DP)).roundToInt(),
            infoY = (infoCardRect.top + dp(activity, INFO_CARD_SAMPLE_INSET_DP)).roundToInt(),
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
        val infoX: Int,
        val infoY: Int,
    )

    private class PiPCapturingActivity : AppCompatActivity() {
        var lastPictureInPictureParams: PictureInPictureParams? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_R47)
            super.onCreate(savedInstanceState)
        }

        override fun enterPictureInPictureMode(params: PictureInPictureParams): Boolean {
            lastPictureInPictureParams = params
            return true
        }
    }

    private class ThemedShellActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_R47)
            super.onCreate(savedInstanceState)
        }
    }
}
