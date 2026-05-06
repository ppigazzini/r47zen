package com.example.r47

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @Test
    fun iteration57_unchangedSnapshotDoesNotResetRenderedKeyState() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyView = createAttachedMainKeyView(activity, 12)
        container.addView(keyView, FrameLayout.LayoutParams(272, 260))
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)

        val snapshot = snapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                isEnabled = true,
            ),
        )

        keyView.updateLabels(snapshot)
        shadowOf(Looper.getMainLooper()).idle()

        keyView.applyTopLabelPlacement(TopLabelLanePlacement(centerShift = 8f))
        shadowOf(Looper.getMainLooper()).idle()

        val baselinePrimaryTranslationX = keyView.primaryLabel.translationX
        val baselineFTranslationX = keyView.fLabel.translationX
        val baselineGTranslationX = keyView.gLabel.translationX

        keyView.updateLabels(snapshot)

        assertEquals(baselinePrimaryTranslationX, keyView.primaryLabel.translationX, 0.001f)
        assertEquals(baselineFTranslationX, keyView.fLabel.translationX, 0.001f)
        assertEquals(baselineGTranslationX, keyView.gLabel.translationX, 0.001f)

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(baselinePrimaryTranslationX, keyView.primaryLabel.translationX, 0.001f)
        assertEquals(baselineFTranslationX, keyView.fLabel.translationX, 0.001f)
        assertEquals(baselineGTranslationX, keyView.gLabel.translationX, 0.001f)
    }

    @Test
    fun iteration57_snapshotRefreshGateSkipsUnchangedSnapshotUntilReset() {
        val gate = KeypadSnapshotRefreshGate(enabled = true)
        val snapshot = snapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                isEnabled = true,
            ),
        )

        assertTrue(gate.shouldApply(snapshot))
        assertFalse(gate.shouldApply(snapshot))

        gate.reset()

        assertTrue(gate.shouldApply(snapshot))
    }

    @Test
    fun iteration57_snapshotRefreshGateAllowsRepeatedSnapshotWhenDisabled() {
        val gate = KeypadSnapshotRefreshGate(enabled = false)
        val snapshot = snapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                isEnabled = true,
            ),
        )

        assertTrue(gate.shouldApply(snapshot))
        assertTrue(gate.shouldApply(snapshot))

        gate.reset()

        assertTrue(gate.shouldApply(snapshot))
    }

    @Test
    fun iteration57_alphaLayoutKeepsPaintedBodyWidthStable() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyView = createAttachedMainKeyView(activity, 12)
        container.addView(keyView, FrameLayout.LayoutParams(272, 260))

        val defaultSnapshot = snapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "XEQ",
                fLabel = "LASTx",
                gLabel = "STK",
                letterLabel = "A",
                layoutClass = KeypadSceneContract.LAYOUT_CLASS_DEFAULT,
                isEnabled = true,
            ),
        )
        val alphaSnapshot = snapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "PROG",
                fLabel = "LASTx",
                gLabel = "STK",
                layoutClass = KeypadSceneContract.LAYOUT_CLASS_ALPHA,
                isEnabled = true,
            ),
        )

        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)

        keyView.updateLabels(defaultSnapshot)
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)
        shadowOf(Looper.getMainLooper()).idle()
        val defaultBodyWidth = requireNotNull(keyView.buildTopLabelLaneInput()).bodyWidth

        keyView.updateLabels(alphaSnapshot)
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)
        shadowOf(Looper.getMainLooper()).idle()
        val alphaBodyWidth = requireNotNull(keyView.buildTopLabelLaneInput()).bodyWidth

        assertEquals(defaultBodyWidth, alphaBodyWidth, 0.001f)
        assertEquals(View.INVISIBLE, keyView.letterLabel.visibility)
    }

    private fun createMainKeyView(code: Int): CalculatorKeyView {
        return CalculatorKeyView(ApplicationProvider.getApplicationContext()).apply {
            setKey(
                KeypadTopology.slotFor(code),
                KeypadFontSet(standard = null, numeric = null, tiny = null),
            )
        }
    }

    private fun createAttachedMainKeyView(activity: Activity, code: Int): CalculatorKeyView {
        return CalculatorKeyView(activity).apply {
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

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}