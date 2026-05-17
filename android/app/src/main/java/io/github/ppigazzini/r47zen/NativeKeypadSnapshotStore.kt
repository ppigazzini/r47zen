package io.github.ppigazzini.r47zen

internal data class NativeKeypadSnapshotRefreshResult(
    val observedGeneration: Int,
    val snapshot: KeypadSnapshot?,
    val isUpToDate: Boolean,
)

internal class NativeKeypadSnapshotStore(
    private val getKeypadSnapshotGeneration: () -> Int,
    private val copyKeypadSnapshotNative: (Int, IntArray, Array<String>) -> Boolean,
) {
    private data class CachedSnapshot(
        val generation: Int,
        val snapshot: KeypadSnapshot,
    )

    private val metaBuffer = IntArray(KeypadSnapshot.META_LENGTH)
    private val labelBuffer = Array(KeypadSnapshot.KEY_COUNT * KeypadSnapshot.LABELS_PER_KEY) { "" }
    private val cachedSnapshots = mutableMapOf<Int, CachedSnapshot>()

    fun snapshotForMode(modeCode: Int): KeypadSnapshot? {
        return refreshSnapshot(modeCode).snapshot
    }

    fun refreshSnapshot(modeCode: Int): NativeKeypadSnapshotRefreshResult {
        val observedGeneration = getKeypadSnapshotGeneration()
        val cachedSnapshot = cachedSnapshots[modeCode]
        if (cachedSnapshot != null && cachedSnapshot.generation == observedGeneration) {
            return NativeKeypadSnapshotRefreshResult(
                observedGeneration = observedGeneration,
                snapshot = cachedSnapshot.snapshot,
                isUpToDate = true,
            )
        }

        if (!copyKeypadSnapshotNative(modeCode, metaBuffer, labelBuffer)) {
            return NativeKeypadSnapshotRefreshResult(
                observedGeneration = observedGeneration,
                snapshot = cachedSnapshot?.snapshot,
                isUpToDate = false,
            )
        }

        val acceptedSnapshot = KeypadSnapshot.fromNative(metaBuffer, labelBuffer)
        cachedSnapshots[modeCode] = CachedSnapshot(observedGeneration, acceptedSnapshot)
        return NativeKeypadSnapshotRefreshResult(
            observedGeneration = observedGeneration,
            snapshot = acceptedSnapshot,
            isUpToDate = true,
        )
    }
}
