package com.everyday.everyday_glasses

import android.content.Context
import android.content.ContentUris
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.everyday.everyday_glasses.binocular.BinocularContentClass
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
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Locale
import kotlin.math.roundToInt
import com.everyday.everyday_glasses.video.VideoItem

/**
 * Container view that manages dynamic widgets (text boxes, status bar, etc.).
 *
 * Responsibilities are delegated to helper classes:
 * - CursorStabilizer: Cursor physics and smoothing for glasses temple input
 * - WidgetRegistry: Widget collection and z-ordering
 * - WidgetPersistenceHelper: Save/restore widget states
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
    private val assistantWidgetIds = IdentityHashMap<BaseWidget, String>()
    private val assistantWidgetSequence = mutableMapOf<WidgetTypeId, Int>()
    private val persistenceHelper = WidgetPersistenceHelper(context)
    private val lifecycleController = WidgetLifecycleController(
        runOnUiThread = { action -> post(action) },
        onChanged = ::notifyContentChanged
    )
    private val threeDofOverlay = ThreeDofOverlay()
    private val smartAlignment = SmartAlignmentHelper(registry)
    private val smartAlignmentRenderer = SmartAlignmentRenderer()
    private val unitSettings = UnitSettings(context)
    private var closedWidgetTemplates = WidgetPersistence.ClosedWidgetTemplates()

    // ==================== 3DOF State ====================

    // Whether 3DOF mode is currently active
    private var is3DofEnabled = false

    // Current content offset from 3DOF head tracking (in pixels)
    private var threeDofOffsetX = 0f
    private var threeDofOffsetY = 0f
    private var threeDofRollDeg = 0f

    // ==================== Menus & Overlays ====================
    
    private var contextMenu: ContextMenu? = null
    private var textEditMenu: TextEditContextMenu? = null
    private enum class TextEditMenuTarget { TEXT_WIDGET, BROWSER_ADDRESS }
    private var textEditMenuTarget: TextEditMenuTarget = TextEditMenuTarget.TEXT_WIDGET
    private var formattingMenu: FormattingMenu? = null
    private var closeConfirmationPopup: CloseConfirmationPopup? = null
    private var layoutDeleteConfirmationPopup: WidgetLayoutDeleteConfirmationPopup? = null
    private val pageAgentApprovalOverlay = PageAgentApprovalOverlay(::notifyContentChanged)

    private val commandRegistry by lazy {
        AppCommandRegistry { stateStore.listLayouts().map { it.name } }
    }
    private val commandExecutor by lazy {
        AppCommandExecutor(commandRegistry, commandCapabilities, CommandUiDispatcher { action ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                action()
            } else {
                val completed = CountDownLatch(1)
                post {
                    try { action() } finally { completed.countDown() }
                }
                completed.await(5, TimeUnit.SECONDS)
            }
        })
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
        override fun setBrightness(value: Float) = settingsController.setBrightness(value)
        override fun scroll(direction: CommandScrollDirection, amount: Float) =
            scrollWidgetAtCursor(cursorX, cursorY, if (direction == CommandScrollDirection.UP) -amount else amount)
        override fun openUrl(url: String) = openBrowserAddress(url)
        override fun enterText(text: String) = onKeyPress(text)
        override fun commandFinished() = notifyContentChanged()
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
    var statusBarWidget: StatusBarWidget?
        get() = registry.statusBarWidget
        private set(value) { registry.statusBarWidget = value }

    // Location widget - delegated to registry
    var locationWidget: LocationWidget?
        get() = registry.locationWidget
        private set(value) { registry.locationWidget = value }

    var mapWidget: MapWidget?
        get() = registry.mapWidget
        private set(value) { registry.mapWidget = value }

    var calendarWidget: CalendarWidget?
        get() = registry.calendarWidget
        private set(value) { registry.calendarWidget = value }

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
    var financeWidget: FinanceWidget?
        get() = registry.financeWidget
        private set(value) { registry.financeWidget = value }

    // Pending country code for finance widget
    private var pendingFinanceCountryCode: String? = null

    // News widget - delegated to registry
    var newsWidget: NewsWidget?
        get() = registry.newsWidget
        private set(value) { registry.newsWidget = value }

    // Pending country code for news widget
    private var pendingNewsCountryCode: String? = null

    var notificationsWidget: NotificationsWidget?
        get() = registry.notificationsWidget
        private set(value) { registry.notificationsWidget = value }

    // Speedometer widget - delegated to registry
    var speedometerWidget: SpeedometerWidget?
        get() = registry.speedometerWidget
        private set(value) { registry.speedometerWidget = value }

    // Subtitle widget - delegated to registry
    var subtitleWidget: SubtitleWidget?
        get() = registry.subtitleWidget
        private set(value) { registry.subtitleWidget = value }

    // Pending speed data for speedometer widget
    private var pendingSpeedKmh = 0f
    private var pendingSpeedQualityOk = false
    private var pendingSpeedMoving = false

    // Screen mirror widget - delegated to registry
    var screenMirrorWidget: ScreenMirrorWidget?
        get() = registry.screenMirrorWidget
        private set(value) { registry.screenMirrorWidget = value }

    // Settings overlays and persisted app-level settings
    private val settingsController = SettingsController(
        resources = resources,
        defaultSpeedVisibilityThresholdKmh = DEFAULT_SPEED_VISIBILITY_THRESHOLD_KMH,
        invalidateView = { invalidate() },
        notifyContentChanged = ::notifyContentChanged,
        setSpeedometerForceVisibleWhenIdle = { forceVisible ->
            speedometerWidget?.setForceVisibleWhenIdle(forceVisible) == true
        },
        isSmartAlignmentEnabled = { smartAlignment.enabled },
        setSmartAlignmentEnabled = { enabled -> smartAlignment.enabled = enabled },
        notifyBrightnessChanged = { value -> displaySettingsHandler?.applyBrightness(value) },
        executeBrightnessCommand = { value ->
            executeCommand(AppCommandInvocation(commandRegistry.setBrightness.id, AppCommandArguments.Brightness(value)))
        },
        notifyAdaptiveBrightnessChanged = { enabled -> displaySettingsHandler?.applyAdaptiveBrightness(enabled) },
        notifyHeadUpTimeChanged = { ms -> displaySettingsHandler?.applyHeadUpTime(ms) },
        notifyWakeDurationChanged = { ms -> displaySettingsHandler?.applyWakeDuration(ms) },
        notifyAngleThresholdChanged = { degrees -> displaySettingsHandler?.applyHeadUpAngleThreshold(degrees) },
        notifyHeadsUpEnabledChanged = { enabled -> displaySettingsHandler?.applyHeadsUpEnabled(enabled) },
        notifySmartAlignmentChanged = { enabled -> displaySettingsHandler?.applySmartAlignment(enabled) },
        notifySpeedVisibilityThresholdChanged = { threshold -> displaySettingsHandler?.applySpeedVisibilityThreshold(threshold) },
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
    private val stateStore = WidgetStateStore(
        context = context,
        handler = Handler(Looper.getMainLooper()),
        captureState = ::captureWidgetStateWithoutLayoutName
    )
    private val layoutManager = WidgetLayoutManager(
        context = context,
        stateStore = stateStore,
        canApplyLayout = ::isReady,
        captureState = ::captureWidgetStateWithoutLayoutName,
        applyState = ::applyPersistedStateReplacingCurrent,
        buildDeliveredLayout = { name -> BuiltInLayouts.build(name, width.toFloat(), height.toFloat()) },
        showNamePrompt = { suggestedName ->
            dismissMenu()
            layoutNamePrompt?.show(suggestedName)
            contentObserver?.onFocusChanged(true)
        },
        showNameError = { error -> layoutNamePrompt?.showError(error) },
        dismissNamePrompt = { layoutNamePrompt?.dismiss() },
        showDeleteConfirmation = { name -> layoutDeleteConfirmationPopup?.show(name) },
        beforeApplyLayout = ::requestLayoutSwitchBrightness,
        notifyContentChanged = ::notifyContentChanged
    )

    var displaySettingsHandler: DisplaySettingsHandler? = null
    var googleAccountHandler: GoogleAccountHandler? = null

    // Access to widget lists via registry
    private val widgets: MutableList<TextBoxWidget>
        get() = registry.textWidgets
    
    private val browserWidgets: MutableList<BrowserWidget>
        get() = registry.browserWidgets

    private val imageWidgets: MutableList<ImageWidget>
        get() = registry.imageWidgets

    private val videoWidgets: MutableList<VideoWidget>
        get() = registry.videoWidgets

    private val pdfWidgets: MutableList<PdfWidget>
        get() = registry.pdfWidgets

    private val fileBrowserWidgets: MutableList<FileBrowserWidget>
        get() = registry.fileBrowserWidgets

    // Currently focused widget
    private var focusedWidget: TextBoxWidget? = null
    private var focusedBrowserWidget: BrowserWidget? = null

    // Widget being dragged
    private var activeWidget: BaseWidget? = null
    private var isWidgetLayoutLocked = false

    // The single widget that currently owns hover — and therefore interaction. At most one
    // widget is hovered at a time; a widget can only *become* hovered when no other widget is
    // already hovered under the cursor. See resolveHoveredWidget for the selection rules.
    private var hoveredWidget: BaseWidget? = null

    // Drag state flags.
    // Per-singleton-widget drag state lives on each SingletonDesc (desc.dragging);
    // the finance dashboard's per-tile drag is a separate, widget-internal gesture.
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
            nowElapsedMs = { android.os.SystemClock.elapsedRealtime() },
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
    // Per-singleton pending-drag state lives on each SingletonDesc (desc.pendingDrag).
    private var pendingDragFinanceTile = false
    private var pendingDragIsResize = false
    private var pendingTextSelection = false
    private var pendingCreateX = 0f
    private var pendingCreateY = 0f

    // ==================== Fullscreen Tracking ====================
    
    private var fullscreenTextWidget: TextBoxWidget? = null
    private var fullscreenBrowserWidget: BrowserWidget? = null
    private var fullscreenImageWidget: ImageWidget? = null
    private var fullscreenVideoWidget: VideoWidget? = null
    private var fullscreenPdfWidget: PdfWidget? = null
    private var fullscreenFileBrowserWidget: FileBrowserWidget? = null
    private var googleAuthBrowserWidget: BrowserWidget? = null
    private var browserRoutingCoordinator: BrowserRoutingCoordinator? = null
    private val webSearchHandler = WebSearchSemanticToolHandler(DuckDuckGoWebSearchProvider())
    private var isStatusBarFullscreen = false
    private var isLocationWidgetFullscreen = false
    private var isMapWidgetFullscreen = false
    private var isCalendarWidgetFullscreen = false
    private var isFinanceWidgetFullscreen = false
    private var isNewsWidgetFullscreen = false
    private var isNotificationsWidgetFullscreen = false
    private var isSpeedometerWidgetFullscreen = false
    private var isSubtitleWidgetFullscreen = false
    private var isMirrorWidgetFullscreen = false

    // ==================== Singleton Widget Descriptors ====================
    // Each descriptor binds a singleton widget's fullscreen flag and widget reference to the
    // shared lifecycle helpers (teardownSingleton / hookupSingleton / handleSingletonXxx).

    /** Outcome of hit-testing a singleton widget for drag/resize purposes. */
    private enum class DragHit { NONE, DRAG, RESIZE }

    private inner class SingletonDesc(
        val isFullscreen: () -> Boolean,
        val setFullscreen: (Boolean) -> Unit,
        val widget: () -> BaseWidget?,
        // Normalizes each widget's bespoke hit-test enum to a shared DragHit.
        // Defaults to BaseWidget.baseHitTest; widgets with their own HitArea enum
        // (status bar, screen mirror) supply a custom mapping.
        val dragHit: (Float, Float) -> DragHit = { px, py -> baseDragHit(widget(), px, py) }
    ) {
        // Live drag state, previously held in parallel per-widget boolean fields.
        var dragging = false
        var pendingDrag = false
    }

    private fun baseDragHit(widget: BaseWidget?, px: Float, py: Float): DragHit {
        widget ?: return DragHit.NONE
        return when (widget.baseHitTest(px, py)) {
            BaseWidget.BaseHitArea.BORDER, BaseWidget.BaseHitArea.CONTENT -> DragHit.DRAG
            BaseWidget.BaseHitArea.RESIZE_HANDLE -> DragHit.RESIZE
            else -> DragHit.NONE
        }
    }

    private val statusBarDesc   = SingletonDesc({ isStatusBarFullscreen },        { isStatusBarFullscreen = it },        { statusBarWidget }, { px, py ->
        when (statusBarWidget?.hitTest(px, py)) {
            StatusBarWidget.HitArea.BORDER, StatusBarWidget.HitArea.CONTENT -> DragHit.DRAG
            StatusBarWidget.HitArea.RESIZE_HANDLE -> DragHit.RESIZE
            else -> DragHit.NONE
        }
    })
    private val locationDesc    = SingletonDesc({ isLocationWidgetFullscreen },    { isLocationWidgetFullscreen = it },    { locationWidget })
    private val mapDesc         = SingletonDesc({ isMapWidgetFullscreen }, { value -> isMapWidgetFullscreen = value; if (!value) mapWidget?.enforceWindowedMode() }, { mapWidget })
    private val calendarDesc    = SingletonDesc({ isCalendarWidgetFullscreen },    { isCalendarWidgetFullscreen = it },    { calendarWidget })
    private val financeDesc     = SingletonDesc({ isFinanceWidgetFullscreen },     { isFinanceWidgetFullscreen = it },     { financeWidget })
    private val newsDesc        = SingletonDesc({ isNewsWidgetFullscreen },        { isNewsWidgetFullscreen = it },        { newsWidget })
    private val notificationsDesc = SingletonDesc({ isNotificationsWidgetFullscreen }, { isNotificationsWidgetFullscreen = it }, { notificationsWidget })
    private val speedometerDesc = SingletonDesc({ isSpeedometerWidgetFullscreen }, { isSpeedometerWidgetFullscreen = it }, { speedometerWidget })
    private val subtitleDesc    = SingletonDesc({ isSubtitleWidgetFullscreen },    { isSubtitleWidgetFullscreen = it },    { subtitleWidget })
    private val mirrorDesc      = SingletonDesc({ isMirrorWidgetFullscreen },      { isMirrorWidgetFullscreen = it },      { screenMirrorWidget }, { px, py ->
        val mirror = screenMirrorWidget
        when {
            mirror == null || !mirror.containsPoint(px, py) -> DragHit.NONE
            else -> when (mirror.hitTest(px, py)) {
                ScreenMirrorWidget.HitArea.BORDER, ScreenMirrorWidget.HitArea.CONTENT -> DragHit.DRAG
                ScreenMirrorWidget.HitArea.RESIZE_HANDLE -> DragHit.RESIZE
                else -> DragHit.NONE
            }
        }
    })

    // Canonical singleton list in hit-priority order (screen mirror first, matching
    // the original pointer-down precedence). Drag handling, fullscreen and minimize
    // all iterate this single list. Built once to avoid per-frame allocation while
    // dragging.
    private val singletonDescs: List<SingletonDesc> =
        listOf(mirrorDesc, statusBarDesc, mapDesc, locationDesc, calendarDesc, financeDesc, newsDesc, notificationsDesc, speedometerDesc, subtitleDesc)

    private val browserLifecycle by lazy {
        MultiWidgetLifecycle(
            WidgetDescriptors[WidgetTypeId.BROWSER],
            register = registry::addBrowserWidget,
            unregister = { widget ->
                persistenceHelper.getBrowserWidgetState(widget)?.let {
                    closedWidgetTemplates = closedWidgetTemplates.copy(browser = it)
                }
                if (fullscreenBrowserWidget === widget) fullscreenBrowserWidget = null
                if (focusedBrowserWidget === widget) setFocusedWidget(null)
                if (activeWidget === widget) activeWidget = null
                if (googleAuthBrowserWidget === widget) googleAuthBrowserWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.removeBrowserWidget(widget)
                notifyMediaBrightnessCapChanged()
                notifyBrowserMinimumBrightnessChanged()
                updateMinimizedWidgetPositions()
            },
            teardown = ::destroyWidget
        )
    }
    private val textLifecycle by lazy {
        MultiWidgetLifecycle(
            WidgetDescriptors[WidgetTypeId.TEXT],
            register = registry::addTextWidget,
            unregister = { widget ->
                closedWidgetTemplates = closedWidgetTemplates.copy(text = persistenceHelper.getTextWidgetState(widget))
                if (fullscreenTextWidget === widget) fullscreenTextWidget = null
                if (focusedWidget === widget) setFocusedWidget(null)
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.removeTextWidget(widget)
                updateMinimizedWidgetPositions()
            }
        )
    }
    private val imageLifecycle by lazy {
        MultiWidgetLifecycle(
            WidgetDescriptors[WidgetTypeId.IMAGE],
            register = registry::addImageWidget,
            unregister = { widget ->
                val path = widget.getImagePath()
                val hasSibling = imageWidgets.any { it !== widget && it.getImagePath() == path }
                if (fullscreenImageWidget === widget) fullscreenImageWidget = null
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.removeImageWidget(widget)
                if (!hasSibling) ImageWidgetStorage.deleteIfManaged(context, path)
                updateMinimizedWidgetPositions()
            }
        )
    }
    private val videoLifecycle by lazy {
        MultiWidgetLifecycle(
            WidgetDescriptors[WidgetTypeId.VIDEO],
            register = registry::addVideoWidget,
            unregister = { widget ->
                if (fullscreenVideoWidget === widget) fullscreenVideoWidget = null
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.removeVideoWidget(widget)
                updateMinimizedWidgetPositions()
                notifyMediaBrightnessCapChanged()
            },
            teardown = ::destroyWidget
        )
    }
    private val pdfLifecycle by lazy {
        MultiWidgetLifecycle(
            WidgetDescriptors[WidgetTypeId.PDF],
            register = registry::addPdfWidget,
            unregister = { widget ->
                if (fullscreenPdfWidget === widget) fullscreenPdfWidget = null
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.removePdfWidget(widget)
                updateMinimizedWidgetPositions()
            },
            teardown = ::destroyWidget
        )
    }
    private val filesLifecycle by lazy {
        MultiWidgetLifecycle(
            WidgetDescriptors[WidgetTypeId.FILES],
            register = registry::addFileBrowserWidget,
            unregister = { widget ->
                if (fullscreenFileBrowserWidget === widget) fullscreenFileBrowserWidget = null
                if (activeWidget === widget) activeWidget = null
                notifyPresentationTargetRemoved(widget)
                registry.removeFileBrowserWidget(widget)
                updateMinimizedWidgetPositions()
            }
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
    private var suppressContentChangedCallback = false

    init {
        setWillNotDraw(false)
        registerSingletonLifecycles()
        setupPersistenceHelperCallbacks()
        unitSettings.onChanged = { applyEffectiveUnitSettings() }
        applyEffectiveUnitSettings(notify = false)
    }

    override fun onDetachedFromWindow() {
        presentationController.dismiss()
        super.onDetachedFromWindow()
    }

    private fun registerSingletonLifecycles() {
        fun bind(
            type: WidgetTypeId,
            current: () -> BaseWidget?,
            mutatePresence: () -> Unit,
            hasClosedTemplate: () -> Boolean
        ) {
            lifecycleController.register(
                SingletonWidgetLifecycle(
                    descriptor = WidgetDescriptors[type],
                    current = current,
                    closeCurrent = { mutatePresence() },
                    restoreClosedTemplate = {
                        if (!hasClosedTemplate()) null else {
                            mutatePresence()
                            current()
                        }
                    },
                    createDefault = {
                        mutatePresence()
                        current()
                    }
                )
            )
        }

        bind(WidgetTypeId.STATUS, { statusBarWidget }, ::mutateStatusBarPresence) { closedWidgetTemplates.status != null }
        bind(WidgetTypeId.LOCATION, { locationWidget }, ::mutateLocationPresence) { closedWidgetTemplates.location != null }
        bind(WidgetTypeId.MAP, { mapWidget }, ::mutateMapPresence) { closedWidgetTemplates.map != null }
        bind(WidgetTypeId.CALENDAR, { calendarWidget }, ::mutateCalendarPresence) { closedWidgetTemplates.calendar != null }
        bind(WidgetTypeId.FINANCE, { financeWidget }, ::mutateFinancePresence) { closedWidgetTemplates.finance != null }
        bind(WidgetTypeId.NEWS, { newsWidget }, ::mutateNewsPresence) { closedWidgetTemplates.news != null }
        bind(WidgetTypeId.NOTIFICATIONS, { notificationsWidget }, ::mutateNotificationsPresence) { closedWidgetTemplates.notifications != null }
        bind(WidgetTypeId.SPEEDOMETER, { speedometerWidget }, ::mutateSpeedometerPresence) { closedWidgetTemplates.speedometer != null }
        bind(WidgetTypeId.SUBTITLE, { subtitleWidget }, ::mutateSubtitlePresence) { closedWidgetTemplates.subtitle != null }
        bind(WidgetTypeId.MIRROR, { screenMirrorWidget }, ::mutateMirrorPresence) { closedWidgetTemplates.mirror != null }

        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.STATUS], WidgetPersistence.StatusBarState::class, ::applyStatusBarState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.LOCATION], WidgetPersistence.LocationWidgetState::class, ::applyLocationState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.MAP], WidgetPersistence.MapWidgetState::class, ::applyMapState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.CALENDAR], WidgetPersistence.CalendarWidgetState::class, ::applyCalendarState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.FINANCE], WidgetPersistence.FinanceWidgetState::class, ::applyFinanceState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.NEWS], WidgetPersistence.NewsWidgetState::class, ::applyNewsState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.NOTIFICATIONS], WidgetPersistence.NotificationsWidgetState::class, ::applyNotificationsState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.SPEEDOMETER], WidgetPersistence.SpeedometerWidgetState::class, ::applySpeedometerState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.SUBTITLE], WidgetPersistence.SubtitleWidgetState::class, ::applySubtitleState))
        lifecycleController.register(WidgetStateRestorer(WidgetDescriptors[WidgetTypeId.MIRROR], WidgetPersistence.MirrorWidgetState::class, ::applyMirrorState))
        lifecycleController.verifyComplete()
    }

    private fun setupPersistenceHelperCallbacks() {
        persistenceHelper.listener = object : WidgetPersistenceListener {
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

            override fun onTextWidgetDeleted(widget: TextBoxWidget) = removeWidget(widget)
            override fun onBrowserWidgetClosed(widget: BrowserWidget) = removeBrowserWidget(widget)
            override fun onWidgetStateChanged() = notifyContentChanged()
            override fun onTextWidgetFullscreenToggled(widget: TextBoxWidget, isFullscreen: Boolean) =
                handleTextWidgetFullscreenToggle(widget, isFullscreen)
            override fun onTextWidgetMinimizeToggled(widget: TextBoxWidget, isMinimized: Boolean) =
                handleTextWidgetMinimizeToggle(widget, isMinimized)
            override fun onBrowserWidgetFullscreenToggled(widget: BrowserWidget, isFullscreen: Boolean) =
                handleBrowserWidgetFullscreenToggle(widget, isFullscreen)
            override fun onBrowserWidgetMinimizeToggled(widget: BrowserWidget, isMinimized: Boolean) =
                handleBrowserWidgetMinimizeToggle(widget, isMinimized)
            override fun onBrowserKeyboardRequested(show: Boolean) {
                host?.requestBrowserKeyboard(show)
            }
        }
    }

    // ==================== Public State Queries ====================

    fun hasFullscreenWidget(): Boolean = fullscreenTextWidget != null ||
            fullscreenBrowserWidget != null ||
            fullscreenImageWidget != null ||
            fullscreenVideoWidget != null ||
            fullscreenPdfWidget != null ||
            fullscreenFileBrowserWidget != null ||
            isStatusBarFullscreen ||
            isLocationWidgetFullscreen ||
            isMapWidgetFullscreen ||
            isCalendarWidgetFullscreen ||
            isFinanceWidgetFullscreen ||
            isNewsWidgetFullscreen ||
            isNotificationsWidgetFullscreen ||
            isSpeedometerWidgetFullscreen ||
            isSubtitleWidgetFullscreen ||
            isMirrorWidgetFullscreen

    fun setDisplayState(state: WakeSleepManager.DisplayState) {
        if (displayState != state) {
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
                        view.visibility = View.GONE
                    } else {
                        view.visibility = when (state) {
                            WakeSleepManager.DisplayState.OFF -> View.GONE
                            WakeSleepManager.DisplayState.SLEEP -> if (browser.isPinned) View.VISIBLE else View.GONE
                            else -> if (browser.isMinimized) View.GONE else View.VISIBLE
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

            // Use a single invalidate() instead of the aggressive notifyContentChanged()
            // to prevent power spike from rapid redraw cascade
            invalidate()
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
        newsWidget?.showNextItemOnHeadUpWake()
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

    /**
     * Resolve a stable widget ID, optionally creating the target after any prior session has
     * been restored. The creation callback is deliberately outside the controller so this API
     * remains independent of concrete widget types.
     */
    fun presentWidget(
        targetId: String,
        spec: PresentationSpec,
        createIfMissing: (() -> BaseWidget?)? = null
    ): Boolean {
        require(targetId.isNotBlank()) { "Presentation target ID must not be blank" }
        var created = false
        val session = presentationController.start(targetId, spec) {
            var target = findWidgetByStableId(targetId)
            if (target == null) {
                target = createIfMissing?.invoke()
                if (target != null) {
                    created = true
                    bindStableWidgetId(target, targetId)
                }
            }
            target?.takeIf { widgetStableId(it) == targetId }?.let {
                capturePresentationSnapshot(it, existedBeforePresentation = !created)
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

    /** Bind a Page Agent to this exact browser widget, active tab, and WebView. */
    fun bindPageAgentBrowser(widgetId: String): PageAgentBrowserAdapter? =
        findBrowserWidgetByStableId(widgetId)?.let { widget ->
            BrowserWidgetPageAgentAdapter.bind(widgetId, widget)
        }

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

    /** Stable ID of the browser currently occupying page/video fullscreen, if any. */
    fun fullscreenBrowserWidgetStableId(): String? =
        (fullscreenBrowserWidget ?: browserWidgets.firstOrNull { it.isInVideoFullscreen() })
            ?.let(::stableWidgetId)

    private fun descriptorForWidget(widget: BaseWidget): WidgetDescriptor? =
        WidgetDescriptors.all.firstOrNull { descriptor ->
            registry.widgets(descriptor.type).any { candidate -> candidate === widget }
        }

    private fun stableWidgetId(widget: BaseWidget): String {
        val descriptor = requireNotNull(descriptorForWidget(widget)) {
            "Widget is not registered and has no stable presentation ID"
        }
        if (descriptor.policy == WidgetInstancePolicy.SINGLETON) {
            return descriptor.type.persistedId
        }
        return assistantWidgetIds[widget] ?: run {
            val next = assistantWidgetSequence[descriptor.type] ?: 0
            assistantWidgetSequence[descriptor.type] = next + 1
            "${descriptor.type.persistedId}#$next".also { assistantWidgetIds[widget] = it }
        }
    }

    private fun bindStableWidgetId(widget: BaseWidget, requestedId: String) {
        val descriptor = requireNotNull(descriptorForWidget(widget)) {
            "Created presentation target must be registered before it is presented"
        }
        if (descriptor.policy == WidgetInstancePolicy.SINGLETON) {
            require(descriptor.type.persistedId == requestedId) {
                "Stable ID $requestedId does not belong to ${descriptor.type.persistedId}"
            }
            return
        }
        val conflicting = assistantWidgetIds.entries.firstOrNull { entry ->
            entry.key !== widget && entry.value == requestedId
        }
        require(conflicting == null) { "Stable widget ID $requestedId is already in use" }
        assistantWidgetIds[widget] = requestedId
        val suffix = requestedId.substringAfter("#", "").toIntOrNull()
        if (suffix != null && requestedId.startsWith("${descriptor.type.persistedId}#")) {
            assistantWidgetSequence[descriptor.type] = maxOf(
                assistantWidgetSequence[descriptor.type] ?: 0,
                suffix + 1
            )
        }
    }

    private fun findWidgetByStableId(targetId: String): BaseWidget? =
        registry.allWidgets().firstOrNull { widget ->
            runCatching { stableWidgetId(widget) == targetId }.getOrDefault(false)
        }

    private fun capturePresentationSnapshot(
        widget: BaseWidget,
        existedBeforePresentation: Boolean
    ): PresentationSnapshot {
        val rendered = widget.getBounds()
        val renderedBounds = PresentationBounds(rendered.left, rendered.top, rendered.right, rendered.bottom)
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
        if (widget.isMinimized) widget.toggleMinimize()
        when (session.spec.mode) {
            PresentationMode.FULLSCREEN -> if (!widget.isFullscreen) widget.toggleFullscreen()
            PresentationMode.WINDOWED -> if (widget.isFullscreen) widget.toggleFullscreen()
        }
        bringWidgetToFront(widget)
        notifyContentChanged()
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
            target?.let(::removePresentationTarget)
            return
        }
        target ?: return

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
        notifyContentChanged()
    }

    private fun leavePresentationWindowed(session: PresentationSession) {
        val target = findWidgetByStableId(session.targetId) ?: return
        normalizePresentationWidget(target)
        target.isPinned = session.snapshot.pinned
        setWidgetBounds(target, session.snapshot.windowedBounds)
        if (!session.snapshot.focused && (focusedWidget === target || focusedBrowserWidget === target)) {
            setFocusedWidget(null)
        }
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    private fun closePresentationTarget(targetId: String) {
        findWidgetByStableId(targetId)?.let(::removePresentationTarget)
    }

    private fun removePresentationTarget(widget: BaseWidget) {
        when (widget) {
            is BrowserWidget -> lifecycleController.close(browserLifecycle, widget)
            is TextBoxWidget -> lifecycleController.close(textLifecycle, widget)
            is ImageWidget -> lifecycleController.close(imageLifecycle, widget)
            is VideoWidget -> lifecycleController.close(videoLifecycle, widget)
            is PdfWidget -> lifecycleController.close(pdfLifecycle, widget)
            is FileBrowserWidget -> lifecycleController.close(filesLifecycle, widget)
            else -> descriptorForWidget(widget)?.let { lifecycleController.hide(it.type) }
        }
    }

    private fun notifyPresentationTargetRemoved(widget: BaseWidget) {
        presentationController.onTargetRemoved(runCatching { stableWidgetId(widget) }.getOrNull() ?: return)
    }

    private fun notifyContentChanged() {
        invalidate()
        postInvalidate()
        (parent as? View)?.invalidate()
        if (!suppressContentChangedCallback) {
            contentObserver?.onContentChanged()
        }
    }

    private fun notifyTransientContentChanged() {
        invalidate()
        postInvalidate()
        (parent as? View)?.invalidate()
        contentObserver?.onTransientContentChanged()
    }

    /**
     * Hover updates are purely visual state changes on model objects drawn by this view.
     * They should repaint immediately without being treated as structural widget changes.
     */
    private fun invalidateHoverVisuals() {
        invalidate()
        postInvalidate()
        (parent as? View)?.invalidate()
    }

    private fun notifyMediaFrame(sourceId: String) {
        if (displayState == WakeSleepManager.DisplayState.OFF) return
        invalidate()
        postInvalidate()
        (parent as? View)?.invalidate()
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
            override fun onKeyboardRequested(show: Boolean) {
                host?.requestBrowserKeyboard(show)
            }
        }
        widget.configureLifecycleListener(
            onClose = { removeBrowserWidget(widget) },
            onFullscreen = { isFullscreen ->
                handleBrowserWidgetFullscreenToggle(widget, isFullscreen)
            },
            onMinimize = { isMinimized ->
                handleBrowserWidgetMinimizeToggle(widget, isMinimized)
            }
        )
        widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
        notifyBrowserMinimumBrightnessChanged()
    }

    private fun configureScreenMirrorWidget(widget: ScreenMirrorWidget) {
        widget.listener = object : MirrorWidgetListener {
            override fun onStateChanged(state: ScreenMirrorWidget.State) {
                when (state) {
                    ScreenMirrorWidget.State.MOVING -> mirrorDesc.dragging = true
                    ScreenMirrorWidget.State.IDLE -> mirrorDesc.dragging = false
                    else -> Unit
                }
                widget.onDisplayVisibilityChanged(isMirrorWidgetVisible(widget))
                notifyTransientContentChanged()
            }

            override fun onStreamStateChanged(state: ScreenMirrorWidget.StreamState) {
                notifyMediaBrightnessCapChanged()
                notifyContentChanged()
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
        widget.hookupSingleton(
            mirrorDesc,
            onClose = { executeDefinition(commandRegistry.toggleMirror) }
        )
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
                override fun onMenuDismissed() = notifyContentChanged()
            }
        }

        textEditMenu = TextEditContextMenu(w.toFloat(), h.toFloat()).apply {
            commandHandler = object : TextEditCommandHandler {
                override fun cut() = handleCut()
                override fun copy() = handleCopy()
                override fun paste(content: String) = handlePaste(content)
                override fun selectAll() = handleSelectAll()
                override fun showFormatting() = showFormattingMenu()
                override fun onMenuDismissed() = notifyContentChanged()
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
                    focusedWidget?.setColumns(columns)
                    notifyContentChanged()
                }
                override fun onMenuDismissed() = notifyContentChanged()
            }
        }

        closeConfirmationPopup = CloseConfirmationPopup(w.toFloat(), h.toFloat()).apply {
            handler = object : CloseConfirmationHandler {
                override fun confirmClose() { host?.requestClose() }
                override fun onPopupDismissed() = notifyContentChanged()
            }
        }

        layoutDeleteConfirmationPopup = WidgetLayoutDeleteConfirmationPopup(w.toFloat(), h.toFloat()).apply {
            handler = object : LayoutDeletionHandler {
                override fun deleteLayout(name: String) = layoutManager.deleteLayoutByName(name)
                override fun onPopupDismissed() = notifyContentChanged()
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
                override fun submitLayoutName(name: String) = layoutManager.saveLayoutWithName(name)
                override fun onPromptDismissed() {
                    contentObserver?.onFocusChanged(hasFocusedWidget())
                    notifyContentChanged()
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
        notifyContentChanged()
    }

    // ==================== Fullscreen Management ====================

    /** Exits fullscreen for a singleton widget without destroying it (used during teardown). */
    private fun teardownSingleton(desc: SingletonDesc) {
        desc.widget()?.let(::notifyPresentationTargetRemoved)
        if (desc.isFullscreen()) {
            desc.widget()?.let { if (it.isFullscreen) it.toggleFullscreen() }
            desc.setFullscreen(false)
        }
    }

    /** Wires standard singleton behavior plus any widget-specific lifecycle handling. */
    private fun BaseWidget.hookupSingleton(
        desc: SingletonDesc,
        onClose: () -> Unit = {},
        onMinimize: (Boolean) -> Unit = {}
    ) {
        configureLifecycleListener(
            onClose = onClose,
            onFullscreen = { fs -> handleSingletonFullscreenToggle(desc, fs) },
            onMinimize = { min ->
                handleSingletonMinimizeToggle(desc, min)
                onMinimize(min)
            }
        )
    }

    private fun BaseWidget.configureLifecycleListener(
        onClose: () -> Unit = {},
        onFullscreen: (Boolean) -> Unit = {},
        onMinimize: (Boolean) -> Unit = {}
    ) {
        lifecycleListener = object : WidgetLifecycleListener {
            override fun onCloseRequested(widget: BaseWidget) = onClose()
            override fun onFullscreenToggled(widget: BaseWidget, isFullscreen: Boolean) = onFullscreen(isFullscreen)
            override fun onMinimizeToggled(widget: BaseWidget, isMinimized: Boolean) = onMinimize(isMinimized)
        }
    }

    private fun handleSingletonFullscreenToggle(desc: SingletonDesc, isFullscreen: Boolean) {
        if (isFullscreen) {
            exitAllFullscreen()
            desc.setFullscreen(true)
            desc.widget()?.setFullscreenBounds(width.toFloat(), height.toFloat())
            notificationsWidget?.setScreenHeight(height.toFloat())
        } else {
            desc.setFullscreen(false)
        }
        notifyContentChanged()
    }

    private fun handleSingletonMinimizeToggle(desc: SingletonDesc, isMinimized: Boolean) {
        if (isMinimized) {
            if (desc.isFullscreen()) desc.setFullscreen(false)
        } else {
            val w = desc.widget()
            if (w?.isFullscreen == true) {
                exitAllFullscreen()
                desc.setFullscreen(true)
                w.setFullscreenBounds(width.toFloat(), height.toFloat())
            }
        }
        updateMinimizedWidgetPositions()
        notifyContentChanged()
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
        notifyContentChanged()
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
        notifyContentChanged()
    }

    fun updateSpeedData(speedKmh: Float, qualityOk: Boolean, isMoving: Boolean) {
        pendingSpeedKmh = speedKmh
        pendingSpeedQualityOk = qualityOk
        pendingSpeedMoving = isMoving
        val changed = speedometerWidget?.setSpeedData(speedKmh, qualityOk, isMoving) ?: false
        if (changed && displayState != WakeSleepManager.DisplayState.OFF) {
            notifyContentChanged()
        }
    }

    fun setSpeedPermissionDenied() {
        pendingSpeedKmh = 0f
        pendingSpeedQualityOk = false
        pendingSpeedMoving = false
        speedometerWidget?.setPermissionDenied()
        if (displayState != WakeSleepManager.DisplayState.OFF) {
            notifyContentChanged()
        }
    }

    fun applySubtitleStatus(status: SubtitleStatus) {
        subtitleWidget?.applyStatus(status)
        notifyContentChanged()
    }

    fun applySubtitleTranscript(transcript: SubtitleTranscript) {
        subtitleWidget?.applyTranscript(transcript)
        notifyTransientContentChanged()
    }

    fun onSubtitlePhoneDisconnected() {
        subtitleWidget?.onPhoneDisconnected()
        notifyContentChanged()
    }

    fun getSpeedUnit(): SpeedometerWidget.SpeedUnit = unitSettings.effectiveSpeedUnit()

    fun setSpeedVisibilityThreshold(thresholdKmh: Float) {
        val forceVisibleChanged = settingsController.setSpeedVisibilityThreshold(thresholdKmh)
        if (forceVisibleChanged && displayState != WakeSleepManager.DisplayState.OFF) {
            notifyContentChanged()
        }
    }

    fun getSpeedVisibilityThreshold(): Float = settingsController.getSpeedVisibilityThreshold()

    private fun createSpeedometerWidget(
        startX: Float,
        startY: Float,
        widgetWidth: Float,
        widgetHeight: Float
    ): SpeedometerWidget {
        return SpeedometerWidget(startX, startY, widgetWidth, widgetHeight).apply {
            hookupSingleton(speedometerDesc, onClose = { executeDefinition(commandRegistry.toggleSpeedometer) })
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
            hookupSingleton(subtitleDesc, onClose = { executeDefinition(commandRegistry.toggleSubtitle) })
            listener = object : SubtitleWidgetListener {
                override fun onStateChanged() = notifyContentChanged()
                override fun onSubtitleControlRequested(control: SubtitleControl) {
                    host?.requestSubtitleControl(control)
                }
            }
            if (autoStart) {
                setCaptureEnabled(true)
            }
        }
    }

    private fun handleTextWidgetFullscreenToggle(widget: TextBoxWidget, isFullscreen: Boolean) {
        if (isFullscreen) { exitAllFullscreen(); fullscreenTextWidget = widget; widget.setFullscreenBounds(width.toFloat(), height.toFloat()) }
        else { fullscreenTextWidget = null }
        notifyContentChanged()
    }

    private fun handleBrowserWidgetFullscreenToggle(widget: BrowserWidget, isFullscreen: Boolean) {
        if (isFullscreen) { exitAllFullscreen(); fullscreenBrowserWidget = widget; widget.setFullscreenBounds(width.toFloat(), height.toFloat()) }
        else { fullscreenBrowserWidget = null }
        widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
        notifyMediaBrightnessCapChanged()
        notifyContentChanged()
    }

    private fun handleImageWidgetFullscreenToggle(widget: ImageWidget, isFullscreen: Boolean) {
        if (isFullscreen) { exitAllFullscreen(); fullscreenImageWidget = widget; widget.setFullscreenBounds(width.toFloat(), height.toFloat()) }
        else { fullscreenImageWidget = null }
        notifyContentChanged()
    }

    private fun handleVideoWidgetFullscreenToggle(widget: VideoWidget, isFullscreen: Boolean) {
        if (isFullscreen) {
            exitAllFullscreen()
            fullscreenVideoWidget = widget
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
            widget.startPlayback(autoPlay = true)
        } else {
            if (fullscreenVideoWidget == widget) fullscreenVideoWidget = null
        }
        widget.setWindowedMode()
        notifyMediaBrightnessCapChanged()
        notifyContentChanged()
    }

    private fun handlePdfWidgetFullscreenToggle(widget: PdfWidget, isFullscreen: Boolean) {
        if (isFullscreen) {
            exitAllFullscreen()
            fullscreenPdfWidget = widget
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
        } else if (fullscreenPdfWidget == widget) {
            fullscreenPdfWidget = null
        }
        notifyContentChanged()
    }

    private fun handleTextWidgetMinimizeToggle(widget: TextBoxWidget, isMinimized: Boolean) {
        if (isMinimized) {
            if (fullscreenTextWidget == widget) {
                fullscreenTextWidget = null
            }
        } else if (widget.isFullscreen) {
            exitAllFullscreen()
            fullscreenTextWidget = widget
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
        }
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    private fun handleBrowserWidgetMinimizeToggle(widget: BrowserWidget, isMinimized: Boolean) {
        if (isMinimized) {
            if (fullscreenBrowserWidget == widget) {
                fullscreenBrowserWidget = null
            }
        } else if (widget.isFullscreen) {
            exitAllFullscreen()
            fullscreenBrowserWidget = widget
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
        }
        widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    private fun handleImageWidgetMinimizeToggle(widget: ImageWidget, isMinimized: Boolean) {
        if (isMinimized) {
            if (fullscreenImageWidget == widget) {
                fullscreenImageWidget = null
            }
        } else if (widget.isFullscreen) {
            exitAllFullscreen()
            fullscreenImageWidget = widget
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
        }
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    private fun handleVideoWidgetMinimizeToggle(widget: VideoWidget, isMinimized: Boolean) {
        if (isMinimized && fullscreenVideoWidget == widget) {
            fullscreenVideoWidget = null
        } else if (!isMinimized && widget.isFullscreen) {
            exitAllFullscreen()
            fullscreenVideoWidget = widget
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
        }
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    private fun handlePdfWidgetMinimizeToggle(widget: PdfWidget, isMinimized: Boolean) {
        if (isMinimized && fullscreenPdfWidget == widget) fullscreenPdfWidget = null
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    private fun handleFileBrowserWidgetMinimizeToggle(widget: FileBrowserWidget, isMinimized: Boolean) {
        if (isMinimized) {
            if (fullscreenFileBrowserWidget == widget) {
                fullscreenFileBrowserWidget = null
            }
        } else if (widget.isFullscreen) {
            exitAllFullscreen()
            fullscreenFileBrowserWidget = widget
            widget.setFullscreenBounds(width.toFloat(), height.toFloat())
        }
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    private fun exitAllFullscreen() {
        fullscreenTextWidget?.let { if (it.isFullscreen) it.toggleFullscreen() }; fullscreenTextWidget = null
        fullscreenBrowserWidget?.let { if (it.isFullscreen) it.toggleFullscreen() }; fullscreenBrowserWidget = null
        fullscreenImageWidget?.let { if (it.isFullscreen) it.toggleFullscreen() }; fullscreenImageWidget = null
        fullscreenVideoWidget?.let { if (it.isFullscreen) it.toggleFullscreen() }; fullscreenVideoWidget = null
        fullscreenPdfWidget?.let { if (it.isFullscreen) it.toggleFullscreen() }; fullscreenPdfWidget = null
        fullscreenFileBrowserWidget?.let { if (it.isFullscreen) it.toggleFullscreen() }; fullscreenFileBrowserWidget = null
        for (desc in singletonDescs) {
            if (desc.isFullscreen()) { desc.widget()?.let { if (it.isFullscreen) it.toggleFullscreen() }; desc.setFullscreen(false) }
        }
    }

    private fun updateFullscreenBounds() {
        if (width <= 0 || height <= 0) return
        notificationsWidget?.setScreenHeight(height.toFloat())
        fullscreenTextWidget?.setFullscreenBounds(width.toFloat(), height.toFloat())
        fullscreenBrowserWidget?.setFullscreenBounds(width.toFloat(), height.toFloat())
        fullscreenImageWidget?.setFullscreenBounds(width.toFloat(), height.toFloat())
        fullscreenVideoWidget?.setFullscreenBounds(width.toFloat(), height.toFloat())
        fullscreenPdfWidget?.setFullscreenBounds(width.toFloat(), height.toFloat())
        fullscreenFileBrowserWidget?.setFullscreenBounds(width.toFloat(), height.toFloat())
        for (desc in singletonDescs) {
            if (desc.isFullscreen()) desc.widget()?.setFullscreenBounds(width.toFloat(), height.toFloat())
        }
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
        calendarWidget?.setSnapshot(snapshot)
        notifyContentChanged()
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

    private fun applyEffectiveUnitSettings(notify: Boolean = true) {
        val speedUnit = unitSettings.effectiveSpeedUnit()
        speedometerWidget?.setUnit(speedUnit)
        settingsController.setSpeedUnit(speedUnit)
        settingsController.setUnitSystemMode(unitSettings.globalMode)
        locationWidget?.setTemperatureUnit(unitSettings.effectiveTemperatureUnit())
        renderLocationWeather()
        if (notify) notifyContentChanged()
    }

    fun applyFinanceSnapshot(snapshot: FinanceSnapshot, notify: Boolean = true) {
        financeWidget?.applySnapshot(snapshot)
        if (notify) notifyContentChanged()
    }

    fun applyFinanceError(message: String) {
        financeWidget?.applyError(message)
        notifyContentChanged()
    }

    fun applyNewsSnapshot(snapshot: NewsSnapshot) {
        newsWidget?.applySnapshot(snapshot)
        notifyContentChanged()
    }

    fun applyNotificationsSnapshot(snapshot: PhoneNotificationsSnapshot) {
        latestNotificationsSnapshot = snapshot
        notificationsWidget?.setNotificationsSnapshot(snapshot)
        notifyContentChanged()
    }

    fun applyNewsError(message: String) {
        newsWidget?.applyError(message)
        notifyContentChanged()
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
                        notifyContentChanged()
                    },
                    onFailure = { failure ->
                        result.complete(
                            NewsSourceUpdateResult(
                                applied = false,
                                message = failure.message ?: "News source could not be loaded"
                            )
                        )
                        notifyContentChanged()
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

    fun currentFinanceSemanticState(): FinanceWidgetSemanticState? =
        financeWidget?.let { widget ->
            widget.semanticState(widgetStableId(widget))
        }

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

            val update = runCatching {
                widget.updateTileInstrument(
                    target = request.target,
                    instrument = request.instrument,
                    range = request.range,
                    chartType = request.chartType,
                    slot = request.slot
                )
            }.getOrElse { failure ->
                Result.failure(failure)
            }
            update.fold(
                onSuccess = { config ->
                    if (request.fullscreen != null && request.fullscreen != widget.isFullscreen) {
                        widget.toggleFullscreen()
                    }
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

    private fun bringWidgetToFront(widget: BaseWidget) {
        registry.bringToFront(widget, parent as? ViewGroup, this)
        notifyContentChanged()
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
        singletonDescs.forEach { it.pendingDrag = false }
        pendingFormattingMenuDrag = false
        formattingMenu?.cancelLongPress()
    }

    fun onCursorDown(x: Float, y: Float, pointerCount: Int = 1) {
        cursorPressed = true; currentPointerCount = pointerCount; isTwoFingerScrollMode = pointerCount >= 2
        cursorX = x; cursorY = y; lastCursorX = x; lastCursorY = y
        cursorStabilizer.reset()
        longPressStartX = x; longPressStartY = y; selectionStartX = x; accumulatedSelectionDx = 0f
        cancelLongPress()
        isTextSelectionActive = false; isLongPressSelectionMode = false; pendingTextSelection = false

        if (isTwoFingerScrollMode) return

        layoutNamePrompt?.let { prompt ->
            if (prompt.isVisible) {
                notifyContentChanged()
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
            notifyContentChanged()
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
        focusedWidget?.let { widget -> if (widget.isFocused() && widget.containsPoint(cx, cy)) { bringWidgetToFront(widget); if (widget.hitTest(cx, cy) == TextBoxWidget.HitArea.CONTENT) { pendingTextSelection = true; scheduleLongPress(); return } } }
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
        bringWidgetToFront(widget)

        // Singleton widgets carry their own drag bookkeeping via SingletonDesc.
        singletonDescs.firstOrNull { it.widget() === widget }?.let { desc ->
            val hit = desc.dragHit(cx, cy)
            if (hit != DragHit.NONE) {
                desc.pendingDrag = true
                pendingDragIsResize = hit == DragHit.RESIZE
                scheduleLongPress()
            }
            return
        }

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
            else -> when (widget.baseHitTest(cx, cy)) {
                BaseWidget.BaseHitArea.BORDER, BaseWidget.BaseHitArea.CONTENT ->
                    beginPendingWidgetDrag(widget, isResize = false)
                BaseWidget.BaseHitArea.RESIZE_HANDLE ->
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
            if (pendingFormattingMenuDrag) { formattingMenu?.let { if (it.onLongPressTriggered()) { } }; pendingFormattingMenuDrag = false; notifyContentChanged(); return@Runnable }
            if (pendingTextSelection) { val widget = focusedWidget ?: fullscreenTextWidget; widget?.let { if (it.isFocused()) { val (cx, cy) = screenToContentCoordinates(longPressStartX, longPressStartY); it.setCursorFromScreenPosition(cx, cy); it.resetPixelAccumulator(); it.startSelection(); isLongPressSelectionMode = true; isTextSelectionActive = false } }; pendingTextSelection = false; notifyContentChanged(); return@Runnable }
            if (isWidgetLayoutLocked && hasPendingWidgetDrag()) {
                clearPendingWidgetDrag()
                notifyContentChanged()
                return@Runnable
            }
            if (pendingDragFinanceTile) {
                financeWidget?.let {
                    val (cx, cy) = screenToContentCoordinates(longPressStartX, longPressStartY)
                    if (it.startTileDrag(cx, cy)) financeTileDragging = true
                }
                pendingDragFinanceTile = false
                notifyContentChanged()
                return@Runnable
            }
            for (desc in singletonDescs) {
                if (!desc.pendingDrag) continue
                desc.widget()?.let { it.startDrag(pendingDragIsResize); desc.dragging = true }
                desc.pendingDrag = false; pendingDragIsResize = false; notifyContentChanged(); return@Runnable
            }
            pendingDragWidget?.let { widget -> widget.startDrag(pendingDragIsResize); activeWidget = widget; notifyContentChanged() }
            pendingDragWidget = null; pendingDragIsResize = false; longPressRunnable = null
        }
        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DELAY_MS)
    }

    private fun hasPendingWidgetDrag(): Boolean =
        pendingDragWidget != null ||
                pendingDragFinanceTile ||
                singletonDescs.any { it.pendingDrag }

    private fun clearPendingWidgetDrag() {
        pendingDragWidget = null
        pendingDragFinanceTile = false
        singletonDescs.forEach { it.pendingDrag = false }
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
        if (financeTileDragging) { financeWidget?.endTileDrag(); financeTileDragging = false; notifyContentChanged() }
        singletonDescs.forEach { desc -> if (desc.dragging) { desc.widget()?.onDragEnd(); desc.dragging = false; notifyContentChanged() } }
        if (activeWidget != null) { activeWidget?.onDragEnd(); activeWidget = null; notifyContentChanged() }
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
            singletonDescs.any { it.dragging } || activeWidget != null || isScrollbarDragActive
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
                notifyContentChanged() 
            } 
        }
        // Menus use screen-space (head-locked)
        formattingMenu?.let { menu -> if (menu.isDragging()) { menu.onDrag(x, y); notifyContentChanged(); return }; if (menu.checkLongPressCancellation(x, y, LONG_PRESS_MAX_MOVEMENT)) { } }
        // Long-press cancellation uses screen-space (cursor movement)
        if (longPressRunnable != null) { val totalMovement = kotlin.math.sqrt((x - longPressStartX) * (x - longPressStartX) + (y - longPressStartY) * (y - longPressStartY)); if (totalMovement > LONG_PRESS_MAX_MOVEMENT) cancelLongPress() }
        // Widget dragging uses delta values (works in both spaces)
        if (financeTileDragging) {
            val (cx, cy) = toContentSpace(x, y)
            financeWidget?.onTileDrag(cx, cy)
            notifyContentChanged()
            return
        }
        for (desc in singletonDescs) {
            if (desc.dragging) { desc.widget()?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
        }
        if (activeWidget != null) { activeWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
        if (isScrollbarDragActive && scrollbarDragWidget != null) { scrollbarDragWidget?.onScrollbarDrag(dy); notifyContentChanged(); return }
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
        for (widget in widgets) { if (widget.state == TextBoxWidget.State.HOVER_CONTENT || widget.state == TextBoxWidget.State.EDITING) { widget.onScroll(dy); notifyContentChanged(); break } }
        fullscreenTextWidget?.let { widget -> if (widget.state == TextBoxWidget.State.HOVER_CONTENT || widget.state == TextBoxWidget.State.EDITING) { widget.onScroll(dy); notifyContentChanged() } }
    }

    fun scrollWidgetAtCursor(cursorX: Float, cursorY: Float, dy: Float, userInitiated: Boolean = false) {
        if (userInitiated) userInputActivity?.onUserInput(UserInputKind.SCROLL)
        contextMenu?.let {
            if (it.isVisible) {
                it.onScroll(dy)
                notifyContentChanged()
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
                notifyContentChanged()
                return
            }
        }
        calendarWidget?.let { if (it.containsPoint(cx, cy) && !it.isMinimized) { it.onScroll(dy); notifyContentChanged(); return } }
        financeWidget?.let { if (it.containsPoint(cx, cy) && !it.isMinimized) { it.onScroll(dy); notifyContentChanged(); return } }
        newsWidget?.let { if (it.containsPoint(cx, cy) && !it.isMinimized) { it.onScroll(dy); notifyContentChanged(); return } }
        notificationsWidget?.let { if (it.containsPoint(cx, cy) && !it.isMinimized) { it.onScroll(dy); notifyContentChanged(); return } }
        for (widget in browserWidgets.reversed()) {
            val hit = widget.hitTest(cx, cy)
            if (!widget.isMinimized && (hit == BrowserWidget.HitArea.CONTENT || hit == BrowserWidget.HitArea.NAV_URL)) {
                widget.onScroll(dy)
                notifyContentChanged()
                return
            }
        }
        for (widget in imageWidgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
        for (widget in pdfWidgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
        for (widget in fileBrowserWidgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
        for (widget in widgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
    }

    private fun scrollFullscreenWidgetAt(cx: Float, cy: Float, dy: Float): Boolean {
        if (isMapWidgetFullscreen) {
            mapWidget?.let {
                if ((it.isPoiConfigVisible() || it.navigationState.choices.isNotEmpty()) && it.containsPoint(cx, cy)) {
                    it.onScroll(dy)
                    notifyContentChanged()
                    return true
                }
            }
        }
        fullscreenTextWidget?.let {
            if (it.containsPoint(cx, cy)) {
                it.onScroll(dy)
                notifyContentChanged()
                return true
            }
        }
        fullscreenBrowserWidget?.let {
            val hit = it.hitTest(cx, cy)
            if (hit == BrowserWidget.HitArea.CONTENT || hit == BrowserWidget.HitArea.NAV_URL) {
                it.onScroll(dy)
                notifyContentChanged()
                return true
            }
        }
        fullscreenPdfWidget?.let {
            if (it.containsPoint(cx, cy)) {
                it.onScroll(dy)
                notifyContentChanged()
                return true
            }
        }
        fullscreenFileBrowserWidget?.let {
            if (it.containsPoint(cx, cy)) {
                it.onScroll(dy)
                notifyContentChanged()
                return true
            }
        }
        if (isCalendarWidgetFullscreen) {
            calendarWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    it.onScroll(dy)
                    notifyContentChanged()
                    return true
                }
            }
        }
        if (isFinanceWidgetFullscreen) {
            financeWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    if (it.onScrollAt(cx, cy, dy)) notifyContentChanged()
                    return true
                }
            }
        }
        if (isNewsWidgetFullscreen) {
            newsWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    it.onScroll(dy)
                    notifyContentChanged()
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
            val handled = layoutNamePrompt?.onTap(x, y) ?: false
            notifyContentChanged()
            return handled
        }
        if (layoutDeleteConfirmationPopup?.isVisible == true) {
            val handled = layoutDeleteConfirmationPopup?.onTap(x, y) ?: false
            notifyContentChanged()
            return handled
        }
        if (closeConfirmationPopup?.isVisible == true) return closeConfirmationPopup?.onTap(x, y) ?: false
        settingsController.handleHoverControlsLayoutEditorTap(x, y)?.let { return it }
        settingsController.handleShortcutsSettingsMenuTap(x, y)?.let { return it }
        if (formattingMenu?.isVisible == true) return formattingMenu?.onTap(x, y) ?: false
        if (textEditMenu?.isVisible == true) return textEditMenu?.onTap(x, y) ?: false
        if (contextMenu?.isVisible == true) return contextMenu?.onTap(x, y) ?: false
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
            if (mapWidget?.onTap(cx, cy) == true) {
                notifyContentChanged()
                return true
            }
        }

        // POI settings are a screen-level modal when open, including in fullscreen map mode.
        if (mapWidget?.isPoiConfigVisible() == true) {
            if (mapWidget?.onTap(cx, cy) == true) {
                notifyContentChanged()
                return true
            }
        }

        statusBarWidget?.let {
            if (it.handleToggleMenuTapOrDismiss(cx, cy)) {
                notifyContentChanged()
                return true
            }
        }

        locationWidget?.let {
            if (it.handleToggleMenuTapOrDismiss(cx, cy)) {
                notifyContentChanged()
                return true
            }
        }

        calendarWidget?.let {
            if (it.handleFontMenuTapOrDismiss(cx, cy)) {
                notifyContentChanged()
                return true
            }
        }

        newsWidget?.let {
            if (it.handleFontMenuTapOrDismiss(cx, cy)) {
                notifyContentChanged()
                return true
            }
        }

        notificationsWidget?.let {
            if (it.handleFontMenuTapOrDismiss(cx, cy)) {
                notifyContentChanged()
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
                    val result = it.onTap(cx, cy)
                    if (it.isEditingAddress()) ensureDrawnAboveNativeOverlays()
                    return result
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
            if (target.isMinimized) { target.toggleMinimize(); notifyContentChanged(); return true }
            return dispatchTapToWidget(target, cx, cy)
        }

        if (hasFocusedWidget()) { unfocusAll(); notifyContentChanged(); return true }
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
                val result = widget.onTap(cx, cy)
                if (widget.isEditingAddress()) ensureDrawnAboveNativeOverlays()
                result
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
        BaseWidget.BaseHitArea.CLOSE_BUTTON      -> { widget.lifecycleListener?.onCloseRequested(widget); notifyContentChanged(); true }
        BaseWidget.BaseHitArea.FULLSCREEN_BUTTON -> { widget.toggleFullscreen(); notifyContentChanged(); true }
        BaseWidget.BaseHitArea.MINIMIZE_BUTTON   -> { widget.toggleMinimize(); notifyContentChanged(); true }
        BaseWidget.BaseHitArea.PIN_BUTTON        -> { widget.isPinned = !widget.isPinned; notifyContentChanged(); true }
        else                                     -> onElse?.invoke(hit) ?: true
    }

    private fun handleTextWidgetTap(widget: TextBoxWidget, x: Float, y: Float): Boolean =
        when (widget.hitTest(x, y)) {
            TextBoxWidget.HitArea.KEYBOARD_BUTTON -> { widget.toggleKeyboard(); notifyContentChanged(); true }
            TextBoxWidget.HitArea.KEYBOARD_KEY    -> { widget.getKeyboard()?.onTap(x, y); notifyContentChanged(); true }
            else -> handleBaseTap(widget, x, y) { hit ->
                if (hit == BaseWidget.BaseHitArea.CONTENT) {
                    widget.setFocused(true); setFocusedWidget(widget)
                    widget.setCursorFromScreenPosition(x, y); widget.clearSelection()
                    notifyContentChanged()
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

    private fun handlePdfWidgetTap(widget: PdfWidget, x: Float, y: Float): Boolean =
        handleBaseTap(widget, x, y) { hit ->
            if (hit == BaseWidget.BaseHitArea.CONTENT) widget.handleContentTap(x, y) else true
        }

    private fun handleFileBrowserWidgetTap(widget: FileBrowserWidget, x: Float, y: Float): Boolean {
        bringWidgetToFront(widget)
        val result = widget.onTap(x, y)
        notifyContentChanged()
        return result
    }

    private fun handleStatusBarTap(statusBar: StatusBarWidget, x: Float, y: Float): Boolean =
        handleBaseTap(statusBar, x, y)

    private fun handleMirrorWidgetTap(mirror: ScreenMirrorWidget, x: Float, y: Float): Boolean =
        handleBaseTap(mirror, x, y) {
            if (mirror.hitTest(x, y) == ScreenMirrorWidget.HitArea.RUN_PAUSE_BUTTON) mirror.onTap(x, y) else true
        }

    private fun handleLocationWidgetTap(loc: LocationWidget, x: Float, y: Float): Boolean {
        if (loc.onUnitTap(x, y)) {
            notifyContentChanged()
            return true
        }
        return handleBaseTap(loc, x, y)
    }

    private fun handleMapWidgetTap(map: MapWidget, x: Float, y: Float): Boolean {
        if (map.onTap(x, y)) { notifyContentChanged(); return true }
        return handleBaseTap(map, x, y)
    }

    private fun handleCalendarWidgetTap(widget: CalendarWidget, x: Float, y: Float): Boolean {
        if (widget.onTap(x, y)) { notifyContentChanged(); return true }
        return handleBaseTap(widget, x, y)
    }

    private fun handleFinanceWidgetTap(fw: FinanceWidget, x: Float, y: Float): Boolean {
        // Let the finance widget handle internal taps first (dropdown, time range)
        if (fw.onTap(x, y)) { notifyContentChanged(); return true }
        return handleBaseTap(fw, x, y)
    }

    private fun handleNewsWidgetTap(nw: NewsWidget, x: Float, y: Float): Boolean {
        // Let the news widget handle arrows/content tap first.
        if (nw.onTap(x, y)) { notifyContentChanged(); return true }
        return handleBaseTap(nw, x, y)
    }

    private fun handleNotificationsWidgetTap(widget: NotificationsWidget, x: Float, y: Float): Boolean {
        if (widget.onTap(x, y)) { notifyContentChanged(); return true }
        return handleBaseTap(widget, x, y)
    }

    private fun handleSpeedometerWidgetTap(sw: SpeedometerWidget, x: Float, y: Float): Boolean {
        if (sw.onTap(x, y)) { notifyContentChanged(); return true }
        return handleBaseTap(sw, x, y)
    }

    private fun handleSubtitleWidgetTap(widget: SubtitleWidget, x: Float, y: Float): Boolean {
        if (widget.onTap(x, y)) { notifyContentChanged(); return true }
        return handleBaseTap(widget, x, y)
    }

    fun onDoubleTap(x: Float, y: Float): Boolean {
        if (closeConfirmationPopup?.isVisible != true) {
            settingsController.handleHoverControlsLayoutEditorDoubleTap(x, y)?.let { return it }
        }

        if (contextMenu?.isVisible == true) {
            val handled = contextMenu?.onDoubleTap(x, y) ?: false
            if (handled) notifyContentChanged()
            return handled
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
            if (mapWidget?.onDoubleTap(cx, cy) == true) {
                notifyContentChanged()
                return true
            }
        }

        fullscreenVideoWidget?.let { widget ->
            if (widget.containsPoint(cx, cy)) {
                widget.handleContentDoubleTap(cx, cy)
                notifyContentChanged()
                return true
            }
            return false
        }

        if (!hasFullscreenWidget() && isAppContextMenuBorderDoubleTap(cx, cy)) {
            showAppContextMenu(x, y, cx, cy)
            return true
        }

        if (isStatusBarFullscreen) {
            statusBarWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }
            return false
        }

        if (isLocationWidgetFullscreen) {
            locationWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }
            return false
        }

        if (isMapWidgetFullscreen) {
            mapWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }
            return false
        }

        if (isCalendarWidgetFullscreen) {
            calendarWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }
            return false
        }

        if (isFinanceWidgetFullscreen) {
            financeWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }
            return false
        }

        if (isNewsWidgetFullscreen) {
            newsWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }
            return false
        }

        if (isNotificationsWidgetFullscreen) {
            notificationsWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }
            return false
        }

        if (isSubtitleWidgetFullscreen) {
            subtitleWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }
            return false
        }

        if (!hasFullscreenWidget()) {
            statusBarWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }

            locationWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }

            mapWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }

            calendarWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }

            newsWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }

            notificationsWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }

            financeWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }

            subtitleWidget?.let {
                if (it.containsPoint(cx, cy) && it.onDoubleTap(cx, cy)) {
                    notifyContentChanged()
                    return true
                }
            }

            for (widget in videoWidgets.asReversed()) {
                if (!widget.containsPoint(cx, cy)) continue
                if (widget.baseHitTest(cx, cy) == BaseWidget.BaseHitArea.CONTENT) {
                    widget.handleContentDoubleTap(cx, cy)
                    notifyContentChanged()
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
        if (hasFullscreenWidget() && fullscreenTextWidget == null) return false
        if (hasFullscreenWidget()) return false
        for (widget in widgets) { if (widget.containsPoint(cx, cy)) return false }
        for (widget in browserWidgets) { if (widget.containsPoint(cx, cy)) return false }
        screenMirrorWidget?.let { if (it.containsPoint(cx, cy)) return false }
        statusBarWidget?.let { if (it.containsPoint(cx, cy)) return false }
        calendarWidget?.let { if (it.containsPoint(cx, cy)) return false }
        locationWidget?.let { if (it.containsPoint(cx, cy)) return false }
        financeWidget?.let { if (it.containsPoint(cx, cy)) return false }
        newsWidget?.let { if (it.containsPoint(cx, cy)) return false }
        notificationsWidget?.let { if (it.containsPoint(cx, cy)) return false }
        subtitleWidget?.let { if (it.containsPoint(cx, cy)) return false }
        speedometerWidget?.let {
            if (it.containsPoint(cx, cy)) {
                settingsController.showSpeedometerSettingsMenu()
                return true
            }
        }

        showAppContextMenu(x, y, cx, cy)
        return true
    }

    fun onContextMenuNavigationTap(x: Float, y: Float): Boolean {
        val handled = contextMenu?.onNavigationTap(x, y) ?: false
        if (handled) notifyContentChanged()
        return handled
    }

    private fun showAppContextMenu(screenX: Float, screenY: Float, contentX: Float, contentY: Float) {
        pendingCreateX = contentX
        pendingCreateY = contentY
        contextMenu?.setLayoutLockState(isWidgetLayoutLocked)
        contextMenu?.show(screenX, screenY)
        notifyContentChanged()
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

    fun onDragEnd() {
        cancelLongPress()
        if (financeTileDragging) { financeWidget?.endTileDrag(); financeTileDragging = false; notifyContentChanged() }
        singletonDescs.forEach { desc -> if (desc.dragging) { desc.widget()?.onDragEnd(); desc.dragging = false; notifyContentChanged() } }
        if (activeWidget != null) { activeWidget?.onDragEnd(); activeWidget = null; notifyContentChanged() }
    }

    // ==================== Widget Creation ====================

    private fun handleMenuItemSelected(item: ContextMenu.MenuItem) {
        userInputActivity?.onUserInput(UserInputKind.SHORTCUT)
        commandRegistry.find(item.id)?.let { executeCommand(commandRegistry.invocation(it)) }
    }

    /**
     * Set the brightness value programmatically (used for restoring persisted state).
     */
    fun setBrightness(value: Float) {
        settingsController.setBrightness(value)
    }

    /**
     * Get the current brightness value (used for persistence).
     */
    fun getBrightness(): Float = settingsController.getBrightness()

    fun setAdaptiveBrightnessEnabled(enabled: Boolean) {
        settingsController.setAdaptiveBrightnessEnabled(enabled)
    }

    fun getAdaptiveBrightnessEnabled(): Boolean = settingsController.getAdaptiveBrightnessEnabled()

    /**
     * Set the head-up time programmatically (used for restoring persisted state).
     */
    fun setHeadUpTime(ms: Long) {
        settingsController.setHeadUpTime(ms)
    }

    /**
     * Get the current head-up time (used for persistence).
     */
    fun getHeadUpTime(): Long = settingsController.getHeadUpTime()

    /**
     * Set the wake duration programmatically (used for restoring persisted state).
     */
    fun setWakeDuration(ms: Long) {
        settingsController.setWakeDuration(ms)
    }

    /**
     * Get the current wake duration (used for persistence).
     */
    fun getWakeDuration(): Long = settingsController.getWakeDuration()

    /**
     * Set the angle threshold programmatically (used for restoring persisted state).
     */
    fun setAngleThreshold(degrees: Float) {
        settingsController.setAngleThreshold(degrees)
    }

    /**
     * Get the current angle threshold (used for persistence).
     */
    fun getAngleThreshold(): Float = settingsController.getAngleThreshold()

    /**
     * Set the heads-up enabled state programmatically (used for restoring persisted state).
     */
    fun setHeadsUpEnabled(enabled: Boolean) {
        settingsController.setHeadsUpEnabled(enabled)
    }

    /**
     * Get the current heads-up enabled state (used for persistence).
     */
    fun getHeadsUpEnabled(): Boolean = settingsController.getHeadsUpEnabled()

    fun setGoogleAuthState(state: GoogleAuthState) {
        settingsController.setGoogleAuthState(state)
    }

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

    fun isSmartAlignmentEnabled(): Boolean = smartAlignment.enabled

    fun setWidgetLayoutLocked(locked: Boolean, persist: Boolean = true) {
        if (isWidgetLayoutLocked == locked) {
            contextMenu?.setLayoutLockState(isWidgetLayoutLocked)
            return
        }
        isWidgetLayoutLocked = locked
        contextMenu?.setLayoutLockState(isWidgetLayoutLocked)
        if (locked) {
            cancelLongPress()
            onDragEnd()
        }
        if (persist) notifyContentChanged()
    }

    private fun handleSubmenuItemDoubleTapped(subItem: ContextMenu.SubMenuItem): Boolean {
        val definition = commandRegistry.find(subItem.id) ?: return false
        val name = commandRegistry.layoutName(definition) ?: return false
        return layoutManager.requestDeleteLayout(name)
    }

    /**
     * Toggle StatusBarWidget: remove if present, create at default location if absent.
     */
    private fun mutateStatusBarPresence() {
        if (statusBarWidget != null) {
            statusBarWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    status = persistenceHelper.getStatusBarState(it)
                )
            }
            teardownSingleton(statusBarDesc)
            statusBarWidget = null
            Log.d(TAG, "StatusBarWidget removed")
        } else {
            closedWidgetTemplates.status?.let {
                applyStatusBarState(it)
                Log.d(TAG, "StatusBarWidget restored from last closed state")
                return
            }
            // Create at default location
            val statusWidth = 220f
            val statusHeight = 110f
            statusBarWidget = StatusBarWidget(
                context, (width - statusWidth) / 2f, height - statusHeight - height * 0.15f, statusWidth, statusHeight
            ).apply {
                onStateChanged = { state ->
                    if (state == StatusBarWidget.State.MOVING) statusBarDesc.dragging = true
                    else if (statusBarDesc.dragging && state == StatusBarWidget.State.IDLE) statusBarDesc.dragging = false
                    notifyContentChanged()
                }
                hookupSingleton(statusBarDesc, onClose = { executeDefinition(commandRegistry.toggleStatus) })
            }
            Log.d(TAG, "StatusBarWidget created")
        }
    }

    /**
     * Toggle LocationWidget: remove if present, create at default location if absent.
     */
    private fun mutateLocationPresence() {
        if (locationWidget != null) {
            locationWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    location = persistenceHelper.getLocationWidgetState(it)
                )
            }
            teardownSingleton(locationDesc)
            locationWidget = null
            Log.d(TAG, "LocationWidget removed")
        } else {
            closedWidgetTemplates.location?.let {
                applyLocationState(it)
                Log.d(TAG, "LocationWidget restored from last closed state")
                return
            }
            // Create at default location
            val locWidth = 300f
            val locHeight = 120f
            locationWidget = LocationWidget(context, 20f, height - locHeight - 20f, locWidth, locHeight).apply {
                hookupSingleton(locationDesc, onClose = { executeDefinition(commandRegistry.toggleLocation) })
                onTemperatureUnitChanged = { unitSettings.toggleTemperatureOverride() }
                setTemperatureUnit(unitSettings.effectiveTemperatureUnit())
                if (pendingLocationAddress != null || pendingLocationWeather != null) {
                    setLocationData(pendingLocationAddress ?: "Loading...", pendingLocationWeather ?: "Checking weather...")
                }
                setSunTimes(pendingLocationSunriseEpochMs, pendingLocationSunsetEpochMs)
            }
            Log.d(TAG, "LocationWidget created")
        }
    }

    private fun mutateMapPresence() {
        if (mapWidget != null) {
            mapWidget?.let { closedWidgetTemplates = closedWidgetTemplates.copy(map = persistenceHelper.getMapWidgetState(it)) }
            mapWidget?.release()
            teardownSingleton(mapDesc)
            mapWidget = null
        } else {
            closedWidgetTemplates.map?.let { applyMapState(it); return }
            val size = 210f
            mapWidget = MapWidget(context, width - size - 24f, 24f, size, size).apply {
                invalidate = { notifyContentChanged() }
                configurePhoneRouteTransport(phoneRouteTransport)
                onPhoneRouteConnectionChanged(phoneRouteConnected)
                bindMapNavigation(this)
                hookupSingleton(mapDesc, onClose = { executeDefinition(commandRegistry.toggleMap) })
                pendingMapLocation?.let(::setLocation)
                setLocationMetadata(pendingMapLocationLabel, pendingMapCountryCode)
            }
        }
        notifyContentChanged()
    }

    fun updateMapLocation(location: android.location.Location) {
        pendingMapLocation = android.location.Location(location)
        mapWidget?.setLocation(location)
    }

    fun requestPhoneMapRefresh() {
        mapWidget?.requestPhoneMapRefresh()
    }

    fun applyMapSnapshot(snapshot: com.everyday.shared.sync.MapSnapshot) {
        mapWidget?.applyProxyMap(snapshot)
        notifyContentChanged()
    }

    private fun updateMapMetadata(location: String, countryCode: String) {
        pendingMapLocationLabel = location
        pendingMapCountryCode = countryCode
        mapWidget?.setLocationMetadata(location, countryCode)
    }

    /**
     * Toggle CalendarWidget: remove if present, create at default location if absent.
     */
    private fun mutateCalendarPresence() {
        if (calendarWidget != null) {
            calendarWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    calendar = persistenceHelper.getCalendarWidgetState(it)
                )
            }
            teardownSingleton(calendarDesc)
            calendarWidget = null
            Log.d(TAG, "CalendarWidget removed")
        } else {
            closedWidgetTemplates.calendar?.let {
                applyCalendarState(it)
                Log.d(TAG, "CalendarWidget restored from last closed state")
                return
            }
            val startX = (width - CalendarWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
            val startY = (height - CalendarWidget.DEFAULT_HEIGHT - 160f).coerceAtLeast(20f)
            calendarWidget = CalendarWidget(
                startX,
                startY,
                CalendarWidget.DEFAULT_WIDTH,
                CalendarWidget.DEFAULT_HEIGHT
            ).apply {
                hookupSingleton(calendarDesc, onClose = { executeDefinition(commandRegistry.toggleCalendar) })
                onStateChanged = { notifyContentChanged() }
                setSnapshot(pendingCalendarSnapshot)
            }
            Log.d(TAG, "CalendarWidget created")
        }
    }

    /**
     * Toggle SpeedometerWidget: remove if present, create at default location if absent.
     */
    private fun mutateSpeedometerPresence() {
        if (speedometerWidget != null) {
            speedometerWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    speedometer = persistenceHelper.getSpeedometerWidgetState(it)
                )
            }
            teardownSingleton(speedometerDesc)
            settingsController.dismissSpeedometerSettingsMenu()
            speedometerWidget = null
            Log.d(TAG, "SpeedometerWidget removed")
        } else {
            closedWidgetTemplates.speedometer?.let {
                applySpeedometerState(it)
                Log.d(TAG, "SpeedometerWidget restored from last closed state")
                return
            }
            val speedWidth = 220f
            val speedHeight = 110f
            val startX = (width - speedWidth - 20f).coerceAtLeast(20f)
            val startY = (height - speedHeight - 20f).coerceAtLeast(20f)
            speedometerWidget = createSpeedometerWidget(startX, startY, speedWidth, speedHeight)
            Log.d(TAG, "SpeedometerWidget created")
        }
    }

    /**
     * Toggle FinanceWidget: remove if present, create at default location if absent.
     */
    private fun mutateFinancePresence() {
        if (financeWidget != null) {
            financeWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    finance = persistenceHelper.getFinanceWidgetState(it)
                )
            }
            teardownSingleton(financeDesc)
            dataProvider?.onFinanceVisibilityChanged(false)
            financeWidget?.onDestroy()
            financeWidget = null
            Log.d(TAG, "FinanceWidget removed")
        } else {
            closedWidgetTemplates.finance?.let {
                applyFinanceState(it)
                Log.d(TAG, "FinanceWidget restored from last closed state")
                return
            }
            val fWidth = FinanceWidget.DEFAULT_WIDTH
            val fHeight = FinanceWidget.DEFAULT_HEIGHT
            financeWidget = FinanceWidget(20f, 20f, fWidth, fHeight).apply {
                hookupSingleton(
                    financeDesc,
                    onClose = { executeDefinition(commandRegistry.toggleFinance) },
                    onMinimize = { isMinimized ->
                        dataProvider?.onFinanceVisibilityChanged(!isMinimized)
                        if (!isMinimized) requestData(force = false, reason = "finance_unminimized")
                    }
                )
                listener = object : FinanceWidgetListener {
                    override fun onStateChanged() = notifyContentChanged()
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
            Log.d(TAG, "FinanceWidget created")
        }
    }

    /**
     * Toggle NewsWidget: remove if present, create at default location if absent.
     */
    private fun mutateNewsPresence() {
        if (newsWidget != null) {
            newsWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    news = persistenceHelper.getNewsWidgetState(it)
                )
            }
            teardownSingleton(newsDesc)
            newsWidget?.onDestroy()
            newsWidget = null
            Log.d(TAG, "NewsWidget removed")
        } else {
            closedWidgetTemplates.news?.let {
                applyNewsState(it)
                Log.d(TAG, "NewsWidget restored from last closed state")
                return
            }
            val nWidth = NewsWidget.DEFAULT_WIDTH
            val nHeight = NewsWidget.DEFAULT_HEIGHT
            val startX = (width - nWidth - 20f).coerceAtLeast(20f)
            val startY = 20f

            newsWidget = NewsWidget(startX, startY, nWidth, nHeight).apply {
                hookupSingleton(newsDesc, onClose = { executeDefinition(commandRegistry.toggleNews) })
                listener = object : NewsWidgetListener {
                    override fun onStateChanged() = notifyContentChanged()
                    override fun onDataRequested(countryCode: String, force: Boolean, reason: String) {
                        dataProvider?.requestNewsData(countryCode, force, reason)
                    }
                    override fun onSourceDataRequested(sourceSpec: NewsSourceSpec, countryCode: String, force: Boolean, reason: String, completion: (Result<NewsSnapshot>) -> Unit) {
                        dataProvider?.requestNewsSourceData(sourceSpec, countryCode, force, reason, completion)
                            ?: completion(Result.failure(IllegalStateException("News data service is unavailable")))
                    }
                    override fun onSourceInputFocusChanged(focused: Boolean) { contentObserver?.onFocusChanged(focused) }
                }
                pendingNewsCountryCode?.let { setCountryCode(it) }
                requestData(force = true, reason = "widget_toggled_on")
            }
            Log.d(TAG, "NewsWidget created")
        }
    }

    private fun mutateNotificationsPresence() {
        if (notificationsWidget != null) {
            notificationsWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    notifications = persistenceHelper.getNotificationsWidgetState(it)
                )
            }
            teardownSingleton(notificationsDesc)
            notificationsWidget = null
            Log.d(TAG, "NotificationsWidget removed")
        } else {
            closedWidgetTemplates.notifications?.let {
                applyNotificationsState(it)
                Log.d(TAG, "NotificationsWidget restored from last closed state")
                return
            }
            val nWidth = NotificationsWidget.DEFAULT_WIDTH
            val nHeight = NotificationsWidget.DEFAULT_HEIGHT
            val startX = (width - nWidth - 20f).coerceAtLeast(20f)
            val startY = 20f
            notificationsWidget = NotificationsWidget(context, startX, startY, nWidth, nHeight, height.toFloat()).apply {
                hookupSingleton(notificationsDesc, onClose = { executeDefinition(commandRegistry.toggleNotifications) })
                onStateChanged = { notifyContentChanged() }
                setNotificationsSnapshot(latestNotificationsSnapshot)
            }
            Log.d(TAG, "NotificationsWidget created")
        }
    }

    /**
     * Toggle SubtitleWidget: remove if present, create at default location if absent.
     */
    private fun mutateSubtitlePresence() {
        if (subtitleWidget != null) {
            subtitleWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    subtitle = persistenceHelper.getSubtitleWidgetState(it)
                )
                it.onDestroy()
            }
            teardownSingleton(subtitleDesc)
            subtitleWidget = null
            Log.d(TAG, "SubtitleWidget removed")
        } else {
            closedWidgetTemplates.subtitle?.let {
                applySubtitleState(it)
                subtitleWidget?.setCaptureEnabled(true)
                Log.d(TAG, "SubtitleWidget restored from last closed state")
                return
            }

            val subtitleWidth = SubtitleWidget.DEFAULT_WIDTH.coerceAtMost(width * 0.82f)
            val subtitleHeight = SubtitleWidget.DEFAULT_HEIGHT
            val startX = ((width - subtitleWidth) / 2f).coerceAtLeast(20f)
            val startY = (height - subtitleHeight - 72f).coerceAtLeast(20f)
            subtitleWidget = createSubtitleWidget(startX, startY, subtitleWidth, subtitleHeight, autoStart = true)
            Log.d(TAG, "SubtitleWidget created")
        }
    }

    /**
     * Toggle ScreenMirrorWidget: remove if present, create at default location if absent.
     */
    private fun mutateMirrorPresence() {
        if (screenMirrorWidget != null) {
            screenMirrorWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    mirror = persistenceHelper.getMirrorWidgetState(it)
                )
            }
            teardownSingleton(mirrorDesc)
            setBinocularMediaSourceActive(screenMirrorWidget!!.binocularSourceId, false)
            screenMirrorWidget?.onDestroy()
            screenMirrorWidget = null
            notifyMediaBrightnessCapChanged()
            Log.d(TAG, "ScreenMirrorWidget removed")
        } else {
            closedWidgetTemplates.mirror?.let {
                applyMirrorState(it)
                Log.d(TAG, "ScreenMirrorWidget restored from last closed state")
                return
            }
            // Create at default location
            val mirrorWidth = 180f
            val mirrorHeight = 320f
            screenMirrorWidget = ScreenMirrorWidget(context, 20f, 20f, mirrorWidth, mirrorHeight).apply {
                configureScreenMirrorWidget(this)
            }
            notifyMediaBrightnessCapChanged()
            Log.d(TAG, "ScreenMirrorWidget created")
        }
    }

    fun createBrowserWidget(x: Float, y: Float) {
        if (openClosedBrowserTemplate() != null) {
            notifyContentChanged()
            return
        }
        val boxWidth = width * 0.45f; val boxHeight = height * 0.5f
        val adjustedX = x.coerceIn(0f, width - boxWidth); val adjustedY = y.coerceIn(0f, height - boxHeight)
        val rootView = (parent as? ViewGroup) ?: return
        val widget = BrowserWidget(context, rootView, adjustedX, adjustedY, boxWidth, boxHeight).apply {
            configureBrowserWidget(this)
        }
        val containerIndex = rootView.indexOfChild(this); val insertIndex = if (containerIndex >= 0) containerIndex else 0
        widget.getWebView()?.let { webView -> if (webView.parent == rootView) rootView.removeView(webView); rootView.addView(webView, insertIndex) }
        lifecycleController.open(browserLifecycle) { widget }
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        widget.toggleFullscreen()
        widget.setFocused(true)
        setFocusedWidget(widget)
        notifyContentChanged()
    }

    private fun bindMapNavigation(map: MapWidget) {
        map.listener = object : MapWidgetListener {
            override fun onMapDataRequested(point: MapGeoPoint, config: PoiDisplayConfig) {
                dataProvider?.requestMapData(point, config)
            }
            override fun onAddressFocusChanged(focused: Boolean) {
                if (focused) bringWidgetToFront(map)
                contentObserver?.onFocusChanged(focused)
                notifyContentChanged()
            }
            override fun onNavigationResult(result: NavigationOperationResult) = notifyContentChanged()
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
        dismissMapPresentation()
        mapWidget?.stopNavigation()
        notifyContentChanged()
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
        val rootView = (parent as? ViewGroup) ?: return null
        val boxWidth = width * 0.45f
        val boxHeight = height * 0.5f
        val adjustedX = pendingCreateX.coerceIn(0f, width - boxWidth)
        val adjustedY = pendingCreateY.coerceIn(0f, height - boxHeight)
        val widget = BrowserWidget(context, rootView, adjustedX, adjustedY, boxWidth, boxHeight).apply {
            configureBrowserWidget(this)
        }
        val containerIndex = rootView.indexOfChild(this)
        val insertIndex = if (containerIndex >= 0) containerIndex else 0
        widget.getWebView()?.let { webView ->
            if (webView.parent == rootView) rootView.removeView(webView)
            rootView.addView(webView, insertIndex)
        }
        val opened = lifecycleController.open(browserLifecycle) { widget } ?: return null
        opened.setFocused(true)
        setFocusedWidget(opened)
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        notifyContentChanged()
        val widgetId = stableWidgetId(opened)
        return BrowserWidgetPageAgentAdapter.bind(widgetId, opened)
            ?: run {
                lifecycleController.close(browserLifecycle, opened)
                null
            }
    }

    private fun openBrowserAddress(address: String) {
        val widget = focusedBrowserWidget
            ?: fullscreenBrowserWidget
            ?: createFullscreenBrowserWidget()
            ?: return

        if (widget.isMinimized) {
            widget.toggleMinimize()
        }
        bringWidgetToFront(widget)
        widget.setFocused(true)
        setFocusedWidget(widget)
        widget.navigateToAddress(address, autoFullscreenVideo = true)
        notifyContentChanged()
    }

    private fun createFullscreenBrowserWidget(): BrowserWidget? {
        val rootView = (parent as? ViewGroup) ?: return null
        val widget = BrowserWidget(context, rootView, 0f, 0f, width.toFloat(), height.toFloat()).apply {
            configureBrowserWidget(this)
        }
        val containerIndex = rootView.indexOfChild(this)
        val insertIndex = if (containerIndex >= 0) containerIndex else 0
        widget.getWebView()?.let { webView ->
            if (webView.parent == rootView) rootView.removeView(webView)
            rootView.addView(webView, insertIndex)
        }
        lifecycleController.open(browserLifecycle) { widget }
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        widget.toggleFullscreen()
        return widget
    }

    private fun removeBrowserWidget(widget: BrowserWidget) {
        lifecycleController.close(browserLifecycle, widget)
    }

    /**
     * Create a fullscreen browser widget that opens YouTube history.
     * Called when the YouTube button is tapped.
     */
    private fun createYouTubeBrowserWidget() {
        val rootView = (parent as? ViewGroup) ?: return
        // Create browser widget that fills the screen
        val widget = BrowserWidget(context, rootView, 0f, 0f, width.toFloat(), height.toFloat()).apply {
            configureBrowserWidget(this)
        }
        val containerIndex = rootView.indexOfChild(this); val insertIndex = if (containerIndex >= 0) containerIndex else 0
        widget.getWebView()?.let { webView -> if (webView.parent == rootView) rootView.removeView(webView); rootView.addView(webView, insertIndex) }
        lifecycleController.open(browserLifecycle) { widget }
        notifyBrowserMinimumBrightnessChanged()

        // Load YouTube history URL
        widget.getWebView()?.loadUrl("https://www.youtube.com/feed/history")

        // Enter fullscreen mode immediately
        widget.toggleFullscreen()

        widget.setFocused(true); setFocusedWidget(widget); notifyContentChanged()
    }

    fun createImageWidget(path: String, x: Float = -1f, y: Float = -1f) {
        val createX = if (x >= 0) x else pendingCreateX
        val createY = if (y >= 0) y else pendingCreateY
        
        val widget = ImageWidget(createX, createY, path).apply {
            configureLifecycleListener(
                onClose = { removeImageWidget(this) },
                onFullscreen = { isFullscreen -> handleImageWidgetFullscreenToggle(this, isFullscreen) },
                onMinimize = { isMinimized -> handleImageWidgetMinimizeToggle(this, isMinimized) }
            )
        }
        lifecycleController.open(imageLifecycle) { widget }
    }

    private fun removeImageWidget(widget: ImageWidget) {
        lifecycleController.close(imageLifecycle, widget)
    }

    fun createVideoWidget(uri: android.net.Uri, displayName: String, x: Float = -1f, y: Float = -1f) {
        val rootView = parent as? ViewGroup ?: return
        val createX = if (x >= 0f) x else ((width - 360f) / 2f).coerceAtLeast(0f)
        val createY = if (y >= 0f) y else ((height - 220f) / 2f).coerceAtLeast(0f)
        val sourceId = "local-video-${uri.hashCode()}"
        val widget = VideoWidget(
            context = context,
            rootLayout = rootView,
            containerView = this,
            contentUri = uri,
            displayName = displayName,
            x = createX,
            y = createY,
            queueItems = buildVideoQueue(uri, displayName),
        ).apply {
            configureLifecycleListener(
                onClose = { removeVideoWidget(this) },
                onFullscreen = { isFullscreen -> handleVideoWidgetFullscreenToggle(this, isFullscreen) },
                onMinimize = { isMinimized -> handleVideoWidgetMinimizeToggle(this, isMinimized) }
            )
            listener = object : VideoWidgetListener {
                override fun onStateChanged() = notifyTransientContentChanged()
                override fun onPlaybackVisibilityChanged(active: Boolean) {
                    setBinocularMediaSourceActive(sourceId, active)
                    notifyMediaBrightnessCapChanged()
                }
            }
        }
        lifecycleController.open(videoLifecycle) { widget }
        widget.toggleFullscreen()
        notifyContentChanged()
    }

    /**
     * Everyday's file browser chooses the initial file, while X3player's Previous/Next controls
     * operate on the alphabetically sorted video siblings in that file's directory.
     */
    private fun buildVideoQueue(initialUri: Uri, initialDisplayName: String): List<VideoItem> {
        val initial = VideoItem(
            id = initialUri.hashCode().toLong(),
            contentUri = initialUri,
            displayName = initialDisplayName,
        )
        val initialPath = resolveVideoPath(initialUri) ?: return listOf(initial)
        val parent = File(initialPath).parentFile ?: return listOf(initial)
        val initialCanonicalPath = File(initialPath).canonicalPath
        val files = parent.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) in FileBrowserWidget.FileFilter.VIDEOS.extensions }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.toList()
            .orEmpty()

        if (files.isEmpty()) return listOf(initial)

        return files.map { file ->
            val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val fileUri = if (canonicalPath == initialCanonicalPath) {
                initialUri
            } else {
                queryVideoUriForPath(file.absolutePath) ?: Uri.fromFile(file)
            }
            VideoItem(
                id = fileUri.hashCode().toLong(),
                contentUri = fileUri,
                displayName = file.name,
            )
        }.ifEmpty { listOf(initial) }
    }

    private fun resolveVideoPath(uri: Uri): String? {
        if (uri.scheme.equals("file", ignoreCase = true)) return uri.path
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun queryVideoUriForPath(path: String): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else ContentUris.withAppendedId(
                    collection,
                    cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)),
                )
            }
        }.getOrNull()
    }

    private fun removeVideoWidget(widget: VideoWidget) {
        lifecycleController.close(videoLifecycle, widget)
    }

    fun createPdfWidget(uri: android.net.Uri, displayName: String, x: Float = -1f, y: Float = -1f) {
        val createX = if (x >= 0f) x else ((width - 360f) / 2f).coerceAtLeast(0f)
        val createY = if (y >= 0f) y else ((height - 260f) / 2f).coerceAtLeast(0f)
        val widget = PdfWidget(context, uri, displayName, createX, createY).apply {
            configureLifecycleListener(
                onClose = { removePdfWidget(this) },
                onFullscreen = { isFullscreen -> handlePdfWidgetFullscreenToggle(this, isFullscreen) },
                onMinimize = { isMinimized -> handlePdfWidgetMinimizeToggle(this, isMinimized) }
            )
            onStateChanged = { notifyTransientContentChanged() }
        }
        lifecycleController.open(pdfLifecycle) { widget }
        widget.toggleFullscreen()
        notifyContentChanged()
    }

    private fun removePdfWidget(widget: PdfWidget) {
        lifecycleController.close(pdfLifecycle, widget)
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
        val widget = FileBrowserWidget(context, createX, createY, browserWidth, browserHeight, filter).apply {
            configureLifecycleListener(
                onClose = { removeFileBrowserWidget(this) },
                onFullscreen = { isFullscreen -> handleFileBrowserWidgetFullscreenToggle(this, isFullscreen) },
                onMinimize = { isMinimized -> handleFileBrowserWidgetMinimizeToggle(this, isMinimized) }
            )
            listener = object : FileBrowserWidgetListener {
                override fun onStateChanged(state: FileBrowserWidget.State) = notifyContentChanged()
                override fun onFileSelected(entry: FileBrowserWidget.FileEntry) {
                    selectionHandler?.invoke(entry)
                    removeFileBrowserWidget(this@apply)
                }
            }
        }
        lifecycleController.open(filesLifecycle) { widget }
        notifyContentChanged()
        post { notifyContentChanged() }
        // Opening the picker may complete asynchronously after a runtime/all-files permission
        // screen. Re-center after the widget actually exists so hover chrome is visible at once
        // and two-finger scrolling is routed to the new browser without a priming drag.
        host?.requestCursorCenter()
    }

    private fun removeFileBrowserWidget(widget: FileBrowserWidget) {
        lifecycleController.close(filesLifecycle, widget)
    }

    private fun handleFileBrowserWidgetFullscreenToggle(widget: FileBrowserWidget, isFullscreen: Boolean) {
        if (isFullscreen) { exitAllFullscreen(); fullscreenFileBrowserWidget = widget; widget.setFullscreenBounds(width.toFloat(), height.toFloat()) }
        else { fullscreenFileBrowserWidget = null }
        notifyContentChanged()
    }

    private fun buildTextBox(x: Float, y: Float, boxWidth: Float, boxHeight: Float): TextBoxWidget =
        TextBoxWidget(context, x, y, boxWidth, boxHeight).apply {
            configureLifecycleListener(
                onClose = { removeWidget(this) },
                onFullscreen = { isFullscreen -> handleTextWidgetFullscreenToggle(this, isFullscreen) },
                onMinimize = { isMinimized -> handleTextWidgetMinimizeToggle(this, isMinimized) }
            )
            listener = object : TextBoxWidgetListener {
                override fun onFocusChanged(focused: Boolean) {
                    if (focused) setFocusedWidget(this@apply)
                    else if (focusedWidget == this@apply) setFocusedWidget(null)
                }
                override fun onStateChanged(state: TextBoxWidget.State) = notifyContentChanged()
            }
        }

    fun createTextBox(x: Float, y: Float) {
        if (openClosedTextTemplate() != null) {
            notifyContentChanged()
            return
        }
        val boxWidth = width * DEFAULT_TEXTBOX_WIDTH_PERCENT; val boxHeight = height * DEFAULT_TEXTBOX_HEIGHT_PERCENT
        val adjustedX = x.coerceIn(0f, width - boxWidth); val adjustedY = y.coerceIn(0f, height - boxHeight)
        val widget = buildTextBox(adjustedX, adjustedY, boxWidth, boxHeight)
        lifecycleController.open(textLifecycle) { widget }; widget.setFocused(true); setFocusedWidget(widget)
    }

    private fun removeWidget(widget: TextBoxWidget) {
        lifecycleController.close(textLifecycle, widget)
    }

    private fun setFocusedWidget(widget: BaseWidget?) {
        when (widget) {
            is TextBoxWidget -> { if (focusedWidget == widget) return; focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = null; focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = widget }
            is BrowserWidget -> { if (focusedBrowserWidget == widget) return; focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = null; focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = widget }
            null -> { focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = null; focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = null }
        }
        formattingMenu?.dismiss(); contentObserver?.onFocusChanged(widget != null)
    }

    fun unfocusAll() {
        mapWidget?.takeIf { it.isAddressEntryFocused() }?.closeAddressEntry()
        setFocusedWidget(null)
    }

    private fun updateMinimizedWidgetPositions() {
        if (width <= 0 || height <= 0) return
        val minimizedWidgets = registry.getMinimizedWidgets(); val squareSize = 64f; val maxPerRow = 7
        minimizedWidgets.forEachIndexed { index, widget -> val row = index / maxPerRow; val col = index % maxPerRow; widget.setPosition(col * squareSize, height - ((row + 1) * squareSize)) }
    }

    fun onKeyPress(key: String, userInitiated: Boolean = false) {
        if (userInitiated) userInputActivity?.onUserInput(UserInputKind.KEYBOARD)
        if (newsWidget?.handleSourcePanelKeyPress(key) == true) {
            notifyContentChanged()
            return
        }
        if (layoutNamePrompt?.onKeyPress(key) == true) {
            notifyContentChanged()
            return
        }
        if (mapWidget?.onAddressKeyPress(key) == true) {
            notifyContentChanged()
            return
        }
        focusedWidget?.onKeyPress(key)
        focusedBrowserWidget?.onKeyPress(key)
        notifyContentChanged()
    }

    // ==================== Text Edit Menu ====================

    fun showTextEditMenu(clipboardContent: String?) {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.isFocused()) return
        textEditMenuTarget = TextEditMenuTarget.TEXT_WIDGET
        if (clipboardContent != null) phoneClipboard = clipboardContent
        // Request fresh clipboard from phone (will arrive async and update the menu)
        host?.requestClipboard()
        textEditMenu?.show(widget.x + widget.widgetWidth / 2 - 90f, widget.y - 60f, glassesClipboard ?: phoneClipboard, widget.hasSelection())
        notifyContentChanged()
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
        notifyContentChanged()
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
            notifyContentChanged()
        }
    }

    private fun handleCut() {
        if (textEditMenuTarget == TextEditMenuTarget.BROWSER_ADDRESS) {
            val browser = focusedBrowserWidget ?: fullscreenBrowserWidget ?: return
            glassesClipboard = browser.cutSelectedAddressText()
            notifyContentChanged()
            return
        }
        val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.hasSelection()) return; glassesClipboard = widget.getSelectedText(); widget.deleteSelection(); notifyContentChanged()
    }

    private fun handleCopy() {
        if (textEditMenuTarget == TextEditMenuTarget.BROWSER_ADDRESS) {
            val browser = focusedBrowserWidget ?: fullscreenBrowserWidget ?: return
            glassesClipboard = browser.copySelectedAddressText()
            notifyContentChanged()
            return
        }
        val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.hasSelection()) return; glassesClipboard = widget.getSelectedText(); notifyContentChanged()
    }

    private fun handlePaste(content: String) {
        if (textEditMenuTarget == TextEditMenuTarget.BROWSER_ADDRESS) {
            (focusedBrowserWidget ?: fullscreenBrowserWidget)?.pasteIntoAddress(content)
            notifyContentChanged()
            return
        }
        (focusedWidget ?: fullscreenTextWidget)?.pasteText(content); notifyContentChanged()
    }

    private fun handleSelectAll() {
        if (textEditMenuTarget == TextEditMenuTarget.BROWSER_ADDRESS) {
            (focusedBrowserWidget ?: fullscreenBrowserWidget)?.selectAllAddressText()
            notifyContentChanged()
            return
        }
        (focusedWidget ?: fullscreenTextWidget)?.selectAll(); notifyContentChanged()
    }

    private fun showFormattingMenu() {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.isFocused()) return
        textEditMenu?.dismiss()
        formattingMenu?.show(widget.x + widget.widgetWidth / 2, widget.y - 20f, widget.textFontSize, widget.isTextWrap, widget.getColumnCount())
        formattingMenu?.apply { isBold = widget.isBoldActive(); isItalic = widget.isItalicActive(); isUnderline = widget.isUnderlineActive() }
        notifyContentChanged()
    }

    private fun ensureDrawnAboveNativeOverlays() {
        val rootView = parent as? ViewGroup ?: return
        if (rootView.indexOfChild(this) != rootView.childCount - 1) {
            rootView.bringChildToFront(this)
        }
        if (elevation < 10f) elevation = 10f
    }

    private fun handleFontSizeChanged(size: Float) { (focusedWidget ?: fullscreenTextWidget)?.setFontSize(size); notifyContentChanged() }
    private fun handleTextWrapToggled(wrap: Boolean) { (focusedWidget ?: fullscreenTextWidget)?.setTextWrap(wrap); notifyContentChanged() }
    private fun handleBoldToggled() { val widget = focusedWidget ?: fullscreenTextWidget ?: return; widget.toggleBold(); formattingMenu?.isBold = widget.isBoldActive(); notifyContentChanged() }
    private fun handleItalicToggled() { val widget = focusedWidget ?: fullscreenTextWidget ?: return; widget.toggleItalic(); formattingMenu?.isItalic = widget.isItalicActive(); notifyContentChanged() }
    private fun handleUnderlineToggled() { val widget = focusedWidget ?: fullscreenTextWidget ?: return; widget.toggleUnderline(); formattingMenu?.isUnderline = widget.isUnderlineActive(); notifyContentChanged() }
    private fun handleBulletListToggled() { (focusedWidget ?: fullscreenTextWidget)?.toggleBulletList(); notifyContentChanged() }
    private fun handleNumberedListToggled() { (focusedWidget ?: fullscreenTextWidget)?.toggleNumberedList(); notifyContentChanged() }

    // ==================== Public Accessors ====================

    fun setPhoneClipboard(content: String?) {
        phoneClipboard = content
        // Update visible text edit menu with new clipboard content
        updateTextEditMenuClipboard()
    }
    fun hasFocusedWidget(): Boolean =
        focusedWidget != null || focusedBrowserWidget != null || mapWidget?.isAddressEntryFocused() == true
    fun isMenuVisible(): Boolean =
        contextMenu?.isVisible == true ||
            textEditMenu?.isVisible == true ||
            formattingMenu?.isVisible == true ||
            layoutNamePrompt?.isVisible == true
    fun isClosePopupVisible(): Boolean =
        closeConfirmationPopup?.isVisible == true ||
            layoutDeleteConfirmationPopup?.isVisible == true
    fun isAnyPopupVisible(): Boolean =
        isMenuVisible() || isClosePopupVisible() || pageAgentApprovalOverlay.isVisible()

    fun showPageAgentApproval(
        runId: String,
        request: PageAgentConfirmationRequest,
        respond: (Boolean) -> Unit
    ) {
        pageAgentApprovalOverlay.show(runId, request, respond)
        ensureDrawnAboveNativeOverlays()
    }

    fun dismissPageAgentApproval(runId: String) {
        pageAgentApprovalOverlay.dismiss(runId)
    }
    fun dismissMenu() { contextMenu?.dismiss(); textEditMenu?.dismiss(); formattingMenu?.dismiss(); layoutNamePrompt?.dismiss() }
    fun isDragging(): Boolean =
        activeWidget != null ||
        financeTileDragging ||
        singletonDescs.any { it.dragging } ||
        formattingMenu?.isDragging() == true
    fun isLongPressPending(): Boolean = longPressRunnable != null || formattingMenu?.isLongPressPending() == true

    // ==================== 3DOF Mode ====================

    /**
     * Check if 3DOF mode is currently active.
     */
    fun is3DofEnabled(): Boolean = is3DofEnabled

    /**
     * Set 3DOF mode enabled state. Called by ThreeDofManager.
     */
    fun set3DofEnabled(enabled: Boolean) {
        if (is3DofEnabled != enabled) {
            is3DofEnabled = enabled
            threeDofOverlay.set3DofEnabled(enabled)
            Log.d(TAG, "3DOF mode ${if (enabled) "enabled" else "disabled"}")
            notifyContentChanged()
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
        notifyContentChanged()
    }


    fun set3DofRoll(rollDeg: Float) {
        threeDofRollDeg = rollDeg
        notifyContentChanged()
    }

    /**
     * Set the 3DOF transition animation progress.
     * @param progress Progress from 0.0 (start) to 1.0 (complete)
     */
    fun set3DofTransitionProgress(progress: Float) {
        threeDofOverlay.setTransitionProgress(progress)
        notifyContentChanged()
    }

    /**
     * Convert screen coordinates to content coordinates.
     * In 3DOF mode, the cursor stays centered (head-locked) while content moves.
     * This transforms cursor position to where it would be in content space.
     */
    fun screenToContentCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> {
        if (!is3DofEnabled) {
            return Pair(screenX, screenY)
        }

        // Inverse of the draw transform:
        // draw: translate(offset) then rotate(-roll) about screen center
        // inverse: rotate(+roll) about screen center then translate(-offset)

        val cx = width / 2f
        val cy = height / 2f

        val a = Math.toRadians(threeDofRollDeg.toDouble())
        val cosA = kotlin.math.cos(a).toFloat()
        val sinA = kotlin.math.sin(a).toFloat()

        // Current onDraw logic: translate(offset) then rotate(-roll)
        // This corresponds to: Rotate then Translate (in forward transform, Android Canvas order)
        // Or Translate then Rotate (in matrix order T*R)?
        // Android Canvas: translate(T); rotate(R) -> Matrix = T * R.
        // Point P' = T * R * P.
        // P is Rotated (around content center) then Translated.
        
        // Inverse should be: P = R_inv * T_inv * P'.
        // 1. Untranslate (T_inv)
        // 2. Unrotate (R_inv)

        // 1. Untranslate
        val untranslatedX = screenX - threeDofOffsetX
        val untranslatedY = screenY - threeDofOffsetY

        // 2. Unrotate around (cx, cy)
        // Rotate by +roll (inverse of -roll)
        val dx = untranslatedX - cx
        val dy = untranslatedY - cy

        val xRot = cx + dx * cosA - dy * sinA
        val yRot = cy + dx * sinA + dy * cosA

        return Pair(xRot, yRot)
    }


    // ==================== Drawing ====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (displayState == WakeSleepManager.DisplayState.OFF) return

        if (displayState == WakeSleepManager.DisplayState.SLEEP) {
            // Check if there's a PINNED fullscreen widget - if so, only draw that one (it covers others)
            val pinnedFullscreenWidget: BaseWidget? = when {
                fullscreenTextWidget?.isPinned == true -> fullscreenTextWidget
                fullscreenBrowserWidget?.isPinned == true -> fullscreenBrowserWidget
                fullscreenImageWidget?.isPinned == true -> fullscreenImageWidget
                fullscreenVideoWidget?.isPinned == true -> fullscreenVideoWidget
                fullscreenPdfWidget?.isPinned == true -> fullscreenPdfWidget
                fullscreenFileBrowserWidget?.isPinned == true -> fullscreenFileBrowserWidget
                isStatusBarFullscreen && statusBarWidget?.isPinned == true -> statusBarWidget
                isLocationWidgetFullscreen && locationWidget?.isPinned == true -> locationWidget
                isMapWidgetFullscreen && mapWidget?.isPinned == true -> mapWidget
                isCalendarWidgetFullscreen && calendarWidget?.isPinned == true -> calendarWidget
                isFinanceWidgetFullscreen && financeWidget?.isPinned == true -> financeWidget
                isNewsWidgetFullscreen && newsWidget?.isPinned == true -> newsWidget
                isNotificationsWidgetFullscreen && notificationsWidget?.isPinned == true -> notificationsWidget
                isSpeedometerWidgetFullscreen && speedometerWidget?.isPinned == true -> speedometerWidget
                isSubtitleWidgetFullscreen && subtitleWidget?.isPinned == true -> subtitleWidget
                isMirrorWidgetFullscreen && screenMirrorWidget?.isPinned == true -> screenMirrorWidget
                else -> null
            }

            if (pinnedFullscreenWidget != null) {
                // Only draw the pinned fullscreen widget - it covers everything else
                pinnedFullscreenWidget.draw(canvas)
                if (!settingsController.isHoverControlsLayoutEditorVisible()) {
                    drawHoverControls(canvas)
                }
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
            fullscreenTextWidget?.draw(canvas)
            fullscreenBrowserWidget?.draw(canvas)
            fullscreenImageWidget?.draw(canvas)
            fullscreenVideoWidget?.draw(canvas)
            fullscreenPdfWidget?.draw(canvas)
            fullscreenFileBrowserWidget?.draw(canvas)
            if (isStatusBarFullscreen) statusBarWidget?.draw(canvas)
            if (isLocationWidgetFullscreen) locationWidget?.draw(canvas)
            if (isMapWidgetFullscreen) mapWidget?.draw(canvas)
            if (isCalendarWidgetFullscreen) calendarWidget?.draw(canvas)
            if (isFinanceWidgetFullscreen) financeWidget?.draw(canvas)
            if (isNewsWidgetFullscreen) newsWidget?.draw(canvas)
            if (isNotificationsWidgetFullscreen) notificationsWidget?.draw(canvas)
            if (isSpeedometerWidgetFullscreen) speedometerWidget?.draw(canvas)
            if (isSubtitleWidgetFullscreen) subtitleWidget?.draw(canvas)
            if (isMirrorWidgetFullscreen) screenMirrorWidget?.draw(canvas)
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

        val cx = width / 2f - threeDofOffsetX
        val cy = height / 2f - threeDofOffsetY

        // Apply 3DOF transformation to content (not to close button or menus)
        if (is3DofEnabled || threeDofOverlay.isVisible()) {
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
        if (is3DofEnabled || threeDofOverlay.isVisible()) {
            canvas.restore()
        }

        drawPresentationProgress(canvas)

        // Draw menus (not affected by 3DOF - stay head-locked like cursor)
        drawOverlayMenus(canvas)

        // Note: Brightness dimming is now handled at window level via WindowManager.LayoutParams.screenBrightness
        // This ensures the entire app (including WebViews) is dimmed uniformly.
    }

    private fun drawOverlayMenus(canvas: Canvas) {
        if (isAnyPopupVisible() || focusedBrowserWidget?.isEditingAddress() == true || fullscreenBrowserWidget?.isEditingAddress() == true) {
            ensureDrawnAboveNativeOverlays()
        }
        contextMenu?.draw(canvas)
        textEditMenu?.draw(canvas)
        formattingMenu?.draw(canvas)
        // Draw the hover-controls editor before the close-app confirmation so the popup
        // appears on top of it when the user invokes Close from the editor's context menu.
        settingsController.drawHoverControlsLayoutEditor(canvas)
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
                host.getKeyboard()?.onTap(cx, cy)
                notifyContentChanged()
                true
            }
            is TextBoxWidget -> {
                host.getKeyboard()?.onTap(cx, cy)
                notifyContentChanged()
                true
            }
            is MapWidget -> {
                host.getAddressKeyboard()?.onTap(cx, cy)
                notifyContentChanged()
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
                notifyContentChanged()
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

    fun capturePersistedState(): WidgetPersistence.PersistedState =
        layoutManager.capturePersistedState()

    private fun captureWidgetStateWithoutLayoutName(): WidgetPersistence.PersistedState =
        WidgetPersistence.createPersistedState(
            textWidgets = persistenceHelper.getTextWidgetStates(widgets),
            browserWidgets = persistenceHelper.getBrowserWidgetStates(browserWidgets),
            imageWidgets = persistenceHelper.getImageWidgetStates(imageWidgets),
            videoWidgets = persistenceHelper.getVideoWidgetStates(videoWidgets),
            pdfWidgets = persistenceHelper.getPdfWidgetStates(pdfWidgets),
            statusBarState = persistenceHelper.getStatusBarState(statusBarWidget),
            locationWidgetState = persistenceHelper.getLocationWidgetState(locationWidget),
            mapWidgetState = persistenceHelper.getMapWidgetState(mapWidget),
            calendarWidgetState = persistenceHelper.getCalendarWidgetState(calendarWidget),
            mirrorWidgetState = persistenceHelper.getMirrorWidgetState(screenMirrorWidget),
            financeWidgetState = persistenceHelper.getFinanceWidgetState(financeWidget),
            newsWidgetState = persistenceHelper.getNewsWidgetState(newsWidget),
            notificationsWidgetState = persistenceHelper.getNotificationsWidgetState(notificationsWidget),
            speedometerWidgetState = persistenceHelper.getSpeedometerWidgetState(speedometerWidget),
            subtitleWidgetState = persistenceHelper.getSubtitleWidgetState(subtitleWidget),
            closedTemplates = closedWidgetTemplates,
            hoverControls = settingsController.getHoverControlPlacements(),
            isFirstRun = false,
            activeLayoutName = null,
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
        notifyContentChanged()
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
                isAvailable = ::isReady
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
                map.startNavigation(
                    address = request.address,
                    modeName = request.mode,
                    presentationSpec = presentation
                ) { outcome ->
                    result.complete(outcome.toAssistantExecution())
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
            dismissMapPresentation()
            mapWidget?.stopNavigation()
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
                lifecycleController.show(WidgetTypeId.MAP)
                val map = mapWidget ?: error("Map widget could not be created")
                map.applyPoiDisplayConfig(request.config)
                val presented = request.presentation?.let { presentWidget(map, it) } ?: true
                if (!presented) {
                    AssistantToolExecution(
                        false,
                        AssistantToolExecutionCodes.EXECUTION_FAILED,
                        "Map presentation could not be started"
                    )
                } else {
                    AssistantToolExecution(
                        true,
                        AssistantToolExecutionCodes.SUCCESS,
                        "Map POIs configured: state=${request.config.state.name}, " +
                            "categories=${request.config.categories.sorted().joinToString(",")}, " +
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
        invalidateHoverVisuals()
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

    private fun executeDefinition(definition: AppCommandDefinition): AppCommandResult =
        executeCommand(commandRegistry.invocation(definition))

    private fun loadCommandLayout(name: String): Boolean {
        if (!isReady()) return false
        val builtIn = BuiltInLayouts.build(name, width.toFloat(), height.toFloat())
        if (builtIn != null) {
            requestLayoutSwitchBrightness()
            applyPersistedStateReplacingCurrent(builtIn)
            layoutManager.activeLayoutName = name
            return true
        }
        val saved = stateStore.loadLayout(name) ?: return false
        requestLayoutSwitchBrightness()
        applyPersistedStateReplacingCurrent(saved)
        layoutManager.activeLayoutName = name
        return true
    }

    fun executeOpenUrlCommand(address: String): AppCommandResult = executeCommand(
        AppCommandInvocation(commandRegistry.openUrl.id, AppCommandArguments.Url(address))
    )

    fun applyPersistedStateReplacingCurrent(state: WidgetPersistence.PersistedState) {
        if (!isReady()) return

        val previousState = captureWidgetStateWithoutLayoutName().copy(
            activeLayoutName = layoutManager.activeLayoutName
        )
        suppressContentChangedCallback = true
        try {
            dismissAllTransientOverlays()
            clearWidgetsForLayoutReplace()
            applyPersistedContents(state)
        } catch (failure: Throwable) {
            runCatching {
                clearWidgetsForLayoutReplace()
                applyPersistedContents(previousState)
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            suppressContentChangedCallback = false
        }

        notifyContentChanged()
    }

    private fun applyPersistedContents(state: WidgetPersistence.PersistedState) {
        shouldCreateDefaultSingletons = false
        closedWidgetTemplates = state.closedTemplates

        if (state.text.isNotEmpty()) openTextStates(state.text)
        if (state.browser.isNotEmpty()) openBrowserStates(state.browser)
        if (state.image.isNotEmpty()) openImageStates(state.image)
        if (state.video.isNotEmpty()) openVideoStates(state.video)
        if (state.pdf.isNotEmpty()) openPdfStates(state.pdf)

        state.status?.let { lifecycleController.restore(WidgetTypeId.STATUS, it) }
        state.location?.let { lifecycleController.restore(WidgetTypeId.LOCATION, it) }
        state.map?.let { lifecycleController.restore(WidgetTypeId.MAP, it) }
        state.calendar?.let { lifecycleController.restore(WidgetTypeId.CALENDAR, it) }
        state.mirror?.let { lifecycleController.restore(WidgetTypeId.MIRROR, it) }
        state.finance?.let { lifecycleController.restore(WidgetTypeId.FINANCE, it) }
        state.news?.let { lifecycleController.restore(WidgetTypeId.NEWS, it) }
        state.notifications?.let { lifecycleController.restore(WidgetTypeId.NOTIFICATIONS, it) }
        state.speedometer?.let { lifecycleController.restore(WidgetTypeId.SPEEDOMETER, it) }
        state.subtitle?.let { lifecycleController.restore(WidgetTypeId.SUBTITLE, it) }
        settingsController.applyHoverControlPlacements(state.hoverControls)
        setWidgetLayoutLocked(state.isLayoutLocked, persist = false)

        updateMinimizedWidgetPositions()
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        layoutManager.activeLayoutName = state.activeLayoutName
    }

    private fun applyDefaultLayoutReplacingCurrent() {
        val defaultState = BuiltInLayouts.build(
            WidgetPersistence.DEFAULT_LAYOUT_NAME,
            width.toFloat(),
            height.toFloat()
        ) ?: return
        settingsController.resetHoverControlPlacementsToDefault()
        applyPersistedStateReplacingCurrent(defaultState)
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

        lifecycleController.closeAll(
            widgets = registry.allWidgets(),
            teardown = ::destroyWidget,
            clearRegistry = registry::clear
        )

        statusBarWidget = null
        locationWidget = null
        mapWidget = null
        calendarWidget = null
        financeWidget = null
        newsWidget = null
        notificationsWidget = null
        speedometerWidget = null
        subtitleWidget = null
        screenMirrorWidget = null

        activeWidget = null
        googleAuthBrowserWidget = null
        fullscreenPdfWidget = null
        isStatusBarFullscreen = false
        isLocationWidgetFullscreen = false
        isMapWidgetFullscreen = false
        isCalendarWidgetFullscreen = false
        isFinanceWidgetFullscreen = false
        isNewsWidgetFullscreen = false
        isNotificationsWidgetFullscreen = false
        isSpeedometerWidgetFullscreen = false
        isSubtitleWidgetFullscreen = false
        isMirrorWidgetFullscreen = false
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

    private fun openClosedTextTemplate(): TextBoxWidget? {
        val template = closedWidgetTemplates.text ?: return null
        val widget = persistenceHelper.restoreTextWidgets(listOf(template), width, height).firstOrNull() ?: return null
        lifecycleController.open(textLifecycle) { widget }
        applyFullscreenState(widget, template.isFullscreen)
        if (!widget.isMinimized) {
            widget.setFocused(true)
            setFocusedWidget(widget)
        }
        return widget
    }

    private fun openClosedBrowserTemplate(): BrowserWidget? {
        val template = closedWidgetTemplates.browser ?: return null
        val rootView = (parent as? ViewGroup) ?: return null
        val widget = persistenceHelper
            .restoreBrowserWidgets(listOf(template), rootView, this, width, height)
            .firstOrNull()
            ?: return null

        configureBrowserWidget(widget)
        lifecycleController.open(browserLifecycle) { widget }
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        applyFullscreenState(widget, template.isFullscreen)
        if (!widget.isMinimized) {
            widget.setFocused(true)
            setFocusedWidget(widget)
        }
        return widget
    }

    private fun openTextStates(states: List<WidgetPersistence.TextWidgetState>) {
        val restoredWidgets = persistenceHelper.restoreTextWidgets(states, width, height)
        for ((index, widget) in restoredWidgets.withIndex()) {
            lifecycleController.open(textLifecycle) { widget }
            applyFullscreenState(widget, states.getOrNull(index)?.isFullscreen == true)
        }
        notifyContentChanged()
    }

    /**
     * Call this BEFORE restoring widget state to prevent default singleton widgets from being created.
     * This should be called when the app is not a fresh install (returning user).
     */
    fun setIsReturningUser() {
        shouldCreateDefaultSingletons = false
    }

    /**
     * Restore or create status bar widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    private fun applyStatusBarState(state: WidgetPersistence.StatusBarState) {
        // Create status bar if it doesn't exist
        if (statusBarWidget == null) {
            val statusWidth = 220f
            val statusHeight = 110f
            statusBarWidget = StatusBarWidget(
                context, state.x, state.y, statusWidth, statusHeight
            ).apply {
                onStateChanged = { s ->
                    if (s == StatusBarWidget.State.MOVING) statusBarDesc.dragging = true
                    else if (statusBarDesc.dragging && s == StatusBarWidget.State.IDLE) statusBarDesc.dragging = false
                    notifyContentChanged()
                }
                hookupSingleton(statusBarDesc, onClose = { executeDefinition(commandRegistry.toggleStatus) })
            }
        }
        persistenceHelper.restoreStatusBarPosition(statusBarWidget, state, width, height)
        applyFullscreenState(statusBarWidget, state.isFullscreen)
        notifyContentChanged()
    }

    private fun openBrowserStates(states: List<WidgetPersistence.BrowserWidgetState>) {
        val rootView = (parent as? ViewGroup) ?: return
        val restoredWidgets = persistenceHelper.restoreBrowserWidgets(states, rootView, this, width, height)
        for ((index, widget) in restoredWidgets.withIndex()) {
            configureBrowserWidget(widget)
            lifecycleController.open(browserLifecycle) { widget }
            applyFullscreenState(widget, states.getOrNull(index)?.isFullscreen == true)
        }
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        notifyContentChanged()
    }

    fun shouldApplyBrowserMinimumBrightness(): Boolean {
        return browserWidgets.any { !it.isMinimized }
    }

    fun shouldApplyMediaBrightnessCap(): Boolean {
        return fullscreenVideoWidget != null || screenMirrorWidget?.shouldApplyMediaBrightnessCap() == true
    }

    private fun openImageStates(states: List<WidgetPersistence.ImageWidgetState>) {
        val restoredWidgets = persistenceHelper.restoreImageWidgets(states, width, height)
        val validStates = states.filter { File(it.imagePath).exists() }
        for ((index, widget) in restoredWidgets.withIndex()) {
            widget.configureLifecycleListener(
                onClose = { removeImageWidget(widget) },
                onFullscreen = { isFullscreen -> handleImageWidgetFullscreenToggle(widget, isFullscreen) },
                onMinimize = { isMinimized -> handleImageWidgetMinimizeToggle(widget, isMinimized) }
            )
            lifecycleController.open(imageLifecycle) { widget }
            applyFullscreenState(widget, validStates.getOrNull(index)?.isFullscreen == true)
        }
        notifyContentChanged()
    }

    /**
     * Restore or create location widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    private fun applyMapState(state: WidgetPersistence.MapWidgetState) {
        if (mapWidget == null) {
            mapWidget = MapWidget(context, state.x, state.y, state.width, state.height).apply {
                invalidate = { notifyContentChanged() }
                configurePhoneRouteTransport(phoneRouteTransport)
                onPhoneRouteConnectionChanged(phoneRouteConnected)
                bindMapNavigation(this)
                hookupSingleton(mapDesc, onClose = { executeDefinition(commandRegistry.toggleMap) })
                pendingMapLocation?.let(::setLocation)
                setLocationMetadata(pendingMapLocationLabel, pendingMapCountryCode)
            }
        }
        persistenceHelper.restoreMapWidgetPosition(mapWidget, state, width, height)
        applyFullscreenState(mapWidget, state.isFullscreen)
        // Restore the projected preference only after fullscreen bounds/state exist.
        mapWidget?.restorePreferences(state.mode, state.zoom, state.showLocation, state.showCountry)
        notifyContentChanged()
    }

    private fun openVideoStates(states: List<WidgetPersistence.VideoWidgetState>) {
        val rootView = parent as? ViewGroup ?: return
        states.forEach { state ->
            val uri = runCatching { android.net.Uri.parse(state.uri) }.getOrNull() ?: return@forEach
            val readable = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!readable) return@forEach

            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, width.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, height.toFloat())
            val adjustedX = state.x.coerceIn(0f, (width - adjustedWidth).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (height - adjustedHeight).coerceAtLeast(0f))
            val sourceId = "local-video-${uri.hashCode()}"
            val widget = VideoWidget(
                context = context,
                rootLayout = rootView,
                containerView = this,
                contentUri = uri,
                displayName = state.displayName,
                x = adjustedX,
                y = adjustedY,
                queueItems = buildVideoQueue(uri, state.displayName),
            ).apply {
                widgetWidth = adjustedWidth
                widgetHeight = adjustedHeight
                configureLifecycleListener(
                    onClose = { removeVideoWidget(this) },
                    onFullscreen = { isFullscreen -> handleVideoWidgetFullscreenToggle(this, isFullscreen) },
                    onMinimize = { isMinimized -> handleVideoWidgetMinimizeToggle(this, isMinimized) }
                )
                listener = object : VideoWidgetListener {
                    override fun onStateChanged() = notifyTransientContentChanged()
                    override fun onPlaybackVisibilityChanged(active: Boolean) {
                        setBinocularMediaSourceActive(sourceId, active)
                        notifyMediaBrightnessCapChanged()
                    }
                }
            }
            persistenceHelper.restoreVideoWidgetState(widget, state, adjustedX, adjustedY)
            lifecycleController.open(videoLifecycle) { widget }
            applyFullscreenState(widget, state.isFullscreen)
        }
        notifyContentChanged()
    }

    private fun openPdfStates(states: List<WidgetPersistence.PdfWidgetState>) {
        states.forEach { state ->
            val uri = runCatching { android.net.Uri.parse(state.uri) }.getOrNull() ?: return@forEach
            val readable = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!readable) return@forEach

            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, width.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, height.toFloat())
            val adjustedX = state.x.coerceIn(0f, (width - adjustedWidth).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (height - adjustedHeight).coerceAtLeast(0f))
            val widget = PdfWidget(
                context = context,
                contentUri = uri,
                displayName = state.displayName,
                x = adjustedX,
                y = adjustedY,
                width = adjustedWidth,
                height = adjustedHeight,
                initialPage = state.page,
                initialZoom = state.zoom,
            ).apply {
                configureLifecycleListener(
                    onClose = { removePdfWidget(this) },
                    onFullscreen = { isFullscreen -> handlePdfWidgetFullscreenToggle(this, isFullscreen) },
                    onMinimize = { isMinimized -> handlePdfWidgetMinimizeToggle(this, isMinimized) }
                )
                onStateChanged = { notifyTransientContentChanged() }
            }
            persistenceHelper.restorePdfWidgetState(widget, state, adjustedX, adjustedY)
            lifecycleController.open(pdfLifecycle) { widget }
            applyFullscreenState(widget, state.isFullscreen)
        }
        notifyContentChanged()
    }

    private fun applyLocationState(state: WidgetPersistence.LocationWidgetState) {
        // Create location widget if it doesn't exist
        if (locationWidget == null) {
            locationWidget = LocationWidget(context, state.x, state.y, state.width, state.height).apply {
                hookupSingleton(locationDesc, onClose = { executeDefinition(commandRegistry.toggleLocation) })
                onTemperatureUnitChanged = { unitSettings.toggleTemperatureOverride() }
                setTemperatureUnit(unitSettings.effectiveTemperatureUnit())
                if (pendingLocationAddress != null || pendingLocationWeather != null) {
                    setLocationData(pendingLocationAddress ?: "Loading...", pendingLocationWeather ?: "Checking weather...")
                }
                setSunTimes(pendingLocationSunriseEpochMs, pendingLocationSunsetEpochMs)
            }
        }
        persistenceHelper.restoreLocationWidgetPosition(locationWidget, state, width, height)
        applyFullscreenState(locationWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create calendar widget with the given state.
     */
    private fun applyCalendarState(state: WidgetPersistence.CalendarWidgetState) {
        if (calendarWidget == null) {
            calendarWidget = CalendarWidget(state.x, state.y, state.width, state.height)
        }
        calendarWidget?.apply {
            hookupSingleton(calendarDesc, onClose = { executeDefinition(commandRegistry.toggleCalendar) })
            onStateChanged = { notifyContentChanged() }
            setFontScale(state.fontScale)
            setSnapshot(pendingCalendarSnapshot)
        }
        persistenceHelper.restoreCalendarWidgetPosition(calendarWidget, state, width, height)
        applyFullscreenState(calendarWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create finance widget with the given state.
     */
    private fun applyFinanceState(state: WidgetPersistence.FinanceWidgetState) {
        if (financeWidget == null) {
            financeWidget = FinanceWidget(state.x, state.y, state.width, state.height).apply {
                hookupSingleton(
                    financeDesc,
                    onClose = { executeDefinition(commandRegistry.toggleFinance) },
                    onMinimize = { isMinimized ->
                        dataProvider?.onFinanceVisibilityChanged(!isMinimized)
                        if (!isMinimized) requestData(force = false, reason = "finance_unminimized")
                    }
                )
                listener = object : FinanceWidgetListener {
                    override fun onStateChanged() = notifyContentChanged()
                    override fun onDataRequested(visibleConfigs: List<FinanceDashboardTileConfig>, refreshTileIds: List<String>, force: Boolean, reason: String) {
                        dataProvider?.requestFinanceData(visibleConfigs, refreshTileIds, force, reason)
                    }
                }
                pendingFinanceCountryCode?.let { setCountryCode(it) }
            }
        }
        persistenceHelper.restoreFinanceWidgetPosition(financeWidget, state, width, height)
        applyFullscreenState(financeWidget, state.isFullscreen)
        if (financeWidget?.isMinimized == true) {
            dataProvider?.onFinanceVisibilityChanged(false)
        } else {
            financeWidget?.requestData(force = true, reason = "widget_restored")
            dataProvider?.onFinanceVisibilityChanged(true)
        }
        notifyContentChanged()
    }

    /**
     * Restore or create news widget with the given state.
     */
    private fun applyNewsState(state: WidgetPersistence.NewsWidgetState) {
        if (newsWidget == null) {
            newsWidget = NewsWidget(state.x, state.y, state.width, state.height).apply {
                hookupSingleton(newsDesc, onClose = { executeDefinition(commandRegistry.toggleNews) })
                listener = object : NewsWidgetListener {
                    override fun onStateChanged() = notifyContentChanged()
                    override fun onDataRequested(countryCode: String, force: Boolean, reason: String) {
                        dataProvider?.requestNewsData(countryCode, force, reason)
                    }
                    override fun onSourceDataRequested(sourceSpec: NewsSourceSpec, countryCode: String, force: Boolean, reason: String, completion: (Result<NewsSnapshot>) -> Unit) {
                        dataProvider?.requestNewsSourceData(sourceSpec, countryCode, force, reason, completion)
                            ?: completion(Result.failure(IllegalStateException("News data service is unavailable")))
                    }
                    override fun onSourceInputFocusChanged(focused: Boolean) { contentObserver?.onFocusChanged(focused) }
                }
                pendingNewsCountryCode?.let { setCountryCode(it) }
            }
        }
        persistenceHelper.restoreNewsWidgetPosition(newsWidget, state, width, height)
        applyFullscreenState(newsWidget, state.isFullscreen)
        newsWidget?.requestData(force = true, reason = "widget_restored")
        notifyContentChanged()
    }

    private fun applyNotificationsState(state: WidgetPersistence.NotificationsWidgetState) {
        if (notificationsWidget == null) {
            notificationsWidget = NotificationsWidget(context, state.x, state.y, state.width, state.height, height.toFloat()).apply {
                hookupSingleton(notificationsDesc, onClose = { executeDefinition(commandRegistry.toggleNotifications) })
                onStateChanged = { notifyContentChanged() }
            }
        }
        persistenceHelper.restoreNotificationsWidgetPosition(notificationsWidget, state, width, height)
        notificationsWidget?.setScreenHeight(height.toFloat())
        notificationsWidget?.setNotificationsSnapshot(latestNotificationsSnapshot)
        applyFullscreenState(notificationsWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create speedometer widget with the given state.
     */
    private fun applySpeedometerState(state: WidgetPersistence.SpeedometerWidgetState) {
        if (speedometerWidget == null) {
            speedometerWidget = createSpeedometerWidget(state.x, state.y, state.width, state.height)
        }
        persistenceHelper.restoreSpeedometerWidgetPosition(speedometerWidget, state, width, height)
        applyFullscreenState(speedometerWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create subtitle widget with capture off.
     */
    private fun applySubtitleState(state: WidgetPersistence.SubtitleWidgetState) {
        if (subtitleWidget == null) {
            subtitleWidget = createSubtitleWidget(state.x, state.y, state.width, state.height, autoStart = false)
        }
        subtitleWidget?.restoreOptions(
            phonePlaybackEnabled = state.phonePlaybackEnabled,
            microphoneEnabled = state.microphoneEnabled,
            translationEnabled = state.translationEnabled
        )
        subtitleWidget?.setCaptureEnabled(false, notifyPhone = false)
        persistenceHelper.restoreSubtitleWidgetPosition(subtitleWidget, state, width, height)
        applyFullscreenState(subtitleWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create mirror widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    private fun applyMirrorState(state: WidgetPersistence.MirrorWidgetState) {
        // Create mirror widget if it doesn't exist
        if (screenMirrorWidget == null) {
            screenMirrorWidget = ScreenMirrorWidget(context, state.x, state.y, state.width, state.height).apply {
                configureScreenMirrorWidget(this)
            }
        }
        persistenceHelper.restoreMirrorWidgetPosition(screenMirrorWidget, state, width, height)
        applyFullscreenState(screenMirrorWidget, state.isFullscreen)
        screenMirrorWidget?.onDisplayVisibilityChanged(
            screenMirrorWidget?.let { isMirrorWidgetVisible(it) } ?: false
        )
        notifyMediaBrightnessCapChanged()
        notifyContentChanged()
    }

    fun isReady(): Boolean = width > 0 && height > 0

    // ==================== Screen Mirroring ====================

    fun setMirroringEnabled(enabled: Boolean, phoneIp: String? = null) {
        screenMirrorWidget?.setMirroringEnabled(enabled, phoneIp)
        screenMirrorWidget?.onDisplayVisibilityChanged(
            screenMirrorWidget?.let { isMirrorWidgetVisible(it) } ?: false
        )
        notifyMediaBrightnessCapChanged()
        notifyContentChanged()
    }

    fun onTrimMemory(level: Int) {
        browserWidgets.forEach { it.onTrimMemory(level) }
    }

    fun onLowMemory() {
        browserWidgets.forEach { it.onLowMemory() }
    }

    fun clearDebugWebViewData(profileSummary: String) {
        browserWidgets.forEach { it.clearDebugWebViewData(profileSummary) }
    }

    fun release() {
        presentationController.dismiss()
        lifecycleController.closeAll(
            widgets = registry.allWidgets(),
            teardown = ::destroyWidget,
            clearRegistry = registry::clear
        )
    }
}
