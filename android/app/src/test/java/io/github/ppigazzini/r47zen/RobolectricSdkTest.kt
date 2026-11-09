package io.github.ppigazzini.r47zen

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// No Robolectric class here but ApiLevelArmsTest names an sdk in @Config, so
// Robolectric runs each other one at the targetSdkVersion of the merged
// manifest, which R47_DEFAULT_ANDROID_TARGET_SDK sets: the level the app runs at
// on current devices, with behavior such as the edge-to-edge enforcement that
// starts at API 35. The app also installs from minSdk 24; ApiLevelArmsTest runs
// the shell's SDK_INT arms at the lower levels.
@RunWith(RobolectricTestRunner::class)
class RobolectricSdkTest {
    @Test
    fun frameworkUnderTest_isTheSdkTheAppTargets() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(context.applicationInfo.targetSdkVersion, Build.VERSION.SDK_INT)
    }
}
