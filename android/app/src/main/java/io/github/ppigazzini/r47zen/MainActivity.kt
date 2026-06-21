package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.os.*
import android.util.Log
import android.view.View
import android.view.*
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.edit
import io.github.ppigazzini.r47zen.databinding.ActivityMainBinding
import android.content.SharedPreferences
import android.content.res.Configuration
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.roundToInt

@Keep
class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val TAG = "R47Activity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var replicaOverlay: ReplicaOverlay
    private lateinit var coreRuntime: NativeCoreRuntime
    private lateinit var storageAccessCoordinator: StorageAccessCoordinator
    private lateinit var displayActionController: DisplayActionController
    private lateinit var factoryResetController: FactoryResetController
    private lateinit var physicalKeyboardInputController: PhysicalKeyboardInputController
    private lateinit var preferenceController: MainActivityPreferenceController
    private lateinit var replicaOverlayController: ReplicaOverlayController
    private lateinit var keypadSnapshotStore: NativeKeypadSnapshotStore
    private val hapticFeedbackController by lazy {
        HapticFeedbackController(this)
    }
    private val appPreferences by lazy {
        getSharedPreferences(SlotStore.APP_PREFS_NAME, MODE_PRIVATE)
    }
    private lateinit var slotSessionController: SlotSessionController
    private lateinit var windowModeController: WindowModeController

    private val graphGestureLock = Any()
    private var graphGestureFlushQueued = false

    @Volatile
    private var lastGraphGestureFlushUptimeMs = 0L
    private val graphGestureAccumulator = GraphGestureAccumulator(
        panFlushEpsilon = GRAPH_PAN_FLUSH_EPSILON,
        panApplyLimit = GRAPH_PAN_APPLY_LIMIT,
        panPendingLimit = GRAPH_PAN_PENDING_LIMIT,
        scaleFlushEpsilon = GRAPH_SCALE_FLUSH_EPSILON,
        scaleFactorMin = GRAPH_SCALE_FACTOR_MIN,
        scaleFactorMax = GRAPH_SCALE_FACTOR_MAX,
    )
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val PREF_SETTINGS_DISCOVERY_PENDING = "settings_discovery_pending"
        private const val GRAPH_PAN_FLUSH_EPSILON = 0.0005f
        private const val GRAPH_PAN_APPLY_LIMIT = 1f
        private const val GRAPH_PAN_PENDING_LIMIT = GRAPH_PAN_APPLY_LIMIT * 4f
        private const val GRAPH_SCALE_FLUSH_EPSILON = 0.0001f
        private const val GRAPH_SCALE_FACTOR_MIN = 0.4f
        private const val GRAPH_SCALE_FACTOR_MAX = 2.5f

        // Minimum spacing between gesture flushes. Each flush re-solves the
        // graph (fnEqSolvGraph), so an unthrottled fast drag/pinch can drive the
        // heavy upstream solver far faster than the display can show, piling up
        // re-solves (and DrwMX rebuilds) on the core thread. Coalescing to about
        // one re-solve per display frame caps that load with no visible cost.
        private const val GRAPH_GESTURE_MIN_FLUSH_INTERVAL_MS = 16L

        init {
            System.loadLibrary("r47zen")
        }

        fun createFactoryResetIntent(context: Context): Intent {
            return FactoryResetController.createIntent(context)
        }
    }

    private fun syncAudioSettings(isBeeperEnabled: Boolean, beeperVolume: Int) {
        AudioEngine.updateSettings(isBeeperEnabled, beeperVolume)
    }

    @Keep
    fun playTone(milliHz: Int, durationMs: Int) {
        AudioEngine.playTone(milliHz, durationMs)
    }

    private fun applyLcdTheme(theme: String, luminancePercent: Int, isNegative: Boolean) {
        val palette = LcdThemePolicy.resolve(theme, luminancePercent, isNegative)
        replicaOverlay.setLcdColors(palette.text, palette.background)
        setLcdColors(palette.text, palette.background)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (prefs == null || key == null) return
        preferenceController.onPreferenceChanged(key)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (physicalKeyboardInputController.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (physicalKeyboardInputController.onKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (physicalKeyboardInputController.onKeyUp(keyCode)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun offerCoreTask(task: Runnable) {
        coreRuntime.offerTask(task)
    }

    private fun queueGraphPan(dxNorm: Float, dyNorm: Float) {
        synchronized(graphGestureLock) {
            graphGestureAccumulator.addPan(dxNorm, dyNorm)
        }
        scheduleGraphGestureFlush()
    }

    private fun queueGraphPinch(scaleFactor: Float) {
        synchronized(graphGestureLock) {
            graphGestureAccumulator.addScale(scaleFactor)
        }
        scheduleGraphGestureFlush()
    }

    private fun queueGraphReset() {
        offerCoreTask(Runnable { resetGraphNative() })
    }

    private fun scheduleGraphGestureFlush() {
        val shouldEnqueue = synchronized(graphGestureLock) {
            if (graphGestureFlushQueued) {
                false
            } else {
                graphGestureFlushQueued = true
                true
            }
        }

        if (!shouldEnqueue) {
            return
        }

        // Rate-limit the re-solve. While a flush is pending (graphGestureFlush
        // Queued stays true until it drains), further gesture deltas coalesce in
        // the accumulator instead of queuing more re-solves, so a fast drag
        // applies at most one re-solve per interval and the net motion is never
        // lost.
        val sinceLast = SystemClock.uptimeMillis() - lastGraphGestureFlushUptimeMs
        if (sinceLast >= GRAPH_GESTURE_MIN_FLUSH_INTERVAL_MS) {
            offerGraphGestureFlush()
        } else {
            mainHandler.postDelayed(
                ::offerGraphGestureFlush,
                GRAPH_GESTURE_MIN_FLUSH_INTERVAL_MS - sinceLast,
            )
        }
    }

    private fun offerGraphGestureFlush() {
        lastGraphGestureFlushUptimeMs = SystemClock.uptimeMillis()
        offerCoreTask(Runnable { flushGraphGesturesOnCoreThread() })
    }

    private fun flushGraphGesturesOnCoreThread() {
        while (true) {
            val batch = synchronized(graphGestureLock) { graphGestureAccumulator.drainBatch() }

            if (batch != null) {
                val hasPan =
                    abs(batch.panDxNorm) > GRAPH_PAN_FLUSH_EPSILON ||
                        abs(batch.panDyNorm) > GRAPH_PAN_FLUSH_EPSILON
                val hasScale = abs(batch.scaleFactor - 1f) > GRAPH_SCALE_FLUSH_EPSILON
                // Apply a combined drag+pinch in one native call so the heavy
                // graph re-solve runs once per batch instead of twice during
                // fast play. Fall back to the single-axis bridges otherwise.
                when {
                    hasPan && hasScale ->
                        applyGraphPanZoomNative(batch.panDxNorm, batch.panDyNorm, batch.scaleFactor)
                    hasPan -> applyGraphPanNative(batch.panDxNorm, batch.panDyNorm)
                    hasScale -> applyGraphPinchZoomNative(batch.scaleFactor)
                }
            }

            val shouldContinue = synchronized(graphGestureLock) {
                val hasPending = graphGestureAccumulator.hasPending()
                if (!hasPending) {
                    graphGestureFlushQueued = false
                }
                hasPending
            }

            if (!shouldContinue) {
                return
            }
        }
    }

    private fun dispatchLiveKey(keyCode: Int) {
        LiveKeyRouter.route(
            keyCode,
            queryDirectStopGate = { requestStopProgramNative() },
            forwardToCore = { code -> offerCoreTask(Runnable { sendKey(code) }) },
        )
    }

    private fun configureActivityShell() {
        volumeControlStream = AudioManager.STREAM_MUSIC
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyDisplayCutoutMode()
    }

    private fun applyDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun initializeStartupControllers() {
        windowModeController = createWindowModeController()
        factoryResetController = createFactoryResetController()
        storageAccessCoordinator = createStorageAccessCoordinator()
        displayActionController = createDisplayActionController()
        physicalKeyboardInputController = createPhysicalKeyboardInputController()
        slotSessionController = createSlotSessionController()
    }

    private fun createWindowModeController(): WindowModeController {
        return WindowModeController(
            activity = this,
            mainHandler = mainHandler,
            onPiPModeChanged = ::handleOverlayPictureInPictureModeChanged,
        )
    }

    private fun handleOverlayPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        Log.i(TAG, "Updating overlay for pipMode=$isInPictureInPictureMode")
        if (::replicaOverlayController.isInitialized) {
            replicaOverlayController.handlePictureInPictureModeChanged(isInPictureInPictureMode)
        } else if (::replicaOverlay.isInitialized) {
            replicaOverlay.setPiPMode(isInPictureInPictureMode)
        }
    }

    private fun createFactoryResetController(): FactoryResetController {
        return FactoryResetController(
            activity = this,
            onResetRequested = ::handleFactoryResetRequested,
            onDestroyFactoryReset = ::handleFactoryResetDestroy,
            onDestroyFinish = ::handleFactoryResetFinish,
        )
    }

    private fun handleFactoryResetRequested() {
        coreRuntime.dispose(stopApp = true)
        AudioEngine.stop()
    }

    private fun handleFactoryResetDestroy() {
        AudioEngine.stop()
        releaseNativeRuntime()
        NativeCoreRuntime.resetSharedState()
    }

    private fun handleFactoryResetFinish() {
        AudioEngine.stop()
        releaseNativeRuntime()
    }

    private fun createStorageAccessCoordinator(): StorageAccessCoordinator {
        return StorageAccessCoordinator(
            activity = this,
            onNativeFileSelected = ::onFileSelectedNative,
            onNativeFileCancelled = ::onFileCancelledNative,
        ).also { coordinator ->
            coordinator.registerLaunchers()
        }
    }

    private fun createDisplayActionController(): DisplayActionController {
        return DisplayActionController(
            context = this,
            mainHandler = mainHandler,
            offerCoreTask = ::offerCoreTask,
            getClipboardXRegisterNative = ::getClipboardXRegisterNative,
            getClipboardStackRegistersNative = ::getClipboardStackRegistersNative,
            getClipboardAllRegistersNative = ::getClipboardAllRegistersNative,
            sendSimFuncNative = ::sendSimFuncNative,
            sendSimKeyNative = ::sendSimKeyNative,
            enterPiP = windowModeController::enterPictureInPicture,
        )
    }

    private fun createPhysicalKeyboardInputController(): PhysicalKeyboardInputController {
        return PhysicalKeyboardInputController(
            offerCoreTask = ::offerCoreTask,
            sendSimKeyNative = ::sendSimKeyNative,
            sendSimMenuNative = ::sendSimMenuNative,
        )
    }

    private fun createSlotSessionController(): SlotSessionController {
        return SlotSessionController(
            context = this,
            mainHandler = mainHandler,
            offerCoreTask = ::offerCoreTask,
            saveStateNative = ::saveStateNative,
            loadStateNative = ::loadStateNative,
            setSlotNative = ::setSlotNative,
        )
    }

    private fun initializeOverlayAndPreferences(prefs: SharedPreferences) {
        replicaOverlay = binding.replicaOverlay
        replicaOverlay.onSettingsDiscoveryCompleted = ::markSettingsDiscoveryComplete
        replicaOverlay.onLcdPanListener = ::queueGraphPan
        replicaOverlay.onLcdPinchListener = ::queueGraphPinch
        replicaOverlay.onLcdResetListener = ::queueGraphReset
        keypadSnapshotStore = createKeypadSnapshotStore()
        replicaOverlayController = createReplicaOverlayController()
        replicaOverlayController.bindOverlay()
        installMainMenuButton()

        preferenceController = createPreferenceController(prefs)
        prefs.registerOnSharedPreferenceChangeListener(this)
        preferenceController.applyInitialPreferences()

        replicaOverlay.post {
            preferenceController.applyDeferredOverlayPreferences()
            replicaOverlayController.refreshDynamicKeys()
        }
    }

    private fun installMainMenuButton() {
        val button = object : View(this) {
            private val menuGlyphGeometry = SettingsMenuGlyph.MAIN_MENU_GEOMETRY
            private val orangeColor = MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorPrimary,
            )
            private val blueColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSecondary,
            )
            private val orangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = orangeColor
                style = Paint.Style.FILL
            }
            private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = blueColor
                style = Paint.Style.FILL
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)

                val density = resources.displayMetrics.density
                val tabHeight = menuGlyphGeometry.tabHeightPx(density)
                val gap = menuGlyphGeometry.gapPx(density)
                val top = height - tabHeight - (SettingsMenuGlyph.MAIN_MENU_BOTTOM_INSET_DP * density)

                SettingsMenuGlyph.drawRightAligned(
                    canvas = canvas,
                    right = width.toFloat(),
                    top = top,
                    tabHeight = tabHeight,
                    gap = gap,
                    orangePaint = orangePaint,
                    bluePaint = bluePaint,
                )
            }
        }.apply {
            contentDescription = context.getString(R.string.main_menu_button_content_description)
            TooltipCompat.setTooltipText(this, contentDescription)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { anchor ->
                completeSettingsDiscovery()
                displayActionController.showMainMenu(anchor) {
                    openSettingsFromMainMenu()
                }
            }
        }

        replicaOverlay.addReplicaView(
            button,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_LEFT,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_TOP,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_WIDTH,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_HEIGHT,
            showTouchZone = true,
        )
    }

    private fun createReplicaOverlayController(): ReplicaOverlayController {
        return ReplicaOverlayController(
            context = this,
            overlay = replicaOverlay,
            performHapticClick = hapticFeedbackController::performClick,
            dispatchLiveKey = ::dispatchLiveKey,
            getKeypadSnapshot = keypadSnapshotStore::snapshotForMode,
            isRuntimeReady = { ::coreRuntime.isInitialized },
        )
    }

    private fun createKeypadSnapshotStore(): NativeKeypadSnapshotStore {
        return NativeKeypadSnapshotStore(
            getKeypadSnapshotGeneration = ::getKeypadSnapshotGeneration,
            copyKeypadSnapshotNative = ::copyKeypadSnapshotNative,
        )
    }

    private fun createPreferenceController(prefs: SharedPreferences): MainActivityPreferenceController {
        return MainActivityPreferenceController(
            preferences = prefs,
            window = window,
            hapticFeedbackController = hapticFeedbackController,
            windowModeController = windowModeController,
            syncAudioSettings = ::syncAudioSettings,
            applyLcdTheme = ::applyLcdTheme,
            applyLcdGraphTouchEnabled = replicaOverlay::setLcdGraphTouchEnabled,
            applyShowTouchZones = replicaOverlay::setShowTouchZones,
            applyShowDeveloperPerformanceHud = replicaOverlay::setShowDeveloperPerformanceHud,
            applyKeypadLabelModes = replicaOverlayController::applyKeypadLabelModes,
        )
    }

    private fun openSettingsFromMainMenu() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun dpToPx(value: Float): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun startCoreRuntime() {
        coreRuntime = createCoreRuntime()
        coreRuntime.attach()
        AudioEngine.start { NativeCoreRuntime.isAppRunning() }
    }

    private fun createCoreRuntime(): NativeCoreRuntime {
        return NativeCoreRuntime(
            filesDirPath = filesDir.absolutePath,
            currentSlotIdProvider = slotSessionController::currentSlotId,
            nativePreInit = ::nativePreInit,
            initNative = ::initNative,
            updateNativeActivityRef = ::updateNativeActivityRef,
            tick = ::tick,
            saveStateNative = ::saveStateNative,
            forceRefreshNative = ::forceRefreshNative,
            getPackedDisplayGeneration = ::getPackedDisplayGeneration,
            getPackedDisplayBuffer = ::getPackedDisplayBuffer,
            getKeypadSnapshotGeneration = ::getKeypadSnapshotGeneration,
            getMainKeyDynamicModeCode = replicaOverlayController::currentMainKeyDynamicModeCode,
            refreshKeypadSnapshot = keypadSnapshotStore::refreshSnapshot,
            onPackedLcd = replicaOverlay::updatePackedLcd,
            onDynamicRefresh = replicaOverlayController::refreshDynamicKeys,
            isPerformanceSnapshotEnabled = { preferenceController.showDeveloperPerformanceHud },
            getPerformanceWindowMillis = { preferenceController.developerPerformanceHudWindowMillis.toLong() },
            onPerformanceSnapshot = replicaOverlay::updateDeveloperPerformanceSnapshot,
        )
    }

    private fun handleInitialIntent() {
        if (factoryResetController.isFactoryResetIntent(intent)) {
            binding.root.post { factoryResetController.handleResetRequest() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureActivityShell()
        val prefs = appPreferences
        initializeStartupControllers()
        initializeOverlayAndPreferences(prefs)
        startCoreRuntime()
        handleInitialIntent()
    }

    private external fun updateNativeActivityRef()

    override fun onDestroy() {
        // Drop any delayed callbacks (e.g. the graph-gesture flush) so they cannot
        // fire against a destroyed activity.
        mainHandler.removeCallbacksAndMessages(null)
        // Guard the lateinit controllers: if onCreate threw before startCoreRuntime
        // assigned them, onDestroy still runs, and an unguarded access would mask
        // the original crash with an UninitializedPropertyAccessException.
        val resetInProgress =
            ::factoryResetController.isInitialized && factoryResetController.isResetInProgress
        Log.i(TAG, "onDestroy: isFinishing=$isFinishing isFactoryResetInProgress=$resetInProgress")
        val shouldStopApp = isFinishing || resetInProgress
        if (::coreRuntime.isInitialized) {
            coreRuntime.dispose(stopApp = shouldStopApp)
        }
        appPreferences.unregisterOnSharedPreferenceChangeListener(this)
        if (::factoryResetController.isInitialized) {
            factoryResetController.handleDestroy(shouldStopApp)
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (factoryResetController.isFactoryResetIntent(intent)) {
            factoryResetController.handleResetRequest()
        }
    }

    override fun onResume() {
        super.onResume()
        replicaOverlayController.onHostResumed()
        storageAccessCoordinator.handleResume()
        maybeShowSettingsDiscoveryHint()
    }

    private fun maybeShowSettingsDiscoveryHint() {
        if (!appPreferences.getBoolean(PREF_SETTINGS_DISCOVERY_PENDING, true)) {
            return
        }
        if (factoryResetController.isResetInProgress) {
            return
        }
        replicaOverlay.setShowSettingsDiscoveryHint(true)
    }

    private fun completeSettingsDiscovery() {
        markSettingsDiscoveryComplete()
        replicaOverlay.dismissSettingsDiscoveryHint()
    }

    private fun markSettingsDiscoveryComplete() {
        appPreferences.edit { putBoolean(PREF_SETTINGS_DISCOVERY_PENDING, false) }
    }

    override fun onPause() {
        super.onPause()
        val isEnteringPiP = windowModeController.isEnteringPictureInPicture()
        Log.i(TAG, "onPause: isEnteringPiP=$isEnteringPiP")
        if (!isEnteringPiP && !factoryResetController.isResetInProgress && appPreferences.getBoolean("auto_save_minimize", true)) {
            Log.i(TAG, "Auto-saving state on pause (synchronous via core thread)...")
            coreRuntime.saveStateOnPause(autoSaveEnabled = true)
        }
    }

    @Keep
    fun requestFile(isSave: Boolean, defaultName: String, fileType: Int) {
        mainHandler.post {
            storageAccessCoordinator.requestNativeFile(isSave, defaultName, fileType)
        }
    }

    @Keep
    fun quitApp() {
        Log.i(TAG, "quitApp called from native")
        mainHandler.post {
            val forceClose = appPreferences.getBoolean("force_close_on_exit", false)
            if (forceClose) {
                finishAndRemoveTask()
            } else {
                moveTaskToBack(true)
            }
        }
    }

    @Keep
    fun processCoreTasks() {
        coreRuntime.processCoreTasks()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        windowModeController.handlePictureInPictureModeChanged(isInPictureInPictureMode)
    }

    private external fun nativePreInit(storagePath: String)
    private external fun initNative(storagePath: String, slotId: Int)
    private external fun tick(): Int
    private external fun releaseNativeRuntime()
    private external fun sendKey(keyCode: Int)
    private external fun sendSimKeyNative(keyId: String, isFn: Boolean, isRelease: Boolean)
    private external fun sendSimMenuNative(menuId: Int)
    private external fun sendSimFuncNative(funcId: Int)
    private external fun applyGraphPanNative(dxNorm: Float, dyNorm: Float): Boolean
    private external fun applyGraphPinchZoomNative(scaleFactor: Float): Boolean
    private external fun applyGraphPanZoomNative(dxNorm: Float, dyNorm: Float, scaleFactor: Float): Boolean
    private external fun resetGraphNative(): Boolean
    private external fun saveStateNative()
    private external fun loadStateNative()
    private external fun forceRefreshNative()
    private external fun setSlotNative(slot: Int)
    private external fun getClipboardXRegisterNative(): String
    private external fun getClipboardStackRegistersNative(): String
    private external fun getClipboardAllRegistersNative(): String
    private external fun getPackedDisplayGeneration(): Int
    private external fun getPackedDisplayBuffer(buffer: ByteArray): Boolean
    private external fun getKeypadSnapshotGeneration(): Int
    private external fun copyKeypadSnapshotNative(
        mainKeyDynamicMode: Int,
        metaBuffer: IntArray,
        labelsBuffer: Array<String>,
    ): Boolean
    private external fun requestStopProgramNative(): Boolean
    private external fun setLcdColors(text: Int, bg: Int)

    @Keep fun onFileSelected(fd: Int) { onFileSelectedNative(fd) }
    @Keep fun onFileCancelled() { onFileCancelledNative() }
    private external fun onFileSelectedNative(fd: Int)
    private external fun onFileCancelledNative()
}
