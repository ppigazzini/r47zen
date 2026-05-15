package io.github.ppigazzini.r47zen

import android.app.Activity
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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
import kotlin.math.abs

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
    fun iteration57_defaultSnapshotRefreshGateSkipsUnchangedSnapshot() {
        val gate = KeypadSnapshotRefreshGate()
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
    fun iteration66_nonContractSnapshotDoesNotOverwriteRenderedKey() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        val keyCode = 12
        val keyView = createAttachedMainKeyView(activity, keyCode)
        overlay.addReplicaView(keyView, 0f, 0f, 272f, 260f)

        activity.setContentView(overlay)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        val validSnapshot = snapshotWith(
            keyCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                letterLabel = "A",
                isEnabled = true,
            ),
        )
        val invalidSnapshot = snapshotWith(
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

        val baselinePrimary = keyView.primaryLabel.text.toString()
        val baselineF = keyView.fLabel.text.toString()
        val baselineG = keyView.gLabel.text.toString()
        val baselineLetter = keyView.letterLabel.text.toString()
        val baselineFTranslationY = keyView.fLabel.translationY
        val baselineGTranslationY = keyView.gLabel.translationY

        ReplicaKeypadLayout.updateDynamicKeys(overlay, invalidSnapshot)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(baselinePrimary, keyView.primaryLabel.text.toString())
        assertEquals(baselineF, keyView.fLabel.text.toString())
        assertEquals(baselineG, keyView.gLabel.text.toString())
        assertEquals(baselineLetter, keyView.letterLabel.text.toString())
        assertEquals(baselineFTranslationY, keyView.fLabel.translationY, 0.001f)
        assertEquals(baselineGTranslationY, keyView.gLabel.translationY, 0.001f)
    }

    @Test
    fun iteration88_blankSnapshotClearsVisibleMainKeyLabels() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyView = createAttachedMainKeyView(activity, 12)
        container.addView(keyView, FrameLayout.LayoutParams(331, 260))

        val labeledSnapshot = snapshotWith(
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
        val blankSnapshot = snapshotWith(
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
    fun iteration66_rowPlacementComputesHorizontalFgShiftPerLane() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        overlay.onGeometryLaidOut = {
            ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay)
        }

        val laneCodes = KeypadTopology.slotsForLane(KeypadLane.MATRIX_ROW_1)
            .filter { !it.isFunctionKey }
            .map { it.code }
        val leftCode = laneCodes[1]
        val rightCode = laneCodes[2]
        val snapshot = snapshotWith(
            leftCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "VERY_LONG_LEFT_FUNCTION",
                gLabel = "VERY_LONG_LEFT_SECONDARY",
                isEnabled = true,
            ),
            rightCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "8",
                fLabel = "VERY_LONG_RIGHT_FUNCTION",
                gLabel = "VERY_LONG_RIGHT_SECONDARY",
                isEnabled = true,
            ),
        )

        val leftView = createAttachedMainKeyView(activity, leftCode)
        val rightView = createAttachedMainKeyView(activity, rightCode)
        overlay.addReplicaView(leftView, 0f, 0f, 272f, 260f)
        overlay.addReplicaView(rightView, 120f, 0f, 272f, 260f)

        ReplicaKeypadLayout.updateDynamicKeys(overlay, snapshot)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(TopLabelLanePlacement.DEFAULT, leftView.currentTopLabelPlacementForTest())
        assertEquals(TopLabelLanePlacement.DEFAULT, rightView.currentTopLabelPlacementForTest())

        activity.setContentView(overlay)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(leftView.hasMeasuredTopLabelAnchors())
        assertTrue(rightView.hasMeasuredTopLabelAnchors())
        val leftBounds = requireNotNull(leftView.currentMainKeyBodyHorizontalBounds())
        val rightBounds = requireNotNull(rightView.currentMainKeyBodyHorizontalBounds())
        val expectedPlacements = TopLabelLaneLayout.solve(
            listOf(
                requireNotNull(leftView.buildTopLabelLaneInput()),
                requireNotNull(rightView.buildTopLabelLaneInput()),
            ),
        )
        assertEquals(expectedPlacements.getValue(leftCode), leftView.currentTopLabelPlacementForTest())
        assertEquals(expectedPlacements.getValue(rightCode), rightView.currentTopLabelPlacementForTest())
    }

    @Test
    fun iteration66_outerGroupsStayInsideSmartphoneScreenEdges() {
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 100f,
            bodyWidth = 120f,
            fTextWidth = 105f,
            gTextWidth = 105f,
            gapWidth = 10f,
            maxShift = 18f,
            minLeftEdge = 0f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 420f,
            bodyWidth = 120f,
            fTextWidth = 105f,
            gTextWidth = 105f,
            gapWidth = 10f,
            maxShift = 18f,
            maxRightEdge = 520f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))

        assertGapAtLeast(left, right, placements)
        assertNeighborBorderGapRespected(left, right, placements)
        assertScreenBoundsRespected(left, placements.getValue(left.code))
        assertScreenBoundsRespected(right, placements.getValue(right.code))
        assertTrue(placements.getValue(left.code).centerShift > 0f)
        assertTrue(placements.getValue(right.code).centerShift < 0f)
    }

    @Test
    fun iteration66_rowPlacementKeepsOuterLabelsInsideOverlayScreenEdges() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        overlay.onGeometryLaidOut = {
            ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay)
        }

        val laneCodes = KeypadTopology.slotsForLane(KeypadLane.SMALL_ROW_1)
            .filter { !it.isFunctionKey }
            .map { it.code }
        val leftCode = laneCodes.first()
        val rightCode = laneCodes.last()
        val snapshot = snapshotWith(
            leftCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "1",
                fLabel = "WWWWWWWWWWWWWWWWWWWWWWWW",
                gLabel = "WWWWWWWWWWWWWWWWWWWWWWWW",
                isEnabled = true,
            ),
            rightCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "6",
                fLabel = "WWWWWWWWWWWWWWWWWWWWWWWW",
                gLabel = "WWWWWWWWWWWWWWWWWWWWWWWW",
                isEnabled = true,
            ),
        )

        val leftView = createAttachedMainKeyView(activity, leftCode)
        val rightView = createAttachedMainKeyView(activity, rightCode)
        overlay.addReplicaView(leftView, -40f, 0f, 272f, 260f)
        overlay.addReplicaView(rightView, 520f, 0f, 272f, 260f)

        ReplicaKeypadLayout.updateDynamicKeys(overlay, snapshot)
        shadowOf(Looper.getMainLooper()).idle()

        activity.setContentView(overlay)
        overlay.measure(exactly(792), exactly(2400))
        overlay.layout(0, 0, 792, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        val leftInput = requireNotNull(leftView.buildTopLabelLaneInput(minLeftEdge = 0f))
        val rightInput = requireNotNull(rightView.buildTopLabelLaneInput(maxRightEdge = overlay.width.toFloat()))
        val expectedPlacements = TopLabelLaneLayout.solve(listOf(leftInput, rightInput))

        assertEquals(expectedPlacements.getValue(leftCode), leftView.currentTopLabelPlacementForTest())
        assertEquals(expectedPlacements.getValue(rightCode), rightView.currentTopLabelPlacementForTest())
        assertScreenBoundsRespected(leftInput, leftView.currentTopLabelPlacementForTest())
        assertScreenBoundsRespected(rightInput, rightView.currentTopLabelPlacementForTest())
    }

    @Test
    fun iteration66_rowPlacementKeepsInterGroupGapAtLeastTwiceIntraGap() {
        val intraGap = 12f
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 100f,
            bodyWidth = 120f,
            fTextWidth = 40f,
            gTextWidth = 40f,
            gapWidth = intraGap,
            maxShift = 12f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 212f,
            bodyWidth = 120f,
            fTextWidth = 40f,
            gTextWidth = 40f,
            gapWidth = intraGap,
            maxShift = 12f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))
        val solvedLeft = solvedGroupBounds(left, placements.getValue(left.code))
        val solvedRight = solvedGroupBounds(right, placements.getValue(right.code))
        val interGroupGap = solvedRight.first - solvedLeft.second

        assertTrue(interGroupGap >= intraGap * 2f - 0.001f)
        assertNeighborBorderGapRespected(left, right, placements)
    }

    @Test
    fun iteration66_centeredGroupsCanUseFiveGapNeighborCorridorWhenInterGapIsRespected() {
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 100f,
            bodyWidth = 240f,
            fTextWidth = 95f,
            gTextWidth = 95f,
            gapWidth = 10f,
            maxShift = 36f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 300f,
            bodyWidth = 260f,
            fTextWidth = 75f,
            gTextWidth = 75f,
            gapWidth = 10f,
            maxShift = 39f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))

        assertEquals(0f, placements.getValue(left.code).centerShift, 0.001f)
        assertEquals(0f, placements.getValue(right.code).centerShift, 0.001f)
        assertGapAtLeast(left, right, placements)
        assertNeighborBorderGapRespected(left, right, placements)
    }

    @Test
    fun iteration66_rowPlacementScalesMostOffendingLabelBeforeExceedingShiftBudget() {
        val intraGap = 10f
        val maxShift = 28.8f
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 231.5f,
            bodyWidth = 192f,
            fTextWidth = 130f,
            gTextWidth = 168f,
            gapWidth = intraGap,
            maxShift = maxShift,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 503.5f,
            bodyWidth = 192f,
            fTextWidth = 130f,
            gTextWidth = 178f,
            gapWidth = intraGap,
            maxShift = maxShift,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))
        val leftPlacement = placements.getValue(left.code)
        val rightPlacement = placements.getValue(right.code)
        val solvedLeft = solvedGroupBounds(left, leftPlacement)
        val solvedRight = solvedGroupBounds(right, rightPlacement)
        val interGroupGap = solvedRight.first - solvedLeft.second

        assertEquals(1f, leftPlacement.fScale, 0.001f)
        assertEquals(1f, leftPlacement.gScale, 0.001f)
        assertEquals(1f, rightPlacement.fScale, 0.001f)
        assertEquals(
            1f - R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP,
            rightPlacement.gScale,
            0.001f,
        )
        assertTrue(abs(leftPlacement.centerShift) <= maxShift + 0.001f)
        assertTrue(abs(rightPlacement.centerShift) <= maxShift + 0.001f)
        assertTrue(interGroupGap >= intraGap * 2f - 0.001f)
        assertNeighborBorderGapRespected(left, right, placements)
    }

    @Test
    fun iteration66_rowPlacementOnlyMovesGroupThatBreaksNeighborBorderLimit() {
        val intraGap = 10f
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 100f,
            bodyWidth = 240f,
            fTextWidth = 120f,
            gTextWidth = 120f,
            gapWidth = intraGap,
            maxShift = 36f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 300f,
            bodyWidth = 260f,
            fTextWidth = 40f,
            gTextWidth = 20f,
            gapWidth = intraGap,
            maxShift = 39f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))
        val leftPlacement = placements.getValue(left.code)
        val rightPlacement = placements.getValue(right.code)

        assertTrue(abs(leftPlacement.centerShift) > 0.001f)
        assertEquals(0f, rightPlacement.centerShift, 0.001f)
        assertEquals(1f, leftPlacement.fScale, 0.001f)
        assertEquals(1f, leftPlacement.gScale, 0.001f)
        assertEquals(1f, rightPlacement.fScale, 0.001f)
        assertEquals(1f, rightPlacement.gScale, 0.001f)
        assertNeighborBorderGapRespected(left, right, placements)
    }

    @Test
    fun iteration66_rowPlacementEnforcesNeighborCorridorWithFiveGapExtension() {
        val intraGap = 10f
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 100f,
            bodyWidth = 240f,
            fTextWidth = 120f,
            gTextWidth = 120f,
            gapWidth = intraGap,
            maxShift = 36f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 300f,
            bodyWidth = 260f,
            fTextWidth = 40f,
            gTextWidth = 20f,
            gapWidth = intraGap,
            maxShift = 39f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))

        assertNeighborBorderGapRespected(left, right, placements)
        assertTrue(placements.getValue(left.code).centerShift < 0f)
        assertEquals(0f, placements.getValue(right.code).centerShift, 0.001f)
    }

    @Test
    fun iteration66_denseSixGroupRowAvoidsOverlap() {
        val intraGap = 10f
        val widths = listOf(377.09775f, 403.6195f, 420.34708f, 315.7074f, 302.4294f, 364.1773f)
        val groups = widths.mapIndexed { index, totalWidth ->
            val textWidth = totalWidth - intraGap
            val fTextWidth = textWidth / 2f
            TopLabelLaneGroupInput(
                code = index + 1,
                centerX = 231.5f + 272f * index,
                bodyWidth = 192f,
                fTextWidth = fTextWidth,
                gTextWidth = textWidth - fTextWidth,
                gapWidth = intraGap,
                maxShift = 28.8f,
            )
        }

        val placements = TopLabelLaneLayout.solve(groups)

        assertLaneResolved(groups, placements)
        assertTrue(groups.indices.any { index -> abs(placements.getValue(groups[index].code).centerShift) > 0.001f })
        assertGroupScaleDifferenceAtMostOneStep(groups, placements)
    }

    @Test
    fun iteration66_denseFiveGroupRowAvoidsOverlap() {
        val intraGap = 10f
        val widths = listOf(355.5047f, 369.6998f, 490.82352f, 377.73904f, 322.91824f)
        val centers = listOf(231.5f, 639.0f, 911.0f, 1183.0f, 1455.0f)
        val bodyWidths = listOf(462f, 192f, 192f, 192f, 192f)
        val maxShifts = bodyWidths.map { it * 0.15f }
        val groups = widths.mapIndexed { index, totalWidth ->
            val textWidth = totalWidth - intraGap
            val fTextWidth = textWidth / 2f
            TopLabelLaneGroupInput(
                code = index + 13,
                centerX = centers[index],
                bodyWidth = bodyWidths[index],
                fTextWidth = fTextWidth,
                gTextWidth = textWidth - fTextWidth,
                gapWidth = intraGap,
                maxShift = maxShifts[index],
            )
        }

        val placements = TopLabelLaneLayout.solve(groups)

        assertLaneResolved(groups, placements)
        assertTrue(groups.indices.any { index -> abs(placements.getValue(groups[index].code).centerShift) > 0.001f })
        assertGroupScaleDifferenceAtMostOneStep(groups, placements)
    }

    @Test
    fun iteration66_scaledLocalOffenderDoesNotDragDistantGroupsOffCenter() {
        val intraGap = 10f
        val widths = listOf(288.11f, 172.8f, 214.38f, 240.01f, 317.95f, 221.12f)
        val groups = widths.mapIndexed { index, totalWidth ->
            val textWidth = totalWidth - intraGap
            val fTextWidth = textWidth / 2f
            TopLabelLaneGroupInput(
                code = index + 1,
                centerX = 231.5f + 272f * index,
                bodyWidth = 192f,
                fTextWidth = fTextWidth,
                gTextWidth = textWidth - fTextWidth,
                gapWidth = intraGap,
                maxShift = 28.8f,
            )
        }

        val placements = TopLabelLaneLayout.solve(groups)

        for (index in listOf(0, 1, 2, 3, 5)) {
            assertEquals(0f, placements.getValue(groups[index].code).centerShift, 0.001f)
        }

        val offenderPlacement = placements.getValue(groups[4].code)
        assertTrue(abs(offenderPlacement.centerShift) > 0.001f)
        assertTrue(offenderPlacement.fScale < 1f || offenderPlacement.gScale < 1f)

        for (index in 0 until groups.lastIndex) {
            assertGapAtLeast(groups[index], groups[index + 1], placements)
            assertNeighborBorderGapRespected(groups[index], groups[index + 1], placements)
        }
    }

    @Test
    fun iteration66_rowPlacementUsesWholeRowTranslationBeforeScaling() {
        val intraGap = 10f
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 231.5f,
            bodyWidth = 192f,
            fTextWidth = 145f,
            gTextWidth = 145f,
            gapWidth = intraGap,
            maxShift = 28.8f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 503.5f,
            bodyWidth = 192f,
            fTextWidth = 145f,
            gTextWidth = 145f,
            gapWidth = intraGap,
            maxShift = 28.8f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))

        assertTrue(abs(placements.getValue(left.code).centerShift) > 0.001f)
        assertTrue(abs(placements.getValue(right.code).centerShift) > 0.001f)
        assertEquals(1f, placements.getValue(left.code).fScale, 0.001f)
        assertEquals(1f, placements.getValue(left.code).gScale, 0.001f)
        assertEquals(1f, placements.getValue(right.code).fScale, 0.001f)
        assertEquals(1f, placements.getValue(right.code).gScale, 0.001f)
        assertNeighborBorderGapRespected(left, right, placements)
    }

    @Test
    fun iteration66_rowPlacementScalesOtherLabelAfterFirstScaleStep() {
        val intraGap = 10f
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 231.5f,
            bodyWidth = 192f,
            fTextWidth = 140f,
            gTextWidth = 158f,
            gapWidth = intraGap,
            maxShift = 28.8f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 503.5f,
            bodyWidth = 192f,
            fTextWidth = 140f,
            gTextWidth = 172f,
            gapWidth = intraGap,
            maxShift = 28.8f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))

        assertEquals(1f, placements.getValue(left.code).fScale, 0.001f)
        assertEquals(1f, placements.getValue(left.code).gScale, 0.001f)
        assertEquals(
            1f - R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP,
            placements.getValue(right.code).fScale,
            0.001f,
        )
        assertEquals(
            1f - R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP,
            placements.getValue(right.code).gScale,
            0.001f,
        )
        assertNeighborBorderGapRespected(left, right, placements)
    }

    @Test
    fun iteration66_fullyScaledGroupCanForceCollidingNeighborToScale() {
        val intraGap = 10f
        val widths = listOf(377.47998046875f, 283.8518371582031f, 326.15771484375f)
        val groups = widths.mapIndexed { index, totalWidth ->
            val textWidth = totalWidth - intraGap
            val fTextWidth = textWidth / 2f
            TopLabelLaneGroupInput(
                code = index + 1,
                centerX = 231.5f + 272f * index,
                bodyWidth = 192f,
                fTextWidth = fTextWidth,
                gTextWidth = textWidth - fTextWidth,
                gapWidth = intraGap,
                maxShift = 28.8f,
            )
        }

        val placements = TopLabelLaneLayout.solve(groups)
        val scaledIndices = groups.indices.filter { index ->
            val placement = placements.getValue(groups[index].code)
            placement.fScale < 1f || placement.gScale < 1f
        }
        val fullyScaledIndices = groups.indices.filter { index ->
            val placement = placements.getValue(groups[index].code)
            abs(placement.fScale - R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE) <= 0.001f &&
                abs(placement.gScale - R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE) <= 0.001f
        }

        assertTrue(scaledIndices.size >= 2)
        assertTrue(fullyScaledIndices.isNotEmpty())
        assertTrue(
            fullyScaledIndices.any { index ->
                listOf(index - 1, index + 1).any { neighborIndex ->
                    neighborIndex in groups.indices && neighborIndex in scaledIndices
                }
            },
        )
        assertTrue(scaledIndices.any { index -> abs(placements.getValue(groups[index].code).centerShift) > 0.001f })

        for (index in 0 until groups.lastIndex) {
            assertGapAtLeast(groups[index], groups[index + 1], placements)
            assertNeighborBorderGapRespected(groups[index], groups[index + 1], placements)
        }
        assertGroupScaleDifferenceAtMostOneStep(groups, placements)
    }

    @Test
    fun iteration66_pathologicalCaseTriesTranslationBeforeExtraScaledown() {
        val intraGap = 10f
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 231.5f,
            bodyWidth = 192f,
            fTextWidth = 160f,
            gTextWidth = 210f,
            gapWidth = intraGap,
            maxShift = 28.8f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 503.5f,
            bodyWidth = 192f,
            fTextWidth = 220f,
            gTextWidth = 280f,
            gapWidth = intraGap,
            maxShift = 28.8f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))
        val leftPlacement = placements.getValue(left.code)
        val rightPlacement = placements.getValue(right.code)
        val solvedLeft = solvedGroupBounds(left, placements.getValue(left.code))
        val solvedRight = solvedGroupBounds(right, placements.getValue(right.code))
        val interGroupGap = solvedRight.first - solvedLeft.second

        assertTrue(interGroupGap >= intraGap * 2f - 0.001f)
        assertGroupScaleDifferenceAtMostOneStep(listOf(left, right), placements)
        assertTrue(
            abs(leftPlacement.centerShift) > left.maxShift + 0.001f ||
                abs(rightPlacement.centerShift) > right.maxShift + 0.001f ||
                leftPlacement.fScale < R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE - 0.001f ||
                leftPlacement.gScale < R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE - 0.001f ||
                rightPlacement.fScale < R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE - 0.001f ||
                rightPlacement.gScale < R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE - 0.001f,
        )
        assertNeighborBorderGapRespected(left, right, placements)
    }

    @Test
    fun iteration66_denseRowGroupScalesNeverDifferByMoreThanOneStep() {
        val intraGap = 10f
        val widths = listOf(484.09457f, 409.71756f, 497.75793f, 364.88055f, 362.02008f, 511.268f)
        val groups = widths.mapIndexed { index, totalWidth ->
            val textWidth = totalWidth - intraGap
            val fTextWidth = textWidth / 2f
            TopLabelLaneGroupInput(
                code = index + 1,
                centerX = 231.5f + 272f * index,
                bodyWidth = 192f,
                fTextWidth = fTextWidth,
                gTextWidth = textWidth - fTextWidth,
                gapWidth = intraGap,
                maxShift = 28.8f,
            )
        }

        val placements = TopLabelLaneLayout.solve(groups)

        assertLaneResolved(groups, placements)
        assertGroupScaleDifferenceAtMostOneStep(groups, placements)
    }

    @Test
    fun iteration66_scaledTopLabelKeepsGlyphBottomAlignedWithUnscaledSibling() {
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
                fLabel = "ASSIGN",
                gLabel = "MYALPHA",
                isEnabled = true,
            ),
        )

        keyView.updateLabels(snapshot)
        shadowOf(Looper.getMainLooper()).idle()
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)

        keyView.applyTopLabelPlacement(
            TopLabelLanePlacement(
                fScale = 1f,
                gScale = R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)

        val fBottom = glyphBottomY(keyView.fLabel)
        val gBottom = glyphBottomY(keyView.gLabel)

        assertTrue(keyView.gLabel.textSize < keyView.fLabel.textSize)
        assertEquals(fBottom, gBottom, 1.0f)
    }

    @Test
    fun iteration66_scalingChangeReplaysSameSnapshotAndRestoresStyledLabel() {
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
            performHapticClick = {},
            offerCoreTask = {},
            sendKey = {},
            getKeypadMetaNative = { _ -> fixture.meta.copyOf() },
            getKeypadLabelsNative = { _ -> fixture.labels.copyOf() },
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

        controller.applyScalingMode("physical")
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
    fun iteration70_pipExitReplaysSameSnapshotAndRestoresStyledLabel() {
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
            performHapticClick = {},
            offerCoreTask = {},
            sendKey = {},
            getKeypadMetaNative = { _ -> fixture.meta.copyOf() },
            getKeypadLabelsNative = { _ -> fixture.labels.copyOf() },
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
    fun iteration77_mainKeyModeMatrixKeepsNumericMatrixFontWhenPreviewIsDisabled() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyCode = 20
        val keyView = createAttachedMainKeyView(activity, keyCode)
        container.addView(keyView, FrameLayout.LayoutParams(331, 260))
        container.measure(exactly(500), exactly(320))
        container.layout(0, 0, 500, 320)
        shadowOf(Looper.getMainLooper()).idle()

        val baselineSnapshot = snapshotWith(
            keyCode to numericMatrixKeyState(),
        )
        keyView.updateLabels(baselineSnapshot)
        shadowOf(Looper.getMainLooper()).idle()
        container.measure(exactly(500), exactly(320))
        container.layout(0, 0, 500, 320)
        val baselineTextSize = keyView.primaryLabel.textSize
        val baselineLabel = keyView.primaryLabel.text.toString()

        ShiftPreviewState.entries.forEach { shiftState ->
            MainKeyDynamicMode.entries.forEach { mode ->
                val previewEnabled = mode == MainKeyDynamicMode.ON && shiftState != ShiftPreviewState.NONE
                val snapshot = snapshotWith(
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
                shadowOf(Looper.getMainLooper()).idle()
                container.measure(exactly(500), exactly(320))
                container.layout(0, 0, 500, 320)

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

    private fun snapshotWith(
        vararg states: Pair<Int, KeypadKeySnapshot>,
        sceneContractVersion: Int = 5,
    ): KeypadSnapshot {
        val keyStates = MutableList(43) { KeypadKeySnapshot.EMPTY }
        states.forEach { (code, keyState) ->
            keyStates[code - 1] = keyState
        }

        return KeypadSnapshot(
            keyboardState = KeyboardStateSnapshot.EMPTY,
            sceneContractVersion = sceneContractVersion,
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

    private fun solvedGroupBounds(
        input: TopLabelLaneGroupInput,
        placement: TopLabelLanePlacement,
    ): Pair<Float, Float> {
        val centerX = input.centerX + placement.centerShift
        val totalWidth =
            input.fTextWidth * placement.fScale +
                input.gapWidth +
                input.gTextWidth * placement.gScale
        return (centerX - totalWidth / 2f) to (centerX + totalWidth / 2f)
    }

    private fun assertGapAtLeast(
        left: TopLabelLaneGroupInput,
        right: TopLabelLaneGroupInput,
        placements: Map<Int, TopLabelLanePlacement>,
    ) {
        val solvedLeft = solvedGroupBounds(left, placements.getValue(left.code))
        val solvedRight = solvedGroupBounds(right, placements.getValue(right.code))
        val requiredGap = maxOf(left.gapWidth, right.gapWidth) * 2f

        assertTrue(solvedRight.first - solvedLeft.second >= requiredGap - 0.001f)
    }

    private fun assertNeighborBorderGapRespected(
        left: TopLabelLaneGroupInput,
        right: TopLabelLaneGroupInput,
        placements: Map<Int, TopLabelLanePlacement>,
    ) {
        val solvedLeft = solvedGroupBounds(left, placements.getValue(left.code))
        val solvedRight = solvedGroupBounds(right, placements.getValue(right.code))
        val corridorExtension = maxOf(left.gapWidth, right.gapWidth) * 5f
        val rightNeighborLeftBorder = right.centerX - right.bodyWidth / 2f + corridorExtension
        val leftNeighborRightBorder = left.centerX + left.bodyWidth / 2f - corridorExtension

        assertTrue(solvedLeft.second <= rightNeighborLeftBorder + 0.001f)
        assertTrue(solvedRight.first + 0.001f >= leftNeighborRightBorder)
    }

    private fun assertLaneResolved(
        groups: List<TopLabelLaneGroupInput>,
        placements: Map<Int, TopLabelLanePlacement>,
    ) {
        for (index in 0 until groups.lastIndex) {
            assertGapAtLeast(groups[index], groups[index + 1], placements)
            assertNeighborBorderGapRespected(groups[index], groups[index + 1], placements)
        }
    }

    private fun assertScreenBoundsRespected(
        group: TopLabelLaneGroupInput,
        placement: TopLabelLanePlacement,
    ) {
        val solvedBounds = solvedGroupBounds(group, placement)
        if (group.minLeftEdge.isFinite()) {
            assertTrue(solvedBounds.first + 0.001f >= group.minLeftEdge)
        }
        if (group.maxRightEdge.isFinite()) {
            assertTrue(solvedBounds.second <= group.maxRightEdge + 0.001f)
        }
    }

    private fun assertGroupScaleDifferenceAtMostOneStep(
        groups: List<TopLabelLaneGroupInput>,
        placements: Map<Int, TopLabelLanePlacement>,
    ) {
        for (group in groups) {
            if (group.gTextWidth <= 0f) {
                continue
            }
            val placement = placements.getValue(group.code)
            assertTrue(
                abs(placement.fScale - placement.gScale) <=
                    R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP + 0.001f,
            )
        }
    }

    private fun glyphBottomY(label: TextView): Float {
        val metrics = Paint(label.paint).fontMetrics
        val bottomOffset = -metrics.ascent + metrics.descent
        return label.top + label.translationY + bottomOffset
    }
}
