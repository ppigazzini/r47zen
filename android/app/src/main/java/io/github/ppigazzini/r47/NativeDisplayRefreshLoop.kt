package io.github.ppigazzini.r47

import android.view.Choreographer

internal interface DisplayRefreshLoop {
    fun start()
    fun stop()
}

internal class NativeDisplayRefreshLoop(
    private val isAppRunning: () -> Boolean,
    private val isNativeInitialized: () -> Boolean,
    private val getPackedDisplayBuffer: (ByteArray) -> Unit,
    private val getKeypadMetaNative: (Int) -> IntArray,
    private val getMainKeyDynamicModeCode: () -> Int,
    private val getKeypadSnapshot: (IntArray) -> KeypadSnapshot,
    private val onPackedLcd: (ByteArray) -> Boolean,
    private val onDynamicRefresh: (KeypadSnapshot) -> Unit,
) : DisplayRefreshLoop {
    private val packedLcdBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
    private var lastLabelRefresh = 0L
    private var lastKeypadMeta = IntArray(0)
    private var isActive = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isActive || !isAppRunning()) {
                return
            }

            if (isNativeInitialized()) {
                getPackedDisplayBuffer(packedLcdBuffer)
                onPackedLcd(packedLcdBuffer)

                val currentMeta = getKeypadMetaNative(getMainKeyDynamicModeCode())
                val now = System.currentTimeMillis()
                val shouldRefreshLabels = now - lastLabelRefresh > 500
                val keypadStateChanged = !lastKeypadMeta.contentEquals(currentMeta)
                if (shouldRefreshLabels || keypadStateChanged) {
                    lastKeypadMeta = currentMeta.copyOf()
                    onDynamicRefresh(getKeypadSnapshot(currentMeta))
                    lastLabelRefresh = now
                }
            }

            if (isActive && isAppRunning()) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun start() {
        if (isActive) {
            return
        }

        isActive = true
        lastLabelRefresh = 0L
        lastKeypadMeta = IntArray(0)
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
