package io.github.ppigazzini.r47zen

import kotlin.math.max
import kotlin.math.min

internal enum class TopLabelLaneLabel {
    F,
    G,
}

internal data class TopLabelLaneGroupInput(
    val code: Int,
    val centerX: Float,
    val bodyWidth: Float,
    val fTextWidth: Float,
    val gTextWidth: Float,
    val gapWidth: Float,
    val maxShift: Float,
    val minLeftEdge: Float = Float.NEGATIVE_INFINITY,
    val maxRightEdge: Float = Float.POSITIVE_INFINITY,
) {
    val hasGLabel: Boolean
        get() = gTextWidth > 0f && gapWidth > 0f

    val totalWidth: Float
        get() = fTextWidth + gapWidth + gTextWidth

    val longestLabel: TopLabelLaneLabel
        get() = if (!hasGLabel || fTextWidth > gTextWidth) TopLabelLaneLabel.F else TopLabelLaneLabel.G
}

internal data class TopLabelLanePlacement(
    val centerShift: Float = 0f,
    val fScale: Float = 1f,
    val gScale: Float = 1f,
) {
    companion object {
        val DEFAULT = TopLabelLanePlacement()
    }
}

private data class TopLabelLaneGroupState(
    val group: TopLabelLaneGroupInput,
    var fScale: Float = 1f,
    var gScale: Float = 1f,
    var centerX: Float = group.centerX,
) {
    val minCenterX: Float
        get() = group.centerX - group.maxShift

    val maxCenterX: Float
        get() = group.centerX + group.maxShift

    val totalWidth: Float
        get() = scaledFTextWidth + group.gapWidth + scaledGTextWidth

    val scaledFTextWidth: Float
        get() = group.fTextWidth * fScale

    val scaledGTextWidth: Float
        get() = group.gTextWidth * gScale

    val left: Float
        get() = centerX - totalWidth / 2f

    val right: Float
        get() = centerX + totalWidth / 2f
}

private data class TopLabelLaneConflict(
    val leftIndex: Int,
    val rightIndex: Int,
    val shortage: Float,
    val gapShortage: Float,
    val leftNeighborOverrun: Float,
    val rightNeighborOverrun: Float,
    val leftScreenOverrun: Float,
    val rightScreenOverrun: Float,
)

private data class TopLabelLaneShiftTarget(
    val offenderIndex: Int,
    val overrun: Float,
    val towardLeft: Boolean,
)

private data class TopLabelLaneScaleCandidate(
    val rank: Int,
    val totalWidth: Float,
    val limitOverrun: Float,
    val offenderIndex: Int,
)

internal object TopLabelLaneLayout {
    private const val EPSILON = 0.001f
    private const val MAX_RELAXATION_PASSES = 64
    private const val RELAXATION_PASSES_PER_STATE = 32
    private const val PATHOLOGICAL_SCALE_FLOOR = 0f
    private const val CORRIDOR_EXTENSION_INTRA_GAPS = 5f

    fun solve(groups: List<TopLabelLaneGroupInput>): Map<Int, TopLabelLanePlacement> {
        if (groups.isEmpty()) {
            return emptyMap()
        }

        if (groups.size == 1) {
            return mapOf(groups.single().code to TopLabelLanePlacement.DEFAULT)
        }

        val states = groups.map { TopLabelLaneGroupState(group = it) }
        var conflicts = relaxRow(states, ignorePreferredShiftBudget = false)

        if (conflicts.isNotEmpty()) {
            conflicts = scaleUntilResolved(
                states = states,
                conflicts = conflicts,
                scaleFloor = R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE,
                ignorePreferredShiftBudget = false,
            )
        }

        if (conflicts.isNotEmpty()) {
            conflicts = relaxRow(states, ignorePreferredShiftBudget = true)
        }

        if (conflicts.isNotEmpty()) {
            conflicts = scaleUntilResolved(
                states = states,
                conflicts = conflicts,
                scaleFloor = PATHOLOGICAL_SCALE_FLOOR,
                ignorePreferredShiftBudget = true,
            )
        }

        if (conflicts.isNotEmpty()) {
            conflicts = relaxRow(states, ignorePreferredShiftBudget = true)
        }

        return states.associate { state ->
            state.group.code to TopLabelLanePlacement(
                centerShift = state.centerX - state.group.centerX,
                fScale = state.fScale,
                gScale = state.gScale,
            )
        }
    }

    private fun resetPositions(states: List<TopLabelLaneGroupState>) {
        states.forEach { it.centerX = it.group.centerX }
    }

    private fun neighborCorridorLimits(
        left: TopLabelLaneGroupState,
        right: TopLabelLaneGroupState,
    ): Pair<Float, Float> {
        val corridorExtension = maxOf(left.group.gapWidth, right.group.gapWidth) * CORRIDOR_EXTENSION_INTRA_GAPS
        val rawLeftLimit = right.group.centerX - right.group.bodyWidth / 2f
        val rawRightLimit = left.group.centerX + left.group.bodyWidth / 2f
        val neighborLeftLimit = rawLeftLimit + corridorExtension
        val neighborRightLimit = rawRightLimit - corridorExtension
        return neighborLeftLimit to neighborRightLimit
    }

    private fun cloneStates(states: List<TopLabelLaneGroupState>): List<TopLabelLaneGroupState> {
        return states.map { it.copy() }
    }

    private fun restoreStates(
        target: List<TopLabelLaneGroupState>,
        source: List<TopLabelLaneGroupState>,
    ) {
        for (index in target.indices) {
            target[index].fScale = source[index].fScale
            target[index].gScale = source[index].gScale
            target[index].centerX = source[index].centerX
        }
    }

    private fun buildConflict(
        states: List<TopLabelLaneGroupState>,
        index: Int,
    ): TopLabelLaneConflict? {
        val left = states[index]
        val right = states[index + 1]
        val (leftLimit, rightLimit) = neighborCorridorLimits(left, right)
        val gapShortage = max(0f, requiredGap(left, right) - (right.left - left.right))
        val leftNeighborOverrun = max(0f, left.right - leftLimit)
        val rightNeighborOverrun = max(0f, rightLimit - right.left)
        val leftScreenOverrun = if (index == 0 && left.group.minLeftEdge.isFinite()) {
            max(0f, left.group.minLeftEdge - left.left)
        } else {
            0f
        }
        val rightScreenOverrun = if (index == states.lastIndex - 1 && right.group.maxRightEdge.isFinite()) {
            max(0f, right.right - right.group.maxRightEdge)
        } else {
            0f
        }
        if (
            gapShortage <= EPSILON &&
            leftNeighborOverrun <= EPSILON &&
            rightNeighborOverrun <= EPSILON &&
            leftScreenOverrun <= EPSILON &&
            rightScreenOverrun <= EPSILON
        ) {
            return null
        }

        return TopLabelLaneConflict(
            leftIndex = index,
            rightIndex = index + 1,
            shortage = max(
                max(gapShortage, leftNeighborOverrun + rightNeighborOverrun),
                max(leftScreenOverrun, rightScreenOverrun),
            ),
            gapShortage = gapShortage,
            leftNeighborOverrun = leftNeighborOverrun,
            rightNeighborOverrun = rightNeighborOverrun,
            leftScreenOverrun = leftScreenOverrun,
            rightScreenOverrun = rightScreenOverrun,
        )
    }

    private fun currentConflicts(states: List<TopLabelLaneGroupState>): List<TopLabelLaneConflict> {
        return buildList {
            for (index in 0 until states.lastIndex) {
                buildConflict(states, index)?.let { add(it) }
            }
        }
    }

    private fun rowResolved(states: List<TopLabelLaneGroupState>): Boolean {
        return currentConflicts(states).isEmpty()
    }

    private fun selectedOffender(
        states: List<TopLabelLaneGroupState>,
        conflict: TopLabelLaneConflict,
    ): TopLabelLaneShiftTarget? {
        val candidates = buildList {
            if (conflict.leftNeighborOverrun > EPSILON) {
                add(TopLabelLaneShiftTarget(conflict.leftIndex, conflict.leftNeighborOverrun, true) to 0)
            }
            if (conflict.rightNeighborOverrun > EPSILON) {
                add(TopLabelLaneShiftTarget(conflict.rightIndex, conflict.rightNeighborOverrun, false) to 0)
            }
            if (conflict.leftScreenOverrun > EPSILON) {
                add(TopLabelLaneShiftTarget(conflict.leftIndex, conflict.leftScreenOverrun, false) to 1)
            }
            if (conflict.rightScreenOverrun > EPSILON) {
                add(TopLabelLaneShiftTarget(conflict.rightIndex, conflict.rightScreenOverrun, true) to 1)
            }
        }
        val selected = candidates.maxWithOrNull(
            compareBy<Pair<TopLabelLaneShiftTarget, Int>>({ it.first.overrun }, { it.second }, { it.first.offenderIndex }),
        )
        if (selected != null) {
            return selected.first
        }

        if (conflict.gapShortage <= EPSILON) {
            return null
        }

        val left = states[conflict.leftIndex]
        val right = states[conflict.rightIndex]
        return if (left.totalWidth > right.totalWidth + EPSILON) {
            TopLabelLaneShiftTarget(conflict.leftIndex, conflict.gapShortage, true)
        } else {
            TopLabelLaneShiftTarget(conflict.rightIndex, conflict.gapShortage, false)
        }
    }

    private fun shiftCapacity(
        state: TopLabelLaneGroupState,
        towardLeft: Boolean,
        ignorePreferredShiftBudget: Boolean,
    ): Float {
        if (ignorePreferredShiftBudget) {
            return Float.POSITIVE_INFINITY
        }
        return if (towardLeft) state.centerX - state.minCenterX else state.maxCenterX - state.centerX
    }

    private fun applyShift(
        state: TopLabelLaneGroupState,
        towardLeft: Boolean,
        amount: Float,
    ) {
        if (towardLeft) {
            state.centerX -= amount
        } else {
            state.centerX += amount
        }
    }

    private fun tryLocalOffenderTranslation(states: List<TopLabelLaneGroupState>): Boolean {
        val working = cloneStates(states)

        repeat(maxOf(MAX_RELAXATION_PASSES, states.size * 2)) {
            val conflicts = currentConflicts(working)
            if (conflicts.isEmpty() && rowResolved(working)) {
                restoreStates(states, working)
                return true
            }

            var changed = false
            for (conflict in conflicts) {
                val selected = selectedOffender(working, conflict) ?: continue
                val offenderIndex = selected.offenderIndex
                val offenderOverrun = selected.overrun
                val offender = working[offenderIndex]
                val capacity = shiftCapacity(
                    state = offender,
                    towardLeft = selected.towardLeft,
                    ignorePreferredShiftBudget = false,
                )
                val move = min(offenderOverrun, capacity)
                if (move > EPSILON) {
                    applyShift(offender, towardLeft = selected.towardLeft, amount = move)
                    changed = true
                }
            }

            if (!changed) {
                return@repeat
            }
        }

        if (rowResolved(working)) {
            restoreStates(states, working)
            return true
        }

        return false
    }

    private fun resolvePositions(
        states: List<TopLabelLaneGroupState>,
        ignorePreferredShiftBudget: Boolean,
    ): List<TopLabelLaneConflict> {
        repeat(maxOf(MAX_RELAXATION_PASSES, states.size * RELAXATION_PASSES_PER_STATE)) {
            var changed = false

            for (conflict in currentConflicts(states)) {
                val left = states[conflict.leftIndex]
                val right = states[conflict.rightIndex]
                val leftMove = min(
                    conflict.leftNeighborOverrun,
                    shiftCapacity(
                        state = left,
                        towardLeft = true,
                        ignorePreferredShiftBudget = ignorePreferredShiftBudget,
                    ),
                )
                if (leftMove > EPSILON) {
                    left.centerX -= leftMove
                    changed = true
                }

                val rightMove = min(
                    conflict.rightNeighborOverrun,
                    shiftCapacity(
                        state = right,
                        towardLeft = false,
                        ignorePreferredShiftBudget = ignorePreferredShiftBudget,
                    ),
                )
                if (rightMove > EPSILON) {
                    right.centerX += rightMove
                    changed = true
                }

                val leftScreenMove = min(
                    conflict.leftScreenOverrun,
                    shiftCapacity(
                        state = left,
                        towardLeft = false,
                        ignorePreferredShiftBudget = ignorePreferredShiftBudget,
                    ),
                )
                if (leftScreenMove > EPSILON) {
                    left.centerX += leftScreenMove
                    changed = true
                }

                val rightScreenMove = min(
                    conflict.rightScreenOverrun,
                    shiftCapacity(
                        state = right,
                        towardLeft = true,
                        ignorePreferredShiftBudget = ignorePreferredShiftBudget,
                    ),
                )
                if (rightScreenMove > EPSILON) {
                    right.centerX -= rightScreenMove
                    changed = true
                }

                val refreshedConflict = buildConflict(states, conflict.leftIndex) ?: continue
                if (refreshedConflict.gapShortage <= EPSILON) {
                    continue
                }

                var shortage = refreshedConflict.gapShortage
                val halfShortage = shortage / 2f

                var leftCapacity = shiftCapacity(
                    state = left,
                    towardLeft = true,
                    ignorePreferredShiftBudget = ignorePreferredShiftBudget,
                )
                var rightCapacity = shiftCapacity(
                    state = right,
                    towardLeft = false,
                    ignorePreferredShiftBudget = ignorePreferredShiftBudget,
                )

                val leftGapMove = min(halfShortage, leftCapacity)
                if (leftGapMove > EPSILON) {
                    left.centerX -= leftGapMove
                    shortage -= leftGapMove
                    if (!ignorePreferredShiftBudget) {
                        leftCapacity -= leftGapMove
                    }
                    changed = true
                }

                val rightGapMove = min(halfShortage, rightCapacity)
                if (rightGapMove > EPSILON) {
                    right.centerX += rightGapMove
                    shortage -= rightGapMove
                    if (!ignorePreferredShiftBudget) {
                        rightCapacity -= rightGapMove
                    }
                    changed = true
                }

                val selected = selectedOffender(states, refreshedConflict)
                val orderedSides = listOf(selected?.towardLeft ?: false, !(selected?.towardLeft ?: false))

                for (towardLeft in orderedSides) {
                    val capacity = if (towardLeft) leftCapacity else rightCapacity
                    val extra = min(shortage, capacity)
                    if (extra > EPSILON) {
                        if (towardLeft) {
                            left.centerX -= extra
                            if (!ignorePreferredShiftBudget) {
                                leftCapacity -= extra
                            }
                        } else {
                            right.centerX += extra
                            if (!ignorePreferredShiftBudget) {
                                rightCapacity -= extra
                            }
                        }
                        shortage -= extra
                        changed = true
                    }
                    if (shortage <= EPSILON) {
                        break
                    }
                }
            }

            if (!changed) {
                return@repeat
            }
        }

        return currentConflicts(states)
    }

    private fun labelScale(
        state: TopLabelLaneGroupState,
        label: TopLabelLaneLabel,
    ): Float {
        return if (label == TopLabelLaneLabel.F) state.fScale else state.gScale
    }

    private fun labelWidth(
        state: TopLabelLaneGroupState,
        label: TopLabelLaneLabel,
    ): Float {
        return if (label == TopLabelLaneLabel.F) state.group.fTextWidth else state.group.gTextWidth
    }

    private fun labelCanScale(
        state: TopLabelLaneGroupState,
        label: TopLabelLaneLabel,
        scaleFloor: Float,
    ): Boolean {
        return labelWidth(state, label) > EPSILON && labelScale(state, label) > scaleFloor + EPSILON
    }

    private fun scaleCandidateLabel(
        state: TopLabelLaneGroupState,
        scaleFloor: Float,
    ): TopLabelLaneLabel? {
        val primary = state.group.longestLabel
        val secondary = if (primary == TopLabelLaneLabel.F) TopLabelLaneLabel.G else TopLabelLaneLabel.F
        val primaryCanScale = labelCanScale(state, primary, scaleFloor)
        val secondaryCanScale = labelCanScale(state, secondary, scaleFloor)

        if (!primaryCanScale && !secondaryCanScale) {
            return null
        }

        val primaryScale = labelScale(state, primary)
        val secondaryScale = labelScale(state, secondary)
        if (primaryScale + R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP <= secondaryScale + EPSILON && secondaryCanScale) {
            return secondary
        }
        if (secondaryScale + R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP <= primaryScale + EPSILON && primaryCanScale) {
            return primary
        }

        if (primaryCanScale) {
            return primary
        }

        if (secondaryCanScale) {
            return secondary
        }

        return null
    }

    private fun scalingCandidatesForConflict(
        states: List<TopLabelLaneGroupState>,
        conflict: TopLabelLaneConflict,
    ): List<TopLabelLaneScaleCandidate> {
        val corridorCandidates = buildList {
            val leftLimitOverrun = max(conflict.leftNeighborOverrun, conflict.leftScreenOverrun)
            if (leftLimitOverrun > EPSILON) {
                add(
                    TopLabelLaneScaleCandidate(
                        rank = 0,
                        totalWidth = states[conflict.leftIndex].totalWidth,
                        limitOverrun = leftLimitOverrun,
                        offenderIndex = conflict.leftIndex,
                    ),
                )
            }
            val rightLimitOverrun = max(conflict.rightNeighborOverrun, conflict.rightScreenOverrun)
            if (rightLimitOverrun > EPSILON) {
                add(
                    TopLabelLaneScaleCandidate(
                        rank = 0,
                        totalWidth = states[conflict.rightIndex].totalWidth,
                        limitOverrun = rightLimitOverrun,
                        offenderIndex = conflict.rightIndex,
                    ),
                )
            }
        }
        if (corridorCandidates.isNotEmpty()) {
            return corridorCandidates
        }

        if (conflict.gapShortage <= EPSILON) {
            return emptyList()
        }

        return listOf(conflict.leftIndex, conflict.rightIndex)
            .sortedWith(compareBy<Int>({ states[it].totalWidth }, { it }).reversed())
            .mapIndexed { rank, offenderIndex ->
                TopLabelLaneScaleCandidate(
                    rank = rank,
                    totalWidth = states[offenderIndex].totalWidth,
                    limitOverrun = conflict.gapShortage,
                    offenderIndex = offenderIndex,
                )
            }
    }

    private fun scaleMostOffending(
        states: List<TopLabelLaneGroupState>,
        conflicts: List<TopLabelLaneConflict>,
        scaleFloor: Float,
    ): Boolean {
        val candidates = mutableMapOf<Int, TopLabelLaneScaleCandidate>()
        for (conflict in conflicts) {
            for (candidate in scalingCandidatesForConflict(states, conflict)) {
                val existing = candidates[candidate.offenderIndex]
                if (
                    existing == null ||
                        compareValuesBy(
                            candidate,
                            existing,
                            TopLabelLaneScaleCandidate::rank,
                            { -it.totalWidth },
                            { -it.limitOverrun },
                            { -it.offenderIndex },
                        ) < 0
                ) {
                    candidates[candidate.offenderIndex] = candidate
                }
            }
        }

        val orderedCandidates = candidates.values.sortedWith(
            compareBy<TopLabelLaneScaleCandidate>(
                TopLabelLaneScaleCandidate::rank,
                { -it.totalWidth },
                { -it.limitOverrun },
                { -it.offenderIndex },
            ),
        )

        for (candidate in orderedCandidates) {
            val state = states[candidate.offenderIndex]
            val label = scaleCandidateLabel(state, scaleFloor) ?: continue
            val currentScale = labelScale(state, label)
            val targetScale = max(
                scaleFloor,
                currentScale - R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP,
            )

            if (targetScale + EPSILON < currentScale) {
                if (label == TopLabelLaneLabel.F) {
                    state.fScale = targetScale
                } else {
                    state.gScale = targetScale
                }
                return true
            }
        }

        return false
    }

    private fun relaxRow(
        states: List<TopLabelLaneGroupState>,
        ignorePreferredShiftBudget: Boolean,
    ): List<TopLabelLaneConflict> {
        resetPositions(states)
        if (rowResolved(states)) {
            return emptyList()
        }
        if (!ignorePreferredShiftBudget && tryLocalOffenderTranslation(states)) {
            return emptyList()
        }
        return resolvePositions(states, ignorePreferredShiftBudget = ignorePreferredShiftBudget)
    }

    private fun scaleUntilResolved(
        states: List<TopLabelLaneGroupState>,
        conflicts: List<TopLabelLaneConflict>,
        scaleFloor: Float,
        ignorePreferredShiftBudget: Boolean,
    ): List<TopLabelLaneConflict> {
        var remainingConflicts = conflicts
        val maxScaleSteps = maxOf(
            1,
            (((1f - scaleFloor) / R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP) + 0.999999f).toInt(),
        )
        repeat(states.size * 2 * maxScaleSteps) {
            if (remainingConflicts.isEmpty()) {
                return emptyList()
            }
            if (!scaleMostOffending(states, remainingConflicts, scaleFloor)) {
                return remainingConflicts
            }
            remainingConflicts = relaxRow(states, ignorePreferredShiftBudget = ignorePreferredShiftBudget)
        }
        return remainingConflicts
    }

    private fun requiredGap(
        left: TopLabelLaneGroupState,
        right: TopLabelLaneGroupState,
    ): Float {
        return maxOf(left.group.gapWidth, right.group.gapWidth) * 2f
    }
}
