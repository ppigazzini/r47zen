package io.github.ppigazzini.r47zen

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

internal const val DEFAULT_LCD_THEME_STORAGE_VALUE = "high_contrast"

private const val LCD_THEME_VINTAGE_STORAGE_VALUE = "vintage"
private const val LCD_THEME_AMBER_STORAGE_VALUE = "amber"
private const val LCD_THEME_BLUE_STORAGE_VALUE = "blue"

internal data class LcdPalette(val text: Int, val background: Int)

internal enum class LcdTheme(
    val storageValue: String,
    val minimumContrast: Double,
    private val lightTextBase: Int,
    private val lightBackgroundBase: Int,
) {
    HIGH_CONTRAST(
        storageValue = DEFAULT_LCD_THEME_STORAGE_VALUE,
        minimumContrast = 5.0,
        lightTextBase = Color.BLACK,
        lightBackgroundBase = Color.parseColor("#E0E0E0"),
    ),
    VINTAGE(
        storageValue = LCD_THEME_VINTAGE_STORAGE_VALUE,
        minimumContrast = 4.5,
        lightTextBase = 0xFF303030.toInt(),
        lightBackgroundBase = 0xFFDFF5CC.toInt(),
    ),
    AMBER(
        storageValue = LCD_THEME_AMBER_STORAGE_VALUE,
        minimumContrast = 4.5,
        lightTextBase = Color.parseColor("#4A2C00"),
        lightBackgroundBase = Color.parseColor("#F4DEB0"),
    ),
    BLUE(
        storageValue = LCD_THEME_BLUE_STORAGE_VALUE,
        minimumContrast = 4.5,
        lightTextBase = Color.parseColor("#102A43"),
        lightBackgroundBase = Color.parseColor("#DCEEFF"),
    );

    fun lightPalette(): LcdPalette {
        return LcdPalette(text = lightTextBase, background = lightBackgroundBase)
    }

    fun inversePalette(): LcdPalette {
        return LcdPalette(text = lightBackgroundBase, background = lightTextBase)
    }

    companion object {
        val DEFAULT = HIGH_CONTRAST

        fun fromStorageValue(value: String?): LcdTheme {
            return entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        }
    }
}

internal object LcdThemePolicy {
    private const val LIGHT_BACKGROUND_MAX_LUMINANCE = 0.99
    private const val INVERSE_BACKGROUND_MIN_LUMINANCE = 0.02
    private const val INVERSE_BACKGROUND_MAX_LUMINANCE = 0.16
    private const val INVERSE_TEXT_MIN_LUMINANCE = 0.68
    private const val INVERSE_TEXT_MAX_LUMINANCE = 0.94

    fun normalizeStorageValue(value: String?): String {
        return LcdTheme.fromStorageValue(value).storageValue
    }

    fun resolve(storageValue: String, luminancePercent: Int, isNegative: Boolean): LcdPalette {
        val theme = LcdTheme.fromStorageValue(storageValue)
        val normalizedLuminance = luminancePercent.coerceIn(
            MainActivityPreferenceController.MIN_LCD_LUMINANCE,
            MainActivityPreferenceController.MAX_LCD_LUMINANCE,
        )
        return if (isNegative) {
            resolveInversePalette(theme, normalizedLuminance)
        } else {
            resolveLightPalette(theme, normalizedLuminance)
        }
    }

    private fun resolveLightPalette(theme: LcdTheme, luminancePercent: Int): LcdPalette {
        val palette = theme.lightPalette()
        val minimumBackgroundLuminance = minimumBackgroundLuminanceForContrast(
            palette.text,
            theme.minimumContrast,
        )
        val targetBackgroundLuminance = interpolateLuminance(
            luminancePercent,
            minimumBackgroundLuminance,
            LIGHT_BACKGROUND_MAX_LUMINANCE,
        )
        val background = setRelativeLuminance(palette.background, targetBackgroundLuminance)
        val text = ensureTextContrast(palette.text, background, theme.minimumContrast)
        return LcdPalette(text = text, background = background)
    }

    private fun resolveInversePalette(theme: LcdTheme, luminancePercent: Int): LcdPalette {
        val palette = theme.inversePalette()
        val baseTextLuminance = ColorUtils.calculateLuminance(palette.text)
        val minimumTextLuminance = maxOf(INVERSE_TEXT_MIN_LUMINANCE, baseTextLuminance * 0.85)
        val maximumTextLuminance = minOf(INVERSE_TEXT_MAX_LUMINANCE, baseTextLuminance + 0.08)
        val targetTextLuminance = interpolateLuminance(
            luminancePercent,
            minimumTextLuminance,
            maximumTextLuminance.coerceAtLeast(minimumTextLuminance),
        )
        val textBase = setRelativeLuminance(palette.text, targetTextLuminance)
        val maximumBackgroundLuminance = minOf(
            INVERSE_BACKGROUND_MAX_LUMINANCE,
            maximumBackgroundLuminanceForContrast(textBase, theme.minimumContrast),
        ).coerceAtLeast(INVERSE_BACKGROUND_MIN_LUMINANCE)
        val targetBackgroundLuminance = interpolateLuminance(
            luminancePercent,
            INVERSE_BACKGROUND_MIN_LUMINANCE,
            maximumBackgroundLuminance,
        )
        val background = setRelativeLuminance(palette.background, targetBackgroundLuminance)
        val text = ensureTextContrast(textBase, background, theme.minimumContrast)
        return LcdPalette(text = text, background = background)
    }

    private fun interpolateLuminance(
        luminancePercent: Int,
        minimumLuminance: Double,
        maximumLuminance: Double,
    ): Double {
        val minLuminance = MainActivityPreferenceController.MIN_LCD_LUMINANCE.toFloat()
        val maxLuminance = MainActivityPreferenceController.MAX_LCD_LUMINANCE.toFloat()
        val fraction = ((luminancePercent - minLuminance) / (maxLuminance - minLuminance))
            .coerceIn(0f, 1f)
        return minimumLuminance + ((maximumLuminance - minimumLuminance) * fraction)
    }

    private fun setRelativeLuminance(color: Int, targetLuminance: Double): Int {
        val hct = FloatArray(3)
        ColorUtils.colorToM3HCT(color, hct)
        var lowTone = 0f
        var highTone = 100f
        var bestColor = color
        var bestDistance = Double.MAX_VALUE

        repeat(18) {
            val targetTone = (lowTone + highTone) / 2f
            val candidate = ColorUtils.M3HCTToColor(hct[0], hct[1], targetTone)
            val candidateLuminance = ColorUtils.calculateLuminance(candidate)
            val distance = abs(candidateLuminance - targetLuminance)
            if (distance < bestDistance) {
                bestColor = candidate
                bestDistance = distance
            }

            if (candidateLuminance < targetLuminance) {
                lowTone = targetTone
            } else {
                highTone = targetTone
            }
        }

        return bestColor
    }

    private fun minimumBackgroundLuminanceForContrast(textColor: Int, minimumContrast: Double): Double {
        val textLuminance = ColorUtils.calculateLuminance(textColor)
        return ((minimumContrast * (textLuminance + 0.05)) - 0.05)
            .coerceIn(0.0, LIGHT_BACKGROUND_MAX_LUMINANCE)
    }

    private fun maximumBackgroundLuminanceForContrast(textColor: Int, minimumContrast: Double): Double {
        val textLuminance = ColorUtils.calculateLuminance(textColor)
        return (((textLuminance + 0.05) / minimumContrast) - 0.05)
            .coerceIn(0.0, INVERSE_BACKGROUND_MAX_LUMINANCE)
    }

    private fun ensureTextContrast(baseText: Int, background: Int, minContrast: Double): Int {
        val baseContrast = ColorUtils.calculateContrast(baseText, background)
        if (baseContrast >= minContrast) {
            return baseText
        }

        val hct = FloatArray(3)
        ColorUtils.colorToM3HCT(baseText, hct)
        var bestCandidate = baseText
        var bestContrast = baseContrast

        for (step in 1..20) {
            val ratio = step / 20f
            val candidateTones = listOf(
                hct[2] + ((100f - hct[2]) * ratio),
                hct[2] * (1f - ratio),
            )

            for (targetTone in candidateTones) {
                val candidate = ColorUtils.M3HCTToColor(hct[0], hct[1], targetTone.coerceIn(0f, 100f))
                val contrast = ColorUtils.calculateContrast(candidate, background)
                if (contrast > bestContrast) {
                    bestCandidate = candidate
                    bestContrast = contrast
                }
                if (contrast >= minContrast) {
                    return candidate
                }
            }
        }

        val blackContrast = ColorUtils.calculateContrast(Color.BLACK, background)
        if (blackContrast > bestContrast) {
            bestCandidate = Color.BLACK
            bestContrast = blackContrast
        }

        val whiteContrast = ColorUtils.calculateContrast(Color.WHITE, background)
        if (whiteContrast > bestContrast) {
            bestCandidate = Color.WHITE
        }

        return bestCandidate
    }
}
