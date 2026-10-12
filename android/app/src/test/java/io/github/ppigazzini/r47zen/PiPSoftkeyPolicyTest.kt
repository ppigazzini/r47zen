package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the picture-in-picture softkey mapping: every touch across the row,
 * the right edge included, presses one of F1 to F6, codes 38 to 43, the only
 * codes native sendKey presses in that row.
 */
class PiPSoftkeyPolicyTest {
    @Test
    fun softkeyCode_mapsEachColumnToItsKey() {
        val width = 600
        for (column in 0 until PiPSoftkeyPolicy.SOFTKEY_COUNT) {
            val centre = (column + 0.5f) * width / PiPSoftkeyPolicy.SOFTKEY_COUNT
            assertEquals(
                PiPSoftkeyPolicy.FIRST_SOFTKEY_CODE + column,
                PiPSoftkeyPolicy.softkeyCode(centre, width),
            )
        }
    }

    @Test
    fun softkeyCode_keepsTheEdgesOnTheRow() {
        assertEquals(38, PiPSoftkeyPolicy.softkeyCode(0f, 600))
        assertEquals(38, PiPSoftkeyPolicy.softkeyCode(-1f, 600))
        assertEquals(43, PiPSoftkeyPolicy.softkeyCode(600f, 600))
        assertEquals(43, PiPSoftkeyPolicy.softkeyCode(601f, 600))
    }
}
