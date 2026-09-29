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
import com.everyday.everyday_glasses.widgets.weather_location.WeatherWidget
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
import com.everyday.everyday_glasses.interaction.assistant.AssistantUiContextCapture
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.MapAssistantActions
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.TextAssistantActions
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.widgets.finance.FinanceModule
import com.everyday.everyday_glasses.widgets.news.NewsModule
import com.everyday.everyday_glasses.widgets.weather_location.WeatherModule
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
 * The dashboard view. It dispatches input, drawing and app data to the widgets and to the layers
 * above them, and holds no logic specific to one widget type.
 *
 * - Input: taps, double-taps, presses, moves, drags and scrolls go to the first taker, in order:
 *   the menus and popups, the visible keyboard, [HoverControlsLayer], a screen modal, then the
 *   fullscreen or top-most widget through [BaseWidget]'s input hooks. The press, long-press and
 *   drag state lives here because it is that dispatch.
 * - Drawing: the widgets in z-order (moved as one layer in 3DOF), then the hover controls,
 *   the keyboards, the presentation and the head-locked menus.
 * - Data: the public `apply…` / `update…` / `set…` methods pass through to each widget type's
 *   module (`StatusBarModule`, `MapModule`, `BrowserModule`, …), which owns that type's creation,
 *   restore, listeners and latest data.
 * - Changes: each mutation is reported once, as transient or persistent, through
 *   [DashboardChangeCoordinator] to [WidgetContentObserver].
 *
 * The other owners: [WidgetRegistry] (the widgets and their order), [WidgetLifecycleController]
 * (opening, closing, closed templates), [TextEditingController] (keyboard focus),
 * [WidgetPresenter] (assistant presentations), [WidgetLayoutManager] (layouts and replacing the
 * dashboard) and [SettingsController] (the settings screens, also used by the activity).
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

    enum class InputSource {GLASSES_TEMPLE, PHONE_TRACKPAD}
    companion object {
        private const val TAG                                    = "WidgetContainer"
        private const val DEFAULT_SPEED_VISIBILITY_THRESHOLD_KMH = 1f
        private const val LONG_PRESS_DELAY_MS                    = 250L
        private const val LONG_PRESS_MAX_MOVEMENT                = 20f
        private const val PHONE_TRACKPAD_SENSITIVITY             = 1.5f // Phone trackpad sensitivity multiplier
    }


    /************************
     * Variables and values *
     ************************/
    // ==================== Inputs and outputs ====================

    var dataProvider: WidgetDataProvider?                   = null         // Inputs: where WidgetContainer requests data
    var host: WidgetHost?                                   = null         // Outputs: where WidgetContainer requests changes outside the dashboard
    private val phoneClipboard: String?               get() = textEditing.phoneClipboard // Phone clipboard is a special thing, it's the way to paste things from the phone

    // ==================== Text Editing ====================
    /** Every text field and text area of the app is edited through this controller. */
    private val textEditing           = TextEditingController(
        toContentSpace                = ::screenToContentCoordinates,
        requestPhoneClipboard         = { host?.requestClipboard() },
        notifyFocusChanged            = { notifyFocusChanged() },
        notifyTransientChange         = ::notifyTransientContentChanged,
        notificationGeneration        = ::contentNotificationGeneration,
        runClassifiedInput            = { action, changed -> runClassifiedInput(action, changed) },
    )

    // ==================== Runtime ====================

    var contentObserver: WidgetContentObserver?             = null
    var userInputActivity: UserInputActivity?               = null
    private var lifecycleMutationInProgress = false
    private val contentChanges = DashboardChangeCoordinator(
        repaint              = {if (Looper.myLooper() == Looper.getMainLooper()) invalidate() else postInvalidate()},
        transient            = {contentObserver?.onTransientContentChanged()},
        persistent           = {if (cursorPressed) interactionPersistentNotified = true; contentObserver?.onPersistentContentChanged()}, // This is gesture bookkeeping, so it remains in WidgetContainer.
        restored             = {contentObserver?.onDashboardRestored()},
        notificationsBlocked = { lifecycleMutationInProgress },
    )

    // ==================== Persistence ====================

    private val registry              = WidgetRegistry()
    private val widgetCaptureRestore  = WidgetCaptureRestore()
    private val lifecycleController   = WidgetLifecycleController(
        runOnUiThread                 = { action -> post(action) },
        onChanged                     = {refreshBrightnessRules(); notifyPersistentContentChanged()}, // Opening, closing, fullscreen and minimize can all change what the brightness rules need.
        registry                      = registry,
        onMutationStarted             = { lifecycleMutationInProgress = true },
        onMutationFinished            = { lifecycleMutationInProgress = false },
        textZoneHost                  = textEditing,
        changeListener                = object : WidgetChangeListener {
            override fun onTransientStateChanged()  = notifyTransientContentChanged()
            override fun onPersistentStateChanged() = notifyPersistentContentChanged()
        },
        onWidgetRemoving              = ::onWidgetRemoving,
        viewportSize                  = { width.toFloat() to height.toFloat() },
        displayState                  = { displayState },
    )

    // ==================== Commands ====================

    private val commandRegistry by lazy {AppCommandRegistry { layoutLibrary.names() }}
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
            CommandAvailability.ALWAYS            -> true
            CommandAvailability.APP_READY         -> isReady()
            CommandAvailability.TEXT_FOCUSED      -> textEditing.hasKeyFocus
            CommandAvailability.BROWSER_AVAILABLE -> textEditing.focusedPage != null || fullscreenAs<BrowserWidget>() != null || isReady()
        }
        override fun toggleWidget(widgetId: CommandWidgetId) = lifecycleController.toggle(WidgetDescriptors.forCommand(widgetId).type)
        override fun setWidgetVisible(widgetId: CommandWidgetId, visible: Boolean) {
            val type = WidgetDescriptors.forCommand(widgetId).type
            if (visible) lifecycleController.show(type) else lifecycleController.hide(type)
        }
        override fun createTextWidget()                                       {textModule.open(pendingCreateX, pendingCreateY)}
        override fun createBrowserWidget()                                    = browserModule.openFromMenu(pendingCreateX, pendingCreateY)
        override fun openUrl(url: String)                                     = openBrowserAddress(url)
        override fun openFilePicker()                                         { host?.requestFilePicker() }
        override fun openYouTubeHistory()                                     = browserModule.openYouTubeHistory()
        override fun loadLayout(name: String): Boolean                        = layoutManager.loadLayout(name)
        override fun toggleLayoutLock()                                       = setWidgetLayoutLocked(!isWidgetLayoutLocked)
        override fun saveLayout()                                             = layoutManager.saveCurrentLayout()
        override fun saveLayoutAs()                                           = layoutManager.showSaveLayoutAsPrompt()
        override fun openSettings()                                           = settingsController.toggleDashboardSettings(width.toFloat(), height.toFloat())
        override fun requestClose()                                           = triggerCloseAppConfirmation()
        override fun setBrightness(value: Float)                              {settingsController.setBrightness(value); notifyTransientContentChanged()}
        override fun scroll(direction: CommandScrollDirection, amount: Float) = scrollWidgetAtCursor(cursorX, cursorY, if (direction == CommandScrollDirection.UP) -amount else amount)
        override fun enterText(text: String)                                  = onKeyPress(text)
        override fun commandFinished()                                        = Unit // Each mutation owns its classified notification; a generic command completion hook would duplicate lifecycle, widget, and transient signals.
    }

    // ==================== Menus, popups and hover controls ====================

    private var contextMenu: ContextMenu?                       = null
    private var contextMenuDispatchDepth                        = 0
    private var closeConfirmationPopup: CloseConfirmationPopup? = null
    private val hoverControlsLayer = HoverControlsLayer(
        context               = context,
        commandRegistry       = commandRegistry,
        closeApp              = { executeCommand(commandRegistry.invocation(commandRegistry.closeApp)) },
        toggleAssistantVoice  = { host?.toggleAssistantVoice() },
        runShortcut           = { invocation ->
            userInputActivity?.onUserInput(UserInputKind.SHORTCUT)
            if (invocation.commandId == commandRegistry.createText.id) {
                pendingCreateX = (width * 0.5f) - (width * TextBoxModule.DEFAULT_WIDTH_PERCENT) / 2f
                pendingCreateY = (height * 0.5f) - (height * TextBoxModule.DEFAULT_HEIGHT_PERCENT) / 2f
            } else if (invocation.commandId == commandRegistry.createBrowser.id) {pendingCreateX = 0f; pendingCreateY = 0f}
            executeCommand(invocation)
            host?.requestCursorCenter()
        },
        requestCursorCenter   = { host?.requestCursorCenter() },
        repaint               = { invalidateHoverVisuals() },
        notifyTransientChange = { notifyTransientContentChanged() },
        isModalOpen           = { isAnyModalOverHoverControls() },
    )

    // ==================== Widget References ====================

    private val statusBarWidget: StatusBarWidget?         get() = registry.statusBarWidget
    private val weatherWidget: WeatherWidget?             get() = registry.weatherWidget
    private val mapWidget: MapWidget?                     get() = registry.mapWidget
    private val calendarWidget: CalendarWidget?           get() = registry.calendarWidget
    private val financeWidget: FinanceWidget?             get() = registry.financeWidget
    private val newsWidget: NewsWidget?                   get() = registry.newsWidget
    private val notificationsWidget: NotificationsWidget? get() = registry.notificationsWidget
    private val speedometerWidget: SpeedometerWidget?     get() = registry.speedometerWidget
    private val subtitleWidget: SubtitleWidget?           get() = registry.subtitleWidget
    private val screenMirrorWidget: ScreenMirrorWidget?   get() = registry.screenMirrorWidget

    // ==================== Widget modules ====================

    private var pendingCreateX      = 0f //Location of widget creation
    private var pendingCreateY      = 0f //Location of widget creation

    /** What every widget module gets from the dashboard. */
    private val dashboardServices   = object : DashboardServices {
        override val viewportWidth: Int                                                       get()  = width
        override val viewportHeight: Int                                                      get()  = height
        override val isDisplayOff: Boolean                                                    get()  = displayState == WakeSleepManager.DisplayState.OFF
        override val captureRestore: WidgetCaptureRestore                                     get()  = widgetCaptureRestore
        override fun notifyTransientChange()                                                         = notifyTransientContentChanged()
        override fun <T> withTransientChanges(action: () -> T): T                                    = withTransientChangeBatch(action)
        override fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean): Boolean      = this@WidgetContainer.runClassifiedInput(action, changed)
        override fun <T> runPersistentMutation(changed: (T) -> Boolean, action: () -> T): T = this@WidgetContainer.runPersistentMutation(changed, action)
        override fun <W : BaseWidget> open(type: WidgetTypeId, create: () -> W?): W?                 = lifecycleController.open(type, create)
        override fun openClosedTemplate(type: WidgetTypeId): BaseWidget?                             = lifecycleController.openClosedTemplate(type)
        override fun close(widget: BaseWidget) = lifecycleController.close(widget)
    }
    private val statusBarModule     = StatusBarModule(dashboardServices) { statusBarWidget }
    private val calendarModule      = CalendarModule(dashboardServices) { calendarWidget }
    private val notificationsModule = NotificationsModule(dashboardServices) { notificationsWidget }
    private val speedometerModule   = SpeedometerModule(
        dashboardServices,
        current              = { speedometerWidget },
        speedUnit            = { unitSettings.effectiveSpeedUnit() },
        toggleSpeedUnit      = { unitSettings.toggleSpeedOverride() },
        showSettings         = { settingsController.showSpeedometerSettingsMenu() },
        dismissSettings      = { settingsController.dismissSpeedometerSettingsMenu() },
        forceVisibleWhenIdle = { settingsController.getSpeedVisibilityThreshold() <= 0f },
    )
    private val subtitleModule      = SubtitleModule(dashboardServices, { subtitleWidget }) { control -> host?.requestSubtitleControl(control)}
    private val mapModule           = MapModule(
        dashboardServices,
        context,
        current             = { mapWidget },
        currentProvider     = { dataProvider },
        requestRepaint      = ::requestRepaint,
        addressFocusChanged = ::onMapAddressFocusChanged,
        dismissPresentation = ::dismissMapPresentation,
    )
    private val mirrorModule        = MirrorModule(
        dashboardServices,
        context,
        current                = { screenMirrorWidget },
        onMediaFrame           = ::notifyMediaFrame,
        setMediaSourceActive   = ::setBinocularMediaSourceActive,
        refreshBrightnessRules = ::refreshBrightnessRules,
        sendMirrorRequest      = { start -> host?.requestMirror(start) },
    )
    private val textModule          = TextBoxModule(dashboardServices)
    private val imageModule         = ImageModule(dashboardServices, context) { registry.imageWidgets }
    private val videoModule         = VideoModule(
        dashboardServices,
        context,
        rootLayout           = { parent as? ViewGroup },
        containerView        = this,
        setMediaSourceActive = ::setBinocularMediaSourceActive,
    )
    private val pdfModule           = PdfModule(dashboardServices, context)
    private val fileBrowserModule   = FileBrowserModule(dashboardServices, context) { host?.requestCursorCenter() }
    private val browserWidgets: List<BrowserWidget> get() = registry.browserWidgets // Access to browser widget list for browser module via registry
    private val browserModule       = BrowserModule(
        dashboardServices,
        context,
        rootLayout      = { parent as? ViewGroup },
        containerView   = this,
        all             = { browserWidgets },
        requestKeyboard = { show -> host?.requestBrowserKeyboard(show) },
    )
    private val weatherModule by lazy {
        WeatherModule(
            services              = dashboardServices,
            context               = context,
            currentWidget         = { weatherWidget },
            temperatureUnit       = { unitSettings.effectiveTemperatureUnit() },
            toggleTemperatureUnit = { unitSettings.toggleTemperatureOverride() },
        )
    }
    private val newsModule by lazy {
        NewsModule(
            services              = dashboardServices,
            ui                    = assistantUiDispatcher,
            currentWidget         = { newsWidget },
            currentProvider       = { dataProvider },
        )
    }
    private val financeModule by lazy {
        FinanceModule(
            services              = dashboardServices,
            ui                    = assistantUiDispatcher,
            currentWidget         = { financeWidget },
            currentProvider       = { dataProvider },
            runPersistentUpdate   = { action -> runPersistentMutation( action = action, changed = { result -> result.isSuccess } ) },
            stableWidgetId        = ::stableWidgetId,
        )
    }

    // ==================== Widgets drag (resize, move, scroll) and hover ====================

    private var activeWidget: BaseWidget?                 = null  // Widget being dragged
    private var hoveredWidget: BaseWidget?                = null // The single widget that currently owns hover — and therefore interaction. At most one widget is hovered at a time; a widget can only *become* hovered when no other widget is already hovered under the cursor. See resolveHoveredWidget for the selection rules.
    private var isWidgetLayoutLocked                      = false
    private var pendingDragWidget: BaseWidget?            = null
    private var pendingInternalDragWidget: BaseWidget?    = null
    private var pendingDragIsResize                       = false
    private var internalDragWidget: BaseWidget?           = null // Drag state. activeWidget is being moved or resized; internalDragWidget drags part of itself (a finance tile); pressCaptureWidget follows a press it captured (a web page, a POI slider).
    private var pressCaptureWidget: BaseWidget?           = null
    private var pendingFormattingMenuDrag                 = false
    private var scrollbarDragWidget: BaseWidget?          = null // Scrollbar dragging state
    private var isTwoFingerScrollMode                     = false // Two-finger scroll mode
    private val smartAlignment                            = SmartAlignmentHelper(registry) //smart alignment is during dragging
    private val smartAlignmentRenderer                    = SmartAlignmentRenderer()

    // ==================== Settings ====================

    val settingsController = SettingsController(
        resources                             = resources,
        defaultSpeedVisibilityThresholdKmh    = DEFAULT_SPEED_VISIBILITY_THRESHOLD_KMH,
        notifyTransientContentChanged         = ::notifyTransientContentChanged,
        notifyPersistentContentChanged        = ::notifyPersistentContentChanged,
        setSpeedometerForceVisibleWhenIdle    = { forceVisible -> speedometerWidget?.setForceVisibleWhenIdle(forceVisible) == true},
        isSmartAlignmentEnabled               = { smartAlignment.enabled },
        setSmartAlignmentEnabled              = { enabled   -> smartAlignment.enabled = enabled },
        notifyBrightnessChanged               = { value     -> displaySettingsHandler?.applyBrightness(value) },
        executeBrightnessCommand              = { value     -> executeCommand(AppCommandInvocation(commandRegistry.setBrightness.id, AppCommandArguments.Brightness(value)))},
        notifyAdaptiveBrightnessChanged       = { enabled   -> displaySettingsHandler?.applyAdaptiveBrightness(enabled)},
        notifyHeadUpTimeChanged               = { ms        -> displaySettingsHandler?.applyHeadUpTime(ms) },
        notifyWakeDurationChanged             = { ms        -> displaySettingsHandler?.applyWakeDuration(ms) },
        notifyAngleThresholdChanged           = { degrees   -> displaySettingsHandler?.applyHeadUpAngleThreshold(degrees)},
        notifyHeadsUpEnabledChanged           = { enabled   -> displaySettingsHandler?.applyHeadsUpEnabled(enabled)},
        notifySmartAlignmentChanged           = { enabled   -> displaySettingsHandler?.applySmartAlignment(enabled)},
        notifySpeedVisibilityThresholdChanged = { threshold -> displaySettingsHandler?.applySpeedVisibilityThreshold(threshold)},
        notifyUnitSystemModeChanged           = { mode      -> unitSettings.setGlobalMode(mode) },
        connectGoogle                         = { googleAccountHandler?.connectGoogleAccount() },
        grantCalendarAccess                   = { googleAccountHandler?.grantCalendarAccess() },
        disconnectGoogle                      = { googleAccountHandler?.disconnectGoogleAccount() },
        retryGoogleAuth                       = { googleAccountHandler?.retryGoogleAuthentication() },
        commandRegistry                       = commandRegistry,
        shortcutsProvider                     = { hoverControlsLayer.shortcuts() },
        onShortcutsChanged                    = { updated   -> hoverControlsLayer.applyShortcuts(updated) }
    )
    private val unitSettings                              = UnitSettings(context)
    var displaySettingsHandler: DisplaySettingsHandler?   = null
    var googleAccountHandler: GoogleAccountHandler?       = null

    // ==================== Layouts ====================

    val isAwaitingDashboardRestore: Boolean get() = layoutManager.isAwaitingRestore // reads layoutManager and guards saving.
    private val layoutLibrary                 = LayoutLibrary(context)
    private val layoutManager                 = WidgetLayoutManager(
        layouts   = layoutLibrary,
        services  = dashboardServices,
        lifecycle = lifecycleController,
        host      = object : WidgetLayoutManager.Host {
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

    // ==================== Cursor, press, long press state ====================
    private var isCursorVisible               = false
    private var cursorPressed                 = false
    private var interactionZOrderChanged      = false
    private var interactionPersistentNotified = false
    private var cursorX                       = 0f
    private var cursorY                       = 0f
    private var lastCursorX                   = 0f
    private var lastCursorY                   = 0f
    private val cursorStabilizer              = CursorStabilizer()
    private val stabilizedCursorDelta         = PointF()
    private val handler                       = Handler(Looper.getMainLooper()) // Android's timer for the main thread
    private var isLongPressSelectionMode      = false
    private var longPressRunnable: Runnable?  = null
    private var longPressStartX               = 0f
    private var longPressStartY               = 0f
    private var pendingTextSelection          = false //text selection works with long press

    // ==================== Assistant ====================

    private val presenter = WidgetPresenter(
        scheduler                   = HandlerPresentationScheduler(handler),
        registry                    = registry,
        viewportSize                = { width.toFloat() to height.toFloat() },
        closeWidget                 = { lifecycleController.close(it) },
        bringToFront                = ::bringWidgetToFront,
        restoreZOrder               = { widget, zOrder -> registry.restoreZOrder(widget, zOrder, parent as? ViewGroup, this) },
        arrangeMinimizedWidgets     = { lifecycleController.arrangeMinimizedWidgets() },
        clearKeyFocus               = { textEditing.clearAllFocus() },
        withNotificationsSuppressed = { action -> withContentChangeSuppressed(action) },
        notifyTransientChange       = ::notifyTransientContentChanged,
    )
    private val activePresentationSession: PresentationSession? get() = presenter.activeSession
    private var browserRoutingCoordinator: BrowserRoutingCoordinator? = null
    private val webSearchHandler                                      = WebSearchSemanticToolHandler(DuckDuckGoWebSearchProvider())
    private val assistantUiDispatcher                                 = UiThreadDispatcher { action -> if (Looper.myLooper() == Looper.getMainLooper()) {action(); true} else {post(Runnable(action))}}
    private val assistantUiContextCapture                             by lazy {AssistantUiContextCapture(registry = registry, screenSize = {width to height}, activeLayoutName = {layoutManager.activeLayoutName}, focusedWidget = ::keyFocusWidget)}
    private val pageAgentApprovalOverlay                        = PageAgentApprovalOverlay(::notifyPersistentContentChanged)
    private val textAssistantActions                                  by lazy {
        TextAssistantActions(
            ui                       = assistantUiDispatcher,
            findTextWidget           = { id -> findWidgetByStableId(id) as? TextBoxWidget },
            createTextWidget         = textModule::openForAssistant,
            clearEditing             = { widget -> widget.hideKeyboard(); textEditing.clearAllFocus(); hideAssistantIme()},
            presentWidget            = { widget, spec, created -> presenter.present(widget, spec, created) },
            removePresentationTarget = presenter::remove,
        )
    }
    private val mapAssistantActions                                   by lazy {
        MapAssistantActions(
            ui                     = assistantUiDispatcher,
            showMap                = {lifecycleController.show(WidgetTypeId.MAP); mapWidget},
            currentMap             = { mapWidget },
            present                = { widget, spec -> presenter.present(widget, spec) },
            dismissPresentation    = ::dismissMapPresentation,
            withTransientChanges   = { action -> withTransientChangeBatch(action) },
            notifyTransientChange  = ::notifyTransientContentChanged,
            notifyPersistentChange = ::notifyPersistentContentChanged,
        )
    }

    // ==================== Fullscreen ====================

    private inline fun <reified T : BaseWidget> fullscreenAs(): T?    = registry.fullscreenWidget as? T /** The fullscreen widget when it is a T, otherwise null. WidgetRegistry is the single fullscreen owner; use this only where the widget type genuinely matters. */
    private fun hasFullscreenWidget(): Boolean                        = registry.fullscreenWidget != null

    // ==================== 3DOF State ====================

    private var is3DofEnabled = false // Whether 3DOF mode is currently active
    private val threeDofOverlay        = ThreeDofOverlay()
    private val dashboardTransform     = DashboardTransform()

    // ==================== Wake Sleep cycle ====================

    private var displayState: WakeSleepManager.DisplayState = WakeSleepManager.DisplayState.WAKE
    private var isTransitioningDisplayState                 = false // Flag to suppress aggressive invalidation during display state transitions
    private var isHostPaused                                = false // Flag to suppress aggressive invalidation during display state transitions

    // =================== init block ===========================

    init {
        setWillNotDraw(false)
        registerWidgetLifecycles()
        unitSettings.onChanged = { applyEffectiveUnitSettings() } // Callers own the classified notification for unit changes; applying the derived widget presentation here must not emit a second transient callback.
        applyEffectiveUnitSettings()
    }

    /************
     * FUNCTIONS *
     ************/

    // ==================== Drawing ====================

    private fun requestRepaint() = contentChanges.requestRepaint() /** Requests one local redraw for pixel-only visual state. */
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
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Update 3DOF overlay dimensions
        threeDofOverlay.setScreenDimensions(w.toFloat(), h.toFloat())

        // Smart alignment helper needs current screen size for snap candidates
        smartAlignment.screenWidth  = w.toFloat()
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
    private fun ensureDrawnAboveNativeOverlays() {
        val rootView = parent as? ViewGroup ?: return
        if (rootView.indexOfChild(this) != rootView.childCount - 1) {
            rootView.bringChildToFront(this)
        }
        if (elevation < 10f) elevation = 10f
    }
    private fun drawVisibleKeyboardOverlays(canvas: Canvas) {
        registry.fullscreenWidget?.let { fullscreen -> if (!fullscreen.drawsOwnKeyboard) fullscreen.visibleKeyboard?.draw(canvas); return}
        registry.getAllSortedByZOrder().forEach { widget -> if (!widget.isMinimized && !widget.drawsOwnKeyboard) widget.visibleKeyboard?.draw(canvas)}
    }

    //====================== Full Screen ===========================

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

    // ==================== Z-Order Management ====================

    private fun bringWidgetToFront(widget: BaseWidget) {registry.bringToFront(widget, parent as? ViewGroup, this)}
    private fun bringWidgetToFrontForInteraction(widget: BaseWidget) {
        val before = widget.zOrder
        bringWidgetToFront(widget)
        if (widget.zOrder != before) interactionZOrderChanged = true
    }
    private fun widgetsTopDown(): List<BaseWidget> = registry.getAllSortedByZOrder().asReversed().sortedByDescending { it.hasScreenModal } /** Widgets in the order they get a first look at an input: a screen modal, then top-most first. */
    private fun screenModalWidget(): BaseWidget?   = registry.allWidgets().firstOrNull { it.hasScreenModal } /** The widget showing a screen-level modal, if any. */

    //============================ Input / Output ===========================

    private fun triggerCloseAppConfirmation() {closeConfirmationPopup?.show(); notifyTransientContentChanged()}

    //================= Text Editing ================

    fun setPhoneClipboard(content: String?)          = textEditing.setPhoneClipboard(content)
    private fun hasFocusedWidget():Boolean           = textEditing.hasKeyFocus

    //==================== Runtime =======================

    private fun notifyFocusChanged(persistent: Boolean = false) {
        // The phone keyboard follows text focus, which is not a suppressible dashboard change:
        // focus often changes inside a mutation whose visual callbacks are discarded.
        contentObserver?.onFocusChanged(hasFocusedWidget())
        if (contentChanges.isSuppressed) return
        if (persistent) notifyPersistentContentChanged() else notifyTransientContentChanged()
    } /** Reports focus to the host and emits the one classified visual change for that mutation. */
    private fun notifyTransientContentChanged()                                                   = contentChanges.notifyTransient() /** Emits a visible runtime change without scheduling persistence. */
    fun contentNotificationGeneration(): Long                                                     = contentChanges.notificationGeneration /** Used by input routing to avoid adding a second notification after a widget callback. */
    fun <T> withTransientChangeBatch(action: () -> T): T                                          = contentChanges.batch(action) /** Coalesces related updates; if one is persistent, that strongest classification wins. */
    private fun <T> runPersistentMutation(changed: (T) -> Boolean = { true }, action: () -> T): T = contentChanges.runPersistentMutation(action = action, changed = changed) /** Perform an action that may change the dashboard, and tell the rest of the app if that change should be saved. */
    private fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean): Boolean        = contentChanges.runClassifiedInput(action, changed) /** Classifies handled input as either persistent or transient, never both. */
    private fun notifyPersistentContentChanged()                                                  = contentChanges.notifyPersistent() /** Emits a dashboard mutation; the host owns structural refresh and debounced persistence. */
    private fun notifyDashboardRestored()                                                         = contentChanges.notifyDashboardRestored() /** Completes a restore with a structural refresh but without requesting a save. */
    private fun notifyMediaFrame(sourceId: String) {
        if (displayState == WakeSleepManager.DisplayState.OFF) return
        requestRepaint()
        contentObserver?.onMediaFrame(sourceId)
    }
    private fun setBinocularMediaSourceActive(sourceId: String, active: Boolean)                  {contentObserver?.onBinocularContentSourceChanged(sourceId, BinocularContentClass.MEDIA, active)}
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

    // ==================== Persistence ====================

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
        lifecycleController.register(weatherModule.lifecycle)
        lifecycleController.register(mapModule.lifecycle)
        lifecycleController.register(calendarModule.lifecycle)
        lifecycleController.register(financeModule.lifecycle)
        lifecycleController.register(newsModule.lifecycle)
        lifecycleController.register(notificationsModule.lifecycle)
        lifecycleController.register(speedometerModule.lifecycle)
        lifecycleController.register(subtitleModule.lifecycle)
        lifecycleController.register(mirrorModule.lifecycle)
        lifecycleController.verifyComplete()
    }
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
    fun setIsReturningUser()                          = layoutManager.expectSavedDashboard()
    /**
     * Runs one input on [widget] and classifies it once: saved when it removed the widget or
     * changed its [BaseWidget.persistentSignature], transient otherwise.
     */
    private fun runWidgetInput(widget: BaseWidget, action: () -> Boolean): Boolean {
        val before = widget.persistentSignature()
        return runClassifiedInput(action) {widget !in registry.allWidgets() || widget.persistentSignature() != before}
    }

    // ==================== Commands ====================

    fun isReady(): Boolean                           = width > 0 && height > 0
    fun executeCommand(invocation: AppCommandInvocation): AppCommandResult = commandExecutor.execute(invocation)

    // ==================== Menus, pop-ups and hover controls ====================

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
    fun isMenuVisible(): Boolean                     = contextMenu?.isVisible == true || textEditing.isMenuVisible || layoutManager.isNamePromptVisible
    private fun isClosePopupVisible(): Boolean       = closeConfirmationPopup?.isVisible == true || layoutManager.isDeleteConfirmationVisible
    private fun isAnyPopupVisible(): Boolean         = isMenuVisible() || isClosePopupVisible() || pageAgentApprovalOverlay.isVisible()
    private fun dismissMenu()                        { contextMenu?.dismiss(); textEditing.dismissMenus(); layoutManager.dismissNamePrompt() }

    //======================= Context menu =======================

    /** Lets a menu action own its classified notification while keeping navigation visual-only. */
    private fun dispatchContextMenuAction(action: () -> Boolean): Boolean {
        val before = contentNotificationGeneration()
        contextMenuDispatchDepth++; val handled = try {action()} finally {contextMenuDispatchDepth--}
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
    private fun handleMenuItemSelected(item: ContextMenu.MenuItem) {
        userInputActivity?.onUserInput(UserInputKind.SHORTCUT)
        commandRegistry.find(item.id)?.let { executeCommand(commandRegistry.invocation(it)) }
    }

    // ==================== Brightness ====================

    fun shouldApplyMediaBrightnessCap()               : Boolean = registry.allWidgets().any { it.requestsMediaBrightnessCap }
    private fun refreshBrightnessRules() {
        displaySettingsHandler?.applyMediaBrightnessCap(shouldApplyMediaBrightnessCap())
        displaySettingsHandler?.applyBrowserMinimumBrightness(shouldApplyBrowserMinimumBrightness())
    } /** Re-evaluates the brightness rules that widgets request (media cap, browser minimum). */

    // ==================== Location and units ====================

    // distribute location updates to widgets that are location dependent
    fun applyLocationMetadata(town: String, countryCode: String) {
        mapModule.applyLocationMetadata(town, countryCode)
        financeModule.updateCountryCode(countryCode)
        newsModule.updateCountryCode(countryCode)
        weatherModule.applyLocationMetadata(town, countryCode)
    }
    fun observeLiveLocationCountry(countryCode: String): Boolean = UnitSettings.normalizeCountryCode(countryCode)?.let {
        unitSettings.observeLiveCountry(it)
        true
    } ?: false
    private fun applyEffectiveUnitSettings() {
        val speedUnit = unitSettings.effectiveSpeedUnit()
        speedometerWidget?.setUnit(speedUnit)
        settingsController.setSpeedUnit(speedUnit)
        settingsController.setUnitSystemMode(unitSettings.globalMode)
        weatherModule.applyUnitSettings()
    }

    //======================= Widget wiring ==============================
    //map
    fun updateMapLocation(location: android.location.Location)                                        = mapModule.updateLocation(location)
    fun requestPhoneMapRefresh()                                                                      = mapModule.requestPhoneMapRefresh()
    fun applyMapSnapshot(snapshot: com.everyday.shared.sync.MapSnapshot)                              = mapModule.applySnapshot(snapshot)
    private fun onMapAddressFocusChanged(map: MapWidget, focused: Boolean)                            {
        val beforeZOrder = map.zOrder
        if (focused) bringWidgetToFront(map)
        notifyFocusChanged(persistent = focused && map.zOrder != beforeZOrder)
    } /** The map's address search took or gave up the keyboard focus. */
    fun configurePhoneRouteTransport(transport: GlassesRouteTransport?)                               = mapModule.configureRouteTransport(transport)
    fun onPhoneRouteConnectionChanged(connected: Boolean)                                             = mapModule.onRouteConnectionChanged(connected)
    fun onPhoneRouteResult(result: com.everyday.shared.transport.RouteTransportResult)                = mapModule.onRouteResult(result)
    fun onPhoneRouteError(error: com.everyday.shared.transport.RouteTransportError)                   = mapModule.onRouteError(error)
    fun onPhoneDestinationSearchResult(result: com.everyday.shared.transport.DestinationSearchResult) = mapModule.onDestinationSearchResult(result)
    fun onPhoneDestinationSearchError(error: com.everyday.shared.transport.DestinationSearchError)    = mapModule.onDestinationSearchError(error)
    private fun dismissMapPresentation() {val map = mapWidget ?: return; if (activePresentationSession?.targetId == stableWidgetId(map)) {dismissPresentation()}}

    //file widgets
    fun createImageWidget(path: String)                                                               = imageModule.open(path, pendingCreateX, pendingCreateY)
    fun createVideoWidget(uri: android.net.Uri, displayName: String)                                  = videoModule.open(uri, displayName)
    fun createPdfWidget(uri: android.net.Uri, displayName: String)                                    = pdfModule.open(uri, displayName)
    fun createFileBrowserWidget(filter: FileBrowserWidget.FileFilter, onFileSelected: (FileBrowserWidget.FileEntry) -> Unit) = fileBrowserModule.open(filter, onFileSelected)

    //mirror
    fun setMirroringEnabled(enabled: Boolean, phoneIp: String? = null) = mirrorModule.setMirroringEnabled(enabled, phoneIp)

    //browser
    fun shouldApplyBrowserMinimumBrightness()         : Boolean = registry.allWidgets().any { it.requestsBrowserMinimumBrightness }
    fun onTrimMemory(level: Int)                      = browserModule.onTrimMemory(level)
    fun onLowMemory()                                 = browserModule.onLowMemory()
    fun clearDebugWebViewData(profileSummary: String) = browserModule.clearDebugWebViewData(profileSummary)
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

    //weather
    fun setLocationPermissionDenied()           = weatherModule.setLocationPermissionDenied()
    fun applyWeatherData(weather: WeatherData?) = weatherModule.applyWeatherData(weather)

    //finance
    fun applyFinanceSnapshot(snapshot: FinanceSnapshot, notify: Boolean = true)                                  = financeModule.applySnapshot(snapshot, notify)
    fun applyFinanceError(message: String)                                                                       = financeModule.applyError(message)
    fun currentFinanceVisibleConfigs(): List<FinanceDashboardTileConfig>                                         = financeModule.currentVisibleConfigs()
    private fun updateFinanceTile(request: FinanceTileUpdateRequest): CompletableFuture<FinanceTileUpdateResult> = financeModule.updateTile(request)

    //news
    fun applyNewsSnapshot(snapshot: NewsSnapshot)                                                    = newsModule.applySnapshot(snapshot)
    fun applyNewsError(message: String)                                                              = newsModule.applyError(message)
    fun currentNewsCountryCode(): String?                                                            = newsModule.currentCountryCode()
    fun currentNewsSourceSpec(): NewsSourceSpec?                                                     = newsModule.currentSourceSpec()
    private fun setNewsSource(sourceSpec: NewsSourceSpec): CompletableFuture<NewsSourceUpdateResult> = newsModule.setSource(sourceSpec)

    //speedometer
    fun updateSpeedData(state: SpeedState)                      = speedometerModule.updateSpeed(state)
    fun setSpeedPermissionDenied()                              = speedometerModule.onSpeedUnavailable()
    fun setSpeedVisibilityThreshold(thresholdKmh: Float) {
        val forceVisibleChanged = settingsController.setSpeedVisibilityThreshold(thresholdKmh)
        if (forceVisibleChanged && displayState != WakeSleepManager.DisplayState.OFF) {notifyTransientContentChanged()}
    }

    //subtitle
    fun applySubtitleStatus(status: SubtitleStatus)             = subtitleModule.applyStatus(status)
    fun applySubtitleTranscript(transcript: SubtitleTranscript) = subtitleModule.applyTranscript(transcript)
    fun onSubtitlePhoneDisconnected()                           = subtitleModule.onPhoneDisconnected()

    //system status
    fun updateStatusBar(state: StatusBarDisplayState)                             = statusBarModule.update(state)

    //calendar
    fun setCalendarSnapshot(snapshot: com.everyday.shared.sync.CalendarSnapshot?) = calendarModule.setSnapshot(snapshot)

    //notifications
    fun applyNotificationsSnapshot(snapshot: PhoneNotificationsSnapshot)          = notificationsModule.applySnapshot(snapshot)

    // ==================== Cursor state ====================

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

        scrollbarDragWidget = scrollbarWidgetAt(cx, cy)

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
        scrollbarDragWidget = null
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
    fun updateCursor(x: Float, y: Float, isVisible: Boolean = true): Pair<Float, Float> {
        isCursorVisible = isVisible; cursorX = x; cursorY = y
        updateHover(x, y, isVisible)
        return Pair(x, y)
    }
    fun onCursorActivity()                          = registry.forEachWidget { it.onCursorActivity() }
    fun onCursorVisibilityChanged(visible: Boolean) = registry.forEachWidget { it.onCursorVisibilityChanged(visible) }

    // ==================== Press and long press ============================

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
    override fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        pendingDragWidget = null; pendingInternalDragWidget = null; pendingDragIsResize = false; pendingTextSelection = false
        pendingFormattingMenuDrag = false
        textEditing.formattingMenu?.cancelLongPress()
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

    // ==================== Taps and double taps ====================

    fun onTap(x: Float, y: Float): Boolean {
        if (pageAgentApprovalOverlay.isVisible()) {return pageAgentApprovalOverlay.onTap(x, y)} // Menus use screen-space coordinates (head-locked)
        textEditing.onMenuTap(x, y)?.let { return it } // The text edit and formatting menus sit above whichever surface owns the focused text zone.
        layoutManager.onOverlayTap(x, y)?.let { return it }
        if (closeConfirmationPopup?.isVisible == true) return closeConfirmationPopup?.onTap(x, y) ?: false
        settingsController.handleHoverControlsLayoutEditorTap(x, y)?.let { return it }
        settingsController.handleShortcutsSettingsMenuTap(x, y)?.let { return it }
        if (contextMenu?.isVisible == true) { return dispatchContextMenuAction { contextMenu?.onTap(x, y) ?: false }}
        settingsController.handleSpeedometerTap(x, y)?.let { return it }
        val (cx, cy) = toContentSpace(x, y) // Transform to content space for widget hit testing
        if (consumeVisibleKeyboardTap(cx, cy)) return true
        if (hoverControlsLayer.onTap(cx, cy)) return true // Hover controls are above every widget, including fullscreen ones.
        for (widget in widgetsTopDown()) {if (runWidgetInput(widget) { widget.onModalTap(cx, cy) }) return true} // Screen modals and open popups take taps anywhere, even over a fullscreen widget.
        registry.fullscreenWidget?.let { fullscreen -> return fullscreen.containsPoint(cx, cy) && tapWidget(fullscreen, cx, cy)} // A fullscreen widget is the only possible target; outside its bounds the tap does nothing.
        settingsController.handleDashboardTap(x, y)?.let { return it } // Modal dashboard check
        interactionTargetAt(cx, cy)?.let { target -> if (target.isMinimized) { target.toggleMinimize(); return true }; return tapWidget(target, cx, cy)} // Route the tap to the single top-most widget under the cursor — the same widget that owns hover — so an overlapping widget can't consume a tap meant for it.
        if (hasFocusedWidget()) { unfocusAll(); notifyTransientContentChanged(); return true }
        return false
    }
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

        showAppContextMenu(x, y, cx, cy) // Any other double-tap, on a border, inside a widget or on empty space, opens the app context menu, whether it came from the temple or from the phone's pointer.
        return true
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
    fun onContextMenuNavigationTap(x: Float, y: Float): Boolean {return dispatchContextMenuAction { contextMenu?.onNavigationTap(x, y) ?: false }}

    // =================== Widget Drag ======================

    fun isDragging(): Boolean = activeWidget != null || internalDragWidget != null || textEditing.formattingMenu?.isDragging() == true
    private fun handleDragOperations(x: Float, y: Float, dx: Float, dy: Float) {
        if (isLongPressSelectionMode || internalDragWidget != null || activeWidget != null || scrollbarDragWidget != null) {userInputActivity?.onUserInput(UserInputKind.DRAG)}
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
        scrollbarDragWidget?.let { it.onScrollbarDrag(dy); notifyTransientContentChanged(); return }
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
    fun setSmartAlignmentEnabled(enabled: Boolean) {smartAlignment.enabled = enabled}
    private fun beginPendingWidgetDrag(widget: BaseWidget, isResize: Boolean) {pendingDragWidget = widget; pendingDragIsResize = isResize; scheduleLongPress()}
    private fun hasPendingWidgetDrag(): Boolean = pendingDragWidget != null || pendingInternalDragWidget != null
    private fun clearPendingWidgetDrag() {pendingDragWidget = null; pendingInternalDragWidget = null; pendingDragIsResize = false; longPressRunnable = null}
    private fun setWidgetLayoutLocked(locked: Boolean, persist: Boolean = true) {
        if (isWidgetLayoutLocked == locked) {contextMenu?.setLayoutLockState(isWidgetLayoutLocked); return}
        isWidgetLayoutLocked = locked
        contextMenu?.setLayoutLockState(isWidgetLayoutLocked)
        if (locked) {cancelLongPress(); onDragEnd(notify = false)}
        if (persist) notifyPersistentContentChanged()
    }

    // =================== Widget Scroll ======================

    /** The widget whose scrollbar is under the content-space point ([cx], [cy]): the fullscreen one, or the top-most. */
    private fun scrollbarWidgetAt(cx: Float, cy: Float): BaseWidget? {
        registry.fullscreenWidget?.let { fullscreen -> return fullscreen.takeIf { it.isOverScrollbar(cx, cy) } }
        return registry.getAllSortedByZOrder().lastOrNull { !it.isMinimized && it.isOverScrollbar(cx, cy) }
    }
    fun isScrollbarDragActive(): Boolean = scrollbarDragWidget != null
    fun getScrollbarDragWidget(): BaseWidget? = scrollbarDragWidget
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

    //===================== Widget hovering =====================

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
    private fun interactionTargetAt(cx: Float, cy: Float): BaseWidget? =
        hoveredWidget?.takeIf { registry.allWidgets().contains(it) && it.containsPoint(cx, cy) }
            ?: registry.getWidgetAt(cx, cy)
    private fun clearWidgetHoverStates() {
        hoveredWidget = null
        registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
    }
    private fun clearWidgetHoverStatesExcept(except: BaseWidget?) {
        hoveredWidget = except
        registry.forEachWidget {
            if (it !== except) it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        }
    }
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
    private fun invalidateHoverVisuals() {requestRepaint()} //Hover updates are purely visual state changes on model objects drawn by this view. They should repaint immediately without being treated as structural widget changes.

    // ==================== Keyboard ====================

    //Key event
    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {return registry.fullscreenWidget?.handleKeyEvent(keyCode, event) == true}
    //Overlay input priority
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
    //keyboard focus handling
    private fun keyFocusWidget(): BaseWidget? = registry.allWidgets().firstOrNull { it.hasKeyFocus } /** The widget that typed keys go to: a text box or a browser page. */
    fun unfocusAll() {registry.forEachWidget { it.releaseKeyFocus() }; textEditing.clearAllFocus()} /** Takes the keyboard from everything, closing the map's address search if it has it. */
    fun onKeyPress(key: String, userInitiated: Boolean = false) {if (userInitiated) userInputActivity?.onUserInput(UserInputKind.KEYBOARD); textEditing.typeKey(key)} /** Keys go to the focused text zone first, otherwise to the focused browser's web page. */

    // ==================== Assistant ====================

    // Widget presentation by assistant
    override fun onDetachedFromWindow() {presenter.dismiss(); super.onDetachedFromWindow()}
    fun cancelPresentationForUserInput(): Boolean = presenter.cancelForUserInput()
    fun dismissPresentation(): Boolean = presenter.dismiss()
    /**
     * Returns the requested browser instance without falling back to another browser.
     * The Page Agent caller should invoke this at run start and retain the returned adapter.
     */
    private fun findBrowserWidgetByStableId(widgetId: String): BrowserWidget? = browserWidgets.firstOrNull { stableWidgetId(it) == widgetId }
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
    fun showPageAgentApproval(runId: String, request: PageAgentConfirmationRequest, respond: (Boolean) -> Unit) {pageAgentApprovalOverlay.show(runId, request, respond); ensureDrawnAboveNativeOverlays()}
    fun dismissPageAgentApproval(runId: String)                                                                 {pageAgentApprovalOverlay.dismiss(runId)}
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
    } /** A windowed browser for a normal assistant browser task, bound to the Page Agent. */
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
            StartNavigationSemanticToolHandler(startNavigation = ::startNavigation, isAvailable = ::isReady),
            StopNavigationSemanticToolHandler(stopNavigation = ::stopNavigation, isAvailable = { isReady() && mapWidget?.hasActiveNavigation() == true }),
            SetNewsSourceSemanticToolHandler(::setNewsSource),
            UpdateFinanceTileSemanticToolHandler(updateTile = ::updateFinanceTile, isAvailable = { financeWidget != null }),
            browserRoutingCoordinator?.let(::RunBrowserTaskSemanticToolHandler)
        )
    )
    private fun onWidgetRemoving(widget: BaseWidget) {presenter.onWidgetRemoving(widget) ; if (activeWidget === widget) activeWidget = null} /** Shared cleanup for every closed widget, run while it still has its stable ID. */
    fun release()                                    {presenter.dismiss() ; lifecycleController.closeAll()}

    /** Assistant navigation */
    fun startNavigation(request: StartNavigationRequest) = mapAssistantActions.startNavigation(request)
    fun stopNavigation() = mapAssistantActions.stopNavigation()
    private fun configureMapPois(request: ConfigureMapPoisRequest) = mapAssistantActions.configurePois(request)

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
    fun configureAssistantSettings(onPasteKey: (String) -> Unit, onRemoveKey: () -> Unit, onSelectModel: (String?) -> Unit) {
        settingsController.geminiListener = object : GeminiSettingsListener {
            override fun onPasteGeminiKey() {
                val clipboard = phoneClipboard
                if (!clipboard.isNullOrBlank()) onPasteKey(clipboard) else host?.requestClipboard()
            }
            override fun onRemoveGeminiKey() = onRemoveKey()
            override fun onSelectGeminiModel(model: String?) = onSelectModel(model)
        }
    }
    fun executeOpenUrlCommand(address: String): AppCommandResult = executeCommand(
        AppCommandInvocation(commandRegistry.openUrl.id, AppCommandArguments.Url(address))
    )
    fun applyPersistedDashboardReplacingCurrent(dashboard: WidgetDocument.PersistedDashboard) = layoutManager.replaceDashboard(dashboard)

    // ==================== 3DOF Mode ====================

    private fun toContentSpace(screenX: Float, screenY: Float): Pair<Float, Float> {return screenToContentCoordinates(screenX, screenY)}
    fun set3DofEnabled(enabled: Boolean) {
        if (is3DofEnabled != enabled) {
            is3DofEnabled = enabled
            threeDofOverlay.set3DofEnabled(enabled)
            Log.d(TAG,"3DOF mode ${if (enabled) "enabled" else "disabled"}")
            notifyTransientContentChanged()
        }
    } // Set 3DOF mode enabled state. Called by ThreeDofManager.
    fun set3DofOffset(offsetX: Float, offsetY: Float) {
        dashboardTransform.setOffset(offsetX, offsetY)
        threeDofOverlay.setContentOffset(offsetX, offsetY)
        notifyTransientContentChanged()
    }
    fun set3DofRoll(rollDeg: Float) {dashboardTransform.setRoll(rollDeg); notifyTransientContentChanged()}
    fun set3DofTransitionProgress(progress: Float) {threeDofOverlay.setTransitionProgress(progress); notifyTransientContentChanged()}
    private fun shouldApplyThreeDofTransform(): Boolean = displayState == WakeSleepManager.DisplayState.WAKE && !hasFullscreenWidget() && (is3DofEnabled || threeDofOverlay.isVisible())
    private fun currentDashboardTransform(): DashboardTransform.Snapshot =  dashboardTransform.snapshot(viewportWidth = width.toFloat(), viewportHeight = height.toFloat(), applies = shouldApplyThreeDofTransform())
    private fun screenToContentCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> = currentDashboardTransform().screenToContent(screenX, screenY) /** Convert the fixed screen cursor to the coordinate space in which widgets are drawn. */

    // ==================== Wake Sleep cycle ====================

    fun setDisplayState(state: WakeSleepManager.DisplayState) {
        if (displayState != state) {
            withTransientChangeBatch {
                isTransitioningDisplayState          = true // Set transition flag to prevent aggressive invalidation cascades which can cause power spikes and display flashes on battery power
                displayState                         = state; Log.d(TAG, "Display state set to: $state")
                registry.forEachWidget               { widget -> widget.glassesDisplayState = state; widget.refreshDisplayVisibility()} // Each widget decides whether it is visible in this state (BaseWidget.isVisibleIn). Browsers, videos and the mirror start or stop their native views accordingly.
                refreshBrightnessRules()
                syncWidgetLifecycleForDisplayState()
                isTransitioningDisplayState          = false // Clear transition flag and do a single, controlled invalidation
                notifyTransientContentChanged()              // Display visibility and wake/sleep transitions are transient state: repaint once and let the host refresh the binocular output without touching the dashboard file.
            }
        }
    }
    fun onAppResumed() {isHostPaused = false; syncWidgetLifecycleForDisplayState()}
    fun onAppPaused()  {isHostPaused = true ; registry.forEachWidget { it.onPause() }}
    private fun syncWidgetLifecycleForDisplayState() {
        if (displayState == WakeSleepManager.DisplayState.OFF) {
            registry.forEachWidget { it.onPause() }
        } else if (!isHostPaused) {
            registry.forEachWidget { it.onResume() }
        }
    }
    fun onHeadUpWake()              {newsModule.onHeadUpWake(); Log.d(TAG, "Head-up wake -> advance news item")}
    fun hasPinnedWidgets(): Boolean = registry.hasPinnedWidgets()
    fun isDisplayAwake():   Boolean = displayState == WakeSleepManager.DisplayState.WAKE
    private fun <T> withContentChangeSuppressed(action: () -> T): T = contentChanges.withNotificationsSuppressed(action) /** Discards callbacks produced by a temporary or restorative compound mutation. */
}
