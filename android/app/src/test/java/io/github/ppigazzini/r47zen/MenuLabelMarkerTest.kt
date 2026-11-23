package io.github.ppigazzini.r47zen

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.numericFloat
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor

// The menu-label marker is a shape of its own, not a text decoration. The
// platform's own underline is the oracle: Paint reads it from the standard
// font's post table, and drawText draws it, so a marker that matches it pixel
// for pixel draws what the font defines, wherever the solver put and scaled
// the label.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MenuLabelMarkerTest {
    @Test
    fun barEmConstantsAreTheStandardFontsUnderlineMetrics() {
        // getUnderlinePosition and getUnderlineThickness are API 29; Robolectric
        // runs this class at the target SDK. The app reads the constants, so it
        // marks labels the same way on API 24 to 28.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val paint = Paint().apply { typeface = Typeface.createFromAsset(activity.assets, STANDARD_FONT) }

        for (size in listOf(20f, 48f, 64f)) {
            paint.textSize = size
            assertEquals(
                "top at $size",
                MenuLabelMarkerGeometry.BAR_TOP_EM * size,
                paint.underlinePosition,
                EM_TOLERANCE * size,
            )
            assertEquals(
                "thickness at $size",
                MenuLabelMarkerGeometry.BAR_THICKNESS_EM * size,
                paint.underlineThickness,
                EM_TOLERANCE * size,
            )
        }
    }

    @Test
    fun everyUnscaledMarkedLabelDrawsWhatThePlatformUnderlineDraws() {
        // A label the solver shrank keeps a full-weight bar, which is no longer
        // its own underline; barKeepsTheUnscaledWeightUnderTheLineBox holds those.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var compared = 0
        for (fixture in KeypadFixtureResources.loadAll()) {
            for (key in laidOutKeys(activity, fixture)) {
                val placement = key.currentTopLabelPlacementForTest()
                val unscaled = markedLabels(key).filter { (slot, _, _) ->
                    (if (slot == MainKeyLabelSlot.F) placement.fScale else placement.gScale) == 1f
                }
                for ((labelSlot, label, marker) in unscaled) {
                    val where = "${fixture.name} key ${key.keyCode} ${labelSlot.name} '${label.text}'"
                    assertEquals(where, platformUnderlined(label).pixels(), labelWithMarker(label, marker).pixels())
                    compared++
                }
            }
        }
        assertTrue("no marked label was compared", compared > 0)
    }

    @Test
    fun barKeepsTheUnscaledWeightUnderTheLineBox() {
        // A label at scale s and unscaled size u has its line-box bottom where
        // the lane puts every label's, and its baseline 0.2 s u above it (the
        // standard font's descent). The bar keeps u's weight and distance.
        runBlocking {
            checkAll(
                PropTestConfig(seed = 20261123L, iterations = 256),
                Arb.numericFloat(0.3f, 1f),
                Arb.numericFloat(20f, 80f),
            ) { scale, unscaled ->
                val size = unscaled * scale
                val bottom = 100f
                val label = barLabel(size, bottom)
                val marked = MarkedLabel(label, bottom - FONT_DESCENT_EM * size, Color.WHITE, unscaled)
                val bar = requireNotNull(MenuLabelMarkerGeometry.marker(UNDERLINE, "bar", marked)).bounds

                assertEquals(
                    (MenuLabelMarkerGeometry.BAR_TOP_EM - FONT_DESCENT_EM) * unscaled,
                    bar.top - bottom,
                    GEOMETRY_TOLERANCE,
                )
                assertEquals(MenuLabelMarkerGeometry.BAR_THICKNESS_EM * unscaled, bar.height, GEOMETRY_TOLERANCE)
                assertEquals(requireNotNull(label.bounds).left, bar.left, 0f)
                assertEquals(requireNotNull(label.bounds).right, bar.right, 0f)
            }
        }
    }

    @Test
    fun everyBarOfALaneSitsOnOneLineAtOneWeight() {
        // The USER scene's lanes mix unscaled and solver-scaled labels.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scene = KeypadFixtureResources.load("user-menus-scene")
        for (screen in PHONE_SCREENS) {
            val keysByCode = keysOf(laidOut(activity, scene, UNDERLINE, screen)).associateBy { it.keyCode }
            KeypadLane.entries.filter { it != KeypadLane.SOFTKEY_ROW }.forEach { lane ->
                // Each bar's top in the overlay's coordinates, and its weight.
                val bars = KeypadTopology.slotsForLane(lane).mapNotNull { keysByCode[it.code] }.flatMap { key ->
                    markedLabels(key).map { (_, _, marker) ->
                        Triple(key.keyCode, key.top + marker.bounds.top, marker.bounds.height)
                    }
                }
                val where = "$lane at ${screen.first}x${screen.second}"
                bars.forEach { (code, top, weight) ->
                    assertEquals("$where key $code top", bars.first().second, top, VIEW_ROUNDING_TOLERANCE)
                    assertEquals("$where key $code weight", bars.first().third, weight, VIEW_ROUNDING_TOLERANCE)
                }
            }
        }
    }

    @Test
    fun markerFollowsTheMenuRolesOnly() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (fixture in KeypadFixtureResources.loadAll()) {
            val snapshot = fixture.snapshot()
            for (key in laidOutKeys(activity, fixture)) {
                val spec = key.currentMainKeyRenderSpecForTest() ?: continue
                val keyState = snapshot.keyStateFor(key.keyCode)
                for ((labelSlot, markerSlot) in MARKED_SLOTS) {
                    // The roles straight from the scene, not through the code under test.
                    val menuRole = when (labelSlot) {
                        MainKeyLabelSlot.F ->
                            keyState.labelRole(KeypadSceneContract.LABEL_F) == KeypadSceneContract.TEXT_ROLE_F_UNDERLINE
                        else ->
                            keyState.labelRole(KeypadSceneContract.LABEL_G) == KeypadSceneContract.TEXT_ROLE_G_UNDERLINE
                    }
                    val expected = spec.label(labelSlot) != null && menuRole
                    assertEquals(
                        "${fixture.name} key ${key.keyCode} ${labelSlot.name}",
                        expected,
                        spec.adornment(markerSlot) != null,
                    )
                }
            }
        }
    }

    @Test
    fun keyDrawsItsMarkerAsAVectorRectAndNoBitmap() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val keys = laidOutKeys(activity, KeypadFixtureResources.load("default-keypad"))
        val key = keys.first { markedLabels(it).any { (slot, _, _) -> slot == MainKeyLabelSlot.G } }
        val marker = markedLabels(key).first { (slot, _, _) -> slot == MainKeyLabelSlot.G }.third
        val canvas = RecordingCanvas(Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888))

        key.draw(canvas)

        assertEquals("bitmaps drawn by key ${key.keyCode}", 0, canvas.bitmaps)
        assertTrue(
            "key ${key.keyCode} drew no rect at its marker ${marker.bounds}",
            canvas.rects.any { it.first == marker.bounds && it.second == marker.color },
        )
    }

    @Test
    fun capHeightEmIsTheStandardFontsCapitalHeight() {
        // The ink of "H" runs from the baseline to the capital height.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val paint = Paint().apply { typeface = Typeface.createFromAsset(activity.assets, STANDARD_FONT) }
        val ink = Rect()

        for (size in listOf(20f, 50f, 100f)) {
            paint.textSize = size
            paint.getTextBounds("H", 0, 1, ink)
            assertEquals(
                "cap at $size",
                MenuLabelMarkerGeometry.CAP_HEIGHT_EM * size,
                -ink.top.toFloat(),
                INK_TOLERANCE,
            )
        }
    }

    @Test
    fun everyBoxHoldsItsLabelsInkAndStaysAboveItsKey() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val ink = LabelInk()
        for (layout in boxedLayouts(activity, listOf(SCREEN_WIDTH to SCREEN_HEIGHT))) {
            for (key in layout.keys) {
                markedLabels(key).forEach { marked -> assertBoxHoldsItsInk(layout, key, marked, ink) }
            }
        }
    }

    @Test
    fun boxesKeepTheIntragapAndTheLaneGap() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (layout in boxedLayouts(activity, PHONE_SCREENS)) {
            val keysByCode = layout.keys.associateBy { it.keyCode }
            KeypadLane.entries.filter { it != KeypadLane.SOFTKEY_ROW }.forEach { lane ->
                assertLaneKeepsItsGaps(layout, KeypadTopology.slotsForLane(lane).mapNotNull { keysByCode[it.code] })
            }
        }
    }

    @Test
    fun boxedMarkersMeetTheContrastBars() {
        // The keypad's background is the shell's black; a box carries meaning,
        // so it needs 3:1 to it, and reverse video's text 4.5:1 to its box.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for (style in BOXED_STYLES) {
            var checked = 0
            for (key in keysOf(laidOut(activity, KeypadFixtureResources.load("default-keypad"), style))) {
                for ((slot, label, marker) in markedLabels(key)) {
                    val where = "$style key ${key.keyCode} ${slot.name}"
                    assertTrue(where, WcagContrast.ratio(marker.color, Color.BLACK) >= WcagContrast.NON_TEXT_MINIMUM)
                    if (style == MenuLabelMarkerStyle.REVERSE) {
                        assertEquals(where, MenuLabelMarkerGeometry.REVERSE_TEXT_COLOR, label.color)
                        val textRatio = WcagContrast.ratio(label.color, marker.color)
                        assertTrue(where, textRatio >= WcagContrast.SMALL_TEXT_MINIMUM)
                    }
                    checked++
                }
            }
            assertTrue("$style checked no marker", checked > 0)
        }
    }

    @Test
    fun reverseVideoDrawsItsBoxBeforeItsLabel() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scene = KeypadFixtureResources.load("default-keypad")
        val keys = keysOf(laidOut(activity, scene, MenuLabelMarkerStyle.REVERSE))
        val key = keys.first { markedLabels(it).any { (slot, _, _) -> slot == MainKeyLabelSlot.G } }
        val (_, label, marker) = markedLabels(key).first { (slot, _, _) -> slot == MainKeyLabelSlot.G }
        val canvas = RecordingCanvas(Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888))

        key.draw(canvas)

        val box = canvas.events.indexOf("roundRect ${marker.bounds}")
        val text = canvas.events.indexOf("text ${label.text}")
        assertTrue("key ${key.keyCode} drew no box", box >= 0)
        assertTrue("key ${key.keyCode} drew its box at $box, after its label at $text", box in 0 until text)
    }

    @Test
    fun aBoxedStyleLaysTheKeypadOutAgainAndUnderlineRestoresIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = laidOut(activity, KeypadFixtureResources.load("user-menus-scene"), MenuLabelMarkerStyle.UNDERLINE)
        val underlined = keysOf(overlay).associate { it.keyCode to it.currentTopLabelPlacementForTest() }

        ReplicaKeypadLayout.applyMenuLabelMarkerStyle(overlay, BOX, UNDERLINE)
        assertTrue(overlay.isLayoutRequested)
        layOut(overlay)
        val boxed = keysOf(overlay).associate { it.keyCode to it.currentTopLabelPlacementForTest() }
        ReplicaKeypadLayout.applyMenuLabelMarkerStyle(overlay, UNDERLINE, BOX)
        layOut(overlay)

        assertTrue("the boxes moved no label", boxed != underlined)
        assertEquals(underlined, keysOf(overlay).associate { it.keyCode to it.currentTopLabelPlacementForTest() })
    }

    // One keypad laid out with a boxed style, and where, for messages.
    private class BoxedLayout(
        val where: String,
        val style: MenuLabelMarkerStyle,
        val keys: List<CalculatorKeyView>,
    )

    private fun boxedLayouts(activity: Activity, screens: List<Pair<Int, Int>>): List<BoxedLayout> {
        return BOXED_STYLES.flatMap { style ->
            BOX_SCENES.flatMap { scene ->
                screens.map { screen ->
                    val overlay = laidOut(activity, KeypadFixtureResources.load(scene), style, screen)
                    BoxedLayout("$style $scene at ${screen.first}x${screen.second}", style, keysOf(overlay))
                }
            }
        }
    }

    // The ink of a label as the platform measures it, around its baseline.
    private class LabelInk {
        val paint: Paint = C47TextRenderer.newTextPaint()
        val bounds = Rect()

        fun measure(label: LabelSpec): Rect {
            C47TextRenderer.configureTextPaint(
                paint,
                label.typeface,
                label.textSize,
                label.align,
                label.color,
                label.alpha,
                label.textScaleX,
            )
            paint.getTextBounds(label.text, 0, label.text.length, bounds)
            return bounds
        }
    }

    private fun assertBoxHoldsItsInk(
        layout: BoxedLayout,
        key: CalculatorKeyView,
        marked: Triple<MainKeyLabelSlot, LabelSpec, MenuLabelMarkerSpec>,
        ink: LabelInk,
    ) {
        val (slot, label, marker) = marked
        val where = "${layout.where} key ${key.keyCode} ${slot.name} '${label.text}'"
        val glyphs = Rect(ink.measure(label))
        val capTop = ink.measure(label.copy(text = "H")).top
        val left = requireNotNull(label.bounds).left
        val baseline = C47TextRenderer.baselineY(label, ink.paint)
        val box = marker.bounds
        val body = (key.currentMainKeyRenderSpecForTest()?.geometry as MainKeyGeometrySpec).bodyBounds
        assertTrue("$where: ink left", box.left <= left + glyphs.left)
        assertTrue("$where: ink right", box.right >= left + glyphs.right)
        assertTrue("$where: ink top", box.top <= baseline + glyphs.top)
        // The caps stand on the baseline inside the box, centered between its
        // edges: the platform's ink of "H" gives the caps' center.
        assertTrue("$where: baseline", box.bottom > baseline)
        assertEquals("$where: centered on the caps", baseline + capTop / 2f, box.centerY, INK_TOLERANCE)
        assertTrue("$where: over the key body", box.bottom + marker.strokeWidth / 2f < body.top)
        val placement = key.currentTopLabelPlacementForTest()
        val unscaled = label.textSize / if (slot == MainKeyLabelSlot.F) placement.fScale else placement.gScale
        val expectedStroke = if (layout.style == MenuLabelMarkerStyle.ROUNDRECT) {
            MenuLabelMarkerGeometry.BOX_STROKE_EM * unscaled
        } else {
            0f
        }
        assertEquals("$where: stroke", expectedStroke, marker.strokeWidth, STROKE_TOLERANCE)
    }

    // Within each key, the boxes keep the intragap; between keys, twice it.
    private fun assertLaneKeepsItsGaps(layout: BoxedLayout, laneKeys: List<CalculatorKeyView>) {
        val groups = laneKeys.mapNotNull { key -> groupExtent(key)?.let { key to it } }
        for ((key, extent) in groups) {
            val gap = requireNotNull(key.buildTopLabelLaneInput()).gapWidth
            extent.intragap?.let { intragap ->
                assertTrue("${layout.where} key ${key.keyCode}: intragap $intragap", intragap >= gap - GAP_TOLERANCE)
            }
        }
        groups.zipWithNext { (left, leftExtent), (right, rightExtent) ->
            val gap = maxOf(
                requireNotNull(left.buildTopLabelLaneInput()).gapWidth,
                requireNotNull(right.buildTopLabelLaneInput()).gapWidth,
            )
            val between = rightExtent.left - leftExtent.right
            assertTrue(
                "${layout.where} keys ${left.keyCode}-${right.keyCode}: $between apart",
                between >= 2f * gap - GAP_TOLERANCE,
            )
        }
    }

    // The outer left and right of a key's f and g group, boxes and their
    // stroke included, in the overlay's coordinates, and the space between
    // its f and g.
    private data class GroupExtent(val left: Float, val right: Float, val intragap: Float?)

    private fun groupExtent(key: CalculatorKeyView): GroupExtent? {
        val spec = key.currentMainKeyRenderSpecForTest()
        val f = spec?.let { outerExtent(it, MainKeyLabelSlot.F, MainKeyAdornmentSlot.F_MENU_MARKER) } ?: return null
        val g = outerExtent(spec, MainKeyLabelSlot.G, MainKeyAdornmentSlot.G_MENU_MARKER)
        return GroupExtent(
            left = key.left + f.first,
            right = key.left + (g ?: f).second,
            intragap = g?.let { it.first - f.second },
        )
    }

    // A label's left and right, or its marker's with the stroke, if it has one.
    private fun outerExtent(
        spec: KeyRenderSpec,
        labelSlot: MainKeyLabelSlot,
        markerSlot: MainKeyAdornmentSlot,
    ): Pair<Float, Float>? {
        val label = spec.label(labelSlot)?.bounds
        val marker = spec.adornment(markerSlot) as? MenuLabelMarkerSpec
        return when {
            marker != null -> {
                val halfStroke = marker.strokeWidth / 2f
                (marker.bounds.left - halfStroke) to (marker.bounds.right + halfStroke)
            }
            label != null -> label.left to label.right
            else -> null
        }
    }

    // A label laid out as the keypad lays an f label: left-aligned, its line
    // box ending at bottom.
    private fun barLabel(size: Float, bottom: Float): LabelSpec {
        return LabelSpec(
            id = MainKeyLabelSlot.F.id,
            text = "MENU",
            visible = true,
            anchor = PointSpec(10f, bottom),
            bounds = RectSpec(10f, bottom - size, 10f + 2f * size, bottom),
            typeface = null,
            textSize = size,
            color = Color.WHITE,
            align = Paint.Align.LEFT,
            verticalAnchor = C47TextRenderer.TEXT_ANCHOR_BOTTOM,
        )
    }

    // The key's labels that carry a marker, with the marker.
    private fun markedLabels(key: CalculatorKeyView): List<Triple<MainKeyLabelSlot, LabelSpec, MenuLabelMarkerSpec>> {
        val spec = key.currentMainKeyRenderSpecForTest() ?: return emptyList()
        return MARKED_SLOTS.mapNotNull { (labelSlot, markerSlot) ->
            val label = spec.label(labelSlot)
            val marker = spec.adornment(markerSlot) as? MenuLabelMarkerSpec
            if (label != null && marker != null) Triple(labelSlot, label, marker) else null
        }
    }

    private fun laidOutKeys(activity: Activity, fixture: ExportedKeypadFixture): List<CalculatorKeyView> {
        return keysOf(laidOut(activity, fixture, MenuLabelMarkerStyle.UNDERLINE))
    }

    private fun laidOut(
        activity: Activity,
        fixture: ExportedKeypadFixture,
        style: MenuLabelMarkerStyle,
        screen: Pair<Int, Int> = SCREEN_WIDTH to SCREEN_HEIGHT,
    ): ReplicaOverlay {
        val overlay = ReplicaOverlay(activity)
        overlay.onGeometryLaidOut = { ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay) }
        ReplicaKeypadLayout.rebuild(activity, overlay, {}, {}, fixture::snapshot)
        ReplicaKeypadLayout.applyMenuLabelMarkerStyle(overlay, style, MenuLabelMarkerStyle.DEFAULT)
        layOut(overlay, screen)
        return overlay
    }

    private fun layOut(overlay: ReplicaOverlay, screen: Pair<Int, Int> = SCREEN_WIDTH to SCREEN_HEIGHT) {
        overlay.measure(exactly(screen.first), exactly(screen.second))
        overlay.layout(0, 0, screen.first, screen.second)
    }

    private fun keysOf(overlay: ReplicaOverlay): List<CalculatorKeyView> {
        return (0 until overlay.childCount).map(overlay::getChildAt).filterIsInstance<CalculatorKeyView>()
    }

    // Draw the label with the platform's underline: one drawText with the
    // paint's underline on.
    private fun platformUnderlined(label: LabelSpec): Bitmap {
        return drawn(label) { canvas ->
            val paint = C47TextRenderer.newTextPaint()
            C47TextRenderer.configureTextPaint(
                paint,
                typeface = label.typeface,
                textSize = label.textSize,
                align = label.align,
                color = label.color,
                alpha = label.alpha,
                textScaleX = label.textScaleX,
            )
            paint.isUnderlineText = true
            canvas.drawText(label.text, label.anchor.x, C47TextRenderer.baselineY(label, Paint(paint)), paint)
        }
    }

    private fun labelWithMarker(label: LabelSpec, marker: MenuLabelMarkerSpec): Bitmap {
        return drawn(label) { canvas ->
            KeyRenderPainter.drawLabel(canvas, label, C47TextRenderer.newTextPaint())
            KeyRenderPainter.drawMenuLabelMarker(canvas, marker, Paint(Paint.ANTI_ALIAS_FLAG))
        }
    }

    // A bitmap around the label's line box and the space under it, in the key's
    // own coordinates.
    private fun drawn(label: LabelSpec, draw: (Canvas) -> Unit): Bitmap {
        val bounds = requireNotNull(label.bounds)
        val left = floor(bounds.left).toInt() - MARGIN
        val top = floor(bounds.top).toInt() - MARGIN
        val right = ceil(bounds.right).toInt() + MARGIN
        val bottom = ceil(bounds.bottom + label.textSize).toInt()
        val bitmap = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        val canvas = Canvas(bitmap)
        canvas.translate(-left.toFloat(), -top.toFloat())
        draw(canvas)
        return bitmap
    }

    private fun Bitmap.pixels(): List<Int> {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.toList()
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    // Record the shapes a key draws, and count any bitmap it draws.
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val rects = mutableListOf<Pair<RectSpec, Int>>()
        val events = mutableListOf<String>()
        var bitmaps = 0

        override fun drawRoundRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            rx: Float,
            ry: Float,
            paint: Paint,
        ) {
            events += "roundRect ${RectSpec(left, top, right, bottom)}"
            super.drawRoundRect(left, top, right, bottom, rx, ry, paint)
        }

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            events += "text $text"
            super.drawText(text, x, y, paint)
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += RectSpec(left, top, right, bottom) to paint.color
            super.drawRect(left, top, right, bottom, paint)
        }

        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            bitmaps++
            super.drawBitmap(bitmap, left, top, paint)
        }

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
            bitmaps++
            super.drawBitmap(bitmap, src, dst, paint)
        }

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
            bitmaps++
            super.drawBitmap(bitmap, src, dst, paint)
        }

        override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
            bitmaps++
            super.drawBitmap(bitmap, matrix, paint)
        }
    }

    private companion object {
        const val STANDARD_FONT = "fonts/C47__StandardFont.ttf"

        // Font units are thousandths of an em.
        const val EM_TOLERANCE = 0.001f

        // getTextBounds rounds the ink out to whole pixels.
        const val INK_TOLERANCE = 0.5f
        const val STROKE_TOLERANCE = 0.001f

        // Float slack on positions the solver and the spec compute.
        const val GAP_TOLERANCE = 0.01f
        const val GEOMETRY_TOLERANCE = 0.001f

        // Key views are sized in whole pixels, so the ENTER key's scale to its
        // design width and a standard key's differ in the third decimal.
        const val VIEW_ROUNDING_TOLERANCE = 0.05f

        // The standard font's descent, 200 of 1000 units.
        const val FONT_DESCENT_EM = 0.2f

        val BOXED_STYLES = MenuLabelMarkerStyle.entries.filter { it.boxed }
        val UNDERLINE = MenuLabelMarkerStyle.UNDERLINE
        val BOX = MenuLabelMarkerStyle.ROUNDRECT

        // FHD+, HD+, and QHD+ portrait phones, as KeypadTopLabelLayoutTest lays out.
        val PHONE_SCREENS = listOf(1080 to 2400, 720 to 1600, 1440 to 3120)
        val BOX_SCENES = listOf("default-keypad", "user-menus-scene")

        // An FHD+ portrait phone, where the USER scene's names are scaled.
        const val SCREEN_WIDTH = 1080
        const val SCREEN_HEIGHT = 2400
        const val MARGIN = 4

        val MARKED_SLOTS = listOf(
            MainKeyLabelSlot.F to MainKeyAdornmentSlot.F_MENU_MARKER,
            MainKeyLabelSlot.G to MainKeyAdornmentSlot.G_MENU_MARKER,
        )
    }
}
