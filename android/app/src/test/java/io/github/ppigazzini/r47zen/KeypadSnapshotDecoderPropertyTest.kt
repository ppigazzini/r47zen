package io.github.ppigazzini.r47zen

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

// Property-based totality coverage for the native keypad-snapshot decoder
// (REPORT-28 Milestone B). KeypadSnapshot.fromNative parses a native int lane
// and a native label array produced across the JNI boundary. The example-based
// KeypadSnapshotDecoderTest pins specific decoded values; this asserts the
// decoder stays total for an arbitrary, possibly malformed, meta/labels pair:
// it never throws, always yields a KEY_COUNT-sized snapshot, falls back to
// EMPTY on short meta, and never indexes out of bounds. Seeded for
// reproducibility; pure Kotlin, so it runs on the plain JVM with no Robolectric.
class KeypadSnapshotDecoderPropertyTest {
    @Test
    fun fromNative_isTotalForArbitraryMetaAndLabels() {
        val rs = RandomSource.seeded(SEED)
        val metaArb = Arb.list(Arb.int(), 0..(KeypadSnapshot.META_LENGTH + 64))
            .map { it.toIntArray() }
        val labelsArb = Arb.list(
            Arb.string(),
            0..(KeypadSnapshot.KEY_COUNT * KeypadSnapshot.LABELS_PER_KEY + 32),
        ).map { it.toTypedArray() }

        repeat(ITERATIONS) {
            val meta = metaArb.sample(rs).value
            val labels = labelsArb.sample(rs).value

            // Must not throw for any shape of input.
            val snapshot = KeypadSnapshot.fromNative(meta, labels)

            if (meta.size < KeypadSnapshot.META_LENGTH) {
                assertSame(
                    "short meta must fall back to EMPTY (seed=$SEED, metaSize=${meta.size})",
                    KeypadSnapshot.EMPTY,
                    snapshot,
                )
            }

            // Every in-range key code resolves to a non-null state; every
            // out-of-range code resolves to the shared EMPTY key, never an
            // index error.
            for (code in 1..KeypadSnapshot.KEY_COUNT) {
                assertNotNull("null key state for code $code (seed=$SEED)", snapshot.keyStateFor(code))
            }
            assertSame(KeypadKeySnapshot.EMPTY, snapshot.keyStateFor(0))
            assertSame(KeypadKeySnapshot.EMPTY, snapshot.keyStateFor(KeypadSnapshot.KEY_COUNT + 1))
        }
    }

    @Test
    fun keyStateFor_neverThrowsForArbitraryCode() {
        val rs = RandomSource.seeded(SEED)
        val snapshot = KeypadSnapshot.fromNative(IntArray(KeypadSnapshot.META_LENGTH), emptyArray())
        val codeArb = Arb.int()

        repeat(ITERATIONS) {
            val code = codeArb.sample(rs).value
            val state = snapshot.keyStateFor(code)
            if (code in 1..KeypadSnapshot.KEY_COUNT) {
                assertNotNull("null key state for in-range code $code (seed=$SEED)", state)
            } else {
                assertSame(
                    "out-of-range code $code must yield EMPTY (seed=$SEED)",
                    KeypadKeySnapshot.EMPTY,
                    state,
                )
            }
        }
    }

    private companion object {
        const val SEED = 0x4B504453L // "KPDS"
        const val ITERATIONS = 2000
    }
}
