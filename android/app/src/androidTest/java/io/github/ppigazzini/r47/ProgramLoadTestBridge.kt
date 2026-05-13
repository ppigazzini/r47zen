package io.github.ppigazzini.r47

data class ProgramLoadState(
    val lastErrorCode: Int,
    val temporaryInformation: Int,
    val programRunStop: Int,
    val numberOfPrograms: Int,
    val currentLocalStepNumber: Int,
    val currentProgramNumber: Int,
    val lcdRefreshCount: Int,
)

object ProgramLoadTestBridge {
    private const val STATE_LENGTH = 7

    init {
        System.loadLibrary("r47_android")
    }

    fun isRuntimeReady(): Boolean = isRuntimeReadyNative()

    fun seedLargeFactorsInput(): Boolean = seedLargeFactorsInputNative()

    fun seedSpiralkInput(): Boolean = seedSpiralkInputNative()

    fun forceRefresh() {
        forceRefreshNative()
    }

    fun saveBackgroundStateForTest() {
        saveBackgroundStateForTestNative()
    }

    fun captureDisplayHash(): Long = captureDisplayHashNative()

    fun setRedrawFlagForTest(enabled: Boolean) {
        setRedrawFlagForTestNative(enabled)
    }

    fun isRedrawFlagSetForTest(): Boolean = isRedrawFlagSetForTestNative()

    fun resetRuntime() {
        resetRuntimeNative()
    }

    fun sendSimFunction(funcId: Int) {
        sendSimFunctionNative(funcId)
    }

    fun sendSimKey(
        keyId: String,
        isFn: Boolean,
        isRelease: Boolean,
    ) {
        sendSimKeyNative(keyId, isFn, isRelease)
    }

    fun beginSimFunction(funcId: Int): Boolean = beginSimFunctionNative(funcId)

    fun setNextLoadProgramFd(fd: Int) {
        setNextLoadProgramFdNative(fd)
    }

    fun clearLoadProgramFdOverride() {
        clearLoadProgramFdOverrideNative()
    }

    fun isSimFunctionRunning(): Boolean = isSimFunctionRunningNative()

    fun getXRegisterType(): Int = getXRegisterTypeNative()

    fun getXRegisterString(): String = getXRegisterStringNative()

    fun snapshotState(): ProgramLoadState {
        val raw = snapshotStateNative()
        require(raw.size == STATE_LENGTH) {
            "Expected $STATE_LENGTH program-load state values, got ${raw.size}"
        }
        return ProgramLoadState(
            lastErrorCode = raw[0],
            temporaryInformation = raw[1],
            programRunStop = raw[2],
            numberOfPrograms = raw[3],
            currentLocalStepNumber = raw[4],
            currentProgramNumber = raw[5],
            lcdRefreshCount = raw[6],
        )
    }

    private external fun isRuntimeReadyNative(): Boolean
    private external fun seedLargeFactorsInputNative(): Boolean
    private external fun seedSpiralkInputNative(): Boolean
    private external fun forceRefreshNative()
    private external fun saveBackgroundStateForTestNative()
    private external fun captureDisplayHashNative(): Long
    private external fun setRedrawFlagForTestNative(enabled: Boolean)
    private external fun isRedrawFlagSetForTestNative(): Boolean
    private external fun resetRuntimeNative()
    private external fun sendSimFunctionNative(funcId: Int)
    private external fun sendSimKeyNative(keyId: String, isFn: Boolean, isRelease: Boolean)
    private external fun beginSimFunctionNative(funcId: Int): Boolean
    private external fun setNextLoadProgramFdNative(fd: Int)
    private external fun clearLoadProgramFdOverrideNative()
    private external fun isSimFunctionRunningNative(): Boolean
    private external fun snapshotStateNative(): IntArray
    private external fun getXRegisterTypeNative(): Int
    private external fun getXRegisterStringNative(): String
}
