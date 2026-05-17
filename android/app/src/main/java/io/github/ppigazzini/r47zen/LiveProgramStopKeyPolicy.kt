package io.github.ppigazzini.r47zen

internal object LiveProgramStopKeyPolicy {
    const val EXIT_KEY_CODE = 33
    const val RUN_STOP_KEY_CODE = 36

    fun shouldPublishDirectStop(keyCode: Int): Boolean {
        return keyCode == RUN_STOP_KEY_CODE || keyCode == EXIT_KEY_CODE
    }
}
