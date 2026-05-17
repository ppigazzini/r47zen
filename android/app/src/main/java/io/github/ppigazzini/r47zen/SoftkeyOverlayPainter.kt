package io.github.ppigazzini.r47zen

import android.graphics.Canvas
import android.graphics.Paint

internal object SoftkeyOverlayPainter {
    fun draw(
        canvas: Canvas,
        overlay: SoftkeyOverlayAdornmentSpec,
        decorPaint: Paint,
        dotPaint: Paint,
        auxPaint: Paint,
    ) {
        decorPaint.color = overlay.color
        dotPaint.color = overlay.color

        when (overlay.kind) {
            SoftkeyOverlayKind.RADIO_FALSE -> {
                canvas.drawCircle(
                    overlay.center.x,
                    overlay.center.y,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_SIZE * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    decorPaint,
                )
            }

            SoftkeyOverlayKind.RADIO_TRUE -> {
                canvas.drawCircle(
                    overlay.center.x,
                    overlay.center.y,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_SIZE * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_HALF_EXTENT_RATIO,
                    decorPaint,
                )
                canvas.drawCircle(
                    overlay.center.x,
                    overlay.center.y,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_SIZE * KeyVisualPolicy.SOFTKEY_OVERLAY_MARK_DOT_RATIO,
                    dotPaint,
                )
            }

            SoftkeyOverlayKind.CHECKBOX_FALSE -> {
                val frame = requireNotNull(overlay.frameBounds)
                canvas.drawRect(frame.asRectF(), decorPaint)
            }

            SoftkeyOverlayKind.CHECKBOX_TRUE -> {
                val frame = requireNotNull(overlay.frameBounds)
                canvas.drawRect(frame.asRectF(), decorPaint)
                canvas.drawLine(
                    overlay.center.x - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_LEFT_X,
                    overlay.center.y,
                    overlay.center.x - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_MID_X,
                    overlay.center.y + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    decorPaint,
                )
                canvas.drawLine(
                    overlay.center.x - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_MID_X,
                    overlay.center.y + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    overlay.center.x + KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_RIGHT_X,
                    overlay.center.y - KeyVisualPolicy.SOFTKEY_OVERLAY_CHECK_DELTA_Y,
                    decorPaint,
                )
            }

            SoftkeyOverlayKind.MENU_BADGE_FALSE,
            SoftkeyOverlayKind.MENU_BADGE_TRUE -> {
                val frame = requireNotNull(overlay.frameBounds)
                canvas.drawRoundRect(
                    frame.asRectF(),
                    KeyVisualPolicy.SOFTKEY_OVERLAY_MB_CORNER_RADIUS,
                    KeyVisualPolicy.SOFTKEY_OVERLAY_MB_CORNER_RADIUS,
                    decorPaint,
                )
                overlay.label?.let { label ->
                    KeyRenderPainter.drawLabel(canvas, label, auxPaint)
                }
                overlay.underline?.let { underline ->
                    KeyRenderPainter.drawLine(canvas, underline, decorPaint)
                }
            }
        }
    }
}
