package io.github.ppigazzini.r47zen

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry

// The upstream PROGRAMS fixtures are staged into the test APK's assets, never
// the app's, so the app a user installs carries no test data. Read them through
// the instrumentation context, which is the test APK; the target context is the
// app under test and has none of them.
internal fun testAssets(): AssetManager =
    InstrumentationRegistry.getInstrumentation().context.assets
