package io.github.ppigazzini.r47zen

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// No Robolectric class here names an sdk in @Config, so Robolectric runs each
// one at the targetSdkVersion of the merged manifest, which
// R47_DEFAULT_ANDROID_TARGET_SDK sets. A class pinned below it would test
// framework behavior the app does not run on, such as the edge-to-edge
// enforcement that starts at API 35.
@RunWith(RobolectricTestRunner::class)
class RobolectricSdkTest {
    @Test
    fun frameworkUnderTest_isTheSdkTheAppTargets() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(context.applicationInfo.targetSdkVersion, Build.VERSION.SDK_INT)
    }
}
