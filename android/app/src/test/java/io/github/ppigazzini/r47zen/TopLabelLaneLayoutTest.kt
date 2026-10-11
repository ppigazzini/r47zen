package io.github.ppigazzini.r47zen

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.numericFloat
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// The top-label lane solver the app runs, TopLabelLaneLayout.solve, tested on
// the plain JVM: it is pure Kotlin. These are its only tests of placement
// rules; the keypad-level tests in MainKeySnapshotRenderTest check that the
// layout applies what it solves.
class TopLabelLaneLayoutTest {
    @Test
    fun outerGroupsStayInsideTheScreenEdges() {
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
    fun interGroupGapIsAtLeastTwiceTheIntraGroupGap() {
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
    fun centeredGroupsMayUseTheFiveGapNeighborCorridor() {
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
    fun scalesTheMostOffendingLabelOnceTheShiftBudgetRunsOut() {
        val maxShift = STANDARD_MAX_SHIFT
        val left = standardGroup(0, fTextWidth = 130f, gTextWidth = 168f)
        val right = standardGroup(1, fTextWidth = 130f, gTextWidth = 178f)

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
        assertTrue(interGroupGap >= INTRA_GAP * 2f - 0.001f)
        assertNeighborBorderGapRespected(left, right, placements)
    }

    @Test
    fun movesOnlyTheGroupThatBreaksTheNeighborBorder() {
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 100f,
            bodyWidth = 240f,
            fTextWidth = 120f,
            gTextWidth = 120f,
            gapWidth = INTRA_GAP,
            maxShift = 36f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 300f,
            bodyWidth = 260f,
            fTextWidth = 40f,
            gTextWidth = 20f,
            gapWidth = INTRA_GAP,
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
    fun enforcesTheNeighborCorridorWithTheFiveGapExtension() {
        val left = TopLabelLaneGroupInput(
            code = 1,
            centerX = 100f,
            bodyWidth = 240f,
            fTextWidth = 120f,
            gTextWidth = 120f,
            gapWidth = INTRA_GAP,
            maxShift = 36f,
        )
        val right = TopLabelLaneGroupInput(
            code = 2,
            centerX = 300f,
            bodyWidth = 260f,
            fTextWidth = 40f,
            gTextWidth = 20f,
            gapWidth = INTRA_GAP,
            maxShift = 39f,
        )

        val placements = TopLabelLaneLayout.solve(listOf(left, right))

        assertNeighborBorderGapRespected(left, right, placements)
        assertTrue(placements.getValue(left.code).centerShift < 0f)
        assertEquals(0f, placements.getValue(right.code).centerShift, 0.001f)
    }

    @Test
    fun denseSixGroupRowResolvesByShifting() {
        val widths = listOf(377.09775f, 403.6195f, 420.34708f, 315.7074f, 302.4294f, 364.1773f)
        val groups = widths.mapIndexed { index, totalWidth -> standardGroup(index, totalWidth = totalWidth) }

        val placements = TopLabelLaneLayout.solve(groups)

        assertLaneResolved(groups, placements)
        assertTrue(groups.indices.any { index -> abs(placements.getValue(groups[index].code).centerShift) > 0.001f })
        assertGroupScaleDifferenceAtMostOneStep(groups, placements)
    }

    @Test
    fun denseFiveGroupEnterRowResolvesByShifting() {
        val widths = listOf(355.5047f, 369.6998f, 490.82352f, 377.73904f, 322.91824f)
        val centers = listOf(231.5f, 639.0f, 911.0f, 1183.0f, 1455.0f)
        val bodyWidths = listOf(462f, 192f, 192f, 192f, 192f)
        val maxShifts = bodyWidths.map { it * R47TopLabelSolverPolicy.TOP_F_G_LABEL_MAX_SHIFT_FRACTION }
        val groups = widths.mapIndexed { index, totalWidth ->
            val textWidth = totalWidth - INTRA_GAP
            val fTextWidth = textWidth / 2f
            TopLabelLaneGroupInput(
                code = index + 13,
                centerX = centers[index],
                bodyWidth = bodyWidths[index],
                fTextWidth = fTextWidth,
                gTextWidth = textWidth - fTextWidth,
                gapWidth = INTRA_GAP,
                maxShift = maxShifts[index],
            )
        }

        val placements = TopLabelLaneLayout.solve(groups)

        assertLaneResolved(groups, placements)
        assertTrue(groups.indices.any { index -> abs(placements.getValue(groups[index].code).centerShift) > 0.001f })
        assertGroupScaleDifferenceAtMostOneStep(groups, placements)
    }

    @Test
    fun scaledLocalOffenderLeavesDistantGroupsCentered() {
        val widths = listOf(288.11f, 172.8f, 214.38f, 240.01f, 317.95f, 221.12f)
        val groups = widths.mapIndexed { index, totalWidth -> standardGroup(index, totalWidth = totalWidth) }

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
    fun mildConflictIsResolvedByShiftingBothGroupsUnscaled() {
        val left = standardGroup(0, fTextWidth = 145f, gTextWidth = 145f)
        val right = standardGroup(1, fTextWidth = 145f, gTextWidth = 145f)

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
    fun secondScaleStepScalesTheOtherLabel() {
        val left = standardGroup(0, fTextWidth = 140f, gTextWidth = 158f)
        val right = standardGroup(1, fTextWidth = 140f, gTextWidth = 172f)

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
    fun fullyScaledGroupForcesItsCollidingNeighborToScale() {
        val widths = listOf(377.47998046875f, 283.8518371582031f, 326.15771484375f)
        val groups = widths.mapIndexed { index, totalWidth -> standardGroup(index, totalWidth = totalWidth) }

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
    fun pathologicalRowTranslatesPastTheBudgetBeforeScalingBelowTheFloor() {
        val left = standardGroup(0, fTextWidth = 160f, gTextWidth = 210f)
        val right = standardGroup(1, fTextWidth = 220f, gTextWidth = 280f)

        val placements = TopLabelLaneLayout.solve(listOf(left, right))

        // Shifting within the budget and scaling to the floor do not resolve
        // this row. The solver then translates both groups past their budget,
        // and that resolves it, so no label goes below the floor.
        listOf(left, right).forEach { group ->
            val placement = placements.getValue(group.code)
            assertEquals(R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE, placement.fScale, 0.001f)
            assertEquals(R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE, placement.gScale, 0.001f)
            assertTrue(
                "group ${group.code} shifted ${placement.centerShift}, within its budget ${group.maxShift}",
                abs(placement.centerShift) > group.maxShift,
            )
        }
        assertLaneResolved(listOf(left, right), placements)
    }

    @Test
    fun denseRowScalesNeverDifferByMoreThanOneStep() {
        val widths = listOf(484.09457f, 409.71756f, 497.75793f, 364.88055f, 362.02008f, 511.268f)
        val groups = widths.mapIndexed { index, totalWidth -> standardGroup(index, totalWidth = totalWidth) }

        val placements = TopLabelLaneLayout.solve(groups)

        assertLaneResolved(groups, placements)
        assertGroupScaleDifferenceAtMostOneStep(groups, placements)
    }


    @Test
    fun rowWithoutConflictKeepsEveryGroupCenteredAndUnscaled() {
        val groups = (0 until 3).map { index -> standardGroup(index, totalWidth = 180f) }

        val placements = TopLabelLaneLayout.solve(groups)

        groups.forEach { group ->
            assertEquals(TopLabelLanePlacement.DEFAULT, placements.getValue(group.code))
        }
    }

    @Test
    fun firstScaleStepHitsOnlyTheMostOffendingLaterLabel() {
        val left = standardGroup(0, fTextWidth = 140f, gTextWidth = 158f)
        val right = standardGroup(1, fTextWidth = 140f, gTextWidth = 166f)

        val placements = TopLabelLaneLayout.solve(listOf(left, right))

        assertEquals(1f, placements.getValue(left.code).fScale, 0.001f)
        assertEquals(1f, placements.getValue(left.code).gScale, 0.001f)
        assertEquals(1f, placements.getValue(right.code).fScale, 0.001f)
        assertEquals(
            1f - R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP,
            placements.getValue(right.code).gScale,
            0.001f,
        )
        assertLaneResolved(listOf(left, right), placements)
    }

    @Test
    fun anyDenseStandardRowResolvesWithScalesAtMostOneStepApart() {
        runBlocking {
            checkAll(SWEEP_CONFIG, Arb.list(Arb.numericFloat(220f, 430f), 6..6)) { widths ->
                val groups = widths.mapIndexed { index, width -> standardGroup(index, totalWidth = width) }

                val placements = TopLabelLaneLayout.solve(groups)

                assertLaneResolved(groups, placements)
                assertGroupScaleDifferenceAtMostOneStep(groups, placements)
            }
        }
    }

    @Test
    fun anyDenseEnterRowResolvesWithScalesAtMostOneStepApart() {
        runBlocking {
            checkAll(SWEEP_CONFIG, Arb.list(Arb.numericFloat(260f, 520f), 5..5)) { widths ->
                val groups = widths.mapIndexed { index, width -> enterRowGroup(index, width) }

                val placements = TopLabelLaneLayout.solve(groups)

                assertLaneResolved(groups, placements)
                assertGroupScaleDifferenceAtMostOneStep(groups, placements)
            }
        }
    }

    // A standard-row group at slot INDEX: the reference body width and pitch,
    // and either one total width split evenly around the gap or two widths.
    private fun standardGroup(
        index: Int,
        totalWidth: Float? = null,
        fTextWidth: Float? = null,
        gTextWidth: Float? = null,
    ): TopLabelLaneGroupInput {
        val textWidth = (totalWidth ?: 0f) - INTRA_GAP
        val f = fTextWidth ?: (textWidth / 2f)
        val g = gTextWidth ?: (textWidth - textWidth / 2f)
        return TopLabelLaneGroupInput(
            code = index + 1,
            centerX = FIRST_STANDARD_CENTER + STANDARD_PITCH * index,
            bodyWidth = STANDARD_BODY,
            fTextWidth = f,
            gTextWidth = g,
            gapWidth = INTRA_GAP,
            maxShift = STANDARD_MAX_SHIFT,
        )
    }

    // The ENTER row: the wide ENTER key, then four standard keys starting two
    // standard pitches from the row's left edge.
    private fun enterRowGroup(index: Int, totalWidth: Float): TopLabelLaneGroupInput {
        val textWidth = totalWidth - INTRA_GAP
        val isEnter = index == 0
        val bodyWidth = if (isEnter) R47ReferenceGeometry.ENTER_WIDTH else STANDARD_BODY
        val left = if (isEnter) {
            R47ReferenceGeometry.STANDARD_LEFT
        } else {
            R47ReferenceGeometry.STANDARD_LEFT + STANDARD_PITCH * (index + 1)
        }
        return TopLabelLaneGroupInput(
            code = index + 13,
            centerX = left + bodyWidth / 2f + OPTICAL_CENTER_OFFSET,
            bodyWidth = bodyWidth,
            fTextWidth = textWidth / 2f,
            gTextWidth = textWidth - textWidth / 2f,
            gapWidth = INTRA_GAP,
            maxShift = bodyWidth * R47TopLabelSolverPolicy.TOP_F_G_LABEL_MAX_SHIFT_FRACTION,
        )
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


    private companion object {
        const val INTRA_GAP = R47LabelLayoutPolicy.TOP_F_G_LABEL_HORIZONTAL_GAP
        const val STANDARD_BODY = R47ReferenceGeometry.STANDARD_KEY_WIDTH
        const val STANDARD_PITCH = R47ReferenceGeometry.STANDARD_PITCH
        const val STANDARD_MAX_SHIFT = STANDARD_BODY * R47TopLabelSolverPolicy.TOP_F_G_LABEL_MAX_SHIFT_FRACTION

        // A key's center in the reference frame: its left edge, half its body,
        // and a quarter of the optical width delta.
        const val OPTICAL_CENTER_OFFSET = R47KeySurfacePolicy.MAIN_KEY_BODY_OPTICAL_WIDTH_DELTA / 4f
        const val FIRST_STANDARD_CENTER =
            R47ReferenceGeometry.STANDARD_LEFT + STANDARD_BODY / 2f + OPTICAL_CENTER_OFFSET

        val SWEEP_CONFIG = PropTestConfig(seed = 20260509L, iterations = 256)
    }
}
