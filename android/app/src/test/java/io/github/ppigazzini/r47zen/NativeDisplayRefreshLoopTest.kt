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
    fun refreshFrame_publishesDeveloperPerformanceSnapshotAcrossRecentWindow() {
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val performanceSnapshots = mutableListOf<DeveloperPerformanceSnapshot>()
        var nextDisplayGeneration = 1
        val measureTimesNanos = longArrayOf(
            100_000_000L,
            100_400_000L,
            200_000_000L,
            200_400_000L,
            300_000_000L,
            300_400_000L,
        )
        var measureIndex = 0
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            getPackedDisplayGeneration = { nextDisplayGeneration++ },
            getPackedDisplayBuffer = { true },
            getKeypadSnapshotGeneration = { 3 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                NativeKeypadSnapshotRefreshResult(
                    observedGeneration = 3,
                    snapshot = snapshot,
                    isUpToDate = true,
                )
            },
            onPackedLcd = { true },
            onDynamicRefresh = {},
            onPerformanceSnapshot = { performanceSnapshots += it },
            measureNanos = { measureTimesNanos[measureIndex++] },
        )

        loop.refreshFrame(nowMillis = 1_000L)
        loop.refreshFrame(nowMillis = 1_250L)
        loop.refreshFrame(nowMillis = 1_500L)

        assertEquals(1, performanceSnapshots.size)
        val performanceSnapshot = performanceSnapshots.single()
        assertEquals(6.0f, performanceSnapshot.uiFramesPerSecond)
        assertEquals(6.0f, performanceSnapshot.lcdUpdatesPerSecond)
        assertEquals(0.4f, performanceSnapshot.averageLcdUpdateMillis)
        assertEquals(3, performanceSnapshot.lcdUpdateSamples)
        assertEquals(
            "UI 6.0 Hz | LCD 6.0 Hz | DR 0% | Copy 0.4 ms",
            performanceSnapshot.overlayLabel(),
        )
    }

    @Test
    fun refreshFrame_usesConfiguredPerformanceWindowMillis() {
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val performanceSnapshots = mutableListOf<DeveloperPerformanceSnapshot>()
        var nextDisplayGeneration = 1
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            getPerformanceWindowMillis = { 1_000L },
            getPackedDisplayGeneration = { nextDisplayGeneration++ },
            getPackedDisplayBuffer = { true },
            getKeypadSnapshotGeneration = { 3 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                NativeKeypadSnapshotRefreshResult(
                    observedGeneration = 3,
                    snapshot = snapshot,
                    isUpToDate = true,
                )
            },
            onPackedLcd = { true },
            onDynamicRefresh = {},
            onPerformanceSnapshot = { performanceSnapshots += it },
        )

        loop.refreshFrame(nowMillis = 1_000L)
        loop.refreshFrame(nowMillis = 1_500L)

        assertEquals(0, performanceSnapshots.size)

        loop.refreshFrame(nowMillis = 2_000L)

        assertEquals(1, performanceSnapshots.size)
        assertEquals(3.0f, performanceSnapshots.single().uiFramesPerSecond)
    }

    @Test
    fun refreshFrame_resetsPerformanceWindowWhenConfiguredWindowChanges() {
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val performanceSnapshots = mutableListOf<DeveloperPerformanceSnapshot>()
        var nextDisplayGeneration = 1
        var performanceWindowMillis = 1_000L
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            getPerformanceWindowMillis = { performanceWindowMillis },
            getPackedDisplayGeneration = { nextDisplayGeneration++ },
            getPackedDisplayBuffer = { true },
            getKeypadSnapshotGeneration = { 3 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                NativeKeypadSnapshotRefreshResult(
                    observedGeneration = 3,
                    snapshot = snapshot,
                    isUpToDate = true,
                )
            },
            onPackedLcd = { true },
            onDynamicRefresh = {},
            onPerformanceSnapshot = { performanceSnapshots += it },
        )

        loop.refreshFrame(nowMillis = 1_000L)
        loop.refreshFrame(nowMillis = 1_500L)

        performanceWindowMillis = 250L
        loop.refreshFrame(nowMillis = 1_600L)

        assertEquals(0, performanceSnapshots.size)

        loop.refreshFrame(nowMillis = 1_850L)

        assertEquals(1, performanceSnapshots.size)
        assertEquals(8.0f, performanceSnapshots.single().uiFramesPerSecond)
    }

    @Test
    fun refreshFrame_reportsAverageDirtyRowPercentageAcrossWindow() {
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val performanceSnapshots = mutableListOf<DeveloperPerformanceSnapshot>()
        var nextDisplayGeneration = 1
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            getPackedDisplayGeneration = { nextDisplayGeneration++ },
            getPackedDisplayBuffer = { packedBuffer ->
                packedBuffer.fill(0)
                packedBuffer[0] = 1
                packedBuffer[R47LcdContract.PACKED_ROW_SIZE_BYTES] = 1
                true
            },
            getKeypadSnapshotGeneration = { 3 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                NativeKeypadSnapshotRefreshResult(
                    observedGeneration = 3,
                    snapshot = snapshot,
                    isUpToDate = true,
                )
            },
            onPackedLcd = { true },
            onDynamicRefresh = {},
            onPerformanceSnapshot = { performanceSnapshots += it },
        )

        loop.refreshFrame(nowMillis = 1_000L)
        loop.refreshFrame(nowMillis = 1_260L)
        loop.refreshFrame(nowMillis = 1_520L)

        assertEquals(1, performanceSnapshots.size)
        val performanceSnapshot = performanceSnapshots.single()
        assertEquals(2f * 100f / 240f, performanceSnapshot.averageDirtyRowsPercent, 0.001f)
        assertEquals("Copy --", DeveloperPerformanceSnapshot.EMPTY.overlayLabel().takeLast(7))
        assertEquals("DR 1%", performanceSnapshot.overlayLabel().split(" | ")[2])
    }

    @Test
    fun refreshFrame_skipsPerformanceSampling_whenHudIsDisabled() {
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val performanceSnapshots = mutableListOf<DeveloperPerformanceSnapshot>()
        val measureCalls = AtomicInteger(0)
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            isPerformanceSnapshotEnabled = { false },
            getPackedDisplayGeneration = { 1 },
            getPackedDisplayBuffer = { true },
            getKeypadSnapshotGeneration = { 3 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                NativeKeypadSnapshotRefreshResult(
                    observedGeneration = 3,
                    snapshot = snapshot,
                    isUpToDate = true,
                )
            },
            onPackedLcd = { true },
            onDynamicRefresh = {},
            onPerformanceSnapshot = { performanceSnapshots += it },
            measureNanos = {
                measureCalls.incrementAndGet()
                100_000_000L
            },
        )

        loop.refreshFrame(nowMillis = 1_000L)
        loop.refreshFrame(nowMillis = 1_500L)

        assertEquals(0, performanceSnapshots.size)
        assertEquals(0, measureCalls.get())
    }

    @Test
    fun refreshFrame_skipsPackedBufferPull_whenGenerationIsUnchanged() {
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
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
            getKeypadSnapshotGeneration = { 3 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                NativeKeypadSnapshotRefreshResult(
                    observedGeneration = 3,
                    snapshot = snapshot,
                    isUpToDate = true,
                )
            },
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
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
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
            getKeypadSnapshotGeneration = { 5 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                NativeKeypadSnapshotRefreshResult(
                    observedGeneration = 5,
                    snapshot = snapshot,
                    isUpToDate = true,
                )
            },
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

    @Test
    fun refreshFrame_reusesLastAcceptedKeypadSnapshot_whenBusy() {
        val initialSnapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val updatedSnapshot = KeypadFixtureResources.load("default-keypad").snapshot()
        val emittedSnapshots = mutableListOf<KeypadSnapshot>()
        var keypadGeneration = 9
        var isBusy = false
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            getPackedDisplayGeneration = { 0 },
            getPackedDisplayBuffer = { false },
            getKeypadSnapshotGeneration = { keypadGeneration },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                when (keypadGeneration) {
                    9 -> NativeKeypadSnapshotRefreshResult(
                        observedGeneration = 9,
                        snapshot = initialSnapshot,
                        isUpToDate = true,
                    )

                    10 -> {
                        if (isBusy) {
                            NativeKeypadSnapshotRefreshResult(
                                observedGeneration = 10,
                                snapshot = initialSnapshot,
                                isUpToDate = false,
                            )
                        } else {
                            NativeKeypadSnapshotRefreshResult(
                                observedGeneration = 10,
                                snapshot = updatedSnapshot,
                                isUpToDate = true,
                            )
                        }
                    }

                    else -> error("unexpected generation $keypadGeneration")
                }
            },
            onPackedLcd = { false },
            onDynamicRefresh = { emittedSnapshots += it },
        )

        loop.refreshFrame(nowMillis = 1_000L)
        keypadGeneration = 10
        isBusy = true
        loop.refreshFrame(nowMillis = 1_100L)
        isBusy = false
        loop.refreshFrame(nowMillis = 1_200L)

        assertEquals(listOf(initialSnapshot, initialSnapshot, updatedSnapshot), emittedSnapshots)
    }

    @Test
    fun refreshFrame_skipsSyntheticEmptyKeypadSnapshot_untilFirstCopySucceeds() {
        val snapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val dynamicRefreshes = AtomicInteger(0)
        var shouldCopySnapshot = false
        val loop = NativeDisplayRefreshLoop(
            isAppRunning = { true },
            isNativeInitialized = { true },
            getPackedDisplayGeneration = { 0 },
            getPackedDisplayBuffer = { false },
            getKeypadSnapshotGeneration = { 4 },
            getMainKeyDynamicModeCode = { MainKeyDynamicMode.DEFAULT.nativeCode },
            refreshKeypadSnapshot = {
                if (!shouldCopySnapshot) {
                    NativeKeypadSnapshotRefreshResult(
                        observedGeneration = 4,
                        snapshot = null,
                        isUpToDate = false,
                    )
                } else {
                    NativeKeypadSnapshotRefreshResult(
                        observedGeneration = 4,
                        snapshot = snapshot,
                        isUpToDate = true,
                    )
                }
            },
            onPackedLcd = { false },
            onDynamicRefresh = { dynamicRefreshes.incrementAndGet() },
        )

        loop.refreshFrame(nowMillis = 1_000L)
        assertEquals(0, dynamicRefreshes.get())

        shouldCopySnapshot = true
        loop.refreshFrame(nowMillis = 1_100L)

        assertEquals(1, dynamicRefreshes.get())
    }
}
