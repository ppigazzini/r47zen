package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Typeface

// Load the keypad's font set from the staged runtime fonts. The smooth standard
// font replaces the pixel one only for key labels; a smooth asset that fails to
// load falls back to the pixel font, and any font that fails to load is null,
// which C47TypefacePolicy falls past.
internal object KeypadFonts {
    fun load(context: Context, smoothKeyLabels: Boolean): KeypadFontSet {
        val standard = loadTypeface(context, "fonts/C47__StandardFont.ttf")
        return KeypadFontSet(
            standard = if (smoothKeyLabels) {
                loadTypeface(context, "fonts/C47__StandardFontSmooth.ttf") ?: standard
            } else {
                standard
            },
            numeric = loadTypeface(context, "fonts/C47__NumericFont.ttf"),
            tiny = loadTypeface(context, "fonts/C47__TinyFont.ttf"),
        )
    }

    private fun loadTypeface(context: Context, assetPath: String): Typeface? {
        return try {
            Typeface.createFromAsset(context.assets, assetPath)
        } catch (_: Exception) {
            null
        }
    }
}
