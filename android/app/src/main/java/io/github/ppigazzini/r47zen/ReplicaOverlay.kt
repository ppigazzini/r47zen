package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal object SettingsDiscoveryHintVisualPolicy {
    const val SURFACE_COLOR_ARGB = 0xEC12151A.toInt()
    const val ON_SURFACE_COLOR_ARGB = 0xFFF7F3EA.toInt()
    const val STROKE_COLOR_ARGB = 0xFF8EDAFE.toInt()
    const val MENU_ORANGE_FALLBACK_COLOR_ARGB = 0xFFFFC36F.toInt()
    const val MENU_BLUE_FALLBACK_COLOR_ARGB = 0xFF8EDAFE.toInt()

    const val CARD_OUTER_MARGIN_DP = 24f
    const val CARD_MIN_WIDTH_DP = 220f
    const val CARD_MAX_WIDTH_DP = 360f
    const val CARD_WIDTH_RATIO = 0.72f
    const val CARD_HORIZONTAL_PADDING_DP = 18f
    const val CARD_VERTICAL_PADDING_DP = 16f
    const val CARD_CORNER_RADIUS_DP = 22f
    const val CARD_LINE_SPACING_DP = 4f
    const val CARD_GLYPH_TEXT_GAP_DP = 12f

    const val CARD_STROKE_WIDTH_DP = 2.5f
    const val CARD_STROKE_EXTRA_WIDTH_DP = 1.5f
    const val INFO_TEXT_SIZE_DP = 14f

    const val FILL_ALPHA_BASE = 220
    const val FILL_ALPHA_DELTA = 16f
    const val CARD_STROKE_ALPHA_BASE = 150
    const val CARD_STROKE_ALPHA_DELTA = 105f
    const val PULSE_PERIOD_MS = 1400L

    val FULL_PULSE_RADIANS = 2.0 * PI
}

internal object DeveloperPerformanceHudVisualPolicy {
    const val TEXT_SIZE_DP = 12f
    const val MIN_AVAILABLE_HEIGHT_DP = 24f
    const val TEXT_HEIGHT_RATIO = 0.09f
    const val MIN_TEXT_SIZE_DP = 10f
    const val MAX_TEXT_SIZE_DP = 16f
    const val MIN_LABEL_WIDTH_DP = 48f
    const val MAX_LABEL_HORIZONTAL_MARGIN_DP = 8f
    const val BASELINE_BOTTOM_INSET_DP = 6f
    const val LEADING_INSET_DP = 2f
    const val SHADOW_RADIUS_DP = 2f
    const val SHADOW_COLOR_ARGB = 0xD0000000.toInt()
}

internal object TouchZoneDebugVisualPolicy {
    const val ZONE_STROKE_WIDTH_DP = 2f
    const val STROKE_ALPHA = 180
}

internal object GraphTouchVisualPolicy {
    const val PAN_GAIN = 1.0f
    const val PINCH_EPSILON = 0.0001f
}

class ReplicaOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private data class SettingsHintLayoutCache(
        val infoCardRect: RectF,
        val infoCornerRadius: Float,
        val infoGlyphTop: Float,
        val infoGlyphRight: Float,
        val infoGlyphTabHeight: Float,
        val infoGlyphGap: Float,
        val infoPaddingHorizontal: Float,
        val infoPaddingVertical: Float,
        val infoTextTop: Float,
        val infoLayout: StaticLayout,
    )

    private var isPiPMode = false
    private var showTouchZones = false
    private val chromeLayout = ReplicaChromeLayout(resources)

    private val lcdBitmap = Bitmap.createBitmap(
        R47LcdContract.PIXEL_WIDTH,
        R47LcdContract.PIXEL_HEIGHT,
        Bitmap.Config.ARGB_8888,
    )
    private val lastLcdPixels = IntArray(R47LcdContract.PIXEL_COUNT)
    private val lastPackedLcd = ByteArray(R47LcdContract.PACKED_BUFFER_SIZE)
    private val linePixels = IntArray(R47LcdContract.PIXEL_WIDTH)
    private val lcdRect = Rect(0, 0, R47LcdContract.PIXEL_WIDTH, R47LcdContract.PIXEL_HEIGHT)
    private val lcdDestRect = RectF()
    private val dirtyRect = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shellRect = RectF()
    private val zonePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = TouchZoneDebugVisualPolicy.ZONE_STROKE_WIDTH_DP * resources.displayMetrics.density
        alpha = TouchZoneDebugVisualPolicy.STROKE_ALPHA
    }
    private val settingsHintSurfaceColor = SettingsDiscoveryHintVisualPolicy.SURFACE_COLOR_ARGB
    private val settingsHintOnSurfaceColor = SettingsDiscoveryHintVisualPolicy.ON_SURFACE_COLOR_ARGB
    private val settingsHintInfoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = settingsHintSurfaceColor
    }
    private val settingsHintInfoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SettingsDiscoveryHintVisualPolicy.STROKE_COLOR_ARGB
        style = Paint.Style.STROKE
        strokeWidth = dp(SettingsDiscoveryHintVisualPolicy.CARD_STROKE_WIDTH_DP)
    }
    private val settingsHintMenuOrangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveThemeColor(
            com.google.android.material.R.attr.colorPrimary,
            SettingsDiscoveryHintVisualPolicy.MENU_ORANGE_FALLBACK_COLOR_ARGB,
        )
        style = Paint.Style.FILL
    }
    private val settingsHintMenuBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveThemeColor(
            com.google.android.material.R.attr.colorSecondary,
            SettingsDiscoveryHintVisualPolicy.MENU_BLUE_FALLBACK_COLOR_ARGB,
        )
        style = Paint.Style.FILL
    }
    private val settingsHintInfoTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = settingsHintOnSurfaceColor
        textSize = dp(SettingsDiscoveryHintVisualPolicy.INFO_TEXT_SIZE_DP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val developerPerformanceTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textAlign = Paint.Align.LEFT
        textSize = dp(DeveloperPerformanceHudVisualPolicy.TEXT_SIZE_DP)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(
            dp(DeveloperPerformanceHudVisualPolicy.SHADOW_RADIUS_DP),
            0f,
            0f,
            DeveloperPerformanceHudVisualPolicy.SHADOW_COLOR_ARGB,
        )
    }
    private var lcdTextColor = 0xFF303030.toInt()
    private var lcdBackgroundColor = 0xFFDFF5CC.toInt()
    private var showDeveloperPerformanceHud = false
    private var showSettingsDiscoveryHint = false
    private var lcdGraphTouchEnabled = true
    private var developerPerformanceSnapshot = DeveloperPerformanceSnapshot.EMPTY
    private var developerPerformanceLabel = developerPerformanceSnapshot.overlayLabel()
    private var settingsHintLayoutCache: SettingsHintLayoutCache? = null

    var onPiPKeyEvent: ((Int) -> Unit)? = null
    var onLongPressListener: ((Float, Float) -> Unit)? = null
    var onLcdPanListener: ((Float, Float) -> Unit)? = null
    var onLcdPinchListener: ((Float) -> Unit)? = null
    var onSettingsTapListener: (() -> Unit)? = null
    var onSettingsDiscoveryCompleted: (() -> Unit)? = null
    var onGeometryLaidOut: (() -> Unit)? = null

    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector
    private val panTouchSlopPx: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var lcdGestureActive = false
    private var lcdPanStarted = false
    private var lcdScalingActive = false
    private var activeLcdPointerId = MotionEvent.INVALID_POINTER_ID
    private var lcdLastTouchX = 0f
    private var lcdLastTouchY = 0f
    private var lcdPanStartX = 0f
    private var lcdPanStartY = 0f

    init {
        setBackgroundColor(Color.BLACK)
        // Allow drawing outside individual key boundaries
        clipChildren = false
        clipToPadding = false

        for (bufferRow in 0 until R47LcdContract.PIXEL_HEIGHT) {
            val rowOffset = bufferRow * R47LcdContract.PACKED_ROW_SIZE_BYTES
            lastPackedLcd[rowOffset + 1] = (R47LcdContract.PIXEL_HEIGHT - bufferRow - 1).toByte()
        }

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                onLongPressListener?.invoke(e.x, e.y)
            }
        })

        scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    val shouldScale =
                        lcdGraphTouchEnabled && lcdGestureActive && isPointInLcd(detector.focusX, detector.focusY)
                    lcdScalingActive = shouldScale
                    if (shouldScale) {
                        lcdPanStarted = false
                    }
                    return shouldScale
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (!lcdScalingActive) {
                        return false
                    }

                    val scaleFactor = detector.scaleFactor
                    if (!scaleFactor.isFinite() || abs(scaleFactor - 1f) < GraphTouchVisualPolicy.PINCH_EPSILON) {
                        return false
                    }

                    onLcdPinchListener?.invoke(scaleFactor)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    lcdScalingActive = false
                }
            },
        )
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )
    }

    private fun resolveThemeColor(attributeResId: Int, fallbackColor: Int): Int {
        val typedValue = TypedValue()
        val resolved = context.theme?.resolveAttribute(attributeResId, typedValue, true) == true
        if (!resolved) {
            return fallbackColor
        }

        return if (typedValue.resourceId != 0) {
            resources.getColor(typedValue.resourceId, context.theme)
        } else {
            typedValue.data
        }
    }

    private fun resetPanAnchor(pointerId: Int, x: Float, y: Float) {
        activeLcdPointerId = pointerId
        lcdLastTouchX = x
        lcdLastTouchY = y
        lcdPanStartX = x
        lcdPanStartY = y
        lcdPanStarted = false
    }

    fun setPiPMode(enabled: Boolean) {
        isPiPMode = enabled
        settingsHintLayoutCache = null
        requestLayout()
        invalidate()
    }

    fun setShowTouchZones(show: Boolean) {
        showTouchZones = show
        invalidate()
    }

    fun setShowDeveloperPerformanceHud(show: Boolean) {
        if (showDeveloperPerformanceHud == show) {
            return
        }

        showDeveloperPerformanceHud = show
        invalidate()
    }

    fun setLcdGraphTouchEnabled(enabled: Boolean) {
        if (lcdGraphTouchEnabled == enabled) {
            return
        }

        lcdGraphTouchEnabled = enabled
        lcdGestureActive = false
        lcdScalingActive = false
        activeLcdPointerId = MotionEvent.INVALID_POINTER_ID
    }

    internal fun updateDeveloperPerformanceSnapshot(snapshot: DeveloperPerformanceSnapshot) {
        if (developerPerformanceSnapshot == snapshot) {
            return
        }

        developerPerformanceSnapshot = snapshot
        developerPerformanceLabel = snapshot.overlayLabel()
        if (showDeveloperPerformanceHud && !showSettingsDiscoveryHint) {
            postInvalidateOnAnimation()
        }
    }

    fun setShowSettingsDiscoveryHint(show: Boolean) {
        if (showSettingsDiscoveryHint == show) {
            return
        }

        showSettingsDiscoveryHint = show
        updateSettingsHintLayoutCache()
        invalidate()
    }

    fun dismissSettingsDiscoveryHint() {
        setShowSettingsDiscoveryHint(false)
    }

    private fun completeSettingsDiscoveryHint() {
        if (!showSettingsDiscoveryHint) {
            return
        }

        dismissSettingsDiscoveryHint()
        onSettingsDiscoveryCompleted?.invoke()
    }

    fun setLcdColors(text: Int, background: Int) {
        if (lcdTextColor == text && lcdBackgroundColor == background) {
            return
        }
        lcdTextColor = text
        lcdBackgroundColor = background
        redrawPackedSnapshot()
    }

    private fun currentChromeSpec(): ReplicaChromeSpec = chromeLayout.currentChromeSpec()

    fun isPointInLcd(x: Float, y: Float): Boolean {
        if (width <= 0 || height <= 0) {
            return false
        }

        val spec = currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, width.toFloat(), height.toFloat())
        val localX = (x - projection.offsetX) / projection.scale
        val localY = (y - projection.offsetY) / projection.scale

        return localX >= spec.lcdWindowLeft &&
            localX <= spec.lcdWindowLeft + spec.lcdWindowWidth &&
            localY >= spec.lcdWindowTop &&
            localY <= spec.lcdWindowTop + spec.lcdWindowHeight
    }

    fun updateLcd(pixels: IntArray) {
        val pixelWidth = R47LcdContract.PIXEL_WIDTH
        val pixelHeight = R47LcdContract.PIXEL_HEIGHT

        var minX = pixelWidth
        var maxX = 0
        var minY = pixelHeight
        var maxY = 0
        var changed = false

        for (i in pixels.indices) {
            if (pixels[i] != lastLcdPixels[i]) {
                val x = i % pixelWidth
                val y = i / pixelWidth
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                lastLcdPixels[i] = pixels[i]
                changed = true
            }
        }

        if (!changed) return

        lcdBitmap.setPixels(pixels, 0, pixelWidth, 0, 0, pixelWidth, pixelHeight)

        invalidateLcdRegion(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            pixelWidthF = pixelWidth.toFloat(),
            pixelHeightF = pixelHeight.toFloat(),
        )
    }

    fun updatePackedLcd(buffer: ByteArray): Boolean {
        var minRow = R47LcdContract.PIXEL_HEIGHT
        var maxRow = -1

        for (bufferRow in 0 until R47LcdContract.PIXEL_HEIGHT) {
            val rowOffset = bufferRow * R47LcdContract.PACKED_ROW_SIZE_BYTES
            if ((buffer[rowOffset].toInt() and 0xFF) == 0) {
                continue
            }

            System.arraycopy(
                buffer,
                rowOffset,
                lastPackedLcd,
                rowOffset,
                R47LcdContract.PACKED_ROW_SIZE_BYTES,
            )

            val rowId = buffer[rowOffset + 1].toInt() and 0xFF
            val displayRow = (R47LcdContract.PIXEL_HEIGHT - rowId - 1)
                .coerceIn(0, R47LcdContract.PIXEL_HEIGHT - 1)

            decodePackedRow(buffer, rowOffset, displayRow)
            buffer[rowOffset] = 0
            if (displayRow < minRow) minRow = displayRow
            if (displayRow > maxRow) maxRow = displayRow
        }

        if (maxRow < minRow) {
            return false
        }

        invalidateLcdRegion(
            minX = 0,
            maxX = R47LcdContract.PIXEL_WIDTH - 1,
            minY = minRow,
            maxY = maxRow,
            pixelWidthF = R47LcdContract.PIXEL_WIDTH.toFloat(),
            pixelHeightF = R47LcdContract.PIXEL_HEIGHT.toFloat(),
        )
        return true
    }

    private fun redrawPackedSnapshot() {
        for (bufferRow in 0 until R47LcdContract.PIXEL_HEIGHT) {
            val rowOffset = bufferRow * R47LcdContract.PACKED_ROW_SIZE_BYTES
            val rowId = lastPackedLcd[rowOffset + 1].toInt() and 0xFF
            val displayRow = (R47LcdContract.PIXEL_HEIGHT - rowId - 1)
                .coerceIn(0, R47LcdContract.PIXEL_HEIGHT - 1)
            decodePackedRow(lastPackedLcd, rowOffset, displayRow)
        }

        invalidateLcdRegion(
            minX = 0,
            maxX = R47LcdContract.PIXEL_WIDTH - 1,
            minY = 0,
            maxY = R47LcdContract.PIXEL_HEIGHT - 1,
            pixelWidthF = R47LcdContract.PIXEL_WIDTH.toFloat(),
            pixelHeightF = R47LcdContract.PIXEL_HEIGHT.toFloat(),
        )
    }

    private fun decodePackedRow(buffer: ByteArray, rowOffset: Int, displayRow: Int) {
        for (byteIndex in 0 until R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW) {
            val packedByte = buffer[rowOffset + 2 + byteIndex].toInt() and 0xFF
            val destX = (R47LcdContract.PACKED_PIXEL_BYTES_PER_ROW - 1 - byteIndex) * 8
            for (bit in 0 until 8) {
                linePixels[destX + bit] = if ((packedByte and (1 shl (7 - bit))) != 0) {
                    lcdTextColor
                } else {
                    lcdBackgroundColor
                }
            }
        }

        lcdBitmap.setPixels(
            linePixels,
            0,
            R47LcdContract.PIXEL_WIDTH,
            0,
            displayRow,
            R47LcdContract.PIXEL_WIDTH,
            1,
        )
    }

    private fun invalidateLcdRegion(
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        pixelWidthF: Float,
        pixelHeightF: Float,
    ) {
        if (width <= 0 || height <= 0) {
            invalidate()
            return
        }

        val spec = currentChromeSpec()
        val projection = chromeLayout.computeProjection(spec, width.toFloat(), height.toFloat())

        // The packed bitmap already contains the cumulative LCD state. Repaint
        // the full LCD window so scaled graph frames do not leave stale crops
        // after repeated theme, resume, or settings transitions.
        val left = projection.offsetX + spec.lcdWindowLeft * projection.scale
        val top = projection.offsetY + spec.lcdWindowTop * projection.scale
        val right = projection.offsetX + (spec.lcdWindowLeft + spec.lcdWindowWidth) * projection.scale
        val bottom = projection.offsetY + (spec.lcdWindowTop + spec.lcdWindowHeight) * projection.scale

        dirtyRect.set(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        postInvalidateOnAnimation(dirtyRect.left, dirtyRect.top, dirtyRect.right, dirtyRect.bottom)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isPiPMode) return false
        if (!lcdGraphTouchEnabled) {
            lcdGestureActive = false
            lcdPanStarted = false
            lcdScalingActive = false
            activeLcdPointerId = MotionEvent.INVALID_POINTER_ID
            return false
        }
        if (showSettingsDiscoveryHint && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            completeSettingsDiscoveryHint()
        }
        gestureDetector.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lcdGestureActive = isPointInLcd(ev.x, ev.y)
                lcdPanStarted = false
                lcdScalingActive = false
                resetPanAnchor(ev.getPointerId(0), ev.x, ev.y)
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                lcdGestureActive = false
                lcdPanStarted = false
                lcdScalingActive = false
                activeLcdPointerId = MotionEvent.INVALID_POINTER_ID
            }
        }

        if (lcdGestureActive) {
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        if (isPiPMode) {
            val fKey = (event.x / width * 6).toInt() + 38
            if (event.action == MotionEvent.ACTION_DOWN) {
                onPiPKeyEvent?.invoke(fKey)
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                onPiPKeyEvent?.invoke(0)
            }
            return true
        }

        if (!lcdGraphTouchEnabled) {
            lcdGestureActive = false
            lcdPanStarted = false
            lcdScalingActive = false
            activeLcdPointerId = MotionEvent.INVALID_POINTER_ID
            return super.onTouchEvent(event)
        }

        if (!lcdGestureActive) {
            return super.onTouchEvent(event)
        }

        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!lcdScalingActive && event.pointerCount == 1) {
                    val pointerIndex = event.findPointerIndex(activeLcdPointerId)
                    if (pointerIndex < 0) {
                        resetPanAnchor(event.getPointerId(0), event.getX(0), event.getY(0))
                        return true
                    }

                    val currentX = event.getX(pointerIndex)
                    val currentY = event.getY(pointerIndex)
                    val dxPixels = currentX - lcdLastTouchX
                    val dyPixels = currentY - lcdLastTouchY

                    if (!lcdPanStarted) {
                        val totalDxPixels = currentX - lcdPanStartX
                        val totalDyPixels = currentY - lcdPanStartY
                        if (abs(totalDxPixels) >= panTouchSlopPx || abs(totalDyPixels) >= panTouchSlopPx) {
                            lcdPanStarted = true
                        }
                    }

                    lcdLastTouchX = currentX
                    lcdLastTouchY = currentY

                    if (lcdPanStarted) {
                        val lcdWidth = lcdDestRect.width()
                        val lcdHeight = lcdDestRect.height()
                        if (lcdWidth > 0f && lcdHeight > 0f) {
                            onLcdPanListener?.invoke(dxPixels / lcdWidth, dyPixels / lcdHeight)
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    val actionIndex = event.actionIndex
                    // Transitioning from pinch to one-finger drag needs a fresh pan anchor
                    // to avoid a jump from stale coordinates.
                    val remainingPointerCount = event.pointerCount - 1
                    if (remainingPointerCount == 1) {
                        lcdScalingActive = false
                        val remainingPointerIndex = if (actionIndex == 0) 1 else 0
                        if (remainingPointerIndex < event.pointerCount) {
                            resetPanAnchor(
                                event.getPointerId(remainingPointerIndex),
                                event.getX(remainingPointerIndex),
                                event.getY(remainingPointerIndex),
                            )
                        } else {
                            activeLcdPointerId = MotionEvent.INVALID_POINTER_ID
                            lcdPanStarted = false
                        }
                    } else if (event.getPointerId(actionIndex) == activeLcdPointerId) {
                        val newPointerIndex = if (actionIndex == 0) 1 else 0
                        if (newPointerIndex < event.pointerCount) {
                            resetPanAnchor(
                                event.getPointerId(newPointerIndex),
                                event.getX(newPointerIndex),
                                event.getY(newPointerIndex),
                            )
                        } else {
                            activeLcdPointerId = MotionEvent.INVALID_POINTER_ID
                            lcdPanStarted = false
                        }
                    }
                } else if (activeLcdPointerId == MotionEvent.INVALID_POINTER_ID) {
                    val actionIndex = event.actionIndex
                    resetPanAnchor(
                        event.getPointerId(actionIndex),
                        event.getX(actionIndex),
                        event.getY(actionIndex),
                    )
                } else {
                    lcdPanStarted = false
                }
            }

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                lcdGestureActive = false
                lcdPanStarted = false
                lcdScalingActive = false
                activeLcdPointerId = MotionEvent.INVALID_POINTER_ID
            }
        }

        return true
    }

    class LayoutParams(
        val logicalX: Float,
        val logicalY: Float,
        val logicalWidth: Float,
        val logicalHeight: Float,
        val showTouchZone: Boolean = false,
    ) : ViewGroup.LayoutParams(0, 0)

    fun addReplicaView(
        view: View,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        showTouchZone: Boolean = false,
    ) {
        addView(view, LayoutParams(x, y, w, h, showTouchZone))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)

        if (!isPiPMode) {
            val projection = chromeLayout.computeProjection(w.toFloat(), h.toFloat())
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val lp = child.layoutParams as LayoutParams
                val childWidth = (lp.logicalWidth * projection.scale).roundToInt().coerceAtLeast(0)
                val childHeight = (lp.logicalHeight * projection.scale).roundToInt().coerceAtLeast(0)
                child.measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
                )
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSettingsHintLayoutCache()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (isPiPMode) {
            for (i in 0 until childCount) {
                getChildAt(i).layout(0, 0, 0, 0)
            }
            return
        }

        val w = (r - l).toFloat()
        val h = (b - t).toFloat()
        val projection = chromeLayout.computeProjection(w, h)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val left = (projection.offsetX + lp.logicalX * projection.scale).roundToInt()
            val top = (projection.offsetY + lp.logicalY * projection.scale).roundToInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }

        updateSettingsHintLayoutCache()
        onGeometryLaidOut?.invoke()
    }

    private fun updateSettingsHintLayoutCache() {
        if (!showSettingsDiscoveryHint || isPiPMode || width <= 0 || height <= 0) {
            settingsHintLayoutCache = null
            return
        }

        val layoutSpec = currentChromeSpec()
        val projection = chromeLayout.computeProjection(layoutSpec, width.toFloat(), height.toFloat())
        val localShellRect = RectF(
            projection.offsetX,
            projection.offsetY,
            projection.offsetX + layoutSpec.shellWidth * projection.scale,
            projection.offsetY + layoutSpec.shellHeight * projection.scale,
        )
        val localLcdDestRect = RectF(
            projection.offsetX + layoutSpec.lcdWindowLeft * projection.scale,
            projection.offsetY + layoutSpec.lcdWindowTop * projection.scale,
            projection.offsetX + (layoutSpec.lcdWindowLeft + layoutSpec.lcdWindowWidth) * projection.scale,
            projection.offsetY + (layoutSpec.lcdWindowTop + layoutSpec.lcdWindowHeight) * projection.scale,
        )

        val overlayDensity = resources.displayMetrics.density
        val maxInfoCardWidth =
            localShellRect.width() - dp(SettingsDiscoveryHintVisualPolicy.CARD_OUTER_MARGIN_DP)
        val desiredInfoCardWidth = min(
            dp(SettingsDiscoveryHintVisualPolicy.CARD_MAX_WIDTH_DP),
            localShellRect.width() * SettingsDiscoveryHintVisualPolicy.CARD_WIDTH_RATIO,
        )
        val infoCardWidth = min(
            maxInfoCardWidth,
            max(dp(SettingsDiscoveryHintVisualPolicy.CARD_MIN_WIDTH_DP), desiredInfoCardWidth),
        )
        val onboardingGlyphGeometry = SettingsMenuGlyph.ONBOARDING_GEOMETRY
        val infoGlyphTabHeight = onboardingGlyphGeometry.tabHeightPx(overlayDensity)
        val infoGlyphGap = onboardingGlyphGeometry.gapPx(overlayDensity)
        val infoGlyphTextGap = dp(SettingsDiscoveryHintVisualPolicy.CARD_GLYPH_TEXT_GAP_DP)

        val infoPaddingHorizontal = dp(SettingsDiscoveryHintVisualPolicy.CARD_HORIZONTAL_PADDING_DP)
        val infoPaddingVertical = dp(SettingsDiscoveryHintVisualPolicy.CARD_VERTICAL_PADDING_DP)
        val infoMessage = resources.getString(R.string.settings_entry_hint_message)
        val infoTextWidth = (infoCardWidth - infoPaddingHorizontal * 2f).roundToInt().coerceAtLeast(1)
        val infoLayout = StaticLayout.Builder
            .obtain(infoMessage, 0, infoMessage.length, settingsHintInfoTextPaint, infoTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(dp(SettingsDiscoveryHintVisualPolicy.CARD_LINE_SPACING_DP), 1f)
            .build()
        val infoCardHeight = infoLayout.height + infoPaddingVertical * 2f + infoGlyphTabHeight + infoGlyphTextGap
        val infoCardOuterMargin = dp(SettingsDiscoveryHintVisualPolicy.CARD_OUTER_MARGIN_DP)
        val minInfoTop = max(localShellRect.top + infoCardOuterMargin, localLcdDestRect.bottom + infoCardOuterMargin)
        val maxInfoTop = localShellRect.bottom - infoCardHeight - infoCardOuterMargin
        val preferredInfoTop = localShellRect.centerY() - infoCardHeight / 2f
        val infoTop = if (maxInfoTop > minInfoTop) {
            preferredInfoTop.coerceIn(minInfoTop, maxInfoTop)
        } else {
            minInfoTop
        }
        val infoCardRect = RectF(
            localShellRect.centerX() - infoCardWidth / 2f,
            infoTop,
            localShellRect.centerX() + infoCardWidth / 2f,
            infoTop + infoCardHeight,
        )
        val infoGlyphTop = infoCardRect.top + infoPaddingVertical
        val infoGlyphRight =
            infoCardRect.centerX() + onboardingGlyphGeometry.totalWidthPx(overlayDensity) / 2f
        val infoTextTop = infoGlyphTop + infoGlyphTabHeight + infoGlyphTextGap

        settingsHintLayoutCache = SettingsHintLayoutCache(
            infoCardRect = RectF(infoCardRect),
            infoCornerRadius = dp(SettingsDiscoveryHintVisualPolicy.CARD_CORNER_RADIUS_DP),
            infoGlyphTop = infoGlyphTop,
            infoGlyphRight = infoGlyphRight,
            infoGlyphTabHeight = infoGlyphTabHeight,
            infoGlyphGap = infoGlyphGap,
            infoPaddingHorizontal = infoPaddingHorizontal,
            infoPaddingVertical = infoPaddingVertical,
            infoTextTop = infoTextTop,
            infoLayout = infoLayout,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (isPiPMode) {
            lcdDestRect.set(0f, 0f, w, h)
            canvas.drawBitmap(lcdBitmap, lcdRect, lcdDestRect, paint)
            return
        }

        val layoutSpec = currentChromeSpec()
        val projection = chromeLayout.computeProjection(layoutSpec, w, h)

        shellRect.set(
            projection.offsetX,
            projection.offsetY,
            projection.offsetX + layoutSpec.shellWidth * projection.scale,
            projection.offsetY + layoutSpec.shellHeight * projection.scale,
        )
        chromeLayout.drawShellBackground(
            canvas,
            layoutSpec,
            shellRect,
            projection.scale,
        )

        lcdDestRect.set(
            projection.offsetX + layoutSpec.lcdWindowLeft * projection.scale,
            projection.offsetY + layoutSpec.lcdWindowTop * projection.scale,
            projection.offsetX + (layoutSpec.lcdWindowLeft + layoutSpec.lcdWindowWidth) * projection.scale,
            projection.offsetY + (layoutSpec.lcdWindowTop + layoutSpec.lcdWindowHeight) * projection.scale
        )
        canvas.drawBitmap(lcdBitmap, lcdRect, lcdDestRect, paint)

        if (showTouchZones) {
            canvas.drawRect(lcdDestRect, zonePaint)

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val lp = child.layoutParams as? LayoutParams ?: continue
                if (!lp.showTouchZone) {
                    continue
                }
                canvas.drawRect(
                    child.left.toFloat(), child.top.toFloat(),
                    child.right.toFloat(), child.bottom.toFloat(),
                    zonePaint
                )
            }
        }

        super.dispatchDraw(canvas)

        if (showDeveloperPerformanceHud && !showSettingsDiscoveryHint) {
            drawDeveloperPerformanceHud(canvas)
        }

        if (showSettingsDiscoveryHint) {
            if (settingsHintLayoutCache == null) {
                updateSettingsHintLayoutCache()
            }
            val hintLayoutCache = settingsHintLayoutCache ?: return

            val infoPulse = oscillatingUnitPulse(
                nowMillis = SystemClock.uptimeMillis(),
                periodMillis = SettingsDiscoveryHintVisualPolicy.PULSE_PERIOD_MS,
            )
            settingsHintInfoFillPaint.alpha = (
                SettingsDiscoveryHintVisualPolicy.FILL_ALPHA_BASE +
                    infoPulse * SettingsDiscoveryHintVisualPolicy.FILL_ALPHA_DELTA
                ).roundToInt()
            settingsHintInfoStrokePaint.alpha = (
                SettingsDiscoveryHintVisualPolicy.CARD_STROKE_ALPHA_BASE +
                    infoPulse * SettingsDiscoveryHintVisualPolicy.CARD_STROKE_ALPHA_DELTA
                ).roundToInt()
            settingsHintInfoStrokePaint.strokeWidth =
                dp(SettingsDiscoveryHintVisualPolicy.CARD_STROKE_WIDTH_DP) +
                    infoPulse * dp(SettingsDiscoveryHintVisualPolicy.CARD_STROKE_EXTRA_WIDTH_DP)
            canvas.drawRoundRect(
                hintLayoutCache.infoCardRect,
                hintLayoutCache.infoCornerRadius,
                hintLayoutCache.infoCornerRadius,
                settingsHintInfoFillPaint,
            )
            canvas.drawRoundRect(
                hintLayoutCache.infoCardRect,
                hintLayoutCache.infoCornerRadius,
                hintLayoutCache.infoCornerRadius,
                settingsHintInfoStrokePaint,
            )
            SettingsMenuGlyph.drawRightAligned(
                canvas = canvas,
                right = hintLayoutCache.infoGlyphRight,
                top = hintLayoutCache.infoGlyphTop,
                tabHeight = hintLayoutCache.infoGlyphTabHeight,
                gap = hintLayoutCache.infoGlyphGap,
                orangePaint = settingsHintMenuOrangePaint,
                bluePaint = settingsHintMenuBluePaint,
            )
            canvas.save()
            canvas.translate(
                hintLayoutCache.infoCardRect.left + hintLayoutCache.infoPaddingHorizontal,
                hintLayoutCache.infoTextTop,
            )
            hintLayoutCache.infoLayout.draw(canvas)
            canvas.restore()

            postInvalidateOnAnimation()
        }
    }

    private fun oscillatingUnitPulse(nowMillis: Long, periodMillis: Long): Float {
        return (((sin((nowMillis % periodMillis) / periodMillis.toDouble() * SettingsDiscoveryHintVisualPolicy.FULL_PULSE_RADIANS) + 1.0) * 0.5)).toFloat()
    }

    private fun drawDeveloperPerformanceHud(canvas: Canvas) {
        val label = developerPerformanceLabel
        if (label.isEmpty()) {
            return
        }

        val availableHeight = (lcdDestRect.top - shellRect.top)
            .coerceAtLeast(dp(DeveloperPerformanceHudVisualPolicy.MIN_AVAILABLE_HEIGHT_DP))
        developerPerformanceTextPaint.textSize = (availableHeight * DeveloperPerformanceHudVisualPolicy.TEXT_HEIGHT_RATIO)
            .coerceIn(
                dp(DeveloperPerformanceHudVisualPolicy.MIN_TEXT_SIZE_DP),
                dp(DeveloperPerformanceHudVisualPolicy.MAX_TEXT_SIZE_DP),
            )

        val maxLabelWidth = (lcdDestRect.width() - dp(DeveloperPerformanceHudVisualPolicy.MAX_LABEL_HORIZONTAL_MARGIN_DP))
            .coerceAtLeast(dp(DeveloperPerformanceHudVisualPolicy.MIN_LABEL_WIDTH_DP))
        val measuredWidth = developerPerformanceTextPaint.measureText(label)
        if (measuredWidth > maxLabelWidth && measuredWidth > 0f) {
            developerPerformanceTextPaint.textSize *= maxLabelWidth / measuredWidth
        }

        val baseline = (lcdDestRect.top - dp(DeveloperPerformanceHudVisualPolicy.BASELINE_BOTTOM_INSET_DP))
            .coerceAtLeast(shellRect.top + developerPerformanceTextPaint.textSize)
        canvas.drawText(
            label,
            lcdDestRect.left + dp(DeveloperPerformanceHudVisualPolicy.LEADING_INSET_DP),
            baseline,
            developerPerformanceTextPaint,
        )
    }
}
