package io.github.ppigazzini.r47zen

import android.graphics.Typeface

internal object C47TypefacePolicy {
    fun standardFirst(
        text: CharSequence,
        fontSet: KeypadFontSet,
    ): Typeface? {
        val standardTypeface = fontSet.standard
        if (standardTypeface != null) {
            return standardTypeface
        }

        val tinyTypeface = fontSet.tiny
        if (tinyTypeface != null) {
            return tinyTypeface
        }

        return null
    }
}
