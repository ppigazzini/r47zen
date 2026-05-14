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
    fun currentKeypadSnapshotRequestsExpectedNativeModes() {
        MainKeyDynamicMode.entries.forEach { mode ->
            val metaModes = mutableListOf<Int>()
            val labelModes = mutableListOf<Int>()
            val controller = createController(
                getMeta = { requestedMode ->
                    metaModes += requestedMode
                    baseMeta()
                },
                getLabels = { requestedMode ->
                    labelModes += requestedMode
                    emptyLabels()
                },
            )

            controller.applyKeypadLabelModes(mode, SoftkeyDynamicMode.ON)
            controller.currentKeypadSnapshot()

            val expectedModes = if (mode == MainKeyDynamicMode.USER) {
                listOf(MainKeyDynamicMode.USER.nativeCode, MainKeyDynamicMode.OFF.nativeCode)
            } else {
                listOf(mode.nativeCode)
            }

            assertEquals(expectedModes, metaModes)
            assertEquals(expectedModes, labelModes)
        }
    }

    @Test
    fun userModeKeepsStaticPrimaryAndOverlaysTopLabels() {
        val keyCode = 12
        val offLabels = emptyLabels().apply {
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_PRIMARY)] = "7"
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_F)] = "LASTx"
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_G)] = "STK"
        }
        val userLabels = emptyLabels().apply {
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_PRIMARY)] = "XEQ"
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_F)] = "ASSIGN"
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_G)] = "MYALPHA"
        }
        val offMeta = baseMeta(userModeEnabled = true).apply {
            setLabelRoles(
                keyCode = keyCode,
                primaryRole = KeypadSceneContract.TEXT_ROLE_PRIMARY,
                fRole = KeypadSceneContract.TEXT_ROLE_F,
                gRole = KeypadSceneContract.TEXT_ROLE_G,
            )
        }
        val userMeta = baseMeta(userModeEnabled = true).apply {
            setLabelRoles(
                keyCode = keyCode,
                primaryRole = KeypadSceneContract.TEXT_ROLE_PRIMARY,
                fRole = KeypadSceneContract.TEXT_ROLE_F_UNDERLINE,
                gRole = KeypadSceneContract.TEXT_ROLE_G_UNDERLINE,
            )
        }
        val controller = createController(
            getMeta = { mode ->
                when (mode) {
                    MainKeyDynamicMode.OFF.nativeCode -> offMeta
                    MainKeyDynamicMode.USER.nativeCode -> userMeta
                    else -> baseMeta()
                }
            },
            getLabels = { mode ->
                when (mode) {
                    MainKeyDynamicMode.OFF.nativeCode -> offLabels
                    MainKeyDynamicMode.USER.nativeCode -> userLabels
                    else -> emptyLabels()
                }
            },
        )

        controller.applyKeypadLabelModes(MainKeyDynamicMode.USER, SoftkeyDynamicMode.ON)

        val keyState = controller.currentKeypadSnapshot().keyStateFor(keyCode)

        assertEquals("7", keyState.primaryLabel)
        assertEquals("ASSIGN", keyState.fLabel)
        assertEquals("MYALPHA", keyState.gLabel)
        assertEquals(
            KeypadSceneContract.TEXT_ROLE_PRIMARY,
            keyState.labelRole(KeypadSceneContract.LABEL_PRIMARY),
        )
        assertEquals(
            KeypadSceneContract.TEXT_ROLE_F_UNDERLINE,
            keyState.labelRole(KeypadSceneContract.LABEL_F),
        )
        assertEquals(
            KeypadSceneContract.TEXT_ROLE_G_UNDERLINE,
            keyState.labelRole(KeypadSceneContract.LABEL_G),
        )
    }

    @Test
    fun userTopLabelOverlayStaysOffOutsideUserMode() {
        val keyCode = 12
        val offLabels = emptyLabels().apply {
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_PRIMARY)] = "7"
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_F)] = "LASTx"
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_G)] = "STK"
        }
        val userLabels = emptyLabels().apply {
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_PRIMARY)] = "XEQ"
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_F)] = "ASSIGN"
            this[labelIndex(keyCode, KeypadSceneContract.LABEL_G)] = "MYALPHA"
        }
        val offMeta = baseMeta(userModeEnabled = false)
        val userMeta = baseMeta(userModeEnabled = false)
        val controller = createController(
            getMeta = { mode ->
                when (mode) {
                    MainKeyDynamicMode.OFF.nativeCode -> offMeta
                    MainKeyDynamicMode.USER.nativeCode -> userMeta
                    else -> baseMeta()
                }
            },
            getLabels = { mode ->
                when (mode) {
                    MainKeyDynamicMode.OFF.nativeCode -> offLabels
                    MainKeyDynamicMode.USER.nativeCode -> userLabels
                    else -> emptyLabels()
                }
            },
        )

        controller.applyKeypadLabelModes(MainKeyDynamicMode.USER, SoftkeyDynamicMode.ON)

        val keyState = controller.currentKeypadSnapshot().keyStateFor(keyCode)

        assertEquals("7", keyState.primaryLabel)
        assertEquals("LASTx", keyState.fLabel)
        assertEquals("STK", keyState.gLabel)
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

    @Test
    fun virtuosoModeKeepsStaticKeycapsAndBlanksRenderedKeyContent() {
        val mainKeyCode = 12
        val labels = emptyLabels().apply {
            this[labelIndex(mainKeyCode, KeypadSceneContract.LABEL_PRIMARY)] = "7"
            this[labelIndex(mainKeyCode, KeypadSceneContract.LABEL_F)] = "LASTx"
            this[labelIndex(mainKeyCode, KeypadSceneContract.LABEL_G)] = "STK"
            this[labelIndex(mainKeyCode, KeypadSceneContract.LABEL_LETTER)] = "A"
            this[labelIndex(38, KeypadSceneContract.LABEL_PRIMARY)] = "FILE"
            this[labelIndex(38, KeypadSceneContract.LABEL_AUX)] = "LOAD"
        }
        val meta = graphicSoftkeyMeta().apply {
            this[META_KEY_ENABLED_OFFSET + mainKeyCode - 1] = 1
            this[META_STYLE_ROLE_OFFSET + mainKeyCode - 1] = KeypadSceneContract.STYLE_SHIFT_F
            this[META_LABEL_ROLE_OFFSET + mainKeyCode - 1] =
                packLabelRole(KeypadSceneContract.LABEL_PRIMARY, KeypadSceneContract.TEXT_ROLE_PRIMARY) or
                    packLabelRole(KeypadSceneContract.LABEL_F, KeypadSceneContract.TEXT_ROLE_F) or
                    packLabelRole(KeypadSceneContract.LABEL_G, KeypadSceneContract.TEXT_ROLE_G) or
                    packLabelRole(KeypadSceneContract.LABEL_LETTER, KeypadSceneContract.TEXT_ROLE_LETTER)
        }
        val controller = createController(
            getMeta = { mode ->
                when (mode) {
                    MainKeyDynamicMode.OFF.nativeCode -> meta
                    else -> baseMeta()
                }
            },
            getLabels = { mode ->
                when (mode) {
                    MainKeyDynamicMode.OFF.nativeCode -> labels
                    else -> emptyLabels()
                }
            },
        )

        controller.applyKeypadLabelModes(MainKeyDynamicMode.VIRTUOSO, SoftkeyDynamicMode.ON)

        val snapshot = controller.currentKeypadSnapshot()
        val mainKeyState = snapshot.keyStateFor(mainKeyCode)
        val softkeyState = snapshot.keyStateFor(38)

        assertTrue(mainKeyState.isEnabled)
        assertEquals(KeypadSceneContract.STYLE_SHIFT_F, mainKeyState.styleRole)
        assertEquals("", mainKeyState.primaryLabel)
        assertEquals("", mainKeyState.fLabel)
        assertEquals("", mainKeyState.gLabel)
        assertEquals("", mainKeyState.letterLabel)
        assertEquals(KeypadSceneContract.TEXT_ROLE_NONE, mainKeyState.labelRole(KeypadSceneContract.LABEL_PRIMARY))
        assertEquals(KeypadSceneContract.TEXT_ROLE_NONE, mainKeyState.labelRole(KeypadSceneContract.LABEL_F))
        assertEquals(KeypadSceneContract.TEXT_ROLE_NONE, mainKeyState.labelRole(KeypadSceneContract.LABEL_G))
        assertEquals(KeypadSceneContract.TEXT_ROLE_NONE, mainKeyState.labelRole(KeypadSceneContract.LABEL_LETTER))

        assertEquals("", softkeyState.primaryLabel)
        assertEquals("", softkeyState.auxLabel)
        assertFalse(softkeyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_REVERSE_VIDEO))
        assertFalse(softkeyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT))
        assertFalse(softkeyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_VALUE))
        assertFalse(softkeyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_CB))
        assertFalse(softkeyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_OUT))
        assertFalse(softkeyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_STRIKE_THROUGH))
        assertFalse(softkeyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_PREVIEW_TARGET))
        assertEquals(KeypadKeySnapshot.NO_VALUE, softkeyState.overlayState)
        assertEquals(KeypadKeySnapshot.NO_VALUE, softkeyState.showValue)
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

    private fun baseMeta(userModeEnabled: Boolean = false): IntArray {
        return IntArray(KeypadSnapshot.META_LENGTH).apply {
            this[META_CONTRACT_VERSION] = KeypadSnapshot.SCENE_CONTRACT_VERSION
            this[META_USER_MODE] = if (userModeEnabled) 1 else 0
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

    private fun IntArray.setLabelRoles(
        keyCode: Int,
        primaryRole: Int,
        fRole: Int,
        gRole: Int,
    ) {
        this[META_KEY_ENABLED_OFFSET + keyCode - 1] = 1
        this[META_LABEL_ROLE_OFFSET + keyCode - 1] =
            packLabelRole(KeypadSceneContract.LABEL_PRIMARY, primaryRole) or
                packLabelRole(KeypadSceneContract.LABEL_F, fRole) or
                packLabelRole(KeypadSceneContract.LABEL_G, gRole)
    }

    private fun packLabelRole(slot: Int, role: Int): Int {
        return role shl (slot * 4)
    }

    private companion object {
        private const val META_USER_MODE = 3
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
