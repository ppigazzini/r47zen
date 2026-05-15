package io.github.ppigazzini.r47zen

import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class LcdThemePolicyTest {

    @Test
    fun normalizeStorageValue_fallsBackToDefaultTheme() {
        assertEquals(
            DEFAULT_LCD_THEME_STORAGE_VALUE,
            LcdThemePolicy.normalizeStorageValue("paper_white"),
        )
    }

    @Test
    fun resolve_preservesReadableContrastAcrossSupportedThemes() {
        val luminanceValues = listOf(
            MainActivityPreferenceController.MIN_LCD_LUMINANCE,
            MainActivityPreferenceController.DEFAULT_LCD_LUMINANCE,
            MainActivityPreferenceController.MAX_LCD_LUMINANCE,
        )

        for (theme in LcdTheme.entries) {
            for (isNegative in listOf(false, true)) {
                for (luminance in luminanceValues) {
                    val palette = LcdThemePolicy.resolve(theme.storageValue, luminance, isNegative)
                    val contrast = ColorUtils.calculateContrast(palette.text, palette.background)

                    assertTrue(
                        "${theme.storageValue} negative=$isNegative at $luminance% contrast was $contrast",
                        contrast >= theme.minimumContrast,
                    )
                }
            }
        }
    }

    @Test
    fun resolve_negativeModeKeepsBackgroundDarkerThanText() {
        for (theme in LcdTheme.entries) {
            val palette = LcdThemePolicy.resolve(
                theme.storageValue,
                MainActivityPreferenceController.DEFAULT_LCD_LUMINANCE,
                true,
            )

            assertTrue(
                "${theme.storageValue} negative palette should keep text brighter than background",
                ColorUtils.calculateLuminance(palette.text) > ColorUtils.calculateLuminance(palette.background),
            )
        }
    }

    @Test
    fun resolve_lightModeChangesBackgroundLuminanceAcrossRange() {
        for (theme in LcdTheme.entries) {
            val minimumPalette = LcdThemePolicy.resolve(
                theme.storageValue,
                MainActivityPreferenceController.MIN_LCD_LUMINANCE,
                false,
            )
            val maximumPalette = LcdThemePolicy.resolve(
                theme.storageValue,
                MainActivityPreferenceController.MAX_LCD_LUMINANCE,
                false,
            )
            val luminanceDelta = abs(
                ColorUtils.calculateLuminance(maximumPalette.background) -
                    ColorUtils.calculateLuminance(minimumPalette.background),
            )

            assertTrue(
                "${theme.storageValue} background luminance delta was $luminanceDelta",
                luminanceDelta >= 0.5,
            )
            assertTrue(
                "${theme.storageValue} minimum palette should keep the background lighter than the text",
                ColorUtils.calculateLuminance(minimumPalette.background) >
                    ColorUtils.calculateLuminance(minimumPalette.text),
            )
        }
    }
}
