package io.github.ppigazzini.r47zen

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// Property-based coverage for the native keypad-snapshot decoder.
// KeypadSnapshot.fromNative parses a native int lane and a native label array
// produced across the JNI boundary. The example-based KeypadSnapshotDecoderTest
// pins specific meta values; this asserts, for arbitrary and possibly malformed
// input, that the decoder never throws, falls back to EMPTY on short meta, maps
// every out-of-range code to the shared EMPTY key, and reads each key's labels
// from their wire position (code - 1) * LABELS_PER_KEY + slot, with "" past the
// end of a short array. Labels are unique per position so a decoder that reads
// the wrong slot, the wrong key, or nothing at all fails. Kotest's checkAll runs
// from a fixed seed and shrinks a failing input before reporting it; pure
// Kotlin, so it runs on the plain JVM with no Robolectric.
class KeypadSnapshotDecoderPropertyTest {
    @Test
    fun fromNative_isTotalForArbitraryMetaAndLabels() {
        val metaArb = Arb.list(Arb.int(), 0..(KeypadSnapshot.META_LENGTH + 64))
            .map { it.toIntArray() }
        val labelCountArb = Arb.int(0..(KeypadSnapshot.KEY_COUNT * KeypadSnapshot.LABELS_PER_KEY + 32))

        runBlocking {
            checkAll(CONFIG, metaArb, labelCountArb) { meta, labelCount ->
                val labels = Array(labelCount) { "label-$it" }

                // Must not throw for any shape of input.
                val snapshot = KeypadSnapshot.fromNative(meta, labels)

                if (meta.size < KeypadSnapshot.META_LENGTH) {
                    assertSame(
                        "short meta must fall back to EMPTY (metaSize=${meta.size})",
                        KeypadSnapshot.EMPTY,
                        snapshot,
                    )
                } else {
                    for (code in 1..KeypadSnapshot.KEY_COUNT) {
                        assertLabelsAtWirePosition(snapshot, code, labels)
                    }
                }
                assertSame(KeypadKeySnapshot.EMPTY, snapshot.keyStateFor(0))
                assertSame(KeypadKeySnapshot.EMPTY, snapshot.keyStateFor(KeypadSnapshot.KEY_COUNT + 1))
            }
        }
    }

    @Test
    fun keyStateFor_readsInRangeCodesAndIsEmptyOutsideThem() {
        val labels = Array(KeypadSnapshot.KEY_COUNT * KeypadSnapshot.LABELS_PER_KEY) { "label-$it" }
        val snapshot = KeypadSnapshot.fromNative(IntArray(KeypadSnapshot.META_LENGTH), labels)
        // Mostly near the valid range, so in-range codes are drawn often and both
        // edges are crossed; an unbounded Arb.int() would almost never hit 1..43.
        val codeArb = Arb.int(-16..(KeypadSnapshot.KEY_COUNT + 16))

        runBlocking {
            checkAll(CONFIG, codeArb) { code ->
                if (code in 1..KeypadSnapshot.KEY_COUNT) {
                    assertLabelsAtWirePosition(snapshot, code, labels)
                } else {
                    assertSame(
                        "out-of-range code $code must yield EMPTY",
                        KeypadKeySnapshot.EMPTY,
                        snapshot.keyStateFor(code),
                    )
                }
            }
        }
    }

    private fun assertLabelsAtWirePosition(snapshot: KeypadSnapshot, code: Int, labels: Array<String>) {
        val state = snapshot.keyStateFor(code)
        val decoded = listOf(state.primaryLabel, state.fLabel, state.gLabel, state.letterLabel, state.auxLabel)
        decoded.forEachIndexed { slot, label ->
            val position = (code - 1) * KeypadSnapshot.LABELS_PER_KEY + slot
            assertEquals(
                "code $code slot $slot read the wrong label (labels=${labels.size})",
                labels.getOrElse(position) { "" },
                label,
            )
        }
    }

    private companion object {
        const val SEED = 0x4B504453L // "KPDS"
        const val ITERATIONS = 2000
        val CONFIG = PropTestConfig(seed = SEED, iterations = ITERATIONS)
    }
}
