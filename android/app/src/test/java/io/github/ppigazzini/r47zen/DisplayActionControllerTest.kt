package io.github.ppigazzini.r47zen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.test.core.app.ApplicationProvider
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class DisplayActionControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before
    fun clearClipboardBeforeTest() {
        clipboard.setPrimaryClip(ClipData.newPlainText("empty", ""))
    }

    @After
    fun clearClipboardAfterTest() {
        clipboard.setPrimaryClip(ClipData.newPlainText("empty", ""))
    }

    @Test
    fun populateMainMenu_includesUpstreamCopyParityItems() {
        val controller = createController()
        val popupMenu = PopupMenu(context, View(context))

        controller.populateMainMenu(popupMenu.menu)

        assertEquals(4, popupMenu.menu.size())
        assertEquals("Settings", popupMenu.menu.getItem(0).title.toString())
        assertEquals("Copy...", popupMenu.menu.getItem(1).title.toString())
        assertEquals("Paste Number", popupMenu.menu.getItem(2).title.toString())
        assertEquals("Picture in Picture", popupMenu.menu.getItem(3).title.toString())
    }

    @Test
    fun popupMenuThemeContext_keepsDarkSurfacesInLightSystemMode() {
        val controller = createController()
        val popupContext = controller.popupMenuThemeContext()

        val surface = MaterialColors.getColor(
            popupContext,
            com.google.android.material.R.attr.colorSurface,
            Color.MAGENTA,
        )
        val onSurface = MaterialColors.getColor(
            popupContext,
            com.google.android.material.R.attr.colorOnSurface,
            Color.MAGENTA,
        )

        assertTrue(ColorUtils.calculateLuminance(surface) < 0.15)
        assertTrue(ColorUtils.calculateLuminance(onSurface) > 0.7)
    }

    @Test
    fun populateCopyMenu_includesThreeClipboardOptions() {
        val controller = createController()
        val popupMenu = PopupMenu(context, View(context))

        controller.populateCopyMenu(popupMenu.menu)

        assertEquals(3, popupMenu.menu.size())
        assertEquals("Copy X Register", popupMenu.menu.getItem(0).title.toString())
        assertEquals("Copy Stack Registers", popupMenu.menu.getItem(1).title.toString())
        assertEquals("Copy All Registers", popupMenu.menu.getItem(2).title.toString())
    }

    @Test
    fun handleCopyMenuSelection_copyActionsWriteExpectedClipboardPayloads() {
        val controller = createController(
            xText = "1.234",
            stackText = "K = 9.\nJ = 8.\nX = 1.234",
            allText = "K = 9.\nJ = 8.\nR00 = 0.",
        )

        assertTrue(controller.handleCopyMenuSelection(DisplayActionController.MENU_COPY_X_REGISTER))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("1.234", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        assertTrue(controller.handleCopyMenuSelection(DisplayActionController.MENU_COPY_STACK_REGISTERS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("K = 9.\nJ = 8.\nX = 1.234", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

        assertTrue(controller.handleCopyMenuSelection(DisplayActionController.MENU_COPY_ALL_REGISTERS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("K = 9.\nJ = 8.\nR00 = 0.", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun handleMainMenuSelection_routesSettingsAndPictureInPicture() {
        var openedSettings = false
        var enteredPiP = false
        val controller = createController(enterPiP = { enteredPiP = true })

        assertTrue(
            controller.handleMainMenuSelection(DisplayActionController.MENU_SETTINGS) {
                openedSettings = true
            },
        )
        assertTrue(controller.handleMainMenuSelection(DisplayActionController.MENU_PICTURE_IN_PICTURE) {})

        assertTrue(openedSettings)
        assertTrue(enteredPiP)
    }

    @Test
    fun handleMainMenuSelection_pasteToleratesEmptyPrimaryClip() {
        val controller = createController()

        // ClipboardManager.getPrimaryClip() can return a non-null ClipData with
        // zero items on some devices/clipboard managers (a known
        // IndexOutOfBoundsException crash class at ClipData.getItemAt). A 0-item
        // clip is not constructible through the public API, so empty mItems on a
        // normally-built clip to reproduce the state.
        val emptyClip = ClipData.newPlainText("paste", "ignored")
        val itemsField = ClipData::class.java.getDeclaredField("mItems")
        itemsField.isAccessible = true
        (itemsField.get(emptyClip) as MutableList<*>).clear()
        clipboard.setPrimaryClip(emptyClip)
        assertEquals(0, clipboard.primaryClip?.itemCount)

        // Before the guard this threw IndexOutOfBoundsException from getItemAt(0).
        assertTrue(
            controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {},
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun createController(
        xText: String = "1.234",
        stackText: String = "K = 9.\nJ = 8.\nX = 1.234",
        allText: String = "K = 9.\nJ = 8.\nR00 = 0.",
        enterPiP: () -> Unit = {},
    ): DisplayActionController {
        return DisplayActionController(
            context = context,
            mainHandler = Handler(Looper.getMainLooper()),
            offerCoreTask = { runnable -> runnable.run() },
            getClipboardXRegisterNative = { xText },
            getClipboardStackRegistersNative = { stackText },
            getClipboardAllRegistersNative = { allText },
            sendSimFuncNative = {},
            sendSimKeyNative = { _, _, _ -> },
            enterPiP = enterPiP,
        )
    }
}
