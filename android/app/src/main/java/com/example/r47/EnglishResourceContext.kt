package com.example.r47

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

internal object EnglishResourceContext {
    private val locale = Locale.ENGLISH

    // Settings-owned screens stay English even if the broader app later adds translations.
    fun wrap(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
