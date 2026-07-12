package io.github.ppigazzini.r47zen

import android.app.PictureInPictureParams
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal class WindowModeController(
    private val activity: AppCompatActivity,
    private val mainHandler: Handler,
    private val onPiPModeChanged: (Boolean) -> Unit,
    private val enterPipMode: (PictureInPictureParams) -> Boolean = { params ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.enterPictureInPictureMode(params)
        } else {
            false
        }
    },
) {
    companion object {
        private const val TAG = "R47WindowMode"
        private val PIP_ASPECT_RATIO = Rational(400, 240)
        private val VISIBLE_SYSTEM_BAR_COLOR = Color.rgb(18, 21, 26)
    }

    private var isMovingToPiP = false

    fun applyFullscreenMode(isFullscreen: Boolean) {
        val window = activity.window ?: return
        try {
            WindowCompat.setDecorFitsSystemWindows(window, !isFullscreen)
            val decorView = window.decorView
            WindowInsetsControllerCompat(window, decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                if (isFullscreen) {
                    window.statusBarColor = Color.TRANSPARENT
                    window.navigationBarColor = Color.TRANSPARENT
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                    window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                } else {
                    window.statusBarColor = VISIBLE_SYSTEM_BAR_COLOR
                    window.navigationBarColor = VISIBLE_SYSTEM_BAR_COLOR
                    show(WindowInsetsCompat.Type.systemBars())
                    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to apply fullscreen mode", error)
        }
    }

    fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isMovingToPiP = true
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(PIP_ASPECT_RATIO)
                .build()
            // enterPictureInPictureMode returns false when PiP is unavailable
            // (user-disabled in system settings, device/policy restriction) and
            // can throw IllegalStateException. On either path the PiP callback
            // never fires, so clear the transient flag here or onPause would skip
            // auto-save for the rest of the process lifetime.
            val entered = try {
                enterPipMode(params)
            } catch (error: IllegalStateException) {
                Log.e(TAG, "Picture-in-picture entry failed; staying in normal mode", error)
                false
            }
            if (!entered) {
                isMovingToPiP = false
            }
        }
    }

    fun handlePictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        Log.i(TAG, "onPictureInPictureModeChanged: isInPictureInPictureMode=$isInPictureInPictureMode")
        isMovingToPiP = false
        mainHandler.post {
            onPiPModeChanged(isInPictureInPictureMode)
        }
    }

    fun isEnteringPictureInPicture(): Boolean {
        return isMovingToPiP || activity.isInPictureInPictureMode
    }
}
