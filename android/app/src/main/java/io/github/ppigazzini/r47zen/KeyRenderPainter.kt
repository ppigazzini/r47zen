package io.github.ppigazzini.r47zen

import android.graphics.Canvas
import android.graphics.Paint

internal object KeyRenderPainter {
    fun drawChrome(
        canvas: Canvas,
        chrome: KeyChromeSpec,
        fillPaint: Paint,
        strokePaint: Paint,
    ) {
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = chrome.fillColor
        canvas.drawRoundRect(
            chrome.bounds.asRectF(),
            chrome.cornerRadius,
            chrome.cornerRadius,
            fillPaint,
        )
        chrome.pressedAccents.forEach { accent ->
            drawLine(canvas, accent, strokePaint)
        }
    }

    fun drawLabel(
        canvas: Canvas,
        label: LabelSpec,
        paint: Paint,
    ) {
        if (!label.visible) {
            return
        }

        C47TextRenderer.drawText(
            canvas = canvas,
            text = label.text,
            paint = paint,
            typeface = label.typeface,
            textSize = label.textSize,
            x = label.anchor.x,
            anchorY = label.anchor.y,
            color = label.color,
            align = label.align,
            verticalAnchor = label.verticalAnchor,
            alpha = label.alpha,
            textScaleX = label.textScaleX,
        )
    }

    // Draw with the float overloads, so the draw pass allocates nothing.
    fun drawMenuLabelMarker(
        canvas: Canvas,
        marker: MenuLabelMarkerSpec,
        paint: Paint,
    ) {
        val bounds = marker.bounds
        paint.color = marker.color
        when (marker.shape) {
            MenuLabelMarkerShape.BAR -> {
                paint.style = Paint.Style.FILL
                canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint)
            }
            MenuLabelMarkerShape.OUTLINE, MenuLabelMarkerShape.FILL -> {
                paint.style = if (marker.shape == MenuLabelMarkerShape.OUTLINE) Paint.Style.STROKE else Paint.Style.FILL
                paint.strokeWidth = marker.strokeWidth
                canvas.drawRoundRect(
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom,
                    marker.cornerRadius,
                    marker.cornerRadius,
                    paint,
                )
            }
        }
    }

    fun drawLine(
        canvas: Canvas,
        line: LineAdornmentSpec,
        paint: Paint,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = line.strokeCap
        paint.strokeWidth = line.strokeWidth
        paint.color = line.color
        canvas.drawLine(
            line.line.start.x,
            line.line.start.y,
            line.line.end.x,
            line.line.end.y,
            paint,
        )
    }
}
