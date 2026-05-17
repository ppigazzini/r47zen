package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeDisplayRefreshLoopTest {
    @Test
    fun refreshFrame_skipsPackedBufferPull_whenGenerationIsUnchanged() {
        val packedBufferPulls = AtomicInteger(0)
        val packedUpdates = AtomicInteger(0)
        val dynamicRefreshes = AtomicInteger(0)
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            getPackedDisplayGeneration = { 7 },
            getPackedDisplayBuffer = {
                packedBufferPulls.incrementAndGet()
                true
            },
            getKeypadMetaNative = { _ -> intArrayOf(1, 2, 3) },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            getKeypadSnapshot = { KeypadSnapshot.EMPTY },
            onPackedLcd = {
                packedUpdates.incrementAndGet()
                true
            },
            onDynamicRefresh = { dynamicRefreshes.incrementAndGet() },
        )

        loop.refreshFrame(nowMillis = 1_000L)
        loop.refreshFrame(nowMillis = 1_100L)

        assertEquals(1, packedBufferPulls.get())
        assertEquals(1, packedUpdates.get())
        assertEquals(1, dynamicRefreshes.get())
    }

    @Test
    fun refreshFrame_retriesSameGeneration_untilPackedBufferCopySucceeds() {
        val packedBufferPulls = AtomicInteger(0)
        val packedUpdates = AtomicInteger(0)
        var shouldCopyBuffer = false
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            getPackedDisplayGeneration = { 11 },
            getPackedDisplayBuffer = {
                packedBufferPulls.incrementAndGet()
                if (!shouldCopyBuffer) {
                    shouldCopyBuffer = true
                    false
                } else {
                    true
                }
            },
            getKeypadMetaNative = { _ -> IntArray(0) },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            getKeypadSnapshot = { KeypadSnapshot.EMPTY },
            onPackedLcd = {
                packedUpdates.incrementAndGet()
                true
            },
            onDynamicRefresh = {},
        )

        loop.refreshFrame(nowMillis = 1_000L)
        loop.refreshFrame(nowMillis = 1_100L)

        assertEquals(2, packedBufferPulls.get())
        assertEquals(1, packedUpdates.get())
    }
}
