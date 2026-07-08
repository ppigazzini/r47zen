package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeKeypadSnapshotStoreTest {
    @Test
    fun refresh_copiesFromNativeOnFirstObservation() {
        var copies = 0
        val store = NativeKeypadSnapshotStore(
            getKeypadSnapshotGeneration = { 1 },
            copyKeypadSnapshotNative = { _, _, _ -> copies += 1; true },
        )

        val result = store.refreshSnapshot(modeCode = 0)

        assertEquals(1, copies)
        assertTrue(result.isUpToDate)
        assertEquals(1, result.observedGeneration)
        assertNotNull(result.snapshot)
    }

    @Test
    fun refresh_reusesCacheWhileGenerationIsUnchanged() {
        var copies = 0
        val store = NativeKeypadSnapshotStore(
            getKeypadSnapshotGeneration = { 7 },
            copyKeypadSnapshotNative = { _, _, _ -> copies += 1; true },
        )

        val first = store.refreshSnapshot(modeCode = 0)
        val second = store.refreshSnapshot(modeCode = 0)

        assertEquals(1, copies)
        assertTrue(second.isUpToDate)
        assertSame(first.snapshot, second.snapshot)
    }

    @Test
    fun refresh_recopiesWhenGenerationAdvances() {
        var copies = 0
        var generation = 1
        val store = NativeKeypadSnapshotStore(
            getKeypadSnapshotGeneration = { generation },
            copyKeypadSnapshotNative = { _, _, _ -> copies += 1; true },
        )

        val first = store.refreshSnapshot(modeCode = 0)
        generation = 2
        val second = store.refreshSnapshot(modeCode = 0)

        assertEquals(2, copies)
        assertEquals(2, second.observedGeneration)
        assertTrue(first.snapshot !== second.snapshot)
    }

    @Test
    fun refresh_keepsPriorSnapshotWhenCopyFails() {
        var generation = 1
        var copySucceeds = true
        val store = NativeKeypadSnapshotStore(
            getKeypadSnapshotGeneration = { generation },
            copyKeypadSnapshotNative = { _, _, _ -> copySucceeds },
        )

        val good = store.refreshSnapshot(modeCode = 0)
        generation = 2
        copySucceeds = false
        val failed = store.refreshSnapshot(modeCode = 0)

        assertFalse(failed.isUpToDate)
        assertSame(good.snapshot, failed.snapshot)
    }

    @Test
    fun refresh_returnsNullWhenNoCacheAndCopyFails() {
        val store = NativeKeypadSnapshotStore(
            getKeypadSnapshotGeneration = { 1 },
            copyKeypadSnapshotNative = { _, _, _ -> false },
        )

        val result = store.refreshSnapshot(modeCode = 0)

        assertFalse(result.isUpToDate)
        assertNull(result.snapshot)
    }

    @Test
    fun refresh_cachesPerMode() {
        var copies = 0
        val store = NativeKeypadSnapshotStore(
            getKeypadSnapshotGeneration = { 1 },
            copyKeypadSnapshotNative = { _, _, _ -> copies += 1; true },
        )

        store.refreshSnapshot(modeCode = 0)
        store.refreshSnapshot(modeCode = 1)
        store.refreshSnapshot(modeCode = 0)

        assertEquals(2, copies)
    }
}
