package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class ReplicaOverlayGoldenTest {
    @Test
    fun packedLcd_firstFrame_isDecodedWhole_andInvalidatesTheWholeLcd() {
        // A host that attaches to a live runtime finds the row flags already
        // taken by its predecessor; its first frame must still reach the screen.
        val overlay = configuredOverlay()
        val packedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(packedBuffer, x = 64, y = 137)
        packedBuffer[137 * R47LcdContract.PACKED_ROW_SIZE_BYTES] = 0

        assertTrue(overlay.updatePackedLcd(packedBuffer))

        val dirtyRect = readPrivateField<Rect>(overlay, "dirtyRect")
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, overlay.width.toFloat(), overlay.height.toFloat())
        val lcdTop = projection.offsetY + spec.lcdWindowTop * projection.scale
        val lcdBottom = projection.offsetY + (spec.lcdWindowTop + spec.lcdWindowHeight) * projection.scale
        assertTrue("the first frame invalidates from the LCD top", dirtyRect.top <= lcdTop.toInt())
        assertTrue("the first frame invalidates to the LCD bottom", dirtyRect.bottom >= ceil(lcdBottom).toInt())
    }

    @Test
    fun packedLcd_singleDirtyRow_invalidatesOnlyTouchedRowSpan() {
        val overlay = overlayAfterFirstFrame()
        val touchedRow = 137
        val packedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(packedBuffer, x = 64, y = touchedRow)

        assertTrue(overlay.updatePackedLcd(packedBuffer))

        val dirtyRect = readPrivateField<Rect>(overlay, "dirtyRect")
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, overlay.width.toFloat(), overlay.height.toFloat())
        val lcdTop = projection.offsetY + spec.lcdWindowTop * projection.scale
        val lcdHeight = spec.lcdWindowHeight * projection.scale
        val expectedTop = (lcdTop + lcdHeight * (touchedRow.toFloat() / R47LcdContract.PIXEL_HEIGHT.toFloat())).toInt()
        val expectedBottom = ceil(
            lcdTop + lcdHeight * ((touchedRow + 1).toFloat() / R47LcdContract.PIXEL_HEIGHT.toFloat())
        ).toInt()
        val expectedLeft = (projection.offsetX + spec.lcdWindowLeft * projection.scale).toInt()
        val expectedRight = (
            projection.offsetX + (spec.lcdWindowLeft + spec.lcdWindowWidth) * projection.scale
        ).toInt()

        assertEquals(expectedLeft, dirtyRect.left)
        assertTrue(dirtyRect.top <= expectedTop)
        assertTrue(dirtyRect.top >= expectedTop - 1)
        assertEquals(expectedRight, dirtyRect.right)
        assertTrue(dirtyRect.bottom >= expectedBottom)
        assertTrue(dirtyRect.bottom <= expectedBottom + 1)
    }

    @Test
    fun packedLcd_singleDirtyRow_doesNotInvalidateFullLcdHeight() {
        val overlay = overlayAfterFirstFrame()
        val touchedRow = 11
        val packedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(packedBuffer, x = 8, y = touchedRow)

        assertTrue(overlay.updatePackedLcd(packedBuffer))

        val dirtyRect = readPrivateField<Rect>(overlay, "dirtyRect")
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, overlay.width.toFloat(), overlay.height.toFloat())
        val lcdTop = projection.offsetY + spec.lcdWindowTop * projection.scale
        val lcdBottom = projection.offsetY + (spec.lcdWindowTop + spec.lcdWindowHeight) * projection.scale
        val fullLcdHeight = ceil(lcdBottom).toInt() - lcdTop.toInt()

        assertTrue("dirty rect should be row-band sized, not full LCD height", dirtyRect.height() < fullLcdHeight)
    }

    @Test
    fun packedLcd_singleDirtyRow_invalidationExpandsForSamplingSafety() {
        val overlay = overlayAfterFirstFrame()
        val touchedRow = 120
        val packedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(packedBuffer, x = 100, y = touchedRow)
        assertTrue(overlay.updatePackedLcd(packedBuffer))

        val dirtyRect = readPrivateField<Rect>(overlay, "dirtyRect")
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, overlay.width.toFloat(), overlay.height.toFloat())
        val lcdTop = projection.offsetY + spec.lcdWindowTop * projection.scale
        val lcdHeight = spec.lcdWindowHeight * projection.scale
        val expectedTop = (lcdTop + lcdHeight * (touchedRow.toFloat() / R47LcdContract.PIXEL_HEIGHT.toFloat())).toInt()
        val expectedBottom = ceil(
            lcdTop + lcdHeight * ((touchedRow + 1).toFloat() / R47LcdContract.PIXEL_HEIGHT.toFloat())
        ).toInt()

        assertTrue("dirty rect top should include a sampling guard", dirtyRect.top <= expectedTop - 1)
        assertTrue("dirty rect bottom should include a sampling guard", dirtyRect.bottom >= expectedBottom + 1)
    }

    @Test
    fun nativeChrome_compositesLcdRasterColours() {
        // Semantic oracle: prove the LCD raster is actually
        // composited into the frame, instead of a re-blessable whole-frame hash
        // that proves nothing about correctness. Both palette colours of the
        // sample frame -- the lit status band and the unlit checker cells -- must
        // appear in the render. A regression that blanks or drops the LCD
        // fails here, without depending on exact (letterboxed) projection math; the
        // PackedLcdRasterTest already locks the precise LCD rendering
        // pixel-for-pixel.
        val rendered = sampledColors(renderToBitmap(overlayShowingSampleFrame()))

        val (text, background) = SAMPLE_PALETTE
        assertTrue(
            "LCD text colour ${hex(text)} must appear in the render",
            rendered.any { colorDistance(it, text) <= LCD_COLOUR_TOLERANCE },
        )
        assertTrue(
            "LCD background colour ${hex(background)} must appear in the render",
            rendered.any { colorDistance(it, background) <= LCD_COLOUR_TOLERANCE },
        )
    }

    @Test
    fun nativeChrome_isTheBlackBackgroundOutsideTheLcdWindow() {
        // Native-only chrome draws no shell, so with no child, hint, or HUD the
        // overlay paints its black background and the LCD bitmap, nothing
        // else: every pixel more than one pixel outside the LCD window, the
        // filtering margin, is opaque black. The text golden cannot see this,
        // since its ramp maps every luminance up to 28 to the same blank.
        val overlay = overlayShowingSampleFrame()
        val bitmap = renderToBitmap(overlay)
        val window = readPrivateField<RectF>(overlay, "lcdDestRect")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val notBlack = pixels.indices.count { index ->
            val x = index % bitmap.width
            val y = index / bitmap.width
            val inMargin = x >= window.left - 1f && x < window.right + 1f &&
                y >= window.top - 1f && y < window.bottom + 1f
            !inMargin && pixels[index] != Color.BLACK
        }

        assertFalse("the LCD window must not be empty", window.isEmpty)
        assertEquals(
            "pixels outside the LCD window that are not the black background",
            0,
            notBlack,
        )
    }

    @Test
    fun lcdWindow_matchesTextGolden() {
        // Code-only golden: an ASCII-luminance fingerprint held in code, no
        // binary reference image (the Roborazzi alternative the maintainer ruled
        // out) and no opaque SHA. It locks where the LCD window sits in the
        // render and the coarse shape of the sample frame in it; the blank rows
        // are the black background that
        // nativeChrome_isTheBlackBackgroundOutsideTheLcdWindow holds exactly.
        // assertEquals on the two grids yields a reviewable diff: an intended
        // change shows up as a shape change a reviewer confirms before
        // updating LCD_WINDOW_TEXT_GOLDEN. The exact LCD colours are
        // locked by nativeChrome_compositesLcdRasterColours and
        // PackedLcdRasterTest.
        val overlay = overlayShowingSampleFrame()

        assertEquals(
            "LCD window golden changed. Compare the grids in this diff; " +
                "if the change is intended, regenerate LCD_WINDOW_TEXT_GOLDEN " +
                "after a review. This is a code-only ASCII fingerprint -- there is " +
                "no binary reference image to commit.",
            LCD_WINDOW_TEXT_GOLDEN,
            renderTextGolden(overlay),
        )
    }

    @Test
    fun developerPerformanceHud_changesRenderedChromeWhenEnabled() {
        val baseOverlay = overlayShowingSampleFrame()
        val performanceOverlay = overlayShowingSampleFrame().apply {
            setShowDeveloperPerformanceHud(true)
            updateDeveloperPerformanceSnapshot(
                DeveloperPerformanceSnapshot(
                    uiFramesPerSecond = 59.8f,
                    lcdUpdatesPerSecond = 12.4f,
                    averageLcdUpdateMillis = 0.38f,
                    lcdUpdateSamples = 8,
                )
            )
        }

        assertFalse(renderHash(baseOverlay) == renderHash(performanceOverlay))
    }

    @Test
    fun topBezelTapIsNotIntercepted() {
        val overlay = configuredOverlay()

        val tapPoint = settingsStripTapPoint(overlay)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, tapPoint.first, tapPoint.second, 0)

        try {
            assertFalse(overlay.onInterceptTouchEvent(down))
        } finally {
            down.recycle()
        }
    }

    @Test
    fun touchBelowSettingsStripIsIntercepted() {
        val overlay = configuredOverlay()
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(overlay.width.toFloat(), overlay.height.toFloat())
        val touchY = projection.offsetY + (spec.topBezelSettingsTapHeight + 24f) * projection.scale
        val touchEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, overlay.width / 2f, touchY, 0)

        try {
            assertTrue(overlay.onInterceptTouchEvent(touchEvent))
        } finally {
            touchEvent.recycle()
        }
    }

    @Test
    fun lcdGraphTouchSettingDisablesLcdGestureIntercept() {
        val overlay = configuredOverlay()
        val lcdCenter = lcdWindowCenter(overlay)

        overlay.setLcdGraphTouchEnabled(false)
        val downWhileDisabled = MotionEvent.obtain(
            0L,
            0L,
            MotionEvent.ACTION_DOWN,
            lcdCenter.first,
            lcdCenter.second,
            0,
        )

        try {
            assertFalse(overlay.onInterceptTouchEvent(downWhileDisabled))
        } finally {
            downWhileDisabled.recycle()
        }

        overlay.setLcdGraphTouchEnabled(true)
        val downWhileEnabled = MotionEvent.obtain(
            0L,
            16L,
            MotionEvent.ACTION_DOWN,
            lcdCenter.first,
            lcdCenter.second,
            0,
        )

        try {
            assertTrue(overlay.onInterceptTouchEvent(downWhileEnabled))
        } finally {
            downWhileEnabled.recycle()
        }
    }

    @Test
    fun primaryPointerUpRebindsToSurvivingPointer() {
        val overlay = configuredOverlay()

        val lcdCenter = lcdWindowCenter(overlay)
        val y = lcdCenter.second
        val pointer0StartX = lcdCenter.first - 140f
        val pointer1StartX = lcdCenter.first

        val down = MotionEvent.obtain(
            0L,
            0L,
            MotionEvent.ACTION_DOWN,
            pointer0StartX,
            y,
            0,
        )
        val pointerDown = obtainMultiTouchEvent(
            downTime = 0L,
            eventTime = 8L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(
                PointerSample(id = 0, x = pointer0StartX, y = y),
                PointerSample(id = 1, x = pointer1StartX, y = y),
            ),
        )
        val pointerUp = obtainMultiTouchEvent(
            downTime = 0L,
            eventTime = 16L,
            actionMasked = MotionEvent.ACTION_POINTER_UP,
            actionIndex = 0,
            pointers = listOf(
                PointerSample(id = 0, x = pointer0StartX, y = y),
                PointerSample(id = 1, x = pointer1StartX, y = y),
            ),
        )

        try {
            assertTrue(overlay.onInterceptTouchEvent(down))
            assertTrue(overlay.onTouchEvent(down))
            assertTrue(overlay.onTouchEvent(pointerDown))
            assertTrue(overlay.onTouchEvent(pointerUp))
        } finally {
            down.recycle()
            pointerDown.recycle()
            pointerUp.recycle()
        }

        assertEquals(1, readPrivateField<Int>(overlay, "activeLcdPointerId"))
        assertEquals(pointer1StartX, readPrivateField<Float>(overlay, "lcdLastTouchX"), 0.01f)
        assertEquals(y, readPrivateField<Float>(overlay, "lcdLastTouchY"), 0.01f)
    }

    @Test
    fun slowContinuousPanStartsAfterAccumulatedSlopAndKeepsReporting() {
        val overlay = configuredOverlay()
        renderHash(overlay)
        val lcdCenter = lcdWindowCenter(overlay)
        val panSlop = readPrivateField<Float>(overlay, "panTouchSlopPx")
        val capturedDeltas = mutableListOf<Float>()
        overlay.onLcdPanListener = { dxNorm, _ ->
            capturedDeltas += dxNorm
        }

        val startX = lcdCenter.first
        val y = lcdCenter.second
        val stepPx = maxOf(1f, panSlop / 3f)

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, startX, y, 0)
        val move1 = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_MOVE, startX + stepPx, y, 0)
        val move2 = MotionEvent.obtain(0L, 32L, MotionEvent.ACTION_MOVE, startX + stepPx * 2f, y, 0)
        val move3 = MotionEvent.obtain(0L, 48L, MotionEvent.ACTION_MOVE, startX + stepPx * 3f, y, 0)
        val move4 = MotionEvent.obtain(0L, 64L, MotionEvent.ACTION_MOVE, startX + stepPx * 4f, y, 0)

        try {
            assertTrue(overlay.onInterceptTouchEvent(down))
            assertTrue(overlay.onTouchEvent(down))
            assertTrue(overlay.onTouchEvent(move1))
            assertTrue(overlay.onTouchEvent(move2))
            assertTrue(overlay.onTouchEvent(move3))
            assertTrue(overlay.onTouchEvent(move4))
        } finally {
            down.recycle()
            move1.recycle()
            move2.recycle()
            move3.recycle()
            move4.recycle()
        }

        assertTrue(capturedDeltas.size >= 2)
        assertTrue(abs(capturedDeltas[0]) > 0f)
        assertTrue(abs(capturedDeltas[1]) > 0f)
    }

    @Test
    fun postStartMicroPanNoiseIsIgnoredUntilMovementReaccumulates() {
        val overlay = configuredOverlay()
        renderHash(overlay)

        val lcdCenter = lcdWindowCenter(overlay)
        val panSlop = readPrivateField<Float>(overlay, "panTouchSlopPx")
        val continuationSlop = maxOf(
            GraphTouchVisualPolicy.PAN_CONTINUATION_MIN_SLOP_PX,
            panSlop * GraphTouchVisualPolicy.PAN_CONTINUATION_SLOP_FRACTION,
        )
        val capturedDeltas = mutableListOf<Float>()
        overlay.onLcdPanListener = { dxNorm, _ ->
            capturedDeltas += dxNorm
        }

        val startX = lcdCenter.first
        val y = lcdCenter.second

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, startX, y, 0)
        val startMove = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_MOVE, startX + panSlop, y, 0)
        val jitter1 = MotionEvent.obtain(
            0L,
            32L,
            MotionEvent.ACTION_MOVE,
            startX + panSlop + continuationSlop * 0.4f,
            y,
            0,
        )
        val jitter2 = MotionEvent.obtain(
            0L,
            48L,
            MotionEvent.ACTION_MOVE,
            startX + panSlop + continuationSlop * 0.8f,
            y,
            0,
        )
        val accumulatedMove = MotionEvent.obtain(
            0L,
            64L,
            MotionEvent.ACTION_MOVE,
            startX + panSlop + continuationSlop * 1.2f,
            y,
            0,
        )

        try {
            assertTrue(overlay.onInterceptTouchEvent(down))
            assertTrue(overlay.onTouchEvent(down))
            assertTrue(overlay.onTouchEvent(startMove))
            assertEquals(1, capturedDeltas.size)

            assertTrue(overlay.onTouchEvent(jitter1))
            assertEquals(1, capturedDeltas.size)

            assertTrue(overlay.onTouchEvent(jitter2))
            assertEquals(1, capturedDeltas.size)

            assertTrue(overlay.onTouchEvent(accumulatedMove))
            assertEquals(2, capturedDeltas.size)
        } finally {
            down.recycle()
            startMove.recycle()
            jitter1.recycle()
            jitter2.recycle()
            accumulatedMove.recycle()
        }

        assertTrue(abs(capturedDeltas.last()) > 0f)
    }

    @Test
    fun pinchPointerLiftResetsPanAnchorAndAvoidsSpuriousTranslation() {
        val overlay = configuredOverlay()
        renderHash(overlay)

        val lcdCenter = lcdWindowCenter(overlay)
        val y = lcdCenter.second
        val pointer0X = lcdCenter.first - 40f
        val pointer1X = lcdCenter.first + 40f
        val panSlop = readPrivateField<Float>(overlay, "panTouchSlopPx")

        val capturedPan = mutableListOf<Float>()
        overlay.onLcdPanListener = { dxNorm, _ ->
            capturedPan += dxNorm
        }

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, pointer0X, y, 0)
        val pointerDown = obtainMultiTouchEvent(
            downTime = 0L,
            eventTime = 8L,
            actionMasked = MotionEvent.ACTION_POINTER_DOWN,
            actionIndex = 1,
            pointers = listOf(
                PointerSample(id = 0, x = pointer0X, y = y),
                PointerSample(id = 1, x = pointer1X, y = y),
            ),
        )
        val pointerUp = obtainMultiTouchEvent(
            downTime = 0L,
            eventTime = 16L,
            actionMasked = MotionEvent.ACTION_POINTER_UP,
            actionIndex = 1,
            pointers = listOf(
                PointerSample(id = 0, x = pointer0X, y = y),
                PointerSample(id = 1, x = pointer1X, y = y),
            ),
        )
        val smallMove = MotionEvent.obtain(
            0L,
            24L,
            MotionEvent.ACTION_MOVE,
            pointer0X + maxOf(1f, panSlop * 0.25f),
            y,
            0,
        )

        try {
            assertTrue(overlay.onInterceptTouchEvent(down))
            assertTrue(overlay.onTouchEvent(down))
            setPrivateField(overlay, "lcdScalingActive", true)
            setPrivateField(overlay, "lcdPanStarted", true)
            assertTrue(overlay.onTouchEvent(pointerDown))
            assertTrue(overlay.onTouchEvent(pointerUp))
            assertTrue(overlay.onTouchEvent(smallMove))
        } finally {
            down.recycle()
            pointerDown.recycle()
            pointerUp.recycle()
            smallMove.recycle()
        }

        assertTrue(capturedPan.isEmpty())
        assertFalse(readPrivateField<Boolean>(overlay, "lcdPanStarted"))
        assertFalse(readPrivateField<Boolean>(overlay, "lcdScalingActive"))
        assertEquals(0, readPrivateField<Int>(overlay, "activeLcdPointerId"))
    }

    private fun configuredOverlay(): ReplicaOverlay {
        return ReplicaOverlay(ApplicationProvider.getApplicationContext()).apply {
            measure(exactly(1080), exactly(2160))
            layout(0, 0, 1080, 2160)
        }
    }

    private fun lcdWindowCenter(overlay: ReplicaOverlay): Pair<Float, Float> {
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(overlay.width.toFloat(), overlay.height.toFloat())
        val x = projection.offsetX + (spec.lcdWindowLeft + spec.lcdWindowWidth * 0.5f) * projection.scale
        val y = projection.offsetY + (spec.lcdWindowTop + spec.lcdWindowHeight * 0.5f) * projection.scale
        return Pair(x, y)
    }

    private data class PointerSample(
        val id: Int,
        val x: Float,
        val y: Float,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> readPrivateField(instance: Any, fieldName: String): T {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(instance) as T
    }

    private fun setPrivateField(instance: Any, fieldName: String, value: Any) {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(instance, value)
    }

    private fun obtainMultiTouchEvent(
        downTime: Long,
        eventTime: Long,
        actionMasked: Int,
        actionIndex: Int = 0,
        pointers: List<PointerSample>,
    ): MotionEvent {
        val pointerProperties = Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointers[index].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val pointerCoords = Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = pointers[index].x
                y = pointers[index].y
                pressure = 1f
                size = 1f
            }
        }
        val action = actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointers.size,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        )
    }

    private fun settingsStripTapPoint(overlay: ReplicaOverlay): Pair<Float, Float> {
        val chromeLayout = ReplicaChromeLayout()
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(overlay.width.toFloat(), overlay.height.toFloat())
        return Pair(
            overlay.width / 2f,
            projection.offsetY + spec.topBezelSettingsTapHeight * projection.scale * 0.5f,
        )
    }

    private fun renderToBitmap(overlay: ReplicaOverlay): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2160, Bitmap.Config.ARGB_8888)
        overlay.draw(Canvas(bitmap))
        return bitmap
    }

    private fun renderHash(overlay: ReplicaOverlay): String = pngSha256(renderToBitmap(overlay))

    // Code-only golden: downsample the render to a
    // small ASCII-luminance grid. Unlike a SHA hash it is a *reviewable* fingerprint
    // -- an intended change shows up as a visible shape diff -- and unlike a
    // Roborazzi reference it is plain text, so it adds no binary image to the repo.
    private fun renderTextGolden(overlay: ReplicaOverlay): String {
        val width = 1080
        val height = 2160
        val pixels = IntArray(width * height)
        renderToBitmap(overlay).getPixels(pixels, 0, width, 0, 0, width, height)
        return (0 until GOLDEN_ROWS).joinToString("\n") { row ->
            val yStart = row * height / GOLDEN_ROWS
            val yEnd = (row + 1) * height / GOLDEN_ROWS
            buildString {
                for (col in 0 until GOLDEN_COLS) {
                    val xStart = col * width / GOLDEN_COLS
                    val xEnd = (col + 1) * width / GOLDEN_COLS
                    var sum = 0L
                    var count = 0
                    var y = yStart
                    while (y < yEnd) {
                        var x = xStart
                        while (x < xEnd) {
                            val pixel = pixels[y * width + x]
                            sum += (299 * Color.red(pixel) + 587 * Color.green(pixel) +
                                114 * Color.blue(pixel)) / 1000
                            count += 1
                            x += GOLDEN_SUBSAMPLE
                        }
                        y += GOLDEN_SUBSAMPLE
                    }
                    val luminance = if (count == 0) 0 else (sum / count).toInt()
                    val index = (luminance * (GOLDEN_RAMP.length - 1) / 255)
                        .coerceIn(0, GOLDEN_RAMP.length - 1)
                    append(GOLDEN_RAMP[index])
                }
            }
        }
    }


    private fun sampledColors(bitmap: Bitmap): List<Int> {
        val step = 12
        val colors = ArrayList<Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                colors.add(bitmap.getPixel(x, y))
                x += step
            }
            y += step
        }
        return colors
    }

    private fun colorDistance(first: Int, second: Int): Int {
        return abs(Color.red(first) - Color.red(second)) +
            abs(Color.green(first) - Color.green(second)) +
            abs(Color.blue(first) - Color.blue(second))
    }

    private fun hex(color: Int): String = "#%08X".format(color)

    private fun setPackedPixel(buffer: ByteArray, x: Int, y: Int) {
        val rowOffset = y * R47LcdContract.PACKED_ROW_SIZE_BYTES
        buffer[rowOffset] = 1
        buffer[rowOffset + 1] = (R47LcdContract.PIXEL_HEIGHT - y - 1).toByte()
        val byteIndex = R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - (x / 8)
        val bitMask = 1 shl (7 - (x % 8))
        buffer[rowOffset + R47LcdContract.PACKED_PIXEL_OFFSET + byteIndex] =
            (buffer[rowOffset + R47LcdContract.PACKED_PIXEL_OFFSET + byteIndex].toInt() or bitMask).toByte()
    }

    // An overlay that has decoded a blank first frame, so the next frame decodes
    // only its flagged rows.
    private fun overlayAfterFirstFrame(): ReplicaOverlay {
        return configuredOverlay().apply {
            assertTrue(updatePackedLcd(ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)))
        }
    }

    private fun overlayShowingSampleFrame(): ReplicaOverlay {
        return configuredOverlay().apply {
            setLcdPalette(SAMPLE_PALETTE)
            assertTrue(updatePackedLcd(samplePackedFrame()))
        }
    }

    // Build features large enough to survive the sampled render: a lit status
    // band, an unlit left column, and a 24 px checker.
    private fun samplePackedFrame(): ByteArray {
        val buffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        for (y in 0 until R47LcdContract.PIXEL_HEIGHT) {
            for (x in 0 until R47LcdContract.PIXEL_WIDTH) {
                if (y < 80 || (x >= 133 && ((x / 24) + (y / 24)) % 2 == 1)) {
                    setPackedPixel(buffer, x, y)
                }
            }
        }
        return buffer
    }

    private fun pngSha256(bitmap: Bitmap): String {
        val pngBytes = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(pngBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun exactly(size: Int): Int {
        return android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY)
    }

    private companion object {
        private val SAMPLE_PALETTE = LcdPalette(text = 0xFF1E3A5F.toInt(), background = 0xFFE7F2E4.toInt())

        // Allow for scaling/filtering when the 400x240 LCD raster is upscaled into
        // the projected window; the sampled centre sits inside a uniform colour
        // block, so a small per-channel sum tolerance is enough.
        private const val LCD_COLOUR_TOLERANCE = 24

        // ASCII-luminance golden grid: dimensions, subsample stride within a cell,
        // and the dark-to-light ramp.
        private const val GOLDEN_COLS = 20
        private const val GOLDEN_ROWS = 40
        private const val GOLDEN_SUBSAMPLE = 6
        private const val GOLDEN_RAMP = " .:-=+*#%@"

        // Reviewable code-only golden of the render: the LCD window in rows
        // 3-14 over the black background. Regenerate by re-running the
        // capture path and reviewing the visible shape diff.
        private val LCD_WINDOW_TEXT_GOLDEN = listOf(
            "                    ",
            "                    ",
            "                    ",
            " ...................",
            "....................",
            "....................",
            "....................",
            "=%%%%%%==+=*-*=++=*:",
            "=%%%%%#++=*:#-*==*-+",
            "=%%%%%%==*-%.#-++-#.",
            "=%%%%%#*+=*:%:*==*:+",
            "=%%%%%%==+=*-*=++=*:",
            "=%%%%%#++=+=*=+==+==",
            "=%%%%%#++++++++++++-",
            "-++++++:-=:+.+:--:+ ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
            "                    ",
        ).joinToString("\n")
    }
}
