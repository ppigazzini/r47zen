package io.github.ppigazzini.r47zen

// A keypad snapshot whose keys are empty except the given code-to-state pairs,
// for the tests that drive main-key views and the refresh gate.
internal fun keypadSnapshotWith(
    vararg states: Pair<Int, KeypadKeySnapshot>,
    sceneContractVersion: Int = 5,
): KeypadSnapshot {
    val keyStates = MutableList(43) { KeypadKeySnapshot.EMPTY }
    states.forEach { (code, keyState) ->
        keyStates[code - 1] = keyState
    }

    return KeypadSnapshot(
        keyboardState = KeyboardStateSnapshot.EMPTY,
        sceneContractVersion = sceneContractVersion,
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
