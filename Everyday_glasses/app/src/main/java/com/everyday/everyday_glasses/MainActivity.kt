package com.everyday.everyday_glasses

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pDevice
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
import com.everyday.shared.sync.LocationFix
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.sync.usableSpeedMps
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
import kotlin.math.abs
import kotlin.math.ln

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

    private var rfcommClient: RfcommClient? = null
    
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



    private var latestPhoneBatteryPercent: Int = -1
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // Glasses battery update
    private var glassesBatteryUpdateRunnable: Runnable? = null
    private var batteryStateReceiver: BroadcastReceiver? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    
    // Time update
    private var timeUpdateRunnable: Runnable? = null
    
    // Auto-save timer

    // Pending widget restoration (waits for views to be ready)
    private var pendingWidgetRestore = false

    // Local speed pipeline state
    private var speedLocationManager: LocationManager? = null
    private var speedLocationListener: LocationListener? = null
    private var previousSpeedLocation: Location? = null
    private var lastSpeedLocationUpdateElapsedMs = 0L
    private var speedFallbackRunnable: Runnable? = null
    private var speedFallbackProviders: List<String> = emptyList()
    private var smoothedSpeedKmh = 0f
    private var lastReliableSpeedSampleElapsedMs = 0L
    private var speedVisibilityThresholdKmh = SPEED_THRESHOLD_KMH
    private var isSpeedMoving = false
    private var lastSpeedQualityOk = false
    private var lastSpeedLogMs = 0L
    private var lastMissingPhoneSpeedLogMs = 0L

    private var wifiDirectClient: WifiDirectClient? = null
    private var pendingWifiDirectMirrorIp: String? = null

    // File picker for opening images, PDFs, etc.
    private lateinit var filePicker: FilePicker
    private var openFileBrowserAfterAllFilesGrant = false

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
        private const val KEY_BRIGHTNESS = "screen_brightness"
        private const val KEY_ADAPTIVE_BRIGHTNESS = "adaptive_brightness_enabled"
        private const val KEY_HEAD_UP_TIME = "head_up_time_ms"
        private const val KEY_WAKE_DURATION = "wake_duration_ms"
        private const val KEY_ANGLE_THRESHOLD = "angle_threshold_degrees"
        private const val KEY_HEADSUP_ENABLED = "headsup_enabled"
        private const val KEY_SPEED_VISIBILITY_THRESHOLD = "speed_visibility_threshold_kmh"
        private const val KEY_SMART_ALIGNMENT = "smart_alignment_enabled"

        private const val DEFAULT_SMART_ALIGNMENT = true
        // Keep media dimming visible; the only intentional full black mask is DisplayState.OFF.


        // Speedometer behavior (source of truth in km/h)
        private const val SPEED_THRESHOLD_KMH = 1f
        private const val SPEED_THRESHOLD_HYSTERESIS_KMH = 0.1f
        private const val SPEED_SMOOTHING_ALPHA = 0.35f
        private const val SPEED_RESUME_BOOTSTRAP_ALPHA = 0.85f
        private const val SPEED_RESUME_BOOTSTRAP_GAP_MS = 4_000L
        private const val SPEED_LOCATION_MIN_TIME_MS = 1000L
        private const val SPEED_LOCATION_MIN_DISTANCE_M = 0f
        private const val MAX_ACCEPTABLE_SPEED_AGE_MS = 10_000L
        private const val MAX_ACCEPTABLE_ACCURACY_M = 60f
        private const val MAX_ACCEPTABLE_COARSE_ACCURACY_M = 250f
        private const val MIN_SPEED_ESTIMATE_INTERVAL_MS = 500L
        private const val MAX_SPEED_ESTIMATE_INTERVAL_MS = 12_000L
        private const val MAX_REASONABLE_SPEED_KMH = 180f
        private const val SPEED_FALLBACK_POLL_INTERVAL_MS = 3_000L
        private const val SPEED_STALE_UPDATE_WINDOW_MS = 6_000L

        // Speedometer logging gate (flip to false to silence)
        private const val SPEEDO_LOG = true
        private const val SPEEDO_LOG_TAG = "Speedometer"
        private const val SPEED_LOG_INTERVAL_MS = 5000L
        private const val ACTION_DEBUG_CLEAR_WEBVIEW =
            "com.everyday.everyday_glasses.action.DEBUG_CLEAR_WEBVIEW"
    }

    private inline fun speedLog(msg: () -> String) {
        if (SPEEDO_LOG) {
            Log.d(SPEEDO_LOG_TAG, msg())
        }
    }

    private fun smoothSpeedReading(rawKmh: Float, qualityOk: Boolean, source: String): Float {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val safeRawKmh = rawKmh.coerceIn(0f, MAX_REASONABLE_SPEED_KMH)
        val previousSmoothedKmh = smoothedSpeedKmh
        val elapsedSinceReliableSampleMs = if (lastReliableSpeedSampleElapsedMs > 0L) {
            nowElapsed - lastReliableSpeedSampleElapsedMs
        } else {
            Long.MAX_VALUE
        }
        val shouldBootstrapResume = qualityOk && (
                !lastSpeedQualityOk ||
                        lastReliableSpeedSampleElapsedMs == 0L ||
                        elapsedSinceReliableSampleMs >= SPEED_RESUME_BOOTSTRAP_GAP_MS
                )
        val alpha = if (shouldBootstrapResume) {
            SPEED_RESUME_BOOTSTRAP_ALPHA
        } else {
            SPEED_SMOOTHING_ALPHA
        }

        smoothedSpeedKmh = (alpha * safeRawKmh) + ((1f - alpha) * previousSmoothedKmh)
        if (smoothedSpeedKmh < 0.05f) smoothedSpeedKmh = 0f

        if (qualityOk) {
            lastReliableSpeedSampleElapsedMs = nowElapsed
        }

        if (shouldBootstrapResume) {
            speedLog {
                "Speed resume bootstrap: source=$source " +
                        "raw=${String.format(Locale.ENGLISH, "%.2f", safeRawKmh)}km/h " +
                        "prev=${String.format(Locale.ENGLISH, "%.2f", previousSmoothedKmh)}km/h " +
                        "next=${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)}km/h " +
                        "gapMs=${if (elapsedSinceReliableSampleMs == Long.MAX_VALUE) "first" else elapsedSinceReliableSampleMs}"
            }
        }

        return smoothedSpeedKmh
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
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }

        // Keep screen on while app is running
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Also acquire a wake lock to prevent display sleep
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
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

        // Create RFCOMM client
        rfcommClient = RfcommClient(this).apply {
            transportConnectionListener = object : TransportConnectionListener {
                override fun onStatusChanged(status: String) {
                    runOnUiThread {
                        Log.d(TAG, "Connection status: $status")
                        notifyTransientBinocularChange()
                    }
                }

                override fun onConnectionChanged(connected: Boolean) {
                    runOnUiThread {
                        widgetContainer.onPhoneRouteConnectionChanged(connected)
                        if (::voiceAssistantCoordinator.isInitialized) {
                            if (connected) rfcommClient?.sendAssistantStatus(voiceAssistantCoordinator.currentStatus())
                            else voiceAssistantCoordinator.onPhoneDisconnected()
                        }
                        if (::dataSyncCoordinator.isInitialized) {
                            dataSyncCoordinator.onPhoneConnectionChanged(connected)
                        }
                        if (!connected && isPhoneMode) {
                            latestPhoneBatteryPercent = -1
                            // Update status bar to show disconnected
                            updateStatusBar(
                                isPhoneConnected = false,
                                phoneBattery = -1,
                            )
                            // Unfocus any widgets on disconnect
                            widgetContainer.unfocusAll()
                            // Disable mirroring on disconnect
                            widgetContainer.setMirroringEnabled(false)
                            widgetContainer.onSubtitlePhoneDisconnected()
                        } else if (connected) {
                            updateStatusBar(
                                isPhoneConnected = true,
                                phoneBattery = latestPhoneBatteryPercent,
                            )
                            widgetContainer.onPhoneReconnected()
                        }
                    }
                }
            }

            remoteInputListener = object : RemoteInputListener {
                override fun onPointerReceived(data: TransportMessage.Pointer) {
                    runOnUiThread {
                        if (isPhoneMode) {
                            handlePhonePointer(data)
                        }
                    }
                }

                override fun onKeyReceived(key: String) {
                    runOnUiThread {
                        handleKeyFromPhone(key)
                    }
                }

                override fun onLongPressWithClipboard(clipboardContent: String?) {
                    runOnUiThread {
                        userInputActivity.onUserInput(UserInputKind.LONG_PRESS)
                        Log.d(TAG, "Phone clipboard received: available=${clipboardContent != null}")
                        // Store phone clipboard content for paste operations
                        // Note: Text selection is now handled by glasses-side long-press detection
                        // Double-tap on focused text shows the text edit menu
                        widgetContainer.setPhoneClipboard(clipboardContent)
                    }
                }

                override fun onClipboardReceived(clipboardContent: String) {
                    runOnUiThread {
                        Log.d(TAG, "Phone clipboard updated: chars=${clipboardContent.length}")
                        // Store phone clipboard content for paste operations
                        widgetContainer.setPhoneClipboard(clipboardContent)
                    }
                }
            }

            remoteContentListener = object : RemoteContentListener {
                override fun onDeviceStatusReceived(status: TransportMessage.DeviceStatus) {
                    status.phoneBatteryPercent?.let { battery ->
                        runOnUiThread {
                            latestPhoneBatteryPercent = battery
                            updateStatusBar(isPhoneConnected = true, phoneBattery = battery)
                        }
                    }
                }

                override fun onImageReceived(imageData: ByteArray, fileName: String) {
                    runOnUiThread {
                        Log.d(TAG, "Image received from phone: $fileName (${imageData.size} bytes)")
                        handleReceivedImage(imageData, fileName)
                    }
                }

                override fun onOpenBrowserRequested(address: String) {
                    runOnUiThread {
                        Log.d(TAG, "Open browser requested from phone: ${address.take(80)}")
                        widgetContainer.executeOpenUrlCommand(address)
                    }
                }
            }

            mirrorSessionListener = object : MirrorSessionListener {
                override fun onMirrorStateChanged(enabled: Boolean, ip: String?, isWifiDirect: Boolean) {
                    runOnUiThread {
                        Log.d(TAG, "Mirror state changed: enabled=$enabled, ip=$ip, wifiDirect=$isWifiDirect")

                        if (enabled && isWifiDirect) {
                            // Phone told us this is WiFi Direct - we need to join the P2P group first
                            if (wifiDirectClient?.isConnected != true) {
                                Log.d(TAG, "WiFi Direct mode, starting peer discovery...")
                                pendingWifiDirectMirrorIp = ip  // Save IP for when we connect
                                wifiDirectClient?.discoverPeers()
                                // DON'T enable mirroring yet - wait for P2P connection
                                return@runOnUiThread
                            } else {
                                Log.d(TAG, "Already connected to WiFi Direct, proceeding with mirroring")
                            }
                        }

                        widgetContainer.setMirroringEnabled(enabled, ip)
                    }
                }
            }

            remoteAuthListener = object : RemoteAuthListener {
                override fun onGoogleAuthStateReceived(
                    status: GooglePhoneAuthStatus,
                    account: GoogleAccountSummary?,
                    detail: String?
                ) {
                    runOnUiThread {
                        if (status == GooglePhoneAuthStatus.SIGNED_OUT) {
                            dataSyncCoordinator.clearCalendarSnapshot()
                        }
                        googleAuthCoordinator.onPhoneAuthStateChanged(status, account, detail)
                    }
                }
            }

            syncMessageListener = object : SyncMessageListener {
                override fun onSyncSnapshotReceived(snapshot: SyncSnapshot) {
                    runOnUiThread {
                        dataSyncCoordinator.onSyncSnapshotReceived(snapshot)
                    }
                }

                override fun onSyncErrorReceived(error: SyncError) {
                    runOnUiThread {
                        dataSyncCoordinator.onSyncErrorReceived(error)
                    }
                }
            }

            routeMessageListener = object : RouteMessageListener {
                override fun onRouteResultReceived(result: RouteTransportResult) {
                    runOnUiThread { widgetContainer.onPhoneRouteResult(result) }
                }

                override fun onRouteErrorReceived(error: RouteTransportError) {
                    runOnUiThread { widgetContainer.onPhoneRouteError(error) }
                }
            }

            destinationSearchMessageListener = object : DestinationSearchMessageListener {
                override fun onDestinationSearchResultReceived(result: DestinationSearchResult) {
                    runOnUiThread { widgetContainer.onPhoneDestinationSearchResult(result) }
                }

                override fun onDestinationSearchErrorReceived(error: DestinationSearchError) {
                    runOnUiThread { widgetContainer.onPhoneDestinationSearchError(error) }
                }
            }

            subtitleMessageListener = object : SubtitleMessageListener {
                override fun onSubtitleStatusReceived(status: SubtitleStatus) {
                    runOnUiThread {
                        widgetContainer.applySubtitleStatus(status)
                    }
                }

                override fun onSubtitleTranscriptReceived(transcript: SubtitleTranscript) {
                    runOnUiThread {
                        widgetContainer.applySubtitleTranscript(transcript)
                    }
                }
            }

            assistantControlListener = object : AssistantControlListener {
                override fun onAssistantControlReceived(control: AssistantControl) {
                    if (::voiceAssistantCoordinator.isInitialized) voiceAssistantCoordinator.handleControl(control)
                }
            }
        }
        widgetContainer.configurePhoneRouteTransport(rfcommClient)

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
                        rfcommClient?.sendAssistantStatus(status)
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

        // On-demand inspection is marshalled to the UI thread; ordinary context capture
        // remains the small snapshot taken when the voice request is submitted.

        widgetContainer.configureAssistantSettings(
            voiceAssistantCoordinator::configureApiKey,
            voiceAssistantCoordinator::removeApiKey,
            voiceAssistantCoordinator::selectModel
        )
        voiceAssistantCoordinator.initialize()

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
        if (hasPermissions() && hasAnyLocationPermission()) {
            startClient()
        } else {
            requestPermissions()
        }

        // Start location updates
        setupLocationUpdates()
        GlassesService.start(this)
        initializeWifiDirect()

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
                rfcommClient?.sendTextFieldFocus(focused)
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
                rfcommClient?.sendMirrorRequest(start)
            }

            override fun requestSubtitleControl(control: SubtitleControl) {
                Log.d(TAG, "Subtitle control from widget: action=${control.action.wireName}")
                if (rfcommClient?.sendSubtitleControl(control) != true) {
                    widgetContainer.onSubtitlePhoneDisconnected()
                }
            }

            override fun requestBrowserKeyboard(show: Boolean) {
                Log.d(TAG, "Browser keyboard request: show=$show")
                rfcommClient?.sendTextFieldFocus(show)
            }

            override fun requestClipboard() {
                Log.d(TAG, "Clipboard request from widget - asking phone for current clipboard")
                rfcommClient?.requestClipboard()
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
            override fun applySpeedVisibilityThreshold(threshold: Float) {
                speedVisibilityThresholdKmh = threshold.coerceIn(0f, 1f)
                saveSpeedVisibilityThreshold(speedVisibilityThresholdKmh)

                val previousMoving = isSpeedMoving
                isSpeedMoving = computeSpeedMovingState(smoothedSpeedKmh, lastSpeedQualityOk, previousMoving)
                widgetContainer.updateSpeedData(smoothedSpeedKmh, lastSpeedQualityOk, isSpeedMoving)

                if (previousMoving != isSpeedMoving) {
                    val transition = if (isSpeedMoving) "idle -> moving" else "moving -> idle"
                    speedLog {
                        "Movement transition: $transition at " +
                                "${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)} km/h " +
                                "(threshold=${formatSpeedThresholdForLog()})"
                    }
                }
                speedLog { "Speed visibility threshold changed to ${formatSpeedThresholdForLog()}" }
            }

            override fun applyBrightness(brightness: Float) {
                brightnessController.setBrightness(brightness)
            }

            override fun applyAdaptiveBrightness(enabled: Boolean) {
                brightnessController.setAdaptiveBrightness(enabled)
            }

            override fun applyMediaBrightnessCap(active: Boolean) {
                brightnessController.setMediaBrightnessCap(active)
            }

            override fun applyBrowserMinimumBrightness(active: Boolean) {
                brightnessController.setBrowserMinimumBrightness(active)
            }

            override fun applyLayoutSwitchBrightness() {
                brightnessController.beginLayoutSwitchBrightness()
            }

            override fun applyHeadUpTime(durationMs: Long) {
                if (::headUpWakeManager.isInitialized) headUpWakeManager.headUpHoldTimeMs = durationMs
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
        speedVisibilityThresholdKmh = loadSpeedVisibilityThreshold()
        widgetContainer.setSpeedVisibilityThreshold(speedVisibilityThresholdKmh)
        speedLog { "Loaded speed visibility threshold ${formatSpeedThresholdForLog()}" }
    }

    private fun setupGoogleAuth() {
        googleAuthCoordinator = GoogleAuthCoordinator(
            clientId = getString(R.string.google_device_client_id),
            store = GoogleAuthPreferencesStore(this),
            networkStatusProvider = DefaultNetworkStatusProvider(this),
            phoneFallbackBridge = object : GooglePhoneFallbackBridge {
                override fun requestPhoneAuthorization() {
                    rfcommClient?.requestGooglePhoneAuthorization()
                }

                override fun requestCalendarSnapshot() {
                    if (::dataSyncCoordinator.isInitialized) {
                        dataSyncCoordinator.requestPhoneCalendarSnapshot()
                    }
                }

                override fun disconnectPhoneAuthorization() {
                    rfcommClient?.disconnectGooglePhoneAuth()
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
            syncMessengerProvider = { rfcommClient },
            networkStatusProvider = DefaultNetworkStatusProvider(this),
            googleAuthCoordinatorProvider = {
                if (::googleAuthCoordinator.isInitialized) googleAuthCoordinator else null
            },
            googleCalendarClientProvider = {
                if (::googleCalendarClient.isInitialized) googleCalendarClient else null
            },
            localLocationProvider = { previousSpeedLocation },
            onPhoneLocationFix = ::applyPhoneLocationFix
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
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SMART_ALIGNMENT, enabled)
            .apply()
    }

    private fun restoreSmartAlignmentSetting() {
        val enabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    private fun saveSpeedVisibilityThreshold(thresholdKmh: Float) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SPEED_VISIBILITY_THRESHOLD, thresholdKmh.coerceIn(0f, 1f))
            .apply()
    }

    private fun loadSpeedVisibilityThreshold(): Float {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED_VISIBILITY_THRESHOLD, SPEED_THRESHOLD_KMH)
            .coerceIn(0f, 1f)
    }

    private fun computeSpeedMovingState(speedKmh: Float, qualityOk: Boolean, wasMoving: Boolean): Boolean {
        if (!qualityOk) return false
        if (speedVisibilityThresholdKmh <= 0f) return true

        val showThreshold = (speedVisibilityThresholdKmh + SPEED_THRESHOLD_HYSTERESIS_KMH).coerceIn(0f, 1f)
        val hideThreshold = (speedVisibilityThresholdKmh - SPEED_THRESHOLD_HYSTERESIS_KMH).coerceAtLeast(0f)
        return if (wasMoving) speedKmh >= hideThreshold else speedKmh >= showThreshold
    }

    private fun formatSpeedThresholdForLog(): String {
        if (speedVisibilityThresholdKmh <= 0f) return "always-visible (0.00 km/h)"

        val showThreshold = (speedVisibilityThresholdKmh + SPEED_THRESHOLD_HYSTERESIS_KMH).coerceIn(0f, 1f)
        val hideThreshold = (speedVisibilityThresholdKmh - SPEED_THRESHOLD_HYSTERESIS_KMH).coerceAtLeast(0f)
        return String.format(
            Locale.ENGLISH,
            "%.2f km/h (show>=%.2f, hide<%.2f)",
            speedVisibilityThresholdKmh,
            showThreshold,
            hideThreshold
        )
    }

    private fun initializeWifiDirect() {
        wifiDirectClient = WifiDirectClient(this).apply {
            connectionListener = object : WifiDirectConnectionListener {
                override fun onConnected(groupOwnerIp: String) {
                    Log.d(TAG, "WiFi Direct connected, group owner: $groupOwnerIp")
                    pendingWifiDirectMirrorIp?.let { ip ->
                        Log.d(TAG, "WiFi Direct connected, now enabling mirroring to $ip")
                        runOnUiThread {
                            widgetContainer.setMirroringEnabled(true, ip)
                        }
                        pendingWifiDirectMirrorIp = null
                    }
                }

                override fun onDisconnected() {
                    Log.d(TAG, "WiFi Direct disconnected")
                }

                override fun onPeersAvailable(peers: List<WifiP2pDevice>) {
                    Log.d(TAG, "WiFi Direct peers available: ${peers.size}")
                    peers.forEach { device ->
                        Log.d(TAG, "  - ${device.deviceName} (${device.deviceAddress}) status=${device.status}")
                    }

                    val availableDevice = peers.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
                    if (availableDevice != null) {
                        Log.d(TAG, "Auto-connecting to: ${availableDevice.deviceName}")
                        connectToDevice(availableDevice)
                    } else {
                        peers.firstOrNull { it.status == WifiP2pDevice.CONNECTED }?.let {
                            Log.d(TAG, "Already connected to: ${it.deviceName}")
                        }
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "WiFi Direct error: $message")
                }
            }

            initialize()
        }
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
            com.everyday.everyday_glasses.binocular.DisplayConfig.EYE_WIDTH.toFloat(),
            com.everyday.everyday_glasses.binocular.DisplayConfig.EYE_HEIGHT.toFloat()
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

        brightnessController.reapplyBrightness()
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
        if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
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
            setupLocationUpdates()
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
        rfcommClient?.stop()
        wifiDirectClient?.release()
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
        stopSpeedLocationUpdates()

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

    private fun handlePhonePointer(data: com.everyday.shared.transport.TransportMessage.Pointer) {
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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
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

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "setupLocationUpdates(): fine=$fine coarse=$coarse")
        speedLog { "setupLocationUpdates fine=$fine coarse=$coarse" }

        stopSpeedLocationUpdates()

        if (!fine && !coarse) {
            Log.w(TAG, "setupLocationUpdates(): no location permission -> showing dashes")
            widgetContainer.setLocationPermissionDenied()
            widgetContainer.setSpeedPermissionDenied()
            previousSpeedLocation = null
            smoothedSpeedKmh = 0f
            lastReliableSpeedSampleElapsedMs = 0L
            isSpeedMoving = false
            lastSpeedQualityOk = false
            speedLog { "Location permission denied -> speed pipeline disabled" }
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        speedLocationManager = locationManager

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val enabledProviders = locationManager.getProviders(true)
        val locationEnabled = androidx.core.location.LocationManagerCompat.isLocationEnabled(locationManager)
        Log.d(TAG, "Providers enabled: GPS=$gpsEnabled NETWORK=$netEnabled")
        Log.d(TAG, "Enabled providers: $enabledProviders")
        Log.d(TAG, "isLocationEnabled=$locationEnabled")
        speedLog { "Providers status: GPS=$gpsEnabled NETWORK=$netEnabled locationEnabled=$locationEnabled enabled=$enabledProviders" }
        if (!locationEnabled || (!gpsEnabled && !netEnabled)) {
            speedLog { "Location services unavailable (locationEnabled=$locationEnabled, gps=$gpsEnabled, network=$netEnabled)." }
        }

        val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        Log.d(TAG, "Last known gps=$lastGps net=$lastNet")

        try {
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    speedLog {
                        "Location update: provider=${location.provider} hasSpeed=${location.hasSpeed()} " +
                                "acc=${if (location.hasAccuracy()) String.format(Locale.ENGLISH, "%.1f", location.accuracy) else "na"} " +
                                "time=${location.time}"
                    }
                    updateLocationWidget(location)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {
                    speedLog { "Provider enabled: $provider" }
                }
                override fun onProviderDisabled(provider: String) {
                    speedLog { "Provider disabled: $provider" }
                }
            }
            speedLocationListener = locationListener

            val requestedProviders = linkedSetOf<String>()
            val candidateProviders = enabledProviders

            fun requestProvider(provider: String) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        SPEED_LOCATION_MIN_TIME_MS,
                        SPEED_LOCATION_MIN_DISTANCE_M,
                        locationListener
                    )
                    requestedProviders.add(provider)
                    speedLog { "Requested location updates from provider=$provider" }
                } catch (e: Exception) {
                    speedLog { "Failed to request updates from provider=$provider: ${e.message}" }
                }
            }

            if (candidateProviders.isNotEmpty()) {
                for (provider in candidateProviders) {
                    requestProvider(provider)
                }
            } else {
                // Keep a passive listener alive as fallback. Useful on devices where
                // GPS/NETWORK are off but another provider still emits updates.
                requestProvider(LocationManager.PASSIVE_PROVIDER)
            }

            val locationCandidates = linkedSetOf<String>().apply {
                addAll(requestedProviders)
                addAll(enabledProviders)
            }
            val fallbackProviders = locationCandidates
                .ifEmpty { listOf("fused", LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER) }
            startSpeedFallbackPolling(locationManager, fallbackProviders.toList())

            var bestLocation: Location? = null
            for (provider in locationCandidates) {
                val candidate = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
                if (bestLocation == null || candidate.time > (bestLocation?.time ?: Long.MIN_VALUE)) {
                    bestLocation = candidate
                }
            }

            if (bestLocation != null) {
                updateLocationWidget(bestLocation)
            } else {
                previousSpeedLocation = null
                lastSpeedQualityOk = false
                widgetContainer.updateSpeedData(0f, qualityOk = false, isMoving = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location updates", e)
            speedLog { "Failed to start location updates: ${e.message}" }
            stopSpeedFallbackPolling()
            previousSpeedLocation = null
            smoothedSpeedKmh = 0f
            lastReliableSpeedSampleElapsedMs = 0L
            isSpeedMoving = false
            lastSpeedQualityOk = false
            widgetContainer.setSpeedPermissionDenied()
        }
    }

    private fun stopSpeedLocationUpdates() {
        val manager = speedLocationManager
        val listener = speedLocationListener
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener)
                speedLog { "Location updates stopped" }
            } catch (e: Exception) {
                speedLog { "Error while stopping location updates: ${e.message}" }
            }
        }
        speedLocationListener = null
        speedLocationManager = null
        stopSpeedFallbackPolling()
        previousSpeedLocation = null
    }

    private fun startSpeedFallbackPolling(locationManager: LocationManager, providers: List<String>) {
        stopSpeedFallbackPolling()
        speedFallbackProviders = providers.distinct()

        val runnable = object : Runnable {
            override fun run() {
                val manager = speedLocationManager ?: return
                if (!hasAnyLocationPermission()) return

                val elapsedSinceLast = android.os.SystemClock.elapsedRealtime() - lastSpeedLocationUpdateElapsedMs
                if (lastSpeedLocationUpdateElapsedMs == 0L || elapsedSinceLast >= SPEED_STALE_UPDATE_WINDOW_MS) {
                    val updatedFromCache = tryUpdateSpeedFromLastKnown(manager, speedFallbackProviders)
                    if (!updatedFromCache) {
                        requestSingleSpeedLocation(manager, speedFallbackProviders)
                    }
                }

                handler.postDelayed(this, SPEED_FALLBACK_POLL_INTERVAL_MS)
            }
        }

        speedFallbackRunnable = runnable
        handler.postDelayed(runnable, SPEED_FALLBACK_POLL_INTERVAL_MS)
        speedLog { "Started speed fallback polling providers=$speedFallbackProviders" }
    }

    private fun stopSpeedFallbackPolling() {
        speedFallbackRunnable?.let { handler.removeCallbacks(it) }
        speedFallbackRunnable = null
        speedFallbackProviders = emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleSpeedLocation(locationManager: LocationManager, providers: List<String>) {
        if (!hasAnyLocationPermission()) return
        for (provider in providers) {
            val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrNull()
            speedLog { "Fallback probe provider=$provider enabled=${enabled ?: "unknown"}" }

            try {
                androidx.core.location.LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    provider,
                    null as android.os.CancellationSignal?,
                    androidx.core.content.ContextCompat.getMainExecutor(this)
                ) { location: Location? ->
                    if (location == null) {
                        speedLog { "Fallback current location returned null for provider=$provider" }
                        return@getCurrentLocation
                    }
                    speedLog {
                        "Fallback location: provider=$provider hasSpeed=${location.hasSpeed()} " +
                                "acc=${if (location.hasAccuracy()) String.format(Locale.ENGLISH, "%.1f", location.accuracy) else "na"} " +
                                "time=${location.time}"
                    }
                    lastSpeedLocationUpdateElapsedMs = android.os.SystemClock.elapsedRealtime()
                    updateLocationWidget(location)
                }
                speedLog { "Requested fallback current location from provider=$provider" }
                return
            } catch (e: Exception) {
                speedLog { "Failed fallback current location provider=$provider: ${e.message}" }
            }
        }
    }

    private fun applyPhoneLocationFix(snapshot: LocationFix) {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val localFresh = lastSpeedLocationUpdateElapsedMs != 0L &&
                (nowElapsed - lastSpeedLocationUpdateElapsedMs) < SPEED_STALE_UPDATE_WINDOW_MS &&
                lastSpeedQualityOk
        if (localFresh) {
            return
        }
        val safeSpeedMps = snapshot.usableSpeedMps() ?: run {
            val now = System.currentTimeMillis()
            if (now - lastMissingPhoneSpeedLogMs >= SPEED_LOG_INTERVAL_MS) {
                lastMissingPhoneSpeedLogMs = now
                speedLog { "Phone speed unavailable or low quality and local speed is stale" }
            }
            smoothSpeedReading(0f, qualityOk = false, source = "phone-unavailable")
            lastSpeedQualityOk = false
            isSpeedMoving = false
            widgetContainer.updateSpeedData(0f, qualityOk = false, isMoving = false)
            return
        }

        val phoneKmh = (safeSpeedMps * 3.6f)
            .coerceAtLeast(0f)
            .coerceAtMost(MAX_REASONABLE_SPEED_KMH)
        smoothSpeedReading(phoneKmh, qualityOk = true, source = "phone")

        val previousMoving = isSpeedMoving
        isSpeedMoving = computeSpeedMovingState(smoothedSpeedKmh, qualityOk = true, wasMoving = previousMoving)
        lastSpeedQualityOk = true
        widgetContainer.updateSpeedData(smoothedSpeedKmh, qualityOk = true, isMoving = isSpeedMoving)

        speedLog {
            "Applied phone speed: " +
                    "${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)} km/h " +
                    "(localFresh=$localFresh)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryUpdateSpeedFromLastKnown(locationManager: LocationManager, providers: List<String>): Boolean {
        if (!hasAnyLocationPermission()) return false
        var bestLocation: Location? = null
        var bestProvider: String? = null

        for (provider in providers.distinct()) {
            val candidate = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (bestLocation == null || candidate.time > (bestLocation?.time ?: Long.MIN_VALUE)) {
                bestLocation = candidate
                bestProvider = provider
            }
        }

        val location = bestLocation ?: return false
        val previousTime = previousSpeedLocation?.time ?: Long.MIN_VALUE
        if (location.time <= previousTime) {
            return false
        }

        speedLog {
            "Fallback lastKnown location: provider=${bestProvider ?: "unknown"} hasSpeed=${location.hasSpeed()} " +
                    "acc=${if (location.hasAccuracy()) String.format(Locale.ENGLISH, "%.1f", location.accuracy) else "na"} " +
                    "time=${location.time}"
        }
        updateLocationWidget(location)
        return true
    }

    /**
     * Called when local location updates are received.
     *
     * NOTE: Weather data prefers the phone app when connected. Local updates are used for:
     * - validating location permission/provider availability
     * - driving the local speedometer widget from sensor speed
     * - triggering standalone weather refreshes when no phone is connected
     */
    private fun updateLocationWidget(location: Location) {
        widgetContainer.updateMapLocation(location)
        lastSpeedLocationUpdateElapsedMs = android.os.SystemClock.elapsedRealtime()

        val now = System.currentTimeMillis()
        val ageMs = now - location.time
        val hasSensorSpeed = location.hasSpeed()
        val fineLocationGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val maxAllowedAccuracyM = if (fineLocationGranted) MAX_ACCEPTABLE_ACCURACY_M else MAX_ACCEPTABLE_COARSE_ACCURACY_M
        val accuracyOk = !location.hasAccuracy() || location.accuracy <= maxAllowedAccuracyM
        val ageOk = ageMs in 0..MAX_ACCEPTABLE_SPEED_AGE_MS
        val sensorKmh = if (hasSensorSpeed) location.speed.coerceAtLeast(0f) * 3.6f else 0f
        val hasReliableSensorSpeed = hasSensorSpeed && accuracyOk && ageOk

        var estimatedKmh = 0f
        var hasReliableEstimatedSpeed = false
        previousSpeedLocation?.let { previous ->
            val deltaTimeMs = location.time - previous.time
            if (deltaTimeMs in MIN_SPEED_ESTIMATE_INTERVAL_MS..MAX_SPEED_ESTIMATE_INTERVAL_MS) {
                val rawDistanceM = previous.distanceTo(location).coerceAtLeast(0f)
                val prevAccuracy = if (previous.hasAccuracy()) previous.accuracy else MAX_ACCEPTABLE_ACCURACY_M
                val currAccuracy = if (location.hasAccuracy()) location.accuracy else MAX_ACCEPTABLE_ACCURACY_M
                val noiseFloorM = ((prevAccuracy + currAccuracy) * 0.25f).coerceIn(0.8f, 8f)
                val adjustedDistanceM = (rawDistanceM - noiseFloorM).coerceAtLeast(0f)
                val estimated = (adjustedDistanceM / (deltaTimeMs / 1000f)) * 3.6f
                if (estimated.isFinite() && estimated in 0f..MAX_REASONABLE_SPEED_KMH && accuracyOk && ageOk) {
                    estimatedKmh = estimated
                    hasReliableEstimatedSpeed = true
                }
            }
        }
        previousSpeedLocation = Location(location)
        if (::dataSyncCoordinator.isInitialized) {
            dataSyncCoordinator.onLocalLocationChanged()
        }

        val qualityOk = hasReliableSensorSpeed || hasReliableEstimatedSpeed

        val rawKmh = when {
            hasReliableSensorSpeed -> sensorKmh
            hasReliableEstimatedSpeed -> estimatedKmh
            else -> 0f
        }
        smoothSpeedReading(
            rawKmh = rawKmh,
            qualityOk = qualityOk,
            source = if (hasReliableSensorSpeed) "sensor" else if (hasReliableEstimatedSpeed) "estimated" else "none"
        )

        val previousMoving = isSpeedMoving
        isSpeedMoving = computeSpeedMovingState(smoothedSpeedKmh, qualityOk, previousMoving)
        lastSpeedQualityOk = qualityOk

        if (previousMoving != isSpeedMoving) {
            val transition = if (isSpeedMoving) "idle -> moving" else "moving -> idle"
            speedLog {
                "Movement transition: $transition at " +
                        "${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)} km/h " +
                        "(threshold=${formatSpeedThresholdForLog()})"
            }
        }

        widgetContainer.updateSpeedData(smoothedSpeedKmh, qualityOk, isSpeedMoving)

        if (now - lastSpeedLogMs >= SPEED_LOG_INTERVAL_MS) {
            lastSpeedLogMs = now
            val displayUnit = widgetContainer.getSpeedUnit()
            val displaySpeed = if (displayUnit == SpeedometerWidget.SpeedUnit.KMH) {
                smoothedSpeedKmh
            } else {
                smoothedSpeedKmh * 0.621371f
            }
            speedLog {
                "speed=${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)} km/h " +
                        "display=${String.format(Locale.ENGLISH, "%.2f", displaySpeed)} ${displayUnit.label} " +
                        "moving=$isSpeedMoving quality=$qualityOk source=${if (hasReliableSensorSpeed) "sensor" else if (hasReliableEstimatedSpeed) "estimated" else "none"} " +
                        "acc=${if (location.hasAccuracy()) String.format(Locale.ENGLISH, "%.1f", location.accuracy) else "na"}m " +
                        "accLimit=${String.format(Locale.ENGLISH, "%.1f", maxAllowedAccuracyM)}m " +
                        "sensor=${String.format(Locale.ENGLISH, "%.2f", sensorKmh)}km/h " +
                        "estimate=${String.format(Locale.ENGLISH, "%.2f", estimatedKmh)}km/h ageMs=$ageMs " +
                        "threshold=${formatSpeedThresholdForLog()}"
            }
        }
    }

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

    private fun hasAnyLocationPermission(): Boolean {
        val fineGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
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
            if (!hasAnyLocationPermission()) {
                widgetContainer.setLocationPermissionDenied()
                widgetContainer.setSpeedPermissionDenied()
                previousSpeedLocation = null
                smoothedSpeedKmh = 0f
                lastReliableSpeedSampleElapsedMs = 0L
                isSpeedMoving = false
                lastSpeedQualityOk = false
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
                var locationGranted = hasAnyLocationPermission()

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
                    locationGranted = hasAnyLocationPermission()
                }

                if (bluetoothGranted) {
                    startClient()
                }

                if (locationGranted) {
                    speedLog { "Location permission granted -> starting speed updates" }
                    setupLocationUpdates()
                } else {
                    // Permission denied, show dashes
                    widgetContainer.withTransientChangeBatch {
                        widgetContainer.setLocationPermissionDenied()
                        widgetContainer.setSpeedPermissionDenied()
                    }
                    previousSpeedLocation = null
                    smoothedSpeedKmh = 0f
                    lastReliableSpeedSampleElapsedMs = 0L
                    isSpeedMoving = false
                    lastSpeedQualityOk = false
                    speedLog { "Location permission denied in result -> speed disabled" }
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
        if (isPhoneMode) {
            rfcommClient?.isPaused = false
            rfcommClient?.start()
        }
    }
}
