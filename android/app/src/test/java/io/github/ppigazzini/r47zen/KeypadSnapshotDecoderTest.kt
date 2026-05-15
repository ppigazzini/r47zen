package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadSnapshotDecoderTest {
    @Test
    fun fromNativeDecodesKeyboardWideAndPerKeyMetadataLanes() {
        val meta = IntArray(KeypadSnapshot.META_LENGTH)
        val labels = Array(KeypadSnapshot.KEY_COUNT * KeypadSnapshot.LABELS_PER_KEY) { "" }
        val keyIndex = 11
        val labelBase = keyIndex * KeypadSnapshot.LABELS_PER_KEY

        labels[labelBase + KeypadSceneContract.LABEL_PRIMARY] = "7"
        labels[labelBase + KeypadSceneContract.LABEL_F] = "LASTx"
        labels[labelBase + KeypadSceneContract.LABEL_G] = "STK"
        labels[labelBase + KeypadSceneContract.LABEL_LETTER] = "A"
        labels[labelBase + KeypadSceneContract.LABEL_AUX] = "menu"

        meta[META_SHIFT_F] = 1
        meta[META_SHIFT_G] = 0
        meta[META_CALC_MODE] = 3
        meta[META_USER_MODE] = 1
        meta[META_ALPHA] = 1
        meta[META_SOFTMENU_ID] = 17
        meta[META_SOFTMENU_FIRST_ITEM] = 4
        meta[META_SOFTMENU_ITEM_COUNT] = 6
        meta[META_SOFTMENU_VISIBLE_ROW] = 1
        meta[META_SOFTMENU_PAGE] = 2
        meta[META_SOFTMENU_PAGE_COUNT] = 5
        meta[META_SOFTMENU_HAS_PREVIOUS] = 1
        meta[META_SOFTMENU_HAS_NEXT] = 0
        meta[META_CONTRACT_VERSION] = KeypadSnapshot.SCENE_CONTRACT_VERSION
        meta[META_SOFTMENU_DOTTED_ROW] = 2
        meta[META_FN_PREVIEW_ACTIVE] = 1
        meta[META_FN_PREVIEW_KEY] = 38
        meta[META_FN_PREVIEW_ROW] = 0
        meta[META_FN_PREVIEW_STATE] = 9
        meta[META_FN_PREVIEW_TIMEOUT_ACTIVE] = 1
        meta[META_FN_PREVIEW_RELEASE_EXEC] = 0
        meta[META_FN_PREVIEW_NOP_OR_EXECUTED] = 1
        meta[META_KEY_ENABLED_OFFSET + keyIndex] = 1
        meta[META_STYLE_ROLE_OFFSET + keyIndex] = KeypadSceneContract.STYLE_NUMERIC
        meta[META_LABEL_ROLE_OFFSET + keyIndex] = 0x7654
        meta[META_LAYOUT_CLASS_OFFSET + keyIndex] = KeypadSceneContract.LAYOUT_CLASS_ALPHA
        meta[META_SCENE_FLAGS_OFFSET + keyIndex] =
            KeypadSceneContract.SCENE_FLAG_SHOW_TEXT or KeypadSceneContract.SCENE_FLAG_SHOW_VALUE
        meta[META_OVERLAY_STATE_OFFSET + keyIndex] = KeypadSceneContract.OVERLAY_CB_TRUE
        meta[META_SHOW_VALUE_OFFSET + keyIndex] = -12

        val snapshot = KeypadSnapshot.fromNative(meta, labels)
        val keyState = snapshot.keyStateFor(keyIndex + 1)

        assertTrue(snapshot.shiftF)
        assertFalse(snapshot.shiftG)
        assertEquals(3, snapshot.calcMode)
        assertTrue(snapshot.userModeEnabled)
        assertTrue(snapshot.alphaOn)
        assertEquals(KeypadSnapshot.SCENE_CONTRACT_VERSION, snapshot.sceneContractVersion)
        assertEquals(17, snapshot.softmenuId)
        assertEquals(4, snapshot.softmenuFirstItem)
        assertEquals(6, snapshot.softmenuItemCount)
        assertEquals(1, snapshot.softmenuVisibleRowOffset)
        assertEquals(2, snapshot.softmenuPage)
        assertEquals(5, snapshot.softmenuPageCount)
        assertTrue(snapshot.softmenuHasPreviousPage)
        assertFalse(snapshot.softmenuHasNextPage)
        assertEquals(2, snapshot.softmenuDottedRow)
        assertTrue(snapshot.functionPreviewActive)
        assertEquals(38, snapshot.functionPreviewKeyCode)
        assertEquals(0, snapshot.functionPreviewRow)
        assertEquals(9, snapshot.functionPreviewState)
        assertTrue(snapshot.functionPreviewTimeoutActive)
        assertFalse(snapshot.functionPreviewReleaseExec)
        assertTrue(snapshot.functionPreviewNopOrExecuted)
        assertEquals("7", keyState.primaryLabel)
        assertEquals("LASTx", keyState.fLabel)
        assertEquals("STK", keyState.gLabel)
        assertEquals("A", keyState.letterLabel)
        assertEquals("menu", keyState.auxLabel)
        assertTrue(keyState.isEnabled)
        assertEquals(KeypadSceneContract.STYLE_NUMERIC, keyState.styleRole)
        assertEquals(0x7654, keyState.labelRoles)
        assertEquals(KeypadSceneContract.LAYOUT_CLASS_ALPHA, keyState.layoutClass)
        assertTrue(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT))
        assertTrue(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_VALUE))
        assertEquals(KeypadSceneContract.OVERLAY_CB_TRUE, keyState.overlayState)
        assertEquals(-12, keyState.showValue)
    }

    @Test
    fun fromNativeFallsBackToBlankWhenLabelEntriesAreMissing() {
        val meta = IntArray(KeypadSnapshot.META_LENGTH)
        meta[META_CONTRACT_VERSION] = KeypadSnapshot.SCENE_CONTRACT_VERSION
        meta[META_KEY_ENABLED_OFFSET] = 1
        val labels = arrayOf("X")

        val snapshot = KeypadSnapshot.fromNative(meta, labels)
        val firstKey = snapshot.keyStateFor(1)

        assertEquals("X", firstKey.primaryLabel)
        assertEquals("", firstKey.fLabel)
        assertEquals("", firstKey.gLabel)
        assertEquals("", firstKey.letterLabel)
        assertEquals("", firstKey.auxLabel)
        assertTrue(firstKey.isEnabled)
    }

    companion object {
        private const val META_SHIFT_F = 0
        private const val META_SHIFT_G = 1
        private const val META_CALC_MODE = 2
        private const val META_USER_MODE = 3
        private const val META_ALPHA = 4
        private const val META_SOFTMENU_ID = 5
        private const val META_SOFTMENU_FIRST_ITEM = 6
        private const val META_SOFTMENU_ITEM_COUNT = 7
        private const val META_SOFTMENU_VISIBLE_ROW = 8
        private const val META_SOFTMENU_PAGE = 9
        private const val META_SOFTMENU_PAGE_COUNT = 10
        private const val META_SOFTMENU_HAS_PREVIOUS = 11
        private const val META_SOFTMENU_HAS_NEXT = 12
        private const val META_KEY_ENABLED_OFFSET = 13
        private const val META_CONTRACT_VERSION = META_KEY_ENABLED_OFFSET + KeypadSnapshot.KEY_COUNT
        private const val META_SOFTMENU_DOTTED_ROW = META_CONTRACT_VERSION + 1
        private const val META_FN_PREVIEW_ACTIVE = META_SOFTMENU_DOTTED_ROW + 1
        private const val META_FN_PREVIEW_KEY = META_FN_PREVIEW_ACTIVE + 1
        private const val META_FN_PREVIEW_ROW = META_FN_PREVIEW_KEY + 1
        private const val META_FN_PREVIEW_STATE = META_FN_PREVIEW_ROW + 1
        private const val META_FN_PREVIEW_TIMEOUT_ACTIVE = META_FN_PREVIEW_STATE + 1
        private const val META_FN_PREVIEW_RELEASE_EXEC = META_FN_PREVIEW_TIMEOUT_ACTIVE + 1
        private const val META_FN_PREVIEW_NOP_OR_EXECUTED = META_FN_PREVIEW_RELEASE_EXEC + 1
        private const val META_STYLE_ROLE_OFFSET = META_FN_PREVIEW_NOP_OR_EXECUTED + 1
        private const val META_LABEL_ROLE_OFFSET = META_STYLE_ROLE_OFFSET + KeypadSnapshot.KEY_COUNT
        private const val META_LAYOUT_CLASS_OFFSET = META_LABEL_ROLE_OFFSET + KeypadSnapshot.KEY_COUNT
        private const val META_SCENE_FLAGS_OFFSET = META_LAYOUT_CLASS_OFFSET + KeypadSnapshot.KEY_COUNT
        private const val META_OVERLAY_STATE_OFFSET = META_SCENE_FLAGS_OFFSET + KeypadSnapshot.KEY_COUNT
        private const val META_SHOW_VALUE_OFFSET = META_OVERLAY_STATE_OFFSET + KeypadSnapshot.KEY_COUNT
    }
}
