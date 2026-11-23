package io.github.ppigazzini.r47zen

internal enum class MainKeyDynamicMode(
    val storageValue: String,
    val nativeCode: Int,
) {
    ON(storageValue = "on", nativeCode = 0),
    ALPHA(storageValue = "alpha", nativeCode = 1),
    USER(storageValue = "user", nativeCode = 2),
    OFF(storageValue = "off", nativeCode = 3),
    VIRTUOSO(storageValue = "virtuoso", nativeCode = OFF.nativeCode);

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

// How a main-key f or g label that opens a menu is marked. The native scene
// says which labels open a menu; the style only picks the shape. A boxed style
// widens its label, so a change to or from one lays the keypad out again.
// Settings lists the styles in this order: the default, then the rest by name.
internal enum class MenuLabelMarkerStyle(
    val storageValue: String,
    val boxed: Boolean,
) {
    ROUNDRECT(storageValue = "roundrect", boxed = true),
    REVERSE(storageValue = "reverse", boxed = true),
    UNDERLINE(storageValue = "underline", boxed = false);

    companion object {
        val DEFAULT = ROUNDRECT

        fun fromStorageValue(value: String?): MenuLabelMarkerStyle {
            return entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        }
    }
}

internal fun KeypadSnapshot.applyMainKeyDynamicMode(mode: MainKeyDynamicMode): KeypadSnapshot {
    return when (mode) {
        MainKeyDynamicMode.USER -> this
        MainKeyDynamicMode.VIRTUOSO -> applyVirtuosoBlankKeycaps()
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

private fun KeypadSnapshot.applyVirtuosoBlankKeycaps(): KeypadSnapshot {
    return transformKeyStates { code, keyState ->
        if (code <= 37) {
            keyState.withVirtuosoMainKeyMode()
        } else {
            keyState.withSoftkeyDynamicMode(SoftkeyDynamicMode.OFF)
        }
    }
}

private fun KeypadKeySnapshot.withVirtuosoMainKeyMode(): KeypadKeySnapshot {
    return copy(
        spokenLabel = spokenLabel ?: KeySpeech.mainKey(this),
        primaryLabel = "",
        fLabel = "",
        gLabel = "",
        letterLabel = "",
        auxLabel = "",
        labelRoles = 0,
        overlayState = KeypadKeySnapshot.NO_VALUE,
        showValue = KeypadKeySnapshot.NO_VALUE,
    )
}

private fun KeypadKeySnapshot.withSoftkeyDynamicMode(mode: SoftkeyDynamicMode): KeypadKeySnapshot {
    return when (mode) {
        SoftkeyDynamicMode.ON -> this
        SoftkeyDynamicMode.GRAPHIC -> copy(
            spokenLabel = spokenLabel ?: spokenSoftkey(),
            primaryLabel = "",
            auxLabel = "",
            sceneFlags = sceneFlags and
                KeypadSceneContract.SCENE_FLAG_SHOW_TEXT.inv() and
                KeypadSceneContract.SCENE_FLAG_SHOW_VALUE.inv(),
            showValue = KeypadKeySnapshot.NO_VALUE,
        )

        SoftkeyDynamicMode.OFF -> copy(
            spokenLabel = spokenLabel ?: spokenSoftkey(),
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

private fun KeypadKeySnapshot.spokenSoftkey(): String? {
    val aux = auxLabel.takeIf { hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT) }
    return KeySpeech.softkey(primaryLabel, aux).ifBlank { null }
}
