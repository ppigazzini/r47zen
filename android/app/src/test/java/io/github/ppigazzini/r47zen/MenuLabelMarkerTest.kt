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
    fun everyMarkedLabelDrawsWhatThePlatformUnderlineDraws() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var compared = 0
        var comparedScaled = 0
        for (fixture in KeypadFixtureResources.loadAll()) {
            val keys = laidOutKeys(activity, fixture)
            for (key in keys) {
                val placement = key.currentTopLabelPlacementForTest()
                for ((labelSlot, label, marker) in markedLabels(key)) {
                    val where = "${fixture.name} key ${key.keyCode} ${labelSlot.name} '${label.text}'"
                    assertEquals(where, platformUnderlined(label).pixels(), labelWithMarker(label, marker).pixels())
                    compared++
                    val scale = if (labelSlot == MainKeyLabelSlot.F) placement.fScale else placement.gScale
                    if (scale < 1f) comparedScaled++
                }
            }
        }
        assertTrue("no marked label was compared", compared > 0)
        assertTrue("no solver-scaled marked label was compared", comparedScaled > 0)
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
        val overlay = ReplicaOverlay(activity)
        overlay.onGeometryLaidOut = { ReplicaKeypadLayout.applyTopLabelPlacementsAfterLayout(overlay) }
        ReplicaKeypadLayout.rebuild(activity, overlay, {}, {}, fixture::snapshot)
        overlay.measure(exactly(SCREEN_WIDTH), exactly(SCREEN_HEIGHT))
        overlay.layout(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
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
        var bitmaps = 0

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
