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
        requireNotNull(leftView.currentMainKeyBodyHorizontalBounds())
        requireNotNull(rightView.currentMainKeyBodyHorizontalBounds())
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
    fun everyPrintedKeypadLaysOutUnscaled_andCenteredUnlessBoxed() {
        // With the shipped C47 fonts, the printed labels of every exported
        // keypad scene fit their keys at common phone resolutions under every
        // menu marker: no top label is scaled, and under a marker that takes no
        // width none is shifted. A box widens its label, so the solver may shift
        // one to make room. A USER keyboard shows names the user chose, so its
        // scenes are held to the lane rules instead, below. The overlay is laid
        // out without a window, so no window traversal can lay it out again at
        // another size.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Typeface.createFromAsset(activity.assets, "fonts/C47__StandardFont.ttf")

        var solvedKeys = 0
        val printed = KeypadFixtureResources.loadAll().filterNot { it.snapshot().userModeEnabled }
        val cases = MenuLabelMarkerStyle.entries.flatMap { style ->
            printed.flatMap { fixture -> PHONE_SCREENS.map { screen -> Triple(style, fixture, screen) } }
        }
        for ((style, fixture, screen) in cases) {
            val where = "$style ${fixture.name} at ${screen.first}x${screen.second}"
            val keys = laidOutKeys(activity, fixture, style, screen)
            val labeled = keys.filter { it.fLabel.isVisible && it.fLabel.text.isNotBlank() }
            assertEquals(
                "$where: keys with a top label that never reached the lane solver",
                emptyList<Int>(),
                labeled.filter { it.buildTopLabelLaneInput() == null }.map { it.keyCode },
            )
            solvedKeys += labeled.size
            keys.forEach { key ->
                val placement = key.currentTopLabelPlacementForTest()
                val unscaled = placement.copy(fScale = 1f, gScale = 1f)
                val expected = if (style.boxed) unscaled else TopLabelLanePlacement.DEFAULT
                assertEquals("$where: key ${key.keyCode} '${key.fLabel.text}'", expected, placement)
            }
        }
        assertTrue("no exported keypad put a top label through the lane solver", solvedKeys > 0)
    }

    private fun laidOutKeys(
        activity: Activity,
        fixture: ExportedKeypadFixture,
        style: MenuLabelMarkerStyle,
        screen: Pair<Int, Int>,
    ): List<CalculatorKeyView> {
        val overlay = ReplicaOverlay(activity)
        overlay.onGeometryLaidOut = { ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay) }
        ReplicaKeypadLayout.rebuild(activity, overlay, {}, {}, fixture::snapshot)
        ReplicaKeypadLayout.applyMenuLabelMarkerStyle(overlay, style, MenuLabelMarkerStyle.DEFAULT)
        overlay.measure(exactly(screen.first), exactly(screen.second))
        overlay.layout(0, 0, screen.first, screen.second)
        return (0 until overlay.childCount).map(overlay::getChildAt).filterIsInstance<CalculatorKeyView>()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun userMenuNamesReachTheSolverAndKeepTheLaneRulesOnPhoneScreens() {
        // The USER scene puts 7-glyph user menu names on whole rows, more than
        // the keys hold: the solver must shift and scale them, and the lane rules
        // page 05 lists must still hold for every lane it solves, on the labels
        // as the keys draw them, not on the solver's own model of their widths.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Typeface.createFromAsset(activity.assets, "fonts/C47__StandardFont.ttf")
        val fixture = KeypadFixtureResources.load("user-menus-scene")

        for ((width, height) in PHONE_SCREENS) {
            val overlay = ReplicaOverlay(activity)
            overlay.onGeometryLaidOut = { ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay) }
            ReplicaKeypadLayout.rebuild(activity, overlay, {}, {}, fixture::snapshot)
            overlay.measure(exactly(width), exactly(height))
            overlay.layout(0, 0, width, height)
            val keysByCode = (0 until overlay.childCount).map(overlay::getChildAt)
                .filterIsInstance<CalculatorKeyView>()
                .associateBy { it.keyCode }

            var scaledLabels = 0
            for (lane in KeypadLane.entries.filter { it != KeypadLane.SOFTKEY_ROW }) {
                val slots = KeypadTopology.slotsForLane(lane)
                val solved = slots.mapNotNull { slot ->
                    val key = keysByCode[slot.code] ?: return@mapNotNull null
                    val input = key.buildTopLabelLaneInput() ?: return@mapNotNull null
                    Triple(input, key.currentTopLabelPlacementForTest(), drawnGroupBounds(key))
                }
                val where = "$lane at ${width}x$height"
                solved.firstOrNull()?.let { (_, _, drawn) ->
                    assertTrue("$where: past the left edge", drawn.first >= -POSITION_TOLERANCE)
                }
                solved.lastOrNull()?.let { (_, _, drawn) ->
                    assertTrue("$where: past the right edge", drawn.second <= width + POSITION_TOLERANCE)
                }
                solved.forEach { (input, placement, _) ->
                    assertTrue(
                        "$where: key ${input.code} f and g scales differ by more than one step",
                        kotlin.math.abs(placement.fScale - placement.gScale) <=
                            R47TopLabelSolverPolicy.TOP_F_G_LABEL_SCALE_STEP + SCALE_TOLERANCE,
                    )
                    if (placement.fScale < 1f) scaledLabels++
                    if (input.hasGLabel && placement.gScale < 1f) scaledLabels++
                }
                solved.zipWithNext { (left, _, leftDrawn), (right, _, rightDrawn) ->
                    val gap = rightDrawn.first - leftDrawn.second
                    val required = maxOf(left.gapWidth, right.gapWidth) * 2f
                    assertTrue(
                        "$where: keys ${left.code} and ${right.code} are $gap apart, under $required",
                        gap + POSITION_TOLERANCE >= required,
                    )
                }
            }
            assertTrue("no USER label was scaled at ${width}x$height", scaledLabels > 0)
        }
    }

    // The left of a key's f label and the right of its g label, or of its f
    // label alone, as the key draws them, in the overlay's coordinates.
    private fun drawnGroupBounds(key: CalculatorKeyView): Pair<Float, Float> {
        val spec = requireNotNull(key.currentMainKeyRenderSpecForTest())
        val f = requireNotNull(spec.label(MainKeyLabelSlot.F)?.bounds)
        val right = spec.label(MainKeyLabelSlot.G)?.bounds?.right ?: f.right
        return (key.left + f.left) to (key.left + right)
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
            input.fWidth(placement.fScale) + input.fFixedWidth +
                input.gapWidth +
                input.gWidth(placement.gScale) + input.gFixedWidth
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

        // Float slack on positions and scales the solver computes.
        const val POSITION_TOLERANCE = 0.01f
        const val SCALE_TOLERANCE = 0.0001f
    }
}
