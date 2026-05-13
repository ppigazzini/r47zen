package io.github.ppigazzini.r47

import android.content.Context

internal object KeypadRefreshPolicy {
    const val ENABLE_UNCHANGED_SNAPSHOT_SKIP = true
}

internal class KeypadSnapshotRefreshGate {
    private var lastAppliedSnapshot: KeypadSnapshot? = null

    constructor(
        enabled: Boolean = KeypadRefreshPolicy.ENABLE_UNCHANGED_SNAPSHOT_SKIP,
    ) {
        this.enabled = enabled
    }

    private val enabled: Boolean

    fun reset() {
        lastAppliedSnapshot = null
    }

    fun shouldApply(snapshot: KeypadSnapshot): Boolean {
        if (!enabled) {
            return true
        }
        if (lastAppliedSnapshot == snapshot) {
            return false
        }
        lastAppliedSnapshot = snapshot
        return true
    }
}

internal class ReplicaOverlayController(
    private val context: Context,
    private val overlay: ReplicaOverlay,
    private val performHapticClick: () -> Unit,
    private val offerCoreTask: (Runnable) -> Unit,
    private val sendKey: (Int) -> Unit,
    private val getKeypadMetaNative: (Int) -> IntArray,
    private val getKeypadLabelsNative: (Int) -> Array<String>,
    private val isRuntimeReady: () -> Boolean,
    private val refreshGate: KeypadSnapshotRefreshGate = KeypadSnapshotRefreshGate(),
) {
    private var mainKeyDynamicMode = MainKeyDynamicMode.DEFAULT
    private var softkeyDynamicMode = SoftkeyDynamicMode.DEFAULT
    private var pendingGeometrySceneReplay = false
    private var geometrySceneReplayPosted = false

    fun bindOverlay() {
        overlay.onPiPKeyEvent = { code ->
            offerCoreTask(Runnable { sendKey(code) })
        }
        overlay.onGeometryLaidOut = {
            ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay)
            schedulePendingGeometrySceneReplay()
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
        markGeometryChange()
    }

    fun applyScalingMode(mode: String) {
        overlay.setScalingMode(mode)
        markGeometryChange()
    }

    fun handlePictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        overlay.setPiPMode(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            markGeometryChange()
        }
    }

    fun currentMainKeyDynamicModeCode(): Int {
        return mainKeyDynamicMode.nativeCode
    }

    fun applyKeypadLabelModes(
        mainKeyDynamicMode: MainKeyDynamicMode,
        softkeyDynamicMode: SoftkeyDynamicMode,
    ) {
        val changed =
            this.mainKeyDynamicMode != mainKeyDynamicMode ||
                this.softkeyDynamicMode != softkeyDynamicMode
        if (!changed) {
            return
        }

        this.mainKeyDynamicMode = mainKeyDynamicMode
        this.softkeyDynamicMode = softkeyDynamicMode
        refreshGate.reset()

        if (!isRuntimeReady() || overlay.childCount == 0) {
            return
        }

        refreshDynamicKeys(forceApply = true)
    }

    fun currentKeypadSnapshot(meta: IntArray? = null): KeypadSnapshot {
        val resolvedMeta = meta ?: getKeypadMetaNative(mainKeyDynamicMode.nativeCode)
        return KeypadSnapshot
            .fromNative(resolvedMeta, getKeypadLabelsNative(mainKeyDynamicMode.nativeCode))
            .applySoftkeyDynamicMode(softkeyDynamicMode)
    }

    fun refreshDynamicKeys(snapshot: KeypadSnapshot? = null) {
        refreshDynamicKeys(snapshot = snapshot, forceApply = false)
    }

    fun refreshDynamicKeys(snapshot: KeypadSnapshot? = null, forceApply: Boolean = false) {
        val resolvedSnapshot = snapshot ?: currentKeypadSnapshot()
        if (!forceApply && !refreshGate.shouldApply(resolvedSnapshot)) {
            return
        }
        if (forceApply) {
            refreshGate.reset()
        }
        ReplicaKeypadLayout.updateDynamicKeys(overlay, resolvedSnapshot)
    }

    fun onHostResumed() {
        if (!pendingGeometrySceneReplay) {
            return
        }

        overlay.requestLayout()
        overlay.invalidate()
    }

    private fun rebuildInteractiveZones(chromeMode: String) {
        refreshGate.reset()
        ReplicaKeypadLayout.rebuild(
            context = context,
            overlay = overlay,
            chromeMode = chromeMode,
            performHapticClick = performHapticClick,
            dispatchKey = ::dispatchKey,
            initialSnapshotProvider = { currentKeypadSnapshot() },
        )
    }

    private fun markGeometryChange() {
        pendingGeometrySceneReplay = true
        refreshGate.reset()
    }

    private fun schedulePendingGeometrySceneReplay() {
        if (!pendingGeometrySceneReplay || geometrySceneReplayPosted) {
            return
        }

        geometrySceneReplayPosted = true
        overlay.post {
            geometrySceneReplayPosted = false
            if (!pendingGeometrySceneReplay || !isRuntimeReady()) {
                return@post
            }

            val snapshot = currentKeypadSnapshot()
            if (snapshot.sceneContractVersion <= 0) {
                return@post
            }

            pendingGeometrySceneReplay = false
            refreshDynamicKeys(snapshot = snapshot, forceApply = true)
        }
    }

    private fun dispatchKey(keyCode: Int) {
        offerCoreTask(Runnable { sendKey(keyCode) })
    }
}
