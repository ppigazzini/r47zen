package io.github.ppigazzini.r47zen

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyRenderSpecSlotTest {
    @Test
    fun typedSlotAccessorsPreserveStableSerializedIds() {
        val valueLabel = LabelSpec(
            id = SoftkeyLabelSlot.VALUE.id,
            text = "12",
            visible = true,
            anchor = PointSpec(10f, 12f),
            bounds = RectSpec(4f, 6f, 10f, 12f),
            typeface = null,
            textSize = 8f,
            color = 0,
            align = Paint.Align.RIGHT,
            verticalAnchor = C47TextRenderer.TEXT_ANCHOR_TOP,
        )
        val previewLine = LineAdornmentSpec(
            id = SoftkeyAdornmentSlot.PREVIEW.id,
            line = LineSpec(PointSpec(0f, 0f), PointSpec(1f, 1f)),
            color = 0,
            strokeWidth = 1f,
        )
        val renderSpec = KeyRenderSpec(
            chrome = null,
            labels = listOf(valueLabel),
            adornments = listOf(previewLine),
            accessibility = AccessibilitySpec("preview"),
            geometry = null,
        )

        assertEquals("value", SoftkeyLabelSlot.VALUE.id)
        assertEquals("preview-line", SoftkeyAdornmentSlot.PREVIEW.id)
        assertSame(valueLabel, renderSpec.label(SoftkeyLabelSlot.VALUE))
        assertSame(previewLine, renderSpec.adornment(SoftkeyAdornmentSlot.PREVIEW))
    }
}
