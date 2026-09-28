package io.github.ppigazzini.r47zen

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
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
// the wrong slot, the wrong key, or nothing at all fails. Seeded for
// reproducibility; pure Kotlin, so it runs on the plain JVM with no Robolectric.
class KeypadSnapshotDecoderPropertyTest {
    @Test
    fun fromNative_isTotalForArbitraryMetaAndLabels() {
        val rs = RandomSource.seeded(SEED)
        val metaArb = Arb.list(Arb.int(), 0..(KeypadSnapshot.META_LENGTH + 64))
            .map { it.toIntArray() }
        val labelCountArb = Arb.int(0..(KeypadSnapshot.KEY_COUNT * KeypadSnapshot.LABELS_PER_KEY + 32))

        repeat(ITERATIONS) {
            val meta = metaArb.sample(rs).value
            val labels = Array(labelCountArb.sample(rs).value) { "label-$it" }

            // Must not throw for any shape of input.
            val snapshot = KeypadSnapshot.fromNative(meta, labels)

            if (meta.size < KeypadSnapshot.META_LENGTH) {
                assertSame(
                    "short meta must fall back to EMPTY (seed=$SEED, metaSize=${meta.size})",
                    KeypadSnapshot.EMPTY,
                    snapshot,
                )
            }

            if (meta.size >= KeypadSnapshot.META_LENGTH) {
                for (code in 1..KeypadSnapshot.KEY_COUNT) {
                    assertLabelsAtWirePosition(snapshot, code, labels)
                }
            }
            assertSame(KeypadKeySnapshot.EMPTY, snapshot.keyStateFor(0))
            assertSame(KeypadKeySnapshot.EMPTY, snapshot.keyStateFor(KeypadSnapshot.KEY_COUNT + 1))
        }
    }

    @Test
    fun keyStateFor_readsInRangeCodesAndIsEmptyOutsideThem() {
        val rs = RandomSource.seeded(SEED)
        val labels = Array(KeypadSnapshot.KEY_COUNT * KeypadSnapshot.LABELS_PER_KEY) { "label-$it" }
        val snapshot = KeypadSnapshot.fromNative(IntArray(KeypadSnapshot.META_LENGTH), labels)
        // Mostly near the valid range, so in-range codes are drawn often and both
        // edges are crossed; an unbounded Arb.int() would almost never hit 1..43.
        val codeArb = Arb.int(-16..(KeypadSnapshot.KEY_COUNT + 16))

        repeat(ITERATIONS) {
            val code = codeArb.sample(rs).value
            val state = snapshot.keyStateFor(code)
            if (code in 1..KeypadSnapshot.KEY_COUNT) {
                assertLabelsAtWirePosition(snapshot, code, labels)
            } else {
                assertSame(
                    "out-of-range code $code must yield EMPTY (seed=$SEED)",
                    KeypadKeySnapshot.EMPTY,
                    state,
                )
            }
        }
    }

    private fun assertLabelsAtWirePosition(snapshot: KeypadSnapshot, code: Int, labels: Array<String>) {
        val state = snapshot.keyStateFor(code)
        val decoded = listOf(state.primaryLabel, state.fLabel, state.gLabel, state.letterLabel, state.auxLabel)
        decoded.forEachIndexed { slot, label ->
            val position = (code - 1) * KeypadSnapshot.LABELS_PER_KEY + slot
            assertEquals(
                "code $code slot $slot read the wrong label (seed=$SEED, labels=${labels.size})",
                labels.getOrElse(position) { "" },
                label,
            )
        }
    }

    private companion object {
        const val SEED = 0x4B504453L // "KPDS"
        const val ITERATIONS = 2000
    }
}
