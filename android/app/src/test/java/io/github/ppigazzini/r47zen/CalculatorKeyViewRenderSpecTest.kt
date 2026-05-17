package io.github.ppigazzini.r47zen

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "xxhdpi")
class CalculatorKeyViewRenderSpecTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun buildMainKeyRenderSpecFreezesRepresentativeGeometryAndAccessibility() {
        val view = createMeasuredView(
            code = 12,
            keyState = KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                letterLabel = "A",
                styleRole = KeypadSceneContract.STYLE_DEFAULT,
                isEnabled = true,
            ),
        )

        val renderSpec = requireNotNull(view.currentMainKeyRenderSpecForTest())
        val geometry = renderSpec.geometry as MainKeyGeometrySpec
        val topLabelGroup = requireNotNull(geometry.topLabelGroup)
        val fLabel = requireNotNull(renderSpec.label("main-f"))
        val gLabel = requireNotNull(renderSpec.label("main-g"))
        val letterLabel = requireNotNull(renderSpec.label("main-letter"))
        val slot = KeypadTopology.slotFor(12)
        val expectedBodyLeft = when (slot.family) {
            KeypadKeyFamily.STANDARD -> MEASURED_WIDTH.toFloat() / R47ReferenceGeometry.STANDARD_PITCH
            KeypadKeyFamily.ENTER -> MEASURED_WIDTH.toFloat() / R47ReferenceGeometry.ENTER_WIDTH
            KeypadKeyFamily.NUMERIC_MATRIX -> MEASURED_WIDTH.toFloat() / R47ReferenceGeometry.MATRIX_PITCH
            KeypadKeyFamily.BASE_OPERATOR -> MEASURED_WIDTH.toFloat() / R47ReferenceGeometry.STANDARD_KEY_WIDTH
            KeypadKeyFamily.SOFTKEY -> error("Softkeys do not use the main-key render spec")
        }

        assertEquals(view.currentMainKeyBodyWidthForTest(), geometry.bodyBounds.width, 0.01f)
        assertEquals(expectedBodyLeft, geometry.bodyBounds.left, 0.01f)
        assertEquals(geometry.bodyBounds.centerX, geometry.primaryAnchor.x, 0.01f)
        assertEquals(geometry.bodyBounds.centerY, geometry.primaryAnchor.y, 0.01f)
        assertEquals(requireNotNull(fLabel.bounds).left, topLabelGroup.bounds.left, 0.01f)
        assertEquals(requireNotNull(gLabel.bounds).right, topLabelGroup.bounds.right, 0.01f)
        assertEquals(
            geometry.bodyBounds.centerX + view.currentTopLabelPlacementForTest().centerShift,
            topLabelGroup.groupCenterX,
            0.01f,
        )
        assertEquals(requireNotNull(geometry.fourthLabelAnchor).x, letterLabel.anchor.x, 0.01f)
        assertEquals(requireNotNull(geometry.fourthLabelAnchor).y, letterLabel.anchor.y, 0.01f)
        assertEquals("7, f LASTx, g STK", renderSpec.accessibility.contentDescription)
    }

    @Test
    fun detachedMirrorBridgeTracksResolvedRenderSpecBounds() {
        val view = createMeasuredView(
            code = 12,
            keyState = KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "LASTx",
                gLabel = "STK",
                letterLabel = "A",
                styleRole = KeypadSceneContract.STYLE_DEFAULT,
                isEnabled = true,
            ),
        )

        val renderSpec = requireNotNull(view.currentMainKeyRenderSpecForTest())
        val primary = requireNotNull(requireNotNull(renderSpec.label("main-primary")).bounds)
        val f = requireNotNull(requireNotNull(renderSpec.label("main-f")).bounds)
        val g = requireNotNull(requireNotNull(renderSpec.label("main-g")).bounds)
        val letter = requireNotNull(requireNotNull(renderSpec.label("main-letter")).bounds)

        assertEquals(primary.left, view.primaryLabel.translationX, 0.01f)
        assertEquals(primary.top, view.primaryLabel.translationY, 0.01f)
        assertEquals(f.left, view.fLabel.translationX, 0.01f)
        assertEquals(f.top, view.fLabel.translationY, 0.01f)
        assertEquals(g.left, view.gLabel.translationX, 0.01f)
        assertEquals(g.top, view.gLabel.translationY, 0.01f)
        assertEquals(letter.left, view.letterLabel.translationX, 0.01f)
        assertEquals(letter.top, view.letterLabel.translationY, 0.01f)
    }

    private fun createMeasuredView(
        code: Int,
        keyState: KeypadKeySnapshot,
    ): CalculatorKeyView {
        return CalculatorKeyView(context).apply {
            setKey(
                KeypadTopology.slotFor(code),
                KeypadFontSet(standard = null, numeric = null, tiny = null),
            )
            updateLabels(snapshotFor(code, keyState))
            measure(exactly(MEASURED_WIDTH), exactly(MEASURED_HEIGHT))
            layout(0, 0, MEASURED_WIDTH, MEASURED_HEIGHT)
        }
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

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }

    private companion object {
        private const val MEASURED_WIDTH = 331
        private const val MEASURED_HEIGHT = 260
    }
}
