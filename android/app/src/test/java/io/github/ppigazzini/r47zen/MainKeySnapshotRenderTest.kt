package io.github.ppigazzini.r47zen

import android.app.Activity
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainKeySnapshotRenderTest {
    @Test
    fun faceplateGlyphFixturesPassThroughUnchanged() {
        val bstGlyphFixture = "BST_GLYPH_FIXTURE"
        val sstGlyphFixture = "SST_GLYPH_FIXTURE"
        val bstView = createMainKeyView(2)
        val sstView = createMainKeyView(3)
        val snapshot = keypadSnapshotWith(
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
    fun assignedBlankCarrierStaysVisible() {
        val assignedBlankFixture = "NORM_KEY_00_CAPTION"
        val carrierView = createMainKeyView(10)
        val snapshot = keypadSnapshotWith(
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
    fun unchangedSnapshotDoesNotResetRenderedKeyState() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyView = createAttachedMainKeyView(activity, 12)
        container.addView(keyView, FrameLayout.LayoutParams(272, 260))
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)

        val snapshot = keypadSnapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                isEnabled = true,
            ),
        )

        keyView.updateLabels(snapshot)
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
    fun alphaLayoutKeepsThePaintedBodyWidth() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyView = createAttachedMainKeyView(activity, 12)
        container.addView(keyView, FrameLayout.LayoutParams(272, 260))

        val defaultSnapshot = keypadSnapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "XEQ",
                fLabel = "LASTx",
                gLabel = "STK",
                letterLabel = "A",
                layoutClass = KeypadSceneContract.LAYOUT_CLASS_DEFAULT,
                isEnabled = true,
            ),
        )
        val alphaSnapshot = keypadSnapshotWith(
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
        val defaultBodyWidth = keyView.currentMainKeyBodyWidthForTest()

        keyView.updateLabels(alphaSnapshot)
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)
        shadowOf(Looper.getMainLooper()).idle()
        val alphaBodyWidth = keyView.currentMainKeyBodyWidthForTest()

        assertEquals(defaultBodyWidth, alphaBodyWidth, 0.001f)
        assertEquals(View.INVISIBLE, keyView.letterLabel.visibility)
    }

    @Test
    fun nonContractSnapshotDoesNotOverwriteTheRenderedKey() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        val keyCode = 12
        val keyView = createAttachedMainKeyView(activity, keyCode)
        overlay.addReplicaView(keyView, 0f, 0f, 272f, 260f)

        activity.setContentView(overlay)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        val validSnapshot = keypadSnapshotWith(
            keyCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                letterLabel = "A",
                isEnabled = true,
            ),
        )
        val invalidSnapshot = keypadSnapshotWith(
            keyCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "BROKEN",
                fLabel = "WRONG",
                gLabel = "WRONG",
                letterLabel = "Z",
                isEnabled = true,
            ),
            sceneContractVersion = 0,
        )

        ReplicaKeypadLayout.updateDynamicKeys(overlay, validSnapshot)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        val baselinePrimary = keyView.primaryLabel.text.toString()
        val baselineF = keyView.fLabel.text.toString()
        val baselineG = keyView.gLabel.text.toString()
        val baselineLetter = keyView.letterLabel.text.toString()
        val baselineRenderSpec = requireNotNull(keyView.currentMainKeyRenderSpecForTest())

        ReplicaKeypadLayout.updateDynamicKeys(overlay, invalidSnapshot)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(baselinePrimary, keyView.primaryLabel.text.toString())
        assertEquals(baselineF, keyView.fLabel.text.toString())
        assertEquals(baselineG, keyView.gLabel.text.toString())
        assertEquals(baselineLetter, keyView.letterLabel.text.toString())
        assertEquals(baselineRenderSpec, keyView.currentMainKeyRenderSpecForTest())
    }

    @Test
    fun blankSnapshotClearsVisibleMainKeyLabels() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyView = createAttachedMainKeyView(activity, 12)
        container.addView(keyView, FrameLayout.LayoutParams(331, 260))

        val labeledSnapshot = keypadSnapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                letterLabel = "A",
                styleRole = KeypadSceneContract.STYLE_SHIFT_F,
                layoutClass = KeypadSceneContract.LAYOUT_CLASS_DEFAULT,
                isEnabled = true,
            ),
        )
        val blankSnapshot = keypadSnapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "",
                fLabel = "",
                gLabel = "",
                letterLabel = "",
                styleRole = KeypadSceneContract.STYLE_SHIFT_F,
                layoutClass = KeypadSceneContract.LAYOUT_CLASS_DEFAULT,
                isEnabled = true,
            ),
        )

        container.measure(exactly(500), exactly(320))
        container.layout(0, 0, 500, 320)
        shadowOf(Looper.getMainLooper()).idle()

        keyView.updateLabels(labeledSnapshot)
        container.measure(exactly(500), exactly(320))
        container.layout(0, 0, 500, 320)
        shadowOf(Looper.getMainLooper()).idle()

        keyView.updateLabels(blankSnapshot)
        container.measure(exactly(500), exactly(320))
        container.layout(0, 0, 500, 320)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("", keyView.primaryLabel.text.toString())
        assertEquals(View.INVISIBLE, keyView.fLabel.visibility)
        assertEquals(View.INVISIBLE, keyView.gLabel.visibility)
        assertEquals(View.INVISIBLE, keyView.letterLabel.visibility)
        assertEquals("", keyView.contentDescription.toString())
    }

    @Test
    fun scalingChangeReplaysTheSnapshotAndRestoresStyledLabels() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        val fixture = KeypadFixtureResources.load("static-single-scene")
        val snapshot = fixture.snapshot()
        val keyCode = (1..37).first { code ->
            val state = snapshot.keyStateFor(code)
            state.fLabel.length >= 3 &&
                state.labelRole(KeypadSceneContract.LABEL_F) == KeypadSceneContract.TEXT_ROLE_F_UNDERLINE
        }
        val keyView = createAttachedMainKeyView(activity, keyCode)
        val controller = ReplicaOverlayController(
            context = activity,
            overlay = overlay,
            performHapticClick = { _ -> },
            dispatchLiveKey = {},
            getKeypadSnapshot = { _ -> fixture.snapshot() },
            isRuntimeReady = { true },
        )
        controller.bindOverlay()

        overlay.addReplicaView(keyView, 0f, 0f, 272f, 260f)
        activity.setContentView(overlay)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)

        controller.refreshDynamicKeys(snapshot)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        val expectedText = snapshot.keyStateFor(keyCode).fLabel
        assertEquals(expectedText, keyView.fLabel.text.toString())
        assertTrue(keyView.fLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0)

        keyView.fLabel.text = expectedText.take(2)
        keyView.fLabel.paintFlags = keyView.fLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()

        controller.requestGeometryReplay()
        controller.onHostResumed()

        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(expectedText, keyView.fLabel.text.toString())
        assertTrue(keyView.fLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0)
    }

    @Test
    fun pipExitReplaysTheSnapshotAndRestoresStyledLabels() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        val fixture = KeypadFixtureResources.load("static-single-scene")
        val snapshot = fixture.snapshot()
        val keyCode = (1..37).first { code ->
            val state = snapshot.keyStateFor(code)
            state.fLabel.length >= 3 &&
                state.labelRole(KeypadSceneContract.LABEL_F) == KeypadSceneContract.TEXT_ROLE_F_UNDERLINE
        }
        val keyView = createAttachedMainKeyView(activity, keyCode)
        val controller = ReplicaOverlayController(
            context = activity,
            overlay = overlay,
            performHapticClick = { _ -> },
            dispatchLiveKey = {},
            getKeypadSnapshot = { _ -> fixture.snapshot() },
            isRuntimeReady = { true },
        )
        controller.bindOverlay()

        overlay.addReplicaView(keyView, 0f, 0f, 272f, 260f)
        activity.setContentView(overlay)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)

        controller.refreshDynamicKeys(snapshot)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        val expectedText = snapshot.keyStateFor(keyCode).fLabel
        assertEquals(expectedText, keyView.fLabel.text.toString())
        assertTrue(keyView.fLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0)

        keyView.fLabel.text = expectedText.take(2)
        keyView.fLabel.paintFlags = keyView.fLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()

        controller.handlePictureInPictureModeChanged(true)
        overlay.measure(exactly(486), exactly(267))
        overlay.layout(0, 0, 486, 267)
        shadowOf(Looper.getMainLooper()).idle()

        controller.handlePictureInPictureModeChanged(false)
        controller.onHostResumed()

        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(expectedText, keyView.fLabel.text.toString())
        assertTrue(keyView.fLabel.paintFlags and Paint.UNDERLINE_TEXT_FLAG != 0)
    }

    @Test
    fun matrixModeKeepsTheNumericMatrixFontWithPreviewDisabled() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyCode = 20
        val keyView = createAttachedMainKeyView(activity, keyCode)
        container.addView(keyView, FrameLayout.LayoutParams(331, 260))
        container.measure(exactly(500), exactly(320))
        container.layout(0, 0, 500, 320)
        shadowOf(Looper.getMainLooper()).idle()

        val baselineSnapshot = keypadSnapshotWith(
            keyCode to numericMatrixKeyState(),
        )
        keyView.updateLabels(baselineSnapshot)
        relayoutAfterIdle(container, 500, 320)
        val baselineTextSize = keyView.primaryLabel.textSize
        val baselineLabel = keyView.primaryLabel.text.toString()

        ShiftPreviewState.entries.forEach { shiftState ->
            MainKeyDynamicMode.entries.forEach { mode ->
                val previewEnabled = mode == MainKeyDynamicMode.ON && shiftState != ShiftPreviewState.NONE
                val snapshot = keypadSnapshotWith(
                    keyCode to numericMatrixKeyState(
                        primaryLabel = if (previewEnabled) shiftState.previewLabel else baselineLabel,
                        styleRole = if (previewEnabled) {
                            KeypadSceneContract.STYLE_DEFAULT
                        } else {
                            KeypadSceneContract.STYLE_NUMERIC
                        },
                    ),
                ).copy(
                    keyboardState = keyboardStateFor(shiftState),
                )

                keyView.updateLabels(snapshot)
                relayoutAfterIdle(container, 500, 320)

                val actualTextSize = keyView.primaryLabel.textSize
                val actualLabel = keyView.primaryLabel.text.toString()

                if (previewEnabled) {
                    assertTrue(
                        "${mode.storageValue}/${shiftState.name} should switch away from numeric sizing",
                        actualTextSize < baselineTextSize - 0.01f,
                    )
                    assertEquals(shiftState.previewLabel, actualLabel)
                } else {
                    assertEquals(
                        "${mode.storageValue}/${shiftState.name} should preserve numeric matrix sizing",
                        baselineTextSize,
                        actualTextSize,
                        0.01f,
                    )
                    assertEquals(
                        "${mode.storageValue}/${shiftState.name} should preserve the printed legend",
                        baselineLabel,
                        actualLabel,
                    )
                }
            }
        }
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

    private fun numericMatrixKeyState(
        primaryLabel: String = "7",
        styleRole: Int = KeypadSceneContract.STYLE_NUMERIC,
    ): KeypadKeySnapshot {
        return KeypadKeySnapshot.EMPTY.copy(
            primaryLabel = primaryLabel,
            fLabel = "LASTx",
            gLabel = "STK",
            letterLabel = "A",
            styleRole = styleRole,
            isEnabled = true,
        )
    }

    private fun keyboardStateFor(shiftState: ShiftPreviewState): KeyboardStateSnapshot {
        return when (shiftState) {
            ShiftPreviewState.NONE -> KeyboardStateSnapshot.EMPTY
            ShiftPreviewState.F -> KeyboardStateSnapshot.EMPTY.copy(shiftF = true)
            ShiftPreviewState.G -> KeyboardStateSnapshot.EMPTY.copy(shiftG = true)
        }
    }

    // Runs the work the label update posted, then lays the view out again.
    private fun relayoutAfterIdle(view: View, width: Int, height: Int) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(exactly(width), exactly(height))
        view.layout(0, 0, width, height)
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }

    private enum class ShiftPreviewState(
        val previewLabel: String,
    ) {
        NONE("7"),
        F("LASTx"),
        G("STK"),
    }
}
