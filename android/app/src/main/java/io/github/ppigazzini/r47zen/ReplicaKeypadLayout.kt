package io.github.ppigazzini.r47zen

import android.content.Context
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

internal object ReplicaKeypadLayout {
    private const val SOFTKEY_WIDTH = R47ReferenceGeometry.STANDARD_KEY_WIDTH
    private const val SOFTKEY_HEIGHT = R47ReferenceGeometry.ROW_HEIGHT
    private const val NON_SOFTKEY_VIEW_HEIGHT = R47AndroidChromeGeometry.NON_SOFTKEY_VIEW_HEIGHT
    private val topLabelPlacementLanes = listOf(
        KeypadLane.SMALL_ROW_1,
        KeypadLane.SMALL_ROW_2,
        KeypadLane.ENTER_ROW,
        KeypadLane.MATRIX_ROW_1,
        KeypadLane.MATRIX_ROW_2,
        KeypadLane.MATRIX_ROW_3,
        KeypadLane.MATRIX_ROW_4,
    )
    private val UPPER_COLUMN_BOUNDARIES = floatArrayOf(94.0f, 366.0f, 638.0f, 910.0f, 1182.0f, 1454.0f, 1726.0f)
    private val UPPER_ROW_BOUNDARIES = floatArrayOf(1232.0f, 1492.0f, 1752.0f, 2012.0f, 2272.0f)
    private val LOWER_COLUMN_BOUNDARIES = floatArrayOf(82.5f, 413.5f, 744.5f, 1075.5f, 1406.5f, 1737.5f)
    private val LOWER_ROW_BOUNDARIES = floatArrayOf(2272.0f, 2532.0f, 2792.0f, 3052.0f, 3312.0f)

    private data class TouchZoneSpec(
        val code: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    private val baseTouchZones = buildBaseTouchZones()
    private val baseTouchZonesByCode = baseTouchZones.associateBy { it.code }

    fun rebuild(
        context: Context,
        overlay: ReplicaOverlay,
        performHapticClick: (View) -> Unit,
        performHapticRelease: (View) -> Unit,
        dispatchKey: (Int) -> Unit,
        initialSnapshotProvider: () -> KeypadSnapshot,
    ) {
        overlay.removeAllViews()
        addDynamicKeypad(
            context,
            overlay,
            performHapticClick,
            performHapticRelease,
            dispatchKey,
            initialSnapshotProvider,
        )
    }

    fun updateDynamicKeys(
        overlay: ReplicaOverlay,
        snapshot: KeypadSnapshot,
    ) {
        if (snapshot.sceneContractVersion <= 0) {
            return
        }

        for (index in 0 until overlay.childCount) {
            val child = overlay.getChildAt(index)
            if (child is CalculatorKeyView) {
                child.updateLabels(snapshot)
            }
        }
        overlay.requestLayout()
    }

    private fun addDynamicKeypad(
        context: Context,
        overlay: ReplicaOverlay,
        performHapticClick: (View) -> Unit,
        performHapticRelease: (View) -> Unit,
        dispatchKey: (Int) -> Unit,
        initialSnapshotProvider: () -> KeypadSnapshot,
    ) {
        val fonts = KeypadFontSet(
            standard = loadTypeface(context, "fonts/C47__StandardFont.ttf"),
            numeric = loadTypeface(context, "fonts/C47__NumericFont.ttf"),
            tiny = loadTypeface(context, "fonts/C47__TinyFont.ttf"),
        )
        val initialSnapshot = initialSnapshotProvider().takeIf {
            it.sceneContractVersion > 0
        }

        KeypadTopology.orderedSlots().forEach { slot ->
            val y = when (slot.lane) {
                KeypadLane.SOFTKEY_ROW -> R47ReferenceGeometry.SOFTKEY_ROW_TOP
                KeypadLane.SMALL_ROW_1 -> R47ReferenceGeometry.FIRST_SMALL_ROW_TOP
                KeypadLane.SMALL_ROW_2 -> R47ReferenceGeometry.FIRST_SMALL_ROW_TOP + R47ReferenceGeometry.ROW_STEP
                KeypadLane.ENTER_ROW -> R47ReferenceGeometry.ENTER_ROW_TOP
                KeypadLane.MATRIX_ROW_1 -> R47ReferenceGeometry.FIRST_LARGE_ROW_TOP
                KeypadLane.MATRIX_ROW_2 -> R47ReferenceGeometry.FIRST_LARGE_ROW_TOP + R47ReferenceGeometry.ROW_STEP
                KeypadLane.MATRIX_ROW_3 -> R47ReferenceGeometry.FIRST_LARGE_ROW_TOP + R47ReferenceGeometry.ROW_STEP * 2f
                KeypadLane.MATRIX_ROW_4 -> R47ReferenceGeometry.FIRST_LARGE_ROW_TOP + R47ReferenceGeometry.ROW_STEP * 3f
            }
            val x = when (slot.family) {
                KeypadKeyFamily.SOFTKEY,
                KeypadKeyFamily.STANDARD,
                KeypadKeyFamily.ENTER,
                KeypadKeyFamily.BASE_OPERATOR -> R47ReferenceGeometry.STANDARD_LEFT + R47ReferenceGeometry.STANDARD_PITCH * slot.column

                KeypadKeyFamily.NUMERIC_MATRIX ->
                    R47ReferenceGeometry.MATRIX_FIRST_VISIBLE_LEFT +
                        R47ReferenceGeometry.MATRIX_PITCH * (slot.column - 1)
            }
            val width = when (slot.family) {
                KeypadKeyFamily.SOFTKEY -> SOFTKEY_WIDTH
                KeypadKeyFamily.STANDARD -> R47ReferenceGeometry.STANDARD_PITCH
                KeypadKeyFamily.ENTER -> R47ReferenceGeometry.ENTER_WIDTH
                KeypadKeyFamily.BASE_OPERATOR -> R47ReferenceGeometry.STANDARD_KEY_WIDTH
                KeypadKeyFamily.NUMERIC_MATRIX -> R47ReferenceGeometry.MATRIX_PITCH
            }
            val height = if (slot.family == KeypadKeyFamily.SOFTKEY) {
                SOFTKEY_HEIGHT
            } else {
                NON_SOFTKEY_VIEW_HEIGHT
            }
            addKey(
                context = context,
                overlay = overlay,
                fonts = fonts,
                initialSnapshot = initialSnapshot,
                slot = slot,
                x = x,
                y = y,
                width = width,
                height = height,
                performHapticClick = performHapticClick,
                performHapticRelease = performHapticRelease,
                dispatchKey = dispatchKey,
            )
        }

        if (initialSnapshot != null) {
            overlay.requestLayout()
        }
    }

    internal fun applyTopLabelPlacementsAfterLayout(overlay: ReplicaOverlay) {
        val keyViewsByCode = mutableMapOf<Int, CalculatorKeyView>()
        for (index in 0 until overlay.childCount) {
            val child = overlay.getChildAt(index)
            if (child is CalculatorKeyView && child.keyCode in 1..37) {
                keyViewsByCode[child.keyCode] = child
            }
        }

        if (keyViewsByCode.isEmpty()) {
            return
        }

        val placementsByCode = mutableMapOf<Int, TopLabelLanePlacement>()
        for (lane in topLabelPlacementLanes) {
            val laneSlots = KeypadTopology.slotsForLane(lane)
            val laneGroups = laneSlots.mapNotNull { slot ->
                val keyView = keyViewsByCode[slot.code] ?: return@mapNotNull null
                val slotIndex = laneSlots.indexOf(slot)
                keyView.buildTopLabelLaneInput(
                    minLeftEdge = if (slotIndex == 0) 0f else Float.NEGATIVE_INFINITY,
                    maxRightEdge = if (slotIndex == laneSlots.lastIndex) overlay.width.toFloat() else Float.POSITIVE_INFINITY,
                )
            }
            if (laneGroups.isEmpty()) {
                continue
            }
            placementsByCode.putAll(TopLabelLaneLayout.solve(laneGroups))
        }

        keyViewsByCode.values.forEach { keyView ->
            keyView.applyTopLabelPlacement(placementsByCode[keyView.keyCode])
        }
    }

    private fun addKey(
        context: Context,
        overlay: ReplicaOverlay,
        fonts: KeypadFontSet,
        initialSnapshot: KeypadSnapshot?,
        slot: KeypadSlotSpec,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        performHapticClick: (View) -> Unit,
        performHapticRelease: (View) -> Unit,
        dispatchKey: (Int) -> Unit,
    ) {
        val keyView = CalculatorKeyView(context)
        keyView.setKey(slot, fonts)
        keyView.setDrawKeySurfaces(true)
        keyView.isClickable = true
        keyView.isFocusable = true
        keyView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        keyView.setOnClickListener {
            if (!it.isEnabled) {
                return@setOnClickListener
            }
            performHapticClick(it)
            dispatchKey(slot.code)
            dispatchKey(0)
        }
        initialSnapshot?.let { keyView.updateLabels(it) }
        addTouchZone(
            context = context,
            overlay = overlay,
            code = slot.code,
            performHapticClick = performHapticClick,
            performHapticRelease = performHapticRelease,
            dispatchKey = dispatchKey,
            pressedView = keyView,
        )
        keyView.setOnTouchListener(
            createTouchListener(
                code = slot.code,
                performHapticClick = performHapticClick,
                performHapticRelease = performHapticRelease,
                dispatchKey = dispatchKey,
                pressedView = keyView,
            )
        )
        overlay.addReplicaView(keyView, x, y, width, height)
    }

    private fun addTouchZone(
        context: Context,
        overlay: ReplicaOverlay,
        code: Int,
        performHapticClick: (View) -> Unit,
        performHapticRelease: (View) -> Unit,
        dispatchKey: (Int) -> Unit,
        pressedView: View? = null,
    ) {
        val touchZoneSpec = touchZoneFor(code)
        val touchZone = View(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = null
            setOnTouchListener(
                createTouchListener(
                    code = code,
                    performHapticClick = performHapticClick,
                    performHapticRelease = performHapticRelease,
                    dispatchKey = dispatchKey,
                    pressedView = pressedView,
                )
            )
        }

        overlay.addReplicaView(
            touchZone,
            touchZoneSpec.x,
            touchZoneSpec.y,
            touchZoneSpec.width,
            touchZoneSpec.height,
            showTouchZone = true,
        )
    }

    private fun loadTypeface(context: Context, assetPath: String): Typeface? {
        return try {
            Typeface.createFromAsset(context.assets, assetPath)
        } catch (_: Exception) {
            null
        }
    }

    private fun createTouchListener(
        code: Int,
        performHapticClick: (View) -> Unit,
        performHapticRelease: (View) -> Unit,
        dispatchKey: (Int) -> Unit,
        pressedView: View? = null,
    ): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            val feedbackView = pressedView ?: view
            if (!feedbackView.isEnabled) {
                feedbackView.isPressed = false
                return@OnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    feedbackView.isPressed = true
                    performHapticClick(feedbackView)
                    dispatchKey(code)
                }

                MotionEvent.ACTION_UP -> {
                    feedbackView.isPressed = false
                    dispatchKey(0)
                    performHapticRelease(feedbackView)
                }

                MotionEvent.ACTION_CANCEL -> {
                    feedbackView.isPressed = false
                    dispatchKey(0)
                }
            }
            true
        }
    }

    private fun touchZoneFor(code: Int): TouchZoneSpec {
        return baseTouchZonesByCode.getValue(code)
    }

    private fun buildBaseTouchZones(): List<TouchZoneSpec> {
        return KeypadTopology.orderedSlots().map { slot ->
            val rowBounds = if (slot.lane.usesUpperTouchGrid) {
                UPPER_ROW_BOUNDARIES
            } else {
                LOWER_ROW_BOUNDARIES
            }
            val columnBounds = if (slot.lane.usesUpperTouchGrid) {
                UPPER_COLUMN_BOUNDARIES
            } else {
                LOWER_COLUMN_BOUNDARIES
            }
            TouchZoneSpec(
                code = slot.code,
                x = columnBounds[slot.column],
                y = rowBounds[slot.lane.touchRowIndex],
                width = columnBounds[slot.column + slot.columnSpan] - columnBounds[slot.column],
                height = rowBounds[slot.lane.touchRowIndex + 1] - rowBounds[slot.lane.touchRowIndex],
            )
        }
    }
}
