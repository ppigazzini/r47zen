package io.github.ppigazzini.r47zen

/**
 * Turn clipboard text into the tokens r47_paste_number enters, or refuse it.
 *
 * The clip must read whole as one real or complex number, because a number typed
 * from part of a clip is a wrong number on the stack. jni_input.c owns what each
 * token means; PasteNumberTest and clipboard_overflow_harness.c hold the two sides
 * to the same token strings.
 */
internal object PasteNumber {
    const val MAX_CHARS = 256

    private const val TOKEN_NEGATE = '~'
    private const val TOKEN_EXPONENT = 'e'
    private const val TOKEN_IMAGINARY = 'j'

    // The classes are spelled out: \d and \s match Unicode digits and spaces on
    // Android and ASCII ones on the JVM that runs the tests, so the tests would
    // describe another parser than the device's.
    private const val DIGIT = "[0-9]"
    private const val SPACE =
        "[\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]"
    private const val UNSIGNED = "(?:$DIGIT+(?:[.,]$DIGIT*)?|[.,]$DIGIT+)(?:e[+-]?$DIGIT+)?"
    private val REAL = Regex("""[+-]?$UNSIGNED""")
    private val COMPLEX_FORMS = listOf(
        Regex("""([+-]?$UNSIGNED)([+-])i($UNSIGNED)"""),
        Regex("""([+-]?$UNSIGNED)([+-])($UNSIGNED)i"""),
        Regex("""()([+-]?)i($UNSIGNED)"""),
        Regex("""()([+-]?)($UNSIGNED)i"""),
    )

    // Space between digits is either grouping or two numbers, and "1,234" is
    // 1.234 or 1234 by locale. Refuse both rather than pick one. A point is always a
    // radix mark, as Copy X writes it, so "1.234" reads as 1.234 in any locale.
    private val SPACE_BETWEEN_DIGITS = Regex("[0-9.,]$SPACE+[0-9.,]")
    private val COMMA_GROUPING = Regex("[1-9]$DIGIT{0,2},$DIGIT{3}")

    /** Return the tokens for [text], or null when it is not one readable number. */
    fun tokens(text: String): String? {
        val clip = text.trim()
        return when {
            clip.length > MAX_CHARS || SPACE_BETWEEN_DIGITS.containsMatchIn(clip) -> null
            else -> normalize(clip).let { number ->
                if (REAL.matches(number)) realTokens(number) else complexTokens(number)
            }
        }
    }

    // U+2212 is the minus sign text sources print. U+2148 and U+2149 are the imaginary
    // units upstream's Copy X writes, by the setting that shows complex numbers with j.
    private fun normalize(clip: String): String {
        return clip.filterNot(Char::isWhitespace)
            .replace('−', '-')
            .replace('ⅈ', 'i')
            .replace('ⅉ', 'i')
            .replace('I', 'i')
            .replace('j', 'i')
            .replace('J', 'i')
            .replace('E', 'e')
    }

    private fun complexTokens(number: String): String? {
        val match = COMPLEX_FORMS.firstNotNullOfOrNull { it.matchEntire(number) } ?: return null
        val (real, sign, imaginary) = match.destructured
        val realPart = realTokens(real.ifEmpty { "0" })
        val imaginaryPart = realTokens(sign + imaginary)
        return if (realPart == null || imaginaryPart == null) {
            null
        } else {
            realPart + TOKEN_IMAGINARY + imaginaryPart
        }
    }

    // CHS follows the digits it negates: the core reads it as the sign of the
    // mantissa, or of the exponent once EEX is entered, and before any digit it
    // would negate the value already in X.
    private fun realTokens(number: String): String? {
        val parts = number.trimStart('+', '-').split(TOKEN_EXPONENT)
        val mantissa = parts.first()
        if (COMMA_GROUPING.matches(mantissa)) {
            return null
        }
        return buildString {
            append(canonicalMantissa(mantissa.replace(',', '.')))
            if (number.startsWith('-')) {
                append(TOKEN_NEGATE)
            }
            parts.getOrNull(1)?.let { exponent ->
                append(TOKEN_EXPONENT)
                append(withoutLeadingZeros(exponent.trimStart('+', '-')))
                if (exponent.startsWith('-')) {
                    append(TOKEN_NEGATE)
                }
            }
        }
    }

    // r47_paste_number takes a digit that does not lengthen the entry as dropped, and
    // the core replaces a lone leading zero in an imaginary part or an exponent
    // instead of appending to it. Dropping leading zeros keeps every digit it sees.
    private fun canonicalMantissa(mantissa: String): String {
        val integerPart = mantissa.substringBefore('.')
        val fraction = mantissa.substring(integerPart.length)
        return withoutLeadingZeros(integerPart) + fraction
    }

    private fun withoutLeadingZeros(digits: String): String {
        return digits.trimStart('0').ifEmpty { "0" }
    }
}
