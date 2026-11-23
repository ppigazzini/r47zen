package io.github.ppigazzini.r47zen

import android.graphics.Color
import kotlin.math.pow

// WCAG 2's contrast ratio between two opaque colors, and the bars it sets:
// 4.5:1 for small text (1.4.3), 3:1 for a graphic that carries meaning
// (1.4.11).
internal object WcagContrast {
    const val SMALL_TEXT_MINIMUM = 4.5
    const val NON_TEXT_MINIMUM = 3.0

    fun ratio(first: Int, second: Int): Double {
        val (light, dark) = listOf(luminance(first), luminance(second)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun luminance(color: Int): Double {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
