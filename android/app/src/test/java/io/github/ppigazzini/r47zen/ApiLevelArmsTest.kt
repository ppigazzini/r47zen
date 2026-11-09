package io.github.ppigazzini.r47zen

import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVibrator

// The app installs on minSdk 24, and every other JVM class runs at the target SDK, so
// each Build.VERSION.SDK_INT arm in the shell runs here against the framework of a
// level on each side of it: N, 24, is below O, R, and S, and R, 30, is at or above O
// and R and below S. The target, above all three, is every other class's level.
// test_robolectric_sdk_owner_contract.py lets this class alone name an sdk.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
class ApiLevelArmsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearStateBeforeTest() {
        clearHapticState()
    }

    @After
    fun clearStateAfterTest() {
        clearHapticState()
    }

    // HapticFeedbackController: below S the pulse goes to the Vibrator service, and
    // below O as a bare duration.
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun customPulse_belowO_vibratesTheServiceForTheDuration() {
        val vibrator = shadowOf(context.getSystemService(Vibrator::class.java))

        assertTrue(customPulseController().performClick(View(context)))

        assertTrue(vibrator.isVibrating)
        assertEquals(PULSE_MS, vibrator.milliseconds)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun customPulse_belowS_vibratesTheServiceWithAnEffect() {
        val vibrator = shadowOf(context.getSystemService(Vibrator::class.java))

        assertTrue(customPulseController().performClick(View(context)))

        assertTrue(vibrator.isVibrating)
        assertEquals(PULSE_MS, vibrator.milliseconds)
    }

    // StorageAccessCoordinator: the picker reads a start folder from O.
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun pickerIntent_belowO_namesNoStartFolder() {
        for (isSave in listOf(true, false)) {
            val intent = coordinator().buildNativeFileRequestIntent(isSave, "state.s47", START)

            assertNull(IntentCompat.getParcelableExtra(intent, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java))
        }
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun pickerIntent_fromO_namesTheStartFolder() {
        for (isSave in listOf(true, false)) {
            val intent = coordinator().buildNativeFileRequestIntent(isSave, "state.s47", START)

            assertEquals(
                START,
                IntentCompat.getParcelableExtra(intent, DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java),
            )
        }
    }

    // WindowModeController: picture-in-picture exists from O.
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun pictureInPicture_belowO_isNeverAsked() {
        var asked = false
        val controller = windowController { asked = true; true }

        controller.enterPictureInPicture()

        assertFalse(asked)
        assertFalse(controller.isEnteringPictureInPicture())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun pictureInPicture_fromO_isAsked() {
        var asked = false
        val controller = windowController { asked = true; true }

        controller.enterPictureInPicture()

        assertTrue(asked)
        assertTrue(controller.isEnteringPictureInPicture())
    }

    // ReplicaOverlay: below O the animator scale is read from Settings, from O through
    // ValueAnimator.
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun animations_belowO_followTheAnimatorDurationScaleSetting() {
        val overlay = ReplicaOverlay(context)
        val resolver = context.contentResolver

        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse(overlay.areAnimationsEnabled())
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertTrue(overlay.areAnimationsEnabled())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun animations_fromO_followValueAnimator() {
        val overlay = ReplicaOverlay(context)
        // The framework's own hidden setter, which the developer option drives.
        val setDurationScale = ValueAnimator::class.java
            .getMethod("setDurationScale", Float::class.javaPrimitiveType)

        try {
            setDurationScale.invoke(null, 0f)
            assertFalse(ValueAnimator.areAnimatorsEnabled())
            assertFalse(overlay.areAnimationsEnabled())
            setDurationScale.invoke(null, 1f)
            assertTrue(overlay.areAnimationsEnabled())
        } finally {
            setDurationScale.invoke(null, 1f)
        }
    }

    // ReplicaOverlay: below R a pan starts at the plain touch slop; from R it reads the
    // event's classification, which an unclassified event leaves at the same slop.
    @Test
    @Config(sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.R])
    fun panStartSlop_isTheTouchSlopForAnUnclassifiedEvent() {
        val overlay = ReplicaOverlay(context)
        val slop = ReplicaOverlay::class.java.getDeclaredField("panTouchSlopPx")
            .apply { isAccessible = true }
            .getFloat(overlay)
        val startSlop = ReplicaOverlay::class.java
            .getDeclaredMethod("effectivePanStartSlopPx", MotionEvent::class.java)
            .apply { isAccessible = true }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 10f, 10f, 0)

        try {
            assertEquals(slop, startSlop.invoke(overlay, event) as Float, 0f)
        } finally {
            event.recycle()
        }
    }

    private fun customPulseController(): HapticFeedbackController {
        val preferences = context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putBoolean(HapticFeedbackController.KEY_HAPTIC_USE_ANDROID_DEFAULT, false)
            .putInt(HapticFeedbackController.KEY_HAPTIC_KEYPRESS_DURATION_MS, PULSE_MS.toInt())
            .commit()
        shadowOf(context.getSystemService(Vibrator::class.java)).setHasVibrator(true)
        return HapticFeedbackController(context).apply { syncFromPreferences(preferences) }
    }

    private fun clearHapticState() {
        context.getSharedPreferences(AppPreferences.NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        ShadowVibrator.reset()
    }

    private fun coordinator() = StorageAccessCoordinator(
        activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get(),
        onNativeFileSelected = { _, _ -> },
        onNativeFileCanceled = {},
        providedSaveIntentLauncher = {},
        providedLoadIntentLauncher = {},
    )

    private fun windowController(enterPipMode: (android.app.PictureInPictureParams) -> Boolean) =
        WindowModeController(
            activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get(),
            mainHandler = Handler(Looper.getMainLooper()),
            onPiPModeChanged = {},
            enterPipMode = enterPipMode,
        )

    private companion object {
        const val PULSE_MS = 6L
        val START: Uri = Uri.parse("content://io.github.ppigazzini.r47zen.documents/document/states")
    }
}
