package io.github.ppigazzini.r47zen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasteNumberTest {

    @Test
    fun tokens_putEverySignAfterTheDigitsItNegates() {
        val expected = mapOf(
            "12" to "12",
            "1.5" to "1.5",
            ".5" to "0.5",
            "007" to "7",
            "00.50" to "0.50",
            "0" to "0",
            "1e005" to "1e5",
            "1e00" to "1e0",
            "7." to "7.",
            "-1.5" to "1.5~",
            "+1.5" to "1.5",
            "1.5e3" to "1.5e3",
            "1.5E+3" to "1.5e3",
            "1.5e-3" to "1.5e3~",
            "-1.5e-3" to "1.5~e3~",
            "-1E-100" to "1~e100~",
            "−1.5" to "1.5~",
        )

        expected.forEach { (clip, tokens) -> assertEquals(clip, tokens, PasteNumber.tokens(clip)) }
    }

    @Test
    fun tokens_readBothImaginaryForms() {
        val expected = mapOf(
            "3+4i" to "3j4",
            "3-4i" to "3j4~",
            "3+i4" to "3j4",
            "3 + 4 j" to "3j4",
            "-3-4i" to "3~j4~",
            "4i" to "0j4",
            "-i4" to "0j4~",
            "1.5e3+2e-1i" to "1.5e3j2e1~",
            "1.5e3-2e-1i" to "1.5e3j2~e1~",
        )

        expected.forEach { (clip, tokens) -> assertEquals(clip, tokens, PasteNumber.tokens(clip)) }
    }

    @Test
    fun tokens_readWhatCopyXWrites() {
        val expected = mapOf(
            "1.234567890123456789012345678901234" to "1.234567890123456789012345678901234",
            "1E+100" to "1e100",
            "-12345" to "12345~",
            "3. + ⅈ4." to "3.j4.",
            "3. - ⅈ4." to "3.j4.~",
            "-3. - ⅈ4." to "3.~j4.~",
            "1.5E+3 + ⅈ0.2" to "1.5e3j0.2",
        )

        expected.forEach { (clip, tokens) -> assertEquals(clip, tokens, PasteNumber.tokens(clip)) }
    }

    @Test
    fun tokens_takeACommaAsARadixMarkOnlyWhenItCannotBeGrouping() {
        assertEquals("1.5", PasteNumber.tokens("1,5"))
        assertEquals("1.2345", PasteNumber.tokens("1,2345"))
        assertNull(PasteNumber.tokens("1,234"))
        assertNull(PasteNumber.tokens("1,234.5"))
        assertNull(PasteNumber.tokens("1.234,5"))
    }

    @Test
    fun tokens_ignoreSpaceAroundTheNumberButNotBetweenDigits() {
        assertEquals("12", PasteNumber.tokens("  12\n"))
        assertNull(PasteNumber.tokens("1 234"))
        assertNull(PasteNumber.tokens("1\n2"))
    }

    @Test
    fun tokens_refuseAnythingThatIsNotOneNumber() {
        val refused = listOf(
            "",
            "   ",
            "hello",
            "2026-11-06",
            "12:30:00",
            "1/2",
            "3E+1°",
            "1e",
            "e5",
            "+",
            "i",
            "1+2",
            "1+2+3i",
            "--1",
            "1..2",
            "FF#16",
        )

        refused.forEach { clip -> assertNull(clip, PasteNumber.tokens(clip)) }
    }

    @Test
    fun tokens_refuseAClipPastTheScanLimit() {
        val atLimit = "7".repeat(PasteNumber.MAX_CHARS)

        assertEquals(atLimit, PasteNumber.tokens(atLimit))
        assertNull(PasteNumber.tokens(atLimit + "7"))
    }
}
