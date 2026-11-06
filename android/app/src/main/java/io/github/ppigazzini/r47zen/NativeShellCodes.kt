package io.github.ppigazzini.r47zen

/**
 * Codes the shell passes to sendSimMenuNative instead of upstream item
 * numbers, which move between upstream revisions. jni_input.c
 * resolves each against the staged items.h; the values mirror the
 * R47_SHELL_* defines in jni_bridge.h, and test_jni_registration_contract.py
 * holds the two equal.
 */
internal object NativeShellCodes {
    /** Upstream -MNU_HOME. */
    const val MENU_HOME = 1

    /** Upstream -MNU_MyMenu. */
    const val MENU_MY_MENU = 2
}
