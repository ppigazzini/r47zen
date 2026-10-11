package io.github.ppigazzini.r47zen

import android.app.Activity
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

// The keypad applies the top-label lane solver's placements to its key views.
// TopLabelLaneLayoutTest holds the solver's own rules.
@RunWith(RobolectricTestRunner::class)
class KeypadTopLabelLayoutTest {
    @Test
    fun layoutAppliesTheSolvedPlacementToEachKey() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        overlay.onGeometryLaidOut = {
            ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay)
        }

        val laneCodes = KeypadTopology.slotsForLane(KeypadLane.MATRIX_ROW_1)
            .filter { !it.isFunctionKey }
            .map { it.code }
        val leftCode = laneCodes[1]
        val rightCode = laneCodes[2]
        val snapshot = keypadSnapshotWith(
            leftCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "VERY_LONG_LEFT_FUNCTION",
                gLabel = "VERY_LONG_LEFT_SECONDARY",
                isEnabled = true,
            ),
            rightCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "8",
                fLabel = "VERY_LONG_RIGHT_FUNCTION",
                gLabel = "VERY_LONG_RIGHT_SECONDARY",
                isEnabled = true,
            ),
        )

        val leftView = createAttachedMainKeyView(activity, leftCode)
        val rightView = createAttachedMainKeyView(activity, rightCode)
        overlay.addReplicaView(leftView, 0f, 0f, 272f, 260f)
        overlay.addReplicaView(rightView, 120f, 0f, 272f, 260f)

        ReplicaKeypadLayout.updateDynamicKeys(overlay, snapshot)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(TopLabelLanePlacement.DEFAULT, leftView.currentTopLabelPlacementForTest())
        assertEquals(TopLabelLanePlacement.DEFAULT, rightView.currentTopLabelPlacementForTest())

        activity.setContentView(overlay)
        overlay.measure(exactly(1200), exactly(2400))
        overlay.layout(0, 0, 1200, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(leftView.hasMeasuredTopLabelAnchors())
        assertTrue(rightView.hasMeasuredTopLabelAnchors())
        val leftBounds = requireNotNull(leftView.currentMainKeyBodyHorizontalBounds())
        val rightBounds = requireNotNull(rightView.currentMainKeyBodyHorizontalBounds())
        val expectedPlacements = TopLabelLaneLayout.solve(
            listOf(
                requireNotNull(leftView.buildTopLabelLaneInput()),
                requireNotNull(rightView.buildTopLabelLaneInput()),
            ),
        )
        assertEquals(expectedPlacements.getValue(leftCode), leftView.currentTopLabelPlacementForTest())
        assertEquals(expectedPlacements.getValue(rightCode), rightView.currentTopLabelPlacementForTest())
    }

    @Test
    fun layoutKeepsOuterLabelsInsideTheOverlay() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        overlay.onGeometryLaidOut = {
            ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay)
        }

        val laneCodes = KeypadTopology.slotsForLane(KeypadLane.SMALL_ROW_1)
            .filter { !it.isFunctionKey }
            .map { it.code }
        val leftCode = laneCodes.first()
        val rightCode = laneCodes.last()
        val snapshot = keypadSnapshotWith(
            leftCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "1",
                fLabel = "WWWWWWWWWWWWWWWWWWWWWWWW",
                gLabel = "WWWWWWWWWWWWWWWWWWWWWWWW",
                isEnabled = true,
            ),
            rightCode to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "6",
                fLabel = "WWWWWWWWWWWWWWWWWWWWWWWW",
                gLabel = "WWWWWWWWWWWWWWWWWWWWWWWW",
                isEnabled = true,
            ),
        )

        val leftView = createAttachedMainKeyView(activity, leftCode)
        val rightView = createAttachedMainKeyView(activity, rightCode)
        overlay.addReplicaView(leftView, -40f, 0f, 272f, 260f)
        overlay.addReplicaView(rightView, 520f, 0f, 272f, 260f)

        ReplicaKeypadLayout.updateDynamicKeys(overlay, snapshot)
        shadowOf(Looper.getMainLooper()).idle()

        activity.setContentView(overlay)
        overlay.measure(exactly(792), exactly(2400))
        overlay.layout(0, 0, 792, 2400)
        shadowOf(Looper.getMainLooper()).idle()

        val leftInput = requireNotNull(leftView.buildTopLabelLaneInput(minLeftEdge = 0f))
        val rightInput = requireNotNull(rightView.buildTopLabelLaneInput(maxRightEdge = overlay.width.toFloat()))
        val expectedPlacements = TopLabelLaneLayout.solve(listOf(leftInput, rightInput))

        assertEquals(expectedPlacements.getValue(leftCode), leftView.currentTopLabelPlacementForTest())
        assertEquals(expectedPlacements.getValue(rightCode), rightView.currentTopLabelPlacementForTest())
        assertScreenBoundsRespected(leftInput, leftView.currentTopLabelPlacementForTest())
        assertScreenBoundsRespected(rightInput, rightView.currentTopLabelPlacementForTest())
    }

    @Test
    fun scaledTopLabelKeepsItsGlyphBottomOnTheUnscaledSiblings() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)

        val keyView = createAttachedMainKeyView(activity, 12)
        container.addView(keyView, FrameLayout.LayoutParams(272, 260))
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)

        val snapshot = keypadSnapshotWith(
            12 to KeypadKeySnapshot.EMPTY.copy(
                primaryLabel = "7",
                fLabel = "ASSIGN",
                gLabel = "MYALPHA",
                isEnabled = true,
            ),
        )

        keyView.updateLabels(snapshot)
        shadowOf(Looper.getMainLooper()).idle()
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)

        keyView.applyTopLabelPlacement(
            TopLabelLanePlacement(
                fScale = 1f,
                gScale = R47TopLabelSolverPolicy.TOP_F_G_LABEL_MIN_SCALE,
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
        container.measure(exactly(400), exactly(300))
        container.layout(0, 0, 400, 300)

        val fBottom = glyphBottomY(keyView.fLabel)
        val gBottom = glyphBottomY(keyView.gLabel)

        assertTrue(keyView.gLabel.textSize < keyView.fLabel.textSize)
        assertEquals(fBottom, gBottom, 1.0f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyExportedKeypadLaysOutCenteredAndUnscaledOnPhoneScreens() {
        // With the shipped C47 fonts, the labels of every exported keypad scene
        // fit their keys at common phone resolutions: no top label is shifted
        // or scaled. The overlay is laid out without a window, so no window
        // traversal can lay it out again at another size.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Typeface.createFromAsset(activity.assets, "fonts/C47__StandardFont.ttf")

        var solvedKeys = 0
        for (fixture in KeypadFixtureResources.loadAll()) {
            for ((width, height) in PHONE_SCREENS) {
                val overlay = ReplicaOverlay(activity)
                overlay.onGeometryLaidOut = { ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay) }
                ReplicaKeypadLayout.rebuild(activity, overlay, {}, {}, fixture::snapshot)
                overlay.measure(exactly(width), exactly(height))
                overlay.layout(0, 0, width, height)

                val keys = (0 until overlay.childCount).map(overlay::getChildAt).filterIsInstance<CalculatorKeyView>()
                val labelled = keys.filter { it.fLabel.isVisible && it.fLabel.text.isNotBlank() }
                assertEquals(
                    "${fixture.name} at ${width}x$height: keys with a top label that never reached the lane solver",
                    emptyList<Int>(),
                    labelled.filter { it.buildTopLabelLaneInput() == null }.map { it.keyCode },
                )
                solvedKeys += labelled.size
                keys.forEach { key ->
                    assertEquals(
                        "${fixture.name} at ${width}x$height: key ${key.keyCode} '${key.fLabel.text}'",
                        TopLabelLanePlacement.DEFAULT,
                        key.currentTopLabelPlacementForTest(),
                    )
                }
            }
        }
        assertTrue("no exported keypad put a top label through the lane solver", solvedKeys > 0)
    }

    private fun createAttachedMainKeyView(activity: Activity, code: Int): CalculatorKeyView {
        return CalculatorKeyView(activity).apply {
            setKey(
                KeypadTopology.slotFor(code),
                KeypadFontSet(standard = null, numeric = null, tiny = null),
            )
        }
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }

    private fun solvedGroupBounds(
        input: TopLabelLaneGroupInput,
        placement: TopLabelLanePlacement,
    ): Pair<Float, Float> {
        val centerX = input.centerX + placement.centerShift
        val totalWidth =
            input.fTextWidth * placement.fScale +
                input.gapWidth +
                input.gTextWidth * placement.gScale
        return (centerX - totalWidth / 2f) to (centerX + totalWidth / 2f)
    }

    private fun assertScreenBoundsRespected(
        group: TopLabelLaneGroupInput,
        placement: TopLabelLanePlacement,
    ) {
        val solvedBounds = solvedGroupBounds(group, placement)
        if (group.minLeftEdge.isFinite()) {
            assertTrue(solvedBounds.first + 0.001f >= group.minLeftEdge)
        }
        if (group.maxRightEdge.isFinite()) {
            assertTrue(solvedBounds.second <= group.maxRightEdge + 0.001f)
        }
    }

    private fun glyphBottomY(label: TextView): Float {
        val metrics = Paint(label.paint).fontMetrics
        val bottomOffset = -metrics.ascent + metrics.descent
        return label.top + label.translationY + bottomOffset
    }

    private companion object {
        // FHD+, HD+, and QHD+ portrait phones.
        val PHONE_SCREENS = listOf(1080 to 2400, 720 to 1600, 1440 to 3120)
    }
}
