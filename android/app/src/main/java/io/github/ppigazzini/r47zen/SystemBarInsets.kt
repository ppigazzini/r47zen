package io.github.ppigazzini.r47zen

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps each screen's content out from under the system bars.
 *
 * From targetSdk 35 the platform lays every window out edge to edge, and from
 * API 36 an app cannot opt out, so a root that ignores window insets draws its
 * top under the status bar and its bottom under the navigation bar, where a
 * three-button bar also takes the touches. Every screen therefore draws edge to
 * edge on every API level, the layout the platform enforces, and pads its own
 * root by the safe area.
 */
internal object SystemBarInsets {
    private val SAFE_AREA = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    /** Draws [activity] edge to edge behind transparent bars with light icons. */
    fun drawEdgeToEdge(activity: ComponentActivity) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
    }

    /** Pads the root of [activity]'s content view by the safe area. */
    fun padContentToSafeArea(activity: Activity) {
        padToSafeArea(activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
    }

    /**
     * Pads [root] by the safe area on top of the padding its layout declares,
     * or by the declared padding alone while [isEnabled] returns false. The
     * insets are consumed here, so no descendant pads for them a second time.
     * Call [ViewCompat.requestApplyInsets] on [root] when [isEnabled] changes.
     */
    fun padToSafeArea(root: View, isEnabled: () -> Boolean = { true }) {
        val declared = Insets.of(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = if (isEnabled()) insets.getInsets(SAFE_AREA) else Insets.NONE
            view.setPadding(
                declared.left + safe.left,
                declared.top + safe.top,
                declared.right + safe.right,
                declared.bottom + safe.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }
}
