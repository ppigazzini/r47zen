package io.github.ppigazzini.r47zen

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.*
import android.util.Log
import android.view.*
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import io.github.ppigazzini.r47zen.databinding.ActivityMainBinding
import android.content.SharedPreferences
import android.content.res.Configuration

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
    private val hapticFeedbackController by lazy {
        HapticFeedbackController(this)
    }
    private val appPreferences by lazy {
        getSharedPreferences(SlotStore.APP_PREFS_NAME, MODE_PRIVATE)
    }
    private lateinit var slotSessionController: SlotSessionController
    private lateinit var windowModeController: WindowModeController

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val PREF_SETTINGS_DISCOVERY_PENDING = "settings_discovery_pending"

        init {
            System.loadLibrary("r47_android")
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

    @Keep
    fun stopTone() {}

    private fun applyLcdTheme(theme: String, luminancePercent: Int, isNegative: Boolean) {
        val palette = LcdThemePolicy.resolve(theme, luminancePercent, isNegative)
        replicaOverlay.setLcdColors(palette.text, palette.background)
        setLcdColors(palette.text, palette.background)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (prefs == null || key == null) return
        preferenceController.onPreferenceChanged(key)
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
            getXRegisterNative = ::getXRegisterNative,
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
        replicaOverlayController = createReplicaOverlayController()
        replicaOverlayController.bindOverlay()

        preferenceController = createPreferenceController(prefs)
        prefs.registerOnSharedPreferenceChangeListener(this)
        preferenceController.applyInitialPreferences()

        replicaOverlay.post {
            preferenceController.applyDeferredOverlayPreferences()
            replicaOverlayController.refreshDynamicKeys()
        }

        displayActionController.bindOverlay(replicaOverlay)
        replicaOverlay.onSettingsTapListener = ::handleSettingsTap
    }

    private fun createReplicaOverlayController(): ReplicaOverlayController {
        return ReplicaOverlayController(
            context = this,
            overlay = replicaOverlay,
            performHapticClick = hapticFeedbackController::performClick,
            performHapticRelease = hapticFeedbackController::performRelease,
            offerCoreTask = ::offerCoreTask,
            sendKey = ::sendKey,
            getKeypadMetaNative = ::getKeypadMetaNative,
            getKeypadLabelsNative = ::getKeypadLabelsNative,
            isRuntimeReady = { ::coreRuntime.isInitialized },
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
            applyScalingMode = replicaOverlayController::applyScalingMode,
            applyShowTouchZones = replicaOverlay::setShowTouchZones,
            applyKeypadLabelModes = replicaOverlayController::applyKeypadLabelModes,
        )
    }

    private fun handleSettingsTap() {
        Log.i(TAG, "Settings tap received in MainActivity")
        completeSettingsDiscovery(openSettings = true)
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
            getPackedDisplayBuffer = ::getPackedDisplayBuffer,
            getKeypadMetaNative = ::getKeypadMetaNative,
            getMainKeyDynamicModeCode = replicaOverlayController::currentMainKeyDynamicModeCode,
            getKeypadSnapshot = replicaOverlayController::currentKeypadSnapshot,
            onPackedLcd = replicaOverlay::updatePackedLcd,
            onDynamicRefresh = replicaOverlayController::refreshDynamicKeys,
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
        Log.i(TAG, "onDestroy: isFinishing=$isFinishing isFactoryResetInProgress=${factoryResetController.isResetInProgress}")
        val shouldStopApp = isFinishing || factoryResetController.isResetInProgress
        coreRuntime.dispose(stopApp = shouldStopApp)
        appPreferences.unregisterOnSharedPreferenceChangeListener(this)
        factoryResetController.handleDestroy(shouldStopApp)
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

    override fun onPause() {
        super.onPause()
        val isEnteringPiP = windowModeController.isEnteringPictureInPicture()
        Log.i(TAG, "onPause: isEnteringPiP=$isEnteringPiP")
        if (!isEnteringPiP && !factoryResetController.isResetInProgress && appPreferences.getBoolean("auto_save_minimize", true)) {
            Log.i(TAG, "Auto-saving state on pause (synchronous via core thread)...")
            coreRuntime.saveStateOnPause(autoSaveEnabled = true)
        }
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

    private fun completeSettingsDiscovery(openSettings: Boolean) {
        appPreferences.edit().putBoolean(PREF_SETTINGS_DISCOVERY_PENDING, false).apply()
        replicaOverlay.setShowSettingsDiscoveryHint(false)
        if (openSettings) {
            startActivity(Intent(this, SettingsActivity::class.java))
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
    private external fun tick()
    private external fun releaseNativeRuntime()
    private external fun sendKey(keyCode: Int)
    private external fun sendSimKeyNative(keyId: String, isFn: Boolean, isRelease: Boolean)
    private external fun sendSimMenuNative(menuId: Int)
    private external fun sendSimFuncNative(funcId: Int)
    private external fun saveStateNative()
    private external fun loadStateNative()
    private external fun forceRefreshNative()
    private external fun setSlotNative(slot: Int)
    private external fun getXRegisterNative(): String
    private external fun getPackedDisplayBuffer(buffer: ByteArray)
    private external fun setLcdColors(text: Int, bg: Int)

    // Legacy keypad getters kept for bridge compatibility.
    private external fun getButtonLabelNative(keyCode: Int, type: Int, isDynamic: Boolean): String
    private external fun getSoftkeyLabelNative(fnKeyIndex: Int): String
    private external fun getKeyboardStateNative(): IntArray // returns [shiftF, shiftG, calcMode, userMode, alphaFlag]

    // Snapshot keypad APIs used by the default Android-native keypad.
    private external fun getKeypadMetaNative(mainKeyDynamicMode: Int): IntArray
    private external fun getKeypadLabelsNative(mainKeyDynamicMode: Int): Array<String>

    @Keep fun onFileSelected(fd: Int) { onFileSelectedNative(fd) }
    @Keep fun onFileCancelled() { onFileCancelledNative() }
    private external fun onFileSelectedNative(fd: Int)
    private external fun onFileCancelledNative()
}
