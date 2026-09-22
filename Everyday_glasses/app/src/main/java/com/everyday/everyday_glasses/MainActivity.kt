package com.everyday.everyday_glasses

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.everyday.everyday_glasses.auth_permissions.DefaultNetworkStatusProvider
import com.everyday.everyday_glasses.widgets.calendar.GoogleCalendarClient
import com.everyday.everyday_glasses.interaction.assistant.AssistantAudioRecorder
import com.everyday.everyday_glasses.interaction.assistant.AssistantConfiguration
import com.everyday.everyday_glasses.interaction.assistant.AssistantModel
import com.everyday.everyday_glasses.interaction.assistant.AssistantNetworkLease
import com.everyday.everyday_glasses.interaction.assistant.AssistantPreferencesStore
import com.everyday.everyday_glasses.interaction.assistant.AssistantStateObserver
import com.everyday.everyday_glasses.interaction.assistant.AssistantUiContext
import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserRoutingCoordinator
import com.everyday.everyday_glasses.interaction.assistant.page_agent.GeminiPageAgentProvider
import com.everyday.everyday_glasses.interaction.assistant.GeminiVoiceAssistantProvider
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentCoordinator
import com.everyday.everyday_glasses.interaction.assistant.VoiceAssistantCoordinator
import com.everyday.everyday_glasses.interaction.assistant.page_agent.WidgetContainerBrowserRoutingHost
import com.everyday.everyday_glasses.ui.components.CursorActivityObserver
import com.everyday.everyday_glasses.settings.DisplaySettingsHandler
import com.everyday.everyday_glasses.auth_permissions.GoogleAccountHandler
import com.everyday.everyday_glasses.interaction.user_input.TapGestureListener
import com.everyday.everyday_glasses._threedof.ThreeDofObserver
import com.everyday.everyday_glasses.headup_wake.WakeRequestListener
import com.everyday.everyday_glasses.connectivity.WidgetDataProvider
import com.everyday.everyday_glasses._binocular.BinocularContentClass
import com.everyday.everyday_glasses._binocular.DisplayConfig
import com.everyday.everyday_glasses._binocular.BinocularUpdateType
import com.everyday.everyday_glasses._binocular.BinocularView
import com.everyday.everyday_glasses.runtime.BrightnessController
import com.everyday.everyday_glasses.runtime.DisplayPowerCallbacks
import com.everyday.everyday_glasses.runtime.DisplayPowerController
import com.everyday.everyday_glasses.widgets.file_browser.FileImportCallbacks
import com.everyday.everyday_glasses.widgets.file_browser.FileImportCoordinator
import com.everyday.everyday_glasses.widgets.file_browser.FileImportError
import com.everyday.everyday_glasses.connectivity.GlassesDataSyncCoordinator
import com.everyday.everyday_glasses.auth_permissions.GoogleAuthCoordinator
import com.everyday.everyday_glasses.auth_permissions.GooglePhoneAuthStatus
import com.everyday.everyday_glasses.auth_permissions.GooglePhoneFallbackBridge
import com.everyday.everyday_glasses.headup_wake.HeadUpSessionController
import com.everyday.everyday_glasses.interaction.user_input.InputCoordinator
import com.everyday.everyday_glasses.widgets.speedometer.LocationSpeedController
import com.everyday.everyday_glasses.auth_permissions.PermissionCoordinator
import com.everyday.everyday_glasses.auth_permissions.PermissionCoordinatorCallbacks
import com.everyday.everyday_glasses.connectivity.PhoneConnectionCallbacks
import com.everyday.everyday_glasses.connectivity.PhoneConnectionCoordinator
import com.everyday.everyday_glasses.widgets.system_status.StatusBarController
import com.everyday.everyday_glasses.widgets.system_status.StatusBarControllerCallbacks
import com.everyday.everyday_glasses.widgets.system_status.StatusBarDisplayState
import com.everyday.everyday_glasses.interaction.user_input.UserInputActivity
import com.everyday.everyday_glasses.interaction.user_input.UserInputKind
import com.everyday.everyday_glasses.auth_permissions.GoogleAccountSummary
import com.everyday.everyday_glasses.auth_permissions.GoogleAuthState
import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.headup_wake.GlassesService
import com.everyday.everyday_glasses.interaction.user_input.TapGestureDetector
import com.everyday.everyday_glasses.headup_wake.HeadUpWakeManager
import com.everyday.everyday_glasses._threedof.ThreeDofManager
import com.everyday.everyday_glasses.runtime.WakeSleepManager
import com.everyday.everyday_glasses.persistence.ActiveDashboardStore
import com.everyday.everyday_glasses.ui.components.BuiltInLayouts
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.GlassesSettingsStore
import com.everyday.everyday_glasses.auth_permissions.GoogleAuthPreferencesStore
import com.everyday.everyday_glasses.persistence.HeadUpSettings
import com.everyday.everyday_glasses.ui.components.CursorView
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserWidget
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
import java.util.concurrent.CompletableFuture

/**
 * Main Android activity and top-level runtime coordinator for the glasses app.
 *
 * MainActivity creates the app's primary views and connects the major systems that
 * need access to the Android activity lifecycle. It is the composition point where
 * coordinators, controllers, stores, and UI components are constructed and wired
 * together.
 *
 * Its main responsibilities are:
 * - Initializing the glasses display, cursor, and WidgetContainer
 * - Forwarding Android lifecycle, input, permission, and memory events
 * - Coordinating phone connectivity and synchronized phone data
 * - Connecting authentication, calendar, location, file import, and assistant services
 * - Managing display power, brightness, head-up wake behavior, and 3DOF tracking
 * - Starting dashboard restoration and persistence
 * - Routing changes between app services and the visible UI
 *
 * Detailed behavior is delegated to focused components. InputCoordinator handles
 * user input, WidgetContainer manages the widget dashboard, and the other
 * coordinators and controllers own their respective app systems.
 *
 * MainActivity should primarily contain application startup, lifecycle handling,
 * dependency wiring, and coordination between otherwise separate systems.
 */
class MainActivity : AppCompatActivity() {

    
    private lateinit var binocularView: BinocularView
    private lateinit var cursorView: CursorView
    private lateinit var widgetContainer: WidgetContainer
    private lateinit var persistenceManager: ActiveDashboardStore

    // Head-up wake manager for hands-free screen wake
    private lateinit var headUpWakeManager: HeadUpWakeManager
    private lateinit var inputCoordinator: InputCoordinator
    private lateinit var userInputActivity: UserInputActivity
    private lateinit var assistantNetworkLease: AssistantNetworkLease

    private lateinit var headUpSessionController: HeadUpSessionController
    private lateinit var settingsStore: GlassesSettingsStore

    // 3DOF (Three Degrees of Freedom) head tracking manager
    private lateinit var threeDofManager: ThreeDofManager

    // Tap gesture detector for single/double/triple tap detection
    private lateinit var tapGestureDetector: TapGestureDetector

    // Brightness management
    private lateinit var brightnessController: BrightnessController

    // Speed data management
    private lateinit var locationSpeedController: LocationSpeedController

    // Display power on/off management
    private lateinit var displayPowerController: DisplayPowerController

    // Time / date / battery display management
    private lateinit var statusBarController: StatusBarController

    private val handler = android.os.Handler(Looper.getMainLooper())

    // Pending widget restoration (waits for views to be ready)
    private var pendingWidgetRestore = false

    private lateinit var phoneConnectionCoordinator: PhoneConnectionCoordinator // Phone communication (inbound / outbound) coordination
    private lateinit var googleAuthCoordinator: GoogleAuthCoordinator
    private lateinit var googleCalendarClient: GoogleCalendarClient
    private lateinit var dataSyncCoordinator: GlassesDataSyncCoordinator
    private lateinit var voiceAssistantCoordinator: VoiceAssistantCoordinator
    private lateinit var browserRoutingCoordinator: BrowserRoutingCoordinator
    private lateinit var permissionCoordinator: PermissionCoordinator
    private lateinit var fileImportCoordinator: FileImportCoordinator

    companion object {
        private const val TAG = "Everyday_glasses"
        // Keep media dimming visible; the only intentional full black mask is DisplayState.OFF.

        // Speedometer logging gate (flip false to silence)
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

        setupDisplayPowerController()
        displayPowerController.initializeWindow()
        settingsStore = GlassesSettingsStore(this)
        headUpSessionController = HeadUpSessionController(
            onSessionExpired = {
                Log.d(TAG, "Head-up session expired; transitioning display to OFF")
                displayPowerController.transitionTo(
                    WakeSleepManager.DisplayState.OFF,
                    reason = "head_up_session_expired"
                )
            }
        )

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

        // Initialize imports from the in-app file browser.
        setupFileImportCoordinator()
        setupPermissionCoordinator()

        cursorView.activityObserver = object : CursorActivityObserver {
            override fun onVisibilityChanged(visible: Boolean) {
                val cursorX = cursorView.getCursorX()
                val cursorY = cursorView.getCursorY()
                widgetContainer.updateCursor(cursorX, cursorY, visible)
                widgetContainer.onCursorVisibilityChanged(visible)
                if (!displayPowerController.isTransitioningDisplayState) {notifyTransientBinocularChange()}
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
        // is deleting a layout. See DashboardPersistence.pruneUnreferencedImages.
        DashboardPersistence.pruneUnreferencedImages(this)
        inputCoordinator = InputCoordinator(
            cursorView = cursorView,
            widgetContainer = widgetContainer,
            tapGestureDetectorProvider = {
                if (::tapGestureDetector.isInitialized) tapGestureDetector else null
            },
            isAwake = { !::displayPowerController.isInitialized || displayPowerController.isAwake() },
            wakeDisplay = {
                if (::displayPowerController.isInitialized) {
                    displayPowerController.wakeFromUserAction("input")
                }
            },
            notifyUserActivity = ::notifyUserActivity,
            notifyTransientChange = ::notifyTransientBinocularChange,
            onTripleTap = ::handleTripleTap,
            userInputActivity = userInputActivity
        )
        updateCursorRedrawMode()
        setupStatusBarController()
        statusBarController.start()

        // Initialize Google auth after the mirrored UI is ready.
        setupGoogleAuth()
        setupDataSyncCoordinator()

        // Start internal display state management after views are ready.
        displayPowerController.start()

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
            hasMicrophonePermission = permissionCoordinator::hasMicrophonePermission,
            requestMicrophonePermission = permissionCoordinator::requestMicrophonePermission,
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

        cursorView.setCursorColor(Color.WHITE)

        // Aggressively prevent system keyboard
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        
        // Force hide any existing keyboard
        hideSystemKeyboard()

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

        // Check startup capabilities and request anything currently missing.
        permissionCoordinator.handleStartupPermissions()

        GlassesService.start(this)

        // Note: Head-up wake is now handled via static flags in onResume()
        // This ensures consistent handling whether activity is created fresh or resumed
        // The static flags are checked in onResume which runs after onCreate

        Log.d(TAG, "onCreate complete")
    }

    /**
     * Hide system UI and enable immersive mode to prevent OS gestures.
     */
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ approach
            @Suppress("DEPRECATION")
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
                if (::statusBarController.isInitialized) {statusBarController.refreshNow()}
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
                    displayPowerController.setMediaKeepAwake(sourceId, active)
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
                permissionCoordinator.requestFileBrowserAccess()
            }

            override fun requestStoragePermission() {
                Log.d(TAG, "Storage permission requested by widget")
                permissionCoordinator.requestStoragePermissions()
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
        speedLog { "Loaded speed visibility threshold $speedThreshold" }
    }

    private fun phoneConnectionOrNull(): PhoneConnectionCoordinator? =
        if (::phoneConnectionCoordinator.isInitialized) {
            phoneConnectionCoordinator
        } else {
            null
        }

    private fun setupStatusBarController() {
        statusBarController = StatusBarController(
            context = this,
            callbacks = object : StatusBarControllerCallbacks {
                override fun applyStatusBarState(state: StatusBarDisplayState) {
                    widgetContainer.updateStatusBar(
                        isPhoneConnected = state.isPhoneConnected,
                        phoneBattery = state.phoneBatteryPercent,
                        glassesBattery = state.glassesBatteryPercent,
                        time = state.time,
                        date = state.date
                    )
                }

                override fun onChargingChanged(charging: Boolean) {
                    binocularView.setChargingState(charging)

                    val changed = brightnessController.setCharging(charging)
                    if (changed) {
                        Log.d(TAG, "Charging state changed: charging=$charging")
                    }
                }
            }
        )
    }

    private fun setupFileImportCoordinator() {
        fileImportCoordinator = FileImportCoordinator(
            activity = this,
            callbacks = object : FileImportCallbacks {
                override fun showFileBrowser(
                    filter: FileBrowserWidget.FileFilter,
                    onFileSelected: (FileBrowserWidget.FileEntry) -> Unit
                ) {
                    widgetContainer.createFileBrowserWidget(
                        filter = filter,
                        onFileSelected = onFileSelected
                    )
                }

                override fun createImageWidget(path: String) {
                    widgetContainer.createImageWidget(path)
                }

                override fun createVideoWidget(
                    uri: Uri,
                    displayName: String
                ) {
                    widgetContainer.createVideoWidget(uri, displayName)
                }

                override fun createPdfWidget(
                    uri: Uri,
                    displayName: String
                ) {
                    widgetContainer.createPdfWidget(uri, displayName)
                }

                override fun showFileImportError(error: FileImportError) {
                    Toast.makeText(
                        this@MainActivity,
                        error.userMessage,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun setupPermissionCoordinator() {
        permissionCoordinator = PermissionCoordinator(
            activity = this,
            callbacks = object : PermissionCoordinatorCallbacks {
                override fun onBluetoothPermissionAvailable() {
                    phoneConnectionOrNull()?.start()
                }

                override fun onLocationPermissionAvailable() {
                    locationSpeedController.startLocationUpdates()
                }

                override fun onLocationPermissionDenied() {
                    locationSpeedController.onLocationPermissionDenied()
                    speedLog {
                        "Startup without location permission -> speed disabled"
                    }
                }

                override fun onWifiDirectPermissionAvailable() {
                    phoneConnectionOrNull()?.onWifiDirectPermissionAvailable()
                }

                override fun onWifiDirectPermissionDenied() {
                    phoneConnectionOrNull()?.onWifiDirectPermissionDenied()
                }

                override fun onFileBrowserAccessHandled() {
                    widgetContainer.post { fileImportCoordinator.openFileBrowser() }
                } // The all-files settings path returns through onResume; post until the resumed view hierarchy is ready for widget work.

                override fun onAssistantAudioPermissionResult(granted: Boolean) {
                    if (::voiceAssistantCoordinator.isInitialized) {
                        voiceAssistantCoordinator.onPermissionResult(granted)
                    }
                }
            }
        )
    }

    private fun setupPhoneConnectionCoordinator() {
        phoneConnectionCoordinator = PhoneConnectionCoordinator(
            context = this,
            callbacks = object : PhoneConnectionCallbacks {
                override fun onConnectionStatusChanged(status: String) {
                    Log.d(TAG, "Connection status: $status")
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

                    if (!connected) {
                        statusBarController.onPhoneConnectionChanged(
                            connected = false,
                            phoneBatteryPercent = -1
                        )
                        widgetContainer.unfocusAll()
                        widgetContainer.setMirroringEnabled(false)
                        widgetContainer.onSubtitlePhoneDisconnected()
                    } else {
                        statusBarController.onPhoneConnectionChanged(
                            connected = true,
                            phoneBatteryPercent = phoneBatteryPercent
                        )
                        widgetContainer.onPhoneReconnected()
                    }
                }

                override fun onPhoneBatteryChanged(phoneBatteryPercent: Int) {
                    statusBarController.onPhoneBatteryChanged(phoneBatteryPercent)
                }

                override fun onPointerReceived(pointer: TransportMessage.Pointer) {
                    handlePhonePointer(pointer)
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
                    Log.d(TAG, "Image received from phone: $fileName (${imageData.size} bytes)")
                    fileImportCoordinator.importReceivedImage(imageData, fileName)
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

                override fun onWifiDirectPermissionRequired() {
                    permissionCoordinator.requestWifiDirectPermission()
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
                googleAuthCoordinator.beginConnect()
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
        settingsStore.saveSmartAlignmentEnabled(enabled)
    }

    private fun restoreSmartAlignmentSetting() {
        widgetContainer.setSmartAlignmentEnabled(settingsStore.loadSmartAlignmentEnabled())
    }



    /**
     * Save head-up wake settings to SharedPreferences.
     */
    private fun saveHeadUpSettings() {
        val headUpTimeMs = widgetContainer.getHeadUpTime()
        val wakeDurationMs = widgetContainer.getWakeDuration()
        val angleThreshold = widgetContainer.getAngleThreshold()
        val headsUpEnabled = widgetContainer.getHeadsUpEnabled()
        settingsStore.saveHeadUpSettings(
            HeadUpSettings(
                holdTimeMs = headUpTimeMs,
                wakeDurationMs = wakeDurationMs,
                angleThresholdDegrees = angleThreshold,
                enabled = headsUpEnabled
            )
        )
        Log.d(TAG, "Saved head-up settings: headUpTime=${headUpTimeMs}ms, wakeDuration=${wakeDurationMs}ms, angle=${angleThreshold}°, enabled=$headsUpEnabled")
    }

    /**
     * Restore head-up wake settings from SharedPreferences.
     */
    private fun restoreHeadUpSettings() {
        val settings = settingsStore.loadHeadUpSettings()

        widgetContainer.setHeadUpTime(settings.holdTimeMs)
        widgetContainer.setWakeDuration(settings.wakeDurationMs)
        widgetContainer.setAngleThreshold(settings.angleThresholdDegrees)
        widgetContainer.setHeadsUpEnabled(settings.enabled)

        // Apply to HeadUpWakeManager if initialized
        if (::headUpWakeManager.isInitialized) {
            headUpWakeManager.headUpHoldTimeMs = settings.holdTimeMs
            headUpWakeManager.wakeDurationMs = settings.wakeDurationMs
            headUpWakeManager.pitchChangeThreshold = settings.angleThresholdDegrees
            headUpWakeManager.isHeadsUpGestureEnabled = settings.enabled
        }

        Log.d(TAG, "Restored head-up settings: $settings")
    }

    private fun setupDisplayPowerController() {
        displayPowerController = DisplayPowerController(
            activity = this,
            callbacks = object : DisplayPowerCallbacks {
                override fun onDisplayStateChanging(
                    state: WakeSleepManager.DisplayState
                ) {
                    if (::headUpWakeManager.isInitialized) {
                        headUpWakeManager.onScreenStateChanged(
                            state == WakeSleepManager.DisplayState.OFF
                        )
                    }
                }

                override fun applyDisplayState(
                    state: WakeSleepManager.DisplayState
                ) {
                    when (state) {
                        WakeSleepManager.DisplayState.WAKE -> {
                            cursorView.setForceHidden(
                                false,
                                useGentleRedraw = true
                            )
                        }

                        WakeSleepManager.DisplayState.SLEEP,
                        WakeSleepManager.DisplayState.OFF -> {
                            cursorView.setForceHidden(
                                true,
                                useGentleRedraw = true
                            )
                        }
                    }

                    widgetContainer.setDisplayState(state)
                }

                override fun ensureVisibleWakeState(): Boolean {
                    val containerWasNotAwake = !widgetContainer.isDisplayAwake()

                    cursorView.setForceHidden(
                        false,
                        useGentleRedraw = true
                    )
                    widgetContainer.setDisplayState(
                        WakeSleepManager.DisplayState.WAKE
                    )

                    return containerWasNotAwake
                }

                override fun onDisplayWake(reason: String) {
                    if (::dataSyncCoordinator.isInitialized) {
                        dataSyncCoordinator.onDisplayWake(reason)
                    }
                }

                override fun hasPinnedWidgets(): Boolean =
                    widgetContainer.hasPinnedWidgets()

                override fun reapplyBrightness() {
                    brightnessController.applyEffectiveWindowBrightness()
                }
            }
        )
    }

    /**
     * Initialize and configure the head-up wake manager.
     * This enables hands-free screen wake by tilting the head up.
     */
    private fun setupHeadUpWakeManager() {
        headUpWakeManager = HeadUpWakeManager(this)

        headUpWakeManager.headUpHoldTimeMs = widgetContainer.getHeadUpTime()
        headUpWakeManager.wakeDurationMs = widgetContainer.getWakeDuration()
        headUpWakeManager.pitchChangeThreshold = widgetContainer.getAngleThreshold()
        headUpWakeManager.isHeadsUpGestureEnabled = widgetContainer.getHeadsUpEnabled()

        headUpWakeManager.wakeRequestListener = object : WakeRequestListener {
            override fun onWakeRequested() {
                Log.d(TAG, "Head-up wake requested (activity in foreground, waking from internal OFF state)")
                if (::displayPowerController.isInitialized) {
                    when (displayPowerController.state) {
                        WakeSleepManager.DisplayState.OFF,
                        WakeSleepManager.DisplayState.SLEEP -> {
                            val wakeDurationMs = headUpWakeManager.wakeDurationMs
                            Log.d(
                                TAG,
                                "Starting head-up wake session with duration ${wakeDurationMs}ms"
                            )
                            headUpSessionController.start(wakeDurationMs)
                            displayPowerController.transitionTo(
                                WakeSleepManager.DisplayState.WAKE,
                                reason = "head_up_gesture"
                            )
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
        if (::displayPowerController.isInitialized) {displayPowerController.onUserActivity()}
        // This is head-up session policy, so it remains in MainActivity for now.
        headUpSessionController.onUserActivity()
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
     * @param state The preloaded persisted state (loaded before layout to set isReturningUser flag)
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

    private fun handleDebugIntent(intent: Intent?) {
        val isDebuggable =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable || intent?.action != ACTION_DEBUG_CLEAR_WEBVIEW) return

        val profileSummary = binocularView.getRenderProfileSummary()
        Log.d(TAG, "Debug clear WebView requested, binocularProfile=$profileSummary")
        widgetContainer.clearDebugWebViewData(profileSummary)
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

        // Check if any permissions are needed on resuming the activity (important for file browser)
        if (::permissionCoordinator.isInitialized) {permissionCoordinator.onActivityResumed()}

        // Notify service that activity is in foreground
        // Service will disable its head-up detection (we handle it here)
        GlassesService.notifyActivityResumed(this)

        // Enable the activity's own head-up wake manager
        // (for detecting head-up when in app's internal SLEEP/OFF state)
        if (::headUpWakeManager.isInitialized) {headUpWakeManager.enable()}

        // Check for head-up wake via static flags (intent extras don't work reliably with full-screen intents)
        if (GlassesService.pendingHeadUpWake) {
            val wakeDurationMs = GlassesService.pendingHeadUpWakeDuration
            Log.d(TAG, "Activity resumed via head-up wake (from static flags, wakeDuration=${wakeDurationMs}ms)")

            // Clear the flags immediately
            GlassesService.pendingHeadUpWake = false

            headUpSessionController.start(wakeDurationMs)

            // Ensure internal state wakes when resumed from service-triggered head-up wake.
            if (::displayPowerController.isInitialized && displayPowerController.state != WakeSleepManager.DisplayState.WAKE) {
                displayPowerController.transitionTo(
                    WakeSleepManager.DisplayState.WAKE,
                    reason = "head_up_wake_resume"
                )
            }

            widgetContainer.onHeadUpWake()
        }

        // Hardware action-button wake can resume the activity without an internal
        // WakeSleepManager transition. Let the coordinator refresh visible data.
        if (::displayPowerController.isInitialized) {
            displayPowerController.onActivityResumed()
        }
        widgetContainer.onAppResumed()
        if (::dataSyncCoordinator.isInitialized) {
            dataSyncCoordinator.onAppResumed()
        }

        // Re-evaluate local speed pipeline on resume in case providers were toggled externally.
        if (::permissionCoordinator.isInitialized && permissionCoordinator.hasAnyLocationPermission()) {locationSpeedController.startLocationUpdates()}
        if (::googleAuthCoordinator.isInitialized) {googleAuthCoordinator.refreshStateSilently(this)}
    }

    override fun onPause() {
        super.onPause()
        binocularView.onPause()
        brightnessController.onPause()

        widgetContainer.onAppPaused()

        // Save widget state when app goes to background
        saveWidgetState()

        // Cancel head-up wake auto-finish timer (activity is already going to background)
        headUpSessionController.cancel()
        locationSpeedController.stopLocationUpdates()

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

    override fun onDestroy() {
        // Flush the last persistent mutation even when the activity is destroyed without a preceding pause/stop callback.
        if (::persistenceManager.isInitialized) saveWidgetState()
        if (::voiceAssistantCoordinator.isInitialized) voiceAssistantCoordinator.release()
        if (::browserRoutingCoordinator.isInitialized) browserRoutingCoordinator.release()
        if (::assistantNetworkLease.isInitialized) assistantNetworkLease.close()

        if (::phoneConnectionCoordinator.isInitialized) {phoneConnectionCoordinator.release()}

        GlassesService.stop(this)
        binocularView.onDestroy()
        locationSpeedController.release() // Stop local speed/location updates
        brightnessController.onDestroy() // Stop brightness control updates
        if (::headUpSessionController.isInitialized) headUpSessionController.release()

        if (::displayPowerController.isInitialized) {displayPowerController.release()} // Stop display power control updates
        if (::inputCoordinator.isInitialized) {inputCoordinator.release()} // Clean up input coordinator callbacks
        if (::dataSyncCoordinator.isInitialized) {dataSyncCoordinator.release()}
        if (::permissionCoordinator.isInitialized) {permissionCoordinator.release()}
        if (::statusBarController.isInitialized) {statusBarController.release()} // Stop status bar monitoring
        if (::fileImportCoordinator.isInitialized) {fileImportCoordinator.release()}

        widgetContainer.release() // Clean up widget container resources

        if (::headUpWakeManager.isInitialized) {headUpWakeManager.release()} // Clean up head-up wake manager
        if (::threeDofManager.isInitialized) {threeDofManager.release()} // Clean up 3DOF manager
        if (::tapGestureDetector.isInitialized) {tapGestureDetector.release()} // Clean up tap gesture detector
        super.onDestroy()
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
            hideSystemKeyboard() // Also hide system keyboard when window gains focus
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
        // it can actually SHOW the keyboard if it's currently hidden!
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
        if (::displayPowerController.isInitialized && !displayPowerController.isAwake()) {
            Log.d(TAG, "Key event while display not awake - waking display")
            if (::userInputActivity.isInitialized) {userInputActivity.onUserInput(UserInputKind.KEYBOARD)}
            displayPowerController.wakeFromUserAction("key_event")
            return true
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (::permissionCoordinator.isInitialized) {
            permissionCoordinator.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
            )
        }
    }

}
