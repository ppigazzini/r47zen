package io.github.ppigazzini.r47zen

import kotlin.math.abs
import kotlin.math.sign

internal data class GraphGestureBatch(
    val panDxNorm: Float,
    val panDyNorm: Float,
    val scaleFactor: Float,
)

internal class GraphGestureAccumulator(
    private val panFlushEpsilon: Float,
    private val panApplyLimit: Float,
    private val panPendingLimit: Float,
    private val scaleFlushEpsilon: Float,
    private val scaleFactorMin: Float,
    private val scaleFactorMax: Float,
) {
    private var pendingPanDxNorm = 0f
    private var pendingPanDyNorm = 0f
    private var pendingScaleFactor = 1f

    fun addPan(dxNorm: Float, dyNorm: Float) {
        if (!dxNorm.isFinite() || !dyNorm.isFinite()) {
            return
        }
        if (dxNorm == 0f && dyNorm == 0f) {
            return
        }

        pendingPanDxNorm += dxNorm
        pendingPanDyNorm += dyNorm
        sanitizePendingPan()
    }

    fun addScale(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) {
            return
        }

        val updatedScale = pendingScaleFactor * scaleFactor
        pendingScaleFactor = if (updatedScale.isFinite() && updatedScale > 0f) {
            updatedScale.coerceIn(scaleFactorMin, scaleFactorMax)
        } else {
            1f
        }
    }

    fun drainBatch(): GraphGestureBatch? {
        val panDxNorm = takePanStep(pendingPanDxNorm)
        val panDyNorm = takePanStep(pendingPanDyNorm)
        pendingPanDxNorm = consumePanStep(pendingPanDxNorm, panDxNorm)
        pendingPanDyNorm = consumePanStep(pendingPanDyNorm, panDyNorm)

        val scaleFactor = if (abs(pendingScaleFactor - 1f) > scaleFlushEpsilon) {
            pendingScaleFactor.also {
                pendingScaleFactor = 1f
            }
        } else {
            1f
        }

        val hasPan = abs(panDxNorm) > panFlushEpsilon || abs(panDyNorm) > panFlushEpsilon
        val hasScale = abs(scaleFactor - 1f) > scaleFlushEpsilon
        if (!hasPan && !hasScale) {
            return null
        }

        return GraphGestureBatch(
            panDxNorm = panDxNorm,
            panDyNorm = panDyNorm,
            scaleFactor = scaleFactor,
        )
    }

    fun hasPending(): Boolean {
        return abs(pendingPanDxNorm) > panFlushEpsilon ||
            abs(pendingPanDyNorm) > panFlushEpsilon ||
            abs(pendingScaleFactor - 1f) > scaleFlushEpsilon
    }

    private fun sanitizePendingPan() {
        pendingPanDxNorm = sanitizePendingPanComponent(pendingPanDxNorm)
        pendingPanDyNorm = sanitizePendingPanComponent(pendingPanDyNorm)
    }

    private fun sanitizePendingPanComponent(value: Float): Float {
        if (!value.isFinite()) {
            return 0f
        }
        if (abs(value) <= panFlushEpsilon) {
            return 0f
        }
        return value.coerceIn(-panPendingLimit, panPendingLimit)
    }

    private fun takePanStep(value: Float): Float {
        if (!value.isFinite() || abs(value) <= panFlushEpsilon) {
            return 0f
        }
        if (abs(value) <= panApplyLimit) {
            return value
        }
        return sign(value) * panApplyLimit
    }

    private fun consumePanStep(value: Float, step: Float): Float {
        if (!value.isFinite()) {
            return 0f
        }

        val remainder = value - step
        if (!remainder.isFinite() || abs(remainder) <= panFlushEpsilon) {
            return 0f
        }
        return remainder
    }
}
