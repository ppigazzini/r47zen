package io.github.ppigazzini.r47

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplicaOverlayControllerLabelModeTest {
    @Test
    fun dynamicModeStorageFallsBackToDefaults() {
        assertEquals(MainKeyDynamicMode.DEFAULT, MainKeyDynamicMode.fromStorageValue("bogus"))
        assertEquals(MainKeyDynamicMode.DEFAULT, MainKeyDynamicMode.fromStorageValue(null))
        assertEquals(SoftkeyDynamicMode.DEFAULT, SoftkeyDynamicMode.fromStorageValue("bogus"))
        assertEquals(SoftkeyDynamicMode.DEFAULT, SoftkeyDynamicMode.fromStorageValue(null))
    }

    @Test
    fun currentKeypadSnapshotRequestsSelectedMainKeyModeFromNative() {
        var metaMode = -1
        var labelMode = -1
        val controller = createController(
            getMeta = { mode ->
                metaMode = mode
                baseMeta()
            },
            getLabels = { mode ->
                labelMode = mode
                emptyLabels()
            },
        )

        controller.applyKeypadLabelModes(MainKeyDynamicMode.USER, SoftkeyDynamicMode.ON)
        controller.currentKeypadSnapshot()

        assertEquals(MainKeyDynamicMode.USER.nativeCode, metaMode)
        assertEquals(MainKeyDynamicMode.USER.nativeCode, labelMode)
    }

    @Test
    fun currentKeypadSnapshotRequestsEveryMainKeyModeFromNative() {
        MainKeyDynamicMode.entries.forEach { mode ->
            var metaMode = -1
            var labelMode = -1
            val controller = createController(
                getMeta = { requestedMode ->
                    metaMode = requestedMode
                    baseMeta()
                },
                getLabels = { requestedMode ->
                    labelMode = requestedMode
                    emptyLabels()
                },
            )

            controller.applyKeypadLabelModes(mode, SoftkeyDynamicMode.ON)
            controller.currentKeypadSnapshot()

            assertEquals(mode.nativeCode, metaMode)
            assertEquals(mode.nativeCode, labelMode)
        }
    }

    @Test
    fun graphicSoftkeyModeDropsTextButKeepsGraphicState() {
        val labels = emptyLabels().apply {
            this[labelIndex(38, KeypadSceneContract.LABEL_PRIMARY)] = "FILE"
            this[labelIndex(38, KeypadSceneContract.LABEL_AUX)] = "LOAD"
        }
        val controller = createController(getLabels = { labels })
        controller.applyKeypadLabelModes(MainKeyDynamicMode.ON, SoftkeyDynamicMode.GRAPHIC)

        val keyState = controller.currentKeypadSnapshot(graphicSoftkeyMeta()).keyStateFor(38)

        assertEquals("", keyState.primaryLabel)
        assertEquals("", keyState.auxLabel)
        assertTrue(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_REVERSE_VIDEO))
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT))
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_VALUE))
        assertTrue(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_CB))
        assertTrue(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_OUT))
        assertEquals(KeypadSceneContract.OVERLAY_CB_TRUE, keyState.overlayState)
        assertEquals(KeypadKeySnapshot.NO_VALUE, keyState.showValue)
    }

    @Test
    fun offSoftkeyModeRemovesDynamicGraphicFlags() {
        val labels = emptyLabels().apply {
            this[labelIndex(38, KeypadSceneContract.LABEL_PRIMARY)] = "FILE"
            this[labelIndex(38, KeypadSceneContract.LABEL_AUX)] = "LOAD"
        }
        val controller = createController(getLabels = { labels })
        controller.applyKeypadLabelModes(MainKeyDynamicMode.ON, SoftkeyDynamicMode.OFF)

        val keyState = controller.currentKeypadSnapshot(graphicSoftkeyMeta()).keyStateFor(38)

        assertEquals("", keyState.primaryLabel)
        assertEquals("", keyState.auxLabel)
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_REVERSE_VIDEO))
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT))
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_VALUE))
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_CB))
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_OUT))
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_THROUGH))
        assertFalse(keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_PREVIEW_TARGET))
        assertEquals(KeypadKeySnapshot.NO_VALUE, keyState.overlayState)
        assertEquals(KeypadKeySnapshot.NO_VALUE, keyState.showValue)
    }

    private fun createController(
        getMeta: (Int) -> IntArray = { baseMeta() },
        getLabels: (Int) -> Array<String> = { emptyLabels() },
    ): ReplicaOverlayController {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ReplicaOverlayController(
            context = context,
            overlay = ReplicaOverlay(context),
            performHapticClick = {},
            offerCoreTask = {},
            sendKey = {},
            getKeypadMetaNative = getMeta,
            getKeypadLabelsNative = getLabels,
            isRuntimeReady = { false },
        )
    }

    private fun baseMeta(): IntArray {
        return IntArray(KeypadSnapshot.META_LENGTH).apply {
            this[META_CONTRACT_VERSION] = KeypadSnapshot.SCENE_CONTRACT_VERSION
        }
    }

    private fun graphicSoftkeyMeta(): IntArray {
        return baseMeta().apply {
            this[META_KEY_ENABLED_OFFSET + 37] = 1
            this[META_LAYOUT_CLASS_OFFSET + 37] = KeypadSceneContract.LAYOUT_CLASS_SOFTKEY
            this[META_SCENE_FLAGS_OFFSET + 37] =
                KeypadSceneContract.SCENE_FLAG_REVERSE_VIDEO or
                    KeypadSceneContract.SCENE_FLAG_SHOW_TEXT or
                    KeypadSceneContract.SCENE_FLAG_SHOW_VALUE or
                    KeypadSceneContract.SCENE_FLAG_SHOW_CB or
                    KeypadSceneContract.SCENE_FLAG_STRIKE_OUT or
                    KeypadSceneContract.SCENE_FLAG_STRIKE_THROUGH or
                    KeypadSceneContract.SCENE_FLAG_PREVIEW_TARGET
            this[META_OVERLAY_STATE_OFFSET + 37] = KeypadSceneContract.OVERLAY_CB_TRUE
            this[META_SHOW_VALUE_OFFSET + 37] = 12
        }
    }

    private fun emptyLabels(): Array<String> {
        return Array(KeypadSnapshot.KEY_COUNT * KeypadSnapshot.LABELS_PER_KEY) { "" }
    }

    private fun labelIndex(code: Int, labelType: Int): Int {
        return (code - 1) * KeypadSnapshot.LABELS_PER_KEY + labelType
    }

    private companion object {
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
