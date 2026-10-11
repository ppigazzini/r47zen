package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class CalculatorKeyViewCanvasTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun drawRendersPrimaryLaneFromLabelState() {
        assertLaneDrawsItsTextColour(
            KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "88",
                styleRole = KeypadSceneContract.STYLE_NUMERIC,
                isEnabled = true,
            ),
            Color.MAGENTA,
        ) { view -> view.primaryLabel }
    }

    @Test
    fun drawRendersFLaneFromLabelState() {
        assertLaneDrawsItsTextColour(
            KeypadKeySnapshot.EMPTY.copy(
                fLabel = "MM",
                styleRole = KeypadSceneContract.STYLE_DEFAULT,
                isEnabled = true,
            ),
            Color.CYAN,
        ) { view -> view.fLabel }
    }

    @Test
    fun drawRendersGLaneFromLabelState() {
        assertLaneDrawsItsTextColour(
            KeypadKeySnapshot.EMPTY.copy(
                fLabel = "MM",
                gLabel = "WW",
                styleRole = KeypadSceneContract.STYLE_DEFAULT,
                isEnabled = true,
            ),
            Color.GREEN,
        ) { view -> view.gLabel }
    }

    @Test
    fun drawRendersFourthLaneFromLabelState() {
        assertLaneDrawsItsTextColour(
            KeypadKeySnapshot.EMPTY.copy(
                letterLabel = "A",
                styleRole = KeypadSceneContract.STYLE_DEFAULT,
                isEnabled = true,
            ),
            Color.YELLOW,
        ) { view -> view.letterLabel }
    }

    // The lane's glyph interiors carry its text colour exactly, so the render
    // must hold that colour exactly. The same render before the lane is given
    // the colour must not hold it, or the colour comes from somewhere else and
    // the check proves nothing.
    private fun assertLaneDrawsItsTextColour(
        keyState: KeypadKeySnapshot,
        textColour: Int,
        lane: (CalculatorKeyView) -> TextView,
    ) {
        assertFalse(
            "${hex(textColour)} is in the render before the lane is given it",
            render(keyState) {}.containsColour(textColour),
        )
        assertTrue(
            "the lane did not draw its text colour ${hex(textColour)}",
            render(keyState) { view -> lane(view).setTextColor(textColour) }.containsColour(textColour),
        )
    }

    private fun render(
        keyState: KeypadKeySnapshot,
        code: Int = 19,
        configureView: (CalculatorKeyView) -> Unit,
    ): Bitmap {
        val view = createMeasuredView(code, keyState, configureView)

        return Bitmap.createBitmap(MEASURED_WIDTH, MEASURED_HEIGHT + (2 * CANVAS_VERTICAL_INSET), Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                translate(0f, CANVAS_VERTICAL_INSET.toFloat())
                view.draw(this)
            }
        }
    }

    private fun createMeasuredView(
        code: Int,
        keyState: KeypadKeySnapshot,
        configureView: (CalculatorKeyView) -> Unit = {},
    ): CalculatorKeyView {
        return CalculatorKeyView(context).apply {
            setKey(
                KeypadTopology.slotFor(code),
                KeypadFontSet(standard = null, numeric = null, tiny = null),
            )
            updateLabels(snapshotFor(code, keyState))
            configureView(this)
            measureAndLayout(this)
        }
    }

    private fun measureAndLayout(view: CalculatorKeyView) {
        view.measure(exactly(MEASURED_WIDTH), exactly(MEASURED_HEIGHT))
        view.layout(0, 0, MEASURED_WIDTH, MEASURED_HEIGHT)
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }

    private fun snapshotFor(code: Int, keyState: KeypadKeySnapshot): KeypadSnapshot {
        val keyStates = MutableList(KeypadSnapshot.KEY_COUNT) { KeypadKeySnapshot.EMPTY }
        keyStates[code - 1] = keyState

        return KeypadSnapshot(
            keyboardState = KeyboardStateSnapshot.EMPTY,
            sceneContractVersion = KeypadSnapshot.SCENE_CONTRACT_VERSION,
            softmenuId = 0,
            softmenuFirstItem = 0,
            softmenuItemCount = 0,
            softmenuVisibleRowOffset = 0,
            softmenuPage = 0,
            softmenuPageCount = 0,
            softmenuHasPreviousPage = false,
            softmenuHasNextPage = false,
            softmenuDottedRow = 0,
            functionPreviewActive = false,
            functionPreviewKeyCode = 0,
            functionPreviewRow = 0,
            functionPreviewState = 0,
            functionPreviewTimeoutActive = false,
            functionPreviewReleaseExec = false,
            functionPreviewNopOrExecuted = false,
            keyStates = keyStates,
        )
    }

    private fun Bitmap.containsColour(colour: Int): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { it == colour }
    }

    private fun hex(colour: Int): String = "#%08X".format(colour)

    private companion object {
        private const val CANVAS_VERTICAL_INSET = 120
        private const val MEASURED_WIDTH = 331
        private const val MEASURED_HEIGHT = 260
    }
}
