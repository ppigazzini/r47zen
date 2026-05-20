package com.example.r47

internal class ReplicaOverlayController(
    private val activity: MainActivity,
    private val overlay: ReplicaOverlay,
    private val performHapticClick: () -> Unit,
    private val offerCoreTask: (Runnable) -> Unit,
    private val sendKey: (Int) -> Unit,
    private val getKeypadMetaNative: (Boolean) -> IntArray,
    private val getKeypadLabelsNative: (Boolean) -> Array<String>,
    private val isRuntimeReady: () -> Boolean,
) {
    fun bindOverlay() {
        overlay.onPiPKeyEvent = { code ->
            offerCoreTask(Runnable { sendKey(code) })
        }
    }

    fun normalizeChromeMode(mode: String?): String {
        return when {
            mode == null -> MainActivityPreferenceController.DEFAULT_CHROME_MODE
            mode == ReplicaOverlay.CHROME_MODE_NATIVE ||
                mode == ReplicaOverlay.CHROME_MODE_TEXTURE ||
                mode == ReplicaOverlay.CHROME_MODE_BACKGROUND -> mode

            else -> MainActivityPreferenceController.DEFAULT_CHROME_MODE
        }
    }

    fun applyChromeMode(mode: String) {
        overlay.setChromeMode(mode)
        rebuildInteractiveZones(mode)
        if (isRuntimeReady() && mode != ReplicaOverlay.CHROME_MODE_TEXTURE) {
            refreshDynamicKeys()
        }
    }

    fun currentKeypadSnapshot(meta: IntArray? = null): KeypadSnapshot {
        val resolvedMeta = meta ?: getKeypadMetaNative(true)
        return KeypadSnapshot.fromNative(
            resolvedMeta,
            getKeypadLabelsNative(true),
        )
    }

    fun refreshDynamicKeys(snapshot: KeypadSnapshot? = null) {
        val resolvedSnapshot = snapshot ?: currentKeypadSnapshot()
        ReplicaKeypadLayout.updateDynamicKeys(overlay, resolvedSnapshot)
    }

    private fun rebuildInteractiveZones(chromeMode: String) {
        ReplicaKeypadLayout.rebuild(
            activity = activity,
            overlay = overlay,
            chromeMode = chromeMode,
            performHapticClick = performHapticClick,
            dispatchKey = ::dispatchKey,
            initialSnapshotProvider = { currentKeypadSnapshot() },
        )
    }

    private fun dispatchKey(keyCode: Int) {
        offerCoreTask(Runnable { sendKey(keyCode) })
    }
}