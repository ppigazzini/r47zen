package io.github.ppigazzini.r47zen

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalculatorSoftkeyPainterContractTest {
    @Test
    fun softkeyContentDescriptionIncludesAuxAndValueFromSceneContract() {
        val view = createSoftkeyView(38)
        val snapshot = snapshotWith(
            38 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "FILE",
                auxLabel = "LOAD",
                sceneFlags = KeypadSceneContract.SCENE_FLAG_SHOW_TEXT or KeypadSceneContract.SCENE_FLAG_SHOW_VALUE,
                showValue = 12,
                isEnabled = true,
            ),
        )

        view.updateLabels(snapshot)

        assertEquals("FILE, LOAD, 12", view.contentDescription.toString())
    }

    @Test
    fun softkeyContentDescriptionSuppressesAuxWhenShowTextIsOff() {
        val view = createSoftkeyView(39)
        val snapshot = snapshotWith(
            39 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "MODE",
                auxLabel = "HIDDEN",
                sceneFlags = 0,
                showValue = KeypadKeySnapshot.NO_VALUE,
                isEnabled = true,
            ),
        )

        view.updateLabels(snapshot)

        assertEquals("MODE", view.contentDescription.toString())
    }

    @Test
    fun blankSoftkeyContentDescriptionStaysEmpty() {
        val view = createSoftkeyView(40)
        val snapshot = snapshotWith(
            40 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "",
                auxLabel = "",
                sceneFlags = 0,
                showValue = KeypadKeySnapshot.NO_VALUE,
                isEnabled = true,
            ),
        )

        view.updateLabels(snapshot)

        assertEquals("", view.contentDescription.toString())
    }

    private fun createSoftkeyView(code: Int): CalculatorKeyView {
        return CalculatorKeyView(ApplicationProvider.getApplicationContext()).apply {
            setKey(
                KeypadTopology.slotFor(code),
                KeypadFontSet(standard = null, numeric = null, tiny = null),
            )
        }
    }

    private fun snapshotWith(vararg states: Pair<Int, KeypadKeySnapshot>): KeypadSnapshot {
        val keyStates = MutableList(KeypadSnapshot.KEY_COUNT) { KeypadKeySnapshot.EMPTY }
        states.forEach { (code, keyState) ->
            keyStates[code - 1] = keyState
        }

        return KeypadSnapshot(
            keyboardState = KeyboardStateSnapshot.EMPTY,
            sceneContractVersion = KeypadSnapshot.SCENE_CONTRACT_VERSION,
            softmenuId = 0,
            softmenuFirstItem = 0,
            softmenuItemCount = 0,
            softmenuVisibleRowOffset = 0,
            softmenuPage = 0,
            softmenuPageCount = 0,
            softmenuHasPreviousPage = false,
            softmenuHasNextPage = false,
            softmenuDottedRow = -1,
            functionPreviewActive = false,
            functionPreviewKeyCode = 0,
            functionPreviewRow = -1,
            functionPreviewState = 0,
            functionPreviewTimeoutActive = false,
            functionPreviewReleaseExec = false,
            functionPreviewNopOrExecuted = false,
            keyStates = keyStates,
        )
    }
}
