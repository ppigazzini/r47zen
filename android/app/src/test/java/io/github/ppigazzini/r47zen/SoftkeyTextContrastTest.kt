package io.github.ppigazzini.r47zen

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

// Android's core app quality bar asks 4.5:1 of small text, and a softkey's text is
// small: its value under 6 dp on a phone. Each text color a softkey draws is held
// to every fill it is drawn on, by WCAG 2's contrast ratio.
@RunWith(RobolectricTestRunner::class)
class SoftkeyTextContrastTest {
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
            val ratio = WcagContrast.ratio(colors.first, colors.second)
            assertTrue("$what is %.2f:1".format(Locale.ROOT, ratio), ratio >= WcagContrast.SMALL_TEXT_MINIMUM)
        }
    }
}
