package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class CalculatorKeyViewCanvasTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun drawRendersPrimaryLaneFromLabelState() {
        assertBitmapContainsColor(
            render(
                KeypadKeySnapshot.EMPTY.copy(
                    primaryLabel = "88",
                    styleRole = KeypadSceneContract.STYLE_NUMERIC,
                    isEnabled = true,
                ),
            ) { view ->
                view.primaryLabel.setTextColor(Color.MAGENTA)
            },
            Color.MAGENTA,
            tolerance = 140,
        )
    }

    @Test
    fun drawRendersFLaneFromLabelState() {
        assertBitmapContainsColor(
            render(
                KeypadKeySnapshot.EMPTY.copy(
                    fLabel = "MM",
                    styleRole = KeypadSceneContract.STYLE_DEFAULT,
                    isEnabled = true,
                ),
            ) { view ->
                view.fLabel.setTextColor(Color.CYAN)
            },
            Color.CYAN,
            tolerance = 140,
        )
    }

    @Test
    fun drawRendersGLaneFromLabelState() {
        assertBitmapContainsColor(
            render(
                KeypadKeySnapshot.EMPTY.copy(
                    fLabel = "MM",
                    gLabel = "WW",
                    styleRole = KeypadSceneContract.STYLE_DEFAULT,
                    isEnabled = true,
                ),
            ) { view ->
                view.gLabel.setTextColor(Color.GREEN)
            },
            Color.GREEN,
            tolerance = 140,
        )
    }

    @Test
    fun drawRendersFourthLaneFromLabelState() {
        assertBitmapContainsColor(
            render(
                KeypadKeySnapshot.EMPTY.copy(
                    letterLabel = "A",
                    styleRole = KeypadSceneContract.STYLE_DEFAULT,
                    isEnabled = true,
                ),
            ) { view ->
                view.letterLabel.setTextColor(Color.YELLOW)
            },
            Color.YELLOW,
            tolerance = 140,
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

    private fun assertBitmapContainsColor(
        bitmap: Bitmap,
        expectedColor: Int,
        tolerance: Int,
    ) {
        val foundMatch = (0 until bitmap.width).any { x ->
            (0 until bitmap.height).any { y ->
                colorsMatch(bitmap.getPixel(x, y), expectedColor, tolerance)
            }
        }

        assertTrue(
            "Expected $expectedColor somewhere in the rendered bitmap",
            foundMatch,
        )
    }

    private fun colorsMatch(actual: Int, expected: Int, tolerance: Int): Boolean {
        return abs(Color.alpha(actual) - Color.alpha(expected)) <= tolerance &&
            abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
            abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
            abs(Color.blue(actual) - Color.blue(expected)) <= tolerance
    }

    private companion object {
        private const val CANVAS_VERTICAL_INSET = 120
        private const val MEASURED_WIDTH = 331
        private const val MEASURED_HEIGHT = 260
    }
}
