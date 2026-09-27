package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
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
import com.everyday.everyday_glasses.settings.DisplaySettingsHandler
import com.everyday.everyday_glasses.runtime.GeminiSettingsListener
import com.everyday.everyday_glasses.auth_permissions.GoogleAccountHandler
import com.everyday.everyday_glasses.connectivity.WidgetDataProvider
import com.everyday.everyday_glasses.runtime.WidgetChangeListener
import com.everyday.everyday_glasses._binocular.BinocularContentClass
import com.everyday.everyday_glasses.interaction.commands.AppCommandCapabilities
import com.everyday.everyday_glasses.interaction.commands.AppCommandExecutor
import com.everyday.everyday_glasses.interaction.commands.AppCommandResult
import com.everyday.everyday_glasses.widgets.map.backend.GlassesRouteTransport
import com.everyday.everyday_glasses.widgets._common.HandlerPresentationScheduler
import com.everyday.everyday_glasses.widgets._common.PresentationSession
import com.everyday.everyday_glasses.widgets._common.WidgetPresenter
import com.everyday.everyday_glasses.settings.SettingsController
import com.everyday.everyday_glasses.interaction.user_input.UserInputActivity
import com.everyday.everyday_glasses.interaction.user_input.UserInputKind
import com.everyday.everyday_glasses.auth_permissions.GoogleAuthState
import com.everyday.everyday_glasses.widgets.map.backend.StartNavigationRequest
import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.browser.BrowserModule
import com.everyday.everyday_glasses.ui.components.CloseConfirmationHandler
import com.everyday.everyday_glasses.ui.components.ContextMenuHandler
import com.everyday.everyday_glasses.interaction.user_input.CursorStabilizer
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
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.LayoutLibrary
import com.everyday.everyday_glasses.ui.hover_controls.HoverControlsLayer
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.browser.BrowserWidget
import com.everyday.everyday_glasses.widgets.calendar.CalendarModule
import com.everyday.everyday_glasses.widgets.calendar.CalendarWidget
import com.everyday.everyday_glasses.ui.components.CloseConfirmationPopup
import com.everyday.everyday_glasses.ui.components.ContextMenu
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserModule
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserWidget
import com.everyday.everyday_glasses.widgets.finance.FinanceWidget
import com.everyday.everyday_glasses.widgets.file_browser.ImageModule
import com.everyday.everyday_glasses.widgets.weather_location.LocationWidget
import com.everyday.everyday_glasses.widgets.map.MapModule
import com.everyday.everyday_glasses.widgets.map.MapWidget
import com.everyday.everyday_glasses.widgets.news.NewsWidget
import com.everyday.everyday_glasses.widgets.notifications.NotificationsWidget
import com.everyday.everyday_glasses.widgets.notifications.NotificationsModule
import com.everyday.everyday_glasses.widgets.file_browser.PdfModule
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.MirrorModule
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.ScreenMirrorWidget
import com.everyday.everyday_glasses.widgets.speedometer.SpeedometerWidget
import com.everyday.everyday_glasses.widgets.speedometer.SpeedState
import com.everyday.everyday_glasses.widgets.speedometer.SpeedometerModule
import com.everyday.everyday_glasses.widgets.system_status.StatusBarWidget
import com.everyday.everyday_glasses.widgets.system_status.StatusBarDisplayState
import com.everyday.everyday_glasses.widgets.system_status.StatusBarModule
import com.everyday.everyday_glasses.widgets.speech_to_text.SubtitleWidget
import com.everyday.everyday_glasses.widgets.speech_to_text.SubtitleModule
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxModule
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import com.everyday.everyday_glasses._threedof.ThreeDofOverlay
import com.everyday.everyday_glasses.widgets.file_browser.VideoModule
import com.everyday.everyday_glasses.widgets.file_browser.VideoWidget
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
        private const val DEFAULT_SPEED_VISIBILITY_THRESHOLD_KMH = 1f
        private const val LONG_PRESS_DELAY_MS = 250L
        private const val LONG_PRESS_MAX_MOVEMENT = 20f
        
        // Phone trackpad sensitivity multiplier
        private const val PHONE_TRACKPAD_SENSITIVITY = 1.5f
    }

    // ==================== Delegated Components ====================

    private val cursorStabilizer = CursorStabilizer()
    private val stabilizedCursorDelta = PointF()
    private val registry = WidgetRegistry()
    private val widgetCaptureRestore = WidgetCaptureRestore()
    /** Every text field and text area of the app is edited through this controller. */
    private val textEditing = TextEditingController(
        toContentSpace = ::screenToContentCoordinates,
        requestPhoneClipboard = { host?.requestClipboard() },
        notifyFocusChanged = { notifyFocusChanged() },
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
    private val locationWeatherBinding by lazy {
        LocationWeatherBinding(
            services = dashboardServices,
            context = context,
            currentWidget = { locationWidget },
            temperatureUnit = { unitSettings.effectiveTemperatureUnit() },
            toggleTemperatureUnit = { unitSettings.toggleTemperatureOverride() },
        )
    }
    private val newsFeatures by lazy {
        NewsFeatureController(
            services = dashboardServices,
            ui = assistantUiDispatcher,
            currentWidget = { newsWidget },
            currentProvider = { dataProvider },
        )
    }
    private val financeFeatures by lazy {
        FinanceFeatureController(
            services = dashboardServices,
            ui = assistantUiDispatcher,
            currentWidget = { financeWidget },
            currentProvider = { dataProvider },
            runPersistentUpdate = { action -> runPersistentMutation( action = action, changed = { result -> result.isSuccess } ) },
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
            CommandAvailability.TEXT_FOCUSED -> textEditing.hasKeyFocus
            CommandAvailability.BROWSER_AVAILABLE -> textEditing.focusedPage != null || fullscreenAs<BrowserWidget>() != null || isReady()
        }

        override fun toggleWidget(widgetId: CommandWidgetId) =
            lifecycleController.toggle(WidgetDescriptors.forCommand(widgetId).type)

        override fun setWidgetVisible(widgetId: CommandWidgetId, visible: Boolean) {
            val type = WidgetDescriptors.forCommand(widgetId).type
            if (visible) lifecycleController.show(type) else lifecycleController.hide(type)
        }

        override fun createTextWidget() {textModule.open(pendingCreateX, pendingCreateY)}
        override fun createBrowserWidget() = browserModule.openFromMenu(pendingCreateX, pendingCreateY)
        override fun openYouTubeHistory() = browserModule.openYouTubeHistory()
        override fun loadLayout(name: String): Boolean = layoutManager.loadLayout(name)
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

    private val hoverControlsLayer = HoverControlsLayer(
        context = context,
        commandRegistry = commandRegistry,
        closeApp = { executeCommand(commandRegistry.invocation(commandRegistry.closeApp)) },
        toggleAssistantVoice = { host?.toggleAssistantVoice() },
        runShortcut = { invocation ->
            userInputActivity?.onUserInput(UserInputKind.SHORTCUT)
            if (invocation.commandId == commandRegistry.createText.id) {
                pendingCreateX = (width * 0.5f) - (width * TextBoxModule.DEFAULT_WIDTH_PERCENT) / 2f
                pendingCreateY = (height * 0.5f) - (height * TextBoxModule.DEFAULT_HEIGHT_PERCENT) / 2f
            } else if (invocation.commandId == commandRegistry.createBrowser.id) {
                pendingCreateX = 0f
                pendingCreateY = 0f
            }
            executeCommand(invocation)
            host?.requestCursorCenter()
        },
        requestCursorCenter = { host?.requestCursorCenter() },
        repaint = { invalidateHoverVisuals() },
        notifyTransientChange = { notifyTransientContentChanged() },
        isModalOpen = { isAnyModalOverHoverControls() },
    )

    // ==================== Assistant Actions ====================
    private val assistantUiContextCapture by lazy {AssistantUiContextCapture(registry = registry, screenSize = {width to height}, activeLayoutName = {layoutManager.activeLayoutName}, focusedWidget = ::keyFocusWidget)}

    private val textAssistantActions by lazy {
        TextAssistantActions(
            ui = assistantUiDispatcher,
            findTextWidget = { id -> findWidgetByStableId(id) as? TextBoxWidget },
            createTextWidget = textModule::openForAssistant,
            clearEditing = { widget ->
                widget.hideKeyboard()
                textEditing.clearAllFocus()
                hideAssistantIme()
            },
            presentWidget = { widget, spec, created -> presenter.present(widget, spec, created) },
            removePresentationTarget = presenter::remove,
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
            present = { widget, spec -> presenter.present(widget, spec) },
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

    //delegation of widgets to registry
    val financeWidget: FinanceWidget?             get() = registry.financeWidget
    val newsWidget: NewsWidget?                   get() = registry.newsWidget
    val notificationsWidget: NotificationsWidget? get() = registry.notificationsWidget
    val speedometerWidget: SpeedometerWidget?     get() = registry.speedometerWidget
    val subtitleWidget: SubtitleWidget?           get() = registry.subtitleWidget
    val screenMirrorWidget: ScreenMirrorWidget?   get() = registry.screenMirrorWidget

    // ==================== Widget modules ====================

    /** What every widget module gets from the dashboard. */
    private val dashboardServices = object : DashboardServices {
        override val viewportWidth: Int get() = width
        override val viewportHeight: Int get() = height
        override val isDisplayOff: Boolean get() = displayState == WakeSleepManager.DisplayState.OFF
        override val captureRestore: WidgetCaptureRestore get() = widgetCaptureRestore
        override fun notifyTransientChange() = notifyTransientContentChanged()
        override fun <T> withTransientChanges(action: () -> T): T = withTransientChangeBatch(action)
        override fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean): Boolean      = this@WidgetContainer.runClassifiedInput(action, changed)
        override fun <T> runPersistentMutation(changed: (T) -> Boolean, action: () -> T): T = this@WidgetContainer.runPersistentMutation(changed, action)
        override fun <W : BaseWidget> open(type: WidgetTypeId, create: () -> W?): W? = lifecycleController.open(type, create)
        override fun openClosedTemplate(type: WidgetTypeId): BaseWidget? = lifecycleController.openClosedTemplate(type)
        override fun close(widget: BaseWidget) = lifecycleController.close(widget)
    }
    private val statusBarModule = StatusBarModule(dashboardServices) { statusBarWidget }
    private val calendarModule = CalendarModule(dashboardServices) { calendarWidget }
    private val notificationsModule = NotificationsModule(dashboardServices) { notificationsWidget }
    private val speedometerModule = SpeedometerModule(
        dashboardServices,
        current = { speedometerWidget },
        speedUnit = { unitSettings.effectiveSpeedUnit() },
        toggleSpeedUnit = { unitSettings.toggleSpeedOverride() },
        showSettings = { settingsController.showSpeedometerSettingsMenu() },
        dismissSettings = { settingsController.dismissSpeedometerSettingsMenu() },
        forceVisibleWhenIdle = { settingsController.getSpeedVisibilityThreshold() <= 0f },
    )
    private val subtitleModule = SubtitleModule(dashboardServices, { subtitleWidget }) { control ->
        host?.requestSubtitleControl(control)
    }
    private val mapModule = MapModule(
        dashboardServices,
        context,
        current = { mapWidget },
        currentProvider = { dataProvider },
        requestRepaint = ::requestRepaint,
        addressFocusChanged = ::onMapAddressFocusChanged,
        dismissPresentation = ::dismissMapPresentation,
    )
    private val mirrorModule = MirrorModule(
        dashboardServices,
        context,
        current = { screenMirrorWidget },
        onMediaFrame = ::notifyMediaFrame,
        setMediaSourceActive = ::setBinocularMediaSourceActive,
        refreshBrightnessRules = ::refreshBrightnessRules,
        sendMirrorRequest = { start -> host?.requestMirror(start) },
    )
    private val textModule = TextBoxModule(dashboardServices)
    private val imageModule = ImageModule(dashboardServices, context) { registry.imageWidgets }
    private val videoModule = VideoModule(
        dashboardServices,
        context,
        rootLayout = { parent as? ViewGroup },
        containerView = this,
        setMediaSourceActive = ::setBinocularMediaSourceActive,
    )
    private val pdfModule = PdfModule(dashboardServices, context)
    private val fileBrowserModule = FileBrowserModule(dashboardServices, context) { host?.requestCursorCenter() }
    private val browserModule = BrowserModule(
        dashboardServices,
        context,
        rootLayout = { parent as? ViewGroup },
        containerView = this,
        all = { browserWidgets },
        requestKeyboard = { show -> host?.requestBrowserKeyboard(show) },
    )


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
        shortcutsProvider = { hoverControlsLayer.shortcuts() },
        onShortcutsChanged = { updated -> hoverControlsLayer.applyShortcuts(updated) }
    )
    private val layoutLibrary = LayoutLibrary(context)
    private val layoutManager = WidgetLayoutManager(
        layouts = layoutLibrary,
        services = dashboardServices,
        lifecycle = lifecycleController,
        host = object : WidgetLayoutManager.Host {
            override fun captureDashboard()                         = captureWidgetDashboardWithoutLayoutName()
            override fun notifyPersistentChange()                   = notifyPersistentContentChanged()
            override fun notifyRestored()                           = notifyDashboardRestored()
            override fun reportKeyFocus()                           { contentObserver?.onFocusChanged(hasFocusedWidget()) }
            override fun dismissMenus()                             = dismissMenu()
            override fun dismissTransientOverlays()                 = dismissAllTransientOverlays()
            override fun <T> suppressingChanges(action: () -> T): T = withContentChangeSuppressed(action)
            override fun clearWidgets() {
                registry.allWidgets().forEach(presenter::onWidgetRemoving)
                exitAllFullscreen()
                lifecycleController.closeAll()
                activeWidget = null
            }
            override fun applyHoverControls(placements: List<DashboardPersistence.HoverControlPlacementState>?) = settingsController.applyHoverControlPlacements(placements)
            override fun resetHoverControls()                       = settingsController.resetHoverControlPlacementsToDefault(notify = false)
            override fun restoreLayoutLock(locked: Boolean)         = setWidgetLayoutLocked(locked, persist = false)
            override fun onLayoutSwitch()                           { displaySettingsHandler?.applyLayoutSwitchBrightness() }
        },
    )
    var displaySettingsHandler: DisplaySettingsHandler? = null
    var googleAccountHandler: GoogleAccountHandler? = null

    // Access to widget lists via registry
    private val widgets: List<TextBoxWidget> get() = registry.textWidgets
    private val browserWidgets: List<BrowserWidget> get() = registry.browserWidgets
    private val videoWidgets: List<VideoWidget> get() = registry.videoWidgets

    // Widget being dragged
    private var activeWidget: BaseWidget? = null
    private var isWidgetLayoutLocked = false

    // The single widget that currently owns hover — and therefore interaction. At most one
    // widget is hovered at a time; a widget can only *become* hovered when no other widget is
    // already hovered under the cursor. See resolveHoveredWidget for the selection rules.
    private var hoveredWidget: BaseWidget? = null

    // Drag state. activeWidget is being moved or resized; internalDragWidget drags part of itself
    // (a finance tile); pressCaptureWidget follows a press it captured (a web page, a POI slider).
    private var internalDragWidget: BaseWidget? = null
    private var pressCaptureWidget: BaseWidget? = null
    private var cursorPressed = false

    // ==================== Cursor State ====================
    
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastCursorX = 0f
    private var lastCursorY = 0f

    // ==================== Long-press Detection ====================
    
    private val handler = Handler(Looper.getMainLooper())
    private val presenter = WidgetPresenter(
        scheduler = HandlerPresentationScheduler(handler),
        registry = registry,
        viewportSize = { width.toFloat() to height.toFloat() },
        closeWidget = { lifecycleController.close(it) },
        bringToFront = ::bringWidgetToFront,
        restoreZOrder = { widget, zOrder -> registry.restoreZOrder(widget, zOrder, parent as? ViewGroup, this) },
        arrangeMinimizedWidgets = { lifecycleController.arrangeMinimizedWidgets() },
        clearKeyFocus = { textEditing.clearAllFocus() },
        withNotificationsSuppressed = { action -> withContentChangeSuppressed(action) },
        notifyTransientChange = ::notifyTransientContentChanged,
    )
    private var longPressRunnable: Runnable? = null
    private var longPressStartX = 0f
    private var longPressStartY = 0f
    private var pendingDragWidget: BaseWidget? = null
    private var pendingInternalDragWidget: BaseWidget? = null
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
    var userInputActivity: UserInputActivity? = null

    private var isCursorVisible = false
    private var displayState: WakeSleepManager.DisplayState = WakeSleepManager.DisplayState.WAKE

    // Flag to suppress aggressive invalidation during display state transitions
    private var isTransitioningDisplayState = false
    private var isHostPaused = false

    /**
     * True while a stored dashboard is known to exist but has not been applied yet.
     * Anything that persists the container must refuse to write while this holds.
     */
    val isAwaitingDashboardRestore: Boolean get() = layoutManager.isAwaitingRestore
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
        unitSettings.onChanged = { applyEffectiveUnitSettings() } // Callers own the classified notification for unit changes; applying the derived widget presentation here must not emit a second transient callback.
        applyEffectiveUnitSettings()
    }

    override fun onDetachedFromWindow() {presenter.dismiss(); super.onDetachedFromWindow()}

    private fun registerWidgetLifecycles() {
        //multi-instance widget registration
        lifecycleController.register(browserModule.lifecycle)
        lifecycleController.register(textModule.lifecycle)
        lifecycleController.register(imageModule.lifecycle)
        lifecycleController.register(videoModule.lifecycle)
        lifecycleController.register(pdfModule.lifecycle)
        lifecycleController.register(fileBrowserModule.lifecycle)

        //singleton widget registration
        lifecycleController.register(statusBarModule.lifecycle)
        lifecycleController.register(locationWeatherBinding.lifecycle)
        lifecycleController.register(mapModule.lifecycle)
        lifecycleController.register(calendarModule.lifecycle)
        lifecycleController.register(financeFeatures.lifecycle)
        lifecycleController.register(newsFeatures.lifecycle)
        lifecycleController.register(notificationsModule.lifecycle)
        lifecycleController.register(speedometerModule.lifecycle)
        lifecycleController.register(subtitleModule.lifecycle)
        lifecycleController.register(mirrorModule.lifecycle)
        lifecycleController.verifyComplete()
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

    fun onHeadUpWake() {newsFeatures.onHeadUpWake(); Log.d(TAG, "Head-up wake -> advance news item")}
    fun hasPinnedWidgets(): Boolean = registry.hasPinnedWidgets()
    fun isDisplayAwake(): Boolean = displayState == WakeSleepManager.DisplayState.WAKE

    // Widget presentation by assistant
    val activePresentationSession: PresentationSession? get() = presenter.activeSession
    fun cancelPresentationForUserInput(): Boolean = presenter.cancelForUserInput()
    fun dismissPresentation(): Boolean = presenter.dismiss()
    fun widgetStableId(widget: BaseWidget): String = stableWidgetId(widget) /** Stable ID used by assistant context and temporary presentation targeting. */

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
            (textEditing.focusedPage as? BrowserWidget)?.takeIf { !it.isMinimized }
                ?: browserWidgets.firstOrNull { !it.isMinimized }
        } ?: return null
        val stableId = stableWidgetId(widget)
        return BrowserWidgetPageAgentAdapter.bind(stableId, widget)
    }
    private fun stableWidgetId(widget: BaseWidget): String          = registry.stableId(widget)
    private fun findWidgetByStableId(targetId: String): BaseWidget? = registry.findByStableId(targetId)


    private fun onWidgetRemoving(widget: BaseWidget)                {presenter.onWidgetRemoving(widget) ; if (activeWidget === widget) activeWidget = null} /** Shared cleanup for every closed widget, run while it still has its stable ID. */
    private fun requestRepaint()                                    = contentChanges.requestRepaint() /** Requests one local redraw for pixel-only visual state. */
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
    private fun invalidateHoverVisuals() {requestRepaint()}

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

    // ==================== Initialization ====================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Update 3DOF overlay dimensions
        threeDofOverlay.setScreenDimensions(w.toFloat(), h.toFloat())

        // Smart alignment helper needs current screen size for snap candidates
        smartAlignment.screenWidth = w.toFloat()
        smartAlignment.screenHeight = h.toFloat()

        hoverControlsLayer.onSizeChanged(w.toFloat(), h.toFloat())
        contextMenu = ContextMenu(w.toFloat(), h.toFloat(), commandRegistry) { type -> registry.widgets(type).isNotEmpty() }.apply {
            setLayoutLockState(isWidgetLayoutLocked)
            handler = object : ContextMenuHandler {
                override fun selectItem(item: ContextMenu.MenuItem) = handleMenuItemSelected(item)
                override fun requestDeleteLayout(name: String) = layoutManager.requestDeleteLayout(name)
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
        layoutManager.createOverlays(w.toFloat(), h.toFloat(), textEditing)
        settingsController.createSpeedometerSettingsMenu(w.toFloat(), h.toFloat())
        settingsController.createHoverControlsLayoutEditor(w.toFloat(), h.toFloat(), hoverControlsLayer.controls) // Initialize the hover-controls layout editor. Constructing it snaps the controls to default grid cells, so this must run after hoverControlsLayer.onSizeChanged.
        layoutManager.createDefaultLayoutIfFirstRun()         // Only create the built-in Standard layout on first run. For returning users, the restoration code will create widgets based on saved state.
        updateFullscreenBounds()
        Log.d(TAG, "Container sized: ${w}x${h}")
    }
    private fun triggerCloseAppConfirmation() {closeConfirmationPopup?.show(); notifyTransientContentChanged()}

    // distribute location updates to widgets that are location dependent
    fun applyLocationMetadata(town: String, countryCode: String) {
        mapModule.applyLocationMetadata(town, countryCode)
        financeFeatures.updateCountryCode(countryCode)
        newsFeatures.updateCountryCode(countryCode)
        locationWeatherBinding.applyLocationMetadata(town, countryCode)
    }

    //update features of the Weather and location widget
    fun setLocationPermissionDenied()           = locationWeatherBinding.setLocationPermissionDenied()
    fun applyWeatherData(weather: WeatherData?) = locationWeatherBinding.applyWeatherData(weather)

    //update features of the Finance widget
    fun applyFinanceSnapshot(snapshot: FinanceSnapshot, notify: Boolean = true)                          = financeFeatures.applySnapshot(snapshot, notify)
    fun applyFinanceError(message: String)                                                               = financeFeatures.applyError(message)
    fun currentFinanceVisibleConfigs(): List<FinanceDashboardTileConfig>                                 = financeFeatures.currentVisibleConfigs()
    fun updateFinanceTile(request: FinanceTileUpdateRequest): CompletableFuture<FinanceTileUpdateResult> = financeFeatures.updateTile(request)

    //update features of the News widget
    fun applyNewsSnapshot(snapshot: NewsSnapshot)                                            = newsFeatures.applySnapshot(snapshot)
    fun applyNewsError(message: String)                                                      = newsFeatures.applyError(message)
    fun currentNewsCountryCode(): String?                                                    = newsFeatures.currentCountryCode()
    fun currentNewsSourceSpec(): NewsSourceSpec?                                             = newsFeatures.currentSourceSpec()
    fun setNewsSource(sourceSpec: NewsSourceSpec): CompletableFuture<NewsSourceUpdateResult> = newsFeatures.setSource(sourceSpec)

    //update features on speedometer and subtitle widgets
    fun updateSpeedData(state: SpeedState)                      = speedometerModule.updateSpeed(state)
    fun setSpeedPermissionDenied()                              = speedometerModule.onSpeedUnavailable()
    fun applySubtitleStatus(status: SubtitleStatus)             = subtitleModule.applyStatus(status)
    fun applySubtitleTranscript(transcript: SubtitleTranscript) = subtitleModule.applyTranscript(transcript)
    fun onSubtitlePhoneDisconnected()                           = subtitleModule.onPhoneDisconnected()

    fun setSpeedVisibilityThreshold(thresholdKmh: Float) {
        val forceVisibleChanged = settingsController.setSpeedVisibilityThreshold(thresholdKmh)
        if (forceVisibleChanged && displayState != WakeSleepManager.DisplayState.OFF) {
            notifyTransientContentChanged()
        }
    }

    private fun exitAllFullscreen() {
        val fullscreen = registry.fullscreenWidget ?: return
        registry.clearFullscreen(fullscreen)
        if (fullscreen.isFullscreen) fullscreen.toggleFullscreen()
    }

    private fun updateFullscreenBounds() {
        if (width <= 0 || height <= 0) return
        registry.forEachWidget { it.onViewportSizeChanged(width.toFloat(), height.toFloat()) }
        registry.fullscreenWidget?.setFullscreenBounds(width.toFloat(), height.toFloat())
    }

    fun updateStatusBar(state: StatusBarDisplayState)                             = statusBarModule.update(state)
    fun setCalendarSnapshot(snapshot: com.everyday.shared.sync.CalendarSnapshot?) = calendarModule.setSnapshot(snapshot)
    fun observeLiveLocationCountry(countryCode: String): Boolean = UnitSettings.normalizeCountryCode(countryCode)?.let {
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

    fun applyNotificationsSnapshot(snapshot: PhoneNotificationsSnapshot) = notificationsModule.applySnapshot(snapshot)

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
        pendingDragWidget = null; pendingInternalDragWidget = null; pendingDragIsResize = false; pendingTextSelection = false
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

        if (layoutManager.isNamePromptVisible) {
            if (textEditing.focusedZoneContains(x, y)) { pendingTextSelection = true; scheduleLongPress() }
            notifyTransientContentChanged()
            return
        }
        if (closeConfirmationPopup?.isVisible != true && settingsController.handleHoverControlsLayoutEditorDown(x, y)) return // The hover-controls layout editor is fully modal; consume input before any widget hit-testing.
        if (settingsController.handleShortcutsSettingsMenuDown(x, y)) return // Shortcuts settings menu is also modal.
        val (cx, cy) = toContentSpace(x, y) // Transform to content space for hit testing against widgets

        // Hover controls sit above every widget — including fullscreen ones — so let any
        // currently-interacting (e.g. lemon mid-magnify) control or any hover-control
        // whose bounds contain the cursor consume the press before widget hit-testing.
        if (isVisibleKeyboardAt(cx, cy)) {hoverControlsLayer.cancelInteractions(); updateKeyboardHoverAt(cx, cy); notifyTransientContentChanged(); return}
        if (hoverControlsLayer.onCursorDown(cx, cy)) return

        val scrollbarWidget = getScrollbarHoveredWidget(cx, cy)
        if (scrollbarWidget != null) { isScrollbarDragActive = true; this.scrollbarDragWidget = scrollbarWidget }
        else { isScrollbarDragActive = false; this.scrollbarDragWidget = null }

        // Menus are NOT transformed (they stay head-locked like cursor)
        textEditing.formattingMenu?.let { menu -> if (menu.isVisible && menu.shouldScheduleLongPress(x, y)) { pendingFormattingMenuDrag = true; scheduleLongPress(); return } }

        if (settingsController.handleSpeedometerDown(x, y)) return

        // A widget panel that owns its drag gestures while open (the map's POI settings, even over
        // a fullscreen map) takes the press before normal widget and focus handling.
        widgetsTopDown().firstOrNull { it.onModalPress(cx, cy) }?.let { pressCaptureWidget = it; return }
        
        // A long press on the focused text zone starts a text selection.
        if (textEditing.focusedZoneContains(x, y)) {
            keyFocusWidget()?.let(::bringWidgetToFrontForInteraction)
            pendingTextSelection = true
            scheduleLongPress()
            return
        }

        // A fullscreen widget is the only possible target; only its own content can take the press.
        registry.fullscreenWidget?.let { fullscreen ->
            if (fullscreen.containsPoint(cx, cy)) pressWidgetContent(fullscreen, cx, cy)
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
     * Begin the appropriate down-gesture for [widget], the resolved interaction target, so
     * overlapping widgets can't compete for the press. Each widget's [BaseWidget.dragHitTest]
     * says whether the press can start a move or a resize.
     */
    private fun dispatchCursorDownToWidget(widget: BaseWidget, cx: Float, cy: Float) {
        // Z-order is part of the persisted dashboard, but a long-press drag must not save
        // its initial frame.  The drag completion below emits the single persistent signal.
        bringWidgetToFrontForInteraction(widget)

        // A press on a web page is forwarded to the page instead.
        if (pressWidgetContent(widget, cx, cy)) return
        when (widget.dragHitTest(cx, cy)) {
            BaseWidget.DragHit.DRAG -> beginPendingWidgetDrag(widget, isResize = false)
            BaseWidget.DragHit.RESIZE -> beginPendingWidgetDrag(widget, isResize = true)
            BaseWidget.DragHit.NONE -> {}
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
            pendingInternalDragWidget?.let { widget ->
                val (cx, cy) = screenToContentCoordinates(longPressStartX, longPressStartY)
                if (widget.startInternalDrag(cx, cy)) internalDragWidget = widget
                pendingInternalDragWidget = null
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
        pendingDragWidget != null || pendingInternalDragWidget != null

    private fun clearPendingWidgetDrag() {
        pendingDragWidget = null
        pendingInternalDragWidget = null
        pendingDragIsResize = false
        longPressRunnable = null
    }

    /**
     * A press on [widget]'s own content: an internal long-press drag (a finance tile), or a press
     * the widget captures (a web page). Returns false when it can start moving the widget instead.
     */
    private fun pressWidgetContent(widget: BaseWidget, cx: Float, cy: Float): Boolean {
        if (widget.canStartInternalDrag(cx, cy)) {
            pendingInternalDragWidget = widget
            scheduleLongPress()
            return true
        }
        if (!widget.onPress(cx, cy)) return false
        pressCaptureWidget = widget
        return true
    }

    /** Ends the captured press at the screen point ([x], [y]), as a release or a cancel. */
    private fun endCapturedPress(x: Float, y: Float, cancel: Boolean) {
        val widget = pressCaptureWidget ?: return
        pressCaptureWidget = null
        val (cx, cy) = toContentSpace(x, y)
        if (cancel) widget.onPressCancel(cx, cy) else widget.onPressRelease(cx, cy)
    }

    fun onCursorUp(x: Float, y: Float) {
        settingsController.handleSpeedometerUp()
        settingsController.handleDashboardUp() // If DashboardSettings is open, end its interaction (e.g., stop slider drag).
        settingsController.handleHoverControlsLayoutEditorUp() // If the hover-controls layout editor is open, finalize any drag.
        settingsController.handleShortcutsSettingsMenuUp() // Shortcuts settings menu.

        // Notify any hover control that captured the press of the release, unless
        // the visible keyboard owns the release point.
        val (cx, cy) = toContentSpace(cursorX, cursorY)
        if (isVisibleKeyboardAt(cx, cy)) {hoverControlsLayer.cancelInteractions()} else {hoverControlsLayer.onCursorUp(cursorX, cursorY)}
        endCapturedPress(x, y, cancel = false)

        cursorPressed = false; cancelLongPress()
        if (isScrollbarDragActive) { isScrollbarDragActive = false; scrollbarDragWidget = null }
        textEditing.formattingMenu?.let { if (it.isDragging()) it.onDragEnd() }
        if (isLongPressSelectionMode) { textEditing.endSelection(); isLongPressSelectionMode = false }
        var dragEnded = false
        internalDragWidget?.let { widget ->
            dragEnded = true
            internalDragWidget = null
            runPersistentMutation(action = { widget.endInternalDrag() }, changed = { it })
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
            endCapturedPress(cursorX, cursorY, cancel = true)
        }
    }

    fun setCursorPosition(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, width.toFloat()); cursorY = y.coerceIn(0f, height.toFloat())
        lastCursorX = cursorX; lastCursorY = cursorY
        updateHover(cursorX, cursorY, isCursorVisible)
    }

    fun onMove(dx: Float, dy: Float, source: InputSource = InputSource.GLASSES_TEMPLE): Pair<Float, Float> {
        if (hoverControlsLayer.freezesCursor) {
            val moveDelta = if (source == InputSource.PHONE_TRACKPAD) {
                stabilizedCursorDelta.apply {
                    set(dx * PHONE_TRACKPAD_SENSITIVITY, dy * PHONE_TRACKPAD_SENSITIVITY)
                }
            } else {
                cursorStabilizer.processInto(dx, dy, stabilizedCursorDelta)
            }
            if (hoverControlsLayer.onCursorMove(cursorX, cursorY, moveDelta.x, moveDelta.y)) {
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

        if (layoutManager.isNamePromptVisible) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        // If SpeedometerSettings is open, treat it as modal and route movement to it.
        if (settingsController.handleSpeedometerMove(cursorX, cursorY)) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        // If DashboardSettings is open, treat it as modal and route movement to it.
        // This allows slider dragging and prevents dragging widgets behind it.
        if (settingsController.handleDashboardMove(cursorX, cursorY)) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        // Same for the hover-controls layout editor.
        if (closeConfirmationPopup?.isVisible != true && settingsController.handleHoverControlsLayoutEditorMove(cursorX, cursorY)) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        if (settingsController.handleShortcutsSettingsMenuMove(cursorX, cursorY)) {
            lastCursorX = cursorX; lastCursorY = cursorY
            return Pair(cursorX, cursorY)
        }

        val moveDx = cursorX - lastCursorX; val moveDy = cursorY - lastCursorY
        lastCursorX = cursorX; lastCursorY = cursorY
        if (hoverControlsLayer.onCursorMove(cursorX, cursorY, moveDx, moveDy)) {return Pair(cursorX, cursorY)} // Forward moves to a hover control that captured the cursor on down.

        // A widget that captured the press follows it (a web page, a POI slider).
        pressCaptureWidget?.let { widget ->
            val (cx, cy) = toContentSpace(cursorX, cursorY)
            if (widget.onPressMove(cx, cy)) return Pair(cursorX, cursorY)
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
            hoverControlsLayer.clearHover()
            registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            if (!isTransitioningDisplayState) invalidateHoverVisuals()
            return
        }

        if (!isVisible) {
            hoveredWidget = null
            hoverControlsLayer.clearHover()
            closeConfirmationPopup?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); textEditing.updateMenuHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); contextMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            layoutManager.updateOverlayHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            cursorStabilizer.reset()
            if (!isTransitioningDisplayState) { invalidateHoverVisuals() } // Hover clear is transient visual state only.
            return
        }
        
        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)

        if (updateKeyboardHoverAt(cx, cy)) {
            hoverControlsLayer.clearHover()
            clearWidgetHoverStatesExcept(visibleKeyboardHostAt(cx, cy))
            if (!isTransitioningDisplayState) invalidateHoverVisuals()
            return
        }
        
        // Hover controls live above every widget, including fullscreen ones, so they
        // always receive hover updates whenever the cursor is visible.
        hoverControlsLayer.updateHover(cx, cy)

        // Menus stay head-locked, use screen coords
        closeConfirmationPopup?.updateHover(x, y); textEditing.updateMenuHover(x, y); contextMenu?.updateHover(x, y)
        layoutManager.updateOverlayHover(x, y)
        if (hoverControlsLayer.ownsHover) {clearWidgetHoverStates(); if (!isTransitioningDisplayState) {invalidateHoverVisuals()}; return}
        
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
        if (isLongPressSelectionMode || internalDragWidget != null || activeWidget != null || isScrollbarDragActive) {userInputActivity?.onUserInput(UserInputKind.DRAG)}
        if (isLongPressSelectionMode && textEditing.isSelecting) {textEditing.extendSelection(x, y)} // For text selection, use content-space coordinates
        // Menus use screen-space (head-locked)
        textEditing.formattingMenu?.let { menu ->
            if (menu.isDragging()) {
                menu.onDrag(x, y)
                notifyTransientContentChanged()
                return
            }
            menu.checkLongPressCancellation(x,y, LONG_PRESS_MAX_MOVEMENT)
        }
        if (longPressRunnable != null) { val totalMovement = kotlin.math.sqrt((x - longPressStartX) * (x - longPressStartX) + (y - longPressStartY) * (y - longPressStartY)); if (totalMovement > LONG_PRESS_MAX_MOVEMENT) cancelLongPress() } // Long-press cancellation uses screen-space (cursor movement)
        // Widget dragging uses delta values (works in both spaces)
        internalDragWidget?.let { widget ->
            val (cx, cy) = toContentSpace(x, y)
            widget.onInternalDrag(cx, cy)
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

        // A screen modal first; otherwise the fullscreen widget, or the top-most widget under the cursor.
        val fullscreen = registry.fullscreenWidget
        val target = screenModalWidget()
            ?: if (fullscreen != null) fullscreen.takeIf { it.containsPoint(cx, cy) }
            else interactionTargetAt(cx, cy)?.takeUnless { it.isMinimized }
        target?.let { runWidgetInput(it) { it.onScrollAt(cx, cy, dy) } }
    }

    // ==================== Tap Handling ====================

    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        return fullscreenAs<VideoWidget>()?.handleKeyEvent(keyCode, event) == true
    }

    fun onTap(x: Float, y: Float): Boolean {
        if (pageAgentApprovalOverlay.isVisible()) {return pageAgentApprovalOverlay.onTap(x, y)} // Menus use screen-space coordinates (head-locked)
        textEditing.onMenuTap(x, y)?.let { return it } // The text edit and formatting menus sit above whichever surface owns the focused text zone.
        layoutManager.onOverlayTap(x, y)?.let { return it }
        if (closeConfirmationPopup?.isVisible == true) return closeConfirmationPopup?.onTap(x, y) ?: false
        settingsController.handleHoverControlsLayoutEditorTap(x, y)?.let { return it }
        settingsController.handleShortcutsSettingsMenuTap(x, y)?.let { return it }
        if (contextMenu?.isVisible == true) { return dispatchContextMenuAction { contextMenu?.onTap(x, y) ?: false }}
        settingsController.handleSpeedometerTap(x, y)?.let { return it }

        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)
        if (consumeVisibleKeyboardTap(cx, cy)) return true

        // Hover controls are above every widget, including fullscreen ones.
        if (hoverControlsLayer.onTap(cx, cy)) return true

        // Screen modals and open popups take taps anywhere, even over a fullscreen widget.
        for (widget in widgetsTopDown()) {if (runWidgetInput(widget) { widget.onModalTap(cx, cy) }) return true}

        // A fullscreen widget is the only possible target; outside its bounds the tap does nothing.
        registry.fullscreenWidget?.let { fullscreen -> return fullscreen.containsPoint(cx, cy) && tapWidget(fullscreen, cx, cy)}

        // Modal dashboard check
        settingsController.handleDashboardTap(x, y)?.let { return it }

        // Route the tap to the single top-most widget under the cursor — the same widget
        // that owns hover — so an overlapping widget can't consume a tap meant for it.
        interactionTargetAt(cx, cy)?.let { target ->
            if (target.isMinimized) { target.toggleMinimize(); return true }
            return tapWidget(target, cx, cy)
        }

        if (hasFocusedWidget()) { unfocusAll(); notifyTransientContentChanged(); return true }
        return false
    }

    /** A tap on [widget]: the fullscreen widget, or the top-most one under the cursor. */
    private fun tapWidget(widget: BaseWidget, cx: Float, cy: Float): Boolean {
        val handled = runWidgetInput(widget) {
            if (widget.bringsToFrontOnTap) bringWidgetToFront(widget)
            widget.onTap(cx, cy)
        }
        if (widget.requestsDrawingAboveNativeViews) ensureDrawnAboveNativeOverlays()
        return handled
    }

    /**
     * Runs one input on [widget] and classifies it once: saved when it removed the widget or
     * changed its [BaseWidget.persistentSignature], transient otherwise.
     */
    private fun runWidgetInput(widget: BaseWidget, action: () -> Boolean): Boolean {
        val before = widget.persistentSignature()
        return runClassifiedInput(action) {
            widget !in registry.allWidgets() || widget.persistentSignature() != before
        }
    }

    /** The widget showing a screen-level modal, if any. */
    private fun screenModalWidget(): BaseWidget? = registry.allWidgets().firstOrNull { it.hasScreenModal }

    /** Widgets in the order they get a first look at an input: a screen modal, then top-most first. */
    private fun widgetsTopDown(): List<BaseWidget> =
        registry.getAllSortedByZOrder().asReversed().sortedByDescending { it.hasScreenModal }

    fun onDoubleTap(x: Float, y: Float): Boolean {
        if (closeConfirmationPopup?.isVisible != true) {settingsController.handleHoverControlsLayoutEditorDoubleTap(x, y)?.let { return it }}
        if (contextMenu?.isVisible == true) {return dispatchContextMenuAction { contextMenu?.onDoubleTap(x, y) ?: false }}
        for (widget in registry.allWidgets()) {if (widget.onDoubleTapAnywhere()) return true} // A widget can take any double-tap first, e.g. a browser leaving a web video's fullscreen.
        val (cx, cy) = toContentSpace(x, y) // Transform to content space for widget hit testing
        if (isVisibleKeyboardAt(cx, cy)) return true
        if (hoverControlsLayer.onDoubleTap(cx, cy)) return true
        if (textEditing.onDoubleTap(x, y)) return true
        screenModalWidget()?.let { modal -> if (runWidgetInput(modal) { modal.onDoubleTap(cx, cy) }) return true} // A screen modal (the map's address search) takes double-taps anywhere.

        // A fullscreen widget is the only possible target, and never opens the app context menu.
        registry.fullscreenWidget?.let { fullscreen ->
            return fullscreen.containsPoint(cx, cy) && runWidgetInput(fullscreen) { fullscreen.onDoubleTap(cx, cy) }
        }

        // The top-most widget under the cursor gets the double-tap, unless it lands on its border.
        val target = interactionTargetAt(cx, cy)
        val onBorder = target != null && !target.isMinimized && target.isBorderHit(cx, cy)
        if (target != null && !onBorder && runWidgetInput(target) { target.onDoubleTap(cx, cy) }) return true

        // Any other double-tap, on a border, inside a widget or on empty space, opens the app
        // context menu, whether it came from the temple or from the phone's pointer.
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

    fun onDragEnd(notify: Boolean = true) {
        cancelLongPress()
        internalDragWidget?.let { widget ->
            internalDragWidget = null
            if (notify) runPersistentMutation(action = { widget.endInternalDrag() }, changed = { it })
            else widget.endInternalDrag()
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

    fun setSmartAlignmentEnabled(enabled: Boolean) {
        smartAlignment.enabled = enabled
    }
    fun setWidgetLayoutLocked(locked: Boolean, persist: Boolean = true) {
        if (isWidgetLayoutLocked == locked) {contextMenu?.setLayoutLockState(isWidgetLayoutLocked); return}
        isWidgetLayoutLocked = locked
        contextMenu?.setLayoutLockState(isWidgetLayoutLocked)
        if (locked) {cancelLongPress(); onDragEnd(notify = false)}
        if (persist) notifyPersistentContentChanged()
    }

    //map section
    fun updateMapLocation(location: android.location.Location)           = mapModule.updateLocation(location)
    fun requestPhoneMapRefresh()                                         = mapModule.requestPhoneMapRefresh()
    fun applyMapSnapshot(snapshot: com.everyday.shared.sync.MapSnapshot) = mapModule.applySnapshot(snapshot)



    /** The map's address search took or gave up the keyboard focus. */
    private fun onMapAddressFocusChanged(map: MapWidget, focused: Boolean) {
        val beforeZOrder = map.zOrder
        if (focused) bringWidgetToFront(map)
        notifyFocusChanged(persistent = focused && map.zOrder != beforeZOrder)
    }

    fun configurePhoneRouteTransport(transport: GlassesRouteTransport?)                               = mapModule.configureRouteTransport(transport)
    fun onPhoneRouteConnectionChanged(connected: Boolean)                                             = mapModule.onRouteConnectionChanged(connected)
    fun onPhoneRouteResult(result: com.everyday.shared.transport.RouteTransportResult)                = mapModule.onRouteResult(result)
    fun onPhoneRouteError(error: com.everyday.shared.transport.RouteTransportError)                   = mapModule.onRouteError(error)
    fun onPhoneDestinationSearchResult(result: com.everyday.shared.transport.DestinationSearchResult) = mapModule.onDestinationSearchResult(result)
    fun onPhoneDestinationSearchError(error: com.everyday.shared.transport.DestinationSearchError)    = mapModule.onDestinationSearchError(error)

    private fun dismissMapPresentation() {
        val map = mapWidget ?: return
        if (activePresentationSession?.targetId == widgetStableId(map)) {
            dismissPresentation()
        }
    }

    //browser section
    /** A windowed browser for a normal assistant browser task, bound to the Page Agent. */
    fun createPageAgentBrowser(): PageAgentBrowserAdapter? {
        if (!isReady() || parent !is ViewGroup) return null
        return runPersistentMutation(changed = { true }) {
            browserModule.openForTask(pendingCreateX, pendingCreateY)?.let { opened ->
                BrowserWidgetPageAgentAdapter.bind(stableWidgetId(opened), opened)
                    ?: run {
                        lifecycleController.close(opened)
                        null
                    }
            }
        }
    }

    private fun openBrowserAddress(address: String) {
        val widget = textEditing.focusedPage as? BrowserWidget
            ?: fullscreenAs<BrowserWidget>()
            ?: browserModule.openFullscreen(focus = false)
            ?: return

        runPersistentMutation {
            if (widget.isMinimized) widget.toggleMinimize()
            bringWidgetToFront(widget)
            widget.setFocused(true)
            widget.navigateToAddress(address, autoFullscreenVideo = true)
        }
    }

    //file widgets
    fun createImageWidget(path: String)                              = imageModule.open(path, pendingCreateX, pendingCreateY)
    fun createVideoWidget(uri: android.net.Uri, displayName: String) = videoModule.open(uri, displayName)
    fun createPdfWidget(uri: android.net.Uri, displayName: String)   = pdfModule.open(uri, displayName)
    fun createFileBrowserWidget(filter: FileBrowserWidget.FileFilter, onFileSelected: (FileBrowserWidget.FileEntry) -> Unit) = fileBrowserModule.open(filter, onFileSelected)

    //keyboard focus handling
    private fun keyFocusWidget(): BaseWidget? = registry.allWidgets().firstOrNull { it.hasKeyFocus } /** The widget that typed keys go to: a text box or a browser page. */
    fun unfocusAll() {registry.forEachWidget { it.releaseKeyFocus() }; textEditing.clearAllFocus()} /** Takes the keyboard from everything, closing the map's address search if it has it. */
    fun onKeyPress(key: String, userInitiated: Boolean = false) {if (userInitiated) userInputActivity?.onUserInput(UserInputKind.KEYBOARD); textEditing.typeKey(key)} /** Keys go to the focused text zone first, otherwise to the focused browser's web page. */

    private fun ensureDrawnAboveNativeOverlays() {
        val rootView = parent as? ViewGroup ?: return
        if (rootView.indexOfChild(this) != rootView.childCount - 1) {
            rootView.bringChildToFront(this)
        }
        if (elevation < 10f) elevation = 10f
    }

    // ==================== Public Accessors ====================
    fun setPhoneClipboard(content: String?)                                                                             = textEditing.setPhoneClipboard(content)
    fun hasFocusedWidget():                                                                                     Boolean = textEditing.hasKeyFocus
    fun isMenuVisible():                                                                                        Boolean = contextMenu?.isVisible == true || textEditing.isMenuVisible || layoutManager.isNamePromptVisible
    fun isClosePopupVisible():                                                                                  Boolean = closeConfirmationPopup?.isVisible == true || layoutManager.isDeleteConfirmationVisible
    fun isAnyPopupVisible():                                                                                    Boolean = isMenuVisible() || isClosePopupVisible() || pageAgentApprovalOverlay.isVisible()
    fun showPageAgentApproval(runId: String, request: PageAgentConfirmationRequest, respond: (Boolean) -> Unit) {pageAgentApprovalOverlay.show(runId, request, respond); ensureDrawnAboveNativeOverlays()}
    fun dismissPageAgentApproval(runId: String)                                                                 {pageAgentApprovalOverlay.dismiss(runId)}
    fun dismissMenu()                                                                                           { contextMenu?.dismiss(); textEditing.dismissMenus(); layoutManager.dismissNamePrompt() }
    fun isDragging():                                                                                           Boolean = activeWidget != null || internalDragWidget != null || textEditing.formattingMenu?.isDragging() == true

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
    fun screenToContentCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> = currentDashboardTransform().screenToContent(screenX, screenY) /** Convert the fixed screen cursor to the coordinate space in which widgets are drawn. */

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
                if (!settingsController.isHoverControlsLayoutEditorVisible()) {hoverControlsLayer.draw(canvas)}
                drawVisibleKeyboardOverlays(canvas)
                presenter.draw(canvas)
                drawOverlayMenus(canvas)
                return
            }

            // No pinned fullscreen widget - draw all pinned widgets normally
            for (widget in registry.getPinnedWidgetsSortedByZOrder()) widget.draw(canvas)
            if (!settingsController.isHoverControlsLayoutEditorVisible()) {hoverControlsLayer.draw(canvas)}
            drawVisibleKeyboardOverlays(canvas)
            presenter.draw(canvas)
            drawOverlayMenus(canvas)
            return
        }

        // Handle fullscreen widgets (not affected by 3DOF transform)
        if (hasFullscreenWidget()) {
            registry.fullscreenWidget?.draw(canvas)
            // Hover controls stay above fullscreen widgets so the user can always reach
            // them (Close, YouTube history, the lemon shortcuts, …).
            if (!settingsController.isHoverControlsLayoutEditorVisible()) {hoverControlsLayer.draw(canvas)}
            drawVisibleKeyboardOverlays(canvas)
            presenter.draw(canvas)
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
            hoverControlsLayer.draw(canvas)               // Hover controls are dashboard overlays and should visually win over any widget they overlap while hovered, except for visible keyboard keys.
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
        presenter.draw(canvas)
        drawOverlayMenus(canvas)         // Draw menus (not affected by 3DOF - stay head-locked like cursor)

        // Note: Brightness dimming is handled at window level via WindowManager.LayoutParams.screenBrightness
        // This ensures the entire app (including WebViews) is dimmed uniformly.
    }

    private fun drawOverlayMenus(canvas: Canvas) {
        if (isAnyPopupVisible() || registry.allWidgets().any { it.requestsDrawingAboveNativeViews }) ensureDrawnAboveNativeOverlays()
        registry.getAllSortedByZOrder().forEach { it.drawScreenOverlay(canvas) }
        contextMenu?.draw(canvas)
        settingsController.drawHoverControlsLayoutEditor(canvas)  // Draw the hover-controls editor before the close-app confirmation so the popup appears on top of it when the user invokes Close from the editor's context menu.
        closeConfirmationPopup?.draw(canvas)
        layoutManager.drawDeleteConfirmation(canvas)
        settingsController.draw(canvas)
        layoutManager.drawNamePrompt(canvas)
        textEditing.drawMenus(canvas)
        pageAgentApprovalOverlay.draw(canvas)
    }
    private fun drawVisibleKeyboardOverlays(canvas: Canvas) {
        registry.fullscreenWidget?.let { fullscreen -> if (!fullscreen.drawsOwnKeyboard) fullscreen.visibleKeyboard?.draw(canvas); return}
        registry.getAllSortedByZOrder().forEach { widget -> if (!widget.isMinimized && !widget.drawsOwnKeyboard) widget.visibleKeyboard?.draw(canvas)}
    }

    // ==================== Keyboard overlay input priority ====================

    /** The widget whose visible keyboard is under ([cx], [cy]): the fullscreen one, else the top-most. */
    private fun visibleKeyboardHostAt(cx: Float, cy: Float): BaseWidget? {
        registry.fullscreenWidget?.let { fullscreen -> return fullscreen.takeIf { it.visibleKeyboard?.containsPoint(cx, cy) == true }}
        return registry.getAllSortedByZOrder().asReversed().firstOrNull { widget -> !widget.isMinimized && widget.visibleKeyboard?.containsPoint(cx, cy) == true }
    }
    private fun isVisibleKeyboardAt(cx: Float, cy: Float): Boolean = visibleKeyboardHostAt(cx, cy) != null
    private fun updateKeyboardHoverAt(cx: Float, cy: Float): Boolean {
        val host = visibleKeyboardHostAt(cx, cy) ?: return false
        host.updateHover(cx, cy)
        return true
    }
    private fun consumeVisibleKeyboardTap(cx: Float, cy: Float): Boolean {
        val host = visibleKeyboardHostAt(cx, cy) ?: return false
        // A batch, not runWidgetInput: the text editing controller classifies typed text itself,
        // and its notification must survive. A browser key can also navigate, which is saved.
        val before = host.persistentSignature()
        withTransientChangeBatch {
            host.visibleKeyboard?.onTap(cx, cy)
            if (host.persistentSignature() != before) notifyPersistentContentChanged()
            else notifyTransientContentChanged()
        }
        return true
    }

    private fun clearWidgetHoverStatesExcept(except: BaseWidget?) {
        hoveredWidget = except
        registry.forEachWidget {
            if (it !== except) it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        }
    }

    // ==================== Hover-control input pipeline ====================

    private fun isAnyModalOverHoverControls(): Boolean {
        if (settingsController.isHoverControlsLayoutEditorVisible()) return true
        if (settingsController.isShortcutsSettingsMenuVisible())     return true
        if (settingsController.isDashboardSettingsVisible())         return true
        if (settingsController.isSpeedometerSettingsMenuVisible())   return true
        if (screenModalWidget() != null)                             return true
        if (closeConfirmationPopup?.isVisible == true)               return true
        if (layoutManager.isDeleteConfirmationVisible)               return true
        if (layoutManager.isNamePromptVisible)                       return true
        if (contextMenu?.isVisible == true)                          return true
        if (textEditing.isMenuVisible)                               return true
        return false
    }

    // ==================== Persistence ====================

    fun capturePersistedDashboard(): WidgetDocument.PersistedDashboard = layoutManager.capturePersistedDashboard()
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


    fun executeCommand(invocation: AppCommandInvocation): AppCommandResult = commandExecutor.execute(invocation)
    fun configureBrowserRouting(routing: BrowserRoutingCoordinator?) {browserRoutingCoordinator = routing}
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

    private fun hideAssistantIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
        imm.hideSoftInputFromWindow(token, InputMethodManager.HIDE_NOT_ALWAYS)
    }

    /** Capture only runtime layout metadata for the next assistant request. */
    fun assistantUiContext(): AssistantUiContext = assistantUiContextCapture.capture()
    fun setAssistantStatus(status: AssistantStatus) {hoverControlsLayer.setAssistantStatus(status); notifyTransientContentChanged()}
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

    fun executeOpenUrlCommand(address: String): AppCommandResult = executeCommand(
        AppCommandInvocation(commandRegistry.openUrl.id, AppCommandArguments.Url(address))
    )

    fun applyPersistedDashboardReplacingCurrent(dashboard: WidgetDocument.PersistedDashboard) = layoutManager.replaceDashboard(dashboard)

    private fun dismissAllTransientOverlays() {
        contextMenu?.dismiss()
        textEditing.dismissMenus()
        closeConfirmationPopup?.dismiss()
        layoutManager.dismissOverlays()
        settingsController.dismissAll()
        cancelLongPress()
        hoverControlsLayer.cancelInteractions()
        textEditing.clearAllFocus()
    }

    fun setMirroringEnabled(enabled: Boolean, phoneIp: String? = null) = mirrorModule.setMirroringEnabled(enabled, phoneIp) // ==================== Screen Mirroring ====================

    /**
     * Call this BEFORE restoring widget state to prevent default singleton widgets from being created.
     * This should be called when the app is not a fresh install (returning user).
     *
     * It also blocks saving until the restore actually lands. Restoring is driven by the
     * first layout pass, which does not happen if the display never comes up, and an
     * unguarded save would then persist the empty container over the user's dashboard.
     */
    fun setIsReturningUser()                          = layoutManager.expectSavedDashboard()
    fun shouldApplyBrowserMinimumBrightness()         : Boolean = registry.allWidgets().any { it.requestsBrowserMinimumBrightness }
    fun shouldApplyMediaBrightnessCap()               : Boolean = registry.allWidgets().any { it.requestsMediaBrightnessCap }
    fun isReady()                                     : Boolean = width > 0 && height > 0
    fun onTrimMemory(level: Int)                      = browserModule.onTrimMemory(level)
    fun onLowMemory()                                 = browserModule.onLowMemory()
    fun clearDebugWebViewData(profileSummary: String) = browserModule.clearDebugWebViewData(profileSummary)
    fun release()                                     {presenter.dismiss() ; lifecycleController.closeAll()}
}
