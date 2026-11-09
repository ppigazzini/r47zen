package io.github.ppigazzini.r47zen

/**
 * What a screen reader says for a key.
 *
 * The R47 fonts draw some symbols at code points of other characters, and a speech
 * engine reads the code point's own name: x squared is "x" and U+2162 ROMAN NUMERAL
 * THREE. The core spells each such glyph through a define in fonts.h, named after
 * what it draws, which is the word here; a label the keypad shows whole is spoken as
 * its function, and any other character above ASCII as a word. KeySpeechTest holds
 * every label of every exported keypad scene to words alone.
 */
internal object KeySpeech {
    private val wholeLabels = mapOf(
        "xⅢ" to "x squared", // STD_SUP_2
        "√Ϳ" to "square root of x", // STD_x_UNDER_ROOT
        "∜Ȳ" to "x-th root of y", // STD_xTH_ROOT, STD_y_UNDER_ROOT
        "ⅈ⊚" to "i polar", // fn_cnst_op_j_pol, STD_SUB_SUN
        "⇌⭡" to "back step", // keypad_scene.c's BST: STD_HAMBURGER, STD_UP_BLOCKARROW
        "⇌⭣" to "single step", // SST: STD_HAMBURGER, STD_DOWN_BLOCKARROW
        "·_·" to "space", // the alpha space, a visible blank between dots
        "⁳" to "exponent", // STD_SUB_E_OUTLINE, EEX
        "←" to "backspace",
        "Α…Ω" to "capital Greek letters",
        "α…ω" to "Greek letters",
    )

    private val characters = mapOf(
        '±' to "plus minus",
        '·' to "dot",
        '×' to "times",
        '÷' to "divided by",
        'Ȳ' to "y", // STD_y_UNDER_ROOT
        'ˣ' to "to the x", // STD_SUP_BOLD_x
        'Ϳ' to "x", // STD_x_UNDER_ROOT
        'Α' to "capital alpha",
        'Δ' to "delta",
        'Ω' to "capital omega",
        'α' to "alpha",
        'π' to "pi",
        'ω' to "omega",
        '…' to "to",
        '⁳' to "exponent", // STD_SUB_E_OUTLINE
        'ⅇ' to "e", // STD_EulerE
        'ⅈ' to "i", // STD_op_i
        'ⅉ' to "j", // STD_op_j
        'Ⅲ' to "squared", // STD_SUP_2
        '←' to "left arrow",
        '↑' to "up",
        '→' to "to",
        '↓' to "down",
        '↵' to "enter", // STD_CR
        '⇄' to "swap", // STD_RIGHT_OVER_LEFT_ARROW
        '⇌' to "menu", // STD_HAMBURGER
        '√' to "square root of",
        '∜' to "x-th root of", // STD_xTH_ROOT
        '∡' to "angle",
        '⊚' to "polar", // STD_SUB_SUN
        '⎌' to "undo",
        '⭡' to "up", // STD_UP_BLOCKARROW
        '⭣' to "down", // STD_DOWN_BLOCKARROW
    )

    private val spaces = Regex("\\s+")

    /** [label] as words: a whole label the keypad shows, else each symbol as a word. */
    fun spoken(label: String): String {
        wholeLabels[label]?.let { return it }
        return buildString {
            for (character in label) {
                val word = characters[character]
                if (word == null) append(character) else append(' ').append(word).append(' ')
            }
        }.replace(spaces, " ").trim()
    }

    /** A main key: its primary label, then the f and g labels it carries. */
    fun mainKey(primary: String, f: String?, g: String?): String = buildString {
        append(spoken(primary))
        if (!f.isNullOrBlank()) append(", f ").append(spoken(f))
        if (!g.isNullOrBlank()) append(", g ").append(spoken(g))
    }

    /** A softkey: its label and the text it shows beside it. */
    fun softkey(primary: String, aux: String?): String = buildString {
        append(spoken(primary))
        if (!aux.isNullOrBlank()) append(", ").append(spoken(aux))
    }
}
