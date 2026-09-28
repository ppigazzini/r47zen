package io.github.ppigazzini.r47zen

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class SystemBarInsetsTest {

    @Test
    fun padsBySafeAreaOnTopOfTheDeclaredPadding() {
        val root = declaredPaddingRoot()

        SystemBarInsets.padToSafeArea(root)
        dispatchSafeAreaInsets(root)

        // Top: the cutout (80) is deeper than the status bar (63).
        assertEquals(listOf(1, 2 + 80, 3, 4 + 126), root.paddingList())
    }

    @Test
    fun repeatedInsetsReplaceRatherThanAccumulate() {
        val root = declaredPaddingRoot()

        SystemBarInsets.padToSafeArea(root)
        dispatchSafeAreaInsets(root)
        dispatchSafeAreaInsets(root)

        assertEquals(listOf(1, 2 + 80, 3, 4 + 126), root.paddingList())
    }

    @Test
    fun disabledPadsOnlyTheDeclaredPadding() {
        val root = declaredPaddingRoot()

        SystemBarInsets.padToSafeArea(root) { false }
        dispatchSafeAreaInsets(root)

        assertEquals(listOf(1, 2, 3, 4), root.paddingList())
    }

    @Test
    fun consumesTheInsetsSoNoDescendantPadsAgain() {
        val root = declaredPaddingRoot()
        val child = View(root.context)
        root.addView(child)
        var childSawInsets = false
        ViewCompat.setOnApplyWindowInsetsListener(child) { _, insets ->
            childSawInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars()) != Insets.NONE
            insets
        }

        SystemBarInsets.padToSafeArea(root)
        dispatchSafeAreaInsets(root)

        assertFalse("a descendant received the insets the root already padded for", childSawInsets)
    }

    @Test
    fun settingsScreensPadTheirContentRoot() {
        assertContentRootPadded(Robolectric.buildActivity(SettingsActivity::class.java).setup().get())
    }

    @Test
    fun repoNoticeIndexPadsItsContentRoot() {
        assertContentRootPadded(Robolectric.buildActivity(RepoNoticeIndexActivity::class.java).setup().get())
    }

    @Test
    fun noticeAssetPadsItsContentRoot() {
        assertContentRootPadded(Robolectric.buildActivity(NoticeAssetActivity::class.java).setup().get())
    }

    private fun assertContentRootPadded(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

        dispatchSafeAreaInsets(root)

        assertEquals(listOf(0, 80, 0, 126), root.paddingList())
    }

    private fun declaredPaddingRoot(): FrameLayout {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        return FrameLayout(activity).apply {
            setPadding(1, 2, 3, 4)
            activity.setContentView(this)
        }
    }

    private fun dispatchSafeAreaInsets(root: View) {
        shadowOf(Looper.getMainLooper()).idle()
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 63, 0, 126))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 80, 0, 0))
                .build(),
        )
    }

    private fun View.paddingList() = listOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
}
