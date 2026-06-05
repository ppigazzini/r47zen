package io.github.ppigazzini.r47zen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
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
@Config(sdk = [34], qualifiers = "xxhdpi")
class ReplicaOverlayGoldenTest {
    @Test
    fun packedLcd_matchesArgbRendering() {
        val textColor = 0xFF20313D.toInt()
        val backgroundColor = 0xFFDCECD0.toInt()
        val packedBuffer = samplePackedBuffer()
        val expectedPixels = decodePackedBuffer(packedBuffer, textColor, backgroundColor)

        val packedOverlay = configuredOverlay().apply {
            setLcdColors(textColor, backgroundColor)
        }
        assertTrue(packedOverlay.updatePackedLcd(packedBuffer.copyOf()))

        val argbOverlay = configuredOverlay().apply {
            setLcdColors(textColor, backgroundColor)
            updateLcd(expectedPixels)
        }

        assertEquals(renderHash(argbOverlay), renderHash(packedOverlay))
    }

    @Test
    fun packedLcd_recolorsExistingSnapshot() {
        val initialText = 0xFF20313D.toInt()
        val initialBackground = 0xFFDCECD0.toInt()
        val recolorText = 0xFF112A4A.toInt()
        val recolorBackground = 0xFFF1E6C5.toInt()
        val packedBuffer = samplePackedBuffer()

        val overlay = configuredOverlay().apply {
            setLcdColors(initialText, initialBackground)
        }
        assertTrue(overlay.updatePackedLcd(packedBuffer.copyOf()))
        overlay.setLcdColors(recolorText, recolorBackground)

        val expectedOverlay = configuredOverlay().apply {
            updateLcd(decodePackedBuffer(packedBuffer, recolorText, recolorBackground))
        }

        assertEquals(renderHash(expectedOverlay), renderHash(overlay))
    }

    @Test
    fun packedLcd_singleDirtyRow_invalidatesOnlyTouchedRowSpan() {
        val overlay = configuredOverlay()
        val touchedRow = 137
        val packedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(packedBuffer, x = 64, y = touchedRow)

        assertTrue(overlay.updatePackedLcd(packedBuffer))

        val dirtyRect = readPrivateField<Rect>(overlay, "dirtyRect")
        val chromeLayout = ReplicaChromeLayout(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
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
        val overlay = configuredOverlay()
        val touchedRow = 11
        val packedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(packedBuffer, x = 8, y = touchedRow)

        assertTrue(overlay.updatePackedLcd(packedBuffer))

        val dirtyRect = readPrivateField<Rect>(overlay, "dirtyRect")
        val chromeLayout = ReplicaChromeLayout(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
        val spec = chromeLayout.currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, overlay.width.toFloat(), overlay.height.toFloat())
        val lcdTop = projection.offsetY + spec.lcdWindowTop * projection.scale
        val lcdBottom = projection.offsetY + (spec.lcdWindowTop + spec.lcdWindowHeight) * projection.scale
        val fullLcdHeight = ceil(lcdBottom).toInt() - lcdTop.toInt()

        assertTrue("dirty rect should be row-band sized, not full LCD height", dirtyRect.height() < fullLcdHeight)
    }

    @Test
    fun packedLcd_unchangedDirtyRow_isSkipped() {
        val overlay = configuredOverlay()
        val firstBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(firstBuffer, x = 24, y = 33)
        assertTrue(overlay.updatePackedLcd(firstBuffer))

        val unchangedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(unchangedBuffer, x = 24, y = 33)

        assertFalse(overlay.updatePackedLcd(unchangedBuffer))
    }

    @Test
    fun packedLcd_dirtyRowUpdate_preservesUntouchedRowPixels() {
        val overlay = configuredOverlay()
        val seedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(seedBuffer, x = 40, y = 40)
        setPackedPixel(seedBuffer, x = 56, y = 41)
        assertTrue(overlay.updatePackedLcd(seedBuffer))

        val bitmapBefore = readPrivateField<Bitmap>(overlay, "lcdBitmap")
        val untouchedRowY = 41
        val sampleX = 56
        val untouchedPixelBefore = bitmapBefore.getPixel(sampleX, untouchedRowY)

        val updateBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(updateBuffer, x = 64, y = 40)
        assertTrue(overlay.updatePackedLcd(updateBuffer))

        val bitmapAfter = readPrivateField<Bitmap>(overlay, "lcdBitmap")
        val untouchedPixelAfter = bitmapAfter.getPixel(sampleX, untouchedRowY)
        assertEquals(untouchedPixelBefore, untouchedPixelAfter)
    }

    @Test
    fun packedLcd_singleDirtyRow_invalidationExpandsForSamplingSafety() {
        val overlay = configuredOverlay()
        val touchedRow = 120
        val packedBuffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(packedBuffer, x = 100, y = touchedRow)
        assertTrue(overlay.updatePackedLcd(packedBuffer))

        val dirtyRect = readPrivateField<Rect>(overlay, "dirtyRect")
        val chromeLayout = ReplicaChromeLayout(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
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
        // Semantic oracle (REPORT-24 Milestone 5): prove the LCD raster is actually
        // composited into the frame, instead of a re-blessable whole-frame hash
        // that proves nothing about correctness. The two most distinctive LCD
        // content colours -- the top status band and the checker highlight -- must
        // both appear in the render. A regression that blanks or drops the LCD
        // fails here, without depending on exact (letterboxed) projection math; the
        // existing packedLcd_matchesArgbRendering test already locks the precise
        // LCD rendering pixel-for-pixel.
        val overlay = configuredOverlay()
        overlay.updateLcd(sampleLcdPixels())
        val rendered = sampledColors(renderToBitmap(overlay))

        val topBand = 0xFF1E3A5F.toInt()
        val checkerHighlight = 0xFFE7F2E4.toInt()
        assertTrue(
            "LCD top status band colour ${hex(topBand)} must appear in the render",
            rendered.any { colorDistance(it, topBand) <= LCD_COLOUR_TOLERANCE },
        )
        assertTrue(
            "LCD checker highlight colour ${hex(checkerHighlight)} must appear in the render",
            rendered.any { colorDistance(it, checkerHighlight) <= LCD_COLOUR_TOLERANCE },
        )
    }

    @Test
    fun nativeChrome_matchesTextGolden() {
        // Code-only golden render oracle (REPORT-24 Milestone 5 Slice B): assert the
        // render against an ASCII-luminance fingerprint held in code -- no binary
        // reference image (the Roborazzi alternative the maintainer ruled out) and
        // no opaque SHA. assertEquals on the two multi-line grids yields a
        // *reviewable* diff: an intended visual change shows up as a visible shape
        // change in the grid, which a reviewer confirms before updating
        // CHROME_TEXT_GOLDEN. The exact LCD colours are separately locked by
        // nativeChrome_compositesLcdRasterColours and packedLcd_matchesArgbRendering.
        val overlay = configuredOverlay()
        overlay.updateLcd(sampleLcdPixels())

        assertEquals(
            "ReplicaOverlay chrome golden changed. Compare the grids in this diff; " +
                "if the visual change is intended, regenerate CHROME_TEXT_GOLDEN " +
                "after a review. This is a code-only ASCII fingerprint -- there is " +
                "no binary reference image to commit.",
            CHROME_TEXT_GOLDEN,
            renderTextGolden(overlay),
        )
    }

    @Test
    fun developerPerformanceHud_changesRenderedChromeWhenEnabled() {
        val baseOverlay = configuredOverlay().apply {
            updateLcd(sampleLcdPixels())
        }
        val performanceOverlay = configuredOverlay().apply {
            updateLcd(sampleLcdPixels())
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
        val chromeLayout = ReplicaChromeLayout(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
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
        val chromeLayout = ReplicaChromeLayout(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
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
        val chromeLayout = ReplicaChromeLayout(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
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

    // Code-only golden (REPORT-24 Milestone 5 Slice B): downsample the render to a
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

    private fun samplePackedBuffer(): ByteArray {
        val buffer = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
        setPackedPixel(buffer, x = 0, y = 0)
        setPackedPixel(buffer, x = 17, y = 0)
        setPackedPixel(buffer, x = 80, y = 60)
        setPackedPixel(buffer, x = 215, y = 60)
        setPackedPixel(buffer, x = 399, y = 120)
        setPackedPixel(buffer, x = 121, y = 239)
        return buffer
    }

    private fun setPackedPixel(buffer: ByteArray, x: Int, y: Int) {
        val rowOffset = y * R47LcdContract.PACKED_ROW_SIZE_BYTES
        buffer[rowOffset] = 1
        buffer[rowOffset + 1] = (R47LcdContract.PIXEL_HEIGHT - y - 1).toByte()
        val byteIndex = R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - (x / 8)
        val bitMask = 1 shl (7 - (x % 8))
        buffer[rowOffset + 2 + byteIndex] =
            (buffer[rowOffset + 2 + byteIndex].toInt() or bitMask).toByte()
    }

    private fun decodePackedBuffer(buffer: ByteArray, textColor: Int, backgroundColor: Int): IntArray {
        val pixels = IntArray(R47LcdContract.PIXEL_COUNT) { backgroundColor }
        for (bufferRow in 0 until R47LcdContract.PIXEL_HEIGHT) {
            val rowOffset = bufferRow * R47LcdContract.PACKED_ROW_SIZE_BYTES
            val rowId = buffer[rowOffset + 1].toInt() and 0xFF
            val displayRow = (R47LcdContract.PIXEL_HEIGHT - rowId - 1)
                .coerceIn(0, R47LcdContract.PIXEL_HEIGHT - 1)
            for (byteIndex in 0 until R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW) {
                val packedByte = buffer[rowOffset + 2 + byteIndex].toInt() and 0xFF
                val destX = (R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - byteIndex) * 8
                for (bit in 0 until 8) {
                    val x = destX + bit
                    val index = displayRow * R47LcdContract.PIXEL_WIDTH + x
                    pixels[index] = if ((packedByte and (1 shl (7 - bit))) != 0) {
                        textColor
                    } else {
                        backgroundColor
                    }
                }
            }
        }
        return pixels
    }

    private fun sampleLcdPixels(): IntArray {
        return IntArray(R47LcdContract.PIXEL_COUNT) { index ->
            val x = index % R47LcdContract.PIXEL_WIDTH
            val y = index / R47LcdContract.PIXEL_WIDTH
            when {
                y < 80 -> 0xFF1E3A5F.toInt()
                x < 133 -> 0xFF89A8B2.toInt()
                ((x / 24) + (y / 24)) % 2 == 0 -> 0xFFE7F2E4.toInt()
                else -> 0xFF2B3A33.toInt()
            }
        }
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

        // Reviewable code-only golden for the rendered replica chrome + LCD: the
        // calculator's LCD window (rows 3-14) over the dark body. Regenerate by
        // re-running the capture path and reviewing the visible shape diff.
        private val CHROME_TEXT_GOLDEN = listOf(
            "                    ",
            "                    ",
            "                    ",
            " ...................",
            "....................",
            "....................",
            "....................",
            "-++++++==+=*-*=++=*:",
            "-++++++++=*:#-*==*-+",
            "-+++++*==*-%.#-++-#.",
            "-++++++*+=*:%:*==*:+",
            "-++++++==+=*-*=++=*:",
            "-++++++++=+=*=+==+==",
            "-+++++++++++=++++++-",
            ":------:-=:+.+:--:+ ",
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
