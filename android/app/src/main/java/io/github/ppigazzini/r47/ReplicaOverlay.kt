package io.github.ppigazzini.r47

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
import android.view.View
import android.view.ViewGroup
import android.util.Log
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class ReplicaOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    companion object {
        const val CHROME_MODE_NATIVE = "native"
        const val CHROME_MODE_TEXTURE = "r47_texture"
        const val CHROME_MODE_BACKGROUND = "r47_background"
    }

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
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 32, 32)
    }
    private val zonePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        alpha = 180
    }
    private val settingsHintSurfaceColor = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorSurfaceContainerHigh,
        Color.argb(236, 18, 21, 26),
    )
    private val settingsHintOnSurfaceColor = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorOnSurface,
        Color.parseColor("#F7F3EA"),
    )
    private val settingsHintTopFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = settingsHintSurfaceColor
    }
    private val settingsHintTopStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#83B7DF")
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val settingsHintInfoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = settingsHintSurfaceColor
    }
    private val settingsHintInfoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#83B7DF")
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val settingsHintTopTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = settingsHintOnSurfaceColor
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    private val settingsHintInfoTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = settingsHintOnSurfaceColor
        textSize = dp(14f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private var lcdTextColor = 0xFF303030.toInt()
    private var lcdBackgroundColor = 0xFFDFF5CC.toInt()
    private var showSettingsDiscoveryHint = false

    var onPiPKeyEvent: ((Int) -> Unit)? = null
    var onLongPressListener: ((Float, Float) -> Unit)? = null
    var onSettingsTapListener: (() -> Unit)? = null
    var onGeometryLaidOut: (() -> Unit)? = null

    private val gestureDetector: GestureDetector

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
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )
    }

    fun setPiPMode(enabled: Boolean) {
        isPiPMode = enabled
        requestLayout()
        invalidate()
    }

    fun setScalingMode(mode: String) {
        chromeLayout.setScalingMode(mode)
        requestLayout()
        invalidate()
    }

    fun setShowTouchZones(show: Boolean) {
        showTouchZones = show
        invalidate()
    }

    fun setShowSettingsDiscoveryHint(show: Boolean) {
        if (showSettingsDiscoveryHint == show) {
            return
        }

        showSettingsDiscoveryHint = show
        invalidate()
    }

    fun setChromeMode(mode: String) {
        if (!chromeLayout.setChromeMode(mode)) {
            return
        }
        requestLayout()
        invalidate()
    }

    fun setLcdColors(text: Int, background: Int) {
        if (lcdTextColor == text && lcdBackgroundColor == background) {
            return
        }
        lcdTextColor = text
        lcdBackgroundColor = background
        redrawPackedSnapshot()
    }

    fun setNativeChrome() {
        setChromeMode(CHROME_MODE_NATIVE)
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
        gestureDetector.onTouchEvent(ev)

        val projection = chromeLayout.computeProjection(width.toFloat(), height.toFloat())
        val lY = (ev.y - projection.offsetY) / projection.scale
        val spec = currentChromeSpec()

        // Intercept touches in the settings area (top bezel)
        if (lY < spec.topBezelSettingsTapHeight && lY > 0) {
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

        val projection = chromeLayout.computeProjection(width.toFloat(), height.toFloat())
        val lY = (event.y - projection.offsetY) / projection.scale
        val spec = currentChromeSpec()

        // If we intercepted this (or no one else took it), and it's in the bezel area
        if (lY < spec.topBezelSettingsTapHeight && lY > 0) {
            if (event.action == MotionEvent.ACTION_UP) {
                Log.i("ReplicaOverlay", "Settings area tap received")
                onSettingsTapListener?.invoke()
            }
            return true
        }

        return super.onTouchEvent(event)
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

        onGeometryLaidOut?.invoke()
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
            bodyPaint,
            paint,
        )

        lcdDestRect.set(
            projection.offsetX + layoutSpec.lcdWindowLeft * projection.scale,
            projection.offsetY + layoutSpec.lcdWindowTop * projection.scale,
            projection.offsetX + (layoutSpec.lcdWindowLeft + layoutSpec.lcdWindowWidth) * projection.scale,
            projection.offsetY + (layoutSpec.lcdWindowTop + layoutSpec.lcdWindowHeight) * projection.scale
        )
        canvas.drawBitmap(lcdBitmap, lcdRect, lcdDestRect, paint)

        if (showTouchZones) {
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
            // Show settings zone
            canvas.drawRect(
                projection.offsetX,
                projection.offsetY,
                projection.offsetX + layoutSpec.shellWidth * projection.scale,
                projection.offsetY + layoutSpec.topBezelSettingsTapHeight * projection.scale,
                zonePaint,
            )
        }

        super.dispatchDraw(canvas)

        if (showSettingsDiscoveryHint) {
            val topPulse = (((sin((SystemClock.uptimeMillis() % 1400L) / 1400.0 * (2.0 * PI)) + 1.0) * 0.5)).toFloat()
            val bottomPulse = (((sin((SystemClock.uptimeMillis() % 4200L) / 4200.0 * (2.0 * PI)) + 1.0) * 0.5)).toFloat()
            val maxBannerWidth = shellRect.width() - dp(24f)
            val desiredBannerWidth = min(dp(360f), shellRect.width() * 0.72f)
            val bannerWidth = min(maxBannerWidth, max(dp(220f), desiredBannerWidth))
            val radius = dp(18f)
            val topBorderAlpha = (150 + topPulse * 105f).roundToInt()
            val topBorderWidth = dp(2.5f) + topPulse * dp(1.5f)
            val topBannerRect = RectF(
                shellRect.centerX() - bannerWidth / 2f,
                projection.offsetY + dp(8f),
                shellRect.centerX() + bannerWidth / 2f,
                projection.offsetY + layoutSpec.topBezelSettingsTapHeight * projection.scale - dp(8f),
            )
            val topText = resources.getString(R.string.settings_entry_hint_chip)

            settingsHintTopStrokePaint.alpha = topBorderAlpha
            settingsHintTopStrokePaint.strokeWidth = topBorderWidth
            settingsHintTopTextPaint.textSize = (topBannerRect.height() * 0.29f).coerceIn(dp(11f), dp(17f))
            val topTextWidth = settingsHintTopTextPaint.measureText(topText)
            val topTextMaxWidth = topBannerRect.width() - dp(28f)
            if (topTextWidth > topTextMaxWidth && topTextWidth > 0f) {
                settingsHintTopTextPaint.textSize *= topTextMaxWidth / topTextWidth
            }

            canvas.drawRoundRect(topBannerRect, radius, radius, settingsHintTopFillPaint)
            canvas.drawRoundRect(topBannerRect, radius, radius, settingsHintTopStrokePaint)

            val topBaseline = topBannerRect.centerY() - (settingsHintTopTextPaint.descent() + settingsHintTopTextPaint.ascent()) / 2f
            canvas.drawText(topText, topBannerRect.centerX(), topBaseline, settingsHintTopTextPaint)

            val infoPaddingHorizontal = dp(18f)
            val infoPaddingVertical = dp(16f)
            val infoMessage = resources.getString(R.string.settings_entry_hint_message)
            val infoTextWidth = (bannerWidth - infoPaddingHorizontal * 2f).roundToInt().coerceAtLeast(1)
            val infoLayout = StaticLayout.Builder
                .obtain(infoMessage, 0, infoMessage.length, settingsHintInfoTextPaint, infoTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(dp(4f), 1f)
                .build()
            val infoCardHeight = infoLayout.height + infoPaddingVertical * 2f
            val minInfoTop = max(topBannerRect.bottom + dp(24f), lcdDestRect.bottom + dp(24f))
            val maxInfoTop = shellRect.bottom - infoCardHeight - dp(24f)
            val preferredInfoTop = shellRect.centerY() - infoCardHeight / 2f
            val infoTop = if (maxInfoTop > minInfoTop) {
                preferredInfoTop.coerceIn(minInfoTop, maxInfoTop)
            } else {
                minInfoTop
            }
            val infoCardRect = RectF(
                shellRect.centerX() - bannerWidth / 2f,
                infoTop,
                shellRect.centerX() + bannerWidth / 2f,
                infoTop + infoCardHeight,
            )

            settingsHintInfoStrokePaint.alpha = (150 + bottomPulse * 105f).roundToInt()
            settingsHintInfoStrokePaint.strokeWidth = dp(2.5f) + bottomPulse * dp(1.5f)
            canvas.drawRoundRect(infoCardRect, dp(22f), dp(22f), settingsHintInfoFillPaint)
            canvas.drawRoundRect(infoCardRect, dp(22f), dp(22f), settingsHintInfoStrokePaint)
            canvas.save()
            canvas.translate(
                infoCardRect.left + infoPaddingHorizontal,
                infoCardRect.top + infoPaddingVertical,
            )
            infoLayout.draw(canvas)
            canvas.restore()

            postInvalidateOnAnimation()
        }
    }
}
