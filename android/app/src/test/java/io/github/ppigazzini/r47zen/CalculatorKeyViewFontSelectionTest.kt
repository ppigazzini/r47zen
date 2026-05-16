package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Typeface
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalculatorKeyViewFontSelectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fontSet = KeypadFontSet(
        standard = Typeface.SERIF,
        numeric = Typeface.MONOSPACE,
        tiny = Typeface.SANS_SERIF,
    )

    @Test
    fun numericStyleUsesStandardTypefaceWhenLabelIsCovered() {
        val view = createMainKeyView(19)

        view.updateLabels(snapshotFor(19, numericMatrixKeyState(primaryLabel = "7")))

        assertSame(fontSet.standard, view.primaryLabel.typeface)
    }

    @Test
    fun numericStyleFallsBackToStandardTypefaceWhenNumericFontMissesGlyph() {
        val view = createMainKeyView(19)

        view.updateLabels(snapshotFor(19, numericMatrixKeyState(primaryLabel = "⎌")))

        assertSame(fontSet.standard, view.primaryLabel.typeface)
    }

    @Test
    fun defaultStyleKeepsStandardTypefaceWhenLabelIsCovered() {
        val view = createMainKeyView(19)

        view.updateLabels(
            snapshotFor(
                19,
                numericMatrixKeyState(
                    primaryLabel = "7",
                    styleRole = KeypadSceneContract.STYLE_DEFAULT,
                ),
            ),
        )

        assertSame(fontSet.standard, view.primaryLabel.typeface)
    }

    @Test
    fun topAndLetterLabelsUseStandardTypefaceWhenCovered() {
        val view = createMainKeyView(19)

        view.updateLabels(
            snapshotFor(
                19,
                numericMatrixKeyState(
                    primaryLabel = "7",
                    fLabel = "LASTx",
                    gLabel = "STK",
                    letterLabel = "A",
                ),
            ),
        )

        assertSame(fontSet.standard, view.fLabel.typeface)
        assertSame(fontSet.standard, view.gLabel.typeface)
        assertSame(fontSet.standard, view.letterLabel.typeface)
    }

    @Test
    fun topLabelsFallbackToStandardTypefaceWhenNumericFontMissesGlyph() {
        val view = createMainKeyView(19)

        view.updateLabels(
            snapshotFor(
                19,
                numericMatrixKeyState(
                    primaryLabel = "7",
                    fLabel = "⎌",
                    gLabel = "↵",
                ),
            ),
        )

        assertSame(fontSet.standard, view.fLabel.typeface)
        assertSame(fontSet.standard, view.gLabel.typeface)
    }

    @Test
    fun coveredNumericTypefaceDoesNotInflateConfiguredLabelSizes() {
        val standardOnlyView = createMainKeyView(
            code = 19,
            fonts = KeypadFontSet(
                standard = Typeface.SERIF,
                numeric = null,
                tiny = Typeface.SANS_SERIF,
            ),
        )
        standardOnlyView.updateLabels(
            snapshotFor(
                19,
                numericMatrixKeyState(
                    primaryLabel = "7",
                    fLabel = "LASTx",
                    gLabel = "STK",
                    letterLabel = "A",
                ),
            ),
        )
        measureAndLayout(standardOnlyView)

        val numericView = createMainKeyView(19)
        numericView.updateLabels(
            snapshotFor(
                19,
                numericMatrixKeyState(
                    primaryLabel = "7",
                    fLabel = "LASTx",
                    gLabel = "STK",
                    letterLabel = "A",
                ),
            ),
        )
        measureAndLayout(numericView)

        assertTrue(numericView.primaryLabel.textSize <= standardOnlyView.primaryLabel.textSize + 0.01f)
        assertTrue(numericView.fLabel.textSize <= standardOnlyView.fLabel.textSize + 0.01f)
        assertTrue(numericView.gLabel.textSize <= standardOnlyView.gLabel.textSize + 0.01f)
        assertTrue(numericView.letterLabel.textSize <= standardOnlyView.letterLabel.textSize + 0.01f)
    }

    @Test
    fun tinyTypefaceIsUsedWhenStandardTypefaceIsUnavailable() {
        val tinyFallbackFontSet = KeypadFontSet(
            standard = null,
            numeric = Typeface.MONOSPACE,
            tiny = Typeface.SANS_SERIF,
        )
        val view = createMainKeyView(code = 19, fonts = tinyFallbackFontSet)

        view.updateLabels(
            snapshotFor(
                19,
                numericMatrixKeyState(
                    primaryLabel = "7",
                    fLabel = "LASTx",
                    gLabel = "STK",
                    letterLabel = "A",
                ),
            ),
        )

        assertSame(tinyFallbackFontSet.tiny, view.primaryLabel.typeface)
        assertSame(tinyFallbackFontSet.tiny, view.fLabel.typeface)
        assertSame(tinyFallbackFontSet.tiny, view.gLabel.typeface)
        assertSame(tinyFallbackFontSet.tiny, view.letterLabel.typeface)
    }

    private fun createMainKeyView(
        code: Int,
        fonts: KeypadFontSet = fontSet,
    ): CalculatorKeyView {
        return CalculatorKeyView(context).apply {
            setKey(KeypadTopology.slotFor(code), fonts)
            measureAndLayout(this)
        }
    }

    private fun measureAndLayout(view: CalculatorKeyView) {
        view.measure(exactly(MEASURED_WIDTH), exactly(MEASURED_HEIGHT))
        view.layout(0, 0, MEASURED_WIDTH, MEASURED_HEIGHT)
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }

    private fun snapshotFor(code: Int, keyState: KeypadKeySnapshot): KeypadSnapshot {
        val keyStates = MutableList(KeypadSnapshot.KEY_COUNT) { KeypadKeySnapshot.EMPTY }
        keyStates[code - 1] = keyState

        return KeypadSnapshot(
            keyboardState = KeyboardStateSnapshot.EMPTY,
            sceneContractVersion = KeypadSnapshot.SCENE_CONTRACT_VERSION,
            softmenuId = 0,
            softmenuFirstItem = 0,
            softmenuItemCount = 0,
            softmenuVisibleRowOffset = 0,
            softmenuPage = 0,
            softmenuPageCount = 0,
            softmenuHasPreviousPage = false,
            softmenuHasNextPage = false,
            softmenuDottedRow = 0,
            functionPreviewActive = false,
            functionPreviewKeyCode = 0,
            functionPreviewRow = 0,
            functionPreviewState = 0,
            functionPreviewTimeoutActive = false,
            functionPreviewReleaseExec = false,
            functionPreviewNopOrExecuted = false,
            keyStates = keyStates,
        )
    }

    private fun numericMatrixKeyState(
        primaryLabel: String,
        fLabel: String = "LASTx",
        gLabel: String = "STK",
        letterLabel: String = "A",
        styleRole: Int = KeypadSceneContract.STYLE_NUMERIC,
    ): KeypadKeySnapshot {
        return KeypadKeySnapshot.EMPTY.copy(
            primaryLabel = primaryLabel,
            fLabel = fLabel,
            gLabel = gLabel,
            letterLabel = letterLabel,
            styleRole = styleRole,
            isEnabled = true,
        )
    }

    private companion object {
        private const val MEASURED_WIDTH = 331
        private const val MEASURED_HEIGHT = 260
    }
}
