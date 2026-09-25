package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.everyday.everyday_glasses.interaction.commands.AwaitingCommandUiDispatcher
import com.everyday.everyday_glasses.interaction.user_input.TextEditingController
import com.everyday.everyday_glasses.interaction.assistant.AssistantConfiguration
import com.everyday.everyday_glasses.interaction.assistant.AssistantModel
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.AssistantTextPresentationRequest
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolCatalog
import com.everyday.everyday_glasses.interaction.assistant.AssistantUiContext
import com.everyday.everyday_glasses.interaction.assistant.AssistantVoiceHoverControl
import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserRoutingCoordinator
import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserWidgetPageAgentAdapter
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.ConfigureMapPoisRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.ConfigureMapPoisSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.DuckDuckGoWebSearchProvider
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateResult
import com.everyday.everyday_glasses.interaction.assistant.InspectUiSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.NewsSourceUpdateResult
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentApprovalOverlay
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentBrowserAdapter
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentConfirmationRequest
import com.everyday.everyday_glasses.interaction.assistant.PresentTextSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.RequestScreenshotSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.page_agent.RunBrowserTaskSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.SetNewsSourceSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.StartNavigationSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.StopNavigationSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.UpdateFinanceTileSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.WebSearchSemanticToolHandler
import com.everyday.everyday_glasses.runtime.DashboardChangeCoordinator
import com.everyday.everyday_glasses.runtime.BrowserWidgetListener
import com.everyday.everyday_glasses.settings.DisplaySettingsHandler
import com.everyday.everyday_glasses.runtime.FileBrowserWidgetListener
import com.everyday.everyday_glasses.runtime.GeminiSettingsListener
import com.everyday.everyday_glasses.auth_permissions.GoogleAccountHandler
import com.everyday.everyday_glasses.runtime.MapWidgetListener
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.MirrorWidgetListener
import com.everyday.everyday_glasses.runtime.SubtitleWidgetListener
import com.everyday.everyday_glasses.connectivity.WidgetDataProvider
import com.everyday.everyday_glasses.runtime.WidgetPersistenceListener
import com.everyday.everyday_glasses.runtime.WidgetChangeListener
import com.everyday.everyday_glasses._binocular.BinocularContentClass
import com.everyday.everyday_glasses.interaction.commands.AppCommandCapabilities
import com.everyday.everyday_glasses.interaction.commands.AppCommandExecutor
import com.everyday.everyday_glasses.interaction.commands.AppCommandResult
import com.everyday.everyday_glasses.widgets.map.backend.GlassesRouteTransport
import com.everyday.everyday_glasses.widgets._common.HandlerPresentationScheduler
import com.everyday.everyday_glasses.widgets._common.PresentationBounds
import com.everyday.everyday_glasses.widgets._common.PresentationController
import com.everyday.everyday_glasses.widgets._common.PresentationLifecycleDelegate
import com.everyday.everyday_glasses.widgets._common.PresentationMode
import com.everyday.everyday_glasses.widgets._common.PresentationProgress
import com.everyday.everyday_glasses.widgets._common.PresentationSession
import com.everyday.everyday_glasses.widgets._common.PresentationSnapshot
import com.everyday.everyday_glasses.widgets._common.PresentationSpec
import com.everyday.everyday_glasses.settings.SettingsController
import com.everyday.everyday_glasses.interaction.user_input.UserInputActivity
import com.everyday.everyday_glasses.interaction.user_input.UserInputKind
import com.everyday.everyday_glasses.auth_permissions.GoogleAuthState
import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.NavigationOperationResult
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import com.everyday.everyday_glasses.widgets.map.backend.StartNavigationRequest
import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.browser.BrowserWidgetFactory
import com.everyday.everyday_glasses.widgets.file_browser.VideoWidgetFactory
import com.everyday.everyday_glasses.ui.components.CloseConfirmationHandler
import com.everyday.everyday_glasses.ui.components.ContextMenuHandler
import com.everyday.everyday_glasses.interaction.user_input.CursorStabilizer
import com.everyday.everyday_glasses.ui.components.LayoutDeletionHandler
import com.everyday.everyday_glasses.ui.components.LayoutNameHandler
import com.everyday.everyday_glasses.widgets._common.SmartAlignmentHelper
import com.everyday.everyday_glasses.widgets._common.SmartAlignmentRenderer
import com.everyday.everyday_glasses._threedof.DashboardTransform
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.interaction.commands.AppCommandRegistry
import com.everyday.everyday_glasses.interaction.commands.CommandAvailability
import com.everyday.everyday_glasses.settings.UnitSettings
import com.everyday.everyday_glasses.runtime.WakeSleepManager
import com.everyday.everyday_glasses.persistence.WidgetLayoutManager
import com.everyday.everyday_glasses.runtime.WidgetLifecycleController
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.ui.components.BuiltInLayouts
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.ImageWidgetStorage
import com.everyday.everyday_glasses.persistence.LayoutLibrary
import com.everyday.everyday_glasses.persistence.ShortcutsStore
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.VideoQueueRepository
import com.everyday.everyday_glasses.ui.hover_controls.BaseHoverControl
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.browser.BrowserWidget
import com.everyday.everyday_glasses.widgets.calendar.CalendarWidget
import com.everyday.everyday_glasses.ui.hover_controls.CloseAppHoverControl
import com.everyday.everyday_glasses.ui.components.CloseConfirmationPopup
import com.everyday.everyday_glasses.ui.components.ContextMenu
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserWidget
import com.everyday.everyday_glasses.widgets.finance.FinanceWidget
import com.everyday.everyday_glasses.widgets.file_browser.ImageWidget
import com.everyday.everyday_glasses.ui.hover_controls.LemonHoverControl
import com.everyday.everyday_glasses.widgets.weather_location.LocationWidget
import com.everyday.everyday_glasses.widgets.map.MapWidget
import com.everyday.everyday_glasses.widgets.news.NewsWidget
import com.everyday.everyday_glasses.widgets.notifications.NotificationsWidget
import com.everyday.everyday_glasses.widgets.file_browser.PdfWidget
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.ScreenMirrorWidget
import com.everyday.everyday_glasses.widgets.speedometer.SpeedometerWidget
import com.everyday.everyday_glasses.widgets.system_status.StatusBarWidget
import com.everyday.everyday_glasses.widgets.speech_to_text.SubtitleWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import com.everyday.everyday_glasses._threedof.ThreeDofOverlay
import com.everyday.everyday_glasses.widgets.file_browser.VideoWidget
import com.everyday.everyday_glasses.ui.components.WidgetLayoutDeleteConfirmationPopup
import com.everyday.everyday_glasses.ui.components.GlassesKeyboardView
import com.everyday.everyday_glasses.ui.components.WidgetLayoutNamePrompt
import com.everyday.everyday_glasses.interaction.assistant.AssistantUiContextCapture
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.MapAssistantActions
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.TextAssistantActions
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.widgets.finance.FinanceFeatureController
import com.everyday.everyday_glasses.widgets.news.NewsFeatureController
import com.everyday.everyday_glasses.widgets.weather_location.LocationWeatherBinding
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.sync.WeatherData
import com.everyday.shared.command.AppCommandArguments
import com.everyday.shared.command.AppCommandInvocation
import com.everyday.shared.command.CommandScrollDirection
import com.everyday.shared.command.CommandWidgetId
import com.everyday.shared.transport.AssistantStatus
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass

/**
 * Central UI host and coordinator for the dashboard's widgets.
 *
 * WidgetContainer owns the currently displayed collection of widgets and coordinates
 * how they are created, presented, interacted with, updated, persisted, and removed.
 * Individual widget classes remain responsible for rendering and behavior specific
 * to their own content.
 *
 * Its main responsibilities are:
 * - Creating, registering, restoring, closing, and arranging widgets
 * - Routing taps, pointer movement, dragging, keyboard input, and hover interactions
 * - Coordinating focus, context menus, formatting controls, and other UI overlays
 * - Drawing widgets and applying fullscreen, minimized, presentation, and 3DOF states
 * - Delivering app data and external updates to the appropriate visible widgets
 * - Capturing and restoring dashboard state in cooperation with persistence classes
 * - Exposing widget operations to app commands and assistant tools
 *
 * Supporting components own narrower parts of this work, including:
 * - WidgetRegistry for the active widget collection, lookup, and display ordering
 * - WidgetLifecycleController for widget registration, closing, and cleanup
 * - WidgetCaptureRestore for translating between live widgets and persisted state
 * - PresentationController for temporary widget presentation modes and transitions
 * - CursorStabilizer for smoothing cursor movement
 * - ThreeDofOverlay and related helpers for 3DOF display behavior
 *
 * This class coordinates these systems; it should not contain the internal
 * content-specific logic of individual widget types.
 */
/** Observes user-visible content changes produced by [com.everyday.everyday_glasses.WidgetContainer]. */
interface WidgetContentObserver {
    fun onFocusChanged(focused: Boolean)
    fun onPersistentContentChanged()
    fun onTransientContentChanged()
    /** Emits the one structural binocular refresh that completes a suppressed restore. */
    fun onDashboardRestored() = Unit
    fun onMediaFrame(sourceId: String)
    fun onBinocularContentSourceChanged(
        sourceId: String,
        contentClass: BinocularContentClass,
        active: Boolean
    )
}

/** Capabilities the activity supplies to [com.everyday.everyday_glasses.WidgetContainer]. */
interface WidgetHost {
    fun requestClose()
    fun requestMirror(start: Boolean)
    fun requestBrowserKeyboard(show: Boolean)
    fun requestClipboard()
    fun requestFilePicker()
    fun requestStoragePermission()
    fun requestCursorCenter()
    fun requestSubtitleControl(control: SubtitleControl)
    fun toggleAssistantVoice()
}
class WidgetContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class InputSource {
        GLASSES_TEMPLE,
        PHONE_TRACKPAD
    }

    companion object {
        private const val TAG = "WidgetContainer"

        private const val DEFAULT_TEXTBOX_WIDTH_PERCENT = 0.30f
        private const val DEFAULT_TEXTBOX_HEIGHT_PERCENT = 0.15f
        private const val DEFAULT_SPEED_VISIBILITY_THRESHOLD_KMH = 1f
        private const val LONG_PRESS_DELAY_MS = 250L
        private const val LONG_PRESS_MAX_MOVEMENT = 20f
        private const val HOVER_CONFIRM_RELEASE_TAP_GUARD_MS = 300L
        private const val BROWSER_TOUCH_TAP_SUPPRESSION_MS = 350L
        
        // Phone trackpad sensitivity multiplier
        private const val PHONE_TRACKPAD_SENSITIVITY = 1.5f
    }

    // ==================== Delegated Components ====================

    private val cursorStabilizer = CursorStabilizer()
    private val stabilizedCursorDelta = PointF()
    private val registry = WidgetRegistry()
    private val widgetCaptureRestore = WidgetCaptureRestore()
    private val videoQueueRepository = VideoQueueRepository(context)
    /** Every text field and text area of the app is edited through this controller. */
    private val textEditing = TextEditingController(
        toContentSpace = ::screenToContentCoordinates,
        requestPhoneClipboard = { host?.requestClipboard() },
        notifyFocusChanged = { onTextZoneFocusChanged() },
        notifyTransientChange = ::notifyTransientContentChanged,
        notificationGeneration = ::contentNotificationGeneration,
        runClassifiedInput = { action, changed -> runClassifiedInput(action, changed) },
    )
    private val lifecycleController = WidgetLifecycleController(
        runOnUiThread = { action -> post(action) },
        onChanged = {refreshBrightnessRules(); notifyPersistentContentChanged()}, // Opening, closing, fullscreen and minimize can all change what the brightness rules need.
        registry = registry,
        onMutationStarted = { lifecycleMutationInProgress = true },
        onMutationFinished = { lifecycleMutationInProgress = false },
        textZoneHost = textEditing,
        changeListener = object : WidgetChangeListener {
            override fun onTransientStateChanged() = notifyTransientContentChanged()
            override fun onPersistentStateChanged() = notifyPersistentContentChanged()
        },
        onWidgetRemoving = ::onWidgetRemoving,
        viewportSize = { width.toFloat() to height.toFloat() },
        displayState = { displayState },
    )
    private val assistantUiDispatcher = UiThreadDispatcher { action -> if (Looper.myLooper() == Looper.getMainLooper()) {action(); true}
    else {post(Runnable(action))}}
    private fun browserWidgetFactory(rootView: ViewGroup) = BrowserWidgetFactory(
        context = context,
        rootLayout = rootView,
        containerView = this,
    )
    private fun videoWidgetFactory(rootView: ViewGroup) = VideoWidgetFactory(
        context = context,
        rootLayout = rootView,
        containerView = this,
        queueRepository = videoQueueRepository,
        onPlaybackVisibilityChanged = { sourceId, active ->
            setBinocularMediaSourceActive(sourceId, active)
        },
    )
    private val locationWeatherBinding by lazy {
        LocationWeatherBinding(
            currentWidget = { locationWidget },
            temperatureUnit = { unitSettings.effectiveTemperatureUnit() },
            toggleTemperatureUnit = { unitSettings.toggleTemperatureOverride() },
            notifyTransientChange = ::notifyTransientContentChanged,
        )
    }
    private val newsFeatures by lazy {
        NewsFeatureController(
            ui = assistantUiDispatcher,
            currentWidget = { newsWidget },
            currentProvider = { dataProvider },
            withTransientChanges = { action -> withTransientChangeBatch(action) },
            notifyTransientChange = ::notifyTransientContentChanged,
        )
    }
    private val financeFeatures by lazy {
        FinanceFeatureController(
            ui = assistantUiDispatcher,
            currentWidget = { financeWidget },
            currentProvider = { dataProvider },
            runPersistentUpdate = { action -> runPersistentMutation( action = action, changed = { result -> result.isSuccess } ) },
            notifyTransientChange = ::notifyTransientContentChanged,
            stableWidgetId = ::widgetStableId,
        )
    }

    private val threeDofOverlay = ThreeDofOverlay()
    private val dashboardTransform = DashboardTransform()
    private val smartAlignment = SmartAlignmentHelper(registry)
    private val smartAlignmentRenderer = SmartAlignmentRenderer()
    private val unitSettings = UnitSettings(context)

    // ==================== 3DOF State ====================

    // Whether 3DOF mode is currently active
    private var is3DofEnabled = false

    // ==================== Menus & Overlays ====================
    
    private var contextMenu: ContextMenu? = null
    private var contextMenuDispatchDepth = 0
    private var closeConfirmationPopup: CloseConfirmationPopup? = null
    private var layoutDeleteConfirmationPopup: WidgetLayoutDeleteConfirmationPopup? = null
    private val pageAgentApprovalOverlay =
        PageAgentApprovalOverlay(::notifyPersistentContentChanged)

    private val commandRegistry by lazy {
        AppCommandRegistry { layoutLibrary.names() }
    }
    private val commandExecutor by lazy {
        AppCommandExecutor(
            registry = commandRegistry,
            capabilities = commandCapabilities,
            uiDispatcher = AwaitingCommandUiDispatcher(
                isUiThread = { Looper.myLooper() == Looper.getMainLooper() },
                postToUiThread = { action -> post(action) },
            ),
        )
    }

    private val commandCapabilities = object : AppCommandCapabilities {
        override fun isAvailable(requirement: CommandAvailability): Boolean = when (requirement) {
            CommandAvailability.ALWAYS -> true
            CommandAvailability.APP_READY -> isReady()
            CommandAvailability.TEXT_FOCUSED -> textEditing.hasFocus || focusedBrowserWidget != null
            CommandAvailability.BROWSER_AVAILABLE -> focusedBrowserWidget != null || fullscreenAs<BrowserWidget>() != null || isReady()
        }

        override fun toggleWidget(widgetId: CommandWidgetId) =
            lifecycleController.toggle(WidgetDescriptors.forCommand(widgetId).type)

        override fun setWidgetVisible(widgetId: CommandWidgetId, visible: Boolean) {
            val type = WidgetDescriptors.forCommand(widgetId).type
            if (visible) lifecycleController.show(type) else lifecycleController.hide(type)
        }

        override fun createTextWidget() {
            createTextBox(pendingCreateX, pendingCreateY)
        }
        override fun createBrowserWidget() = createBrowserWidget(pendingCreateX, pendingCreateY)
        override fun openYouTubeHistory() = createYouTubeBrowserWidget()
        override fun loadLayout(name: String): Boolean = loadCommandLayout(name)
        override fun toggleLayoutLock() = setWidgetLayoutLocked(!isWidgetLayoutLocked)
        override fun saveLayout() = layoutManager.saveCurrentLayout()
        override fun saveLayoutAs() = layoutManager.showSaveLayoutAsPrompt()
        override fun openFilePicker() { host?.requestFilePicker() }
        override fun openSettings() = settingsController.toggleDashboardSettings(width.toFloat(), height.toFloat())
        override fun requestClose() = triggerCloseAppConfirmation()
        override fun setBrightness(value: Float) {
            settingsController.setBrightness(value)
            notifyTransientContentChanged()
        }
        override fun scroll(direction: CommandScrollDirection, amount: Float) =
            scrollWidgetAtCursor(cursorX, cursorY, if (direction == CommandScrollDirection.UP) -amount else amount)
        override fun openUrl(url: String) = openBrowserAddress(url)
        override fun enterText(text: String) = onKeyPress(text)
        // Each mutation owns its classified notification; a generic command completion hook
        // would duplicate lifecycle, widget, and transient signals.
        override fun commandFinished() = Unit
    }

    private val closeAppHoverControl = CloseAppHoverControl {
        executeCommand(commandRegistry.invocation(commandRegistry.closeApp))
    }
    private val assistantVoiceHoverControl = AssistantVoiceHoverControl {
        host?.toggleAssistantVoice()
    }

    // Mutable list of lemon shortcuts; loaded lazily from disk on first access so the
    // file read doesn't happen during construction (context.getExternalFilesDir is fine
    // but disk I/O during init is best avoided).
    private var lemonShortcuts: MutableList<AppCommandInvocation>? = null
    private fun ensureLemonShortcuts(): MutableList<AppCommandInvocation> {
        val cached = lemonShortcuts
        if (cached != null) return cached
        val loaded = ShortcutsStore.load(context, commandRegistry).toMutableList()
        lemonShortcuts = loaded
        return loaded
    }

    private val lemonHoverControl: LemonHoverControl = LemonHoverControl(
        commandRegistry = commandRegistry,
        shortcutsProvider = { ensureLemonShortcuts() },
        onActionSelected = { invocation ->
            userInputActivity?.onUserInput(UserInputKind.SHORTCUT)
            if (invocation.commandId == commandRegistry.createText.id) {
                pendingCreateX = (width * 0.5f) - (width * DEFAULT_TEXTBOX_WIDTH_PERCENT) / 2f
                pendingCreateY = (height * 0.5f) - (height * DEFAULT_TEXTBOX_HEIGHT_PERCENT) / 2f
            } else if (invocation.commandId == commandRegistry.createBrowser.id) {
                pendingCreateX = 0f
                pendingCreateY = 0f
            }
            executeCommand(invocation)
            host?.requestCursorCenter()
        },
        invalidate = { invalidateHoverVisuals() }
    )

    private val hoverControls: List<BaseHoverControl> = listOf(
        closeAppHoverControl,
        assistantVoiceHoverControl,
        lemonHoverControl
    )

    /** Hover control currently capturing the cursor (between down and up). */
    private var capturedHoverControl: BaseHoverControl? = null
    private var hoverConfirmTapGuardUntilMs: Long = 0L

    // ==================== Assistant Actions ====================
    private val assistantUiContextCapture by lazy {AssistantUiContextCapture(registry = registry, screenSize = {width to height}, activeLayoutName = {layoutManager.activeLayoutName}, focusedWidget = {focusedWidget ?: focusedBrowserWidget})}

    private val textAssistantActions by lazy {
        TextAssistantActions(
            ui = assistantUiDispatcher,
            findTextWidget = { id -> findWidgetByStableId(id) as? TextBoxWidget },
            createTextWidget = ::createAssistantTextWidget,
            clearEditing = { widget ->
                widget.hideKeyboard()
                textEditing.release(widget.textZone)
                setFocusedWidget(null)
                hideAssistantIme()
            },
            presentWidget = { widget, spec, created -> presentWidget(widget, spec, created) },
            removePresentationTarget = ::removePresentationTarget,
        )
    }

    private val mapAssistantActions by lazy {
        MapAssistantActions(
            ui = assistantUiDispatcher,
            showMap = {
                lifecycleController.show(WidgetTypeId.MAP)
                mapWidget
            },
            currentMap = { mapWidget },
            present = { widget, spec -> presentWidget(widget, spec) },
            dismissPresentation = ::dismissMapPresentation,
            withTransientChanges = { action -> withTransientChangeBatch(action) },
            notifyTransientChange = ::notifyTransientContentChanged,
            notifyPersistentChange = ::notifyPersistentContentChanged,
        )
    }

    // ==================== Widget References ====================
    
    // Status bar widget - delegated to registry
    val statusBarWidget: StatusBarWidget?
        get() = registry.statusBarWidget

    // Location widget - delegated to registry
    val locationWidget: LocationWidget?
        get() = registry.locationWidget

    val mapWidget: MapWidget?
        get() = registry.mapWidget

    val calendarWidget: CalendarWidget?
        get() = registry.calendarWidget

    // Pending data for location widget
    private var pendingMapLocation: android.location.Location? = null
    private var pendingMapLocationLabel: String = ""
    private var pendingMapCountryCode: String = ""
    private var pendingCalendarSnapshot: com.everyday.shared.sync.CalendarSnapshot? = null

    // Finance widget - delegated to registry
    val financeWidget: FinanceWidget?
        get() = registry.financeWidget

    // News widget - delegated to registry
    val newsWidget: NewsWidget?
        get() = registry.newsWidget

    val notificationsWidget: NotificationsWidget?
        get() = registry.notificationsWidget

    // Speedometer widget - delegated to registry
    val speedometerWidget: SpeedometerWidget?
        get() = registry.speedometerWidget

    // Subtitle widget - delegated to registry
    val subtitleWidget: SubtitleWidget?
        get() = registry.subtitleWidget

    // Pending speed data for speedometer widget
    private var pendingSpeedKmh = 0f
    private var pendingSpeedQualityOk = false
    private var pendingSpeedMoving = false

    // Screen mirror widget - delegated to registry
    val screenMirrorWidget: ScreenMirrorWidget?
        get() = registry.screenMirrorWidget

    // Settings overlays and persisted app-level settings
    private val settingsController = SettingsController(
        resources = resources,
        defaultSpeedVisibilityThresholdKmh = DEFAULT_SPEED_VISIBILITY_THRESHOLD_KMH,
        notifyTransientContentChanged = ::notifyTransientContentChanged,
        notifyPersistentContentChanged = ::notifyPersistentContentChanged,
        setSpeedometerForceVisibleWhenIdle = { forceVisible ->
            speedometerWidget?.setForceVisibleWhenIdle(forceVisible) == true
        },
        isSmartAlignmentEnabled = { smartAlignment.enabled },
        setSmartAlignmentEnabled = { enabled -> smartAlignment.enabled = enabled },
        notifyBrightnessChanged = { value -> displaySettingsHandler?.applyBrightness(value) },
        executeBrightnessCommand = { value ->
            executeCommand(
                AppCommandInvocation(
                    commandRegistry.setBrightness.id,
                    AppCommandArguments.Brightness(value)
                )
            )
        },
        notifyAdaptiveBrightnessChanged = { enabled ->
            displaySettingsHandler?.applyAdaptiveBrightness(
                enabled
            )
        },
        notifyHeadUpTimeChanged = { ms -> displaySettingsHandler?.applyHeadUpTime(ms) },
        notifyWakeDurationChanged = { ms -> displaySettingsHandler?.applyWakeDuration(ms) },
        notifyAngleThresholdChanged = { degrees ->
            displaySettingsHandler?.applyHeadUpAngleThreshold(
                degrees
            )
        },
        notifyHeadsUpEnabledChanged = { enabled ->
            displaySettingsHandler?.applyHeadsUpEnabled(
                enabled
            )
        },
        notifySmartAlignmentChanged = { enabled ->
            displaySettingsHandler?.applySmartAlignment(
                enabled
            )
        },
        notifySpeedVisibilityThresholdChanged = { threshold ->
            displaySettingsHandler?.applySpeedVisibilityThreshold(
                threshold
            )
        },
        notifyUnitSystemModeChanged = { mode -> unitSettings.setGlobalMode(mode) },
        connectGoogle = { googleAccountHandler?.connectGoogleAccount() },
        grantCalendarAccess = { googleAccountHandler?.grantCalendarAccess() },
        disconnectGoogle = { googleAccountHandler?.disconnectGoogleAccount() },
        retryGoogleAuth = { googleAccountHandler?.retryGoogleAuthentication() },
        commandRegistry = commandRegistry,
        shortcutsProvider = { ensureLemonShortcuts().toList() },
        onShortcutsChanged = { updated -> applyShortcuts(updated) }
    )
    private var layoutNamePrompt: WidgetLayoutNamePrompt? = null
    private var layoutOverlayNotificationEmitted = false
    private var layoutNameSaveInProgress = false
    private var layoutDeleteInProgress = false
    private val layoutLibrary = LayoutLibrary(context)
    private val layoutManager = WidgetLayoutManager(
        layouts = layoutLibrary,
        captureDashboard = ::captureWidgetDashboardWithoutLayoutName,
        showNamePrompt = { suggestedName ->
            dismissMenu()
            layoutNamePrompt?.show(suggestedName)
        },
        showNameError = { error -> layoutNamePrompt?.showError(error) },
        dismissNamePrompt = { layoutNamePrompt?.dismiss() },
        showDeleteConfirmation = { name -> layoutDeleteConfirmationPopup?.show(name) },
        notifyTransientContentChanged = {
            layoutOverlayNotificationEmitted = true
            notifyTransientContentChanged()
        },
        notifyPersistentContentChanged = {
            layoutOverlayNotificationEmitted = true
            notifyPersistentContentChanged()
        }
    )

    var displaySettingsHandler: DisplaySettingsHandler? = null
    var googleAccountHandler: GoogleAccountHandler? = null

    // Access to widget lists via registry
    private val widgets: List<TextBoxWidget>
        get() = registry.textWidgets
    
    private val browserWidgets: List<BrowserWidget>
        get() = registry.browserWidgets

    private val imageWidgets: List<ImageWidget>
        get() = registry.imageWidgets

    private val videoWidgets: List<VideoWidget>
        get() = registry.videoWidgets

    private val pdfWidgets: List<PdfWidget>
        get() = registry.pdfWidgets

    private val fileBrowserWidgets: List<FileBrowserWidget>
        get() = registry.fileBrowserWidgets

    // Text widget whose text area is being edited
    private val focusedWidget: TextBoxWidget?
        get() = textEditing.focusedZone?.let { zone -> widgets.firstOrNull { it.textZone === zone } }

    // Browser receiving keys for its web page (address bar keys go through the text editing controller)
    private var focusedBrowserWidget: BrowserWidget? = null

    // Widget being dragged
    private var activeWidget: BaseWidget? = null
    private var isWidgetLayoutLocked = false

    // The single widget that currently owns hover — and therefore interaction. At most one
    // widget is hovered at a time; a widget can only *become* hovered when no other widget is
    // already hovered under the cursor. See resolveHoveredWidget for the selection rules.
    private var hoveredWidget: BaseWidget? = null

    // Drag state flags. All widgets share activeWidget; finance tile drag remains internal.
    private var financeTileDragging = false
    private var cursorPressed = false
    private var activeBrowserTouchWidget: BrowserWidget? = null
    private var suppressBrowserContentTapWidget: BrowserWidget? = null
    private var suppressBrowserContentTapUntilMs: Long = 0L

    // ==================== Cursor State ====================
    
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastCursorX = 0f
    private var lastCursorY = 0f

    // ==================== Long-press Detection ====================
    
    private val handler = Handler(Looper.getMainLooper())
    private var presentationProgress: PresentationProgress? = null
    private val presentationProgressBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(170, 0, 0, 0)
    }
    private val presentationProgressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val presentationController by lazy {
        PresentationController(
            scheduler = HandlerPresentationScheduler(handler),
            nowElapsedMs = { SystemClock.elapsedRealtime() },
            delegate = object : PresentationLifecycleDelegate {
                override fun onPresentationStarted(session: PresentationSession) {
                    applyPresentation(session)
                }

                override fun onRestorePrevious(session: PresentationSession) {
                    restorePresentationPrevious(session)
                }

                override fun onLeaveWindowed(session: PresentationSession) {
                    leavePresentationWindowed(session)
                }

                override fun onClose(targetId: String) {
                    closePresentationTarget(targetId)
                }

                override fun onProgressChanged(progress: PresentationProgress?) {
                    presentationProgress = progress
                    notifyTransientContentChanged()
                }
            }
        )
    }
    private var latestNotificationsSnapshot = PhoneNotificationsSnapshot(
        items = emptyList(),
        listenerEnabled = false,
        capturedAtMs = 0L
    )
    private var longPressRunnable: Runnable? = null
    private var longPressStartX = 0f
    private var longPressStartY = 0f
    private var pendingDragWidget: BaseWidget? = null
    private var pendingDragFinanceTile = false
    private var pendingDragIsResize = false
    private var interactionZOrderChanged = false
    private var interactionPersistentNotified = false
    private var pendingTextSelection = false
    private var pendingCreateX = 0f
    private var pendingCreateY = 0f

    // ==================== Fullscreen Tracking ====================

    /**
     * The fullscreen widget when it is a [T], otherwise null. WidgetRegistry is the single
     * fullscreen owner; use this only where the widget type genuinely matters.
     */
    private inline fun <reified T : BaseWidget> fullscreenAs(): T? = registry.fullscreenWidget as? T

    private var browserRoutingCoordinator: BrowserRoutingCoordinator? = null
    private val webSearchHandler = WebSearchSemanticToolHandler(DuckDuckGoWebSearchProvider())

    private val browserLifecycle by lazy {
        WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[WidgetTypeId.BROWSER],
            widgetType = BrowserWidget::class,
            stateType = DashboardPersistence.BrowserWidgetState::class,
            stateCodec = WidgetDocument.codecs[WidgetTypeId.BROWSER],
            capture = widgetCaptureRestore::getBrowserWidgetState,
            restore = ::restoreBrowserWidget,
            configure = ::configureBrowserWidget,
            beforeRemove = { widget ->
                textEditing.release(widget.addressZone)
                if (focusedBrowserWidget === widget) setFocusedWidget(null)
            },
            teardown = ::destroyWidget
        )
    }
    private val textLifecycle by lazy {
        WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[WidgetTypeId.TEXT],
            widgetType = TextBoxWidget::class,
            stateType = DashboardPersistence.TextWidgetState::class,
            stateCodec = WidgetDocument.codecs[WidgetTypeId.TEXT],
            capture = widgetCaptureRestore::getTextWidgetState,
            restore = ::restoreTextWidget,
            beforeRemove = { widget ->
                if (focusedWidget === widget) setFocusedWidget(null)
            }
        )
    }
    private val imageLifecycle by lazy {
        WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[WidgetTypeId.IMAGE],
            widgetType = ImageWidget::class,
            stateType = DashboardPersistence.ImageWidgetState::class,
            stateCodec = WidgetDocument.codecs[WidgetTypeId.IMAGE],
            capture = widgetCaptureRestore::getImageWidgetState,
            restore = ::restoreImageWidget,
            beforeRemove = { widget ->
                val path = widget.getImagePath()
                val hasSibling = imageWidgets.any { it !== widget && it.getImagePath() == path }
                if (!hasSibling) ImageWidgetStorage.deleteIfManaged(context, path)
            }
        )
    }
    private val videoLifecycle by lazy {
        WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[WidgetTypeId.VIDEO],
            widgetType = VideoWidget::class,
            stateType = DashboardPersistence.VideoWidgetState::class,
            stateCodec = WidgetDocument.codecs[WidgetTypeId.VIDEO],
            capture = widgetCaptureRestore::getVideoWidgetState,
            restore = ::restoreVideoWidget,
            teardown = ::destroyWidget,
            onFullscreenChanged = ::handleVideoWidgetFullscreenSideEffects
        )
    }
    private val pdfLifecycle by lazy {
        WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[WidgetTypeId.PDF],
            widgetType = PdfWidget::class,
            stateType = DashboardPersistence.PdfWidgetState::class,
            stateCodec = WidgetDocument.codecs[WidgetTypeId.PDF],
            capture = widgetCaptureRestore::getPdfWidgetState,
            restore = ::restorePdfWidget,
            teardown = ::destroyWidget
        )
    }
    private val filesLifecycle by lazy {
        WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[WidgetTypeId.FILES],
            widgetType = FileBrowserWidget::class,
            stateType = Unit::class
        )
    }

    // ==================== Phone Clipboard to forward to relevant editors ====================

    private val phoneClipboard: String? get() = textEditing.phoneClipboard

    // ==================== Selection Tracking ====================

    private var isLongPressSelectionMode = false
    private var pendingFormattingMenuDrag = false

    // Scrollbar dragging state
    private var isScrollbarDragActive = false
    private var scrollbarDragWidget: TextBoxWidget? = null
    
    // Two-finger scroll mode
    private var isTwoFingerScrollMode = false

    // ==================== Host contracts ====================

    var contentObserver: WidgetContentObserver? = null
    var host: WidgetHost? = null
    var dataProvider: WidgetDataProvider? = null
    private var phoneRouteTransport: GlassesRouteTransport? = null
    private var phoneRouteConnected = false
    var userInputActivity: UserInputActivity? = null

    private var isCursorVisible = false
    private var displayState: WakeSleepManager.DisplayState = WakeSleepManager.DisplayState.WAKE

    // Flag to suppress aggressive invalidation during display state transitions
    private var isTransitioningDisplayState = false
    private var isHostPaused = false

    // Flag to control whether default singleton widgets should be created.
    // Set to false after restoring state on a returning user to prevent recreating closed widgets.
    private var shouldCreateDefaultSingletons = true

    /**
     * True between learning that a saved dashboard exists and actually applying it.
     * While set, saving is suppressed so a not-yet-restored container cannot overwrite
     * the stored document. See [setIsReturningUser].
     */
    private var isAwaitingRestore = false

    /**
     * True while a stored dashboard is known to exist but has not been applied yet.
     * Anything that persists the container must refuse to write while this holds.
     */
    val isAwaitingDashboardRestore: Boolean get() = isAwaitingRestore
    private var lifecycleMutationInProgress = false
    private val contentChanges = DashboardChangeCoordinator(
        repaint              = {if (Looper.myLooper() == Looper.getMainLooper()) invalidate() else postInvalidate()},
        transient            = {contentObserver?.onTransientContentChanged()},
        persistent           = {if (cursorPressed) interactionPersistentNotified = true; contentObserver?.onPersistentContentChanged()}, // This is gesture bookkeeping, so it remains in WidgetContainer.
        restored             = {contentObserver?.onDashboardRestored()},
        notificationsBlocked = { lifecycleMutationInProgress },
    )
    init {
        setWillNotDraw(false)
        registerWidgetLifecycles()
        setupWidgetCaptureRestoreCallbacks()
        unitSettings.onChanged = { applyEffectiveUnitSettings() } // Callers own the classified notification for unit changes; applying the derived widget presentation here must not emit a second transient callback.
        applyEffectiveUnitSettings()
    }

    override fun onDetachedFromWindow() {
        presentationController.dismiss()
        super.onDetachedFromWindow()
    }

    private fun registerWidgetLifecycles() {
        lifecycleController.register(browserLifecycle)
        lifecycleController.register(textLifecycle)
        lifecycleController.register(imageLifecycle)
        lifecycleController.register(videoLifecycle)
        lifecycleController.register(pdfLifecycle)
        lifecycleController.register(filesLifecycle)

        fun <W : BaseWidget, S : Any> bindSingleton(
            type: WidgetTypeId,
            widgetType: KClass<W>,
            stateType: KClass<S>,
            current: () -> W?,
            createWidget: () -> W,
            restoreWidget: (S) -> W,
            captureState: (W) -> S?,
            reopenWidget: (S) -> W = restoreWidget,
            configure: (W) -> Unit = {},
            beforeRemove: (W) -> Unit = {},
            teardown: (W) -> Unit = ::destroyWidget,
            onFullscreen: (W, Boolean) -> Unit = { _, _ -> },
            onMinimize: (W, Boolean) -> Unit = { _, _ -> }
        ) {
            lifecycleController.register(
                WidgetLifecycleRegistration(
                    descriptor = WidgetDescriptors[type],
                    widgetType = widgetType,
                    stateType = stateType,
                    stateCodec = WidgetDocument.codecs[type],
                    current = current,
                    createDefault = createWidget,
                    restore = restoreWidget,
                    reopen = reopenWidget,
                    capture = captureState,
                    configure = configure,
                    beforeRemove = beforeRemove,
                    teardown = teardown,
                    onFullscreenChanged = onFullscreen,
                    onMinimizeChanged = onMinimize
                )
            )
        }

        bindSingleton(
            WidgetTypeId.STATUS, StatusBarWidget::class, DashboardPersistence.StatusBarState::class,
            { statusBarWidget }, ::createStatusBarPresence, ::applyStatusBarState,
            { widgetCaptureRestore.getStatusBarState(it) })
        bindSingleton(
            WidgetTypeId.LOCATION, LocationWidget::class, DashboardPersistence.LocationWidgetState::class,
            { locationWidget }, ::createLocationPresence, ::applyLocationState,
            { widgetCaptureRestore.getLocationWidgetState(it) })
        bindSingleton(
            WidgetTypeId.MAP, MapWidget::class, DashboardPersistence.MapWidgetState::class,
            { mapWidget }, ::createMapPresence, ::applyMapState,
            { widgetCaptureRestore.getMapWidgetState(it) },
            beforeRemove = MapWidget::release,
            onFullscreen = { widget, fullscreen -> if (!fullscreen) widget.enforceWindowedMode() })
        bindSingleton(
            WidgetTypeId.CALENDAR, CalendarWidget::class, DashboardPersistence.CalendarWidgetState::class,
            { calendarWidget }, ::createCalendarPresence, ::applyCalendarState,
            { widgetCaptureRestore.getCalendarWidgetState(it) })
        bindSingleton(
            WidgetTypeId.FINANCE, FinanceWidget::class, DashboardPersistence.FinanceWidgetState::class,
            { financeWidget }, ::createFinancePresence, ::applyFinanceState,
            { widgetCaptureRestore.getFinanceWidgetState(it) },
            onMinimize = { widget, minimized -> financeFeatures.onMinimizeChanged(widget, minimized) })
        bindSingleton(
            WidgetTypeId.NEWS, NewsWidget::class, DashboardPersistence.NewsWidgetState::class,
            { newsWidget }, ::createNewsPresence, ::applyNewsState,
            { widgetCaptureRestore.getNewsWidgetState(it) })
        bindSingleton(
            WidgetTypeId.NOTIFICATIONS, NotificationsWidget::class, DashboardPersistence.NotificationsWidgetState::class,
            { notificationsWidget }, ::createNotificationsPresence, ::applyNotificationsState,
            { widgetCaptureRestore.getNotificationsWidgetState(it) })
        bindSingleton(
            WidgetTypeId.SPEEDOMETER, SpeedometerWidget::class, DashboardPersistence.SpeedometerWidgetState::class,
            { speedometerWidget }, ::createSpeedometerPresence, ::applySpeedometerState,
            { widgetCaptureRestore.getSpeedometerWidgetState(it) },
            beforeRemove = { settingsController.dismissSpeedometerSettingsMenu() })
        bindSingleton(
            WidgetTypeId.SUBTITLE, SubtitleWidget::class, DashboardPersistence.SubtitleWidgetState::class,
            { subtitleWidget }, ::createSubtitlePresence, ::applySubtitleState,
            { widgetCaptureRestore.getSubtitleWidgetState(it) },
            // Reopening a closed subtitle widget resumes capture; loading a dashboard does not.
            reopenWidget = { state -> applySubtitleState(state).also { it.setCaptureEnabled(true) } })
        bindSingleton(
            WidgetTypeId.MIRROR, ScreenMirrorWidget::class, DashboardPersistence.MirrorWidgetState::class,
            { screenMirrorWidget }, ::createMirrorPresence, ::applyMirrorState,
            { widgetCaptureRestore.getMirrorWidgetState(it) },
            configure = ::configureScreenMirrorWidget)

        lifecycleController.verifyComplete()
    }

    private fun setupWidgetCaptureRestoreCallbacks() {
        widgetCaptureRestore.listener = object : WidgetPersistenceListener {
            override fun onWidgetFocusChanged(widget: BaseWidget?, focused: Boolean) {
                if (focused && widget != null) {
                    setFocusedWidget(widget)
                } else if (!focused) {
                    if (widget is BrowserWidget && focusedBrowserWidget == widget) {
                        setFocusedWidget(null)
                    }
                }
            }

            override fun onBrowserKeyboardRequested(show: Boolean) {
                host?.requestBrowserKeyboard(show)
            }
        }
    }

    // ==================== Public State Queries ====================

    fun hasFullscreenWidget(): Boolean = registry.fullscreenWidget != null

    fun setDisplayState(state: WakeSleepManager.DisplayState) {
        if (displayState != state) {
            withTransientChangeBatch {
                // Set transition flag to prevent aggressive invalidation cascades
                // which can cause power spikes and display flashes on battery power
                isTransitioningDisplayState = true

                displayState = state
                Log.d(TAG, "Display state set to: $state")

                // Each widget decides whether it is visible in this state (BaseWidget.isVisibleIn).
                // Browsers, videos and the mirror start or stop their native views accordingly.
                registry.forEachWidget { widget ->
                    widget.glassesDisplayState = state
                    widget.refreshDisplayVisibility()
                }
                refreshBrightnessRules()
                syncWidgetLifecycleForDisplayState()

                // Clear transition flag and do a single, controlled invalidation
                isTransitioningDisplayState = false

                // Display visibility and wake/sleep transitions are transient state: repaint once
                // and let the host refresh the binocular output without touching the dashboard file.
                notifyTransientContentChanged()
            }
        }
    }

    fun onAppResumed() {
        isHostPaused = false
        syncWidgetLifecycleForDisplayState()
    }

    fun onAppPaused() {
        isHostPaused = true
        registry.forEachWidget { it.onPause() }
    }

    private fun syncWidgetLifecycleForDisplayState() {
        if (displayState == WakeSleepManager.DisplayState.OFF) {
            registry.forEachWidget { it.onPause() }
        } else if (!isHostPaused) {
            registry.forEachWidget { it.onResume() }
        }
    }

    fun onHeadUpWake() {
        Log.d(TAG, "Head-up wake -> advance news item")
        newsWidget?.let { news ->
            val beforeIndex = news.getSelectedIndex()
            val persistedChange = runPersistentMutation(
                action = {
                    news.showNextItemOnHeadUpWake()
                    news.getSelectedIndex() != beforeIndex
                },
                changed = { it }
            )
            if (!persistedChange) notifyTransientContentChanged()
        }
    }

    fun hasPinnedWidgets(): Boolean = registry.hasPinnedWidgets()
    fun isDisplayAwake(): Boolean = displayState == WakeSleepManager.DisplayState.WAKE

    val activePresentationSession: PresentationSession?
        get() = presentationController.activeSession

    /**
     * Present an already-created widget for a bounded period. Set [createdByPresentation] when
     * the caller created the widget solely for this presentation; RESTORE_PREVIOUS then closes it.
     */
    fun presentWidget(
        widget: BaseWidget,
        spec: PresentationSpec,
        createdByPresentation: Boolean = false
    ): Boolean {
        val targetId = widgetStableId(widget)
        val session = presentationController.start(targetId, spec) {
            if (!registry.allWidgets().any { it === widget }) {
                null
            } else {
                capturePresentationSnapshot(widget, existedBeforePresentation = !createdByPresentation)
            }
        }
        return session != null
    }

    fun cancelPresentationForUserInput(): Boolean = presentationController.cancelForUserInput()

    fun dismissPresentation(): Boolean = presentationController.dismiss()

    /** Stable ID used by assistant context and temporary presentation targeting. */
    fun widgetStableId(widget: BaseWidget): String = stableWidgetId(widget)

    /**
     * Returns the requested browser instance without falling back to another browser.
     * The Page Agent caller should invoke this at run start and retain the returned adapter.
     */
    fun findBrowserWidgetByStableId(widgetId: String): BrowserWidget? =
        browserWidgets.firstOrNull { stableWidgetId(it) == widgetId }

    /**
     * Atomically capture the browser occupying page/video fullscreen and bind its current tab
     * and WebView. This method is called on the UI thread at voice-audio submission time.
     */
    fun bindFullscreenPageAgentBrowser(): PageAgentBrowserAdapter? {
        val widget = fullscreenAs<BrowserWidget>()
            ?: browserWidgets.firstOrNull { it.isInVideoFullscreen() }
            ?: return null
        val widgetId = stableWidgetId(widget)
        return BrowserWidgetPageAgentAdapter.bind(widgetId, widget)
    }

    /** Bind an existing browser for a normal assistant browser task. */
    fun bindPageAgentBrowserForTask(widgetId: String?): PageAgentBrowserAdapter? {
        val normalizedId = widgetId?.trim()?.takeIf { it.isNotBlank() }
        val widget = if (normalizedId != null) {
            findBrowserWidgetByStableId(normalizedId)
        } else {
            focusedBrowserWidget?.takeIf { !it.isMinimized }
                ?: browserWidgets.firstOrNull { !it.isMinimized }
        } ?: return null
        val stableId = stableWidgetId(widget)
        return BrowserWidgetPageAgentAdapter.bind(stableId, widget)
    }

    private fun stableWidgetId(widget: BaseWidget): String = registry.stableId(widget)
    private fun findWidgetByStableId(targetId: String): BaseWidget? =
        registry.findByStableId(targetId)

    private fun capturePresentationSnapshot(
        widget: BaseWidget,
        existedBeforePresentation: Boolean
    ): PresentationSnapshot {
        val rendered = widget.getBounds()
        val renderedBounds =
            PresentationBounds(rendered.left, rendered.top, rendered.right, rendered.bottom)
        val windowed = if (widget.isMinimized) {
            val width = widget.savedMinWidth.takeIf { it > 0f } ?: rendered.width()
            val height = widget.savedMinHeight.takeIf { it > 0f } ?: rendered.height()
            PresentationBounds(
                widget.savedMinX,
                widget.savedMinY,
                widget.savedMinX + width,
                widget.savedMinY + height
            )
        } else {
            val bounds = widget.getPersistenceBounds()
            PresentationBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        return PresentationSnapshot(
            existedBeforePresentation = existedBeforePresentation,
            visible = !widget.isMinimized,
            bounds = renderedBounds,
            fullscreen = widget.isFullscreen,
            focused = focusedWidget === widget || focusedBrowserWidget === widget,
            zOrder = widget.zOrder,
            windowedBounds = windowed,
            minimized = widget.isMinimized,
            wasFullscreenBeforeMinimize = widget.wasFullscreenBeforeMinimize,
            pinned = widget.isPinned
        )
    }

    private fun applyPresentation(session: PresentationSession) {
        val widget = findWidgetByStableId(session.targetId) ?: return
        withContentChangeSuppressed {
            if (widget.isMinimized) widget.toggleMinimize()
            when (session.spec.mode) {
                PresentationMode.FULLSCREEN -> if (!widget.isFullscreen) widget.toggleFullscreen()
                PresentationMode.WINDOWED -> if (widget.isFullscreen) widget.toggleFullscreen()
            }
            // Presentation z-order is temporary and must not rewrite the dashboard layout.
            bringWidgetToFront(widget)
        }
        notifyTransientContentChanged()
    }

    private fun normalizePresentationWidget(widget: BaseWidget) {
        if (widget.isFullscreen) widget.toggleFullscreen()
        if (widget.isMinimized) widget.toggleMinimize()
        // A minimized widget may re-enter fullscreen as part of its normal restore path.
        if (widget.isFullscreen) widget.toggleFullscreen()
    }

    private fun setWidgetBounds(widget: BaseWidget, bounds: PresentationBounds) {
        widget.x = bounds.left
        widget.y = bounds.top
        widget.widgetWidth = bounds.width
        widget.widgetHeight = bounds.height
    }

    private fun restorePresentationPrevious(session: PresentationSession) {
        val target = findWidgetByStableId(session.targetId)
        if (!session.snapshot.existedBeforePresentation) {
            target?.let {
                removePresentationTarget(it)
                notifyTransientContentChanged()
            }
            return
        }
        target ?: return

        withContentChangeSuppressed {
            normalizePresentationWidget(target)
            target.isPinned = session.snapshot.pinned
            if (session.snapshot.minimized) {
                setWidgetBounds(target, session.snapshot.windowedBounds)
                if (session.snapshot.wasFullscreenBeforeMinimize) {
                    target.toggleFullscreen()
                    target.toggleMinimize()
                } else {
                    target.toggleMinimize()
                }
                setWidgetBounds(target, session.snapshot.bounds)
            } else if (session.snapshot.fullscreen) {
                setWidgetBounds(target, session.snapshot.windowedBounds)
                target.toggleFullscreen()
            } else {
                setWidgetBounds(target, session.snapshot.bounds)
                if (!session.snapshot.visible && !target.isMinimized) target.toggleMinimize()
            }

            if (session.snapshot.focused) {
                setFocusedWidget(target)
            } else if (focusedWidget === target || focusedBrowserWidget === target) {
                setFocusedWidget(null)
            }
            registry.restoreZOrder(target, session.snapshot.zOrder, parent as? ViewGroup, this)
            lifecycleController.arrangeMinimizedWidgets()
        }
        notifyTransientContentChanged()
    }

    private fun leavePresentationWindowed(session: PresentationSession) {
        val target = findWidgetByStableId(session.targetId) ?: return
        withContentChangeSuppressed {
            normalizePresentationWidget(target)
            target.isPinned = session.snapshot.pinned
            setWidgetBounds(target, session.snapshot.windowedBounds)
            if (!session.snapshot.focused && (focusedWidget === target || focusedBrowserWidget === target)) {
                setFocusedWidget(null)
            }
            lifecycleController.arrangeMinimizedWidgets()
        }
        notifyTransientContentChanged()
    }

    private fun closePresentationTarget(targetId: String) {
        findWidgetByStableId(targetId)?.let {
            removePresentationTarget(it)
            notifyTransientContentChanged()
        }
    }

    private fun removePresentationTarget(widget: BaseWidget) {
        withContentChangeSuppressed { lifecycleController.close(widget) }
    }

    private fun notifyPresentationTargetRemoved(widget: BaseWidget) {
        presentationController.onTargetRemoved(runCatching { stableWidgetId(widget) }.getOrNull() ?: return)
    }

    /** Shared cleanup for every closed widget, run while it still has its stable ID. */
    private fun onWidgetRemoving(widget: BaseWidget) {
        notifyPresentationTargetRemoved(widget)
        if (activeWidget === widget) activeWidget = null
    }

    private fun requestRepaint() = contentChanges.requestRepaint() /** Requests one local redraw for pixel-only visual state. */
    private fun <T> withContentChangeSuppressed(action: () -> T): T = contentChanges.withNotificationsSuppressed(action) /** Discards callbacks produced by a temporary or restorative compound mutation. */
    /** Reports focus to the host and emits the one classified visual change for that mutation. */
    private fun notifyFocusChanged(persistent: Boolean = false) {
        // The phone keyboard follows text focus, which is not a suppressible dashboard change:
        // focus often changes inside a mutation whose visual callbacks are discarded.
        contentObserver?.onFocusChanged(hasFocusedWidget())
        if (contentChanges.isSuppressed) return
        if (persistent) notifyPersistentContentChanged() else notifyTransientContentChanged()
    }
    private fun notifyTransientContentChanged() = contentChanges.notifyTransient() /** Emits a visible runtime change without scheduling persistence. */
    fun contentNotificationGeneration(): Long = contentChanges.notificationGeneration /** Used by input routing to avoid adding a second notification after a widget callback. */
    fun <T> withTransientChangeBatch(action: () -> T): T = contentChanges.batch(action) /** Coalesces related updates; if one is persistent, that strongest classification wins. */
    private fun <T> runPersistentMutation(changed: (T) -> Boolean = { true }, action: () -> T): T = contentChanges.runPersistentMutation(action = action, changed = changed) /** Perform an action that may change the dashboard, and tell the rest of the app if that change should be saved. */
    private fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean): Boolean = contentChanges.runClassifiedInput(action, changed) /** Classifies handled input as either persistent or transient, never both. */
    private fun notifyPersistentContentChanged() = contentChanges.notifyPersistent() /** Emits a dashboard mutation; the host owns structural refresh and debounced persistence. */
    private fun notifyDashboardRestored() = contentChanges.notifyDashboardRestored() /** Completes a restore with a structural refresh but without requesting a save. */

    /**
     * Hover updates are purely visual state changes on model objects drawn by this view.
     * They should repaint immediately without being treated as structural widget changes.
     */
    private fun invalidateHoverVisuals() {
        requestRepaint()
    }

    private fun notifyMediaFrame(sourceId: String) {
        if (displayState == WakeSleepManager.DisplayState.OFF) return
        requestRepaint()
        contentObserver?.onMediaFrame(sourceId)
    }

    private fun setBinocularMediaSourceActive(sourceId: String, active: Boolean) {
        contentObserver?.onBinocularContentSourceChanged(sourceId, BinocularContentClass.MEDIA, active)
    }

    /** Re-evaluates the brightness rules that widgets request (media cap, browser minimum). */
    private fun refreshBrightnessRules() {
        displaySettingsHandler?.applyMediaBrightnessCap(shouldApplyMediaBrightnessCap())
        displaySettingsHandler?.applyBrowserMinimumBrightness(shouldApplyBrowserMinimumBrightness())
    }

    private fun requestLayoutSwitchBrightness() {
        displaySettingsHandler?.applyLayoutSwitchBrightness()
    }

    private fun configureBrowserWidget(widget: BrowserWidget) {
        widget.listener = object : BrowserWidgetListener {
            override fun onFocusChanged(focused: Boolean) {
                if (focused) setFocusedWidget(widget)
                else if (focusedBrowserWidget == widget) setFocusedWidget(null)
            }
            override fun onKeyboardRequested(show: Boolean) {host?.requestBrowserKeyboard(show)}
        }
        widget.refreshDisplayVisibility()
    }

    private fun configureScreenMirrorWidget(widget: ScreenMirrorWidget) {
        widget.listener = object : MirrorWidgetListener {
            override fun onFrameReady() {if (widget.isBinocularMediaActive()) notifyMediaFrame(widget.binocularSourceId)}
            override fun onMediaStateChanged(active: Boolean) {
                setBinocularMediaSourceActive(widget.binocularSourceId, active)
                // The mirror's media brightness cap follows its live media state.
                refreshBrightnessRules()
            }
            override fun requestMirror(start: Boolean) {host?.requestMirror(start)}
        }
        widget.refreshDisplayVisibility()
    }

    // ==================== Initialization ====================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Update 3DOF overlay dimensions
        threeDofOverlay.setScreenDimensions(w.toFloat(), h.toFloat())

        // Smart alignment helper needs current screen size for snap candidates
        smartAlignment.screenWidth = w.toFloat()
        smartAlignment.screenHeight = h.toFloat()

        updateDefaultHoverControlBounds(w.toFloat(), h.toFloat())
        lemonHoverControl.setScreenSize(w.toFloat(), h.toFloat())

        contextMenu = ContextMenu(w.toFloat(), h.toFloat(), commandRegistry).apply {
            setLayoutLockState(isWidgetLayoutLocked)
            handler = object : ContextMenuHandler {
                override fun submenuItemsFor(item: ContextMenu.MenuItem) = getUpdatedSubmenuItems(item)
                override fun selectItem(item: ContextMenu.MenuItem) = handleMenuItemSelected(item)
                override fun selectSubmenuItem(
                    parent: ContextMenu.MenuItem,
                    item: ContextMenu.SubMenuItem
                ) = handleSubmenuItemDoubleTapped(item)
                override fun onMenuDismissed() {
                    if (contextMenuDispatchDepth == 0) notifyTransientContentChanged()
                }
            }
        }

        textEditing.createMenus(w.toFloat(), h.toFloat())

        closeConfirmationPopup = CloseConfirmationPopup(w.toFloat(), h.toFloat()).apply {
            handler = object : CloseConfirmationHandler {
                override fun confirmClose() { host?.requestClose() }
                override fun onPopupDismissed() = notifyTransientContentChanged()
            }
        }

        layoutDeleteConfirmationPopup = WidgetLayoutDeleteConfirmationPopup(
            w.toFloat(),
            h.toFloat()
        ).apply {
            handler = object : LayoutDeletionHandler {
                override fun deleteLayout(name: String) {
                    layoutDeleteInProgress = true
                    try {
                        layoutManager.deleteLayoutByName(name)
                    } finally {
                        layoutDeleteInProgress = false
                    }
                }
                override fun onPopupDismissed() {
                    if (!layoutDeleteInProgress) {
                        layoutOverlayNotificationEmitted = true
                        notifyTransientContentChanged()
                    }
                }
            }
        }

        settingsController.createSpeedometerSettingsMenu(w.toFloat(), h.toFloat())

        // Initialize the hover-controls layout editor. Constructing it snaps the controls
        // to default grid cells, so this must run after updateDefaultHoverControlBounds.
        settingsController.createHoverControlsLayoutEditor(
            w.toFloat(),
            h.toFloat(),
            hoverControls
        )

        layoutNamePrompt = WidgetLayoutNamePrompt(w.toFloat(), h.toFloat()).apply {
            textZoneHost = textEditing
            handler = object : LayoutNameHandler {
                override fun submitLayoutName(name: String) {
                    layoutNameSaveInProgress = true
                    try {
                        layoutManager.saveLayoutWithName(name)
                    } finally {
                        layoutNameSaveInProgress = false
                    }
                }
                override fun onPromptDismissed() {
                    if (layoutNameSaveInProgress) {
                        // The save callback below owns the cumulative visual update. Keep the
                        // host focus state current without emitting a second transient signal.
                        contentObserver?.onFocusChanged(hasFocusedWidget())
                    } else {
                        layoutOverlayNotificationEmitted = true
                        notifyFocusChanged()
                    }
                }
            }
        }

        // Only create the built-in Standard layout on first run.
        // For returning users, the restoration code will create widgets based on saved state.
        if (shouldCreateDefaultSingletons) {
            applyDefaultLayoutReplacingCurrent()
        }

        notificationsWidget?.setScreenHeight(h.toFloat())
        updateFullscreenBounds()
        Log.d(TAG, "Container sized: ${w}x${h}")
    }

    private fun updateDefaultHoverControlBounds(containerWidth: Float, containerHeight: Float) {
        // Initial fallback positions in case the layout editor hasn't been created yet.
        // The editor takes over and snaps these to grid centers as soon as it is built.
        closeAppHoverControl.setBounds(
            containerWidth - CloseAppHoverControl.DEFAULT_SIZE - CloseAppHoverControl.DEFAULT_MARGIN,
            CloseAppHoverControl.DEFAULT_MARGIN,
            CloseAppHoverControl.DEFAULT_SIZE
        )
        assistantVoiceHoverControl.setBounds(
            containerWidth - AssistantVoiceHoverControl.DEFAULT_SIZE,
            containerHeight - AssistantVoiceHoverControl.DEFAULT_SIZE,
            AssistantVoiceHoverControl.DEFAULT_SIZE
        )
        lemonHoverControl.setBounds(
            CloseAppHoverControl.DEFAULT_MARGIN,
            containerHeight - LemonHoverControl.DEFAULT_SIZE - CloseAppHoverControl.DEFAULT_MARGIN,
            LemonHoverControl.DEFAULT_SIZE
        )
    }

    private fun triggerCloseAppConfirmation() {
        closeConfirmationPopup?.show()
        notifyTransientContentChanged()
    }

    // ==================== Fullscreen Management ====================
    private fun handleVideoWidgetFullscreenSideEffects(widget: VideoWidget, isFullscreen: Boolean) {
        if (isFullscreen) widget.startPlayback(autoPlay = true)
        widget.setWindowedMode()
    }

    // distribute location updates to widgets that are location dependent
    fun applyLocationMetadata(town: String, countryCode: String) {
        updateMapMetadata(town, countryCode)
        financeFeatures.updateCountryCode(countryCode)
        newsFeatures.updateCountryCode(countryCode)
        locationWeatherBinding.applyLocationMetadata(town, countryCode)
    }

    //update features of the Weather and location widget
    fun setLocationPermissionDenied() = locationWeatherBinding.setLocationPermissionDenied()
    fun applyWeatherData(weather: WeatherData?) = locationWeatherBinding.applyWeatherData(weather)

    //update features of the Finance widget
    fun applyFinanceSnapshot(snapshot: FinanceSnapshot, notify: Boolean = true) = financeFeatures.applySnapshot(snapshot, notify)
    fun applyFinanceError(message: String) = financeFeatures.applyError(message)
    fun currentFinanceVisibleConfigs(): List<FinanceDashboardTileConfig> = financeFeatures.currentVisibleConfigs()
    fun updateFinanceTile(request: FinanceTileUpdateRequest): CompletableFuture<FinanceTileUpdateResult> = financeFeatures.updateTile(request)

    //update features of the News widget
    fun applyNewsSnapshot(snapshot: NewsSnapshot) = newsFeatures.applySnapshot(snapshot)
    fun applyNewsError(message: String) = newsFeatures.applyError(message)
    fun currentNewsCountryCode(): String? = newsFeatures.currentCountryCode()
    fun currentNewsSourceSpec(): NewsSourceSpec? = newsFeatures.currentSourceSpec()
    fun setNewsSource(sourceSpec: NewsSourceSpec): CompletableFuture<NewsSourceUpdateResult> = newsFeatures.setSource(sourceSpec)

    fun updateSpeedData(speedKmh: Float, qualityOk: Boolean, isMoving: Boolean) {
        pendingSpeedKmh = speedKmh
        pendingSpeedQualityOk = qualityOk
        pendingSpeedMoving = isMoving
        val changed = speedometerWidget?.setSpeedData(speedKmh, qualityOk, isMoving) ?: false
        if (changed && displayState != WakeSleepManager.DisplayState.OFF) {
            notifyTransientContentChanged()
        }
    }

    fun setSpeedPermissionDenied() {
        pendingSpeedKmh = 0f
        pendingSpeedQualityOk = false
        pendingSpeedMoving = false
        speedometerWidget?.setPermissionDenied()
        if (displayState != WakeSleepManager.DisplayState.OFF) {
            notifyTransientContentChanged()
        }
    }

    fun applySubtitleStatus(status: SubtitleStatus) {
        withTransientChangeBatch {
            subtitleWidget?.applyStatus(status)
            notifyTransientContentChanged()
        }
    }

    fun applySubtitleTranscript(transcript: SubtitleTranscript) {
        withTransientChangeBatch {
            subtitleWidget?.applyTranscript(transcript)
            notifyTransientContentChanged()
        }
    }

    fun onSubtitlePhoneDisconnected() {
        withTransientChangeBatch {
            subtitleWidget?.onPhoneDisconnected()
            notifyTransientContentChanged()
        }
    }

    fun setSpeedVisibilityThreshold(thresholdKmh: Float) {
        val forceVisibleChanged = settingsController.setSpeedVisibilityThreshold(thresholdKmh)
        if (forceVisibleChanged && displayState != WakeSleepManager.DisplayState.OFF) {
            notifyTransientContentChanged()
        }
    }

    private fun createSpeedometerWidget(
        startX: Float,
        startY: Float,
        widgetWidth: Float,
        widgetHeight: Float
    ): SpeedometerWidget {
        return SpeedometerWidget(startX, startY, widgetWidth, widgetHeight).apply {
            onUnitChanged = {
                unitSettings.toggleSpeedOverride()
            }
            setUnit(unitSettings.effectiveSpeedUnit())
            setForceVisibleWhenIdle(settingsController.getSpeedVisibilityThreshold() <= 0f)
            setSpeedData(pendingSpeedKmh, pendingSpeedQualityOk, pendingSpeedMoving)
        }
    }

    private fun createSubtitleWidget(
        startX: Float,
        startY: Float,
        widgetWidth: Float,
        widgetHeight: Float,
        autoStart: Boolean
    ): SubtitleWidget {
        return SubtitleWidget(startX, startY, widgetWidth, widgetHeight).apply {
            listener = object : SubtitleWidgetListener {
                override fun onSubtitleControlRequested(control: SubtitleControl) {
                    host?.requestSubtitleControl(control)
                }
            }
            if (autoStart) {
                setCaptureEnabled(true)
            }
        }
    }

    private fun exitAllFullscreen() {
        val fullscreen = registry.fullscreenWidget ?: return
        registry.clearFullscreen(fullscreen)
        if (fullscreen.isFullscreen) fullscreen.toggleFullscreen()
    }

    private fun updateFullscreenBounds() {
        if (width <= 0 || height <= 0) return
        notificationsWidget?.setScreenHeight(height.toFloat())
        registry.fullscreenWidget?.setFullscreenBounds(width.toFloat(), height.toFloat())
    }

    fun updateStatusBar(isPhoneConnected: Boolean, phoneBattery: Int, glassesBattery: Int, time: String, date: String) {
        val statusBar = statusBarWidget ?: return
        val changed =
            statusBar.isPhoneConnected != isPhoneConnected ||
                statusBar.phoneBattery != phoneBattery ||
                statusBar.glassesBattery != glassesBattery ||
                statusBar.timeString != time ||
                statusBar.dateString != date

        if (!changed) return

        statusBar.apply {
            this.isPhoneConnected = isPhoneConnected
            this.phoneBattery = phoneBattery
            this.glassesBattery = glassesBattery
            this.timeString = time
            this.dateString = date
        }
        notifyTransientContentChanged()
    }

    fun setCalendarSnapshot(snapshot: com.everyday.shared.sync.CalendarSnapshot?) {
        pendingCalendarSnapshot = snapshot
        withTransientChangeBatch {
            calendarWidget?.setSnapshot(snapshot)
            notifyTransientContentChanged()
        }
    }

    fun observeLiveLocationCountry(countryCode: String): Boolean =
        UnitSettings.normalizeCountryCode(countryCode)?.let {
            unitSettings.observeLiveCountry(it)
            true
        } ?: false

    private fun applyEffectiveUnitSettings() {
        val speedUnit = unitSettings.effectiveSpeedUnit()
        speedometerWidget?.setUnit(speedUnit)
        settingsController.setSpeedUnit(speedUnit)
        settingsController.setUnitSystemMode(unitSettings.globalMode)
        locationWeatherBinding.applyUnitSettings()
    }

    fun applyNotificationsSnapshot(snapshot: PhoneNotificationsSnapshot) {
        latestNotificationsSnapshot = snapshot
        withTransientChangeBatch {
            notificationsWidget?.setNotificationsSnapshot(snapshot)
            notifyTransientContentChanged()
        }
    }

    // ==================== Z-Order Management ====================
    private fun bringWidgetToFront(widget: BaseWidget) {registry.bringToFront(widget, parent as? ViewGroup, this)}
    private fun bringWidgetToFrontForInteraction(widget: BaseWidget) {
        val before = widget.zOrder
        bringWidgetToFront(widget)
        if (widget.zOrder != before) interactionZOrderChanged = true
    }

    /**
     * Choose the single widget that should own hover for a cursor at (cx, cy) in content
     * space, honoring two rules:
     *
     *  1. A widget currently being moved or resized keeps the cursor for the whole gesture.
     *  2. Otherwise, hover is "sticky": while the previously hovered widget still contains the
     *     cursor it stays hovered, so a widget can only *become* hovered when no other widget
     *     is already hovered under the cursor. When hover is free, the top-most (highest
     *     z-order) widget under the cursor wins.
     *
     * Returns null when no widget is under the cursor.
     */
    private fun resolveHoveredWidget(cx: Float, cy: Float): BaseWidget? {
        // Ascending z-order, so lastOrNull yields the top-most match.
        val byZOrder = registry.getAllSortedByZOrder()

        byZOrder.lastOrNull {
            it.baseState == BaseWidget.BaseState.MOVING || it.baseState == BaseWidget.BaseState.RESIZING
        }?.let { return it }

        val current = hoveredWidget
        if (current != null && byZOrder.contains(current) && current.containsPoint(cx, cy)) {
            return current
        }
        return byZOrder.lastOrNull { it.containsPoint(cx, cy) }
    }

    /**
     * The widget a press / tap at (cx, cy) in content space should be routed to. Prefers the
     * widget that currently owns hover (so interaction matches what the user sees highlighted)
     * and falls back to the top-most widget under the cursor if hover is stale or cleared.
     */
    private fun interactionTargetAt(cx: Float, cy: Float): BaseWidget? =
        hoveredWidget?.takeIf { registry.allWidgets().contains(it) && it.containsPoint(cx, cy) }
            ?: registry.getWidgetAt(cx, cy)

    // ==================== Input Handling ====================

    override fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        pendingDragWidget = null; pendingDragFinanceTile = false; pendingDragIsResize = false; pendingTextSelection = false
        pendingFormattingMenuDrag = false
        textEditing.formattingMenu?.cancelLongPress()
    }

    fun onCursorDown(x: Float, y: Float, pointerCount: Int = 1) {
        cursorPressed = true; isTwoFingerScrollMode = pointerCount >= 2
        interactionZOrderChanged = false
        interactionPersistentNotified = false
        cursorX = x; cursorY = y; lastCursorX = x; lastCursorY = y
        cursorStabilizer.reset()
        longPressStartX = x; longPressStartY = y
        cancelLongPress()
        isLongPressSelectionMode = false; pendingTextSelection = false

        if (isTwoFingerScrollMode) return

        layoutNamePrompt?.let { prompt ->
            if (prompt.isVisible) {
                if (textEditing.focusedZoneContains(x, y)) { pendingTextSelection = true; scheduleLongPress() }
                notifyTransientContentChanged()
                return
            }
        }

        // The hover-controls layout editor is fully modal; consume input before any
        // widget hit-testing.
        if (closeConfirmationPopup?.isVisible != true &&
            settingsController.handleHoverControlsLayoutEditorDown(x, y)
        ) return

        // Shortcuts settings menu is also modal.
        if (settingsController.handleShortcutsSettingsMenuDown(x, y)) return

        // Transform to content space for hit testing against widgets
        val (cx, cy) = toContentSpace(x, y)

        // Hover controls sit above every widget — including fullscreen ones — so let any
        // currently-interacting (e.g. lemon mid-magnify) control or any hover-control
        // whose bounds contain the cursor consume the press before widget hit-testing.
        if (isVisibleKeyboardAt(cx, cy)) {
            cancelHoverControlInteractions()
            updateKeyboardHoverAt(cx, cy)
            notifyTransientContentChanged()
            return
        }

        if (hoverControlsConsumeDown(cx, cy)) return

        val scrollbarWidget = getScrollbarHoveredWidget(cx, cy)
        if (scrollbarWidget != null) { isScrollbarDragActive = true; this.scrollbarDragWidget = scrollbarWidget }
        else { isScrollbarDragActive = false; this.scrollbarDragWidget = null }

        // Menus are NOT transformed (they stay head-locked like cursor)
        textEditing.formattingMenu?.let { menu -> if (menu.isVisible && menu.shouldScheduleLongPress(x, y)) { pendingFormattingMenuDrag = true; scheduleLongPress(); return } }

        if (settingsController.handleSpeedometerDown(x, y)) return

        // The map POI settings panel owns its drag gestures while open, including when the
        // map is fullscreen. Route the press before normal widget/focus handling.
        if (mapWidget?.isPoiConfigVisible() == true && mapWidget?.onDown(cx, cy) == true) return
        
        // A long press on the focused text zone starts a text selection.
        if (textEditing.focusedZoneContains(x, y)) {
            focusedWidget?.let(::bringWidgetToFrontForInteraction)
            pendingTextSelection = true
            scheduleLongPress()
            return
        }

        fullscreenAs<FinanceWidget>()?.let { fw ->
            if (fw.containsPoint(cx, cy) && fw.canStartTileDrag(cx, cy)) {
                pendingDragFinanceTile = true
                scheduleLongPress()
                return
            }
        }

        if (hasFullscreenWidget()) {
            fullscreenAs<BrowserWidget>()?.let { widget ->
                if (widget.containsPoint(cx, cy) && widget.hitTest(cx, cy) == BrowserWidget.HitArea.CONTENT) {
                    handleBrowserContentDown(widget, cx, cy)
                    return
                }
            }
            return
        }

        // Modal dashboard check - prioritize input if visible
        if (settingsController.handleDashboardDown(x, y)) return

        // Route the press to the single top-most widget under the cursor — the same widget
        // that owns hover. This prevents an overlapping, unintended widget (e.g. one whose
        // border or button band reaches under another widget) from stealing the press.
        interactionTargetAt(cx, cy)?.let { target -> dispatchCursorDownToWidget(target, cx, cy) }
    }

    /**
     * Begin the appropriate down-gesture for [widget], the resolved interaction target.
     * Mirrors the per-type drag / content-touch handling the old fixed-priority walk did,
     * but for exactly one widget so overlapping widgets can't compete for the press.
     */
    private fun dispatchCursorDownToWidget(widget: BaseWidget, cx: Float, cy: Float) {
        // Z-order is part of the persisted dashboard, but a long-press drag must not save
        // its initial frame.  The drag completion below emits the single persistent signal.
        bringWidgetToFrontForInteraction(widget)

        when (widget) {
            is BrowserWidget -> when (widget.hitTest(cx, cy)) {
                BrowserWidget.HitArea.CONTENT -> handleBrowserContentDown(widget, cx, cy)
                BrowserWidget.HitArea.BORDER -> beginPendingWidgetDrag(widget, isResize = false)
                BrowserWidget.HitArea.RESIZE_HANDLE -> beginPendingWidgetDrag(widget, isResize = true)
                else -> {}
            }
            is FileBrowserWidget -> when (widget.hitTest(cx, cy)) {
                FileBrowserWidget.HitArea.BORDER -> beginPendingWidgetDrag(widget, isResize = false)
                FileBrowserWidget.HitArea.RESIZE_HANDLE -> beginPendingWidgetDrag(widget, isResize = true)
                else -> {}
            }
            is TextBoxWidget -> when (widget.hitTest(cx, cy)) {
                TextBoxWidget.HitArea.BORDER -> beginPendingWidgetDrag(widget, isResize = false)
                TextBoxWidget.HitArea.RESIZE_HANDLE -> beginPendingWidgetDrag(widget, isResize = true)
                else -> {}
            }
            // image / video / pdf share the base hit-test; border or content both start a move.
            else -> when (widget.dragHitTest(cx, cy)) {
                BaseWidget.DragHit.DRAG ->
                    beginPendingWidgetDrag(widget, isResize = false)
                BaseWidget.DragHit.RESIZE ->
                    beginPendingWidgetDrag(widget, isResize = true)
                else -> {}
            }
        }
    }

    private fun beginPendingWidgetDrag(widget: BaseWidget, isResize: Boolean) {
        pendingDragWidget = widget
        pendingDragIsResize = isResize
        scheduleLongPress()
    }

    private fun scheduleLongPress() {
        longPressRunnable = Runnable {
            if (!cursorPressed) return@Runnable
            userInputActivity?.onUserInput(UserInputKind.LONG_PRESS)
            registry.forEachWidget { it.snapProvider = smartAlignment }
            if (pendingFormattingMenuDrag) {
                textEditing.formattingMenu?.onLongPressTriggered()
                pendingFormattingMenuDrag = false
                notifyTransientContentChanged()
                return@Runnable
            }
            if (pendingTextSelection) { if (textEditing.hasFocus) { textEditing.beginSelection(longPressStartX, longPressStartY); isLongPressSelectionMode = true}; pendingTextSelection = false; notifyTransientContentChanged(); return@Runnable }
            if (isWidgetLayoutLocked && hasPendingWidgetDrag()) {
                clearPendingWidgetDrag()
                notifyTransientContentChanged()
                return@Runnable
            }
            if (pendingDragFinanceTile) {
                financeWidget?.let {
                    val (cx, cy) = screenToContentCoordinates(longPressStartX, longPressStartY)
                    if (it.startTileDrag(cx, cy)) financeTileDragging = true
                }
                pendingDragFinanceTile = false
                notifyTransientContentChanged()
                return@Runnable
            }
            pendingDragWidget?.let { widget ->
                withTransientChangeBatch {
                    widget.startDrag(pendingDragIsResize)
                    activeWidget = widget
                    notifyTransientContentChanged()
                }
            }
            pendingDragWidget = null; pendingDragIsResize = false; longPressRunnable = null
        }
        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DELAY_MS)
    }

    private fun hasPendingWidgetDrag(): Boolean =
        pendingDragWidget != null ||
                pendingDragFinanceTile

    private fun clearPendingWidgetDrag() {
        pendingDragWidget = null
        pendingDragFinanceTile = false
        pendingDragIsResize = false
        longPressRunnable = null
    }

    private fun beginBrowserContentTouch(widget: BrowserWidget, x: Float, y: Float) {
        activeBrowserTouchWidget = widget
        widget.setFocused(true)
        setFocusedWidget(widget)
        widget.dispatchTouchEvent(x, y, MotionEvent.ACTION_DOWN)
    }

    private fun handleBrowserContentDown(widget: BrowserWidget, x: Float, y: Float) {
        widget.setFocused(true)
        setFocusedWidget(widget)
        if (widget.shouldStartContentTouch(x, y)) {
            beginBrowserContentTouch(widget, x, y)
        }
    }

    private fun finishBrowserContentTouch(x: Float, y: Float) {
        val widget = activeBrowserTouchWidget ?: return
        val (cx, cy) = toContentSpace(x, y)
        widget.dispatchTouchEvent(cx, cy, MotionEvent.ACTION_UP)
        activeBrowserTouchWidget = null
        suppressBrowserContentTapWidget = widget
        suppressBrowserContentTapUntilMs = SystemClock.uptimeMillis() + BROWSER_TOUCH_TAP_SUPPRESSION_MS
    }

    private fun shouldSuppressBrowserContentTap(widget: BrowserWidget): Boolean {
        if (suppressBrowserContentTapWidget != widget) return false
        if (SystemClock.uptimeMillis() <= suppressBrowserContentTapUntilMs) return true
        suppressBrowserContentTapWidget = null
        suppressBrowserContentTapUntilMs = 0L
        return false
    }

    private fun cancelBrowserContentTouch() {
        val widget = activeBrowserTouchWidget ?: return
        val (cx, cy) = toContentSpace(cursorX, cursorY)
        widget.dispatchTouchEvent(cx, cy, MotionEvent.ACTION_CANCEL)
        activeBrowserTouchWidget = null
    }

    fun onCursorUp(x: Float, y: Float) {
        settingsController.handleSpeedometerUp()
        settingsController.handleDashboardUp() // If DashboardSettings is open, end its interaction (e.g., stop slider drag).
        mapWidget?.let {if (it.isPoiConfigVisible()) it.onUp()}
        settingsController.handleHoverControlsLayoutEditorUp() // If the hover-controls layout editor is open, finalize any drag.
        settingsController.handleShortcutsSettingsMenuUp() // Shortcuts settings menu.

        // Notify any hover control that captured the press of the release, unless
        // the visible keyboard owns the release point.
        val (cx, cy) = toContentSpace(cursorX, cursorY)
        if (isVisibleKeyboardAt(cx, cy)) {
            cancelHoverControlInteractions()
        } else {
            hoverControlsConsumeUp(cursorX, cursorY)
        }

        finishBrowserContentTouch(x, y)

        cursorPressed = false; cancelLongPress()
        if (isScrollbarDragActive) { isScrollbarDragActive = false; scrollbarDragWidget = null }
        textEditing.formattingMenu?.let { if (it.isDragging()) it.onDragEnd() }
        if (isLongPressSelectionMode) { textEditing.endSelection(); isLongPressSelectionMode = false }
        var dragEnded = false
        if (financeTileDragging) {
            dragEnded = true
            if (financeWidget != null) {
                runPersistentMutation(
                    action = { financeWidget?.endTileDrag() == true },
                    changed = { it }
                )
            }
            financeTileDragging = false
        }
        if (activeWidget != null) {
            dragEnded = true
            val widget = activeWidget
            activeWidget = null
            runPersistentMutation {
                widget?.onDragEnd()
            }
        }
        if (!dragEnded && interactionZOrderChanged && !interactionPersistentNotified) {
            notifyPersistentContentChanged()
        }
        interactionZOrderChanged = false
        interactionPersistentNotified = false
        isTwoFingerScrollMode = false
    }
    
    fun onPointerCountChanged(pointerCount: Int) {
        val wasScrollMode = isTwoFingerScrollMode; isTwoFingerScrollMode = pointerCount >= 2
        if (isTwoFingerScrollMode && !wasScrollMode) {
            cancelLongPress()
            cancelBrowserContentTouch()
        }
    }

    fun setCursorPosition(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, width.toFloat()); cursorY = y.coerceIn(0f, height.toFloat())
        lastCursorX = cursorX; lastCursorY = cursorY
        updateHover(cursorX, cursorY, isCursorVisible)
    }

    fun onMove(dx: Float, dy: Float, source: InputSource = InputSource.GLASSES_TEMPLE): Pair<Float, Float> {
        capturedHoverControl?.takeIf { it.freezesCursorWhileInteracting() }?.let { control ->
            val moveDelta = if (source == InputSource.PHONE_TRACKPAD) {
                stabilizedCursorDelta.apply {
                    set(dx * PHONE_TRACKPAD_SENSITIVITY, dy * PHONE_TRACKPAD_SENSITIVITY)
                }
            } else {
                cursorStabilizer.processInto(dx, dy, stabilizedCursorDelta)
            }
            if (control.onCursorMove(cursorX, cursorY, moveDelta.x, moveDelta.y)) {
                lastCursorX = cursorX
                lastCursorY = cursorY
                return Pair(cursorX, cursorY)
            }
        }

        if (source == InputSource.PHONE_TRACKPAD) {
            cursorX = (cursorX + (dx * PHONE_TRACKPAD_SENSITIVITY)).coerceIn(0f, width.toFloat())
            cursorY = (cursorY + (dy * PHONE_TRACKPAD_SENSITIVITY)).coerceIn(0f, height.toFloat())
        } else {
            val stabilized = cursorStabilizer.processInto(dx, dy, stabilizedCursorDelta)
            cursorX = (cursorX + stabilized.x).coerceIn(0f, width.toFloat())
            cursorY = (cursorY + stabilized.y).coerceIn(0f, height.toFloat())
        }
        updateHover(cursorX, cursorY, isCursorVisible)

        layoutNamePrompt?.let { prompt ->
            if (prompt.isVisible) {
                lastCursorX = cursorX; lastCursorY = cursorY
                return Pair(cursorX, cursorY)
            }
        }

        // If SpeedometerSettings is open, treat it as modal and route movement to it.
        if (settingsController.handleSpeedometerMove(cursorX, cursorY)) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        // Keep POI sliders live throughout a press, matching the Brightness slider's drag
        // behavior instead of waiting for a final tap.
        mapWidget?.let {
            if (it.isPoiConfigVisible()) {
                val (mapX, mapY) = toContentSpace(cursorX, cursorY)
                if (it.onMove(mapX, mapY)) {
                    lastCursorX = cursorX; lastCursorY = cursorY
                    return Pair(cursorX, cursorY)
                }
            }
        }

        // If DashboardSettings is open, treat it as modal and route movement to it.
        // This allows slider dragging and prevents dragging widgets behind it.
        if (settingsController.handleDashboardMove(cursorX, cursorY)) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        // Same for the hover-controls layout editor.
        if (closeConfirmationPopup?.isVisible != true &&
            settingsController.handleHoverControlsLayoutEditorMove(cursorX, cursorY)
        ) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        if (settingsController.handleShortcutsSettingsMenuMove(cursorX, cursorY)) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        val moveDx = cursorX - lastCursorX; val moveDy = cursorY - lastCursorY
        lastCursorX = cursorX; lastCursorY = cursorY

        // Forward moves to a hover control that captured the cursor on down.
        if (hoverControlsConsumeMove(cursorX, cursorY, moveDx, moveDy)) {
            return Pair(cursorX, cursorY)
        }

        activeBrowserTouchWidget?.let { widget ->
            val (cx, cy) = toContentSpace(cursorX, cursorY)
            widget.dispatchTouchEvent(cx, cy, MotionEvent.ACTION_MOVE)
            return Pair(cursorX, cursorY)
        }

        handleDragOperations(cursorX, cursorY, moveDx, moveDy)
        return Pair(cursorX, cursorY)
    }

    fun updateCursor(x: Float, y: Float, isVisible: Boolean = true): Pair<Float, Float> {
        isCursorVisible = isVisible; cursorX = x; cursorY = y
        updateHover(x, y, isVisible)
        return Pair(x, y)
    }

    /** Forward global cursor activity to any visible video player controls. */
    fun onCursorActivity() {
        videoWidgets.forEach { it.onCursorActivity() }
    }

    /** Keep video player controls aligned with the global cursor visibility state. */
    fun onCursorVisibilityChanged(visible: Boolean) {
        videoWidgets.forEach { it.onCursorVisibilityChanged(visible) }
    }

    private fun updateHover(x: Float, y: Float, isVisible: Boolean) {
        // The hover-controls editor handles its own cursor state and the dashboard widgets/
        // controls behind it are hidden, so suppress the regular hover pass.
        if (settingsController.isHoverControlsLayoutEditorVisible()) {
            hoveredWidget = null
            hoverControls.forEach { it.clearHover() }
            registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            if (!isTransitioningDisplayState) invalidateHoverVisuals()
            return
        }

        if (!isVisible) {
            hoveredWidget = null
            hoverControls.forEach { it.clearHover() }
            closeConfirmationPopup?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); textEditing.updateMenuHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); contextMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            layoutDeleteConfirmationPopup?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            layoutNamePrompt?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            cursorStabilizer.reset()
            // Hover clear is transient visual state only.
            if (!isTransitioningDisplayState) { invalidateHoverVisuals() }
            return
        }
        
        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)

        if (updateKeyboardHoverAt(cx, cy)) {
            hoverControls.forEach { it.clearHover() }
            clearWidgetHoverStatesExcept(visibleKeyboardHostAt(cx, cy))
            if (!isTransitioningDisplayState) invalidateHoverVisuals()
            return
        }
        
        // Hover controls live above every widget, including fullscreen ones, so they
        // always receive hover updates whenever the cursor is visible.
        hoverControls.forEach { it.updateHover(cx, cy) }

        // Menus stay head-locked, use screen coords
        closeConfirmationPopup?.updateHover(x, y); textEditing.updateMenuHover(x, y); contextMenu?.updateHover(x, y)
        layoutDeleteConfirmationPopup?.updateHover(x, y)
        layoutNamePrompt?.updateHover(x, y)

        if (!isAnyModalOverHoverControls() && hoverControls.any { it.isHovered || it.isInteracting() }) {
            clearWidgetHoverStates()
            if (!isTransitioningDisplayState) {
                invalidateHoverVisuals()
            }
            return
        }
        
        // Widgets use content-space coordinates for hit testing
        val fullscreen = registry.fullscreenWidget
        if (fullscreen != null) {
            // Only the fullscreen widget is visible, so no windowed widget owns hover here.
            hoveredWidget = null
            fullscreen.updateHover(if (fullscreen.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
        } else {
            // Exclusive hover: only a single widget — the top-most one under the cursor —
            // may be hovered at any moment, and it stays hovered until the cursor leaves it
            // (see resolveHoveredWidget). Every other widget has its hover state cleared, so
            // overlapping widgets can never all light up (or all try to grab the cursor) at
            // once. This keeps the visually-hovered widget and the widget that receives
            // taps / drags in agreement.
            val target = resolveHoveredWidget(cx, cy)
            hoveredWidget = target
            for (widget in registry.allWidgets()) {
                if (widget === target) {
                    widget.updateHover(cx, cy)
                } else {
                    widget.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
                }
            }
        }

        if (!isTransitioningDisplayState) {
            invalidateHoverVisuals()
        }
    }
    private fun clearWidgetHoverStates() {
        hoveredWidget = null
        registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
    }

    private fun toContentSpace(screenX: Float, screenY: Float): Pair<Float, Float> {
        return screenToContentCoordinates(screenX, screenY)
    }

    private fun handleDragOperations(x: Float, y: Float, dx: Float, dy: Float) {
        if (isLongPressSelectionMode || financeTileDragging ||
            activeWidget != null || isScrollbarDragActive
        ) {
            userInputActivity?.onUserInput(UserInputKind.DRAG)
        }
        // For text selection, use content-space coordinates
        if (isLongPressSelectionMode && textEditing.isSelecting) {
            textEditing.extendSelection(x, y)
        }
        // Menus use screen-space (head-locked)
        textEditing.formattingMenu?.let { menu ->
            if (menu.isDragging()) {
                menu.onDrag(x, y)
                notifyTransientContentChanged()
                return
            }

            menu.checkLongPressCancellation(
                x,
                y,
                LONG_PRESS_MAX_MOVEMENT
            )
        }
        // Long-press cancellation uses screen-space (cursor movement)
        if (longPressRunnable != null) { val totalMovement = kotlin.math.sqrt((x - longPressStartX) * (x - longPressStartX) + (y - longPressStartY) * (y - longPressStartY)); if (totalMovement > LONG_PRESS_MAX_MOVEMENT) cancelLongPress() }
        // Widget dragging uses delta values (works in both spaces)
        if (financeTileDragging) {
            val (cx, cy) = toContentSpace(x, y)
            financeWidget?.onTileDrag(cx, cy)
            notifyTransientContentChanged()
            return
        }
        if (activeWidget != null) { activeWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyTransientContentChanged(); return }
        if (isScrollbarDragActive && scrollbarDragWidget != null) { scrollbarDragWidget?.onScrollbarDrag(dy); notifyTransientContentChanged(); return }
    }

    /** The text box whose scrollbar is under the content-space point ([cx], [cy]), if any. */
    private fun getScrollbarHoveredWidget(cx: Float, cy: Float): TextBoxWidget? {
        fullscreenAs<TextBoxWidget>()?.let { if (it.isOverScrollbar(cx, cy)) return it }
        for (widget in widgets.reversed()) { if (widget.isOverScrollbar(cx, cy)) return widget }
        return null
    }

    fun isScrollbarDragActive(): Boolean = isScrollbarDragActive
    fun getScrollbarDragWidget(): TextBoxWidget? = scrollbarDragWidget

    fun scrollWidgetAtCursor(cursorX: Float, cursorY: Float, dy: Float, userInitiated: Boolean = false) {
        if (userInitiated) userInputActivity?.onUserInput(UserInputKind.SCROLL)
        contextMenu?.let {
            if (it.isVisible) {
                it.onScroll(dy)
                notifyTransientContentChanged()
                return
            }
        }

        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(cursorX, cursorY)

        if (scrollFullscreenWidgetAt(cx, cy, dy)) return
        if (hasFullscreenWidget()) return

        mapWidget?.let {
            if ((it.isPoiConfigVisible() || it.navigationState.choices.isNotEmpty()) && it.containsPoint(cx, cy) && !it.isMinimized) {
                it.onScroll(dy)
                notifyTransientContentChanged()
                return
            }
        }
        calendarWidget?.let {
            if (it.containsPoint(cx, cy) && !it.isMinimized) {
                withTransientChangeBatch {
                    it.onScroll(dy)
                    notifyTransientContentChanged()
                }
                return
            }
        }
        financeWidget?.let {
            if (it.containsPoint(cx, cy) && !it.isMinimized) {
                val beforeIndex = it.getNavigationIndex()
                it.onScroll(dy)
                if (it.getNavigationIndex() != beforeIndex) notifyPersistentContentChanged()
                else notifyTransientContentChanged()
                return
            }
        }
        newsWidget?.let {
            if (it.containsPoint(cx, cy) && !it.isMinimized) {
                val beforeIndex = it.getSelectedIndex()
                runClassifiedInput(
                    action = { it.onScroll(dy); true },
                    changed = { it.getSelectedIndex() != beforeIndex }
                )
                return
            }
        }
        notificationsWidget?.let {
            if (it.containsPoint(cx, cy) && !it.isMinimized) {
                val beforeIndex = it.getScrollIndex()
                runClassifiedInput(
                    action = { it.onScroll(dy); true },
                    changed = { it.getScrollIndex() != beforeIndex }
                )
                return
            }
        }
        for (widget in browserWidgets.reversed()) {
            val hit = widget.hitTest(cx, cy)
            if (!widget.isMinimized && (hit == BrowserWidget.HitArea.CONTENT || hit == BrowserWidget.HitArea.NAV_URL)) {
                widget.onScroll(dy)
                notifyTransientContentChanged()
                return
            }
        }
        for (widget in imageWidgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyTransientContentChanged(); return } }
        for (widget in pdfWidgets.reversed()) {
            if (widget.containsPoint(cx, cy) && !widget.isMinimized) {
                withTransientChangeBatch {
                    widget.onScroll(dy)
                    notifyTransientContentChanged()
                }
                return
            }
        }
        for (widget in fileBrowserWidgets.reversed()) {
            if (widget.containsPoint(cx, cy) && !widget.isMinimized) {
                withTransientChangeBatch {
                    widget.onScroll(dy)
                    notifyTransientContentChanged()
                }
                return
            }
        }
        for (widget in widgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyTransientContentChanged(); return } }
    }

    private fun scrollFullscreenWidgetAt(cx: Float, cy: Float, dy: Float): Boolean {
        when (val widget = registry.fullscreenWidget) {
            is MapWidget -> {
                if ((widget.isPoiConfigVisible() || widget.navigationState.choices.isNotEmpty()) && widget.containsPoint(cx, cy)) {
                    widget.onScroll(dy)
                    notifyTransientContentChanged()
                    return true
                }
            }
            is TextBoxWidget -> {
                if (widget.containsPoint(cx, cy)) {
                    widget.onScroll(dy)
                    notifyTransientContentChanged()
                    return true
                }
            }
            is BrowserWidget -> {
                val hit = widget.hitTest(cx, cy)
                if (hit == BrowserWidget.HitArea.CONTENT || hit == BrowserWidget.HitArea.NAV_URL) {
                    widget.onScroll(dy)
                    notifyTransientContentChanged()
                    return true
                }
            }
            is PdfWidget, is FileBrowserWidget, is CalendarWidget -> {
                if (widget.containsPoint(cx, cy)) {
                    withTransientChangeBatch {
                        widget.onScroll(dy)
                        notifyTransientContentChanged()
                    }
                    return true
                }
            }
            is FinanceWidget -> {
                if (widget.containsPoint(cx, cy)) {
                    val beforeIndex = widget.getNavigationIndex()
                    if (widget.onScrollAt(cx, cy, dy)) {
                        if (widget.getNavigationIndex() != beforeIndex) notifyPersistentContentChanged()
                        else notifyTransientContentChanged()
                    }
                    return true
                }
            }
            is NewsWidget -> {
                if (widget.containsPoint(cx, cy)) {
                    val beforeIndex = widget.getSelectedIndex()
                    runClassifiedInput(
                        action = { widget.onScroll(dy); true },
                        changed = { widget.getSelectedIndex() != beforeIndex }
                    )
                    return true
                }
            }
            is NotificationsWidget -> {
                if (widget.containsPoint(cx, cy)) {
                    val beforeIndex = widget.getScrollIndex()
                    runClassifiedInput(
                        action = { widget.onScroll(dy); true },
                        changed = { widget.getScrollIndex() != beforeIndex }
                    )
                    return true
                }
            }
        }
        return false
    }

    // ==================== Tap Handling ====================

    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        return fullscreenAs<VideoWidget>()?.handleKeyEvent(keyCode, event) == true
    }

    fun onTap(x: Float, y: Float): Boolean {
        // Menus use screen-space coordinates (head-locked)
        if (pageAgentApprovalOverlay.isVisible()) {
            return pageAgentApprovalOverlay.onTap(x, y)
        }
        // The text edit and formatting menus sit above whichever surface owns the focused text zone.
        textEditing.onMenuTap(x, y)?.let { return it }
        if (layoutNamePrompt?.isVisible == true) {
            layoutOverlayNotificationEmitted = false
            val handled = layoutNamePrompt?.onTap(x, y) ?: false
            if (handled && !layoutOverlayNotificationEmitted) notifyTransientContentChanged()
            layoutOverlayNotificationEmitted = false
            return handled
        }
        if (layoutDeleteConfirmationPopup?.isVisible == true) {
            layoutOverlayNotificationEmitted = false
            layoutDeleteInProgress = true
            val handled = try {
                layoutDeleteConfirmationPopup?.onTap(x, y) ?: false
            } finally {
                layoutDeleteInProgress = false
            }
            if (handled && !layoutOverlayNotificationEmitted) notifyTransientContentChanged()
            layoutOverlayNotificationEmitted = false
            return handled
        }
        if (closeConfirmationPopup?.isVisible == true) return closeConfirmationPopup?.onTap(x, y) ?: false
        settingsController.handleHoverControlsLayoutEditorTap(x, y)?.let { return it }
        settingsController.handleShortcutsSettingsMenuTap(x, y)?.let { return it }
        if (contextMenu?.isVisible == true) {
            return dispatchContextMenuAction { contextMenu?.onTap(x, y) ?: false }
        }
        settingsController.handleSpeedometerTap(x, y)?.let { return it }

        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)

        if (consumeVisibleKeyboardTap(cx, cy)) return true

        // Hover controls are above every widget, including fullscreen ones.
        if (isHoverConfirmReleaseTapGuardActive()) return true
        if (hoverControlsConsumeTap(cx, cy)) return true

        // Map address search and ambiguous destination choices are screen-level modals even
        // when the map itself is windowed. Route them before ordinary widget hit testing so a
        // large centered prompt cannot be obscured by another dashboard widget.
        if (mapWidget?.isAddressEntryVisible() == true || mapWidget?.navigationState?.choices?.isNotEmpty() == true) {
            val map = mapWidget
            if (map != null) {
                val before = mapPersistentSignature(map)
                if (runClassifiedInput({ map.onTap(cx, cy) }, { mapPersistentSignature(map) != before })) {
                return true
                }
            }
        }

        // POI settings are a screen-level modal when open, including in fullscreen map mode.
        if (mapWidget?.isPoiConfigVisible() == true) {
            val map = mapWidget
            if (map != null) {
                val before = mapPersistentSignature(map)
                if (runClassifiedInput({ map.onTap(cx, cy) }, { mapPersistentSignature(map) != before })) {
                return true
                }
            }
        }

        statusBarWidget?.let {
            val before = listOf(
                it.isTimeVisible(), it.isDateVisible(),
                it.isPhoneBatteryVisible(), it.isGlassesBatteryVisible()
            )
            if (it.handleToggleMenuTapOrDismiss(cx, cy)) {
                val after = listOf(
                    it.isTimeVisible(), it.isDateVisible(),
                    it.isPhoneBatteryVisible(), it.isGlassesBatteryVisible()
                )
                if (before != after) notifyPersistentContentChanged()
                else notifyTransientContentChanged()
                return true
            }
        }

        locationWidget?.let {
            val before = listOf(it.isWeatherVisible(), it.isTemperatureVisible(), it.isLocationVisible(), it.isCountryVisible())
            if (it.handleToggleMenuTapOrDismiss(cx, cy)) {
                val after = listOf(it.isWeatherVisible(), it.isTemperatureVisible(), it.isLocationVisible(), it.isCountryVisible())
                if (before != after) notifyPersistentContentChanged()
                else notifyTransientContentChanged()
                return true
            }
        }

        calendarWidget?.let {
            val before = it.getFontScale()
            if (runClassifiedInput({ it.handleFontMenuTapOrDismiss(cx, cy) }, { it.getFontScale() != before })) {
                return true
            }
        }

        newsWidget?.let {
            val before = it.getFontScale()
            if (runClassifiedInput({ it.handleFontMenuTapOrDismiss(cx, cy) }, { it.getFontScale() != before })) {
                return true
            }
        }

        notificationsWidget?.let {
            val before = it.getWidgetFontScale() to it.getToastFontScale()
            if (runClassifiedInput(
                    { it.handleFontMenuTapOrDismiss(cx, cy) },
                    { it.getWidgetFontScale() != before.first || it.getToastFontScale() != before.second }
                )
            ) {
                return true
            }
        }

        // A fullscreen widget is the only possible target; outside its bounds the tap does nothing.
        registry.fullscreenWidget?.let { fullscreen -> return fullscreen.containsPoint(cx, cy) && dispatchTapToWidget(fullscreen, cx, cy)}

        // Modal dashboard check
        settingsController.handleDashboardTap(x, y)?.let { return it }

        // Route the tap to the single top-most widget under the cursor — the same widget
        // that owns hover — so an overlapping widget can't consume a tap meant for it.
        interactionTargetAt(cx, cy)?.let { target ->
            if (target.isMinimized) { target.toggleMinimize(); return true }
            return dispatchTapToWidget(target, cx, cy)
        }

        if (hasFocusedWidget()) { unfocusAll(); notifyTransientContentChanged(); return true }
        return false
    }

    /** Dispatch a tap to [widget], the resolved (top-most / hovered) interaction target. */
    private fun dispatchTapToWidget(widget: BaseWidget, cx: Float, cy: Float): Boolean = when (widget) {
        is ScreenMirrorWidget -> handleMirrorWidgetTap(widget, cx, cy)
        is StatusBarWidget -> handleStatusBarTap(widget, cx, cy)
        is LocationWidget -> handleLocationWidgetTap(widget, cx, cy)
        is MapWidget -> handleMapWidgetTap(widget, cx, cy)
        is CalendarWidget -> handleCalendarWidgetTap(widget, cx, cy)
        is FinanceWidget -> handleFinanceWidgetTap(widget, cx, cy)
        is NewsWidget -> handleNewsWidgetTap(widget, cx, cy)
        is NotificationsWidget -> handleNotificationsWidgetTap(widget, cx, cy)
        is SpeedometerWidget -> handleSpeedometerWidgetTap(widget, cx, cy)
        is SubtitleWidget -> handleSubtitleWidgetTap(widget, cx, cy)
        is BrowserWidget -> {
            if (widget.hitTest(cx, cy) == BrowserWidget.HitArea.CONTENT && shouldSuppressBrowserContentTap(widget)) {
                true
            } else {
                handleBrowserWidgetTap(widget, cx, cy)
            }
        }
        is ImageWidget -> handleImageWidgetTap(widget, cx, cy)
        is VideoWidget -> handleVideoWidgetTap(widget, cx, cy)
        is PdfWidget -> handlePdfWidgetTap(widget, cx, cy)
        is FileBrowserWidget -> handleFileBrowserWidgetTap(widget, cx, cy)
        is TextBoxWidget -> handleTextWidgetTap(widget, cx, cy)
        else -> false
    }

    /**
     * Handles the four standard base-widget chrome buttons (close / fullscreen / minimize / pin).
     *
     * Calls [BaseWidget.baseHitTest] once and dispatches accordingly. [onElse] receives the raw
     * [BaseWidget.BaseHitArea] result when the hit falls outside the four base buttons, letting
     * per-widget handlers add extra behavior; defaults to swallowing the tap (returns `true`).
     */
    private fun handleBaseTap(
        widget: BaseWidget,
        x: Float,
        y: Float,
        onElse: ((BaseWidget.BaseHitArea) -> Boolean)? = null
    ): Boolean = when (val hit = widget.baseHitTest(x, y)) {
        BaseWidget.BaseHitArea.CLOSE_BUTTON      -> {
            runPersistentMutation { widget.lifecycleListener?.onCloseRequested(widget) }
            true
        }
        BaseWidget.BaseHitArea.FULLSCREEN_BUTTON -> {
            runPersistentMutation { widget.toggleFullscreen() }
            true
        }
        BaseWidget.BaseHitArea.MINIMIZE_BUTTON   -> {
            runPersistentMutation { widget.toggleMinimize() }
            true
        }
        BaseWidget.BaseHitArea.PIN_BUTTON        -> { widget.isPinned = !widget.isPinned; notifyPersistentContentChanged(); true }
        else                                     -> onElse?.invoke(hit) ?: true
    }

    private fun handleTextWidgetTap(widget: TextBoxWidget, x: Float, y: Float): Boolean =
        when (widget.hitTest(x, y)) {
            TextBoxWidget.HitArea.KEYBOARD_BUTTON -> {
                withTransientChangeBatch {
                    widget.toggleKeyboard()
                    notifyTransientContentChanged()
                }
                true
            }
            TextBoxWidget.HitArea.KEYBOARD_KEY    -> {
                tapKeyboard(widget.getKeyboard(), x, y)
                true
            }
            else -> handleBaseTap(widget, x, y) { hit ->
                if (hit == BaseWidget.BaseHitArea.CONTENT) {
                    setFocusedWidget(widget)
                    textEditing.tap(widget.textZone, x, y)
                }
                true
            }
        }

    private fun handleImageWidgetTap(widget: ImageWidget, x: Float, y: Float): Boolean =
        handleBaseTap(widget, x, y)

    private fun handleVideoWidgetTap(widget: VideoWidget, x: Float, y: Float): Boolean =
        handleBaseTap(widget, x, y) { hit -> if (hit == BaseWidget.BaseHitArea.CONTENT) {widget.handleContentTap(x, y)}; true}

    private fun handleBrowserWidgetTap(widget: BrowserWidget, x: Float, y: Float): Boolean {
        val hit = widget.hitTest(x, y)
        val persistentNavigation = hit == BrowserWidget.HitArea.NAV_HOME ||
            hit == BrowserWidget.HitArea.NEW_TAB ||
            hit == BrowserWidget.HitArea.TAB_CLOSE ||
            hit == BrowserWidget.HitArea.TAB ||
            widget.baseHitTest(x, y) == BaseWidget.BaseHitArea.CLOSE_BUTTON
        val handled = if (persistentNavigation) {
            runPersistentMutation { widget.onTap(x, y) }
        } else {
            val before = browserPersistentSignature(widget)
            runClassifiedInput(
                action = { widget.onTap(x, y) },
                changed = { browserPersistentSignature(widget) != before }
            )
        }
        if (widget.isEditingAddress()) ensureDrawnAboveNativeOverlays()
        return handled
    }

    private fun browserPersistentSignature(widget: BrowserWidget): List<Any?> = listOf(
        widget.getUrl(), widget.isFullscreen, widget.isMinimized, widget.isPinned
    )

    /** Keys typed on a glasses keyboard are classified by the text editing controller. */
    private fun tapKeyboard(keyboard: GlassesKeyboardView?, x: Float, y: Float) = withTransientChangeBatch {
        keyboard?.onTap(x, y)
        notifyTransientContentChanged()
    }

    private fun handlePdfWidgetTap(widget: PdfWidget, x: Float, y: Float): Boolean =
        handleBaseTap(widget, x, y) { hit ->
            if (hit == BaseWidget.BaseHitArea.CONTENT) {
                val before = widget.currentPage to widget.zoom
                runClassifiedInput(
                    action = { widget.handleContentTap(x, y); true },
                    changed = { (widget.currentPage to widget.zoom) != before }
                )
            } else {
                true
            }
        }

    private fun handleFileBrowserWidgetTap(widget: FileBrowserWidget, x: Float, y: Float): Boolean {
        val beforeZOrder = widget.zOrder
        val result = runPersistentMutation(
            action = {
                bringWidgetToFront(widget)
                val handled = widget.onTap(x, y)
                // Selecting a file closes the picker through its callback, which is also a
                // persistent widget removal even when the picker was already front-most.
                handled to (
                    widget.zOrder != beforeZOrder ||
                        registry.allWidgets().none { it === widget }
                    )
            },
            changed = { it.second }
        )
        if (!result.second) notifyTransientContentChanged()
        return result.first
    }

    private fun handleStatusBarTap(statusBar: StatusBarWidget, x: Float, y: Float): Boolean =
        handleBaseTap(statusBar, x, y)

    private fun handleMirrorWidgetTap(mirror: ScreenMirrorWidget, x: Float, y: Float): Boolean =
        handleBaseTap(mirror, x, y) {
            if (mirror.hitTest(x, y) == ScreenMirrorWidget.HitArea.RUN_PAUSE_BUTTON) mirror.onTap(x, y) else true
        }

    private fun handleLocationWidgetTap(loc: LocationWidget, x: Float, y: Float): Boolean {
        if (loc.onUnitTap(x, y)) {
            notifyTransientContentChanged()
            return true
        }
        return handleBaseTap(loc, x, y)
    }

    private fun handleMapWidgetTap(map: MapWidget, x: Float, y: Float): Boolean {
        val before = mapPersistentSignature(map)
        if (runClassifiedInput({ map.onTap(x, y) }, { mapPersistentSignature(map) != before })) return true
        return handleBaseTap(map, x, y)
    }

    private fun mapPersistentSignature(map: MapWidget): List<Any?> = listOf(
        map.zOrder,
        map.isFullscreen,
        map.mode,
        map.zoom,
        map.isLocationVisible(),
        map.isCountryVisible(),
        map.poiDisplayConfig
    )

    private fun handleCalendarWidgetTap(widget: CalendarWidget, x: Float, y: Float): Boolean {
        val before = widget.isFullscreen to widget.getFontScale()
        if (runClassifiedInput({ widget.onTap(x, y) }, { (widget.isFullscreen to widget.getFontScale()) != before })) return true
        return handleBaseTap(widget, x, y)
    }

    private fun handleFinanceWidgetTap(fw: FinanceWidget, x: Float, y: Float): Boolean {
        // Let the finance widget handle internal taps first (dropdown, time range)
        val before = listOf(fw.isFullscreen, fw.getTileConfigs(), fw.getNavigationIndex(), fw.getTilingSpan(), fw.getSelectedTileConfig())
        if (runClassifiedInput({ fw.onTap(x, y) }, {
                listOf(fw.isFullscreen, fw.getTileConfigs(), fw.getNavigationIndex(), fw.getTilingSpan(), fw.getSelectedTileConfig()) != before
            })
        ) return true
        return handleBaseTap(fw, x, y)
    }

    private fun handleNewsWidgetTap(nw: NewsWidget, x: Float, y: Float): Boolean {
        // Let the news widget handle arrows/content tap first.
        val before = listOf(nw.isFullscreen, nw.getSourceSpec(), nw.getSelectedIndex(), nw.getFontScale())
        if (runClassifiedInput({ nw.onTap(x, y) }, {
                listOf(nw.isFullscreen, nw.getSourceSpec(), nw.getSelectedIndex(), nw.getFontScale()) != before
            })
        ) return true
        return handleBaseTap(nw, x, y)
    }

    private fun handleNotificationsWidgetTap(widget: NotificationsWidget, x: Float, y: Float): Boolean {
        val before = listOf(widget.isFullscreen, widget.getWidgetFontScale(), widget.getToastFontScale(), widget.getScrollIndex())
        if (runClassifiedInput({ widget.onTap(x, y) }, {
                listOf(widget.isFullscreen, widget.getWidgetFontScale(), widget.getToastFontScale(), widget.getScrollIndex()) != before
            })
        ) return true
        return handleBaseTap(widget, x, y)
    }

    private fun handleSpeedometerWidgetTap(sw: SpeedometerWidget, x: Float, y: Float): Boolean {
        if (sw.onTap(x, y)) {
            notifyTransientContentChanged()
            return true
        }
        return handleBaseTap(sw, x, y)
    }

    private fun handleSubtitleWidgetTap(widget: SubtitleWidget, x: Float, y: Float): Boolean {
        val before = widget.isFullscreen to widget.options
        if (runClassifiedInput(
                action = { widget.onTap(x, y) },
                changed = { (widget.isFullscreen to widget.options) != before }
            )
        ) return true
        return handleBaseTap(widget, x, y)
    }

    fun onDoubleTap(x: Float, y: Float): Boolean {
        if (closeConfirmationPopup?.isVisible != true) {
            settingsController.handleHoverControlsLayoutEditorDoubleTap(x, y)?.let { return it }
        }

        if (contextMenu?.isVisible == true) {
            return dispatchContextMenuAction { contextMenu?.onDoubleTap(x, y) ?: false }
        }

        // Fix: Check for browser video fullscreen mode (e.g. YouTube) and exit it
        for (widget in browserWidgets) {
            if (widget.isInVideoFullscreen()) {
                widget.exitVideoFullscreen()
                return true
            }
        }

        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)

        if (isVisibleKeyboardAt(cx, cy)) return true

        if (isHoverConfirmReleaseTapGuardActive()) return true
        if (hoverControlsConsumeDoubleTap(cx, cy)) return true

        if (textEditing.onDoubleTap(x, y)) return true

        if (mapWidget?.isAddressEntryVisible() == true || mapWidget?.navigationState?.choices?.isNotEmpty() == true) {
            mapWidget?.let { map ->
                val before = mapPersistentSignature(map)
                if (runClassifiedInput({ map.onDoubleTap(cx, cy) }, { mapPersistentSignature(map) != before })) {
                    return true
                }
            }
        }

        // A fullscreen video or singleton owns every double-tap. Other fullscreen types
        // (browser, text, image, PDF, files, mirror) continue to the browser address check below.
        when (val fullscreen = registry.fullscreenWidget) {
            is VideoWidget -> {
                if (fullscreen.containsPoint(cx, cy)) {
                    withTransientChangeBatch {
                        fullscreen.handleContentDoubleTap(cx, cy)
                        notifyTransientContentChanged()
                    }
                    return true
                }
                return false
            }
            is StatusBarWidget -> {
                if (fullscreen.containsPoint(cx, cy) && fullscreen.onDoubleTap(cx, cy)) {
                    withTransientChangeBatch { notifyTransientContentChanged() }
                    return true
                }
                return false
            }
            is LocationWidget -> {
                if (fullscreen.containsPoint(cx, cy) && fullscreen.onDoubleTap(cx, cy)) {
                    withTransientChangeBatch { notifyTransientContentChanged() }
                    return true
                }
                return false
            }
            is MapWidget -> {
                val before = mapPersistentSignature(fullscreen)
                return fullscreen.containsPoint(cx, cy) &&
                        runClassifiedInput({ fullscreen.onDoubleTap(cx, cy) }, { mapPersistentSignature(fullscreen) != before })
            }
            is CalendarWidget -> {
                val before = fullscreen.isFullscreen to fullscreen.getFontScale()
                return fullscreen.containsPoint(cx, cy) &&
                        runClassifiedInput({ fullscreen.onDoubleTap(cx, cy) }, { (fullscreen.isFullscreen to fullscreen.getFontScale()) != before })
            }
            is FinanceWidget -> {
                val before = listOf(fullscreen.isFullscreen, fullscreen.getTileConfigs(), fullscreen.getNavigationIndex(), fullscreen.getTilingSpan(), fullscreen.getSelectedTileConfig())
                return fullscreen.containsPoint(cx, cy) && runClassifiedInput({ fullscreen.onDoubleTap(cx, cy) }, {
                    listOf(fullscreen.isFullscreen, fullscreen.getTileConfigs(), fullscreen.getNavigationIndex(), fullscreen.getTilingSpan(), fullscreen.getSelectedTileConfig()) != before
                })
            }
            is NewsWidget -> {
                val before = listOf(fullscreen.isFullscreen, fullscreen.getSourceSpec(), fullscreen.getSelectedIndex(), fullscreen.getFontScale())
                return fullscreen.containsPoint(cx, cy) && runClassifiedInput({ fullscreen.onDoubleTap(cx, cy) }, {
                    listOf(fullscreen.isFullscreen, fullscreen.getSourceSpec(), fullscreen.getSelectedIndex(), fullscreen.getFontScale()) != before
                })
            }
            is NotificationsWidget -> {
                val before = listOf(fullscreen.isFullscreen, fullscreen.getWidgetFontScale(), fullscreen.getToastFontScale(), fullscreen.getScrollIndex())
                return fullscreen.containsPoint(cx, cy) && runClassifiedInput({ fullscreen.onDoubleTap(cx, cy) }, {
                    listOf(fullscreen.isFullscreen, fullscreen.getWidgetFontScale(), fullscreen.getToastFontScale(), fullscreen.getScrollIndex()) != before
                })
            }
            is SubtitleWidget -> {
                val before = fullscreen.isFullscreen to fullscreen.options
                return fullscreen.containsPoint(cx, cy) &&
                        runClassifiedInput({ fullscreen.onDoubleTap(cx, cy) }, { (fullscreen.isFullscreen to fullscreen.options) != before })
            }
            is SpeedometerWidget -> {
                if (fullscreen.containsPoint(cx, cy)) {
                    settingsController.showSpeedometerSettingsMenu()
                    return true
                }
                return false
            }
            else -> Unit
        }

        // A windowed map owns double-tap anywhere on its visible shell, including the border.
        // Route it before the generic border double-tap opens the app context menu.
        if (!hasFullscreenWidget()) {
            mapWidget?.let { map ->
                if (interactionTargetAt(cx, cy) === map && map.containsPoint(cx, cy)) {
                    val before = mapPersistentSignature(map)
                    if (runClassifiedInput(
                            { map.onDoubleTap(cx, cy) },
                            { mapPersistentSignature(map) != before }
                        )) {
                        return true
                    }
                }
            }
        }

        if (!hasFullscreenWidget()) {
            val target = interactionTargetAt(cx, cy)
            if (
                target is SpeedometerWidget &&
                target.baseHitTest(cx, cy) == BaseWidget.BaseHitArea.CONTENT
            ) {
                settingsController.showSpeedometerSettingsMenu()
                return true
            }
        }

        if (!hasFullscreenWidget() && isAppContextMenuBorderDoubleTap(cx, cy)) {
            showAppContextMenu(x, y, cx, cy)
            return true
        }

        if (!hasFullscreenWidget()) {
            statusBarWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    withTransientChangeBatch { notifyTransientContentChanged() }
                    return true
                }
            }

            locationWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    withTransientChangeBatch { notifyTransientContentChanged() }
                    return true
                }
            }

            mapWidget?.let {
                val before = mapPersistentSignature(it)
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, { mapPersistentSignature(it) != before })) {
                    return true
                }
            }

            calendarWidget?.let {
                val before = it.isFullscreen to it.getFontScale()
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, { (it.isFullscreen to it.getFontScale()) != before })) {
                    return true
                }
            }

            newsWidget?.let {
                val before = listOf(it.isFullscreen, it.getSourceSpec(), it.getSelectedIndex(), it.getFontScale())
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, {
                        listOf(it.isFullscreen, it.getSourceSpec(), it.getSelectedIndex(), it.getFontScale()) != before
                    })) {
                    return true
                }
            }

            notificationsWidget?.let {
                val before = listOf(it.isFullscreen, it.getWidgetFontScale(), it.getToastFontScale(), it.getScrollIndex())
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, {
                        listOf(it.isFullscreen, it.getWidgetFontScale(), it.getToastFontScale(), it.getScrollIndex()) != before
                    })) {
                    return true
                }
            }

            financeWidget?.let {
                val before = listOf(it.isFullscreen, it.getTileConfigs(), it.getNavigationIndex(), it.getTilingSpan(), it.getSelectedTileConfig())
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, {
                        listOf(it.isFullscreen, it.getTileConfigs(), it.getNavigationIndex(), it.getTilingSpan(), it.getSelectedTileConfig()) != before
                    })) {
                    return true
                }
            }

            subtitleWidget?.let {
                val before = it.isFullscreen to it.options
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, { (it.isFullscreen to it.options) != before })) {
                    return true
                }
            }

            for (widget in videoWidgets.asReversed()) {
                if (!widget.containsPoint(cx, cy)) continue
                if (widget.baseHitTest(cx, cy) == BaseWidget.BaseHitArea.CONTENT) {
                    withTransientChangeBatch {
                        widget.handleContentDoubleTap(cx, cy)
                        notifyTransientContentChanged()
                    }
                    return true
                }
                return false
            }
        }

        focusedBrowserWidget?.let {
            if (it.isAddressHit(cx, cy)) {
                showBrowserAddressEditMenu(it)
                return true
            }
        }
        fullscreenAs<BrowserWidget>()?.let {
            if (it.isAddressHit(cx, cy)) {
                showBrowserAddressEditMenu(it)
                return true
            }
        }
        if (!hasFullscreenWidget()) {
            for (widget in browserWidgets.reversed()) {
                if (widget.isAddressHit(cx, cy)) {
                    showBrowserAddressEditMenu(widget)
                    return true
                }
            }
        }
        if (hasFullscreenWidget()) return false

        // Every explicit windowed-widget double-tap action has had an opportunity above.
        // Do not silently swallow an otherwise-unhandled double-tap just because it landed
        // inside a widget's bounds. The app context menu is the dashboard-level fallback for
        // both widget shells/content and empty dashboard space. This also makes the behavior
        // independent of the input source (temple gesture or phone pointer event).
        showAppContextMenu(x, y, cx, cy)
        return true
    }

    fun onContextMenuNavigationTap(x: Float, y: Float): Boolean {
        return dispatchContextMenuAction { contextMenu?.onNavigationTap(x, y) ?: false }
    }

    /** Lets a menu action own its classified notification while keeping navigation visual-only. */
    private fun dispatchContextMenuAction(action: () -> Boolean): Boolean {
        val before = contentNotificationGeneration()
        contextMenuDispatchDepth++
        val handled = try {
            action()
        } finally {
            contextMenuDispatchDepth--
        }
        if (handled && contentNotificationGeneration() == before) notifyTransientContentChanged()
        return handled
    }


    private fun showAppContextMenu(screenX: Float, screenY: Float, contentX: Float, contentY: Float) {
        pendingCreateX = contentX
        pendingCreateY = contentY
        contextMenu?.setLayoutLockState(isWidgetLayoutLocked)
        contextMenu?.show(screenX, screenY)
        notifyTransientContentChanged()
    }

    private fun isAppContextMenuBorderDoubleTap(cx: Float, cy: Float): Boolean {
        val widget = registry.getWidgetAt(cx, cy) ?: return false
        if (widget.isMinimized) return false

        return when (widget) {
            is TextBoxWidget -> widget.hitTest(cx, cy) == TextBoxWidget.HitArea.BORDER
            is BrowserWidget -> widget.hitTest(cx, cy) == BrowserWidget.HitArea.BORDER
            is FileBrowserWidget -> widget.hitTest(cx, cy) == FileBrowserWidget.HitArea.BORDER
            is ScreenMirrorWidget -> widget.hitTest(cx, cy) == ScreenMirrorWidget.HitArea.BORDER
            is StatusBarWidget -> widget.hitTest(cx, cy) == StatusBarWidget.HitArea.BORDER
            else -> widget.baseHitTest(cx, cy) == BaseWidget.BaseHitArea.BORDER
        }
    }

    fun onDragEnd(notify: Boolean = true) {
        cancelLongPress()
        if (financeTileDragging) {
            if (notify && financeWidget != null) {
                runPersistentMutation(
                    action = { financeWidget?.endTileDrag() == true },
                    changed = { it }
                )
            } else {
                financeWidget?.endTileDrag()
            }
            financeTileDragging = false
        }
        if (activeWidget != null) {
            val widget = activeWidget
            activeWidget = null
            runPersistentMutation(
                action = { widget?.onDragEnd(); true },
                changed = { notify && it }
            )
        }
    }

    private fun handleMenuItemSelected(item: ContextMenu.MenuItem) {
        userInputActivity?.onUserInput(UserInputKind.SHORTCUT)
        commandRegistry.find(item.id)?.let { executeCommand(commandRegistry.invocation(it)) }
    }

    // ==================== Setters and Getters ====================



    /** setters and getters for brightness and head-up */
    fun setBrightness(value: Float) {settingsController.setBrightness(value)}
    fun setAdaptiveBrightnessEnabled(enabled: Boolean) {settingsController.setAdaptiveBrightnessEnabled(enabled)}
    fun setHeadUpTime(ms: Long) {settingsController.setHeadUpTime(ms)} /**Set the head-up time programmatically (used for restoring persisted state).*/
    fun getHeadUpTime(): Long = settingsController.getHeadUpTime() /** Get the current head-up time (used for persistence).*/
    fun setWakeDuration(ms: Long) {settingsController.setWakeDuration(ms)} /** Set the wake duration programmatically (used for restoring persisted state).*/
    fun getWakeDuration(): Long = settingsController.getWakeDuration() /** Get the current wake duration (used for persistence). */
    fun setAngleThreshold(degrees: Float) {settingsController.setAngleThreshold(degrees)} /** Set the angle threshold programmatically (used for restoring persisted state). */
    fun getAngleThreshold(): Float = settingsController.getAngleThreshold() /** Get the current angle threshold (used for persistence). */
    fun setHeadsUpEnabled(enabled: Boolean) {settingsController.setHeadsUpEnabled(enabled)} /** Set the heads-up enabled state programmatically (used for restoring persisted state). */
    fun getHeadsUpEnabled(): Boolean = settingsController.getHeadsUpEnabled() /** Get the current heads-up enabled state (used for persistence). */

    /** setter for Google authentication*/
    fun setGoogleAuthState(state: GoogleAuthState) {settingsController.setGoogleAuthState(state)}

    /**
     * Get updated submenu items with current widget presence state.
     */
    private fun getUpdatedSubmenuItems(item: ContextMenu.MenuItem): List<ContextMenu.SubMenuItem> {
        if (item.id == "toggle_widgets") {
            return WidgetDescriptors.all
                .filter { it.commandId != null }
                .sortedBy { it.menuOrder }
                .map { descriptor ->
                    val definition = commandRegistry.widgetDefinition(requireNotNull(descriptor.commandId))
                    ContextMenu.SubMenuItem(
                        definition.id,
                        descriptor.label,
                        registry.widgets(descriptor.type).isNotEmpty()
                    )
                }
        }
        if (item.id == "layouts") {
            val loadItems = commandRegistry.shortcutDefinitions().mapNotNull { definition ->
                commandRegistry.layoutName(definition)?.let { ContextMenu.SubMenuItem(definition.id, it) }
            }
            return listOf(
                ContextMenu.SubMenuItem(commandRegistry.saveLayout.id, commandRegistry.saveLayout.label),
                ContextMenu.SubMenuItem(commandRegistry.saveLayoutAs.id, commandRegistry.saveLayoutAs.label),
                ContextMenu.SubMenuItem("layout_load_menu", "Load", submenu = loadItems)
            )
        }
        return item.submenu ?: emptyList()
    }

    fun setSmartAlignmentEnabled(enabled: Boolean) {
        smartAlignment.enabled = enabled
    }
    fun setWidgetLayoutLocked(locked: Boolean, persist: Boolean = true) {
        if (isWidgetLayoutLocked == locked) {
            contextMenu?.setLayoutLockState(isWidgetLayoutLocked)
            return
        }
        isWidgetLayoutLocked = locked
        contextMenu?.setLayoutLockState(isWidgetLayoutLocked)
        if (locked) {
            cancelLongPress()
            onDragEnd(notify = false)
        }
        if (persist) notifyPersistentContentChanged()
    }

    private fun handleSubmenuItemDoubleTapped(subItem: ContextMenu.SubMenuItem): Boolean {
        val definition = commandRegistry.find(subItem.id) ?: return false
        val name = commandRegistry.layoutName(definition) ?: return false
        return layoutManager.requestDeleteLayout(name)
    }

    private fun createStatusBarPresence(): StatusBarWidget {
        val statusWidth = 220f
        val statusHeight = 110f
        return StatusBarWidget(
            (width - statusWidth) / 2f,
            height - statusHeight - height * 0.15f,
            statusWidth,
            statusHeight
        )
    }

    private fun createLocationPresence(): LocationWidget {
        val locationWidth = 300f
        val locationHeight = 120f
        val widget = LocationWidget(context, 20f, height - locationHeight - 20f, locationWidth, locationHeight)
        locationWeatherBinding.configure(widget)
        return widget
    }

    private fun createMapPresence(): MapWidget {
        val size = 210f
        return MapWidget(context, width - size - 24f, 24f, size, size).apply {
            invalidate = ::requestRepaint
            configurePhoneRouteTransport(phoneRouteTransport)
            onPhoneRouteConnectionChanged(phoneRouteConnected)
            bindMapNavigation(this)
            pendingMapLocation?.let(::setLocation)
            setLocationMetadata(pendingMapLocationLabel, pendingMapCountryCode)
        }
    }

    fun updateMapLocation(location: android.location.Location) {
        pendingMapLocation = android.location.Location(location)
        mapWidget?.setLocation(location)
        notifyTransientContentChanged()
    }

    fun requestPhoneMapRefresh() {
        mapWidget?.requestPhoneMapRefresh()
    }

    fun applyMapSnapshot(snapshot: com.everyday.shared.sync.MapSnapshot) {
        mapWidget?.applyProxyMap(snapshot)
        notifyTransientContentChanged()
    }

    private fun updateMapMetadata(location: String, countryCode: String) {
        pendingMapLocationLabel = location
        pendingMapCountryCode = countryCode
        mapWidget?.setLocationMetadata(location, countryCode)
    }

    private fun createCalendarPresence(): CalendarWidget {
        val startX = (width - CalendarWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
        val startY = (height - CalendarWidget.DEFAULT_HEIGHT - 160f).coerceAtLeast(20f)
        return CalendarWidget(
            startX,
            startY,
            CalendarWidget.DEFAULT_WIDTH,
            CalendarWidget.DEFAULT_HEIGHT
        ).apply {
            setSnapshot(pendingCalendarSnapshot)
        }
    }

    private fun createSpeedometerPresence(): SpeedometerWidget {
        val speedWidth = 220f
        val speedHeight = 110f
        val startX = (width - speedWidth - 20f).coerceAtLeast(20f)
        val startY = (height - speedHeight - 20f).coerceAtLeast(20f)
        return createSpeedometerWidget(startX, startY, speedWidth, speedHeight)
    }

    private fun createFinancePresence(): FinanceWidget {
        val widget = FinanceWidget(20f, 20f, FinanceWidget.DEFAULT_WIDTH, FinanceWidget.DEFAULT_HEIGHT)
        financeFeatures.configure(widget)
        financeFeatures.onOpened(widget, reason = "widget_toggled_on")
        return widget
    }

    private fun createNewsPresence(): NewsWidget {
        val startX = (width - NewsWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
        val widget = NewsWidget(startX, 20f, NewsWidget.DEFAULT_WIDTH, NewsWidget.DEFAULT_HEIGHT)
        newsFeatures.configure(widget)
        newsFeatures.onOpened(widget, reason = "widget_toggled_on")
        return widget
    }

    private fun createNotificationsPresence(): NotificationsWidget {
        val startX = (width - NotificationsWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
        return NotificationsWidget(
            startX, 20f, NotificationsWidget.DEFAULT_WIDTH,
            NotificationsWidget.DEFAULT_HEIGHT, height.toFloat()
        ).apply {setNotificationsSnapshot(latestNotificationsSnapshot)}
    }

    private fun createSubtitlePresence(): SubtitleWidget {
        val subtitleWidth = SubtitleWidget.DEFAULT_WIDTH.coerceAtMost(width * 0.82f)
        val subtitleHeight = SubtitleWidget.DEFAULT_HEIGHT
        val startX = ((width - subtitleWidth) / 2f).coerceAtLeast(20f)
        val startY = (height - subtitleHeight - 72f).coerceAtLeast(20f)
        return createSubtitleWidget(startX, startY, subtitleWidth, subtitleHeight, autoStart = true)
    }

    private fun createMirrorPresence(): ScreenMirrorWidget {return ScreenMirrorWidget(context, 20f, 20f, 180f, 320f)}

    fun createBrowserWidget(x: Float, y: Float) {
        if (openClosedBrowserTemplate() != null) {
            return
        }
        val boxWidth = width * 0.45f
        val boxHeight = height * 0.5f
        val adjustedX = x.coerceIn(0f, width - boxWidth)
        val adjustedY = y.coerceIn(0f, height - boxHeight)
        val rootView = parent as? ViewGroup ?: return
        val widget = browserWidgetFactory(rootView).create(
            adjustedX,
            adjustedY,
            boxWidth,
            boxHeight,
        )
        runPersistentMutation {
            lifecycleController.open(browserLifecycle) { widget }
            widget.toggleFullscreen()
            widget.setFocused(true)
            setFocusedWidget(widget)
        }
    }

    private fun bindMapNavigation(map: MapWidget) {
        map.listener = object : MapWidgetListener {
            override fun onMapDataRequested(point: MapGeoPoint, config: PoiDisplayConfig) {
                dataProvider?.requestMapData(point, config)
            }
            override fun onAddressFocusChanged(focused: Boolean) {
                val beforeZOrder = map.zOrder
                if (focused) bringWidgetToFront(map)
                notifyFocusChanged(persistent = focused && map.zOrder != beforeZOrder)
            }
            override fun onNavigationResult(result: NavigationOperationResult) = notifyTransientContentChanged()
            override fun onStopNavigationRequested() = stopNavigationFromMap()
        }
    }

    fun configurePhoneRouteTransport(transport: GlassesRouteTransport?) {
        phoneRouteTransport = transport
        mapWidget?.configurePhoneRouteTransport(transport)
    }

    fun onPhoneRouteConnectionChanged(connected: Boolean) {
        phoneRouteConnected = connected
        mapWidget?.onPhoneRouteConnectionChanged(connected)
    }

    fun onPhoneRouteResult(result: com.everyday.shared.transport.RouteTransportResult) {
        mapWidget?.onPhoneRouteResult(result)
    }

    fun onPhoneRouteError(error: com.everyday.shared.transport.RouteTransportError) {
        mapWidget?.onPhoneRouteError(error)
    }

    fun onPhoneDestinationSearchResult(result: com.everyday.shared.transport.DestinationSearchResult) {
        mapWidget?.onPhoneDestinationSearchResult(result)
    }

    fun onPhoneDestinationSearchError(error: com.everyday.shared.transport.DestinationSearchError) {
        mapWidget?.onPhoneDestinationSearchError(error)
    }

    private fun stopNavigationFromMap() {
        // Explicit stop cancels the temporary voice presentation without restoring an older map
        // rectangle; the current map/POI/orientation state remains visible.
        withTransientChangeBatch {
            dismissMapPresentation()
            mapWidget?.stopNavigation()
            notifyTransientContentChanged()
        }
    }

    private fun dismissMapPresentation() {
        val map = mapWidget ?: return
        if (activePresentationSession?.targetId == widgetStableId(map)) {
            dismissPresentation()
        }
    }

    /** Create a visible windowed browser for a normal assistant browser task. */
    fun createPageAgentBrowser(): PageAgentBrowserAdapter? {
        if (width <= 0 || height <= 0) return null
        val rootView = parent as? ViewGroup ?: return null
        val boxWidth = width * 0.45f
        val boxHeight = height * 0.5f
        val adjustedX = pendingCreateX.coerceIn(0f, width - boxWidth)
        val adjustedY = pendingCreateY.coerceIn(0f, height - boxHeight)
        val widget = browserWidgetFactory(rootView).create(
            adjustedX,
            adjustedY,
            boxWidth,
            boxHeight,
        )
        return runPersistentMutation(
            action = {
                lifecycleController.open(browserLifecycle) { widget }?.let { opened ->
                    opened.setFocused(true)
                    setFocusedWidget(opened)
                    BrowserWidgetPageAgentAdapter.bind(stableWidgetId(opened), opened)
                        ?: run {
                            lifecycleController.close(opened)
                            null
                        }
                }
            },
            changed = { true },
        )
    }

    private fun openBrowserAddress(address: String) {
        val widget = focusedBrowserWidget
            ?: fullscreenAs<BrowserWidget>()
            ?: createFullscreenBrowserWidget()
            ?: return

        runPersistentMutation {
            if (widget.isMinimized) widget.toggleMinimize()
            bringWidgetToFront(widget)
            widget.setFocused(true)
            setFocusedWidget(widget)
            widget.navigateToAddress(address, autoFullscreenVideo = true)
        }
    }

    private fun createFullscreenBrowserWidget(): BrowserWidget? {
        val rootView = parent as? ViewGroup ?: return null
        val widget = browserWidgetFactory(rootView).create(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
        )
        return runPersistentMutation {
            lifecycleController.open(browserLifecycle) { widget }
            widget.toggleFullscreen()
            widget
        }
    }

    /**
     * Create a fullscreen browser widget that opens YouTube history.
     * Called when the YouTube button is tapped.
     */
    private fun createYouTubeBrowserWidget() {
        val rootView = parent as? ViewGroup ?: return
        val widget = browserWidgetFactory(rootView).create(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
        )
        runPersistentMutation {
            lifecycleController.open(browserLifecycle) { widget }
            widget.getWebView()?.loadUrl("https://www.youtube.com/feed/history")
            widget.toggleFullscreen()
            widget.setFocused(true)
            setFocusedWidget(widget)
        }
    }

    fun createImageWidget(path: String, x: Float = -1f, y: Float = -1f) {
        val createX = if (x >= 0) x else pendingCreateX
        val createY = if (y >= 0) y else pendingCreateY
        
        val widget = ImageWidget(createX, createY, path)
        lifecycleController.open(imageLifecycle) { widget }
    }

    fun createVideoWidget(
        uri: android.net.Uri,
        displayName: String,
        x: Float = -1f,
        y: Float = -1f,
    ) {
        val rootView = parent as? ViewGroup ?: return
        val createX = if (x >= 0f) x else ((width - 360f) / 2f).coerceAtLeast(0f)
        val createY = if (y >= 0f) y else ((height - 220f) / 2f).coerceAtLeast(0f)
        val widget = videoWidgetFactory(rootView).create(uri, displayName, createX, createY)
        runPersistentMutation {
            lifecycleController.open(videoLifecycle) { widget }
            widget.toggleFullscreen()
        }
    }

    fun createPdfWidget(uri: android.net.Uri, displayName: String, x: Float = -1f, y: Float = -1f) {
        val createX = if (x >= 0f) x else ((width - 360f) / 2f).coerceAtLeast(0f)
        val createY = if (y >= 0f) y else ((height - 260f) / 2f).coerceAtLeast(0f)
        val widget = PdfWidget(context, uri, displayName, createX, createY)
        runPersistentMutation {
            lifecycleController.open(pdfLifecycle) { widget }
            widget.toggleFullscreen()
        }
    }

    /**
     * Create a file browser widget for selecting files.
     * @param filter The file type filter to use (default: IMAGES)
     * @param onFileSelected Callback when a file is selected (provides FileEntry with path and optional URI)
     */
    fun createFileBrowserWidget(
        filter: FileBrowserWidget.FileFilter = FileBrowserWidget.FileFilter.IMAGES,
        x: Float = -1f,
        y: Float = -1f,
        onFileSelected: ((FileBrowserWidget.FileEntry) -> Unit)? = null
    ) {
        // Use screen-relative sizing (50% width, 70% height) to avoid widget being too large
        val browserWidth = (width * 0.5f).coerceIn(300f, 500f)
        val browserHeight = (height * 0.7f).coerceIn(300f, 600f)

        // Center the widget if no position specified, otherwise use the specified position
        val createX = if (x >= 0) x.coerceIn(0f, (width - browserWidth).coerceAtLeast(0f))
                      else ((width - browserWidth) / 2f).coerceAtLeast(0f)
        val createY = if (y >= 0) y.coerceIn(0f, (height - browserHeight).coerceAtLeast(0f))
                      else ((height - browserHeight) / 2f).coerceAtLeast(0f)

        val widget = FileBrowserWidget(
            context,
            createX,
            createY,
            browserWidth,
            browserHeight,
            filter
        ).apply {
            listener = object : FileBrowserWidgetListener {
                override fun onFileSelected(entry: FileBrowserWidget.FileEntry) {
                    onFileSelected?.invoke(entry)
                    removeFileBrowserWidget(this@apply)
                }
            }
        }
        lifecycleController.open(filesLifecycle) { widget }
        // Opening the picker may complete asynchronously after a runtime/all-files permission
        // screen. Re-center after the widget actually exists so hover chrome is visible at once
        // and two-finger scrolling is routed to the new browser without a priming drag.
        host?.requestCursorCenter()
    }
    private fun removeFileBrowserWidget(widget: FileBrowserWidget) {lifecycleController.close(widget)}

    private fun buildTextBox(x: Float, y: Float, boxWidth: Float, boxHeight: Float): TextBoxWidget = TextBoxWidget(x, y, boxWidth, boxHeight)

    fun createTextBox(x: Float, y: Float) {
        if (openClosedTextTemplate() != null) {
            return
        }
        val boxWidth = width * DEFAULT_TEXTBOX_WIDTH_PERCENT; val boxHeight = height * DEFAULT_TEXTBOX_HEIGHT_PERCENT
        val adjustedX = x.coerceIn(0f, width - boxWidth); val adjustedY = y.coerceIn(0f, height - boxHeight)
        val widget = buildTextBox(adjustedX, adjustedY, boxWidth, boxHeight)
        runPersistentMutation {
            lifecycleController.open(textLifecycle) { widget }
            setFocusedWidget(widget)
        }
    }

    /**
     * Focus for typing: a text widget's text area, or a browser page whose web content receives
     * keys directly. Other fields request focus from the text editing controller themselves.
     */
    private fun setFocusedWidget(widget: BaseWidget?) {
        when (widget) {
            is TextBoxWidget -> {
                setBrowserFocus(null)
                textEditing.focus(widget.textZone)
            }
            is BrowserWidget -> {
                if (textEditing.focusedZone !== widget.addressZone) textEditing.clearFocus()
                setBrowserFocus(widget)
            }
            else -> {
                textEditing.clearFocus()
                setBrowserFocus(null)
            }
        }
    }

    private fun setBrowserFocus(widget: BrowserWidget?) {
        val previous = focusedBrowserWidget
        if (previous === widget) return
        focusedBrowserWidget = widget
        previous?.setFocused(false)
        notifyFocusChanged()
    }

    /** Only one thing takes typed keys: a newly focused text zone ends web page typing. */
    private fun onTextZoneFocusChanged() {
        val browser = focusedBrowserWidget
        val zone = textEditing.focusedZone
        if (browser != null && zone != null && zone !== browser.addressZone) {
            focusedBrowserWidget = null
            browser.setFocused(false)
        }
        notifyFocusChanged()
    }

    fun unfocusAll() {mapWidget?.takeIf { it.isAddressEntryFocused() }?.closeAddressEntry(); setFocusedWidget(null)}

    /** Keys go to the focused text zone first, otherwise to the focused browser's web page. */
    fun onKeyPress(key: String, userInitiated: Boolean = false) {
        if (userInitiated) userInputActivity?.onUserInput(UserInputKind.KEYBOARD)
        if (textEditing.onKey(key)) return
        val browser = focusedBrowserWidget ?: return
        val before = browserPersistentSignature(browser)
        runClassifiedInput(
            action = {
                browser.onKeyPress(key)
                true
            },
            changed = { browserPersistentSignature(browser) != before }
        )
    }

    private fun ensureDrawnAboveNativeOverlays() {
        val rootView = parent as? ViewGroup ?: return
        if (rootView.indexOfChild(this) != rootView.childCount - 1) {
            rootView.bringChildToFront(this)
        }
        if (elevation < 10f) elevation = 10f
    }

    // ==================== Text Edit Menu ====================
    private fun showBrowserAddressEditMenu(widget: BrowserWidget) {
        widget.beginAddressEditing(selectAll = true)
        ensureDrawnAboveNativeOverlays()
        textEditing.showTextEditMenu()
    }

    // ==================== Public Accessors ====================
    fun setPhoneClipboard(content: String?)                                                                             = textEditing.setPhoneClipboard(content)
    fun hasFocusedWidget():                                                                                     Boolean = textEditing.hasFocus || focusedBrowserWidget != null
    fun isMenuVisible():                                                                                        Boolean = contextMenu?.isVisible == true || textEditing.isMenuVisible || layoutNamePrompt?.isVisible == true
    fun isClosePopupVisible():                                                                                  Boolean = closeConfirmationPopup?.isVisible == true || layoutDeleteConfirmationPopup?.isVisible == true
    fun isAnyPopupVisible():                                                                                    Boolean = isMenuVisible() || isClosePopupVisible() || pageAgentApprovalOverlay.isVisible()
    fun showPageAgentApproval(runId: String, request: PageAgentConfirmationRequest, respond: (Boolean) -> Unit) {pageAgentApprovalOverlay.show(runId, request, respond); ensureDrawnAboveNativeOverlays()}
    fun dismissPageAgentApproval(runId: String)                                                                 {pageAgentApprovalOverlay.dismiss(runId)}
    fun dismissMenu()                                                                                           { contextMenu?.dismiss(); textEditing.dismissMenus(); layoutNamePrompt?.dismiss() }
    fun isDragging():                                                                                           Boolean = activeWidget != null || financeTileDragging || textEditing.formattingMenu?.isDragging() == true

    // ==================== 3DOF Mode ====================
    /**
     * Set 3DOF mode enabled state. Called by ThreeDofManager.
     */
    fun set3DofEnabled(enabled: Boolean) {
        if (is3DofEnabled != enabled) {
            is3DofEnabled = enabled
            threeDofOverlay.set3DofEnabled(enabled)
            Log.d(TAG,"3DOF mode ${if (enabled) "enabled" else "disabled"}")
            notifyTransientContentChanged()
        }
    }
    fun set3DofOffset(offsetX: Float, offsetY: Float) {
        dashboardTransform.setOffset(offsetX, offsetY)
        threeDofOverlay.setContentOffset(offsetX, offsetY)
        notifyTransientContentChanged()
    }
    fun set3DofRoll(rollDeg: Float) {dashboardTransform.setRoll(rollDeg); notifyTransientContentChanged()}
    fun set3DofTransitionProgress(progress: Float) {threeDofOverlay.setTransitionProgress(progress); notifyTransientContentChanged()}
    private fun shouldApplyThreeDofTransform(): Boolean = displayState == WakeSleepManager.DisplayState.WAKE && !hasFullscreenWidget() && (is3DofEnabled || threeDofOverlay.isVisible())
    private fun currentDashboardTransform(): DashboardTransform.Snapshot =  dashboardTransform.snapshot(viewportWidth = width.toFloat(), viewportHeight = height.toFloat(), applies = shouldApplyThreeDofTransform())

    /** Convert the fixed screen cursor to the coordinate space in which widgets are drawn. */
    fun screenToContentCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> = currentDashboardTransform().screenToContent(screenX, screenY)
    // ==================== Drawing ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (displayState == WakeSleepManager.DisplayState.OFF) return

        if (displayState == WakeSleepManager.DisplayState.SLEEP) {
            // Check if there's a PINNED fullscreen widget - if so, only draw that one (it covers others)
            val pinnedFullscreenWidget = registry.fullscreenWidget?.takeIf(BaseWidget::isPinned)

            if (pinnedFullscreenWidget != null) {
                // Only draw the pinned fullscreen widget - it covers everything else
                pinnedFullscreenWidget.draw(canvas)
                if (!settingsController.isHoverControlsLayoutEditorVisible()) {drawHoverControls(canvas)}
                drawVisibleKeyboardOverlays(canvas)
                drawPresentationProgress(canvas)
                drawOverlayMenus(canvas)
                return
            }

            // No pinned fullscreen widget - draw all pinned widgets normally
            for (widget in registry.getPinnedWidgetsSortedByZOrder()) widget.draw(canvas)
            if (!settingsController.isHoverControlsLayoutEditorVisible()) {
                drawHoverControls(canvas)
            }
            drawVisibleKeyboardOverlays(canvas)
            drawPresentationProgress(canvas)
            drawOverlayMenus(canvas)
            return
        }

        // Handle fullscreen widgets (not affected by 3DOF transform)
        if (hasFullscreenWidget()) {
            registry.fullscreenWidget?.draw(canvas)
            // Hover controls stay above fullscreen widgets so the user can always reach
            // them (Close, YouTube history, the lemon shortcuts, …).
            if (!settingsController.isHoverControlsLayoutEditorVisible()) {
                drawHoverControls(canvas)
            }
            drawVisibleKeyboardOverlays(canvas)
            drawPresentationProgress(canvas)
            drawOverlayMenus(canvas)
            return
        }

        val transform = currentDashboardTransform()
        if (transform.applies) {canvas.save() ; transform.applyTo(canvas)} // Dashboard content moves in 3DOF; head-locked menus are drawn after the Canvas is restored.

        // While the hover-controls layout editor is open, hide widgets and the regular
        // dashboard hover overlays — the editor renders the controls itself in their
        // dashboard positions.
        val editorActive = settingsController.isHoverControlsLayoutEditorVisible()
        if (!editorActive) {
            for (widget in registry.getAllSortedByZOrder()) {widget.draw(canvas)} // Draw all widgets (affected by 3DOF transformation)
            drawHoverControls(canvas)                // Hover controls are dashboard overlays and should visually win over any widget they overlap while hovered, except for visible keyboard keys.
            drawVisibleKeyboardOverlays(canvas)
        }

        // Draw smart alignment guides (only present while a drag/resize is active
        // and at least one edge is within snap tolerance of a candidate line)
        smartAlignmentRenderer.draw(
            canvas,
            smartAlignment.getActiveGuides(),
            width.toFloat(),
            height.toFloat()
        )

        threeDofOverlay.draw(canvas)            // Draw 3DOF overlay (boundary rectangle - moves with content to show head-locked area)
        if (transform.applies) canvas.restore() // Restore canvas if 3DOF was applied
        drawPresentationProgress(canvas)
        drawOverlayMenus(canvas)         // Draw menus (not affected by 3DOF - stay head-locked like cursor)

        // Note: Brightness dimming is handled at window level via WindowManager.LayoutParams.screenBrightness
        // This ensures the entire app (including WebViews) is dimmed uniformly.
    }

    private fun drawOverlayMenus(canvas: Canvas) {
        val mapOverlayVisible = mapWidget?.let {it.isAddressEntryVisible() || it.navigationState.choices.isNotEmpty()} == true
        if (isAnyPopupVisible() || mapOverlayVisible || focusedBrowserWidget?.isEditingAddress() == true || fullscreenAs<BrowserWidget>()?.isEditingAddress() == true) {ensureDrawnAboveNativeOverlays()}
        mapWidget?.drawScreenOverlay(canvas)
        contextMenu?.draw(canvas)
        settingsController.drawHoverControlsLayoutEditor(canvas)  // Draw the hover-controls editor before the close-app confirmation so the popup appears on top of it when the user invokes Close from the editor's context menu.
        closeConfirmationPopup?.draw(canvas)
        layoutDeleteConfirmationPopup?.draw(canvas)
        settingsController.draw(canvas)
        layoutNamePrompt?.draw(canvas)
        textEditing.drawMenus(canvas)
        pageAgentApprovalOverlay.draw(canvas)
    }

    private fun drawHoverControls(canvas: Canvas) {
        hoverControls.forEach { it.draw(canvas) }
    }

    private fun drawVisibleKeyboardOverlays(canvas: Canvas) {
        if (hasFullscreenWidget()) {
            fullscreenAs<BrowserWidget>()?.takeIf { it.isKeyboardVisible }?.getKeyboard()?.draw(canvas)
            fullscreenAs<TextBoxWidget>()?.takeIf { it.isKeyboardVisible }?.getKeyboard()?.draw(canvas)
            return
        }

        registry.getAllSortedByZOrder().forEach { widget ->
            when (widget) {
                is BrowserWidget -> if (!widget.isMinimized && widget.isKeyboardVisible) {
                    widget.getKeyboard()?.draw(canvas)
                }
                is TextBoxWidget -> if (!widget.isMinimized && widget.isKeyboardVisible) {
                    widget.getKeyboard()?.draw(canvas)
                }
            }
        }
    }

    private fun drawPresentationProgress(canvas: Canvas) {
        val progress = presentationProgress ?: return
        val target = findWidgetByStableId(progress.targetId) ?: return
        val bounds = target.getBounds()
        val radius = 9f
        val centerX = (bounds.left + 16f).coerceIn(radius + 2f, width - radius - 2f)
        val centerY = (bounds.top + 16f).coerceIn(radius + 2f, height - radius - 2f)
        val ring = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawCircle(centerX, centerY, radius, presentationProgressBackgroundPaint)
        canvas.drawArc(ring, -90f, progress.fraction * 360f, false, presentationProgressPaint)
    }

    // ==================== Keyboard overlay input priority ====================

    private fun visibleKeyboardHostAt(cx: Float, cy: Float): BaseWidget? {
        if (hasFullscreenWidget()) {
            fullscreenAs<BrowserWidget>()?.let {
                if (it.isKeyboardVisible && it.getKeyboard()?.containsPoint(cx, cy) == true) return it
            }
            fullscreenAs<TextBoxWidget>()?.let {
                if (it.isKeyboardVisible && it.getKeyboard()?.containsPoint(cx, cy) == true) return it
            }
            fullscreenAs<MapWidget>()?.let {
                if (it.isAddressKeyboardVisible() && it.getAddressKeyboard()?.containsPoint(cx, cy) == true) return it
            }
            return null
        }

        return registry.getAllSortedByZOrder()
            .asReversed()
            .firstOrNull { widget ->
                when (widget) {
                    is BrowserWidget -> !widget.isMinimized &&
                        widget.isKeyboardVisible &&
                        widget.getKeyboard()?.containsPoint(cx, cy) == true
                    is TextBoxWidget -> !widget.isMinimized &&
                        widget.isKeyboardVisible &&
                        widget.getKeyboard()?.containsPoint(cx, cy) == true
                    is MapWidget -> !widget.isMinimized &&
                        widget.isAddressKeyboardVisible() &&
                        widget.getAddressKeyboard()?.containsPoint(cx, cy) == true
                    else -> false
                }
            }
    }

    private fun isVisibleKeyboardAt(cx: Float, cy: Float): Boolean =
        visibleKeyboardHostAt(cx, cy) != null

    private fun updateKeyboardHoverAt(cx: Float, cy: Float): Boolean {
        val host = visibleKeyboardHostAt(cx, cy) ?: return false
        host.updateHover(cx, cy)
        return true
    }

    private fun consumeVisibleKeyboardTap(cx: Float, cy: Float): Boolean {
        return when (val host = visibleKeyboardHostAt(cx, cy)) {
            is BrowserWidget -> {
                val before = browserPersistentSignature(host)
                runClassifiedInput(
                    action = { host.getKeyboard()?.onTap(cx, cy) == true },
                    changed = { browserPersistentSignature(host) != before }
                )
                true
            }
            is TextBoxWidget -> {
                tapKeyboard(host.getKeyboard(), cx, cy)
                true
            }
            is MapWidget -> {
                tapKeyboard(host.getAddressKeyboard(), cx, cy)
                true
            }
            else -> false
        }
    }

    private fun clearWidgetHoverStatesExcept(except: BaseWidget?) {
        hoveredWidget = except
        registry.forEachWidget {
            if (it !== except) it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        }
    }

    // ==================== Hover-control input pipeline ====================

    /**
     * Forward a cursor-down. Returns true if a hover control captured the press; the
     * caller should stop further widget hit-testing in that case.
     */
    private fun hoverControlsConsumeDown(cx: Float, cy: Float): Boolean {
        if (isAnyModalOverHoverControls()) return false
        // Iterate top-most last so it gets first crack (matches draw order which is also
        // last-on-top).
        for (control in hoverControls.asReversed()) {
            if (!control.isPlaced) continue
            if (control.onCursorDown(cx, cy)) {
                capturedHoverControl = control
                invalidateHoverVisuals()
                return true
            }
        }
        return false
    }

    private fun isAnyModalOverHoverControls(): Boolean {
        if (settingsController.isHoverControlsLayoutEditorVisible()) return true
        if (settingsController.isShortcutsSettingsMenuVisible()) return true
        if (settingsController.isDashboardSettingsVisible()) return true
        if (settingsController.isSpeedometerSettingsMenuVisible()) return true
        if (mapWidget?.isAddressEntryVisible() == true || mapWidget?.navigationState?.choices?.isNotEmpty() == true) return true
        if (closeConfirmationPopup?.isVisible == true) return true
        if (layoutDeleteConfirmationPopup?.isVisible == true) return true
        if (layoutNamePrompt?.isVisible == true) return true
        if (contextMenu?.isVisible == true) return true
        if (textEditing.isMenuVisible) return true
        return false
    }

    private fun hoverControlsConsumeMove(cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        val captured = capturedHoverControl ?: return false
        return captured.onCursorMove(cx, cy, dx, dy)
    }

    private fun hoverControlsConsumeUp(cx: Float, cy: Float): Boolean {
        val captured = capturedHoverControl ?: return false
        val handled = captured.onCursorUp(cx, cy)
        if (handled && captured.shouldGuardTapAfterCursorUp()) {
            hoverConfirmTapGuardUntilMs = System.currentTimeMillis() + HOVER_CONFIRM_RELEASE_TAP_GUARD_MS
        }
        capturedHoverControl = null
        return handled
    }

    /**
     * Tap pass for hover controls. Any control with a still-active interaction state
     * (e.g. the lemon in CONFIRMING) gets the tap first, even when the cursor is outside
     * its idle bounds — taps outside a magnified slice need to reach the lemon to cancel
     * the selection.
     */
    private fun hoverControlsConsumeTap(cx: Float, cy: Float): Boolean {
        // Interacting controls always take precedence regardless of cursor position.
        for (control in hoverControls.asReversed()) {
            if (control.isInteracting() && control.handleTap(cx, cy)) {
                return true
            }
        }
        if (isAnyModalOverHoverControls()) return false
        for (control in hoverControls.asReversed()) {
            if (!control.isPlaced) continue
            if (control.handleTap(cx, cy)) return true
        }
        return false
    }

    private fun hoverControlsConsumeDoubleTap(cx: Float, cy: Float): Boolean {
        for (control in hoverControls.asReversed()) {
            if (control.isInteracting() && control.handleDoubleTap(cx, cy)) {
                notifyTransientContentChanged()
                if (control === lemonHoverControl) {
                    host?.requestCursorCenter()
                }
                return true
            }
        }
        return false
    }

    private fun isHoverConfirmReleaseTapGuardActive(): Boolean {
        if (hoverConfirmTapGuardUntilMs <= 0L) return false
        if (System.currentTimeMillis() > hoverConfirmTapGuardUntilMs) {
            hoverConfirmTapGuardUntilMs = 0L
            return false
        }
        return hoverControls.any { it.isInteracting() }
    }

    private fun cancelHoverControlInteractions() {
        for (control in hoverControls) control.cancelInteraction()
        capturedHoverControl = null
        hoverConfirmTapGuardUntilMs = 0L
    }

    // ==================== Persistence ====================

    fun capturePersistedDashboard(): WidgetDocument.PersistedDashboard =
        layoutManager.capturePersistedDashboard()

    private fun captureWidgetDashboardWithoutLayoutName(): WidgetDocument.PersistedDashboard =
        WidgetDocument.PersistedDashboard(
            widgets = registry.allWidgets().mapNotNull { widget ->
                lifecycleController.captureRecord(widget, registry.stableId(widget))
            },
            closedTemplates = lifecycleController.closedTemplateRecords(),
            hoverControls = settingsController.getHoverControlPlacements(),
            isFirstRun = false,
            isLayoutLocked = isWidgetLayoutLocked
        )

    // ==================== Shortcuts ====================

    private fun applyShortcuts(invocations: List<AppCommandInvocation>) {
        val list = ensureLemonShortcuts()
        list.clear()
        // De-dup by id and cap to MAX_LEMON_SLICES.
        val seen = mutableSetOf<String>()
        for (invocation in invocations) {
            if (invocation.commandId in seen) continue
            val definition = commandRegistry.find(invocation.commandId) ?: continue
            if (!definition.shortcutEligible) continue
            seen.add(invocation.commandId)
            list.add(invocation)
            if (list.size >= AppCommandRegistry.MAX_LEMON_COMMANDS) break
        }
        ShortcutsStore.save(context, commandRegistry, list.toList())
        lemonHoverControl.cancelInteraction()
        notifyTransientContentChanged()
    }

    fun executeCommand(invocation: AppCommandInvocation): AppCommandResult = commandExecutor.execute(invocation)

    fun configureBrowserRouting(routing: BrowserRoutingCoordinator?) {
        browserRoutingCoordinator = routing
    }

    fun assistantToolCatalog(): AssistantToolCatalog = AssistantToolCatalog(
        registry = commandRegistry,
        isAvailable = commandCapabilities::isAvailable,
        executeCommand = this::executeCommand,
        extraHandlers = listOfNotNull(
            PresentTextSemanticToolHandler(),
            webSearchHandler,
            InspectUiSemanticToolHandler(),
            RequestScreenshotSemanticToolHandler(),
            ConfigureMapPoisSemanticToolHandler(
                applyConfiguration = ::configureMapPois,
                isAvailable = ::isReady
            ),
            StartNavigationSemanticToolHandler(
                startNavigation = ::startNavigation,
                isAvailable = ::isReady
            ),
            StopNavigationSemanticToolHandler(
                stopNavigation = ::stopNavigation,
                isAvailable = { isReady() && mapWidget?.hasActiveNavigation() == true }
            ),
            SetNewsSourceSemanticToolHandler(::setNewsSource),
            UpdateFinanceTileSemanticToolHandler(
                updateTile = ::updateFinanceTile,
                isAvailable = { financeWidget != null }
            ),
            browserRoutingCoordinator?.let(::RunBrowserTaskSemanticToolHandler)
        )
    )

    /** Assistant navigation */
    fun startNavigation(request: StartNavigationRequest) = mapAssistantActions.startNavigation(request)
    fun stopNavigation() = mapAssistantActions.stopNavigation()
    fun configureMapPois(request: ConfigureMapPoisRequest) = mapAssistantActions.configurePois(request)

    /**
     * Present assistant-authored text on the UI thread. The callback is asynchronous because
     * semantic handlers run inside the provider worker and widget mutations are main-thread-only.
     */
    fun presentAssistantText(request: AssistantTextPresentationRequest) =  textAssistantActions.present(request)

    private fun createAssistantTextWidget(): TextBoxWidget? {
        if (!isReady()) return null
        val boxWidth = (width * 0.72f).coerceAtMost(width.toFloat())
        val boxHeight = (height * 0.55f).coerceAtMost(height.toFloat())
        val x = ((width - boxWidth) / 2f).coerceAtLeast(0f)
        val y = ((height - boxHeight) / 2f).coerceAtLeast(0f)
        val widget = buildTextBox(x, y, boxWidth, boxHeight)
        return lifecycleController.open(textLifecycle) { widget }
    }

    private fun hideAssistantIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
        imm.hideSoftInputFromWindow(token, InputMethodManager.HIDE_NOT_ALWAYS)
    }

    /** Capture only runtime layout metadata for the next assistant request. */
    fun assistantUiContext(): AssistantUiContext = assistantUiContextCapture.capture()

    fun setAssistantStatus(status: AssistantStatus) {
        assistantVoiceHoverControl.configured = status.configured
        assistantVoiceHoverControl.state = status.state
        notifyTransientContentChanged()
    }

    fun configureAssistantSettings(
        onPasteKey: (String) -> Unit,
        onRemoveKey: () -> Unit,
        onSelectModel: (String?) -> Unit
    ) {
        settingsController.geminiListener = object : GeminiSettingsListener {
            override fun onPasteGeminiKey() {
                val clipboard = phoneClipboard
                if (!clipboard.isNullOrBlank()) onPasteKey(clipboard) else host?.requestClipboard()
            }
            override fun onRemoveGeminiKey() = onRemoveKey()
            override fun onSelectGeminiModel(model: String?) = onSelectModel(model)
        }
    }

    fun setAssistantConfiguration(configuration: AssistantConfiguration?, models: List<AssistantModel>, detail: String) {
        settingsController.setAssistantConfiguration(configuration, models, detail)
    }

    private fun loadCommandLayout(name: String): Boolean {
        if (!isReady()) return false
        val builtIn = BuiltInLayouts.buildDashboard(name, width.toFloat(), height.toFloat())
        if (builtIn != null) {
            requestLayoutSwitchBrightness()
            applyPersistedDashboardReplacingCurrent(builtIn, notifyRestored = false)
            layoutManager.activeLayoutName = name
            notifyPersistentContentChanged()
            return true
        }
        val saved = layoutLibrary.load(name) ?: return false
        requestLayoutSwitchBrightness()
        applyPersistedDashboardReplacingCurrent(saved, notifyRestored = false)
        layoutManager.activeLayoutName = name
        notifyPersistentContentChanged()
        return true
    }

    fun executeOpenUrlCommand(address: String): AppCommandResult = executeCommand(
        AppCommandInvocation(commandRegistry.openUrl.id, AppCommandArguments.Url(address))
    )

    fun applyPersistedDashboardReplacingCurrent(
        dashboard: WidgetDocument.PersistedDashboard,
        notifyRestored: Boolean = true,
    ) {
        if (!isReady()) return

        val previousDashboard = captureWidgetDashboardWithoutLayoutName().copy(
            activeLayoutName = layoutManager.activeLayoutName,
        )
        withContentChangeSuppressed {
            try {
                dismissAllTransientOverlays()
                clearWidgetsForLayoutReplace()
                applyPersistedContents(dashboard)
            } catch (failure: Throwable) {
                runCatching {
                    clearWidgetsForLayoutReplace()
                    applyPersistedContents(previousDashboard)
                }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        // The container now reflects a stored dashboard, so saving is safe again.
        isAwaitingRestore = false
        if (notifyRestored) notifyDashboardRestored()
    }

    private fun applyPersistedContents(dashboard: WidgetDocument.PersistedDashboard) {
        shouldCreateDefaultSingletons = false
        lifecycleController.replaceClosedTemplates(dashboard.closedTemplates)

        dashboard.widgets.forEach { record ->
            runCatching { lifecycleController.restore(record) }
                .onFailure { Log.w(TAG, "Skipping invalid persisted ${record.type.persistedId} widget", it) }
        }
        settingsController.applyHoverControlPlacements(dashboard.hoverControls)
        setWidgetLayoutLocked(dashboard.isLayoutLocked, persist = false)

        lifecycleController.arrangeMinimizedWidgets()
        layoutManager.activeLayoutName = dashboard.activeLayoutName
    }

    private fun applyDefaultLayoutReplacingCurrent() {
        val defaultDashboard = BuiltInLayouts.buildDashboard(
            DashboardPersistence.DEFAULT_LAYOUT_NAME,
            width.toFloat(),
            height.toFloat()
        ) ?: return
        settingsController.resetHoverControlPlacementsToDefault(notify = false)
        applyPersistedDashboardReplacingCurrent(defaultDashboard)
    }

    private fun dismissAllTransientOverlays() {
        contextMenu?.dismiss()
        textEditing.dismissMenus()
        closeConfirmationPopup?.dismiss()
        layoutDeleteConfirmationPopup?.dismiss()
        settingsController.dismissAll()
        layoutNamePrompt?.dismiss()
        cancelLongPress()
        cancelHoverControlInteractions()
        setFocusedWidget(null)
    }

    private fun clearWidgetsForLayoutReplace() {
        registry.allWidgets().forEach(::notifyPresentationTargetRemoved)
        exitAllFullscreen()
        lifecycleController.closeAll()
        activeWidget = null
    }

    private fun destroyWidget(widget: BaseWidget) {
        if (widget is ScreenMirrorWidget) {
            setBinocularMediaSourceActive(widget.binocularSourceId, false)
        }
        if (widget is FinanceWidget) {
            financeFeatures.onDestroyed()
        }
        widget.onDestroy()
    }

    private fun restoreTextWidget(state: DashboardPersistence.TextWidgetState): TextBoxWidget? =
        widgetCaptureRestore.restoreTextWidgets(listOf(state), width, height).firstOrNull()

    private fun restoreBrowserWidget(state: DashboardPersistence.BrowserWidgetState): BrowserWidget? {
        val rootView = parent as? ViewGroup ?: return null
        val widget = browserWidgetFactory(rootView).create(state.x, state.y, state.width, state.height)
        widgetCaptureRestore.restoreBrowserWidgetState(widget, state, width, height)
        return widget
    }

    private fun restoreImageWidget(state: DashboardPersistence.ImageWidgetState): ImageWidget? =
        widgetCaptureRestore.restoreImageWidgets(listOf(state), width, height).firstOrNull()

    private fun restoreVideoWidget(state: DashboardPersistence.VideoWidgetState): VideoWidget? {
        val rootView = parent as? ViewGroup ?: return null
        val uri = WidgetCaptureRestore.readableUri(context, state.uri) ?: return null
        val widget = videoWidgetFactory(rootView).create(uri, state.displayName, state.x, state.y)
        widgetCaptureRestore.restoreCommonFields(widget, state, width, height)
        return widget
    }

    private fun restorePdfWidget(state: DashboardPersistence.PdfWidgetState): PdfWidget? {
        val uri = WidgetCaptureRestore.readableUri(context, state.uri) ?: return null
        val widget = PdfWidget(
            context = context,
            contentUri = uri,
            displayName = state.displayName,
            x = state.x,
            y = state.y,
            width = state.width,
            height = state.height,
            initialPage = state.page,
            initialZoom = state.zoom
        )
        widgetCaptureRestore.restoreCommonFields(widget, state, width, height)
        return widget
    }

    private fun openClosedTextTemplate(): TextBoxWidget? = runPersistentMutation(changed = { it != null }) {
        (lifecycleController.openClosedTemplate(WidgetTypeId.TEXT) as? TextBoxWidget)?.also { widget ->
            if (!widget.isMinimized) {
                setFocusedWidget(widget)
            }
        }
    }

    private fun openClosedBrowserTemplate(): BrowserWidget? = runPersistentMutation(changed = { it != null }) {
        (lifecycleController.openClosedTemplate(WidgetTypeId.BROWSER) as? BrowserWidget)?.also { widget ->
            if (!widget.isMinimized) {
                widget.setFocused(true)
                setFocusedWidget(widget)
            }
        }
    }

    /**
     * Call this BEFORE restoring widget state to prevent default singleton widgets from being created.
     * This should be called when the app is not a fresh install (returning user).
     *
     * It also blocks saving until the restore actually lands. Restoring is driven by the
     * first layout pass, which does not happen if the display never comes up, and an
     * unguarded save would then persist the empty container over the user's dashboard.
     */
    fun setIsReturningUser() {
        shouldCreateDefaultSingletons = false
        isAwaitingRestore = true
    }

    /**
     * Restore or create status bar widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    private fun applyStatusBarState(state: DashboardPersistence.StatusBarState): StatusBarWidget {
        val widget = StatusBarWidget(state.x, state.y, 220f, 110f)
        widgetCaptureRestore.restoreStatusBarPosition(widget, state, width, height)
        return widget
    }

    fun shouldApplyBrowserMinimumBrightness(): Boolean = registry.allWidgets().any { it.requestsBrowserMinimumBrightness }
    fun shouldApplyMediaBrightnessCap(): Boolean = registry.allWidgets().any { it.requestsMediaBrightnessCap }

    /**
     * Restore or create location widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    private fun applyMapState(state: DashboardPersistence.MapWidgetState): MapWidget {
        val widget = MapWidget(context, state.x, state.y, state.width, state.height).apply {
            invalidate = ::requestRepaint
            configurePhoneRouteTransport(phoneRouteTransport)
            onPhoneRouteConnectionChanged(phoneRouteConnected)
            bindMapNavigation(this)
            pendingMapLocation?.let(::setLocation)
            setLocationMetadata(pendingMapLocationLabel, pendingMapCountryCode)
        }
        widgetCaptureRestore.restoreMapWidgetPosition(widget, state, width, height)
        return widget
    }

    private fun applyLocationState(state: DashboardPersistence.LocationWidgetState): LocationWidget {val widget = LocationWidget(context, state.x, state.y, state.width, state.height)
        locationWeatherBinding.configure(widget)
        widgetCaptureRestore.restoreLocationWidgetPosition(widget, state, width, height)
        return widget
    }

    /**
     * Restore or create calendar widget with the given state.
     */
    private fun applyCalendarState(state: DashboardPersistence.CalendarWidgetState): CalendarWidget {
        val widget = CalendarWidget(state.x, state.y, state.width, state.height).apply {
            setFontScale(state.fontScale)
            setSnapshot(pendingCalendarSnapshot)
        }
        widgetCaptureRestore.restoreCommonFields(widget, state, width, height)
        return widget
    }

    /**
     * Restore or create finance widget with the given state.
     */
    private fun applyFinanceState(
        state: DashboardPersistence.FinanceWidgetState
    ): FinanceWidget {
        val widget = FinanceWidget(state.x, state.y, state.width, state.height)
        financeFeatures.configure(widget)
        widgetCaptureRestore.restoreFinanceWidgetPosition(widget, state, width, height)
        financeFeatures.onOpened(widget, reason = "widget_restored")
        return widget
    }

    /**
     * Restore or create news widget with the given state.
     */
    private fun applyNewsState(state: DashboardPersistence.NewsWidgetState): NewsWidget {
        val widget = NewsWidget(state.x, state.y, state.width, state.height)
        newsFeatures.configure(widget)
        widgetCaptureRestore.restoreNewsWidgetPosition(widget, state, width, height)
        newsFeatures.onOpened(widget, reason = "widget_restored")
        return widget
    }

    private fun applyNotificationsState(state: DashboardPersistence.NotificationsWidgetState): NotificationsWidget {
        val widget = NotificationsWidget(state.x, state.y, state.width, state.height, height.toFloat())
        widgetCaptureRestore.restoreNotificationsWidgetPosition(widget, state, width, height)
        widget.setScreenHeight(height.toFloat())
        widget.setNotificationsSnapshot(latestNotificationsSnapshot)
        return widget
    }

    /**
     * Restore or create speedometer widget with the given state.
     */
    private fun applySpeedometerState(state: DashboardPersistence.SpeedometerWidgetState): SpeedometerWidget {
        val widget = createSpeedometerWidget(state.x, state.y, state.width, state.height)
        widgetCaptureRestore.restoreCommonFields(widget, state, width, height)
        return widget
    }

    /**
     * Restore or create subtitle widget with capture off.
     */
    private fun applySubtitleState(state: DashboardPersistence.SubtitleWidgetState): SubtitleWidget {
        val widget = createSubtitleWidget(state.x, state.y, state.width, state.height, autoStart = false)
        widget.restoreOptions(
            phonePlaybackEnabled = state.phonePlaybackEnabled,
            microphoneEnabled = state.microphoneEnabled,
            translationEnabled = state.translationEnabled
        )
        widget.setCaptureEnabled(false, notifyPhone = false)
        widgetCaptureRestore.restoreSubtitleWidgetPosition(widget, state, width, height)
        return widget
    }

    /**
     * Restore or create mirror widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    private fun applyMirrorState(state: DashboardPersistence.MirrorWidgetState): ScreenMirrorWidget {
        val widget = ScreenMirrorWidget(context, state.x, state.y, state.width, state.height)
        widgetCaptureRestore.restoreCommonFields(widget, state, width, height)
        return widget
    }

    fun isReady(): Boolean = width > 0 && height > 0

    // ==================== Screen Mirroring ====================
    fun setMirroringEnabled(enabled: Boolean, phoneIp: String? = null) {
        withTransientChangeBatch {
            screenMirrorWidget?.setMirroringEnabled(enabled, phoneIp)
            screenMirrorWidget?.refreshDisplayVisibility()
            refreshBrightnessRules()
            notifyTransientContentChanged()
        }
    }

    fun onTrimMemory(level: Int)                      {browserWidgets.forEach { it.onTrimMemory(level) }}
    fun onLowMemory()                                 {browserWidgets.forEach { it.onLowMemory() }}
    fun clearDebugWebViewData(profileSummary: String) {browserWidgets.forEach { it.clearDebugWebViewData(profileSummary) }}
    fun release()                                     {presentationController.dismiss() ; lifecycleController.closeAll()}
}
