package com.everyday.everyday_glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Environment
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.everyday.everyday_glasses.binocular.BinocularContentClass
import com.everyday.everyday_glasses.binocular.DisplayConfig
import com.everyday.everyday_glasses.binocular.BinocularUpdateType
import com.everyday.everyday_glasses.binocular.BinocularView
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.transport.AssistantControl
import com.everyday.shared.transport.AssistantStatus
import com.everyday.shared.transport.DestinationSearchError
import com.everyday.shared.transport.DestinationSearchResult
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportResult
import com.everyday.shared.transport.TransportMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Glasses app with cursor controlled by phone trackpad or local temple trackpad.
 *
 * Features:
 * - Cursor control from phone or temple trackpad
 * - Double-tap to open context menu and create widgets
 * - Triple-tap to toggle 3DOF (world-locked) mode
 * - Dynamic text boxes with move/resize/delete
 * - Text input via phone keyboard when text field is focused
 * - Movable status bar showing phone/glasses battery and time
 */
class MainActivity : AppCompatActivity() {
    
    // Wake lock to prevent display from sleeping
    private var wakeLock: PowerManager.WakeLock? = null
    
    private lateinit var binocularView: BinocularView
    private lateinit var cursorView: CursorView
    private lateinit var widgetContainer: WidgetContainer
    private lateinit var persistenceManager: ActiveDashboardStore
    
    // Wake/Sleep state manager
    private lateinit var wakeSleepManager: WakeSleepManager

    // Head-up wake manager for hands-free screen wake
    private lateinit var headUpWakeManager: HeadUpWakeManager
    private lateinit var inputCoordinator: InputCoordinator
    private lateinit var userInputActivity: UserInputActivity
    private lateinit var assistantNetworkLease: AssistantNetworkLease

    // Track if this session was started by head-up wake (for auto-finish)
    private var isHeadUpWakeSession = false
    private var headUpWakeAutoFinishRunnable: Runnable? = null

    // 3DOF (Three Degrees of Freedom) head tracking manager
    private lateinit var threeDofManager: ThreeDofManager

    // Tap gesture detector for single/double/triple tap detection
    private lateinit var tapGestureDetector: TapGestureDetector

    // Mode: true = phone trackpad, false = temple trackpad (backend only, no UI toggle)
    private var isPhoneMode = true

    // Flag to prevent aggressive invalidation during display state transitions
    private var isTransitioningDisplayState = false
    private val keepAwakeMediaSources = mutableSetOf<String>()

    // Brightness management
    private lateinit var brightnessController: BrightnessController

    // Speed data management
    private lateinit var locationSpeedController: LocationSpeedController

    private val handler = android.os.Handler(Looper.getMainLooper())

    // Glasses battery update
    private var glassesBatteryUpdateRunnable: Runnable? = null
    private var batteryStateReceiver: BroadcastReceiver? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    
    // Time update
    private var timeUpdateRunnable: Runnable? = null
    
    // Auto-save timer

    // Pending widget restoration (waits for views to be ready)
    private var pendingWidgetRestore = false

    // File picker for opening images, PDFs, etc.
    private lateinit var filePicker: FilePicker
    private var openFileBrowserAfterAllFilesGrant = false

    private lateinit var phoneConnectionCoordinator: PhoneConnectionCoordinator // Phone communication (inbound / outbound) coordination
    private lateinit var googleAuthCoordinator: GoogleAuthCoordinator
    private lateinit var googleCalendarClient: GoogleCalendarClient
    private lateinit var dataSyncCoordinator: GlassesDataSyncCoordinator
    private lateinit var voiceAssistantCoordinator: VoiceAssistantCoordinator
    private lateinit var browserRoutingCoordinator: BrowserRoutingCoordinator

    companion object {
        private const val TAG = "Everyday_glasses"
        private const val REQUEST_PERMISSIONS = 1
        private const val REQUEST_STORAGE_PERMISSION = 2
        private const val REQUEST_ASSISTANT_AUDIO_PERMISSION = 3
        
        // Battery update interval
        private const val BATTERY_UPDATE_INTERVAL_MS = 30000L  // 30 seconds

        // Time update interval
        private const val TIME_UPDATE_INTERVAL_MS = 1000L  // 1 second

        // SharedPreferences keys for settings
        private const val PREFS_NAME = "everyday_settings"
        private const val KEY_HEAD_UP_TIME = "head_up_time_ms"
        private const val KEY_WAKE_DURATION = "wake_duration_ms"
        private const val KEY_ANGLE_THRESHOLD = "angle_threshold_degrees"
        private const val KEY_HEADSUP_ENABLED = "headsup_enabled"
        private const val KEY_SMART_ALIGNMENT = "smart_alignment_enabled"

        private const val DEFAULT_SMART_ALIGNMENT = true
        // Keep media dimming visible; the only intentional full black mask is DisplayState.OFF.

        // Speedometer logging gate (flip to false to silence)
        private const val SPEEDO_LOG = true
        private const val SPEEDO_LOG_TAG = "Speedometer"
        private const val ACTION_DEBUG_CLEAR_WEBVIEW =
            "com.everyday.everyday_glasses.action.DEBUG_CLEAR_WEBVIEW"
    }

    private inline fun speedLog(msg: () -> String) {
        if (SPEEDO_LOG) {
            Log.d(SPEEDO_LOG_TAG, msg())
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow showing over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Dismiss keyguard if possible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }

        // Keep screen on while app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Also acquire a wake lock to prevent display sleep
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Everyday_glasses::DisplayWakeLock"
        ).apply {
            acquire()
        }
        Log.d(TAG, "Wake lock acquired")
        
        setContentView(R.layout.activity_main)
        
        // Hide system UI after content view is set
        hideSystemUI()

        // Initialize brightness control
        brightnessController = BrightnessController(this)

        // Initialize speed data control


        // Initialize views
        binocularView = findViewById(R.id.binocularView)
        binocularView.initialize(this)

        cursorView = findViewById(R.id.cursorView)
        widgetContainer = findViewById(R.id.widgetContainer)
        userInputActivity = UserInputActivity { widgetContainer.cancelPresentationForUserInput() }
        widgetContainer.userInputActivity = userInputActivity

        brightnessController.initialize()
        val brightnessState = brightnessController.restore()
        widgetContainer.setBrightness(brightnessState.requestedBrightness)
        widgetContainer.setAdaptiveBrightnessEnabled(brightnessState.adaptiveEnabled)

        brightnessController.setMediaBrightnessCap(
            widgetContainer.shouldApplyMediaBrightnessCap()
        )

        brightnessController.setBrowserMinimumBrightness(
            widgetContainer.shouldApplyBrowserMinimumBrightness()
        )

        // Initialize location and speed data
        locationSpeedController = LocationSpeedController(
            activity = this,

            onSpeedChanged = { state ->
                widgetContainer.updateSpeedData(
                    state.speedKmh,
                    state.qualityOk,
                    state.isMoving
                )
            },

            onSpeedUnavailable = {
                widgetContainer.setSpeedPermissionDenied()
            },

            onLocationUpdated = { location ->
                widgetContainer.updateMapLocation(location)

                if (::dataSyncCoordinator.isInitialized) {
                    dataSyncCoordinator.onLocalLocationChanged()
                }
            },

            onLocationUnavailable = {
                widgetContainer.withTransientChangeBatch {
                    widgetContainer.setLocationPermissionDenied()
                    widgetContainer.setSpeedPermissionDenied()
                }
            }
        )

        // Initialize file picker for opening images, PDFs, etc.
        filePicker = FilePicker(this)

        cursorView.activityObserver = object : CursorActivityObserver {
            override fun onVisibilityChanged(visible: Boolean) {
                val cursorX = cursorView.getCursorX()
                val cursorY = cursorView.getCursorY()
                widgetContainer.updateCursor(cursorX, cursorY, visible)
                widgetContainer.onCursorVisibilityChanged(visible)
                if (!isTransitioningDisplayState) notifyTransientBinocularChange()
            }

            override fun onUserActivity() {
                widgetContainer.onCursorActivity()
            }
        }

        // Setup widget container
        setupWidgetContainer()
        persistenceManager = ActiveDashboardStore(
            context = this,
            handler = handler,
            captureDashboard = { widgetContainer.capturePersistedDashboard() },
            canSave = { !widgetContainer.isAwaitingDashboardRestore }
        )
        // Startup is one of the two moments an image can become unreachable; the other
        // is deleting a layout. See WidgetPersistence.pruneUnreferencedImages.
        WidgetPersistence.pruneUnreferencedImages(this)
        inputCoordinator = InputCoordinator(
            cursorView = cursorView,
            widgetContainer = widgetContainer,
            tapGestureDetectorProvider = {
                if (::tapGestureDetector.isInitialized) tapGestureDetector else null
            },
            isPhoneMode = { isPhoneMode },
            isAwake = { !::wakeSleepManager.isInitialized || wakeSleepManager.isAwake() },
            wakeDisplay = { wakeDisplayFromUserAction("input") },
            notifyUserActivity = ::notifyUserActivity,
            notifyTransientChange = ::notifyTransientBinocularChange,
            onTripleTap = ::handleTripleTap,
            userInputActivity = userInputActivity
        )
        updateCursorRedrawMode()
        registerBatteryStateReceiver()
        registerScreenStateReceiver()

        // Initialize Google auth after the mirrored UI is ready.
        setupGoogleAuth()
        setupDataSyncCoordinator()

        // Initialize wake/sleep manager
        setupWakeSleepManager()

        // Initialize head-up wake manager (for hands-free screen wake)
        setupHeadUpWakeManager()

        // Initialize 3DOF manager and tap gesture detector
        setup3DofManager()
        setupTapGestureDetector()

        val assistantPreferences = AssistantPreferencesStore(this)
        assistantNetworkLease = AssistantNetworkLease(this)
        browserRoutingCoordinator = BrowserRoutingCoordinator(
            pageAgent = PageAgentCoordinator.usingPreferences(
                preferences = assistantPreferences,
                provider = GeminiPageAgentProvider()
            ),
            host = WidgetContainerBrowserRoutingHost(widgetContainer),
            showApproval = widgetContainer::showPageAgentApproval,
            dismissApproval = widgetContainer::dismissPageAgentApproval
        )
        widgetContainer.configureBrowserRouting(browserRoutingCoordinator)
        voiceAssistantCoordinator = VoiceAssistantCoordinator(
            preferences = assistantPreferences,
            provider = GeminiVoiceAssistantProvider(),
            recorder = AssistantAudioRecorder(this),
            hasMicrophonePermission = {
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            requestMicrophonePermission = {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_ASSISTANT_AUDIO_PERMISSION
                )
            },
            toolCatalog = widgetContainer::assistantToolCatalog,
            captureUiContext = widgetContainer::assistantUiContext,
            stateObserver = object : AssistantStateObserver {
                override fun onAssistantStatusChanged(status: AssistantStatus) {
                    runOnUiThread {
                        assistantNetworkLease.update(status.state, status.sessionId != null)
                        widgetContainer.setAssistantStatus(status)
                        phoneConnectionOrNull()?.sendAssistantStatus(status)
                    }
                }

                override fun onAssistantConfigurationChanged(
                    configuration: AssistantConfiguration?,
                    models: List<AssistantModel>,
                    detail: String
                ) {
                    runOnUiThread {
                        widgetContainer.setAssistantConfiguration(configuration, models, detail)
                    }
                }
            },
            captureUiContextOnDemand = ::captureAssistantUiContextOnDemand,
            requestScreenshot = binocularView::captureAssistantScreenshot,
            presentText = widgetContainer::presentAssistantText,
            browserRouting = browserRoutingCoordinator
        )

        // On-demand inspection is marshaled to the UI thread; ordinary context capture
        // remains the small snapshot taken when the voice request is submitted.

        widgetContainer.configureAssistantSettings(
            voiceAssistantCoordinator::configureApiKey,
            voiceAssistantCoordinator::removeApiKey,
            voiceAssistantCoordinator::selectModel
        )
        voiceAssistantCoordinator.initialize()
        setupPhoneConnectionCoordinator()
        googleAuthCoordinator.refreshStateSilently(this)

        // Default: phone mode ON
        isPhoneMode = true
        cursorView.setCursorColor(Color.WHITE)
        
        // Initial status bar update
        updateStatusBar(
            isPhoneConnected = false,
            phoneBattery = -1
        )

        // Aggressively prevent system keyboard
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        
        // Force hide any existing keyboard
        hideSystemKeyboard()

        // Start glasses battery monitoring
        startGlassesBatteryMonitoring()
        
        // Start time updates
        startTimeUpdates()

        // Check if this is a returning user BEFORE layout happens.
        // This prevents onSizeChanged() from creating default singleton widgets
        // that the user has previously closed.
        val persistedState = persistenceManager.load()
        if (!persistedState.isFirstRun) {
            Log.d(TAG, "Returning user detected - preventing default singleton widget creation")
            widgetContainer.setIsReturningUser()
        }

        // Schedule widget restoration after layout
        pendingWidgetRestore = true
        val vto = widgetContainer.viewTreeObserver
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (pendingWidgetRestore && widgetContainer.isReady()) {
                    pendingWidgetRestore = false
                    restoreWidgetState(persistedState)  // Pass the already-loaded state
                    handleDebugIntent(intent)
                    widgetContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        }
        vto.addOnGlobalLayoutListener(listener)

        // Check permissions and start
        if (hasPermissions() && locationSpeedController.hasAnyLocationPermission()) {
            startClient()
        } else {
            requestPermissions()
        }

        // Start location updates
        locationSpeedController.startLocationUpdates()
        GlassesService.start(this)

        // Note: Head-up wake is now handled via static flags in onResume()
        // This ensures consistent handling whether activity is created fresh or resumed
        // The static flags are checked in onResume which runs after onCreate

        Log.d(TAG, "onCreate complete, isPhoneMode=$isPhoneMode")
    }

    /**
     * Hide system UI and enable immersive mode to prevent OS gestures.
     */
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ approach
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 10 and below
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun updateCursorRedrawMode() {
        if (!::cursorView.isInitialized || !::binocularView.isInitialized) return
        cursorView.setGentleRedrawEnabled(binocularView.isUsingPixelCopyMode())
    }

    private fun refreshBinocularRenderingStrategy() {
        binocularView.refreshRenderingStrategy()
        updateCursorRedrawMode()
    }

    private fun notifyStructuralBinocularChange(
        changedView: View? = null,
        contentClass: BinocularContentClass = BinocularContentClass.INTERACTIVE
    ) {
        binocularView.notifyContentChanged(
            changedView = changedView,
            contentClass = contentClass,
            updateType = BinocularUpdateType.STRUCTURAL
        )
    }

    private fun notifyTransientBinocularChange(
        changedView: View? = null,
        contentClass: BinocularContentClass = BinocularContentClass.INTERACTIVE
    ) {
        binocularView.notifyContentChanged(
            changedView = changedView,
            contentClass = contentClass,
            updateType = BinocularUpdateType.TRANSIENT
        )
    }

    private fun setupWidgetContainer() {
        widgetContainer.contentObserver = object : WidgetContentObserver {
            override fun onFocusChanged(focused: Boolean) {
                Log.d(TAG, "Widget focus changed: $focused")
                phoneConnectionOrNull()?.sendTextFieldFocus(focused)
            }

            override fun onPersistentContentChanged() {
                refreshBinocularRenderingStrategy()
                notifyStructuralBinocularChange(contentClass = BinocularContentClass.INTERACTIVE)
                saveWidgetStateDebounced()
            }

            override fun onTransientContentChanged() {
                notifyTransientBinocularChange(contentClass = BinocularContentClass.INTERACTIVE)
            }

            override fun onDashboardRestored() {
                refreshBinocularRenderingStrategy()
                notifyStructuralBinocularChange(contentClass = BinocularContentClass.INTERACTIVE)
            }

            override fun onMediaFrame(sourceId: String) {
                binocularView.onMediaFrame(sourceId)
                notifyTransientBinocularChange(contentClass = BinocularContentClass.MEDIA)
            }

            override fun onBinocularContentSourceChanged(
                sourceId: String,
                contentClass: BinocularContentClass,
                active: Boolean
            ) {
                binocularView.setContentClass(sourceId, contentClass, active)
                if (contentClass == BinocularContentClass.MEDIA) {
                    if (active) keepAwakeMediaSources += sourceId else keepAwakeMediaSources -= sourceId
                    if (::wakeSleepManager.isInitialized) {
                        wakeSleepManager.setKeepAwake(keepAwakeMediaSources.isNotEmpty())
                    }
                }
                refreshBinocularRenderingStrategy()
                notifyStructuralBinocularChange(
                    contentClass = if (active && contentClass == BinocularContentClass.MEDIA) {
                        BinocularContentClass.MEDIA
                    } else {
                        BinocularContentClass.INTERACTIVE
                    }
                )
            }
        }

        widgetContainer.host = object : WidgetHost {
            override fun requestClose() {
                Log.d(TAG, "Close requested by user")
                finish()
            }

            override fun requestMirror(start: Boolean) {
                Log.d(TAG, "Mirror request from widget: start=$start")
                phoneConnectionOrNull()?.sendMirrorRequest(start)
            }

            override fun requestSubtitleControl(control: SubtitleControl) {
                Log.d(TAG, "Subtitle control from widget: action=${control.action.wireName}")
                if (phoneConnectionOrNull()?.sendSubtitleControl(control) != true) {
                    widgetContainer.onSubtitlePhoneDisconnected()
                }
            }

            override fun requestBrowserKeyboard(show: Boolean) {
                Log.d(TAG, "Browser keyboard request: show=$show")
                phoneConnectionOrNull()?.sendTextFieldFocus(show)
            }

            override fun requestClipboard() {
                Log.d(TAG, "Clipboard request from widget - asking phone for current clipboard")
                phoneConnectionOrNull()?.requestClipboard()
            }

            override fun requestFilePicker() {
                Log.d(TAG, "File picker requested")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    requestAllFilesAccessForBrowser()
                } else if (hasStoragePermission()) {
                    openFileBrowser()
                } else {
                    this@MainActivity.requestStoragePermission()
                }
            }

            override fun requestStoragePermission() {
                Log.d(TAG, "Storage permission requested by widget")
                this@MainActivity.requestStoragePermission()
            }

            override fun requestCursorCenter() {
                val logicalWidth = if (cursorView.width > DisplayConfig.EYE_WIDTH) {
                    DisplayConfig.EYE_WIDTH
                } else {
                    cursorView.width
                }
                val logicalHeight = minOf(cursorView.height, DisplayConfig.EYE_HEIGHT)
                val centerX = logicalWidth.toFloat() / 2f
                val centerY = logicalHeight.toFloat() / 2f
                cursorView.setCursorPosition(centerX, centerY)
                widgetContainer.setCursorPosition(cursorView.getCursorX(), cursorView.getCursorY())
                notifyTransientBinocularChange()
            }

            override fun toggleAssistantVoice() {
                if (::voiceAssistantCoordinator.isInitialized) voiceAssistantCoordinator.toggleLocal()
            }
        }

        widgetContainer.displaySettingsHandler = object : DisplaySettingsHandler {
            override fun applySpeedVisibilityThreshold(threshold: Float) {locationSpeedController.setVisibilityThreshold(threshold)}
            override fun applyBrightness(brightness: Float) {brightnessController.setBrightness(brightness)}
            override fun applyAdaptiveBrightness(enabled: Boolean) {brightnessController.setAdaptiveBrightness(enabled)}
            override fun applyMediaBrightnessCap(active: Boolean) {brightnessController.setMediaBrightnessCap(active)}
            override fun applyBrowserMinimumBrightness(active: Boolean) {brightnessController.setBrowserMinimumBrightness(active)}

            override fun applyLayoutSwitchBrightness() {
                brightnessController.beginLayoutSwitchBrightness()
            }

            override fun applyHeadUpTime(durationMs: Long) {
                if (::headUpWakeManager.isInitialized) {
                    headUpWakeManager.headUpHoldTimeMs = durationMs
                }
                saveHeadUpSettings()
            }

            override fun applyWakeDuration(durationMs: Long) {
                if (::headUpWakeManager.isInitialized) headUpWakeManager.wakeDurationMs = durationMs
                saveHeadUpSettings()
            }

            override fun applyHeadUpAngleThreshold(degrees: Float) {
                if (::headUpWakeManager.isInitialized) headUpWakeManager.pitchChangeThreshold = degrees
                saveHeadUpSettings()
            }

            override fun applyHeadsUpEnabled(enabled: Boolean) {
                if (::headUpWakeManager.isInitialized) headUpWakeManager.isHeadsUpGestureEnabled = enabled
                saveHeadUpSettings()
            }

            override fun applySmartAlignment(enabled: Boolean) = saveSmartAlignmentSetting(enabled)
        }

        // Restore saved settings
        restoreHeadUpSettings()
        restoreSmartAlignmentSetting()
        val speedThreshold = locationSpeedController.restoreVisibilityThreshold()
        widgetContainer.setSpeedVisibilityThreshold(speedThreshold)
        speedLog { "Loaded speed visibility threshold ${speedThreshold}" }
    }

    private fun phoneConnectionOrNull(): PhoneConnectionCoordinator? =
        if (::phoneConnectionCoordinator.isInitialized) {
            phoneConnectionCoordinator
        } else {
            null
        }



    private fun setupPhoneConnectionCoordinator() {
        phoneConnectionCoordinator = PhoneConnectionCoordinator(
            context = this,
            callbacks = object : PhoneConnectionCallbacks {
                override fun onConnectionStatusChanged(status: String) {
                    Log.d(TAG, "Connection status: $status")
                    notifyTransientBinocularChange()
                }

                override fun onConnectionChanged(
                    connected: Boolean,
                    phoneBatteryPercent: Int
                ) {
                    widgetContainer.onPhoneRouteConnectionChanged(connected)

                    if (::voiceAssistantCoordinator.isInitialized) {
                        if (connected) {
                            phoneConnectionCoordinator.sendAssistantStatus(
                                voiceAssistantCoordinator.currentStatus()
                            )
                        } else {
                            voiceAssistantCoordinator.onPhoneDisconnected()
                        }
                    }

                    if (::dataSyncCoordinator.isInitialized) {
                        dataSyncCoordinator.onPhoneConnectionChanged(connected)
                    }

                    if (!connected && isPhoneMode) {
                        updateStatusBar(
                            isPhoneConnected = false,
                            phoneBattery = -1
                        )
                        widgetContainer.unfocusAll()
                        widgetContainer.setMirroringEnabled(false)
                        widgetContainer.onSubtitlePhoneDisconnected()
                    } else if (connected) {
                        updateStatusBar(
                            isPhoneConnected = true,
                            phoneBattery = phoneBatteryPercent
                        )
                        widgetContainer.onPhoneReconnected()
                    }
                }

                override fun onPhoneBatteryChanged(phoneBatteryPercent: Int) {
                    updateStatusBar(
                        isPhoneConnected = true,
                        phoneBattery = phoneBatteryPercent
                    )
                }

                override fun onPointerReceived(pointer: TransportMessage.Pointer) {
                    if (isPhoneMode) {
                        handlePhonePointer(pointer)
                    }
                }

                override fun onKeyReceived(key: String) {
                    handleKeyFromPhone(key)
                }

                override fun onLongPressWithClipboard(clipboardContent: String?) {
                    userInputActivity.onUserInput(UserInputKind.LONG_PRESS)
                    Log.d(
                        TAG,
                        "Phone clipboard received: available=${clipboardContent != null}"
                    )
                    widgetContainer.setPhoneClipboard(clipboardContent)
                }

                override fun onClipboardReceived(clipboardContent: String) {
                    Log.d(TAG, "Phone clipboard updated: chars=${clipboardContent.length}")
                    widgetContainer.setPhoneClipboard(clipboardContent)
                }

                override fun onImageReceived(imageData: ByteArray, fileName: String) {
                    Log.d(
                        TAG,
                        "Image received from phone: $fileName (${imageData.size} bytes)"
                    )
                    handleReceivedImage(imageData, fileName)
                }

                override fun onOpenBrowserRequested(address: String) {
                    Log.d(TAG, "Open browser requested from phone: ${address.take(80)}")
                    widgetContainer.executeOpenUrlCommand(address)
                }

                override fun onMirroringChanged(enabled: Boolean, ip: String?) {
                    widgetContainer.setMirroringEnabled(enabled, ip)
                }

                override fun onGoogleAuthStateReceived(
                    status: GooglePhoneAuthStatus,
                    account: GoogleAccountSummary?,
                    detail: String?
                ) {
                    if (status == GooglePhoneAuthStatus.SIGNED_OUT) {
                        dataSyncCoordinator.clearCalendarSnapshot()
                    }
                    googleAuthCoordinator.onPhoneAuthStateChanged(status, account, detail)
                }

                override fun onSyncSnapshotReceived(snapshot: SyncSnapshot) {
                    dataSyncCoordinator.onSyncSnapshotReceived(snapshot)
                }

                override fun onSyncErrorReceived(error: SyncError) {
                    dataSyncCoordinator.onSyncErrorReceived(error)
                }

                override fun onRouteResultReceived(result: RouteTransportResult) {
                    widgetContainer.onPhoneRouteResult(result)
                }

                override fun onRouteErrorReceived(error: RouteTransportError) {
                    widgetContainer.onPhoneRouteError(error)
                }

                override fun onDestinationSearchResultReceived(
                    result: DestinationSearchResult
                ) {
                    widgetContainer.onPhoneDestinationSearchResult(result)
                }

                override fun onDestinationSearchErrorReceived(
                    error: DestinationSearchError
                ) {
                    widgetContainer.onPhoneDestinationSearchError(error)
                }

                override fun onSubtitleStatusReceived(status: SubtitleStatus) {
                    widgetContainer.applySubtitleStatus(status)
                }

                override fun onSubtitleTranscriptReceived(
                    transcript: SubtitleTranscript
                ) {
                    widgetContainer.applySubtitleTranscript(transcript)
                }

                override fun onAssistantControlReceived(control: AssistantControl) {
                    if (::voiceAssistantCoordinator.isInitialized) {
                        voiceAssistantCoordinator.handleControl(control)
                    }
                }

            }
        )

        phoneConnectionCoordinator.initialize()
        widgetContainer.configurePhoneRouteTransport(phoneConnectionCoordinator)
    }
    private fun setupGoogleAuth() {
        googleAuthCoordinator = GoogleAuthCoordinator(
            clientId = getString(R.string.google_device_client_id),
            store = GoogleAuthPreferencesStore(this),
            networkStatusProvider = DefaultNetworkStatusProvider(this),
            phoneFallbackBridge = object : GooglePhoneFallbackBridge {
                override fun requestPhoneAuthorization() {
                    phoneConnectionOrNull()?.requestGooglePhoneAuthorization()
                }

                override fun requestCalendarSnapshot() {
                    if (::dataSyncCoordinator.isInitialized) {
                        dataSyncCoordinator.requestPhoneCalendarSnapshot()
                    }
                }

                override fun disconnectPhoneAuthorization() {
                    phoneConnectionOrNull()?.disconnectGooglePhoneAuth()
                }
            }
        ).apply {
            onStateChanged = { state ->
                runOnUiThread {
                    updateGoogleAuthUi(state)
                }
            }
        }

        googleCalendarClient = GoogleCalendarClient(googleAuthCoordinator)

        widgetContainer.googleAccountHandler = object : GoogleAccountHandler {
            override fun connectGoogleAccount() {
                googleAuthCoordinator.beginConnect(this@MainActivity)
            }

            override fun grantCalendarAccess() {
                googleAuthCoordinator.requestCalendarGrant(this@MainActivity)
            }

            override fun disconnectGoogleAccount() {
                if (::dataSyncCoordinator.isInitialized) {
                    dataSyncCoordinator.clearCalendarSnapshot()
                } else {
                    widgetContainer.setCalendarSnapshot(null)
                }
                googleAuthCoordinator.disconnect()
            }

            override fun retryGoogleAuthentication() {
                googleAuthCoordinator.retry(this@MainActivity)
            }

            override fun onGoogleAuthBrowserClosed() = Unit
        }

        updateGoogleAuthUi(googleAuthCoordinator.getCurrentState())
    }

    private fun setupDataSyncCoordinator() {
        dataSyncCoordinator = GlassesDataSyncCoordinator(
            context = this,
            widgetContainer = widgetContainer,
            syncMessengerProvider = { phoneConnectionOrNull() },
            networkStatusProvider = DefaultNetworkStatusProvider(this),
            googleAuthCoordinatorProvider = {
                if (::googleAuthCoordinator.isInitialized) googleAuthCoordinator else null
            },
            googleCalendarClientProvider = {
                if (::googleCalendarClient.isInitialized) googleCalendarClient else null
            },
            localLocationProvider = { locationSpeedController.latestLocalLocation },
            onPhoneLocationFix = locationSpeedController::applyPhoneLocationFix
        )
        widgetContainer.dataProvider = object : WidgetDataProvider {
            override fun requestFinanceData(
                visibleConfigs: List<FinanceDashboardTileConfig>,
                refreshTileIds: List<String>,
                force: Boolean,
                reason: String
            ) {
                dataSyncCoordinator.onFinanceDataRequested(visibleConfigs, refreshTileIds, force, reason)
            }

            override fun onFinanceVisibilityChanged(visible: Boolean) {
                dataSyncCoordinator.onFinanceVisibilityChanged(visible)
            }

            override fun requestNewsData(countryCode: String, force: Boolean, reason: String) {
                dataSyncCoordinator.onNewsDataRequested(countryCode, force, reason)
            }

            override fun requestNewsSourceData(
                sourceSpec: NewsSourceSpec,
                countryCode: String,
                force: Boolean,
                reason: String,
                completion: (Result<NewsSnapshot>) -> Unit
            ) {
                dataSyncCoordinator.requestNewsSource(sourceSpec, countryCode, completion)
            }

            override fun requestMapData(point: MapGeoPoint, config: PoiDisplayConfig) {
                dataSyncCoordinator.onMapDataRequested(point, config)
            }
        }
        dataSyncCoordinator.restoreCachedSnapshots()
    }

    private fun updateGoogleAuthUi(state: GoogleAuthState) {
        widgetContainer.setGoogleAuthState(state)
    }















    private fun saveSmartAlignmentSetting(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SMART_ALIGNMENT, enabled)
            .apply()
    }

    private fun restoreSmartAlignmentSetting() {
        val enabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SMART_ALIGNMENT, DEFAULT_SMART_ALIGNMENT)
        widgetContainer.setSmartAlignmentEnabled(enabled)
    }



    /**
     * Save head-up wake settings to SharedPreferences.
     */
    private fun saveHeadUpSettings() {
        val headUpTimeMs = widgetContainer.getHeadUpTime()
        val wakeDurationMs = widgetContainer.getWakeDuration()
        val angleThreshold = widgetContainer.getAngleThreshold()
        val headsUpEnabled = widgetContainer.getHeadsUpEnabled()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(KEY_HEAD_UP_TIME, headUpTimeMs)
            .putLong(KEY_WAKE_DURATION, wakeDurationMs)
            .putFloat(KEY_ANGLE_THRESHOLD, angleThreshold)
            .putBoolean(KEY_HEADSUP_ENABLED, headsUpEnabled)
            .apply()
        Log.d(TAG, "Saved head-up settings: headUpTime=${headUpTimeMs}ms, wakeDuration=${wakeDurationMs}ms, angle=${angleThreshold}°, enabled=$headsUpEnabled")
    }

    /**
     * Restore head-up wake settings from SharedPreferences.
     */
    private fun restoreHeadUpSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedHeadUpTime = prefs.getLong(KEY_HEAD_UP_TIME, HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS)
        val savedWakeDuration = prefs.getLong(KEY_WAKE_DURATION, HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS)
        val savedAngleThreshold = prefs.getFloat(KEY_ANGLE_THRESHOLD, HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD)
        val savedHeadsUpEnabled = prefs.getBoolean(KEY_HEADSUP_ENABLED, true)

        widgetContainer.setHeadUpTime(savedHeadUpTime)
        widgetContainer.setWakeDuration(savedWakeDuration)
        widgetContainer.setAngleThreshold(savedAngleThreshold)
        widgetContainer.setHeadsUpEnabled(savedHeadsUpEnabled)

        // Apply to HeadUpWakeManager if initialized
        if (::headUpWakeManager.isInitialized) {
            headUpWakeManager.headUpHoldTimeMs = savedHeadUpTime
            headUpWakeManager.wakeDurationMs = savedWakeDuration
            headUpWakeManager.pitchChangeThreshold = savedAngleThreshold
            headUpWakeManager.isHeadsUpGestureEnabled = savedHeadsUpEnabled
        }

        Log.d(TAG, "Restored head-up settings: headUpTime=${savedHeadUpTime}ms, wakeDuration=${savedWakeDuration}ms, angle=${savedAngleThreshold}°, enabled=$savedHeadsUpEnabled")
    }

    /**
     * Initialize and configure the wake/sleep manager.
     */
    private fun setupWakeSleepManager() {
        wakeSleepManager = WakeSleepManager { state ->
            Log.d(TAG, "Display state changed to: $state")

            // Set transition flag to prevent aggressive invalidation cascades
            // that cause power spikes and display flashes on battery power
            isTransitioningDisplayState = true

            // Notify head-up wake manager of screen state changes
            if (::headUpWakeManager.isInitialized) {
                headUpWakeManager.onScreenStateChanged(state == WakeSleepManager.DisplayState.OFF)
            }

            // Update visibility of UI elements based on state
            // Note: We use a controlled single invalidation approach to avoid
            // power spikes that cause display flashes on battery power.
            when (state) {
                WakeSleepManager.DisplayState.OFF -> {
                    // Hide everything
                    cursorView.setForceHidden(true, useGentleRedraw = true)
                    widgetContainer.setDisplayState(state)

                    // NEW: Actually turn off the display
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    wakeLock?.let {
                        if (it.isHeld) it.release()
                    }
                }
                WakeSleepManager.DisplayState.WAKE -> {
                    // NEW: Re-enable display keeping
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    wakeLock?.let {
                        if (!it.isHeld) it.acquire()
                    }

                    // Show everything
                    cursorView.setForceHidden(false, useGentleRedraw = true)
                    widgetContainer.setDisplayState(state)
                    if (::dataSyncCoordinator.isInitialized) {
                        dataSyncCoordinator.onDisplayWake("display_wake")
                    }
                }
                WakeSleepManager.DisplayState.SLEEP -> {
                    // Hide cursor, show only pinned widgets - use gentle redraw
                    cursorView.setForceHidden(true, useGentleRedraw = true)
                    widgetContainer.setDisplayState(state)
                }
            }

            // Clear transition flag before final invalidation
            isTransitioningDisplayState = false

            // WidgetContainer emits the single transient repaint/binocular update for this
            // display transition after its visibility state has settled.
        }
        
        // Wire up callbacks for checking pinned widgets
        // (These will be implemented in Task 2, for now return false)
        wakeSleepManager.hasPinnedWidgets = {
            widgetContainer.hasPinnedWidgets()
        }
        
//        wakeSleepManager.hasVisibleContent = {
//            // Returns true if there's any visible content on screen
//            wakeSleepManager.state != WakeSleepManager.DisplayState.OFF
//        }
        
        Log.d(TAG, "WakeSleepManager initialized")
    }

    /**
     * Initialize and configure the head-up wake manager.
     * This enables hands-free screen wake by tilting the head up.
     */
    private fun setupHeadUpWakeManager() {
        headUpWakeManager = HeadUpWakeManager(this)

        // Restore saved settings
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        headUpWakeManager.headUpHoldTimeMs = prefs.getLong(
            KEY_HEAD_UP_TIME,
            HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS
        )
        headUpWakeManager.wakeDurationMs = prefs.getLong(
            KEY_WAKE_DURATION,
            HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS
        )
        headUpWakeManager.pitchChangeThreshold = prefs.getFloat(
            KEY_ANGLE_THRESHOLD,
            HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD
        )
        headUpWakeManager.isHeadsUpGestureEnabled = prefs.getBoolean(
            KEY_HEADSUP_ENABLED,
            true
        )

        headUpWakeManager.wakeRequestListener = object : WakeRequestListener {
            override fun onWakeRequested() {
                Log.d(TAG, "Head-up wake requested (activity in foreground, waking from internal OFF state)")
                if (::wakeSleepManager.isInitialized) {
                    when (wakeSleepManager.state) {
                        WakeSleepManager.DisplayState.OFF,
                        WakeSleepManager.DisplayState.SLEEP -> {
                            isHeadUpWakeSession = true
                            val wakeDurationMs = headUpWakeManager.wakeDurationMs
                            Log.d(TAG, "Starting head-up wake session with duration ${wakeDurationMs}ms")
                            startHeadUpWakeAutoFinishTimer(wakeDurationMs)
                            wakeSleepManager.transitionTo(WakeSleepManager.DisplayState.WAKE)
                            widgetContainer.onHeadUpWake()
                        }
                        WakeSleepManager.DisplayState.WAKE -> Unit
                    }
                }
            }

            override fun onSleepRequested() {
                Log.d(TAG, "Head-up wake onSleepRequested callback (should be handled by auto-finish timer)")
            }
        }

        // Don't enable here - onResume will enable it, onPause will disable it
        // This ensures proper coordination with the service's head-up detection

        Log.d(TAG, "HeadUpWakeManager initialized (headUpTime=${headUpWakeManager.headUpHoldTimeMs}ms, wakeDuration=${headUpWakeManager.wakeDurationMs}ms, angle=${headUpWakeManager.pitchChangeThreshold}°, enabled=${headUpWakeManager.isHeadsUpGestureEnabled})")
    }

    /**
     * Initialize and configure the 3DOF (Three Degrees of Freedom) manager.
     * 3DOF enables world-locked content mode where UI stays fixed in virtual space
     * while the user looks around with head movements.
     */
    private fun setup3DofManager() {
        threeDofManager = ThreeDofManager(this)

        // Set screen dimensions for offset calculations
        threeDofManager.setScreenDimensions(
            DisplayConfig.EYE_WIDTH.toFloat(),
            DisplayConfig.EYE_HEIGHT.toFloat()
        )

        threeDofManager.observer = object : ThreeDofObserver {
            override fun onModeChanged(enabled: Boolean) {
                Log.d(TAG, "3DOF mode changed: enabled=$enabled")
                widgetContainer.set3DofEnabled(enabled)
            }

            override fun onOrientationChanged(offsetX: Float, offsetY: Float) {
                widgetContainer.set3DofOffset(offsetX, offsetY)
            }

            override fun onRollChanged(rollDegrees: Float) {
                widgetContainer.set3DofRoll(rollDegrees)
            }

            override fun onTransitionProgress(progress: Float) {
                widgetContainer.set3DofTransitionProgress(progress)
            }
        }

        Log.d(TAG, "ThreeDofManager initialized")
    }

    /**
     * Initialize the tap gesture detector for single/double/triple tap detection.
     * This replaces the simple double-tap detection to support triple-tap for 3DOF toggle.
     */
    private fun setupTapGestureDetector() {
        tapGestureDetector = TapGestureDetector()

        tapGestureDetector.listener = object : TapGestureListener {
            override fun onSingleTap(x: Float, y: Float) {
                inputCoordinator.handleTapAtCoordinates(x, y)
            }

            override fun onDoubleTap(x: Float, y: Float) {
                inputCoordinator.handleDoubleTapAtCoordinates(x, y)
            }

            override fun onTripleTap(x: Float, y: Float) {
                handleTripleTap()
            }
        }

        Log.d(TAG, "TapGestureDetector initialized")
    }

    /**
     * Handle triple tap - toggles 3DOF mode.
     */
    private fun handleTripleTap() {
        Log.d(TAG, "Triple tap detected - toggling 3DOF mode")
        inputCoordinator.flashCursor()
        threeDofManager.toggle3Dof()
    }

    /**
     * Notify wake/sleep manager of user activity.
     * This resets the sleep timer when the user interacts.
     */
    private fun notifyUserActivity() {
        wakeDisplayFromUserAction("user_activity")

        if (::wakeSleepManager.isInitialized) {
            wakeSleepManager.onUserActivity()
        }

        // If this was a head-up wake session, convert it to a normal session
        // so the screen stays on after user interaction
        cancelHeadUpWakeSession()
    }

    private fun wakeDisplayFromUserAction(reason: String) {
        if (!::wakeSleepManager.isInitialized) return

        if (wakeSleepManager.state != WakeSleepManager.DisplayState.WAKE) {
            Log.d(TAG, "User action waking display from ${wakeSleepManager.state} ($reason)")
            wakeSleepManager.transitionTo(WakeSleepManager.DisplayState.WAKE)
            return
        }

        ensureVisibleWakeState(reason)
    }

    private fun ensureVisibleWakeState(reason: String) {
        if (!::widgetContainer.isInitialized || !::cursorView.isInitialized) return

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock?.let {
            if (!it.isHeld) it.acquire()
        }

        val containerWasNotAwake = !widgetContainer.isDisplayAwake()
        cursorView.setForceHidden(false, useGentleRedraw = true)
        widgetContainer.setDisplayState(WakeSleepManager.DisplayState.WAKE)

        if (containerWasNotAwake) {
            Log.d(TAG, "Recovered visible wake state after stale internal mask ($reason)")
        }

        brightnessController.applyEffectiveWindowBrightness()
    }
    
    private fun saveWidgetStateDebounced() {
        persistenceManager.saveDebounced()
    }
    
    /**
     * Save current widget state to persistent storage.
     */
    private fun saveWidgetState() {
        persistenceManager.saveNow()
        //Log.d(TAG, "Saved ${textWidgetStates.size} widgets")
    }
    
    /**
     * Restore widget state from persistent storage.
     *
     * On first run (no saved state): the built-in Standard layout is created by WidgetContainer.
     * On returning user (saved state exists): Restore currently open widgets and keep the
     * last-closed widget templates available so reopening uses the previous bounds/config.
     *
     * @param state The pre-loaded persisted state (loaded before layout to set isReturningUser flag)
     */
    private fun restoreWidgetState(state: WidgetDocument.PersistedDashboard) {
        Log.d(TAG, "Restoring widget dashboard: isFirstRun=${state.isFirstRun}, widgets=${state.widgets.size}, templates=${state.closedTemplates.size}")

        if (state.isFirstRun) {
            return
        }

        val delivered = state.activeLayoutName
            ?.takeIf(BuiltInLayouts::isDelivered)
            ?.let { BuiltInLayouts.buildDashboard(it, widgetContainer.width.toFloat(), widgetContainer.height.toFloat()) }
        widgetContainer.applyPersistedDashboardReplacingCurrent(delivered ?: state)
    }
    
    /**
     * Handle key press from phone keyboard.
     */
    private fun handleKeyFromPhone(key: String) {
        Log.d(TAG, "Received key input from phone: chars=${key.length}")
        userInputActivity.onUserInput(UserInputKind.KEYBOARD)
        widgetContainer.onKeyPress(key, userInitiated = true)
    }

    /**
     * Get the current time formatted as "HH:mm".
     */
    private fun getCurrentTime(): String {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.ENGLISH)
        return timeFormat.format(Date())
    }
    
    /**
     * Get the current date formatted as "Wed 24 Jan 2026" (weekday + day + month + year).
     */
    private fun getCurrentDate(): String {
        val dateFormat = SimpleDateFormat("EEE dd MMM yyyy", Locale.ENGLISH)
        return dateFormat.format(Date())
    }
    
    /**
     * Get the current glasses battery level.
     */
    private fun getGlassesBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                (level * 100 / scale)
            } else {
                -1
            }
        } ?: -1
    }

    private fun registerBatteryStateReceiver() {
        updateChargingStateFromIntent(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

        batteryStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateChargingStateFromIntent(intent)
            }
        }

        registerReceiver(batteryStateReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun registerScreenStateReceiver() {
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    handlePhysicalScreenOn("screen_on_broadcast")
                }
            }
        }

        registerReceiver(screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun handlePhysicalScreenOn(reason: String) {
        if (!::wakeSleepManager.isInitialized) return

        Log.d(TAG, "Physical screen-on detected ($reason) in state ${wakeSleepManager.state}")
        wakeDisplayFromUserAction(reason)
    }

    private fun isChargingFromIntent(intent: Intent?): Boolean {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
    }

    private fun updateChargingStateFromIntent(intent: Intent?) {
        val charging = isChargingFromIntent(intent)

        binocularView.setChargingState(charging)

        val changed = brightnessController.setCharging(charging)

        if (changed) {
            Log.d(TAG, "Charging state changed: charging=$charging")
        }
    }

    private fun handleDebugIntent(intent: Intent?) {
        val isDebuggable =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable || intent?.action != ACTION_DEBUG_CLEAR_WEBVIEW) return

        val profileSummary = binocularView.getRenderProfileSummary()
        Log.d(TAG, "Debug clear WebView requested, binocularProfile=$profileSummary")
        widgetContainer.clearDebugWebViewData(profileSummary)
    }
    
    /**
     * Start periodic glasses battery monitoring.
     */
    private fun startGlassesBatteryMonitoring() {
        glassesBatteryUpdateRunnable = object : Runnable {
            override fun run() {
                // Update glasses battery in status bar
                val currentStatusBar = widgetContainer.statusBarWidget
                if (currentStatusBar != null) {
                    widgetContainer.updateStatusBar(
                        isPhoneConnected = currentStatusBar.isPhoneConnected,
                        phoneBattery = currentStatusBar.phoneBattery,
                        glassesBattery = getGlassesBatteryLevel(),
                        time = currentStatusBar.timeString,
                        date = currentStatusBar.dateString
                    )
                }
                handler.postDelayed(this, BATTERY_UPDATE_INTERVAL_MS)
            }
        }
        handler.post(glassesBatteryUpdateRunnable!!)
    }
    
    /**
     * Start periodic time updates.
     */
    private fun startTimeUpdates() {
        timeUpdateRunnable = object : Runnable {
            override fun run() {
                // Update time in status bar
                val currentStatusBar = widgetContainer.statusBarWidget
                if (currentStatusBar != null) {
                    widgetContainer.updateStatusBar(
                        isPhoneConnected = currentStatusBar.isPhoneConnected,
                        phoneBattery = currentStatusBar.phoneBattery,
                        glassesBattery = currentStatusBar.glassesBattery,
                        time = getCurrentTime(),
                        date = getCurrentDate()
                    )
                }
                handler.postDelayed(this, TIME_UPDATE_INTERVAL_MS)
            }
        }
        handler.post(timeUpdateRunnable!!)
    }
    
    /**
     * Update the status bar widget.
     */
    private fun updateStatusBar(
        isPhoneConnected: Boolean,
        phoneBattery: Int
    ) {
        widgetContainer.updateStatusBar(
            isPhoneConnected = isPhoneConnected,
            phoneBattery = phoneBattery,
            glassesBattery = getGlassesBatteryLevel(),
            time = getCurrentTime(),
            date = getCurrentDate()
        )
    }

    private fun captureAssistantUiContextOnDemand(): CompletableFuture<AssistantUiContext> {
        val result = CompletableFuture<AssistantUiContext>()
        val capture = Runnable {
            runCatching { widgetContainer.assistantUiContext() }
                .onSuccess(result::complete)
                .onFailure(result::completeExceptionally)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) capture.run() else runOnUiThread(capture)
        return result
    }

    override fun onResume() {
        super.onResume()
        binocularView.onResume()

        // Re-hide system UI in case it came back
        hideSystemUI()

        // Also hide system keyboard
        hideSystemKeyboard()
        brightnessController.onResume()

        if (openFileBrowserAfterAllFilesGrant &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
        ) {
            openFileBrowserAfterAllFilesGrant = false
            widgetContainer.post { openFileBrowser() }
        }

        // Notify service that activity is in foreground
        // Service will disable its head-up detection (we handle it here)
        GlassesService.notifyActivityResumed(this)

        // Enable the activity's own head-up wake manager
        // (for detecting head-up when in app's internal SLEEP/OFF state)
        if (::headUpWakeManager.isInitialized) {
            headUpWakeManager.enable()
        }

        // Check for head-up wake via static flags (intent extras don't work reliably with full-screen intents)
        if (GlassesService.pendingHeadUpWake) {
            val wakeDurationMs = GlassesService.pendingHeadUpWakeDuration
            Log.d(TAG, "Activity resumed via head-up wake (from static flags, wakeDuration=${wakeDurationMs}ms)")

            // Clear the flags immediately
            GlassesService.pendingHeadUpWake = false

            // Mark this as a head-up wake session
            isHeadUpWakeSession = true

            // Start auto-finish timer
            startHeadUpWakeAutoFinishTimer(wakeDurationMs)

            // Ensure internal state wakes when resumed from service-triggered head-up wake.
            if (::wakeSleepManager.isInitialized &&
                wakeSleepManager.state != WakeSleepManager.DisplayState.WAKE
            ) {
                wakeSleepManager.transitionTo(WakeSleepManager.DisplayState.WAKE)
            }

            widgetContainer.onHeadUpWake()
            if (::dataSyncCoordinator.isInitialized) {
                dataSyncCoordinator.onDisplayWake("head_up_wake_resume")
            }
        }

        // Hardware action-button wake can resume the activity without an internal
        // WakeSleepManager transition. Let the coordinator refresh visible data.
        if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) {
            handlePhysicalScreenOn("activity_resume")
        }
        widgetContainer.onAppResumed()
        if (::dataSyncCoordinator.isInitialized) {
            dataSyncCoordinator.onAppResumed()
        }

        // Re-evaluate local speed pipeline on resume in case providers were toggled externally.
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            locationSpeedController.startLocationUpdates()
        }

        if (::googleAuthCoordinator.isInitialized) {
            googleAuthCoordinator.refreshStateSilently(this)
        }
    }

    /**
     * Start the auto-finish timer for head-up wake sessions.
     * When the timer expires, the activity will finish() to return to the glasses' lock screen.
     */
    private fun startHeadUpWakeAutoFinishTimer(wakeDurationMs: Long) {
        // Cancel any existing timer
        cancelHeadUpWakeAutoFinishTimer()

        Log.d(TAG, "Head-up wake auto-finish timer started for ${wakeDurationMs}ms")

        headUpWakeAutoFinishRunnable = Runnable {
            if (isHeadUpWakeSession) {
                Log.d(TAG, "Head-up wake timer expired - transitioning to OFF state (staying in foreground)")
                isHeadUpWakeSession = false

                // Instead of leaving the activity (which causes the launcher to force-stop us),
                // we stay in foreground but transition to the OFF display state.
                // This shows a black screen just like when the hardware button turns off the display,
                // but keeps our app alive so head-up motion can wake it again instantly.
                if (::wakeSleepManager.isInitialized) {
                    wakeSleepManager.transitionTo(WakeSleepManager.DisplayState.OFF)
                    Log.d(TAG, "Transitioned to OFF state - app stays in foreground with black screen")
                }
            }
        }
        handler.postDelayed(headUpWakeAutoFinishRunnable!!, wakeDurationMs)
    }

    /**
     * Cancel the head-up wake auto-finish timer.
     * Called when user interacts with the screen (to keep it open).
     */
    private fun cancelHeadUpWakeAutoFinishTimer() {
        headUpWakeAutoFinishRunnable?.let {
            handler.removeCallbacks(it)
            headUpWakeAutoFinishRunnable = null
        }
    }

    /**
     * Cancel head-up wake session - user has interacted, so keep screen on normally.
     * This converts a head-up wake session into a normal session.
     */
    private fun cancelHeadUpWakeSession() {
        if (isHeadUpWakeSession) {
            Log.d(TAG, "User activity detected - converting head-up wake session to normal session")
            isHeadUpWakeSession = false
            cancelHeadUpWakeAutoFinishTimer()
        }
    }

    override fun onPause() {
        super.onPause()
        binocularView.onPause()
        brightnessController.onPause()

        widgetContainer.onAppPaused()

        // Save widget state when app goes to background
        saveWidgetState()

        // Cancel head-up wake auto-finish timer (activity is already going to background)
        cancelHeadUpWakeAutoFinishTimer()
        isHeadUpWakeSession = false

        // Disable the activity's own head-up wake manager
        // The service will take over head-up detection when we're in background
        if (::headUpWakeManager.isInitialized) {
            headUpWakeManager.disable()
        }

        // Notify service that activity is going to background
        // Service will enable its head-up detection
        GlassesService.notifyActivityPaused(this)

    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
        // Note: Head-up wake is now handled via static flags in onResume()
        // onResume() will check GlassesService.pendingHeadUpWake after this returns
    }

    override fun onStop() {
        super.onStop()
        
        // Also save in onStop for extra safety
        saveWidgetState()
    }

    override fun onDestroy() {
        // Flush the last persistent mutation even when the activity is destroyed without a
        // preceding pause/stop callback.
        if (::persistenceManager.isInitialized) saveWidgetState()
        if (::voiceAssistantCoordinator.isInitialized) voiceAssistantCoordinator.release()
        if (::browserRoutingCoordinator.isInitialized) browserRoutingCoordinator.release()
        if (::assistantNetworkLease.isInitialized) assistantNetworkLease.close()
        super.onDestroy()
        if (::phoneConnectionCoordinator.isInitialized) {phoneConnectionCoordinator.release()}
        GlassesService.stop(this)
        binocularView.onDestroy()
        
        // Stop battery monitoring
        glassesBatteryUpdateRunnable?.let { handler.removeCallbacks(it) }
        batteryStateReceiver?.let { unregisterReceiver(it) }
        batteryStateReceiver = null
        screenStateReceiver?.let { unregisterReceiver(it) }
        screenStateReceiver = null

        // Stop time updates
        timeUpdateRunnable?.let { handler.removeCallbacks(it) }


        // Stop local speed/location updates
        locationSpeedController.stopLocationUpdates()

        // Stop brightness control updates
        brightnessController.onDestroy()

        // Cancel head-up wake auto-finish timer
        cancelHeadUpWakeAutoFinishTimer()
        
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
        
        // Clean up input coordinator callbacks
        if (::inputCoordinator.isInitialized) {
            inputCoordinator.release()
        }

        if (::dataSyncCoordinator.isInitialized) {
            dataSyncCoordinator.release()
        }
        
        // Clean up widget container resources
        widgetContainer.release()

        // Clean up wake/sleep manager
        if (::wakeSleepManager.isInitialized) {
            wakeSleepManager.release()
        }

        // Clean up head-up wake manager
        if (::headUpWakeManager.isInitialized) {
            headUpWakeManager.release()
        }

        // Clean up 3DOF manager
        if (::threeDofManager.isInitialized) {
            threeDofManager.release()
        }

        // Clean up tap gesture detector
        if (::tapGestureDetector.isInitialized) {
            tapGestureDetector.release()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        binocularView.onTrimMemory(level)
        widgetContainer.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binocularView.onLowMemory()
        widgetContainer.onLowMemory()
    }

    // ==================== Phone Data Handling ====================

    private fun handlePhonePointer(data: TransportMessage.Pointer) {
        inputCoordinator.handlePhoneData(data)
    }

    // ==================== Temple Trackpad Handling ====================

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
            // Also hide system keyboard when window gains focus
            hideSystemKeyboard()
        }
    }
    
    /**
     * Hide the system keyboard using InputMethodManager.
     * This is called in multiple places to ensure the keyboard never appears.
     */
    private fun hideSystemKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val currentFocus = currentFocus ?: window.decorView

        // Hide the keyboard - only use hideSoftInputFromWindow, NOT toggleSoftInput
        // toggleSoftInput can actually SHOW the keyboard if it's currently hidden!
        imm.hideSoftInputFromWindow(currentFocus.windowToken, 0)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        //Log.d(TAG, "dispatchGenericMotionEvent: action=${event.action} actionMasked=${event.actionMasked} source=${event.source} device=${event.device?.name} x=${event.x} y=${event.y}")
        return super.dispatchGenericMotionEvent(event)
    }
    
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        //Log.d(TAG, "dispatchTouchEvent: action=${event.action} source=${event.source} device=${event.device?.name} x=${event.x} y=${event.y}")
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return if (::inputCoordinator.isInitialized && inputCoordinator.onGenericMotionEvent(event)) {
            true
        } else {
            super.onGenericMotionEvent(event)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return if (::inputCoordinator.isInitialized && inputCoordinator.onTouchEvent(event)) {
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "Key down: $keyCode (${KeyEvent.keyCodeToString(keyCode)}) source=${event?.device?.sources}")

        // Note: The glasses' hardware action button controls the display at the OS level
        // and is completely independent of our app's internal wake/sleep state machine.
        // We do NOT intercept or handle the hardware action button in this app.

        // Only process key events if we're in WAKE state
        if (::wakeSleepManager.isInitialized && !wakeSleepManager.isAwake()) {
            Log.d(TAG, "Key event while display not awake - waking display")
            if (::userInputActivity.isInitialized) {
                userInputActivity.onUserInput(UserInputKind.KEYBOARD)
            }
            wakeDisplayFromUserAction("key_event")
            return true  // Consume but don't process
        }

        if (::userInputActivity.isInitialized) {
            userInputActivity.onUserInput(UserInputKind.KEYBOARD)
        }

        if (::widgetContainer.isInitialized && event != null && widgetContainer.handleKeyEvent(keyCode, event)) {
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                notifyUserActivity()
                inputCoordinator.handleTap()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ==================== Location Handling ====================









    // ==================== Permissions ====================

    private fun hasPermissions(): Boolean {
        // Return true only if mandatory Bluetooth permissions are granted.
        // Location permission is handled separately.

        val bluetoothGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }

        return bluetoothGranted
    }


    /**
     * Check if storage permission is granted for reading images.
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 6-12 uses READ_EXTERNAL_STORAGE
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }.also { granted ->
            Log.d(TAG, "hasStoragePermission check: granted=$granted, SDK=${Build.VERSION.SDK_INT}")
        }
    }

    /**
     * Request storage permission specifically for the file browser.
     * Uses a separate request code to handle the result appropriately.
     */
    private fun requestStoragePermission() {
        val permissionsList = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Android 13+ uses granular media permissions
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12 uses READ_EXTERNAL_STORAGE
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsList.isNotEmpty()) {
            Log.d(TAG, "Requesting storage permissions: $permissionsList")
            ActivityCompat.requestPermissions(this, permissionsList.toTypedArray(), REQUEST_STORAGE_PERMISSION)
        } else {
            // Permission already granted, open file browser
            Log.d(TAG, "Storage permission already granted, opening file browser")
            openFileBrowser()
        }
    }

    /**
     * Open the file browser widget.
     */
    private fun openFileBrowser() {
        widgetContainer.createFileBrowserWidget(
            filter = FileBrowserWidget.FileFilter.MEDIA,
            onFileSelected = { entry ->
                Log.d(TAG, "File selected: ${entry.absolutePath}, uri: ${entry.uri}")
                if (entry.extension == "pdf" || entry.mimeType == "application/pdf") {
                    val uri = entry.uri ?: android.net.Uri.fromFile(java.io.File(entry.absolutePath))
                    handlePdfFile(uri, entry.name)
                } else if (entry.extension in FileBrowserWidget.FileFilter.VIDEOS.extensions || entry.mimeType?.startsWith("video/") == true) {
                    val uri = entry.uri ?: android.net.Uri.fromFile(java.io.File(entry.absolutePath))
                    handleVideoFile(uri, entry.name)
                } else if (entry.uri != null) {
                    handleImageFile(entry.uri)
                } else {
                    handleImageFile(java.io.File(entry.absolutePath))
                }
            }
        )
    }

    private fun requestAllFilesAccessForBrowser() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestStoragePermission()
            return
        }
        openFileBrowserAfterAllFilesGrant = true
        val appSettingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        runCatching { startActivity(appSettingsIntent) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            .onFailure {
                openFileBrowserAfterAllFilesGrant = false
                Log.e(TAG, "Unable to open all-files access settings", it)
            }
    }

    private fun handleVideoFile(uri: android.net.Uri, displayName: String) {
        runOnUiThread {
            widgetContainer.createVideoWidget(uri, displayName)
        }
    }

    private fun handlePdfFile(uri: android.net.Uri, displayName: String) {
        runOnUiThread {
            widgetContainer.createPdfWidget(uri, displayName)
        }
    }

    private fun requestPermissions() {
        // Request any missing runtime permissions needed by the app and optional widgets.
        
        val permissionsList = mutableListOf<String>()
        
        // Mandatory Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.BLUETOOTH)
                permissionsList.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }

        // Location permissions: always request if missing so user gets standard OS prompt.
        val fineGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!coarseGranted) {
            permissionsList.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Storage permissions for file browser
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses granular media permissions
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12 uses READ_EXTERNAL_STORAGE
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsList.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            // If list is empty, all requested runtime permissions are already granted.
            startClient()
            if (!locationSpeedController.hasAnyLocationPermission()) {
                locationSpeedController.onLocationPermissionDenied()
                speedLog { "Startup without location permission -> speed disabled" }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Delegate to FilePicker if this is a file picker request
        filePicker.handleActivityResult(requestCode, resultCode, data)
    }

    /**
     * Handle file selection from the file picker.
     * Extensible to support different file types (images, PDFs, etc.)
     */
    /**
     * Process an image file from the given URI.
     * Copies URI content to persistent app storage to get a durable file path for BitmapFactory.
     */
    private fun handleImageFile(uri: android.net.Uri) {
        Thread {
            try {
                // Determine the file extension from the content type
                val mimeType = contentResolver.getType(uri)
                val extension = when (mimeType) {
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    "image/bmp" -> "bmp"
                    else -> "jpg"
                }

                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open image URI: $uri")
                val file = saveImageToPersistentStorage(inputStream, "image_${System.currentTimeMillis()}.$extension")

                Log.d(TAG, "Image saved to ${file.absolutePath}")

                runOnUiThread {
                    widgetContainer.createImageWidget(file.absolutePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from URI: $uri", e)
            }
        }.start()
    }

    /**
     * Process an image file directly from the file system (used by FileBrowserWidget).
     */
    private fun handleImageFile(file: java.io.File) {
        Thread {
            try {
                Log.d(TAG, "Loading image from ${file.absolutePath}")
                runOnUiThread {
                    widgetContainer.createImageWidget(file.absolutePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image file", e)
            }
        }.start()
    }

    /**
     * Handle an image received from the phone over Bluetooth.
     * Saves the image to persistent app storage and opens it in an ImageWidget.
     */
    private fun handleReceivedImage(imageData: ByteArray, fileName: String) {
        Thread {
            try {
                // Create a unique file name to avoid conflicts
                val timestamp = System.currentTimeMillis()
                val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val imageFile = saveImageToPersistentStorage(imageData, "${timestamp}_$safeFileName")

                // Write image data to file
                Log.d(TAG, "Saved received image to ${imageFile.absolutePath}")

                // Create ImageWidget on UI thread
                runOnUiThread {
                    widgetContainer.createImageWidget(imageFile.absolutePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling received image: ${e.message}", e)
            }
        }.start()
    }

    private fun saveImageToPersistentStorage(
        inputStream: java.io.InputStream,
        fileName: String
    ): java.io.File {
        val outputFile = ImageWidgetStorage.createManagedFile(this, fileName)
        inputStream.use { input ->
            java.io.FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        return outputFile
    }

    private fun saveImageToPersistentStorage(
        imageData: ByteArray,
        fileName: String
    ): java.io.File {
        val outputFile = ImageWidgetStorage.createManagedFile(this, fileName)
        outputFile.writeBytes(imageData)
        return outputFile
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                // Check Bluetooth permissions (essential)
                var bluetoothGranted = hasPermissions()
                // Check location permission (optional)
                var locationGranted = locationSpeedController.hasAnyLocationPermission()

                for (i in permissions.indices) {
                    if (permissions[i] == Manifest.permission.BLUETOOTH_CONNECT ||
                        permissions[i] == Manifest.permission.BLUETOOTH ||
                        permissions[i] == Manifest.permission.BLUETOOTH_ADMIN) {
                        if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                            bluetoothGranted = true
                        }
                    }
                    if (permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION ||
                        permissions[i] == Manifest.permission.ACCESS_COARSE_LOCATION) {
                        if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                            locationGranted = true
                        }
                    }
                }

                // If no location permission was included in this request, use current runtime state.
                if (!permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    !permissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    locationGranted = locationSpeedController.hasAnyLocationPermission()
                }

                if (bluetoothGranted) {
                    startClient()
                }

                if (locationGranted) {
                    locationSpeedController.startLocationUpdates()
                } else {
                    locationSpeedController.onLocationPermissionDenied()
                }

            }

            REQUEST_STORAGE_PERMISSION -> {
                // Check if storage permission was granted
                var storageGranted = false

                for (i in permissions.indices) {
                    val permission = permissions[i]
                    if (permission == Manifest.permission.READ_MEDIA_IMAGES ||
                        permission == Manifest.permission.READ_MEDIA_VIDEO ||
                        permission == Manifest.permission.READ_EXTERNAL_STORAGE) {
                        if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                            storageGranted = true
                            Log.d(TAG, "Storage permission granted: $permission")
                        } else {
                            Log.d(TAG, "Storage permission DENIED: $permission")
                        }
                    }
                }

                if (storageGranted) {
                    // Permission granted, open the file browser
                    Log.d(TAG, "Storage permission result: granted, opening file browser")
                    openFileBrowser()
                } else {
                    // Permission denied - still open file browser, it will show the permission message
                    Log.d(TAG, "Storage permission result: denied, opening file browser anyway (will show permission message)")
                    openFileBrowser()
                }
            }
            REQUEST_ASSISTANT_AUDIO_PERMISSION -> {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (::voiceAssistantCoordinator.isInitialized) voiceAssistantCoordinator.onPermissionResult(granted)
            }
        }
    }

    private fun startClient() {
        Log.d(TAG, "startClient called, isPhoneMode=$isPhoneMode")
        if (isPhoneMode && ::phoneConnectionCoordinator.isInitialized) {
            phoneConnectionCoordinator.start()
        }
    }
}
