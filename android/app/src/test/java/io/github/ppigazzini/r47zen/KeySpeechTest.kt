package io.github.ppigazzini.r47zen

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A screen reader names every key in words: no label of any exported keypad
// scene leaves a character a speech engine would read by its code point, every key a
// scene enables with labels has a name, every key is a button, and Zen keeps the names.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class KeySpeechTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun everyExportedLabel_isSpokenInWordsAlone() {
        val unspoken = KeypadFixtureResources.loadAll()
            .flatMap { fixture -> fixture.labels.filter(String::isNotBlank).map { fixture.name to it } }
            .filter { (_, label) -> !KeySpeech.spoken(label).isPrintableAscii() }

        assertTrue("labels with a character no word names: $unspoken", unspoken.isEmpty())
    }

    @Test
    fun theR47GlyphsSpeakTheirFunction() {
        val expected = mapOf(
            "xⅢ" to "x squared",
            "√Ϳ" to "square root of x",
            "∜Ȳ" to "x-th root of y",
            "10ˣ" to "10 to the x",
            "x⇄y" to "x swap y",
            "→R" to "to R",
            "R↑" to "R up",
            "⇌⭡" to "back step",
            "·_·" to "space",
            "←" to "backspace",
            "STO" to "STO",
        )
        for ((label, words) in expected) {
            assertEquals(label, words, KeySpeech.spoken(label))
        }
    }

    @Test
    fun everyRenderedKey_isNamedInWords_andIsAButton() {
        for (fixture in KeypadFixtureResources.loadAll()) {
            val snapshot = fixture.snapshot()
            for (key in keysOf(snapshot)) {
                val state = snapshot.keyStateFor(key.keyCode)
                val description = key.contentDescription?.toString().orEmpty()
                val labeled = listOf(state.primaryLabel, state.fLabel, state.gLabel).any(String::isNotBlank)
                val where = "${fixture.name} key ${key.keyCode}: '$description'"

                assertTrue(where, description.isPrintableAscii())
                assertTrue(where, !(state.isEnabled && labeled) || description.isNotBlank())
                assertEquals(where, android.widget.Button::class.java.name, key.accessibilityClassName)
            }
        }
    }

    @Test
    fun zenHidesTheLabels_butKeepsEveryKeysName() {
        for (fixture in KeypadFixtureResources.loadAll()) {
            val shown = fixture.snapshot()
            val zen = shown.applyMainKeyDynamicMode(MainKeyDynamicMode.VIRTUOSO)
            for (code in 1..MAIN_KEY_COUNT) {
                val before = shown.keyStateFor(code)
                if (listOf(before.primaryLabel, before.fLabel, before.gLabel).all(String::isBlank)) continue
                val after = zen.keyStateFor(code)

                assertEquals("", after.primaryLabel)
                assertEquals(
                    "${fixture.name} key $code",
                    KeySpeech.mainKey(before.primaryLabel, before.fLabel, before.gLabel),
                    after.spokenLabel,
                )
            }
        }
    }

    private fun keysOf(snapshot: KeypadSnapshot): List<CalculatorKeyView> {
        val overlay = ReplicaOverlay(context)
        ReplicaKeypadLayout.rebuild(
            context = context,
            overlay = overlay,
            performHapticClick = {},
            dispatchKey = {},
            initialSnapshotProvider = { snapshot },
        )
        overlay.measure(exactly(WIDTH), exactly(HEIGHT))
        overlay.layout(0, 0, WIDTH, HEIGHT)
        return (0 until overlay.childCount).mapNotNull { overlay.getChildAt(it) as? CalculatorKeyView }
    }

    private fun String.isPrintableAscii() = all { it.code in 0x20..0x7E }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val MAIN_KEY_COUNT = 37
        const val WIDTH = 1080
        const val HEIGHT = 2160
    }
}
