package io.github.ppigazzini.r47zen

internal data class KeyboardStateSnapshot(
    val shiftF: Boolean,
    val shiftG: Boolean,
    val calcMode: Int,
    val userModeEnabled: Boolean,
    val alphaOn: Boolean,
) {
    companion object {
        val EMPTY = KeyboardStateSnapshot(
            shiftF = false,
            shiftG = false,
            calcMode = 0,
            userModeEnabled = false,
            alphaOn = false,
        )

        // The keypad scene's meta lane, at the offsets KeypadSnapshot declares and
        // test_keypad_snapshot_wire_contract.py holds to native's.
        fun fromMeta(meta: IntArray?): KeyboardStateSnapshot {
            if (meta == null || meta.size <= KeypadSnapshot.META_ALPHA) {
                return EMPTY
            }

            return KeyboardStateSnapshot(
                shiftF = meta[KeypadSnapshot.META_SHIFT_F] != 0,
                shiftG = meta[KeypadSnapshot.META_SHIFT_G] != 0,
                calcMode = meta[KeypadSnapshot.META_CALC_MODE],
                userModeEnabled = meta[KeypadSnapshot.META_USER_MODE] != 0,
                alphaOn = meta[KeypadSnapshot.META_ALPHA] != 0,
            )
        }
    }
}
