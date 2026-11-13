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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "notnight")
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
    fun handleCopyMenuSelection_asksTheCalculatorForEachKind() {
        val requested = ArrayList<Int>()
        val controller = createController(
            copyRegisters = { what ->
                requested.add(what)
                true
            },
        )

        assertTrue(controller.handleCopyMenuSelection(DisplayActionController.MENU_COPY_X_REGISTER))
        assertTrue(controller.handleCopyMenuSelection(DisplayActionController.MENU_COPY_STACK_REGISTERS))
        assertTrue(controller.handleCopyMenuSelection(DisplayActionController.MENU_COPY_ALL_REGISTERS))

        assertEquals(
            listOf(CalculatorEngine.COPY_X, CalculatorEngine.COPY_STACK, CalculatorEngine.COPY_ALL),
            requested,
        )
    }

    @Test
    fun aMenuItemTheControllerDoesNotKnowIsLeftToAndroid() {
        val asked = mutableListOf<Int>()
        val controller = createController(copyRegisters = { asked.add(it) })

        assertTrue(controller.handleMainMenuSelection(DisplayActionController.MENU_COPY) {})
        assertFalse(controller.handleMainMenuSelection(UNKNOWN_ITEM) {})
        assertFalse(controller.handleCopyMenuSelection(UNKNOWN_ITEM))
        assertTrue(asked.isEmpty())
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

        // The guard prevents an IndexOutOfBoundsException from getItemAt(0).
        assertTrue(
            controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {},
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun paste_sendsTheWholeNumberAsOneRequest() {
        val pasted = ArrayList<String>()
        val controller = createController(
            pasteNumberNative = { tokens ->
                pasted.add(tokens)
                true
            },
        )
        clipboard.setPrimaryClip(ClipData.newPlainText("paste", "-1.5e-3"))

        controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}

        assertEquals(listOf("1.5~e3~"), pasted)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun paste_refusesAClipThatIsNotOneNumberWithoutTouchingTheCore() {
        val pasted = ArrayList<String>()
        val controller = createController(
            pasteNumberNative = { tokens ->
                pasted.add(tokens)
                true
            },
        )
        clipboard.setPrimaryClip(ClipData.newPlainText("paste", "2026-11-06"))

        controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}

        assertTrue(pasted.isEmpty())
        assertEquals(
            context.getString(R.string.main_menu_paste_not_a_number),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun paste_tellsTheUserWhenTheRequestIsRefused() {
        val controller = createController(pasteNumberNative = { false })
        clipboard.setPrimaryClip(ClipData.newPlainText("paste", "12"))

        controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}

        assertEquals(
            context.getString(R.string.main_menu_paste_not_ready),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun copy_tellsTheUserWhenTheRequestIsRefused() {
        val controller = createController(copyRegisters = { false })

        assertTrue(controller.handleCopyMenuSelection(DisplayActionController.MENU_COPY_X_REGISTER))

        assertEquals(
            context.getString(R.string.main_menu_copy_not_ready),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun copy_saysNothingWhenTheRequestIsQueued() {
        val controller = createController(copyRegisters = { true })

        controller.handleCopyMenuSelection(DisplayActionController.MENU_COPY_STACK_REGISTERS)

        assertNull(ShadowToast.getLatestToast())
    }

    private companion object {
        const val UNKNOWN_ITEM = 99
    }

    private fun createController(
        enterPiP: () -> Unit = {},
        copyRegisters: (Int) -> Boolean = { true },
        pasteNumberNative: (String) -> Boolean = { true },
    ): DisplayActionController {
        return DisplayActionController(
            context = context,
            copyRegisters = copyRegisters,
            pasteNumberNative = pasteNumberNative,
            enterPiP = enterPiP,
        )
    }
}
