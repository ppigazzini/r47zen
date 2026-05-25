package io.github.ppigazzini.r47zen

import android.view.Choreographer

internal interface DisplayRefreshLoop {
    fun start()
    fun stop()
}

internal class NativeDisplayRefreshLoop(
    private val isAppRunning: () -> Boolean,
    private val isNativeInitialized: () -> Boolean,
    private val isPerformanceSnapshotEnabled: () -> Boolean = { true },
    private val getPerformanceWindowMillis: () -> Long = { DEFAULT_PERFORMANCE_WINDOW_MILLIS },
    private val getPackedDisplayGeneration: () -> Int,
    private val getPackedDisplayBuffer: (ByteArray) -> Boolean,
    private val getKeypadSnapshotGeneration: () -> Int,
    private val getMainKeyDynamicModeCode: () -> Int,
    private val refreshKeypadSnapshot: (Int) -> NativeKeypadSnapshotRefreshResult,
    private val onPackedLcd: (ByteArray) -> Boolean,
    private val onDynamicRefresh: (KeypadSnapshot) -> Unit,
    private val onPerformanceSnapshot: (DeveloperPerformanceSnapshot) -> Unit = {},
    private val measureNanos: () -> Long = System::nanoTime,
) : DisplayRefreshLoop {
    companion object {
        internal const val DEFAULT_PERFORMANCE_WINDOW_MILLIS = 500L
    }

    private val packedLcdBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
    private var lastDisplayGeneration = Int.MIN_VALUE
    private var lastLabelRefresh = 0L
    private var lastKeypadGeneration = Int.MIN_VALUE
    private var performanceWindowStartMillis = 0L
    private var performanceFrameCount = 0
    private var performanceLcdUpdateCount = 0
    private var performanceLcdUpdateNanos = 0L
    private var performanceDirtyRowsTotal = 0
    private var lastPerformanceWindowMillis = DEFAULT_PERFORMANCE_WINDOW_MILLIS
    private var isActive = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isActive || !isAppRunning()) {
                return
            }

            refreshFrame(System.currentTimeMillis())

            if (isActive && isAppRunning()) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    internal fun refreshFrame(nowMillis: Long) {
        if (!isNativeInitialized()) {
            return
        }

        val performanceWindowMillis = getPerformanceWindowMillis().coerceAtLeast(1L)
        if (performanceWindowMillis != lastPerformanceWindowMillis) {
            lastPerformanceWindowMillis = performanceWindowMillis
            resetPerformanceWindow()
        }

        val isPerformanceSnapshotEnabled = isPerformanceSnapshotEnabled()
        if (isPerformanceSnapshotEnabled) {
            if (performanceWindowStartMillis == 0L) {
                performanceWindowStartMillis = nowMillis
            }
            performanceFrameCount += 1
        } else if (performanceWindowStartMillis != 0L) {
            resetPerformanceWindow()
        }

        val currentDisplayGeneration = getPackedDisplayGeneration()
        if (currentDisplayGeneration != lastDisplayGeneration) {
            val updateStartNanos = if (isPerformanceSnapshotEnabled) {
                measureNanos()
            } else {
                0L
            }
            if (getPackedDisplayBuffer(packedLcdBuffer)) {
                val dirtyRowsInFrame = if (isPerformanceSnapshotEnabled) {
                    countDirtyRows(packedLcdBuffer)
                } else {
                    0
                }
                onPackedLcd(packedLcdBuffer)
                lastDisplayGeneration = currentDisplayGeneration
                if (isPerformanceSnapshotEnabled) {
                    performanceLcdUpdateCount += 1
                    performanceLcdUpdateNanos +=
                        (measureNanos() - updateStartNanos).coerceAtLeast(0L)
                    performanceDirtyRowsTotal += dirtyRowsInFrame
                }
            }
        }

        val currentKeypadGeneration = getKeypadSnapshotGeneration()
        val shouldRefreshLabels = nowMillis - lastLabelRefresh > 500
        val keypadStateChanged = currentKeypadGeneration != lastKeypadGeneration
        if (shouldRefreshLabels || keypadStateChanged) {
            val refreshResult = refreshKeypadSnapshot(getMainKeyDynamicModeCode())
            val snapshot = refreshResult.snapshot
            if (snapshot != null) {
                onDynamicRefresh(snapshot)
                lastLabelRefresh = nowMillis
            }
            if (refreshResult.isUpToDate) {
                lastKeypadGeneration = refreshResult.observedGeneration
            }
        }

        if (isPerformanceSnapshotEnabled) {
            publishPerformanceSnapshotIfReady(nowMillis, performanceWindowMillis)
        }
    }

    private fun publishPerformanceSnapshotIfReady(nowMillis: Long, performanceWindowMillis: Long) {
        val elapsedMillis = nowMillis - performanceWindowStartMillis
        if (elapsedMillis < performanceWindowMillis) {
            return
        }

        val elapsedSeconds = elapsedMillis / 1000f
        onPerformanceSnapshot(
            DeveloperPerformanceSnapshot(
                uiFramesPerSecond = performanceFrameCount / elapsedSeconds,
                lcdUpdatesPerSecond = performanceLcdUpdateCount / elapsedSeconds,
                averageLcdUpdateMillis = if (performanceLcdUpdateCount > 0) {
                    performanceLcdUpdateNanos.toFloat() /
                        performanceLcdUpdateCount.toFloat() /
                        1_000_000f
                } else {
                    0f
                },
                lcdUpdateSamples = performanceLcdUpdateCount,
                averageDirtyRowsPercent = if (performanceLcdUpdateCount > 0) {
                    performanceDirtyRowsTotal.toFloat() * 100f /
                        (performanceLcdUpdateCount.toFloat() * R47LcdContract.PIXEL_HEIGHT.toFloat())
                } else {
                    0f
                },
            )
        )

        performanceWindowStartMillis = nowMillis
        performanceFrameCount = 0
        performanceLcdUpdateCount = 0
        performanceLcdUpdateNanos = 0L
        performanceDirtyRowsTotal = 0
    }

    private fun resetPerformanceWindow() {
        performanceWindowStartMillis = 0L
        performanceFrameCount = 0
        performanceLcdUpdateCount = 0
        performanceLcdUpdateNanos = 0L
        performanceDirtyRowsTotal = 0
    }

    private fun countDirtyRows(buffer: ByteArray): Int {
        var dirtyRows = 0
        for (bufferRow in 0 until R47LcdContract.PIXEL_HEIGHT) {
            val rowOffset = bufferRow * R47LcdContract.PACKED_ROW_SIZE_BYTES
            if ((buffer[rowOffset].toInt() and 0xFF) != 0) {
                dirtyRows += 1
            }
        }
        return dirtyRows
    }

    override fun start() {
        if (isActive) {
            return
        }

        isActive = true
        lastDisplayGeneration = Int.MIN_VALUE
        lastLabelRefresh = 0L
        lastKeypadGeneration = Int.MIN_VALUE
        lastPerformanceWindowMillis = getPerformanceWindowMillis().coerceAtLeast(1L)
        resetPerformanceWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun stop() {
        if (!isActive) {
            return
        }

        isActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }
}
