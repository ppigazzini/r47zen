package io.github.ppigazzini.r47zen

import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.math.pow

// Android's core app quality bar asks 4.5:1 of small text, and a softkey's text is
// small: its value under 6 dp on a phone. Each text color a softkey draws is held
// to every fill it is drawn on, by WCAG 2's contrast ratio.
@RunWith(RobolectricTestRunner::class)
class SoftkeyTextContrastTest {
    private companion object {
        const val SMALL_TEXT_MINIMUM = 4.5
    }

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))
    }

    private fun contrast(text: Int, fill: Int): Double {
        val (light, dark) = listOf(luminance(text), luminance(fill)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun everySoftkeyTextColor_meetsTheSmallTextBarOnEachFillItIsDrawnOn() {
        val keyView = CalculatorKeyView
        val pairs = mapOf(
            "primary on a normal softkey" to (keyView.defaultPrimaryColor to keyView.mainKeyFillColor),
            "primary on a pressed softkey" to (keyView.defaultPrimaryColor to keyView.mainKeyPressedColor),
            "primary on a reverse softkey" to (keyView.softkeyLightTextColor to keyView.softkeyReverseColor),
            "value on a normal softkey" to (keyView.softkeyValueTextColor to keyView.mainKeyFillColor),
            "value on a pressed softkey" to (keyView.softkeyValueTextColor to keyView.mainKeyPressedColor),
            "value on a reverse softkey" to (keyView.softkeyValueTextColor to keyView.softkeyReverseColor),
        )

        pairs.forEach { (what, colors) ->
            val ratio = contrast(colors.first, colors.second)
            assertTrue("$what is %.2f:1".format(Locale.ROOT, ratio), ratio >= SMALL_TEXT_MINIMUM)
        }
    }
}
