package io.github.ppigazzini.r47

internal enum class MainKeyDynamicMode(
    val storageValue: String,
    val nativeCode: Int,
) {
    ON(storageValue = "on", nativeCode = 0),
    ALPHA(storageValue = "alpha", nativeCode = 1),
    USER(storageValue = "user", nativeCode = 2),
    OFF(storageValue = "off", nativeCode = 3);

    companion object {
        val DEFAULT = ON

        fun fromStorageValue(value: String?): MainKeyDynamicMode {
            return entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        }
    }
}

internal enum class SoftkeyDynamicMode(
    val storageValue: String,
) {
    ON(storageValue = "on"),
    GRAPHIC(storageValue = "graphic"),
    OFF(storageValue = "off");

    companion object {
        val DEFAULT = ON

        fun fromStorageValue(value: String?): SoftkeyDynamicMode {
            return entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        }
    }
}

internal fun KeypadSnapshot.applyMainKeyDynamicMode(
    mode: MainKeyDynamicMode,
    userSnapshot: KeypadSnapshot? = null,
): KeypadSnapshot {
    return when (mode) {
        MainKeyDynamicMode.USER -> applyUserModeTopLabels(userSnapshot)
        else -> this
    }
}

internal fun KeypadSnapshot.applySoftkeyDynamicMode(mode: SoftkeyDynamicMode): KeypadSnapshot {
    if (mode == SoftkeyDynamicMode.ON) {
        return this
    }

    return transformKeyStates { code, keyState ->
        if (code <= 37) {
            keyState
        } else {
            keyState.withSoftkeyDynamicMode(mode)
        }
    }
}

private fun KeypadSnapshot.applyUserModeTopLabels(userSnapshot: KeypadSnapshot?): KeypadSnapshot {
    if (userSnapshot == null || !userSnapshot.userModeEnabled) {
        return this
    }

    return transformKeyStates { code, keyState ->
        if (code <= 37) {
            keyState.withUserModeTopLabels(userSnapshot.keyStateFor(code))
        } else {
            keyState
        }
    }
}

private fun KeypadKeySnapshot.withUserModeTopLabels(
    userKeyState: KeypadKeySnapshot,
): KeypadKeySnapshot {
    return copy(
        fLabel = userKeyState.fLabel,
        gLabel = userKeyState.gLabel,
        labelRoles = labelRoles
            .replaceLabelRole(
                KeypadSceneContract.LABEL_F,
                userKeyState.labelRole(KeypadSceneContract.LABEL_F),
            )
            .replaceLabelRole(
                KeypadSceneContract.LABEL_G,
                userKeyState.labelRole(KeypadSceneContract.LABEL_G),
            ),
    )
}

private fun Int.replaceLabelRole(slot: Int, role: Int): Int {
    val shift = slot * 4
    val mask = 0xF shl shift
    return (this and mask.inv()) or (role shl shift)
}

private fun KeypadKeySnapshot.withSoftkeyDynamicMode(mode: SoftkeyDynamicMode): KeypadKeySnapshot {
    return when (mode) {
        SoftkeyDynamicMode.ON -> this
        SoftkeyDynamicMode.GRAPHIC -> copy(
            primaryLabel = "",
            auxLabel = "",
            sceneFlags = sceneFlags and
                KeypadSceneContract.SCENE_FLAG_SHOW_TEXT.inv() and
                KeypadSceneContract.SCENE_FLAG_SHOW_VALUE.inv(),
            showValue = KeypadKeySnapshot.NO_VALUE,
        )

        SoftkeyDynamicMode.OFF -> copy(
            primaryLabel = "",
            auxLabel = "",
            sceneFlags = sceneFlags and
                KeypadSceneContract.SCENE_FLAG_REVERSE_VIDEO.inv() and
                KeypadSceneContract.SCENE_FLAG_SHOW_TEXT.inv() and
                KeypadSceneContract.SCENE_FLAG_SHOW_VALUE.inv() and
                KeypadSceneContract.SCENE_FLAG_SHOW_CB.inv() and
                KeypadSceneContract.SCENE_FLAG_STRIKE_OUT.inv() and
                KeypadSceneContract.SCENE_FLAG_STRIKE_THROUGH.inv() and
                KeypadSceneContract.SCENE_FLAG_PREVIEW_TARGET.inv(),
            overlayState = KeypadKeySnapshot.NO_VALUE,
            showValue = KeypadKeySnapshot.NO_VALUE,
        )
    }
}
