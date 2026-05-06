package com.example.r47

import android.app.Activity
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportedKeypadFixtureRenderTest {
    @Test
    fun replicaKeypadLayoutAppliesExportedFixturesToMainAndSoftkeys() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val overlay = ReplicaOverlay(activity)
        activity.setContentView(overlay)

        val mainKeyView = createMainKeyView(activity, 12)
        val softkeyView = createMainKeyView(activity, 38)
        overlay.addReplicaView(mainKeyView, 0f, 0f, 272f, 260f)
        overlay.addReplicaView(softkeyView, 300f, 0f, 272f, 140f)
        overlay.measure(exactly(1200), exactly(800))
        overlay.layout(0, 0, 1200, 800)

        KeypadFixtureResources.loadAll().forEach { fixture ->
            val snapshot = fixture.snapshot()
            val mainState = snapshot.keyStateFor(12)
            val softkeyState = snapshot.keyStateFor(38)

            ReplicaKeypadLayout.updateDynamicKeys(overlay, snapshot)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(mainState.primaryLabel, mainKeyView.primaryLabel.text.toString())
            assertEquals(mainState.fLabel, mainKeyView.fLabel.text.toString())
            assertEquals(mainState.gLabel, mainKeyView.gLabel.text.toString())
            assertEquals(mainState.letterLabel, mainKeyView.letterLabel.text.toString())
            assertEquals(expectedSoftkeyContentDescription(softkeyState), softkeyView.contentDescription.toString())
        }
    }

    @Test
    fun exportedFixturesDriveStaticSingleAndAlphaLayoutRules() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val staticSnapshot = KeypadFixtureResources.load("static-single-scene").snapshot()
        val staticKeyCode = (1..37).first {
            staticSnapshot.keyStateFor(it).layoutClass == KeypadSceneContract.LAYOUT_CLASS_STATIC_SINGLE
        }
        val staticView = createMainKeyView(activity, staticKeyCode)
        staticView.updateLabels(staticSnapshot)
        assertEquals(View.GONE, staticView.gLabel.visibility)
        assertTrue(staticView.fLabel.text.isNotBlank())

        val alphaSnapshot = KeypadFixtureResources.load("alpha-prog-transition").snapshot()
        val alphaKeyCode = (1..37).first {
            alphaSnapshot.keyStateFor(it).layoutClass == KeypadSceneContract.LAYOUT_CLASS_ALPHA
        }
        val alphaView = createMainKeyView(activity, alphaKeyCode)
        alphaView.updateLabels(alphaSnapshot)
        assertEquals(View.INVISIBLE, alphaView.letterLabel.visibility)
    }

    private fun createMainKeyView(activity: Activity, code: Int): CalculatorKeyView {
        return CalculatorKeyView(activity).apply {
            setKey(
                KeypadTopology.slotFor(code),
                KeypadFontSet(standard = null, numeric = null, tiny = null),
            )
        }
    }

    private fun expectedSoftkeyContentDescription(keyState: KeypadKeySnapshot): String {
        return buildString {
            append(keyState.primaryLabel)
            if (keyState.hasSceneFlag(KeypadSceneContract.SCENE_FLAG_SHOW_TEXT) && keyState.auxLabel.isNotBlank()) {
                append(", ")
                append(keyState.auxLabel)
            }
            val valueText = when (keyState.showValue) {
                KeypadKeySnapshot.NO_VALUE,
                -127 -> ""
                else -> {
                    val prefix = if (keyState.showValue < 0) "-" else ""
                    prefix + kotlin.math.abs(keyState.showValue).toString()
                }
            }
            if (valueText.isNotBlank()) {
                append(", ")
                append(valueText)
            }
        }
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}