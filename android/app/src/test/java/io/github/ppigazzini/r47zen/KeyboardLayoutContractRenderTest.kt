package io.github.ppigazzini.r47zen

import android.app.Activity
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyboardLayoutContractRenderTest {
    @Test
    fun sharedKeyboardLayoutContractMatchesRepresentativeFixtureSnapshots() {
        val contract = KeyboardLayoutContractResources.contract()

        contract.fixtureExpectations.forEach { (scenarioName, keyExpectations) ->
            val snapshot = KeypadFixtureResources.load(scenarioName).snapshot()
            keyExpectations.forEach { (keyCode, expected) ->
                assertKeyState(snapshot.keyStateFor(keyCode), expected)
            }
        }
    }

    @Test
    fun calculatorKeyViewRendersRepresentativeKeyboardContractStates() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val contract = KeyboardLayoutContractResources.contract()
        val renderedScenarios = listOf("default-keypad", "shift-f-preview", "shift-g-preview")

        renderedScenarios.forEach { scenarioName ->
            val snapshot = KeypadFixtureResources.load(scenarioName).snapshot()
            contract.fixtureExpectations.getValue(scenarioName).forEach { (keyCode, expected) ->
                val view = createMainKeyView(activity, keyCode)
                view.updateLabels(snapshot)
                measureAndLayout(view)

                assertEquals(expected.primaryLabel, view.primaryLabel.text.toString())
                assertEquals(expected.fLabel, view.fLabel.text.toString())
                assertEquals(expected.gLabel, view.gLabel.text.toString())
                assertEquals(expected.letterLabel, view.letterLabel.text.toString())
                assertEquals(
                    expectedContentDescription(expected, snapshot.keyStateFor(keyCode)),
                    view.contentDescription.toString(),
                )
            }
        }
    }

    @Test
    fun sharedKeyboardLayoutContractKeepsBlankAndStaticSingleRulesVisible() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val contract = KeyboardLayoutContractResources.contract()
        val view = createMainKeyView(activity, 37)
        view.updateLabels(snapshot)
        measureAndLayout(view)

        val expected = contract.fixtureExpectations.getValue("static-single-scene").getValue(37)
        assertEquals(expected.letterLabel, view.letterLabel.text.toString())
        assertEquals(expected.gLabel, view.gLabel.text.toString())
        assertEquals(View.VISIBLE, view.letterLabel.visibility)
    }

    private fun assertKeyState(actual: KeypadKeySnapshot, expected: KeyboardLayoutKeyExpectation) {
        assertEquals(expected.primaryLabel, actual.primaryLabel)
        assertEquals(expected.fLabel, actual.fLabel)
        assertEquals(expected.gLabel, actual.gLabel)
        assertEquals(expected.letterLabel, actual.letterLabel)
        assertEquals(expected.auxLabel, actual.auxLabel)
    }

    // A label the scene marks as opening a menu is named a menu, read from the
    // roles here rather than through the code under test.
    private fun expectedContentDescription(
        expected: KeyboardLayoutKeyExpectation,
        keyState: KeypadKeySnapshot,
    ): String {
        val fMenu = keyState.labelRole(KeypadSceneContract.LABEL_F) == KeypadSceneContract.TEXT_ROLE_F_UNDERLINE
        val gMenu = keyState.labelRole(KeypadSceneContract.LABEL_G) == KeypadSceneContract.TEXT_ROLE_G_UNDERLINE
        return buildString {
            append(expected.primaryLabel)
            if (expected.fLabel.isNotBlank()) {
                append(", f ")
                append(expected.fLabel)
                if (fMenu) append(" menu")
            }
            if (expected.fLabel.isNotBlank() && expected.gLabel.isNotBlank()) {
                append(", g ")
                append(expected.gLabel)
                if (gMenu) append(" menu")
            }
        }
    }

    private fun createMainKeyView(activity: Activity, code: Int): CalculatorKeyView {
        return CalculatorKeyView(activity).apply {
            setKey(
                KeypadTopology.slotFor(code),
                KeypadFontSet(standard = null, numeric = null, tiny = null),
            )
        }
    }

    private fun measureAndLayout(view: CalculatorKeyView) {
        view.measure(exactly(331), exactly(260))
        view.layout(0, 0, 331, 260)
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}
