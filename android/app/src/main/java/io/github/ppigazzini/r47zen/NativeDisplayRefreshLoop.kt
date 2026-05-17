package io.github.ppigazzini.r47zen

import android.view.Choreographer

internal interface DisplayRefreshLoop {
    fun start()
    fun stop()
}

internal class NativeDisplayRefreshLoop(
    private val isAppRunning: () -> Boolean,
    private val isNativeInitialized: () -> Boolean,
    private val getPackedDisplayGeneration: () -> Int,
    private val getPackedDisplayBuffer: (ByteArray) -> Boolean,
    private val getKeypadSnapshotGeneration: () -> Int,
    private val getMainKeyDynamicModeCode: () -> Int,
    private val refreshKeypadSnapshot: (Int) -> NativeKeypadSnapshotRefreshResult,
    private val onPackedLcd: (ByteArray) -> Boolean,
    private val onDynamicRefresh: (KeypadSnapshot) -> Unit,
) : DisplayRefreshLoop {
    private val packedLcdBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
    private var lastDisplayGeneration = Int.MIN_VALUE
    private var lastLabelRefresh = 0L
    private var lastKeypadGeneration = Int.MIN_VALUE
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

        val currentDisplayGeneration = getPackedDisplayGeneration()
        if (currentDisplayGeneration != lastDisplayGeneration && getPackedDisplayBuffer(packedLcdBuffer)) {
            onPackedLcd(packedLcdBuffer)
            lastDisplayGeneration = currentDisplayGeneration
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
    }

    override fun start() {
        if (isActive) {
            return
        }

        isActive = true
        lastDisplayGeneration = Int.MIN_VALUE
        lastLabelRefresh = 0L
        lastKeypadGeneration = Int.MIN_VALUE
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
