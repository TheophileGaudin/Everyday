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
import com.everyday.everyday_glasses.adapters.AwaitingCommandUiDispatcher
import com.everyday.everyday_glasses.agent_tools.AssistantConfiguration
import com.everyday.everyday_glasses.agent_tools.AssistantModel
import com.everyday.everyday_glasses.agent_tools.AssistantSemanticFact
import com.everyday.everyday_glasses.agent_tools.AssistantSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.AssistantTextContentFormatter
import com.everyday.everyday_glasses.agent_tools.AssistantTextPresentationRequest
import com.everyday.everyday_glasses.agent_tools.AssistantToolCatalog
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecution
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.agent_tools.AssistantUiContext
import com.everyday.everyday_glasses.agent_tools.AssistantVoiceHoverControl
import com.everyday.everyday_glasses.agent_tools.AssistantWidgetContext
import com.everyday.everyday_glasses.agent_tools.BrowserRoutingCoordinator
import com.everyday.everyday_glasses.agent_tools.BrowserWidgetPageAgentAdapter
import com.everyday.everyday_glasses.agent_tools.ConfigureMapPoisRequest
import com.everyday.everyday_glasses.agent_tools.ConfigureMapPoisSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.DuckDuckGoWebSearchProvider
import com.everyday.everyday_glasses.agent_tools.FinanceTileUpdateRequest
import com.everyday.everyday_glasses.agent_tools.FinanceTileUpdateResult
import com.everyday.everyday_glasses.agent_tools.InspectUiSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.NewsSourceUpdateResult
import com.everyday.everyday_glasses.agent_tools.PageAgentApprovalOverlay
import com.everyday.everyday_glasses.agent_tools.PageAgentBrowserAdapter
import com.everyday.everyday_glasses.agent_tools.PageAgentConfirmationRequest
import com.everyday.everyday_glasses.agent_tools.PresentTextSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.RequestScreenshotSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.RunBrowserTaskSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.SetNewsSourceSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.StartNavigationSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.StopNavigationSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.UpdateFinanceTileSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.WebSearchSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.toAssistantSemanticFacts
import com.everyday.everyday_glasses.bindings.BrowserWidgetListener
import com.everyday.everyday_glasses.bindings.DisplaySettingsHandler
import com.everyday.everyday_glasses.bindings.FileBrowserWidgetListener
import com.everyday.everyday_glasses.bindings.FinanceWidgetListener
import com.everyday.everyday_glasses.bindings.GeminiSettingsListener
import com.everyday.everyday_glasses.bindings.GoogleAccountHandler
import com.everyday.everyday_glasses.bindings.MapWidgetListener
import com.everyday.everyday_glasses.bindings.MirrorWidgetListener
import com.everyday.everyday_glasses.bindings.NewsWidgetListener
import com.everyday.everyday_glasses.bindings.SubtitleWidgetListener
import com.everyday.everyday_glasses.bindings.TextBoxWidgetListener
import com.everyday.everyday_glasses.bindings.WidgetContentObserver
import com.everyday.everyday_glasses.bindings.WidgetDataProvider
import com.everyday.everyday_glasses.bindings.WidgetHost
import com.everyday.everyday_glasses.bindings.WidgetPersistenceListener
import com.everyday.everyday_glasses.binocular.BinocularContentClass
import com.everyday.everyday_glasses.controllers.AppCommandCapabilities
import com.everyday.everyday_glasses.controllers.AppCommandExecutor
import com.everyday.everyday_glasses.controllers.AppCommandResult
import com.everyday.everyday_glasses.controllers.GlassesRouteTransport
import com.everyday.everyday_glasses.controllers.HandlerPresentationScheduler
import com.everyday.everyday_glasses.controllers.PresentationBounds
import com.everyday.everyday_glasses.controllers.PresentationController
import com.everyday.everyday_glasses.controllers.PresentationLifecycleDelegate
import com.everyday.everyday_glasses.controllers.PresentationMode
import com.everyday.everyday_glasses.controllers.PresentationProgress
import com.everyday.everyday_glasses.controllers.PresentationSession
import com.everyday.everyday_glasses.controllers.PresentationSnapshot
import com.everyday.everyday_glasses.controllers.PresentationSpec
import com.everyday.everyday_glasses.controllers.SettingsController
import com.everyday.everyday_glasses.controllers.UserInputActivity
import com.everyday.everyday_glasses.controllers.UserInputKind
import com.everyday.everyday_glasses.data_models.GoogleAuthState
import com.everyday.everyday_glasses.data_models.MapGeoPoint
import com.everyday.everyday_glasses.data_models.NavigationOperationResult
import com.everyday.everyday_glasses.data_models.NavigationPresentationDefaults
import com.everyday.everyday_glasses.data_models.PoiDisplayConfig
import com.everyday.everyday_glasses.data_models.StartNavigationRequest
import com.everyday.everyday_glasses.data_models.WidgetDescriptor
import com.everyday.everyday_glasses.data_models.WidgetDescriptors
import com.everyday.everyday_glasses.data_models.WidgetDocument
import com.everyday.everyday_glasses.data_models.WidgetTypeId
import com.everyday.everyday_glasses.data_models.formatWalkingEta
import com.everyday.everyday_glasses.data_models.toAssistantSemanticFacts
import com.everyday.everyday_glasses.factories.BrowserWidgetFactory
import com.everyday.everyday_glasses.factories.VideoWidgetFactory
import com.everyday.everyday_glasses.helpers.CloseConfirmationHandler
import com.everyday.everyday_glasses.helpers.ContextMenuHandler
import com.everyday.everyday_glasses.helpers.CursorStabilizer
import com.everyday.everyday_glasses.helpers.LayoutDeletionHandler
import com.everyday.everyday_glasses.helpers.LayoutNameHandler
import com.everyday.everyday_glasses.helpers.SmartAlignmentHelper
import com.everyday.everyday_glasses.helpers.SmartAlignmentRenderer
import com.everyday.everyday_glasses.helpers.TextEditCommandHandler
import com.everyday.everyday_glasses.helpers.TextFormattingHandler
import com.everyday.everyday_glasses.helpers.ThreeDofCoordinateMath
import com.everyday.everyday_glasses.helpers.WidgetCaptureRestore
import com.everyday.everyday_glasses.managers.AppCommandRegistry
import com.everyday.everyday_glasses.managers.CommandAvailability
import com.everyday.everyday_glasses.managers.UnitDisplayFormatter
import com.everyday.everyday_glasses.managers.UnitSettings
import com.everyday.everyday_glasses.managers.WakeSleepManager
import com.everyday.everyday_glasses.managers.WidgetLayoutManager
import com.everyday.everyday_glasses.managers.WidgetLifecycleController
import com.everyday.everyday_glasses.managers.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.managers.WidgetRegistry
import com.everyday.everyday_glasses.stores.BuiltInLayouts
import com.everyday.everyday_glasses.stores.DashboardPersistence
import com.everyday.everyday_glasses.stores.ImageWidgetStorage
import com.everyday.everyday_glasses.stores.LayoutLibrary
import com.everyday.everyday_glasses.stores.ShortcutsStore
import com.everyday.everyday_glasses.stores.VideoQueueRepository
import com.everyday.everyday_glasses.ui.BaseHoverControl
import com.everyday.everyday_glasses.ui.BaseWidget
import com.everyday.everyday_glasses.ui.BrowserWidget
import com.everyday.everyday_glasses.ui.CalendarWidget
import com.everyday.everyday_glasses.ui.CloseAppHoverControl
import com.everyday.everyday_glasses.ui.CloseConfirmationPopup
import com.everyday.everyday_glasses.ui.ContextMenu
import com.everyday.everyday_glasses.ui.FileBrowserWidget
import com.everyday.everyday_glasses.ui.FinanceWidget
import com.everyday.everyday_glasses.ui.FormattingMenu
import com.everyday.everyday_glasses.ui.ImageWidget
import com.everyday.everyday_glasses.ui.LemonHoverControl
import com.everyday.everyday_glasses.ui.LocationWidget
import com.everyday.everyday_glasses.ui.MapWidget
import com.everyday.everyday_glasses.ui.NewsWidget
import com.everyday.everyday_glasses.ui.NotificationsWidget
import com.everyday.everyday_glasses.ui.PdfWidget
import com.everyday.everyday_glasses.ui.ScreenMirrorWidget
import com.everyday.everyday_glasses.ui.SpeedometerWidget
import com.everyday.everyday_glasses.ui.StatusBarWidget
import com.everyday.everyday_glasses.ui.SubtitleWidget
import com.everyday.everyday_glasses.ui.TextBoxWidget
import com.everyday.everyday_glasses.ui.TextEditContextMenu
import com.everyday.everyday_glasses.ui.ThreeDofOverlay
import com.everyday.everyday_glasses.ui.VideoWidget
import com.everyday.everyday_glasses.ui.WidgetLayoutDeleteConfirmationPopup
import com.everyday.everyday_glasses.ui.WidgetLayoutNamePrompt
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
import kotlin.math.roundToInt

/**
 * Container view that manages dynamic widgets (text boxes, status bar, etc.).
 *
 * Responsibilities are delegated to helper classes:
 * - CursorStabilizer: Cursor physics and smoothing for glasses temple input
 * - WidgetRegistry: Widget collection and z-ordering
 * - WidgetwidgetCaptureRestore: Save/restore widget states
 * - ThreeDofOverlay: Visual overlay for 3DOF mode (dashed boundary)
 *
 * This class handles:
 * - Widget creation/deletion
 * - Cursor interaction routing
 * - Context menu display
 * - Widget drawing coordination
 * - Long-press to drag
 * - Fullscreen mode for widgets
 * - 3DOF mode content transformation
 */
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
    private val widgetCaptureRestore = WidgetCaptureRestore(context)
    private val videoQueueRepository = VideoQueueRepository(context)
    private val lifecycleController = WidgetLifecycleController(
        runOnUiThread = { action -> post(action) },
        onChanged = ::notifyPersistentContentChanged,
        registry = registry,
        onMutationStarted = { lifecycleMutationInProgress = true },
        onMutationFinished = { lifecycleMutationInProgress = false }
    )
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
        onStateChanged = ::notifyTransientContentChanged,
        onPlaybackVisibilityChanged = { sourceId, active ->
            setBinocularMediaSourceActive(sourceId, active)
            notifyMediaBrightnessCapChanged()
        },
    )
    private val threeDofOverlay = ThreeDofOverlay()
    private val smartAlignment = SmartAlignmentHelper(registry)
    private val smartAlignmentRenderer = SmartAlignmentRenderer()
    private val unitSettings = UnitSettings(context)
    /** Runtime source of truth for reopenable widget templates. */
    private val closedTemplateRecords = LinkedHashMap<WidgetTypeId, WidgetDocument.PersistedWidgetRecord>()

    // ==================== 3DOF State ====================

    // Whether 3DOF mode is currently active
    private var is3DofEnabled = false

    // Current content offset from 3DOF head tracking (in pixels)
    private var threeDofOffsetX = 0f
    private var threeDofOffsetY = 0f
    private var threeDofRollDeg = 0f

    // ==================== Menus & Overlays ====================
    
    private var contextMenu: ContextMenu? = null
    private var contextMenuDispatchDepth = 0
    private var textEditMenu: TextEditContextMenu? = null
    private var textEditMenuDispatchDepth = 0
    private enum class TextEditMenuTarget { TEXT_WIDGET, BROWSER_ADDRESS }
    private var textEditMenuTarget: TextEditMenuTarget = TextEditMenuTarget.TEXT_WIDGET
    private var formattingMenu: FormattingMenu? = null
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
            CommandAvailability.TEXT_FOCUSED -> focusedWidget?.isFocused() == true || focusedBrowserWidget != null
            CommandAvailability.BROWSER_AVAILABLE -> focusedBrowserWidget != null || fullscreenBrowserWidget != null || isReady()
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
    private var pendingLocationTown: String? = null
    private var pendingLocationCountryCode: String? = null
    private var pendingWeatherData: WeatherData? = null
    private var pendingLocationAddress: String? = null
    private var pendingLocationWeather: String? = null
    private var pendingLocationSunriseEpochMs: Long? = null
    private var pendingLocationSunsetEpochMs: Long? = null
    private var pendingMapLocation: android.location.Location? = null
    private var pendingMapLocationLabel: String = ""
    private var pendingMapCountryCode: String = ""
    private var pendingCalendarSnapshot: com.everyday.shared.sync.CalendarSnapshot? = null

    // Finance widget - delegated to registry
    val financeWidget: FinanceWidget?
        get() = registry.financeWidget

    // Pending country code for finance widget
    private var pendingFinanceCountryCode: String? = null

    // News widget - delegated to registry
    val newsWidget: NewsWidget?
        get() = registry.newsWidget

    // Pending country code for news widget
    private var pendingNewsCountryCode: String? = null

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
        context = context,
        layouts = layoutLibrary,
        canApplyLayout = ::isReady,
        captureDashboard = ::captureWidgetDashboardWithoutLayoutName,
        applyDashboard = ::applyPersistedDashboardReplacingCurrent,
        buildDeliveredLayout = { name ->
            BuiltInLayouts.buildDashboard(name, width.toFloat(), height.toFloat())
        },
        showNamePrompt = { suggestedName ->
            dismissMenu()
            layoutNamePrompt?.show(suggestedName)
            notifyFocusChanged(true)
        },
        showNameError = { error -> layoutNamePrompt?.showError(error) },
        dismissNamePrompt = { layoutNamePrompt?.dismiss() },
        showDeleteConfirmation = { name -> layoutDeleteConfirmationPopup?.show(name) },
        beforeApplyLayout = ::requestLayoutSwitchBrightness,
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

    // Currently focused widget
    private var focusedWidget: TextBoxWidget? = null
    private var focusedBrowserWidget: BrowserWidget? = null
    private var suppressNestedFocusCallbacks = false

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
    
    // Transitional typed views over the registry's single fullscreen owner. These carry no
    // state; they keep the specialized input handlers readable while fullscreen ownership is
    // centralized in WidgetRegistry.
    private val fullscreenTextWidget: TextBoxWidget?
        get() = registry.fullscreenWidget as? TextBoxWidget
    private val fullscreenBrowserWidget: BrowserWidget?
        get() = registry.fullscreenWidget as? BrowserWidget
    private val fullscreenImageWidget: ImageWidget?
        get() = registry.fullscreenWidget as? ImageWidget
    private val fullscreenVideoWidget: VideoWidget?
        get() = registry.fullscreenWidget as? VideoWidget
    private val fullscreenPdfWidget: PdfWidget?
        get() = registry.fullscreenWidget as? PdfWidget
    private val fullscreenFileBrowserWidget: FileBrowserWidget?
        get() = registry.fullscreenWidget as? FileBrowserWidget
    private var googleAuthBrowserWidget: BrowserWidget? = null
    private var browserRoutingCoordinator: BrowserRoutingCoordinator? = null
    private val webSearchHandler = WebSearchSemanticToolHandler(DuckDuckGoWebSearchProvider())
    // registry.isFullscreen treats an absent widget as windowed. A bare identity check against
    // registry.fullscreenWidget would report `true` for a singleton that is not on the dashboard
    // at all, because both sides are then null.
    private val isStatusBarFullscreen: Boolean
        get() = registry.isFullscreen(statusBarWidget)
    private val isLocationWidgetFullscreen: Boolean
        get() = registry.isFullscreen(locationWidget)
    private val isMapWidgetFullscreen: Boolean
        get() = registry.isFullscreen(mapWidget)
    private val isCalendarWidgetFullscreen: Boolean
        get() = registry.isFullscreen(calendarWidget)
    private val isFinanceWidgetFullscreen: Boolean
        get() = registry.isFullscreen(financeWidget)
    private val isNewsWidgetFullscreen: Boolean
        get() = registry.isFullscreen(newsWidget)
    private val isNotificationsWidgetFullscreen: Boolean
        get() = registry.isFullscreen(notificationsWidget)
    private val isSpeedometerWidgetFullscreen: Boolean
        get() = registry.isFullscreen(speedometerWidget)
    private val isSubtitleWidgetFullscreen: Boolean
        get() = registry.isFullscreen(subtitleWidget)
    private val isMirrorWidgetFullscreen: Boolean
        get() = registry.isFullscreen(screenMirrorWidget)

    private val browserLifecycle by lazy {
        WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[WidgetTypeId.BROWSER],
            widgetType = BrowserWidget::class,
            stateType = DashboardPersistence.BrowserWidgetState::class,
            stateCodec = WidgetDocument.codecs[WidgetTypeId.BROWSER],
            capture = widgetCaptureRestore::getBrowserWidgetState,
            restore = ::restoreBrowserWidget,
            configure = ::configureBrowserWidget,
            register = { registry.add(it) },
            unregister = { widget ->
                widgetCaptureRestore.getBrowserWidgetState(widget)?.let {
                    storeClosedTemplate(WidgetTypeId.BROWSER, it)
                }
                if (focusedBrowserWidget === widget) setFocusedWidget(null)
                if (activeWidget === widget) activeWidget = null
                if (googleAuthBrowserWidget === widget) googleAuthBrowserWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.remove(widget)
                notifyMediaBrightnessCapChanged()
                notifyBrowserMinimumBrightnessChanged()
                updateMinimizedWidgetPositions()
            },
            teardown = ::destroyWidget,
            onFullscreenChanged = ::handleBrowserWidgetFullscreenSideEffects,
            onMinimizeChanged = ::handleBrowserWidgetMinimizeSideEffects
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
            register = { registry.add(it) },
            unregister = { widget ->
                storeClosedTemplate(
                    WidgetTypeId.TEXT,
                    widgetCaptureRestore.getTextWidgetState(widget)
                )
                if (focusedWidget === widget) setFocusedWidget(null)
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.remove(widget)
                updateMinimizedWidgetPositions()
            },
            onFullscreenChanged = ::handleGenericFullscreenSideEffects,
            onMinimizeChanged = ::handleGenericMinimizeSideEffects
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
            register = { registry.add(it) },
            unregister = { widget ->
                val path = widget.getImagePath()
                val hasSibling = imageWidgets.any { it !== widget && it.getImagePath() == path }
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.remove(widget)
                if (!hasSibling) ImageWidgetStorage.deleteIfManaged(context, path)
                updateMinimizedWidgetPositions()
            },
            onFullscreenChanged = ::handleGenericFullscreenSideEffects,
            onMinimizeChanged = ::handleGenericMinimizeSideEffects
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
            register = { registry.add(it) },
            unregister = { widget ->
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.remove(widget)
                updateMinimizedWidgetPositions()
                notifyMediaBrightnessCapChanged()
            },
            teardown = ::destroyWidget,
            onFullscreenChanged = ::handleVideoWidgetFullscreenSideEffects,
            onMinimizeChanged = ::handleGenericMinimizeSideEffects
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
            register = { registry.add(it) },
            unregister = { widget ->
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.remove(widget)
                updateMinimizedWidgetPositions()
            },
            teardown = ::destroyWidget,
            onFullscreenChanged = ::handleGenericFullscreenSideEffects,
            onMinimizeChanged = ::handleGenericMinimizeSideEffects
        )
    }
    private val filesLifecycle by lazy {
        WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[WidgetTypeId.FILES],
            widgetType = FileBrowserWidget::class,
            stateType = Unit::class,
            register = { registry.add(it) },
            unregister = { widget ->
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.remove(widget)
                updateMinimizedWidgetPositions()
            },
            onFullscreenChanged = ::handleGenericFullscreenSideEffects,
            onMinimizeChanged = ::handleGenericMinimizeSideEffects
        )
    }

    // ==================== Clipboard ====================
    
    private var glassesClipboard: String? = null
    private var phoneClipboard: String? = null

    // ==================== Selection Tracking ====================
    
    private var isTextSelectionActive = false
    private var selectionStartX = 0f
    private var accumulatedSelectionDx = 0f
    private var isLongPressSelectionMode = false
    private var pendingFormattingMenuDrag = false

    // Scrollbar dragging state
    private var isScrollbarDragActive = false
    private var scrollbarDragWidget: TextBoxWidget? = null
    
    // Two-finger scroll mode
    private var isTwoFingerScrollMode = false
    private var currentPointerCount = 1

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
    private var suppressContentChangedCallback = false
    private var lifecycleMutationInProgress = false
    private var transientNotificationDepth = 0
    private var transientNotificationPending = false
    private var persistentNotificationDepth = 0
    private var persistentNotificationPending = false
    private var contentNotificationGeneration = 0L

    init {
        setWillNotDraw(false)
        registerWidgetLifecycles()
        setupWidgetCaptureRestoreCallbacks()
        // Callers own the classified notification for unit changes; applying the derived
        // widget presentation here must not emit a second transient callback.
        unitSettings.onChanged = { applyEffectiveUnitSettings() }
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
            storeClosedState: (S) -> Unit,
            beforeRemove: (W) -> Unit = {},
            teardown: (W) -> Unit = ::destroyWidget,
            onFullscreen: (W, Boolean) -> Unit = ::handleSingletonRegisteredFullscreen,
            onMinimize: (W, Boolean) -> Unit = ::handleSingletonRegisteredMinimize
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
                    capture = captureState,
                    register = { registry.add(it) },
                    unregister = { widget ->
                        captureState(widget)?.let(storeClosedState)
                        notifyPresentationTargetRemoved(widget)
                        if (activeWidget === widget) activeWidget = null
                        beforeRemove(widget)
                        registry.remove(widget)
                        updateMinimizedWidgetPositions()
                    },
                    teardown = teardown,
                    onFullscreenChanged = onFullscreen,
                    onMinimizeChanged = onMinimize
                )
            )
        }

        bindSingleton(
            WidgetTypeId.STATUS, StatusBarWidget::class, DashboardPersistence.StatusBarState::class,
            { statusBarWidget }, ::createStatusBarPresence, ::applyStatusBarState,
            { widgetCaptureRestore.getStatusBarState(it) },
            { storeClosedTemplate(WidgetTypeId.STATUS, it) })
        bindSingleton(
            WidgetTypeId.LOCATION, LocationWidget::class, DashboardPersistence.LocationWidgetState::class,
            { locationWidget }, ::createLocationPresence, ::applyLocationState,
            { widgetCaptureRestore.getLocationWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.LOCATION, it) })
        bindSingleton(
            WidgetTypeId.MAP, MapWidget::class, DashboardPersistence.MapWidgetState::class,
            { mapWidget }, ::createMapPresence, ::applyMapState,
            { widgetCaptureRestore.getMapWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.MAP, it) },
            beforeRemove = MapWidget::release)
        bindSingleton(
            WidgetTypeId.CALENDAR, CalendarWidget::class, DashboardPersistence.CalendarWidgetState::class,
            { calendarWidget }, ::createCalendarPresence, ::applyCalendarState,
            { widgetCaptureRestore.getCalendarWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.CALENDAR, it) })
        bindSingleton(
            WidgetTypeId.FINANCE, FinanceWidget::class, DashboardPersistence.FinanceWidgetState::class,
            { financeWidget }, ::createFinancePresence, ::applyFinanceState,
            { widgetCaptureRestore.getFinanceWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.FINANCE, it) },
            onMinimize = { widget, minimized ->
                handleSingletonRegisteredMinimize(widget, minimized)
                dataProvider?.onFinanceVisibilityChanged(!minimized)
                if (!minimized) widget.requestData(force = false, reason = "finance_unminimized")
            })
        bindSingleton(
            WidgetTypeId.NEWS, NewsWidget::class, DashboardPersistence.NewsWidgetState::class,
            { newsWidget }, ::createNewsPresence, ::applyNewsState,
            { widgetCaptureRestore.getNewsWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.NEWS, it) })
        bindSingleton(
            WidgetTypeId.NOTIFICATIONS, NotificationsWidget::class, DashboardPersistence.NotificationsWidgetState::class,
            { notificationsWidget }, ::createNotificationsPresence, ::applyNotificationsState,
            { widgetCaptureRestore.getNotificationsWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.NOTIFICATIONS, it) })
        bindSingleton(
            WidgetTypeId.SPEEDOMETER, SpeedometerWidget::class, DashboardPersistence.SpeedometerWidgetState::class,
            { speedometerWidget }, ::createSpeedometerPresence, ::applySpeedometerState,
            { widgetCaptureRestore.getSpeedometerWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.SPEEDOMETER, it) },
            beforeRemove = { settingsController.dismissSpeedometerSettingsMenu() })
        bindSingleton(
            WidgetTypeId.SUBTITLE, SubtitleWidget::class, DashboardPersistence.SubtitleWidgetState::class,
            { subtitleWidget }, ::createSubtitlePresence, ::applySubtitleState,
            { widgetCaptureRestore.getSubtitleWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.SUBTITLE, it) })
        bindSingleton(
            WidgetTypeId.MIRROR, ScreenMirrorWidget::class, DashboardPersistence.MirrorWidgetState::class,
            { screenMirrorWidget }, ::createMirrorPresence, ::applyMirrorState,
            { widgetCaptureRestore.getMirrorWidgetState(it) },
            { storeClosedTemplate(WidgetTypeId.MIRROR, it) },
            beforeRemove = { notifyMediaBrightnessCapChanged() })

        lifecycleController.verifyComplete()
    }

    private fun handleSingletonRegisteredFullscreen(widget: BaseWidget, fullscreen: Boolean) {
        if (fullscreen) {
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
            notificationsWidget?.setScreenHeight(height.toFloat())
        } else if (widget is MapWidget) {
            widget.enforceWindowedMode()
        }
    }

    private fun handleSingletonRegisteredMinimize(widget: BaseWidget, minimized: Boolean) {
        if (!minimized && widget.isFullscreen) {
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
        }
        updateMinimizedWidgetPositions()
    }

    private fun setupWidgetCaptureRestoreCallbacks() {
        widgetCaptureRestore.listener = object : WidgetPersistenceListener {
            override fun onWidgetFocusChanged(widget: BaseWidget?, focused: Boolean) {
                if (focused && widget != null) {
                    setFocusedWidget(widget)
                } else if (!focused) {
                    if (widget is TextBoxWidget && focusedWidget == widget) {
                        setFocusedWidget(null)
                    } else if (widget is BrowserWidget && focusedBrowserWidget == widget) {
                        setFocusedWidget(null)
                    }
                }
            }

            override fun onWidgetStateChanged() = notifyTransientContentChanged()
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

                for (browser in browserWidgets) {
                    val view = browser.getWebView()
                    if (view != null) {
                        // Always keep WebView hidden when video is in fullscreen mode
                        // (the fullscreen video view is a separate view on top)
                        if (browser.isInVideoFullscreen()) {
                            view.visibility = GONE
                        } else {
                            view.visibility = when (state) {
                                WakeSleepManager.DisplayState.OFF -> GONE
                                WakeSleepManager.DisplayState.SLEEP -> if (browser.isPinned) VISIBLE else GONE
                                else -> if (browser.isMinimized) GONE else VISIBLE
                            }
                        }
                    }
                    browser.onDisplayVisibilityChanged(isBrowserWidgetVisible(browser))
                }
                for (video in videoWidgets) {
                    video.onDisplayVisibilityChanged(
                        state == WakeSleepManager.DisplayState.WAKE && !video.isMinimized
                    )
                }
                notifyMediaBrightnessCapChanged()
                notifyBrowserMinimumBrightnessChanged()

                screenMirrorWidget?.let { mirror ->
                    mirror.onDisplayVisibilityChanged(isMirrorWidgetVisible(mirror))
                }
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

    fun onPhoneReconnected() {
        Log.d(TAG, "Phone reconnected")
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
        val widget = fullscreenBrowserWidget
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
            updateMinimizedWidgetPositions()
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
            updateMinimizedWidgetPositions()
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

    /** Requests one local redraw for pixel-only visual state. */
    private fun requestRepaint() {
        if (Looper.myLooper() == Looper.getMainLooper()) invalidate() else postInvalidate()
    }

    /** Suppresses intermediate widget/lifecycle callbacks for a temporary compound mutation. */
    private inline fun <T> withContentChangeSuppressed(action: () -> T): T {
        val previous = suppressContentChangedCallback
        suppressContentChangedCallback = true
        return try {
            action()
        } finally {
            suppressContentChangedCallback = previous
        }
    }

    /** Reports focus to the host and emits the one classified visual change for that mutation. */
    private fun notifyFocusChanged(focused: Boolean, persistent: Boolean = false) {
        if (suppressContentChangedCallback) return
        contentObserver?.onFocusChanged(focused)
        if (persistent) notifyPersistentContentChanged() else notifyTransientContentChanged()
    }

    /** Emits a visible runtime change without scheduling persistence. */
    private fun notifyTransientContentChanged() {
        if (lifecycleMutationInProgress) return
        if (suppressContentChangedCallback) return
        if (transientNotificationDepth > 0) {
            transientNotificationPending = true
            return
        }
        requestRepaint()
        contentNotificationGeneration++
        contentObserver?.onTransientContentChanged()
    }

    /**
     * Returns the mutation notification generation for input routing. Cursor-only movement
     * still needs a binocular refresh, but widget-owned transient/persistent callbacks already
     * provide one and must not be followed by a second caller-side refresh.
     */
    fun contentNotificationGeneration(): Long = contentNotificationGeneration

    /** Coalesces several related runtime updates into one transient notification. */
    fun <T> withTransientChangeBatch(action: () -> T): T {
        transientNotificationDepth++
        try {
            return action()
        } finally {
            transientNotificationDepth--
            if (transientNotificationDepth == 0 && transientNotificationPending) {
                transientNotificationPending = false
                notifyTransientContentChanged()
            }
        }
    }

    /** Runs a synchronous widget mutation while upgrading any callback it emits to persistent. */
    private fun <T> runPersistentMutation(
        action: () -> T,
        changed: (T) -> Boolean = { true }
    ): T {
        transientNotificationDepth++
        persistentNotificationDepth++
        val result = try {
            action()
        } finally {
            transientNotificationDepth--
            transientNotificationPending = false
            persistentNotificationDepth--
            persistentNotificationPending = false
        }
        if (changed(result)) notifyPersistentContentChanged()
        return result
    }

    private fun <T> runPersistentMutation(action: () -> T): T =
        runPersistentMutation(action = action, changed = { true })

    /** Upgrades a widget callback to persistent only when it changed a persisted field. */
    private fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean): Boolean {
        var persistedChange = false
        val handled = runPersistentMutation(
            action = { action().also { persistedChange = changed() } },
            changed = { it && persistedChange }
        )
        if (handled && !persistedChange) notifyTransientContentChanged()
        return handled
    }

    /** Emits a dashboard mutation; the host owns binocular refresh and debounced persistence. */
    private fun notifyPersistentContentChanged() {
        if (lifecycleMutationInProgress) return
        if (suppressContentChangedCallback) return
        if (cursorPressed) interactionPersistentNotified = true
        if (persistentNotificationDepth > 0) {
            persistentNotificationPending = true
            return
        }
        requestRepaint()
        contentNotificationGeneration++
        contentObserver?.onPersistentContentChanged()
    }

    /** Completes a state restore without turning the loaded state into a new save request. */
    private fun notifyDashboardRestored() {
        requestRepaint()
        contentObserver?.onDashboardRestored()
    }

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

    private fun notifyMediaBrightnessCapChanged() {
        displaySettingsHandler?.applyMediaBrightnessCap(shouldApplyMediaBrightnessCap())
    }

    private fun notifyBrowserMinimumBrightnessChanged() {
        displaySettingsHandler?.applyBrowserMinimumBrightness(shouldApplyBrowserMinimumBrightness())
    }

    private fun requestLayoutSwitchBrightness() {
        displaySettingsHandler?.applyLayoutSwitchBrightness()
    }

    private fun isBrowserWidgetVisible(widget: BrowserWidget): Boolean {
        return when (displayState) {
            WakeSleepManager.DisplayState.OFF -> false
            WakeSleepManager.DisplayState.SLEEP -> widget.isPinned && !widget.isMinimized
            WakeSleepManager.DisplayState.WAKE -> !widget.isMinimized
        }
    }

    private fun isMirrorWidgetVisible(widget: ScreenMirrorWidget): Boolean {
        return when (displayState) {
            WakeSleepManager.DisplayState.OFF -> false
            WakeSleepManager.DisplayState.SLEEP -> widget.isPinned && !widget.isMinimized
            WakeSleepManager.DisplayState.WAKE -> !widget.isMinimized
        }
    }

    private fun configureBrowserWidget(widget: BrowserWidget) {
        widget.listener = object : BrowserWidgetListener {
            override fun onFocusChanged(focused: Boolean) {
                if (focused) setFocusedWidget(widget)
                else if (focusedBrowserWidget == widget) setFocusedWidget(null)
            }
            override fun onStateChanged(state: BrowserWidget.State) {
                widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
                notifyTransientContentChanged()
            }
            override fun onPersistentStateChanged() = notifyPersistentContentChanged()
            override fun onKeyboardRequested(show: Boolean) {
                host?.requestBrowserKeyboard(show)
            }
        }
        widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
        notifyBrowserMinimumBrightnessChanged()
    }

    private fun configureScreenMirrorWidget(widget: ScreenMirrorWidget) {
        widget.listener = object : MirrorWidgetListener {
            override fun onStateChanged(state: ScreenMirrorWidget.State) {
                widget.onDisplayVisibilityChanged(isMirrorWidgetVisible(widget))
                notifyTransientContentChanged()
            }

            override fun onStreamStateChanged(state: ScreenMirrorWidget.StreamState) {
                notifyMediaBrightnessCapChanged()
                notifyTransientContentChanged()
            }

            override fun onFrameReady() {
                if (widget.isBinocularMediaActive()) notifyMediaFrame(widget.binocularSourceId)
            }

            override fun onMediaStateChanged(active: Boolean) {
                setBinocularMediaSourceActive(widget.binocularSourceId, active)
            }

            override fun requestMirror(start: Boolean) {
                host?.requestMirror(start)
            }
        }
        widget.onDisplayVisibilityChanged(isMirrorWidgetVisible(widget))
        notifyMediaBrightnessCapChanged()
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

        textEditMenu = TextEditContextMenu(w.toFloat(), h.toFloat()).apply {
            commandHandler = object : TextEditCommandHandler {
                override fun cut() = handleCut()
                override fun copy() = handleCopy()
                override fun paste(content: String) = handlePaste(content)
                override fun selectAll() = handleSelectAll()
                override fun showFormatting() = showFormattingMenu()
                override fun onMenuDismissed() {
                    if (textEditMenuDispatchDepth == 0) notifyTransientContentChanged()
                }
            }
        }

        formattingMenu = FormattingMenu(w.toFloat(), h.toFloat()).apply {
            formattingHandler = object : TextFormattingHandler {
                override fun setFontSize(size: Float) = handleFontSizeChanged(size)
                override fun setTextWrap(enabled: Boolean) = handleTextWrapToggled(enabled)
                override fun setBold(enabled: Boolean) = handleBoldToggled()
                override fun setItalic(enabled: Boolean) = handleItalicToggled()
                override fun setUnderline(enabled: Boolean) = handleUnderlineToggled()
                override fun setBulletList(enabled: Boolean) = handleBulletListToggled()
                override fun setNumberedList(enabled: Boolean) = handleNumberedListToggled()
                override fun setColumns(columns: Int) {
                    runPersistentMutation { focusedWidget?.setColumns(columns) }
                }
                override fun onMenuDismissed() = notifyTransientContentChanged()
            }
        }

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
                        notifyFocusChanged(hasFocusedWidget())
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

    private fun handleGenericFullscreenSideEffects(widget: BaseWidget, isFullscreen: Boolean) {
        if (isFullscreen) widget.setFullscreenBounds(width.toFloat(), height.toFloat())
    }

    private fun handleGenericMinimizeSideEffects(widget: BaseWidget, isMinimized: Boolean) {
        if (!isMinimized && widget.isFullscreen) {
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
        }
        updateMinimizedWidgetPositions()
    }

    private fun handleBrowserWidgetFullscreenSideEffects(widget: BrowserWidget, isFullscreen: Boolean) {
        handleGenericFullscreenSideEffects(widget, isFullscreen)
        widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
    }

    private fun handleBrowserWidgetMinimizeSideEffects(widget: BrowserWidget, isMinimized: Boolean) {
        handleGenericMinimizeSideEffects(widget, isMinimized)
        widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
    }

    private fun handleVideoWidgetFullscreenSideEffects(widget: VideoWidget, isFullscreen: Boolean) {
        handleGenericFullscreenSideEffects(widget, isFullscreen)
        if (isFullscreen) widget.startPlayback(autoPlay = true)
        widget.setWindowedMode()
        notifyMediaBrightnessCapChanged()
    }

    fun updateLocationData(
        address: String,
        weather: String,
        sunriseEpochMs: Long? = null,
        sunsetEpochMs: Long? = null
    ) {
        pendingLocationAddress = address
        pendingLocationWeather = weather
        pendingLocationSunriseEpochMs = sunriseEpochMs
        pendingLocationSunsetEpochMs = sunsetEpochMs
        locationWidget?.setLocationData(address, weather)
        locationWidget?.setSunTimes(sunriseEpochMs, sunsetEpochMs)
        notifyTransientContentChanged()
    }

    fun updateFinanceCountryCode(countryCode: String) {
        pendingFinanceCountryCode = countryCode
        financeWidget?.setCountryCode(countryCode)
    }

    fun updateNewsCountryCode(countryCode: String) {
        pendingNewsCountryCode = countryCode
        newsWidget?.setCountryCode(countryCode)
    }

    fun setLocationPermissionDenied() {
        pendingLocationAddress = "-----"
        pendingLocationWeather = "-"
        pendingLocationSunriseEpochMs = null
        pendingLocationSunsetEpochMs = null
        locationWidget?.setPermissionDenied()
        notifyTransientContentChanged()
    }

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
                override fun onStateChanged() = notifyTransientContentChanged()
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

    fun applyLocationMetadata(town: String, countryCode: String) {
        pendingLocationTown = town
        pendingLocationCountryCode = countryCode
        updateMapMetadata(town, countryCode)
        updateFinanceCountryCode(countryCode)
        updateNewsCountryCode(countryCode)
        renderLocationWeather()
    }

    fun applyWeatherData(weather: WeatherData?) {
        pendingWeatherData = weather
        renderLocationWeather()
    }

    private fun renderLocationWeather() {
        val town = pendingLocationTown ?: return
        val countryCode = pendingLocationCountryCode.orEmpty()
        val weather = pendingWeatherData
        val addressText = if (countryCode.isNotEmpty()) "$town, $countryCode" else town
        if (weather == null) {
            updateLocationData(addressText, "Checking weather...")
            return
        }
        val tempText = UnitDisplayFormatter.formatTemperature(
            weather.tempCurrent,
            unitSettings.effectiveTemperatureUnit()
        )
        val weatherText = if (weather.weatherDescCurrent.isNotEmpty()) {
            "${weather.weatherDescCurrent} $tempText"
        } else {
            tempText
        }
        updateLocationData(
            addressText,
            weatherText,
            weather.sunriseEpochMs,
            weather.sunsetEpochMs
        )
    }

    private fun applyEffectiveUnitSettings() {
        val speedUnit = unitSettings.effectiveSpeedUnit()
        speedometerWidget?.setUnit(speedUnit)
        settingsController.setSpeedUnit(speedUnit)
        settingsController.setUnitSystemMode(unitSettings.globalMode)
        locationWidget?.setTemperatureUnit(unitSettings.effectiveTemperatureUnit())
        renderLocationWeather()
    }

    fun applyFinanceSnapshot(snapshot: FinanceSnapshot, notify: Boolean = true) {
        financeWidget?.let {
            it.applySnapshot(snapshot)
            if (notify) notifyTransientContentChanged()
        }
    }

    fun applyFinanceError(message: String) {
        financeWidget?.let {
            it.applyError(message)
            notifyTransientContentChanged()
        }
    }

    fun applyNewsSnapshot(snapshot: NewsSnapshot) {
        val widget = newsWidget ?: return
        // A pending source switch owns the matching snapshot's persistent callback; until the
        // source is committed, applying it would only produce a redundant transient refresh.
        if (widget.getSourceSpec() != snapshot.sourceSpec || widget.getCountryCode() != snapshot.countryCode) {
            return
        }
        withTransientChangeBatch {
            widget.applySnapshot(snapshot)
            notifyTransientContentChanged()
        }
    }

    fun applyNotificationsSnapshot(snapshot: PhoneNotificationsSnapshot) {
        latestNotificationsSnapshot = snapshot
        withTransientChangeBatch {
            notificationsWidget?.setNotificationsSnapshot(snapshot)
            notifyTransientContentChanged()
        }
    }

    fun applyNewsError(message: String) {
        withTransientChangeBatch {
            newsWidget?.applyError(message)
            notifyTransientContentChanged()
        }
    }

    fun currentNewsCountryCode(): String? = newsWidget?.getCountryCode() ?: pendingNewsCountryCode

    fun currentNewsSourceSpec(): NewsSourceSpec? = newsWidget?.getSourceSpec()

    /** Update the existing singleton News widget from an assistant request. */
    fun setNewsSource(sourceSpec: NewsSourceSpec): CompletableFuture<NewsSourceUpdateResult> {
        val result = CompletableFuture<NewsSourceUpdateResult>()
        val action = Runnable {
            val widget = newsWidget
            if (widget == null) {
                result.complete(
                    NewsSourceUpdateResult(
                        applied = false,
                        message = "News widget is not visible"
                    )
                )
                return@Runnable
            }
            widget.requestSource(sourceSpec) { fetchResult ->
                fetchResult.fold(
                    onSuccess = {
                        result.complete(
                            NewsSourceUpdateResult(
                                applied = true,
                                message = "News source updated",
                                sourceSpec = sourceSpec
                            )
                        )
                    },
                    onFailure = { failure ->
                        result.complete(
                            NewsSourceUpdateResult(
                                applied = false,
                                message = failure.message ?: "News source could not be loaded"
                            )
                        )
                    }
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else if (!post(action)) {
            result.complete(
                NewsSourceUpdateResult(
                    applied = false,
                    message = "Glasses display is unavailable"
                )
            )
        }
        return result
    }

    fun currentFinanceVisibleConfigs(): List<FinanceDashboardTileConfig> =
        financeWidget?.getVisibleTileConfigs().orEmpty()


    /** Update an existing finance tile on the UI thread and keep the widget instance intact. */
    fun updateFinanceTile(request: FinanceTileUpdateRequest): CompletableFuture<FinanceTileUpdateResult> {
        val result = CompletableFuture<FinanceTileUpdateResult>()
        val action = Runnable {
            val widget = financeWidget
            if (widget == null) {
                result.complete(
                    FinanceTileUpdateResult(
                        applied = false,
                        message = "Finance widget is not visible"
                    )
                )
                return@Runnable
            }

            if (request.fullscreen == true && widget.isMinimized) {
                result.complete(
                    FinanceTileUpdateResult(
                        applied = false,
                        message = "Finance widget is minimized"
                    )
                )
                return@Runnable
            }

            val update = runPersistentMutation(
                action = {
                    runCatching {
                        widget.updateTileInstrument(
                            target = request.target,
                            instrument = request.instrument,
                            range = request.range,
                            chartType = request.chartType,
                            slot = request.slot
                        )
                    }.getOrElse { failure ->
                        Result.failure(failure)
                    }.also { result ->
                        if (result.isSuccess && request.fullscreen != null && request.fullscreen != widget.isFullscreen) {
                            widget.toggleFullscreen()
                        }
                    }
                },
                changed = { it.isSuccess }
            )
            update.fold(
                onSuccess = { config ->
                    val state = widget.semanticState(widgetStableId(widget))
                    result.complete(
                        FinanceTileUpdateResult(
                            applied = true,
                            message = "Updated ${request.instrument.displayName} on finance tile ${config.id}",
                            tileId = config.id,
                            instrument = request.instrument,
                            state = state
                        )
                    )
                },
                onFailure = { failure ->
                    result.complete(
                        FinanceTileUpdateResult(
                            applied = false,
                            message = failure.message ?: "Requested finance tile is not present"
                        )
                    )
                }
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else if (!post(action)) {
            result.complete(
                FinanceTileUpdateResult(
                    applied = false,
                    message = "Glasses display is unavailable"
                )
            )
        }
        return result
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
     *  2. Otherwise hover is "sticky": while the previously hovered widget still contains the
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
        formattingMenu?.cancelLongPress()
    }

    fun onCursorDown(x: Float, y: Float, pointerCount: Int = 1) {
        cursorPressed = true; currentPointerCount = pointerCount; isTwoFingerScrollMode = pointerCount >= 2
        interactionZOrderChanged = false
        interactionPersistentNotified = false
        cursorX = x; cursorY = y; lastCursorX = x; lastCursorY = y
        cursorStabilizer.reset()
        longPressStartX = x; longPressStartY = y; selectionStartX = x; accumulatedSelectionDx = 0f
        cancelLongPress()
        isTextSelectionActive = false; isLongPressSelectionMode = false; pendingTextSelection = false

        if (isTwoFingerScrollMode) return

        layoutNamePrompt?.let { prompt ->
            if (prompt.isVisible) {
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
        formattingMenu?.let { menu -> if (menu.isVisible && menu.shouldScheduleLongPress(x, y)) { pendingFormattingMenuDrag = true; scheduleLongPress(); return } }

        if (settingsController.handleSpeedometerDown(x, y)) return

        // The map POI settings panel owns its drag gestures while open, including when the
        // map is fullscreen. Route the press before normal widget/focus handling.
        if (mapWidget?.isPoiConfigVisible() == true && mapWidget?.onDown(cx, cy) == true) return
        
        // Widgets use content-space coordinates
        focusedWidget?.let { widget -> if (widget.isFocused() && widget.containsPoint(cx, cy)) { bringWidgetToFrontForInteraction(widget); if (widget.hitTest(cx, cy) == TextBoxWidget.HitArea.CONTENT) { pendingTextSelection = true; scheduleLongPress(); return } } }
        fullscreenTextWidget?.let { widget -> if (widget.isFocused() && widget.containsPoint(cx, cy)) { if (widget.hitTest(cx, cy) == TextBoxWidget.HitArea.CONTENT) { pendingTextSelection = true; scheduleLongPress(); return } } }
        
        if (isFinanceWidgetFullscreen) {
            financeWidget?.let { fw ->
                if (fw.containsPoint(cx, cy) && fw.canStartTileDrag(cx, cy)) {
                    pendingDragFinanceTile = true
                    scheduleLongPress()
                    return
                }
            }
        }

        if (hasFullscreenWidget()) {
            fullscreenBrowserWidget?.let { widget ->
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
            if (pendingFormattingMenuDrag) { formattingMenu?.let { if (it.onLongPressTriggered()) { } }; pendingFormattingMenuDrag = false; notifyTransientContentChanged(); return@Runnable }
            if (pendingTextSelection) { val widget = focusedWidget ?: fullscreenTextWidget; widget?.let { if (it.isFocused()) { val (cx, cy) = screenToContentCoordinates(longPressStartX, longPressStartY); it.setCursorFromScreenPosition(cx, cy); it.resetPixelAccumulator(); it.startSelection(); isLongPressSelectionMode = true; isTextSelectionActive = false } }; pendingTextSelection = false; notifyTransientContentChanged(); return@Runnable }
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
        suppressBrowserContentTapUntilMs = android.os.SystemClock.uptimeMillis() + BROWSER_TOUCH_TAP_SUPPRESSION_MS
    }

    private fun shouldSuppressBrowserContentTap(widget: BrowserWidget): Boolean {
        if (suppressBrowserContentTapWidget != widget) return false
        if (android.os.SystemClock.uptimeMillis() <= suppressBrowserContentTapUntilMs) return true
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

        // If DashboardSettings is open, end its interaction (e.g., stop slider drag).
        settingsController.handleDashboardUp()

        mapWidget?.let {
            if (it.isPoiConfigVisible()) it.onUp()
        }

        // If the hover-controls layout editor is open, finalize any drag.
        settingsController.handleHoverControlsLayoutEditorUp()

        // Shortcuts settings menu.
        settingsController.handleShortcutsSettingsMenuUp()

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
        formattingMenu?.let { if (it.isDragging()) it.onDragEnd() }
        if (isLongPressSelectionMode) { (focusedWidget ?: fullscreenTextWidget)?.endSelection(); isLongPressSelectionMode = false; isTextSelectionActive = false }
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
        isTwoFingerScrollMode = false; currentPointerCount = 1
    }
    
    fun onPointerCountChanged(pointerCount: Int) {
        val wasScrollMode = isTwoFingerScrollMode; currentPointerCount = pointerCount; isTwoFingerScrollMode = pointerCount >= 2
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
            closeConfirmationPopup?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); formattingMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); textEditMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); contextMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            layoutDeleteConfirmationPopup?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            layoutNamePrompt?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            if (hasFullscreenWidget()) { fullscreenTextWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); fullscreenBrowserWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); fullscreenImageWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); fullscreenVideoWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isStatusBarFullscreen) statusBarWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isLocationWidgetFullscreen) locationWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isCalendarWidgetFullscreen) calendarWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isFinanceWidgetFullscreen) financeWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isNewsWidgetFullscreen) newsWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isNotificationsWidgetFullscreen) notificationsWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isSpeedometerWidgetFullscreen) speedometerWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isSubtitleWidgetFullscreen) subtitleWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isMirrorWidgetFullscreen) screenMirrorWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            else { registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) } }
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
        closeConfirmationPopup?.updateHover(x, y); formattingMenu?.updateHover(x, y); textEditMenu?.updateHover(x, y); contextMenu?.updateHover(x, y)
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
        if (hasFullscreenWidget()) {
            // Fullscreen routing uses the dedicated fullscreen references, so no windowed
            // widget owns hover here.
            hoveredWidget = null
            fullscreenTextWidget?.updateHover(if (fullscreenTextWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            fullscreenBrowserWidget?.updateHover(if (fullscreenBrowserWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            fullscreenImageWidget?.updateHover(if (fullscreenImageWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            fullscreenVideoWidget?.updateHover(if (fullscreenVideoWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            fullscreenPdfWidget?.updateHover(if (fullscreenPdfWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            fullscreenFileBrowserWidget?.updateHover(if (fullscreenFileBrowserWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isStatusBarFullscreen) statusBarWidget?.updateHover(if (statusBarWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isLocationWidgetFullscreen) locationWidget?.updateHover(if (locationWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isMapWidgetFullscreen) mapWidget?.updateHover(if (mapWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isCalendarWidgetFullscreen) calendarWidget?.updateHover(if (calendarWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isFinanceWidgetFullscreen) financeWidget?.updateHover(if (financeWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isNewsWidgetFullscreen) newsWidget?.updateHover(if (newsWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isNotificationsWidgetFullscreen) notificationsWidget?.updateHover(if (notificationsWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isSpeedometerWidgetFullscreen) speedometerWidget?.updateHover(if (speedometerWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isSubtitleWidgetFullscreen) subtitleWidget?.updateHover(if (subtitleWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isMirrorWidgetFullscreen) screenMirrorWidget?.updateHover(if (screenMirrorWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
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
        if (hasFullscreenWidget()) {
            fullscreenTextWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fullscreenBrowserWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fullscreenImageWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fullscreenVideoWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fullscreenPdfWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fullscreenFileBrowserWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        }
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
        if (isLongPressSelectionMode) { 
            val widget = focusedWidget ?: fullscreenTextWidget
            if (widget?.isFocused() == true) { 
                val (cx, cy) = toContentSpace(x, y)
                widget.setCursorFromScreenPosition(cx, cy)
                widget.updateSelectionToCursor()
                isTextSelectionActive = true
                notifyTransientContentChanged()
            } 
        }
        // Menus use screen-space (head-locked)
        formattingMenu?.let { menu -> if (menu.isDragging()) { menu.onDrag(x, y); notifyTransientContentChanged(); return }; if (menu.checkLongPressCancellation(x, y, LONG_PRESS_MAX_MOVEMENT)) { } }
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

    fun getScrollbarHoveredWidget(x: Float, y: Float): TextBoxWidget? {
        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)
        
        fullscreenTextWidget?.let { if (it.isOverScrollbar(cx, cy)) return it }
        for (widget in widgets.reversed()) { if (widget.isOverScrollbar(cx, cy)) return widget }
        return null
    }

    fun isScrollbarDragActive(): Boolean = isScrollbarDragActive
    fun getScrollbarDragWidget(): TextBoxWidget? = scrollbarDragWidget

    fun onScroll(dy: Float) {
        for (widget in widgets) { if (widget.state == TextBoxWidget.State.HOVER_CONTENT || widget.state == TextBoxWidget.State.EDITING) { widget.onScroll(dy); notifyTransientContentChanged(); break } }
        fullscreenTextWidget?.let { widget -> if (widget.state == TextBoxWidget.State.HOVER_CONTENT || widget.state == TextBoxWidget.State.EDITING) { widget.onScroll(dy); notifyTransientContentChanged() } }
    }

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
        if (isMapWidgetFullscreen) {
            mapWidget?.let {
                if ((it.isPoiConfigVisible() || it.navigationState.choices.isNotEmpty()) && it.containsPoint(cx, cy)) {
                    it.onScroll(dy)
                    notifyTransientContentChanged()
                    return true
                }
            }
        }
        fullscreenTextWidget?.let {
            if (it.containsPoint(cx, cy)) {
                it.onScroll(dy)
                notifyTransientContentChanged()
                return true
            }
        }
        fullscreenBrowserWidget?.let {
            val hit = it.hitTest(cx, cy)
            if (hit == BrowserWidget.HitArea.CONTENT || hit == BrowserWidget.HitArea.NAV_URL) {
                it.onScroll(dy)
                notifyTransientContentChanged()
                return true
            }
        }
        fullscreenPdfWidget?.let {
            if (it.containsPoint(cx, cy)) {
                withTransientChangeBatch {
                    it.onScroll(dy)
                    notifyTransientContentChanged()
                }
                return true
            }
        }
        fullscreenFileBrowserWidget?.let {
            if (it.containsPoint(cx, cy)) {
                withTransientChangeBatch {
                    it.onScroll(dy)
                    notifyTransientContentChanged()
                }
                return true
            }
        }
        if (isCalendarWidgetFullscreen) {
            calendarWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    withTransientChangeBatch {
                        it.onScroll(dy)
                        notifyTransientContentChanged()
                    }
                    return true
                }
            }
        }
        if (isFinanceWidgetFullscreen) {
            financeWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    val beforeIndex = it.getNavigationIndex()
                    if (it.onScrollAt(cx, cy, dy)) {
                        if (it.getNavigationIndex() != beforeIndex) notifyPersistentContentChanged()
                        else notifyTransientContentChanged()
                    }
                    return true
                }
            }
        }
        if (isNewsWidgetFullscreen) {
            newsWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    val beforeIndex = it.getSelectedIndex()
                    runClassifiedInput(
                        action = { it.onScroll(dy); true },
                        changed = { it.getSelectedIndex() != beforeIndex }
                    )
                    return true
                }
            }
        }
        if (isNotificationsWidgetFullscreen) {
            notificationsWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    val beforeIndex = it.getScrollIndex()
                    runClassifiedInput(
                        action = { it.onScroll(dy); true },
                        changed = { it.getScrollIndex() != beforeIndex }
                    )
                    return true
                }
            }
        }
        return false
    }

    // ==================== Tap Handling ====================

    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        return fullscreenVideoWidget?.handleKeyEvent(keyCode, event) == true
    }

    fun onTap(x: Float, y: Float): Boolean {
        // Menus use screen-space coordinates (head-locked)
        if (pageAgentApprovalOverlay.isVisible()) {
            return pageAgentApprovalOverlay.onTap(x, y)
        }
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
        if (formattingMenu?.isVisible == true) return formattingMenu?.onTap(x, y) ?: false
        if (textEditMenu?.isVisible == true) {
            return dispatchTextEditMenuAction { textEditMenu?.onTap(x, y) ?: false }
        }
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

        if (hasFullscreenWidget()) {
            fullscreenTextWidget?.let { if (it.containsPoint(cx, cy)) return handleTextWidgetTap(it, cx, cy); return false }
            fullscreenBrowserWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    if (it.hitTest(cx, cy) == BrowserWidget.HitArea.CONTENT && shouldSuppressBrowserContentTap(it)) {
                        return true
                    }
                    return handleBrowserWidgetTap(it, cx, cy)
                }
                return false
            }
            fullscreenImageWidget?.let { if (it.containsPoint(cx, cy)) return handleImageWidgetTap(it, cx, cy); return false }
            fullscreenVideoWidget?.let { if (it.containsPoint(cx, cy)) return handleVideoWidgetTap(it, cx, cy); return false }
            fullscreenPdfWidget?.let { if (it.containsPoint(cx, cy)) return handlePdfWidgetTap(it, cx, cy); return false }
            fullscreenFileBrowserWidget?.let { if (it.containsPoint(cx, cy)) return handleFileBrowserWidgetTap(it, cx, cy); return false }
            if (isStatusBarFullscreen) { statusBarWidget?.let { if (it.containsPoint(cx, cy)) return handleStatusBarTap(it, cx, cy) }; return false }
            if (isLocationWidgetFullscreen) { locationWidget?.let { if (it.containsPoint(cx, cy)) return handleLocationWidgetTap(it, cx, cy) }; return false }
            if (isMapWidgetFullscreen) { mapWidget?.let { if (it.containsPoint(cx, cy)) return handleMapWidgetTap(it, cx, cy) }; return false }
            if (isCalendarWidgetFullscreen) { calendarWidget?.let { if (it.containsPoint(cx, cy)) return handleCalendarWidgetTap(it, cx, cy) }; return false }
            if (isFinanceWidgetFullscreen) { financeWidget?.let { if (it.containsPoint(cx, cy)) return handleFinanceWidgetTap(it, cx, cy) }; return false }
            if (isNewsWidgetFullscreen) { newsWidget?.let { if (it.containsPoint(cx, cy)) return handleNewsWidgetTap(it, cx, cy) }; return false }
            if (isNotificationsWidgetFullscreen) { notificationsWidget?.let { if (it.containsPoint(cx, cy)) return handleNotificationsWidgetTap(it, cx, cy) }; return false }
            if (isSpeedometerWidgetFullscreen) { speedometerWidget?.let { if (it.containsPoint(cx, cy)) return handleSpeedometerWidgetTap(it, cx, cy) }; return false }
            if (isSubtitleWidgetFullscreen) { subtitleWidget?.let { if (it.containsPoint(cx, cy)) return handleSubtitleWidgetTap(it, cx, cy) }; return false }
            if (isMirrorWidgetFullscreen) { screenMirrorWidget?.let { if (it.containsPoint(cx, cy)) return handleMirrorWidgetTap(it, cx, cy) }; return false }
        }

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
     * per-widget handlers add extra behaviour; defaults to swallowing the tap (returns `true`).
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
                val before = textPersistentSignature(widget)
                runClassifiedInput(
                    action = { widget.getKeyboard()?.onTap(x, y) == true },
                    changed = { textPersistentSignature(widget) != before }
                )
                true
            }
            else -> handleBaseTap(widget, x, y) { hit ->
                if (hit == BaseWidget.BaseHitArea.CONTENT) {
                    withTransientChangeBatch {
                        widget.setFocused(true); setFocusedWidget(widget)
                        widget.setCursorFromScreenPosition(x, y); widget.clearSelection()
                        notifyTransientContentChanged()
                    }
                }
                true
            }
        }

    private fun handleImageWidgetTap(widget: ImageWidget, x: Float, y: Float): Boolean =
        handleBaseTap(widget, x, y)

    private fun handleVideoWidgetTap(widget: VideoWidget, x: Float, y: Float): Boolean =
        handleBaseTap(widget, x, y) { hit ->
            if (hit == BaseWidget.BaseHitArea.CONTENT) widget.handleContentTap(x, y) else true
        }

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

    private fun textPersistentSignature(widget: TextBoxWidget): List<Any?> = listOf(
        widget.text,
        widget.getHtmlContent(),
        widget.textFontSize,
        widget.isTextWrap,
        widget.getColumnCount()
    )

    private fun handlePdfWidgetTap(widget: PdfWidget, x: Float, y: Float): Boolean =
        handleBaseTap(widget, x, y) { hit ->
            if (hit == BaseWidget.BaseHitArea.CONTENT) {
                val before = widget.currentPage to widget.zoom
                runClassifiedInput(
                    action = { widget.handleContentTap(x, y) },
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

        if (mapWidget?.isAddressEntryVisible() == true || mapWidget?.navigationState?.choices?.isNotEmpty() == true) {
            mapWidget?.let { map ->
                val before = mapPersistentSignature(map)
                if (runClassifiedInput({ map.onDoubleTap(cx, cy) }, { mapPersistentSignature(map) != before })) {
                    return true
                }
            }
        }

        fullscreenVideoWidget?.let { widget ->
            if (widget.containsPoint(cx, cy)) {
                withTransientChangeBatch {
                    widget.handleContentDoubleTap(cx, cy)
                    notifyTransientContentChanged()
                }
                return true
            }
            return false
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

        if (!hasFullscreenWidget() && isAppContextMenuBorderDoubleTap(cx, cy)) {
            showAppContextMenu(x, y, cx, cy)
            return true
        }

        if (isStatusBarFullscreen) {
            statusBarWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    withTransientChangeBatch { notifyTransientContentChanged() }
                    return true
                }
            }
            return false
        }

        if (isLocationWidgetFullscreen) {
            locationWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    withTransientChangeBatch { notifyTransientContentChanged() }
                    return true
                }
            }
            return false
        }

        if (isMapWidgetFullscreen) {
            mapWidget?.let {
                val before = mapPersistentSignature(it)
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, { mapPersistentSignature(it) != before })) {
                    return true
                }
            }
            return false
        }

        if (isCalendarWidgetFullscreen) {
            calendarWidget?.let {
                val before = it.isFullscreen to it.getFontScale()
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, { (it.isFullscreen to it.getFontScale()) != before })) {
                    return true
                }
            }
            return false
        }

        if (isFinanceWidgetFullscreen) {
            financeWidget?.let {
                val before = listOf(it.isFullscreen, it.getTileConfigs(), it.getNavigationIndex(), it.getTilingSpan(), it.getSelectedTileConfig())
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, {
                        listOf(it.isFullscreen, it.getTileConfigs(), it.getNavigationIndex(), it.getTilingSpan(), it.getSelectedTileConfig()) != before
                    })) {
                    return true
                }
            }
            return false
        }

        if (isNewsWidgetFullscreen) {
            newsWidget?.let {
                val before = listOf(it.isFullscreen, it.getSourceSpec(), it.getSelectedIndex(), it.getFontScale())
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, {
                        listOf(it.isFullscreen, it.getSourceSpec(), it.getSelectedIndex(), it.getFontScale()) != before
                    })) {
                    return true
                }
            }
            return false
        }

        if (isNotificationsWidgetFullscreen) {
            notificationsWidget?.let {
                val before = listOf(it.isFullscreen, it.getWidgetFontScale(), it.getToastFontScale(), it.getScrollIndex())
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, {
                        listOf(it.isFullscreen, it.getWidgetFontScale(), it.getToastFontScale(), it.getScrollIndex()) != before
                    })) {
                    return true
                }
            }
            return false
        }

        if (isSubtitleWidgetFullscreen) {
            subtitleWidget?.let {
                val before = it.isFullscreen to it.options
                if (it.containsPoint(cx, cy) && runClassifiedInput({ it.onDoubleTap(cx, cy) }, { (it.isFullscreen to it.options) != before })) {
                    return true
                }
            }
            return false
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

        if (isSpeedometerWidgetFullscreen) {
            speedometerWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    settingsController.showSpeedometerSettingsMenu()
                    return true
                }
            }
            return false
        }

        focusedWidget?.let { if (it.isFocused() && it.containsPoint(cx, cy)) { showTextEditMenu(phoneClipboard); return true } }
        fullscreenTextWidget?.let { if (it.isFocused() && it.containsPoint(cx, cy)) { showTextEditMenu(phoneClipboard); return true } }
        focusedBrowserWidget?.let {
            if (it.isAddressHit(cx, cy)) {
                showBrowserAddressEditMenu(it, phoneClipboard)
                return true
            }
        }
        fullscreenBrowserWidget?.let {
            if (it.isAddressHit(cx, cy)) {
                showBrowserAddressEditMenu(it, phoneClipboard)
                return true
            }
        }
        if (!hasFullscreenWidget()) {
            for (widget in browserWidgets.reversed()) {
                if (widget.isAddressHit(cx, cy)) {
                    showBrowserAddressEditMenu(widget, phoneClipboard)
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
        val fallbackTarget = interactionTargetAt(cx, cy)
        if (fallbackTarget is SpeedometerWidget && fallbackTarget.isContentVisible()) {
            settingsController.showSpeedometerSettingsMenu()
            return true
        }
        showAppContextMenu(x, y, cx, cy)
        return true
    }

    fun onContextMenuNavigationTap(x: Float, y: Float): Boolean {
        return dispatchContextMenuAction { contextMenu?.onNavigationTap(x, y) ?: false }
    }

    /** Lets a menu action own its classified notification while keeping navigation visual-only. */
    private fun dispatchContextMenuAction(action: () -> Boolean): Boolean {
        val before = contentNotificationGeneration
        contextMenuDispatchDepth++
        val handled = try {
            action()
        } finally {
            contextMenuDispatchDepth--
        }
        if (handled && contentNotificationGeneration == before) notifyTransientContentChanged()
        return handled
    }

    /** Coalesces a text-edit command with the menu dismissal it causes. */
    private fun dispatchTextEditMenuAction(action: () -> Boolean): Boolean {
        val before = contentNotificationGeneration
        textEditMenuDispatchDepth++
        val handled = try {
            action()
        } finally {
            textEditMenuDispatchDepth--
        }
        if (handled && contentNotificationGeneration == before) notifyTransientContentChanged()
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

    /** setter for google authentication*/
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
        closedTemplateState<DashboardPersistence.StatusBarState>(WidgetTypeId.STATUS)?.let {
            return applyStatusBarState(it)
        }
        val statusWidth = 220f
        val statusHeight = 110f
        return StatusBarWidget(
            context,
            (width - statusWidth) / 2f,
            height - statusHeight - height * 0.15f,
            statusWidth,
            statusHeight
        ).apply { onStateChanged = { notifyTransientContentChanged() } }
    }

    private fun createLocationPresence(): LocationWidget {
        closedTemplateState<DashboardPersistence.LocationWidgetState>(WidgetTypeId.LOCATION)?.let {
            return applyLocationState(it)
        }
        val locWidth = 300f
        val locHeight = 120f
        return LocationWidget(context, 20f, height - locHeight - 20f, locWidth, locHeight).apply {
            onTemperatureUnitChanged = { unitSettings.toggleTemperatureOverride() }
            setTemperatureUnit(unitSettings.effectiveTemperatureUnit())
            if (pendingLocationAddress != null || pendingLocationWeather != null) {
                setLocationData(pendingLocationAddress ?: "Loading...", pendingLocationWeather ?: "Checking weather...")
            }
            setSunTimes(pendingLocationSunriseEpochMs, pendingLocationSunsetEpochMs)
        }
    }

    private fun createMapPresence(): MapWidget {
        closedTemplateState<DashboardPersistence.MapWidgetState>(WidgetTypeId.MAP)?.let { return applyMapState(it) }
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
        closedTemplateState<DashboardPersistence.CalendarWidgetState>(WidgetTypeId.CALENDAR)?.let {
            return applyCalendarState(it)
        }
        val startX = (width - CalendarWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
        val startY = (height - CalendarWidget.DEFAULT_HEIGHT - 160f).coerceAtLeast(20f)
        return CalendarWidget(
            startX,
            startY,
            CalendarWidget.DEFAULT_WIDTH,
            CalendarWidget.DEFAULT_HEIGHT
        ).apply {
            onStateChanged = { notifyTransientContentChanged() }
            setSnapshot(pendingCalendarSnapshot)
        }
    }

    private fun createSpeedometerPresence(): SpeedometerWidget {
        closedTemplateState<DashboardPersistence.SpeedometerWidgetState>(WidgetTypeId.SPEEDOMETER)?.let {
            return applySpeedometerState(it)
        }
        val speedWidth = 220f
        val speedHeight = 110f
        val startX = (width - speedWidth - 20f).coerceAtLeast(20f)
        val startY = (height - speedHeight - 20f).coerceAtLeast(20f)
        return createSpeedometerWidget(startX, startY, speedWidth, speedHeight)
    }

    private fun createFinancePresence(): FinanceWidget {
        closedTemplateState<DashboardPersistence.FinanceWidgetState>(WidgetTypeId.FINANCE)?.let {
            return applyFinanceState(it)
        }
        return FinanceWidget(20f, 20f, FinanceWidget.DEFAULT_WIDTH, FinanceWidget.DEFAULT_HEIGHT).apply {
            listener = object : FinanceWidgetListener {
                override fun onStateChanged() = notifyTransientContentChanged()
                override fun onPersistentStateChanged() = notifyPersistentContentChanged()
                override fun onDataRequested(
                    visibleConfigs: List<FinanceDashboardTileConfig>,
                    refreshTileIds: List<String>, force: Boolean, reason: String
                ) {
                    dataProvider?.requestFinanceData(visibleConfigs, refreshTileIds, force, reason)
                }
            }
            pendingFinanceCountryCode?.let { setCountryCode(it) }
            requestData(force = true, reason = "widget_toggled_on")
            dataProvider?.onFinanceVisibilityChanged(true)
        }
    }

    private fun createNewsPresence(): NewsWidget {
        closedTemplateState<DashboardPersistence.NewsWidgetState>(WidgetTypeId.NEWS)?.let {
            return applyNewsState(it)
        }
        val startX = (width - NewsWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
        return NewsWidget(startX, 20f, NewsWidget.DEFAULT_WIDTH, NewsWidget.DEFAULT_HEIGHT).apply {
            listener = object : NewsWidgetListener {
                override fun onStateChanged() = notifyTransientContentChanged()
                override fun onPersistentStateChanged() = notifyPersistentContentChanged()
                override fun onDataRequested(countryCode: String, force: Boolean, reason: String) {
                    dataProvider?.requestNewsData(countryCode, force, reason)
                }
                override fun onSourceDataRequested(sourceSpec: NewsSourceSpec, countryCode: String, force: Boolean, reason: String, completion: (Result<NewsSnapshot>) -> Unit) {
                    dataProvider?.requestNewsSourceData(sourceSpec, countryCode, force, reason, completion)
                        ?: completion(Result.failure(IllegalStateException("News data service is unavailable")))
                }
                override fun onSourceInputFocusChanged(focused: Boolean) { notifyFocusChanged(focused) }
            }
            pendingNewsCountryCode?.let { setCountryCode(it) }
            requestData(force = true, reason = "widget_toggled_on")
        }
    }

    private fun createNotificationsPresence(): NotificationsWidget {
        closedTemplateState<DashboardPersistence.NotificationsWidgetState>(WidgetTypeId.NOTIFICATIONS)?.let {
            return applyNotificationsState(it)
        }
        val startX = (width - NotificationsWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
        return NotificationsWidget(
            context, startX, 20f, NotificationsWidget.DEFAULT_WIDTH,
            NotificationsWidget.DEFAULT_HEIGHT, height.toFloat()
        ).apply {
            onStateChanged = { notifyTransientContentChanged() }
            setNotificationsSnapshot(latestNotificationsSnapshot)
        }
    }

    private fun createSubtitlePresence(): SubtitleWidget {
        closedTemplateState<DashboardPersistence.SubtitleWidgetState>(WidgetTypeId.SUBTITLE)?.let {
            return applySubtitleState(it).also { widget -> widget.setCaptureEnabled(true) }
        }
        val subtitleWidth = SubtitleWidget.DEFAULT_WIDTH.coerceAtMost(width * 0.82f)
        val subtitleHeight = SubtitleWidget.DEFAULT_HEIGHT
        val startX = ((width - subtitleWidth) / 2f).coerceAtLeast(20f)
        val startY = (height - subtitleHeight - 72f).coerceAtLeast(20f)
        return createSubtitleWidget(startX, startY, subtitleWidth, subtitleHeight, autoStart = true)
    }

    private fun createMirrorPresence(): ScreenMirrorWidget {
        closedTemplateState<DashboardPersistence.MirrorWidgetState>(WidgetTypeId.MIRROR)?.let {
            return applyMirrorState(it)
        }
        return ScreenMirrorWidget(context, 20f, 20f, 180f, 320f).apply {
            configureScreenMirrorWidget(this)
        }.also { notifyMediaBrightnessCapChanged() }
    }

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
            notifyMediaBrightnessCapChanged()
            notifyBrowserMinimumBrightnessChanged()
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
                notifyFocusChanged(
                    focused = focused,
                    persistent = focused && map.zOrder != beforeZOrder
                )
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
                    notifyMediaBrightnessCapChanged()
                    notifyBrowserMinimumBrightnessChanged()
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
            ?: fullscreenBrowserWidget
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
            notifyMediaBrightnessCapChanged()
            notifyBrowserMinimumBrightnessChanged()
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
            notifyBrowserMinimumBrightnessChanged()
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
        val widget = PdfWidget(context, uri, displayName, createX, createY).apply {
            onStateChanged = { notifyTransientContentChanged() }
        }
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

        val selectionHandler = onFileSelected
        val widget = FileBrowserWidget(
            context,
            createX,
            createY,
            browserWidth,
            browserHeight,
            filter
        ).apply {
            listener = object : FileBrowserWidgetListener {
                override fun onStateChanged(state: FileBrowserWidget.State) = notifyTransientContentChanged()
                override fun onFileSelected(entry: FileBrowserWidget.FileEntry) {
                    selectionHandler?.invoke(entry)
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

    private fun buildTextBox(x: Float, y: Float, boxWidth: Float, boxHeight: Float): TextBoxWidget =
        TextBoxWidget(context, x, y, boxWidth, boxHeight).apply {
            listener = object : TextBoxWidgetListener {
                override fun onFocusChanged(focused: Boolean) {
                    if (focused) setFocusedWidget(this@apply)
                    else if (focusedWidget == this@apply) setFocusedWidget(null)
                }
                override fun onStateChanged(state: TextBoxWidget.State) = notifyTransientContentChanged()
            }
        }

    fun createTextBox(x: Float, y: Float) {
        if (openClosedTextTemplate() != null) {
            return
        }
        val boxWidth = width * DEFAULT_TEXTBOX_WIDTH_PERCENT; val boxHeight = height * DEFAULT_TEXTBOX_HEIGHT_PERCENT
        val adjustedX = x.coerceIn(0f, width - boxWidth); val adjustedY = y.coerceIn(0f, height - boxHeight)
        val widget = buildTextBox(adjustedX, adjustedY, boxWidth, boxHeight)
        runPersistentMutation {
            lifecycleController.open(textLifecycle) { widget }
            widget.setFocused(true)
            setFocusedWidget(widget)
        }
    }

    private fun setFocusedWidget(widget: BaseWidget?) {
        if (suppressNestedFocusCallbacks) return
        if ((widget is TextBoxWidget && focusedWidget === widget) ||
            (widget is BrowserWidget && focusedBrowserWidget === widget)
        ) return
        suppressNestedFocusCallbacks = true
        try {
            when (widget) {
                is TextBoxWidget -> { focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = null; focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = widget }
                is BrowserWidget -> { focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = null; focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = widget }
                null -> { focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = null; focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = null }
            }
            formattingMenu?.dismiss()
        } finally {
            suppressNestedFocusCallbacks = false
        }
        notifyFocusChanged(widget != null)
    }

    fun unfocusAll() {mapWidget?.takeIf { it.isAddressEntryFocused() }?.closeAddressEntry(); setFocusedWidget(null)}

    private fun updateMinimizedWidgetPositions() {
        if (width <= 0 || height <= 0) return
        val minimizedWidgets = registry.getMinimizedWidgets(); val squareSize = 64f; val maxPerRow = 7
        minimizedWidgets.forEachIndexed { index, widget -> val row = index / maxPerRow; val col = index % maxPerRow; widget.setPosition(col * squareSize, height - ((row + 1) * squareSize)) }
    }

    fun onKeyPress(key: String, userInitiated: Boolean = false) {
        if (userInitiated) userInputActivity?.onUserInput(UserInputKind.KEYBOARD)
        newsWidget?.let { news ->
            val beforeSource = news.getSourceSpec()
            var persistedChange = false
            val handled = runPersistentMutation(
                action = {
                    news.handleSourcePanelKeyPress(key).also {
                        persistedChange = news.getSourceSpec() != beforeSource
                    }
                },
                changed = { it && persistedChange }
            )
            if (handled) {
                if (!persistedChange) notifyTransientContentChanged()
                return
            }
        }
        layoutOverlayNotificationEmitted = false
        if (layoutNamePrompt?.onKeyPress(key) == true) {
            if (!layoutOverlayNotificationEmitted) notifyTransientContentChanged()
            layoutOverlayNotificationEmitted = false
            return
        }
        layoutOverlayNotificationEmitted = false
        if (mapWidget?.onAddressKeyPress(key) == true) {
            notifyTransientContentChanged()
            return
        }
        focusedWidget?.let { widget ->
            val before = textPersistentSignature(widget)
            runClassifiedInput(
                action = { widget.onKeyPress(key); true },
                changed = { textPersistentSignature(widget) != before }
            )
            return
        }
        focusedBrowserWidget?.let { browser ->
            if (browser.isEditingAddress() && (key == "ENTER" || key == "\n")) {
                runPersistentMutation { browser.onKeyPress(key) }
            } else {
                val before = browserPersistentSignature(browser)
                runClassifiedInput(
                    action = { browser.onKeyPress(key); true },
                    changed = { browserPersistentSignature(browser) != before }
                )
            }
        }
    }

    // ==================== Text Edit Menu ====================

    fun showTextEditMenu(clipboardContent: String?) {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.isFocused()) return
        textEditMenuTarget = TextEditMenuTarget.TEXT_WIDGET
        if (clipboardContent != null) phoneClipboard = clipboardContent
        // Request fresh clipboard from phone (will arrive async and update the menu)
        host?.requestClipboard()
        textEditMenu?.show(widget.x + widget.widgetWidth / 2 - 90f, widget.y - 60f, glassesClipboard ?: phoneClipboard, widget.hasSelection())
        notifyTransientContentChanged()
    }

    private fun showBrowserAddressEditMenu(widget: BrowserWidget, clipboardContent: String?) {
        textEditMenuTarget = TextEditMenuTarget.BROWSER_ADDRESS
        if (clipboardContent != null) phoneClipboard = clipboardContent
        if (!widget.isEditingAddress()) widget.beginAddressEditing(selectAll = true)
        setFocusedWidget(widget)
        ensureDrawnAboveNativeOverlays()
        host?.requestClipboard()
        val menuX = widget.x + widget.widgetWidth / 2 - 90f
        val menuY = (widget.y + 8f).coerceAtLeast(0f)
        textEditMenu?.show(
            menuX,
            menuY,
            glassesClipboard ?: phoneClipboard,
            widget.addressHasSelection(),
            includeFormatting = false
        )
        notifyTransientContentChanged()
    }
    
    /**
     * Update the visible text edit menu with new clipboard content.
     * Called when clipboard is received from phone while menu is visible.
     */
    fun updateTextEditMenuClipboard() {
        val widget = focusedWidget ?: fullscreenTextWidget
        val browser = focusedBrowserWidget ?: fullscreenBrowserWidget
        if (textEditMenu?.isVisible == true && (widget?.isFocused() == true || browser?.isEditingAddress() == true)) {
            textEditMenu?.updateClipboard(glassesClipboard ?: phoneClipboard)
            notifyTransientContentChanged()
        }
    }

    private fun handleCut() {
        if (textEditMenuTarget == TextEditMenuTarget.BROWSER_ADDRESS) {
            val browser = focusedBrowserWidget ?: fullscreenBrowserWidget ?: return
            glassesClipboard = browser.cutSelectedAddressText()
            notifyTransientContentChanged()
            return
        }
        val widget = focusedWidget ?: fullscreenTextWidget ?: return
        if (!widget.hasSelection()) return
        runPersistentMutation {
            glassesClipboard = widget.getSelectedText()
            widget.deleteSelection()
        }
    }

    private fun handleCopy() {
        if (textEditMenuTarget == TextEditMenuTarget.BROWSER_ADDRESS) {
            val browser = focusedBrowserWidget ?: fullscreenBrowserWidget ?: return
            glassesClipboard = browser.copySelectedAddressText()
            notifyTransientContentChanged()
            return
        }
        val widget = focusedWidget ?: fullscreenTextWidget ?: return
        if (!widget.hasSelection()) return
        glassesClipboard = widget.getSelectedText()
        notifyTransientContentChanged()
    }

    private fun handlePaste(content: String) {
        if (textEditMenuTarget == TextEditMenuTarget.BROWSER_ADDRESS) {
            (focusedBrowserWidget ?: fullscreenBrowserWidget)?.pasteIntoAddress(content)
            notifyTransientContentChanged()
            return
        }
        runPersistentMutation { (focusedWidget ?: fullscreenTextWidget)?.pasteText(content) }
    }

    private fun handleSelectAll() {
        if (textEditMenuTarget == TextEditMenuTarget.BROWSER_ADDRESS) {
            (focusedBrowserWidget ?: fullscreenBrowserWidget)?.selectAllAddressText()
            notifyTransientContentChanged()
            return
        }
        (focusedWidget ?: fullscreenTextWidget)?.let {
            it.selectAll()
            notifyTransientContentChanged()
        }
    }

    private fun showFormattingMenu() {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.isFocused()) return
        textEditMenu?.dismiss()
        formattingMenu?.show(widget.x + widget.widgetWidth / 2, widget.y - 20f, widget.textFontSize, widget.isTextWrap, widget.getColumnCount())
        formattingMenu?.apply { isBold = widget.isBoldActive(); isItalic = widget.isItalicActive(); isUnderline = widget.isUnderlineActive() }
        notifyTransientContentChanged()
    }

    private fun ensureDrawnAboveNativeOverlays() {
        val rootView = parent as? ViewGroup ?: return
        if (rootView.indexOfChild(this) != rootView.childCount - 1) {
            rootView.bringChildToFront(this)
        }
        if (elevation < 10f) elevation = 10f
    }

    private fun handleFontSizeChanged(size: Float) {
        runPersistentMutation {
        (focusedWidget ?: fullscreenTextWidget)?.setFontSize(size)
        }
    }

    private fun handleTextWrapToggled(wrap: Boolean) {
        runPersistentMutation {
        (focusedWidget ?: fullscreenTextWidget)?.setTextWrap(wrap)
        }
    }

    private fun handleBoldToggled() = runPersistentMutation {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return@runPersistentMutation
        widget.toggleBold()
        formattingMenu?.isBold = widget.isBoldActive()
    }

    private fun handleItalicToggled() = runPersistentMutation {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return@runPersistentMutation
        widget.toggleItalic()
        formattingMenu?.isItalic = widget.isItalicActive()
    }

    private fun handleUnderlineToggled() = runPersistentMutation {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return@runPersistentMutation
        widget.toggleUnderline()
        formattingMenu?.isUnderline = widget.isUnderlineActive()
    }

    private fun handleBulletListToggled() {runPersistentMutation {(focusedWidget ?: fullscreenTextWidget)?.toggleBulletList()}}
    private fun handleNumberedListToggled() {runPersistentMutation {(focusedWidget ?: fullscreenTextWidget)?.toggleNumberedList()}}

    // ==================== Public Accessors ====================
    fun setPhoneClipboard(content: String?)                                                                     {phoneClipboard = content ; updateTextEditMenuClipboard()} // Update visible text edit menu with new clipboard content
    fun hasFocusedWidget():                                                                                     Boolean = focusedWidget != null || focusedBrowserWidget != null || mapWidget?.isAddressEntryFocused() == true
    fun isMenuVisible():                                                                                        Boolean = contextMenu?.isVisible == true || textEditMenu?.isVisible == true || formattingMenu?.isVisible == true || layoutNamePrompt?.isVisible == true
    fun isClosePopupVisible():                                                                                  Boolean = closeConfirmationPopup?.isVisible == true || layoutDeleteConfirmationPopup?.isVisible == true
    fun isAnyPopupVisible():                                                                                    Boolean = isMenuVisible() || isClosePopupVisible() || pageAgentApprovalOverlay.isVisible()
    fun showPageAgentApproval(runId: String, request: PageAgentConfirmationRequest, respond: (Boolean) -> Unit) {pageAgentApprovalOverlay.show(runId, request, respond); ensureDrawnAboveNativeOverlays()}
    fun dismissPageAgentApproval(runId: String)                                                                 {pageAgentApprovalOverlay.dismiss(runId)}
    fun dismissMenu()                                                                                           { contextMenu?.dismiss(); textEditMenu?.dismiss(); formattingMenu?.dismiss(); layoutNamePrompt?.dismiss() }
    fun isDragging():                                                                                           Boolean = activeWidget != null || financeTileDragging || formattingMenu?.isDragging() == true

    // ==================== 3DOF Mode ====================
    /**
     * Set 3DOF mode enabled state. Called by ThreeDofManager.
     */
    fun set3DofEnabled(enabled: Boolean) {
        if (is3DofEnabled != enabled) {
            is3DofEnabled = enabled
            threeDofOverlay.set3DofEnabled(enabled)
            Log.d(TAG, "3DOF mode ${if (enabled) "enabled" else "disabled"}")
            notifyTransientContentChanged()
        }
    }
    /**
     * Update the 3DOF content offset. Called by ThreeDofManager.
     * @param offsetX Horizontal offset in pixels (positive = content shifts right)
     * @param offsetY Vertical offset in pixels (positive = content shifts down)
     */
    fun set3DofOffset(offsetX: Float, offsetY: Float) {
        threeDofOffsetX = offsetX
        threeDofOffsetY = offsetY
        threeDofOverlay.setContentOffset(offsetX, offsetY)
        notifyTransientContentChanged()
    }
    fun set3DofRoll(rollDeg: Float) {threeDofRollDeg = rollDeg ; notifyTransientContentChanged()}
    fun set3DofTransitionProgress(progress: Float) {threeDofOverlay.setTransitionProgress(progress);notifyTransientContentChanged()} /** Set the 3DOF transition animation progress. @param progress Progress from 0.0 (start) to 1.0 (complete) */
    private fun shouldApplyThreeDofTransform(): Boolean = displayState == WakeSleepManager.DisplayState.WAKE && !hasFullscreenWidget() && (is3DofEnabled || threeDofOverlay.isVisible())

    /** Convert the fixed screen cursor to the coordinate space in which widgets are drawn. */
    fun screenToContentCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> {
        if (!shouldApplyThreeDofTransform()) return Pair(screenX, screenY)
        return ThreeDofCoordinateMath.screenToContent(screenX = screenX, screenY = screenY, viewportWidth = width.toFloat(), viewportHeight = height.toFloat(), offsetX = threeDofOffsetX, offsetY = threeDofOffsetY, rollDeg = threeDofRollDeg,)
    }
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

        val applyThreeDofTransform = shouldApplyThreeDofTransform()
        val cx = width / 2f - threeDofOffsetX
        val cy = height / 2f - threeDofOffsetY

        // Apply 3DOF transformation to dashboard content, but not to head-locked menus.
        if (applyThreeDofTransform) {
            canvas.save()
            // Translate canvas by 3DOF offset to move content with head movement
            canvas.translate(threeDofOffsetX, threeDofOffsetY)
            canvas.rotate(-threeDofRollDeg, cx, cy)
        }

        // While the hover-controls layout editor is open, hide widgets and the regular
        // dashboard hover overlays — the editor renders the controls itself in their
        // dashboard positions.
        val editorActive = settingsController.isHoverControlsLayoutEditorVisible()

        if (!editorActive) {
            // Draw all widgets (affected by 3DOF transformation)
            for (widget in registry.getAllSortedByZOrder()) {
                widget.draw(canvas)
            }

            // Hover controls are dashboard overlays and should visually win over any
            // widget they overlap while hovered, except for visible keyboard keys.
            drawHoverControls(canvas)
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

        // Draw 3DOF overlay (boundary rectangle - moves with content to show head-locked area)
        threeDofOverlay.draw(canvas)

        // Restore canvas if 3DOF was applied
        if (applyThreeDofTransform) {canvas.restore()}

        drawPresentationProgress(canvas)

        // Draw menus (not affected by 3DOF - stay head-locked like cursor)
        drawOverlayMenus(canvas)

        // Note: Brightness dimming is now handled at window level via WindowManager.LayoutParams.screenBrightness
        // This ensures the entire app (including WebViews) is dimmed uniformly.
    }

    private fun drawOverlayMenus(canvas: Canvas) {
        val mapOverlayVisible = mapWidget?.let {it.isAddressEntryVisible() || it.navigationState.choices.isNotEmpty()} == true
        if (isAnyPopupVisible() || mapOverlayVisible || focusedBrowserWidget?.isEditingAddress() == true || fullscreenBrowserWidget?.isEditingAddress() == true) {ensureDrawnAboveNativeOverlays()}
        mapWidget?.drawScreenOverlay(canvas)
        contextMenu?.draw(canvas)
        textEditMenu?.draw(canvas)
        formattingMenu?.draw(canvas)
        settingsController.drawHoverControlsLayoutEditor(canvas)  // Draw the hover-controls editor before the close-app confirmation so the popup appears on top of it when the user invokes Close from the editor's context menu.
        closeConfirmationPopup?.draw(canvas)
        layoutDeleteConfirmationPopup?.draw(canvas)
        settingsController.draw(canvas)
        layoutNamePrompt?.draw(canvas)
        pageAgentApprovalOverlay.draw(canvas)
    }

    private fun drawHoverControls(canvas: Canvas) {
        hoverControls.forEach { it.draw(canvas) }
    }

    private fun drawVisibleKeyboardOverlays(canvas: Canvas) {
        if (hasFullscreenWidget()) {
            fullscreenBrowserWidget?.takeIf { it.isKeyboardVisible }?.getKeyboard()?.draw(canvas)
            fullscreenTextWidget?.takeIf { it.isKeyboardVisible }?.getKeyboard()?.draw(canvas)
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
            fullscreenBrowserWidget?.let {
                if (it.isKeyboardVisible && it.getKeyboard()?.containsPoint(cx, cy) == true) return it
            }
            fullscreenTextWidget?.let {
                if (it.isKeyboardVisible && it.getKeyboard()?.containsPoint(cx, cy) == true) return it
            }
            if (isMapWidgetFullscreen && mapWidget?.isAddressKeyboardVisible() == true &&
                mapWidget?.getAddressKeyboard()?.containsPoint(cx, cy) == true) {
                return mapWidget
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
                val before = textPersistentSignature(host)
                runClassifiedInput(
                    action = { host.getKeyboard()?.onTap(cx, cy) == true },
                    changed = { textPersistentSignature(host) != before }
                )
                true
            }
            is MapWidget -> {
                host.getAddressKeyboard()?.onTap(cx, cy)
                notifyTransientContentChanged()
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
        if (textEditMenu?.isVisible == true) return true
        if (formattingMenu?.isVisible == true) return true
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
            closedTemplates = closedTemplateRecords.values.toList(),
            hoverControls = settingsController.getHoverControlPlacements(),
            isFirstRun = false,
            isLayoutLocked = isWidgetLayoutLocked
        )

    private fun storeClosedTemplate(type: WidgetTypeId, state: Any?) {
        val codec = WidgetDocument.codecs[type]
        if (state == null || codec == null) {
            closedTemplateRecords.remove(type)
        } else {
            closedTemplateRecords[type] = WidgetDocument.PersistedWidgetRecord(
                type = type,
                instanceId = "${type.persistedId}#template",
                state = codec.encode(state)
            )
        }
    }

    private fun replaceClosedTemplates(records: List<WidgetDocument.PersistedWidgetRecord>) {
        closedTemplateRecords.clear()
        records.forEach { record ->
            if (WidgetDescriptors[record.type].storesClosedTemplate) {
                closedTemplateRecords.putIfAbsent(record.type, record)
            }
        }
    }

    private inline fun <reified S : Any> closedTemplateState(type: WidgetTypeId): S? {
        val record = closedTemplateRecords[type] ?: return null
        val codec = WidgetDocument.codecs[type] ?: return null
        return codec.decode(record.state) as? S
    }

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
        extraHandlers = listOfNotNull<AssistantSemanticToolHandler>(
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

    /** Starts glasses-local walking navigation and presents the map for voice requests. */
    fun startNavigation(request: StartNavigationRequest): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val action = Runnable {
            try {
                lifecycleController.show(WidgetTypeId.MAP)
                val map = mapWidget ?: error("Map widget could not be created")
                val presentation = request.presentation ?: NavigationPresentationDefaults.VOICE_ROUTE
                if (!presentWidget(map, presentation)) {
                    result.complete(
                        AssistantToolExecution(
                            false,
                            AssistantToolExecutionCodes.EXECUTION_FAILED,
                            "Map presentation could not be started"
                        )
                    )
                    return@Runnable
                }
                withTransientChangeBatch {
                    map.startNavigation(
                        address = request.address,
                        modeName = request.mode,
                        presentationSpec = presentation
                    ) { outcome ->
                        result.complete(outcome.toAssistantExecution())
                    }
                    notifyTransientContentChanged()
                }
            } catch (failure: Throwable) {
                result.complete(
                    AssistantToolExecution(
                        false,
                        AssistantToolExecutionCodes.EXECUTION_FAILED,
                        failure.message ?: "Navigation could not be started"
                    )
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else if (!post(action)) {
            result.complete(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.EXECUTION_FAILED,
                    "Glasses display is unavailable"
                )
            )
        }
        return result
    }

    /** Idempotently stops navigation without changing the map widget's prior presentation state. */
    fun stopNavigation(): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val action = Runnable {
            withTransientChangeBatch {
                dismissMapPresentation()
                mapWidget?.stopNavigation()
                notifyTransientContentChanged()
            }
            result.complete(
                AssistantToolExecution(
                    true,
                    AssistantToolExecutionCodes.SUCCESS,
                    "Navigation stopped"
                )
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else if (!post(action)) {
            result.complete(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.EXECUTION_FAILED,
                    "Glasses display is unavailable"
                )
            )
        }
        return result
    }

    private fun NavigationOperationResult.toAssistantExecution(): AssistantToolExecution = when (this) {
        is NavigationOperationResult.Active -> AssistantToolExecution(
            true,
            AssistantToolExecutionCodes.SUCCESS,
            "Walking route active: destination=${state.destination?.label.orEmpty()}, " +
                    "eta=${formatWalkingEta(state.walkingEtaSeconds)}"
        )
        is NavigationOperationResult.Ambiguous -> AssistantToolExecution(
            false,
            AssistantToolExecutionCodes.EXECUTION_FAILED,
            "Destination is ambiguous; choose one: ${state.choices.joinToString(" | ") { it.label }}"
        )
        is NavigationOperationResult.Failure -> AssistantToolExecution(
            false,
            AssistantToolExecutionCodes.EXECUTION_FAILED,
            message
        )
    }

    /** Applies a semantic POI request on the UI thread and optionally presents the map. */
    fun configureMapPois(request: ConfigureMapPoisRequest): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val action = Runnable {
            runCatching {
                val mapWasAlreadyVisible = mapWidget != null
                lifecycleController.show(WidgetTypeId.MAP)
                val map = mapWidget ?: error("Map widget could not be created")
                val beforeConfig = map.poiDisplayConfig
                map.applyPoiDisplayConfig(request.config)
                val configChanged = map.poiDisplayConfig != beforeConfig
                val presented = request.presentation?.let { presentWidget(map, it) } ?: true
                if (!presented) {
                    AssistantToolExecution(
                        false,
                        AssistantToolExecutionCodes.EXECUTION_FAILED,
                        "Map presentation could not be started"
                    )
                } else {
                    // Creation already notified through the lifecycle controller.  Existing-map
                    // configuration changes need the explicit persistent signal here.
                    if (configChanged && mapWasAlreadyVisible) notifyPersistentContentChanged()
                    AssistantToolExecution(
                        true,
                        AssistantToolExecutionCodes.SUCCESS,
                        "Map POIs configured: state=${request.config.state.name}, " +
                                "categories=${
                                    request.config.categories.sorted().joinToString(",")
                                }, " +
                                "radius_m=${request.config.radiusM}, max_results=${request.config.maxResults}"
                    )
                }
            }.onSuccess(result::complete)
                .onFailure { failure ->
                    result.complete(
                        AssistantToolExecution(
                            false,
                            AssistantToolExecutionCodes.EXECUTION_FAILED,
                            failure.message ?: "Map POI configuration failed"
                        )
                    )
                }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else if (!post(action)) {
            result.complete(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.EXECUTION_FAILED,
                    "Glasses display is unavailable"
                )
            )
        }
        return result
    }

    /**
     * Present assistant-authored text on the UI thread. The callback is asynchronous because
     * semantic handlers run inside the provider worker and widget mutations are main-thread-only.
     */
    fun presentAssistantText(request: AssistantTextPresentationRequest): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val action = Runnable {
            val execution = runCatching { presentAssistantTextOnUi(request) }
                .getOrElse { failure ->
                    AssistantToolExecution(
                        false,
                        AssistantToolExecutionCodes.EXECUTION_FAILED,
                        failure.message ?: "Text presentation failed"
                    )
                }
            result.complete(execution)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else if (!post(action)) {
            result.complete(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.EXECUTION_FAILED,
                    "Glasses display is unavailable"
                )
            )
        }
        return result
    }

    private fun presentAssistantTextOnUi(request: AssistantTextPresentationRequest): AssistantToolExecution {
        val targetedWidget = request.targetWidgetId
            ?.let(::findWidgetByStableId)
            as? TextBoxWidget
        val widget = targetedWidget ?: createAssistantTextWidget()
            ?: return AssistantToolExecution(
            false,
            AssistantToolExecutionCodes.EXECUTION_FAILED,
            "Text widget could not be created"
        )

        val renderedContent = AssistantTextContentFormatter.render(request.content, request.sources)
        widget.setAssistantContent(renderedContent)

        // Assistant output is not a typing task: clear both the canvas keyboard and any Android
        // IME/focus state before applying the requested temporary presentation.
        widget.hideKeyboard()
        widget.setFocused(false)
        setFocusedWidget(null)
        hideAssistantIme()

        val presented = presentWidget(
            widget = widget,
            spec = request.spec,
            createdByPresentation = targetedWidget == null
        )
        if (!presented) {
            if (targetedWidget == null) removePresentationTarget(widget)
            return AssistantToolExecution(
                false,
                AssistantToolExecutionCodes.EXECUTION_FAILED,
                "Text presentation could not be started"
            )
        }
        return AssistantToolExecution(
            true,
            AssistantToolExecutionCodes.SUCCESS,
            "Text presented"
        )
    }

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
    fun assistantUiContext(): AssistantUiContext {
        val entries = mutableListOf<Triple<String, WidgetDescriptor, BaseWidget>>()
        WidgetDescriptors.all.forEach { descriptor ->
            registry.widgets(descriptor.type).forEach { widget ->
                val id = stableWidgetId(widget)
                entries += Triple(id, descriptor, widget)
            }
        }

        val focused = when {
            focusedWidget != null -> focusedWidget
            focusedBrowserWidget != null -> focusedBrowserWidget
            else -> null
        }
        val focusedId = entries.firstOrNull { it.third === focused }?.first
        val widgets = entries.map { (id, descriptor, widget) ->
            val bounds = widget.getBounds()
            val facts = mutableListOf(
                AssistantSemanticFact("widget_type", descriptor.type.persistedId),
                AssistantSemanticFact("widget_label", descriptor.label)
            )
            if (widget is NewsWidget) {
                facts += AssistantSemanticFact("source_kind", widget.getSourceSpec().kind.name)
                facts += AssistantSemanticFact("source_description", widget.getSourceDescription())
            }
            if (widget is FinanceWidget) {
                facts += widget.semanticState(id).toAssistantSemanticFacts()
            }
            if (widget is MapWidget) {
                facts += widget.poiDisplayConfig.toAssistantSemanticFacts()
                facts += widget.navigationState.toAssistantSemanticFacts()
            }
            AssistantWidgetContext(
                id = id,
                left = bounds.left.roundToInt(),
                top = bounds.top.roundToInt(),
                right = bounds.right.roundToInt(),
                bottom = bounds.bottom.roundToInt(),
                zOrder = widget.zOrder,
                minimized = widget.isMinimized,
                fullscreen = widget.isFullscreen,
                pinned = widget.isPinned,
                stableId = id,
                facts = facts
            )
        }
        return AssistantUiContext(
            screenWidth = width,
            screenHeight = height,
            layoutName = layoutManager.activeLayoutName,
            focusedWidgetId = focusedId,
            widgets = widgets
        )
    }

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
        notifyRestored: Boolean = true
    ) {
        if (!isReady()) return

        val previousDashboard = captureWidgetDashboardWithoutLayoutName().copy(
            activeLayoutName = layoutManager.activeLayoutName
        )
        suppressContentChangedCallback = true
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
        } finally {
            suppressContentChangedCallback = false
        }

        // The container now reflects a stored dashboard, so saving is safe again.
        isAwaitingRestore = false

        if (notifyRestored) notifyDashboardRestored()
    }

    private fun applyPersistedContents(dashboard: WidgetDocument.PersistedDashboard) {
        shouldCreateDefaultSingletons = false
        replaceClosedTemplates(dashboard.closedTemplates)

        dashboard.widgets.forEach { record ->
            runCatching { lifecycleController.restore(record) }
                .onFailure { Log.w(TAG, "Skipping invalid persisted ${record.type.persistedId} widget", it) }
        }
        settingsController.applyHoverControlPlacements(dashboard.hoverControls)
        setWidgetLayoutLocked(dashboard.isLayoutLocked, persist = false)

        updateMinimizedWidgetPositions()
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
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
        textEditMenu?.dismiss()
        formattingMenu?.dismiss()
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
        googleAuthBrowserWidget = null
        notifyBrowserMinimumBrightnessChanged()
    }

    private fun destroyWidget(widget: BaseWidget) {
        if (widget is ScreenMirrorWidget) {
            setBinocularMediaSourceActive(widget.binocularSourceId, false)
        }
        if (widget is FinanceWidget) {
            dataProvider?.onFinanceVisibilityChanged(false)
        }
        widget.onDestroy()
    }

    private fun applyFullscreenState(widget: BaseWidget?, shouldBeFullscreen: Boolean) {
        if (widget == null || !shouldBeFullscreen || widget.isMinimized || widget.isFullscreen) return
        widget.toggleFullscreen()
    }

    private fun restoreTextWidget(state: DashboardPersistence.TextWidgetState): TextBoxWidget? =
        widgetCaptureRestore.restoreTextWidgets(listOf(state), width, height).firstOrNull()

    private fun restoreBrowserWidget(state: DashboardPersistence.BrowserWidgetState,): BrowserWidget? {
        val rootView = parent as? ViewGroup ?: return null
        val adjustedX = state.x.coerceIn(0f, (width - state.width).coerceAtLeast(0f))
        val adjustedY = state.y.coerceIn(0f, (height - state.height).coerceAtLeast(0f))
        val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, width.toFloat())
        val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, height.toFloat())
        return browserWidgetFactory(rootView)
            .create(adjustedX, adjustedY, adjustedWidth, adjustedHeight)
            .apply {widgetCaptureRestore.restoreBrowserWidgetState(this, state, adjustedX, adjustedY)}
    }

    private fun restoreImageWidget(state: DashboardPersistence.ImageWidgetState): ImageWidget? =
        widgetCaptureRestore.restoreImageWidgets(listOf(state), width, height).firstOrNull()

    private fun restoreVideoWidget(state: DashboardPersistence.VideoWidgetState): VideoWidget? {
        val rootView = parent as? ViewGroup ?: return null
        val uri = runCatching { android.net.Uri.parse(state.uri) }.getOrNull() ?: return null
        val readable = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) return null

        val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, width.toFloat())
        val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, height.toFloat())
        val adjustedX = state.x.coerceIn(0f, (width - adjustedWidth).coerceAtLeast(0f))
        val adjustedY = state.y.coerceIn(0f, (height - adjustedHeight).coerceAtLeast(0f))
        return videoWidgetFactory(rootView)
            .create(uri, state.displayName, adjustedX, adjustedY)
            .apply {
                widgetWidth = adjustedWidth
                widgetHeight = adjustedHeight
                widgetCaptureRestore.restoreVideoWidgetState(this, state, adjustedX, adjustedY)
            }
    }

    private fun restorePdfWidget(state: DashboardPersistence.PdfWidgetState): PdfWidget? {
        val uri = runCatching { android.net.Uri.parse(state.uri) }.getOrNull() ?: return null
        val readable = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!readable) return null

        val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, width.toFloat())
        val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, height.toFloat())
        val adjustedX = state.x.coerceIn(0f, (width - adjustedWidth).coerceAtLeast(0f))
        val adjustedY = state.y.coerceIn(0f, (height - adjustedHeight).coerceAtLeast(0f))
        return PdfWidget(
            context = context,
            contentUri = uri,
            displayName = state.displayName,
            x = adjustedX,
            y = adjustedY,
            width = adjustedWidth,
            height = adjustedHeight,
            initialPage = state.page,
            initialZoom = state.zoom
        ).apply {
            onStateChanged = { notifyTransientContentChanged() }
            widgetCaptureRestore.restorePdfWidgetState(this, state, adjustedX, adjustedY)
        }
    }

    private fun openClosedTextTemplate(): TextBoxWidget? {
        val template = closedTemplateState<DashboardPersistence.TextWidgetState>(WidgetTypeId.TEXT) ?: return null
        val widget = widgetCaptureRestore.restoreTextWidgets(listOf(template), width, height).firstOrNull() ?: return null
        return runPersistentMutation {
            lifecycleController.open(textLifecycle) { widget }
            applyFullscreenState(widget, template.isFullscreen)
            if (!widget.isMinimized) {
                widget.setFocused(true)
                setFocusedWidget(widget)
            }
            widget
        }
    }

    private fun openClosedBrowserTemplate(): BrowserWidget? {
        val template =
            closedTemplateState<DashboardPersistence.BrowserWidgetState>(WidgetTypeId.BROWSER)
                ?: return null
        val widget = restoreBrowserWidget(template) ?: return null

        return runPersistentMutation {
            lifecycleController.open(browserLifecycle) { widget }
            notifyMediaBrightnessCapChanged()
            notifyBrowserMinimumBrightnessChanged()
            applyFullscreenState(widget, template.isFullscreen)
            if (!widget.isMinimized) {
                widget.setFocused(true)
                setFocusedWidget(widget)
            }
            widget
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
        val widget = StatusBarWidget(context, state.x, state.y, 220f, 110f).apply {
            onStateChanged = { notifyTransientContentChanged() }
        }
        widgetCaptureRestore.restoreStatusBarPosition(widget, state, width, height)
        applyFullscreenState(widget, state.isFullscreen)
        return widget
    }

    fun shouldApplyBrowserMinimumBrightness(): Boolean {
        return browserWidgets.any { !it.isMinimized }
    }

    fun shouldApplyMediaBrightnessCap(): Boolean {
        return fullscreenVideoWidget != null || screenMirrorWidget?.shouldApplyMediaBrightnessCap() == true
    }

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
        applyFullscreenState(widget, state.isFullscreen)
        // Restore the projected preference only after fullscreen bounds/state exist.
        widget.restorePreferences(state.mode, state.zoom, state.showLocation, state.showCountry)
        return widget
    }

    private fun applyLocationState(state: DashboardPersistence.LocationWidgetState): LocationWidget {
        val widget = LocationWidget(context, state.x, state.y, state.width, state.height).apply {
            onTemperatureUnitChanged = { unitSettings.toggleTemperatureOverride() }
            setTemperatureUnit(unitSettings.effectiveTemperatureUnit())
            if (pendingLocationAddress != null || pendingLocationWeather != null) {
                setLocationData(pendingLocationAddress ?: "Loading...", pendingLocationWeather ?: "Checking weather...")
            }
            setSunTimes(pendingLocationSunriseEpochMs, pendingLocationSunsetEpochMs)
        }
        widgetCaptureRestore.restoreLocationWidgetPosition(widget, state, width, height)
        applyFullscreenState(widget, state.isFullscreen)
        return widget
    }

    /**
     * Restore or create calendar widget with the given state.
     */
    private fun applyCalendarState(state: DashboardPersistence.CalendarWidgetState): CalendarWidget {
        val widget = CalendarWidget(state.x, state.y, state.width, state.height).apply {
            onStateChanged = { notifyTransientContentChanged() }
            setFontScale(state.fontScale)
            setSnapshot(pendingCalendarSnapshot)
        }
        widgetCaptureRestore.restoreCalendarWidgetPosition(widget, state, width, height)
        applyFullscreenState(widget, state.isFullscreen)
        return widget
    }

    /**
     * Restore or create finance widget with the given state.
     */
    private fun applyFinanceState(state: DashboardPersistence.FinanceWidgetState): FinanceWidget {
        val widget = FinanceWidget(state.x, state.y, state.width, state.height).apply {
            listener = object : FinanceWidgetListener {
                override fun onStateChanged() = notifyTransientContentChanged()
                override fun onPersistentStateChanged() = notifyPersistentContentChanged()
                override fun onDataRequested(visibleConfigs: List<FinanceDashboardTileConfig>, refreshTileIds: List<String>, force: Boolean, reason: String) {
                    dataProvider?.requestFinanceData(visibleConfigs, refreshTileIds, force, reason)
                }
            }
            pendingFinanceCountryCode?.let { setCountryCode(it) }
        }
        widgetCaptureRestore.restoreFinanceWidgetPosition(widget, state, width, height)
        applyFullscreenState(widget, state.isFullscreen)
        if (widget.isMinimized) {
            dataProvider?.onFinanceVisibilityChanged(false)
        } else {
            widget.requestData(force = true, reason = "widget_restored")
            dataProvider?.onFinanceVisibilityChanged(true)
        }
        return widget
    }

    /**
     * Restore or create news widget with the given state.
     */
    private fun applyNewsState(state: DashboardPersistence.NewsWidgetState): NewsWidget {
        val widget = NewsWidget(state.x, state.y, state.width, state.height).apply {
            listener = object : NewsWidgetListener {
                override fun onStateChanged() = notifyTransientContentChanged()
                override fun onPersistentStateChanged() = notifyPersistentContentChanged()
                override fun onDataRequested(countryCode: String, force: Boolean, reason: String) {
                    dataProvider?.requestNewsData(countryCode, force, reason)
                }
                override fun onSourceDataRequested(sourceSpec: NewsSourceSpec, countryCode: String, force: Boolean, reason: String, completion: (Result<NewsSnapshot>) -> Unit) {
                    dataProvider?.requestNewsSourceData(sourceSpec, countryCode, force, reason, completion)
                        ?: completion(Result.failure(IllegalStateException("News data service is unavailable")))
                }
                override fun onSourceInputFocusChanged(focused: Boolean) { notifyFocusChanged(focused) }
            }
            pendingNewsCountryCode?.let { setCountryCode(it) }
        }
        widgetCaptureRestore.restoreNewsWidgetPosition(widget, state, width, height)
        applyFullscreenState(widget, state.isFullscreen)
        widget.requestData(force = true, reason = "widget_restored")
        return widget
    }

    private fun applyNotificationsState(state: DashboardPersistence.NotificationsWidgetState): NotificationsWidget {
        val widget = NotificationsWidget(
            context,
            state.x,
            state.y,
            state.width,
            state.height,
            height.toFloat()
        ).apply {
            onStateChanged = { notifyTransientContentChanged() }
        }
        widgetCaptureRestore.restoreNotificationsWidgetPosition(widget, state, width, height)
        widget.setScreenHeight(height.toFloat())
        widget.setNotificationsSnapshot(latestNotificationsSnapshot)
        applyFullscreenState(widget, state.isFullscreen)
        return widget
    }

    /**
     * Restore or create speedometer widget with the given state.
     */
    private fun applySpeedometerState(state: DashboardPersistence.SpeedometerWidgetState): SpeedometerWidget {
        val widget = createSpeedometerWidget(state.x, state.y, state.width, state.height)
        widgetCaptureRestore.restoreSpeedometerWidgetPosition(widget, state, width, height)
        applyFullscreenState(widget, state.isFullscreen)
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
        applyFullscreenState(widget, state.isFullscreen)
        return widget
    }

    /**
     * Restore or create mirror widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    private fun applyMirrorState(state: DashboardPersistence.MirrorWidgetState): ScreenMirrorWidget {
        val widget = ScreenMirrorWidget(context, state.x, state.y, state.width, state.height).apply {
            configureScreenMirrorWidget(this)
        }
        widgetCaptureRestore.restoreMirrorWidgetPosition(widget, state, width, height)
        applyFullscreenState(widget, state.isFullscreen)
        widget.onDisplayVisibilityChanged(isMirrorWidgetVisible(widget))
        notifyMediaBrightnessCapChanged()
        return widget
    }

    fun isReady(): Boolean = width > 0 && height > 0

    // ==================== Screen Mirroring ====================
    fun setMirroringEnabled(enabled: Boolean, phoneIp: String? = null) {
        withTransientChangeBatch {
            screenMirrorWidget?.setMirroringEnabled(enabled, phoneIp)
            screenMirrorWidget?.onDisplayVisibilityChanged(
                screenMirrorWidget?.let { isMirrorWidgetVisible(it) } ?: false
            )
            notifyMediaBrightnessCapChanged()
            notifyTransientContentChanged()
        }
    }

    fun onTrimMemory(level: Int)                      {browserWidgets.forEach { it.onTrimMemory(level) }}
    fun onLowMemory()                                 {browserWidgets.forEach { it.onLowMemory() }}
    fun clearDebugWebViewData(profileSummary: String) {browserWidgets.forEach { it.clearDebugWebViewData(profileSummary) }}
    fun release()                                     {presentationController.dismiss() ; lifecycleController.closeAll()}
}
