package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.edit
import io.github.ppigazzini.r47zen.databinding.ActivityMainBinding
import android.content.SharedPreferences
import android.content.res.Configuration
import com.google.android.material.color.MaterialColors

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener, CalculatorHost {

    private val TAG = "R47Activity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var replicaOverlay: ReplicaOverlay
    private lateinit var displayRefreshLoop: DisplayRefreshLoop
    private lateinit var storageAccessCoordinator: StorageAccessCoordinator
    private lateinit var displayActionController: DisplayActionController
    private val calculatorAnswers by lazy { CalculatorAnswers(applicationContext, mainHandler) }
    private lateinit var factoryResetController: FactoryResetController
    private lateinit var physicalKeyboardInputController: PhysicalKeyboardInputController
    private lateinit var preferenceController: MainActivityPreferenceController
    private lateinit var replicaOverlayController: ReplicaOverlayController
    private lateinit var keypadSnapshotStore: NativeKeypadSnapshotStore
    private var mainMenuButton: View? = null
    private val hapticFeedbackController by lazy {
        HapticFeedbackController(this)
    }
    private val appPreferences by lazy {
        getSharedPreferences(AppPreferences.NAME, MODE_PRIVATE)
    }
    private lateinit var windowModeController: WindowModeController


    private val mainHandler = Handler(Looper.getMainLooper())
    private val graphGestureFlusher = GraphGestureFlusher(
        accumulator = GraphGestureAccumulator(
            panFlushEpsilon = GRAPH_PAN_FLUSH_EPSILON,
            panApplyLimit = GRAPH_PAN_APPLY_LIMIT,
            panPendingLimit = GRAPH_PAN_PENDING_LIMIT,
            scaleFlushEpsilon = GRAPH_SCALE_FLUSH_EPSILON,
            scaleFactorMin = GRAPH_SCALE_FACTOR_MIN,
            scaleFactorMax = GRAPH_SCALE_FACTOR_MAX,
        ),
        minFlushIntervalMs = GRAPH_GESTURE_MIN_FLUSH_INTERVAL_MS,
        uptimeMillis = SystemClock::uptimeMillis,
        postDelayed = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
        applyBatch = ::applyGraphGestureBatch,
    )

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
        // re-solves (and DrwMX rebuilds) on the calculator thread. Coalescing to about
        // one re-solve per display frame caps that load with no visible cost.
        private const val GRAPH_GESTURE_MIN_FLUSH_INTERVAL_MS = 16L

        fun createFactoryResetIntent(context: Context): Intent {
            return FactoryResetController.createIntent(context)
        }
    }

    private fun syncAudioSettings(isBeeperEnabled: Boolean, beeperVolume: Int) {
        AudioEngine.updateSettings(isBeeperEnabled, beeperVolume)
    }

    override fun showDisplayText(isError: Boolean, text: String) {
        if (::replicaOverlay.isInitialized) {
            replicaOverlay.setDisplayDescription(text, isError)
        }
    }

    override fun onClipboardText(what: Int, text: String) {
        calculatorAnswers.onClipboardText(what, text)
    }

    override fun setClipboardText(text: String) {
        calculatorAnswers.setClipboardText(text)
    }

    override fun onPasteResult(pasted: Boolean) {
        calculatorAnswers.onPasteResult(pasted)
    }

    override fun showWarning(text: String) {
        showCalculatorWarning(this, text)
    }

    // A process killed behind the picker comes back with the answer and no request.
    override fun onFileRequestLost() {
        Toast.makeText(applicationContext, R.string.file_request_lost, Toast.LENGTH_LONG).show()
    }

    private fun applyLcdTheme(theme: String, luminancePercent: Int, isNegative: Boolean) {
        replicaOverlay.setLcdPalette(LcdThemePolicy.resolve(theme, luminancePercent, isNegative))
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (prefs == null || key == null) return
        if (key == MainActivityPreferenceController.KEY_AUTO_SAVE_MINIMIZE) {
            syncAutoSaveSetting()
        }
        preferenceController.onPreferenceChanged(key)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (physicalKeyboardInputController.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyGraphGestureBatch(batch: GraphGestureBatch) {
        CalculatorEngine.graphGestureNative(batch.panDxNorm, batch.panDyNorm, batch.scaleFactor)
    }

    private fun configureActivityShell() {
        volumeControlStream = AudioManager.STREAM_MUSIC
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    private fun initializeStartupControllers() {
        windowModeController = createWindowModeController()
        windowModeController.fitContentToSafeArea(binding.root)
        factoryResetController = createFactoryResetController()
        storageAccessCoordinator = createStorageAccessCoordinator()
        displayActionController = createDisplayActionController()
        physicalKeyboardInputController = createPhysicalKeyboardInputController()
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
        displayRefreshLoop.stop()
        CalculatorEngine.stopForFactoryReset()
        AudioEngine.stop()
    }

    private fun handleFactoryResetDestroy() {
        AudioEngine.stop()
    }

    // A second instance finishing leaves the first one using the audio.
    private fun handleFactoryResetFinish() {
        if (!CalculatorEngine.hasHost()) {
            AudioEngine.stop()
        }
    }

    private fun createStorageAccessCoordinator(): StorageAccessCoordinator {
        return StorageAccessCoordinator(
            activity = this,
            onNativeFileSelected = CalculatorEngine.fileRequestGate::deliverSelected,
            onNativeFileCanceled = { CalculatorEngine.fileRequestGate.deliverCanceled() },
            onLoadTransferStarted = CalculatorEngine.fileRequestGate::beginTransfer,
            onLoadTransferred = CalculatorEngine.fileRequestGate::deliverTransferred,
            onLoadTransferFailed = CalculatorEngine.fileRequestGate::deliverTransferFailed,
        ).also { coordinator ->
            coordinator.registerLaunchers()
        }
    }

    private fun createDisplayActionController(): DisplayActionController {
        return DisplayActionController(
            context = this,
            copyRegisters = CalculatorEngine::copyRegistersNative,
            pasteNumberNative = CalculatorEngine::pasteNumberNative,
            enterPiP = windowModeController::enterPictureInPicture,
        )
    }

    private fun createPhysicalKeyboardInputController(): PhysicalKeyboardInputController {
        return PhysicalKeyboardInputController(
            sendPcKey = CalculatorEngine::sendPcKeyNative,
            onPaste = { displayActionController.pasteFromClipboard() },
            onMainMenu = ::openMainMenu,
        )
    }

    // The keyboard's Menu key and a mouse's right click open the main menu, as a tap
    // on its button does.
    private fun openMainMenu() {
        mainMenuButton?.performClick()
    }

    private fun initializeOverlayAndPreferences(prefs: SharedPreferences) {
        replicaOverlay = binding.replicaOverlay
        replicaOverlay.onSettingsDiscoveryCompleted = ::markSettingsDiscoveryComplete
        replicaOverlay.onLcdPanListener = graphGestureFlusher::addPan
        replicaOverlay.onLcdPinchListener = graphGestureFlusher::addScale
        replicaOverlay.onLcdResetListener = CalculatorEngine::resetGraphNative
        replicaOverlay.onSecondaryClick = ::openMainMenu
        replicaOverlay.onDisplayDescriptionWanted = { CalculatorEngine.describeDisplayNative() }
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
            contentDescription = MainMenuButtonDescription.of(context, hintShowing = false)
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

        mainMenuButton = button
        replicaOverlay.addReplicaView(
            button,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_LEFT,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_TOP,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_WIDTH,
            R47AndroidChromeGeometry.MAIN_MENU_BUTTON_HEIGHT,
            showTouchZone = true,
        )
        // The first-run hint is a painted card: while it shows, the button a
        // screen reader can reach carries its welcome.
        replicaOverlay.onSettingsDiscoveryHintShown = { showing ->
            button.contentDescription = MainMenuButtonDescription.of(this, showing)
        }
    }

    private fun createReplicaOverlayController(): ReplicaOverlayController {
        return ReplicaOverlayController(
            context = this,
            overlay = replicaOverlay,
            performHapticClick = hapticFeedbackController::performClick,
            dispatchLiveKey = CalculatorEngine::dispatchKey,
            getKeypadSnapshot = keypadSnapshotStore::snapshotForMode,
            isRuntimeReady = CalculatorEngine::isReady,
        )
    }

    private fun createKeypadSnapshotStore(): NativeKeypadSnapshotStore {
        return NativeKeypadSnapshotStore(
            getKeypadSnapshotGeneration = CalculatorEngine::getKeypadSnapshotGeneration,
            copyKeypadSnapshotNative = CalculatorEngine::copyKeypadSnapshotNative,
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

    private fun syncAutoSaveSetting() {
        CalculatorEngine.setAutoSaveNative(
            appPreferences.getBoolean(
                MainActivityPreferenceController.KEY_AUTO_SAVE_MINIMIZE,
                MainActivityPreferenceController.DEFAULT_AUTO_SAVE_MINIMIZE,
            ),
        )
    }

    private fun startCalculator() {
        syncAutoSaveSetting()
        CalculatorEngine.start(this)
        displayRefreshLoop = createDisplayRefreshLoop()
    }

    private fun createDisplayRefreshLoop(): DisplayRefreshLoop {
        return NativeDisplayRefreshLoop(
            isAppRunning = CalculatorEngine::isThreadRunning,
            isNativeInitialized = CalculatorEngine::isReady,
            isPerformanceSnapshotEnabled = { preferenceController.showDeveloperPerformanceHud },
            getPerformanceWindowMillis = { preferenceController.developerPerformanceHudWindowMillis.toLong() },
            getPackedDisplayGeneration = CalculatorEngine::getPackedDisplayGeneration,
            getPackedDisplayBuffer = CalculatorEngine::getPackedDisplayBuffer,
            getKeypadSnapshotGeneration = CalculatorEngine::getKeypadSnapshotGeneration,
            getMainKeyDynamicModeCode = replicaOverlayController::currentMainKeyDynamicModeCode,
            refreshKeypadSnapshot = keypadSnapshotStore::refreshSnapshot,
            onPackedLcd = replicaOverlay::updatePackedLcd,
            onDynamicRefresh = replicaOverlayController::refreshDynamicKeys,
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
        startCalculator()
        handleInitialIntent()
    }

    override fun onDestroy() {
        // Drop any delayed callbacks (e.g. the graph-gesture flush) so they cannot
        // fire against a destroyed activity.
        mainHandler.removeCallbacksAndMessages(null)
        // Guard the lateinit controllers: if onCreate threw before startCalculator
        // assigned them, onDestroy still runs, and an unguarded access would mask
        // the original crash with an UninitializedPropertyAccessException.
        val resetInProgress =
            ::factoryResetController.isInitialized && factoryResetController.isResetInProgress
        Log.i(TAG, "onDestroy: isFinishing=$isFinishing isFactoryResetInProgress=$resetInProgress")
        val shouldStopApp = isFinishing || resetInProgress
        if (::displayRefreshLoop.isInitialized) {
            displayRefreshLoop.stop()
        }
        CalculatorEngine.detachHost(this, recreating = !shouldStopApp)
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

    override fun onStart() {
        super.onStart()
        // Here, not in onCreate: this may be an instance coming back from behind another that
        // has just finished, which took the calculator's host and the audio with it.
        CalculatorEngine.attachHost(this)
        AudioEngine.start(CalculatorEngine::isThreadRunning)
        CalculatorEngine.onHostStarted()
        displayRefreshLoop.start()
    }

    override fun onResume() {
        super.onResume()
        replicaOverlayController.onHostResumed()
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

    override fun onStop() {
        displayRefreshLoop.stop()
        // The user leaves the app to reach a print: the files go to the work directory now.
        DataFolderMirror.shared(this).mirror()
        // The first point at which the system may kill this process, and PiP's close
        // arrives here with no pause before it.
        CalculatorEngine.onHostStopped(
            stopping = this,
            saveState = !factoryResetController.isResetInProgress &&
                appPreferences.getBoolean(
                    MainActivityPreferenceController.KEY_AUTO_SAVE_MINIMIZE,
                    MainActivityPreferenceController.DEFAULT_AUTO_SAVE_MINIMIZE,
                ),
        )
        super.onStop()
    }

    override fun launchFilePicker(isSave: Boolean, defaultName: String, start: Uri?) {
        storageAccessCoordinator.requestNativeFile(isSave, defaultName, start)
    }

    override fun onCalculatorOff() {
        Log.i(TAG, "The calculator switched off")
        mainHandler.post {
            val forceClose = appPreferences.getBoolean(
                MainActivityPreferenceController.KEY_FORCE_CLOSE_ON_EXIT,
                MainActivityPreferenceController.DEFAULT_FORCE_CLOSE_ON_EXIT,
            )
            if (forceClose) {
                finishAndRemoveTask()
            } else {
                moveTaskToBack(true)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        windowModeController.handlePictureInPictureModeChanged(isInPictureInPictureMode)
    }
}
