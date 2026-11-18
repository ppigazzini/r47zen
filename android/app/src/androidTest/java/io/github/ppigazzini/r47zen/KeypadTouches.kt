package io.github.ppigazzini.r47zen

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

// Touches on the real MainActivity's key views, injected as a finger's would be, so
// each reaches whatever view the window puts at the key's center: the key's touch
// listener, which hands its code to ReplicaOverlayController.dispatchKey and on to
// CalculatorEngine.dispatchKey, as MainActivity wires them.
internal object KeypadTouches {
    // KeypadTopology's codes: ENTER is 13; 1, 2, and 3 are 29 to 31; + is 37.
    const val KEY_ENTER = 13
    const val KEY_1 = 29
    const val KEY_2 = 30
    const val KEY_3 = 31
    const val KEY_PLUS = 37

    private const val LAYOUT_TIMEOUT_MS = 5_000L
    private const val POLL_INTERVAL_MS = 25L

    // A press and a release at the center of the key's view, once it is laid out.
    fun tap(scenario: ActivityScenario<MainActivity>, code: Int) {
        var center: FloatArray? = null
        waitUntil(LAYOUT_TIMEOUT_MS) {
            scenario.onActivity { activity -> center = centerOf(findKey(activity.window.decorView, code)) }
            center != null
        }
        val (x, y) = checkNotNull(center) { "no laid-out key view for code $code" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
    }

    fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) {
                return true
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun centerOf(key: View?): FloatArray? {
        val laidOut = key?.takeIf { it.width > 0 && it.height > 0 } ?: return null
        val location = IntArray(2)
        laidOut.getLocationOnScreen(location)
        return floatArrayOf(location[0] + laidOut.width / 2f, location[1] + laidOut.height / 2f)
    }

    private fun findKey(view: View, code: Int): CalculatorKeyView? = when {
        view is CalculatorKeyView && view.keyCode == code && view.isShown -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findKey(view.getChildAt(it), code) }
        else -> null
    }
}
