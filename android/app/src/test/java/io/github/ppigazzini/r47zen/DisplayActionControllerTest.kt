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
import java.time.Duration

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

        // The guard prevents an IndexOutOfBoundsException from getItemAt(0).
        assertTrue(
            controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {},
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun pasteSteps_pressAndReleaseEachKnownGlyph() {
        assertEquals(
            listOf(
                ClipboardPaster.Step.Key("28", isRelease = false),
                ClipboardPaster.Step.Key("28", isRelease = true),
                ClipboardPaster.Step.Key("34", isRelease = false),
                ClipboardPaster.Step.Key("34", isRelease = true),
                ClipboardPaster.Step.Key(SimKeyIds.EEX, isRelease = false),
                ClipboardPaster.Step.Key(SimKeyIds.EEX, isRelease = true),
                ClipboardPaster.Step.Function(NativeShellCodes.FUNC_OP_I),
            ),
            ClipboardPaster.steps("1.x E i"),
        )
    }

    @Test
    fun paste_offersOneCoreTaskPerKeystroke_spacedOnTheMainThread() {
        val offered = ArrayList<Runnable>()
        val keys = ArrayList<Pair<String, Boolean>>()
        val controller = createController(
            offerCoreTask = { offered.add(it) },
            sendSimKeyNative = { id, _, release -> keys.add(id to release) },
        )
        clipboard.setPrimaryClip(ClipData.newPlainText("paste", "12"))

        controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}
        // One short task, not the whole clip on the core thread.
        assertEquals(1, offered.size)
        val mainLooper = shadowOf(Looper.getMainLooper())
        repeat(3) {
            mainLooper.idleFor(Duration.ofMillis(50))
        }
        assertEquals(4, offered.size)
        mainLooper.idleFor(Duration.ofMillis(500))
        assertEquals(4, offered.size)

        offered.forEach { it.run() }
        assertEquals(listOf("28" to false, "28" to true, "29" to false, "29" to true), keys)
    }

    @Test
    fun paste_aNewPasteReleasesTheKeyTheOldOneHeld() {
        val offered = ArrayList<Runnable>()
        val keys = ArrayList<Pair<String, Boolean>>()
        val controller = createController(
            offerCoreTask = { offered.add(it) },
            sendSimKeyNative = { id, _, release -> keys.add(id to release) },
        )

        // The second paste lands between the first one's press and release.
        clipboard.setPrimaryClip(ClipData.newPlainText("paste", "111"))
        controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}
        clipboard.setPrimaryClip(ClipData.newPlainText("paste", "2"))
        controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))

        offered.forEach { it.run() }
        assertEquals(listOf("28" to false, "28" to true, "29" to false, "29" to true), keys)
    }

    @Test
    fun paste_aNewPasteStopsTheOneStillReplaying() {
        val offered = ArrayList<Runnable>()
        val keys = ArrayList<Pair<String, Boolean>>()
        val controller = createController(
            offerCoreTask = { offered.add(it) },
            sendSimKeyNative = { id, _, release -> keys.add(id to release) },
        )
        val mainLooper = shadowOf(Looper.getMainLooper())

        clipboard.setPrimaryClip(ClipData.newPlainText("paste", "111"))
        controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}
        mainLooper.idleFor(Duration.ofMillis(50))
        clipboard.setPrimaryClip(ClipData.newPlainText("paste", "2"))
        controller.handleMainMenuSelection(DisplayActionController.MENU_PASTE_NUMBER) {}
        mainLooper.idleFor(Duration.ofMillis(1000))

        offered.forEach { it.run() }
        assertEquals(listOf("28" to false, "28" to true, "29" to false, "29" to true), keys)
    }

    private fun createController(
        xText: String = "1.234",
        stackText: String = "K = 9.\nJ = 8.\nX = 1.234",
        allText: String = "K = 9.\nJ = 8.\nR00 = 0.",
        enterPiP: () -> Unit = {},
        offerCoreTask: (Runnable) -> Unit = { runnable -> runnable.run() },
        sendSimKeyNative: (String, Boolean, Boolean) -> Unit = { _, _, _ -> },
    ): DisplayActionController {
        return DisplayActionController(
            context = context,
            mainHandler = Handler(Looper.getMainLooper()),
            offerCoreTask = offerCoreTask,
            getClipboardXRegisterNative = { xText },
            getClipboardStackRegistersNative = { stackText },
            getClipboardAllRegistersNative = { allText },
            sendSimFuncNative = {},
            sendSimKeyNative = sendSimKeyNative,
            enterPiP = enterPiP,
        )
    }
}
