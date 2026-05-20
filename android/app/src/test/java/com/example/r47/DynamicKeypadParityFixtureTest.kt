package com.example.r47

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DynamicKeypadParityFixtureTest {
    @Test
    fun iteration36_faceplateGlyphFixturesPassThroughUnchanged() {
        val bstGlyphFixture = "ITER36_BST_GLYPH_FIXTURE"
        val sstGlyphFixture = "ITER36_SST_GLYPH_FIXTURE"
        val bstView = createMainKeyView(2)
        val sstView = createMainKeyView(3)
        val snapshot = snapshotWith(
            2 to KeypadKeySnapshot.EMPTY.copy(
                fLabel = bstGlyphFixture,
                gLabel = "REGS",
                isEnabled = true,
            ),
            3 to KeypadKeySnapshot.EMPTY.copy(
                fLabel = sstGlyphFixture,
                gLabel = "FLGS",
                isEnabled = true,
            ),
        )

        bstView.updateLabels(snapshot)
        sstView.updateLabels(snapshot)

        assertEquals(bstGlyphFixture, bstView.fLabel.text.toString())
        assertEquals("REGS", bstView.gLabel.text.toString())
        assertTrue(bstView.contentDescription.toString().contains(bstGlyphFixture))

        assertEquals(sstGlyphFixture, sstView.fLabel.text.toString())
        assertEquals("FLGS", sstView.gLabel.text.toString())
        assertTrue(sstView.contentDescription.toString().contains(sstGlyphFixture))
    }

    @Test
    fun iteration37_assignedBlankCarrierFixtureRemainsVisible() {
        val assignedBlankFixture = "ITER37_NORM_KEY_00_CAPTION"
        val carrierView = createMainKeyView(10)
        val snapshot = snapshotWith(
            10 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = assignedBlankFixture,
                styleRole = KeypadSceneContract.STYLE_ALPHA,
                isEnabled = true,
            ),
        )

        carrierView.updateLabels(snapshot)

        assertEquals(assignedBlankFixture, carrierView.primaryLabel.text.toString())
        assertEquals(View.VISIBLE, carrierView.primaryLabel.visibility)
        assertEquals(assignedBlankFixture, carrierView.contentDescription.toString())
    }

    private fun createMainKeyView(code: Int): CalculatorKeyView {
        return CalculatorKeyView(ApplicationProvider.getApplicationContext()).apply {
            setKey(
                KeypadTopology.slotFor(code),
                KeypadFontSet(standard = null, numeric = null, tiny = null),
            )
        }
    }

    private fun snapshotWith(vararg states: Pair<Int, KeypadKeySnapshot>): KeypadSnapshot {
        val keyStates = MutableList(43) { KeypadKeySnapshot.EMPTY }
        states.forEach { (code, keyState) ->
            keyStates[code - 1] = keyState
        }

        return KeypadSnapshot(
            keyboardState = KeyboardStateSnapshot.EMPTY,
            sceneContractVersion = 5,
            softmenuId = 0,
            softmenuFirstItem = 0,
            softmenuItemCount = 0,
            softmenuVisibleRowOffset = 0,
            softmenuPage = 0,
            softmenuPageCount = 0,
            softmenuHasPreviousPage = false,
            softmenuHasNextPage = false,
            softmenuDottedRow = -1,
            functionPreviewActive = false,
            functionPreviewKeyCode = 0,
            functionPreviewRow = -1,
            functionPreviewState = 0,
            functionPreviewTimeoutActive = false,
            functionPreviewReleaseExec = false,
            functionPreviewNopOrExecuted = false,
            keyStates = keyStates,
        )
    }
}