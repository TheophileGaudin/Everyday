package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.binocular.BinocularContentClass
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.sync.WeatherSnapshot
import java.io.File

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
        private const val DEFAULT_SPEED_VISIBILITY_THRESHOLD_KMH = 0.5f
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
    private val persistenceHelper = WidgetPersistenceHelper(context)
    private val threeDofOverlay = ThreeDofOverlay()
    private val smartAlignment = SmartAlignmentHelper(registry)
    private val smartAlignmentRenderer = SmartAlignmentRenderer()
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

    private val closeAppHoverControl = CloseAppHoverControl {
        closeConfirmationPopup?.show()
        notifyContentChanged()
    }
    private val youtubeHistoryHoverControl = YouTubeHistoryHoverControl {
        createYouTubeBrowserWidget()
    }

    // Mutable list of lemon shortcuts; loaded lazily from disk on first access so the
    // file read doesn't happen during construction (context.getExternalFilesDir is fine
    // but disk I/O during init is best avoided).
    private var lemonShortcuts: MutableList<ShortcutAction>? = null
    private fun ensureLemonShortcuts(): MutableList<ShortcutAction> {
        val cached = lemonShortcuts
        if (cached != null) return cached
        val loaded = ShortcutsStore.load(context).toMutableList()
        lemonShortcuts = loaded
        return loaded
    }

    private val lemonHoverControl: LemonHoverControl = LemonHoverControl(
        shortcutsProvider = { ensureLemonShortcuts() },
        onActionSelected = { action -> runShortcutAction(action) },
        invalidate = { invalidateHoverVisuals() }
    )

    private val hoverControls: List<BaseHoverControl> = listOf(
        closeAppHoverControl,
        youtubeHistoryHoverControl,
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

    var calendarWidget: CalendarWidget?
        get() = registry.calendarWidget
        private set(value) { registry.calendarWidget = value }

    // Pending data for location widget
    private var pendingLocationAddress: String? = null
    private var pendingLocationWeather: String? = null
    private var pendingLocationSunriseEpochMs: Long? = null
    private var pendingLocationSunsetEpochMs: Long? = null
    private var pendingCalendarSnapshot: GoogleCalendarSnapshot? = null

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
    private var speedUnit: SpeedometerWidget.SpeedUnit = SpeedometerWidget.SpeedUnit.KMH

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
        notifyBrightnessChanged = { value -> onBrightnessChanged?.invoke(value) },
        notifyAdaptiveBrightnessChanged = { enabled -> onAdaptiveBrightnessChanged?.invoke(enabled) },
        notifyHeadUpTimeChanged = { ms -> onHeadUpTimeChanged?.invoke(ms) },
        notifyWakeDurationChanged = { ms -> onWakeDurationChanged?.invoke(ms) },
        notifyAngleThresholdChanged = { degrees -> onAngleThresholdChanged?.invoke(degrees) },
        notifyHeadsUpEnabledChanged = { enabled -> onHeadsUpEnabledChanged?.invoke(enabled) },
        notifySmartAlignmentChanged = { enabled -> onSmartAlignmentChanged?.invoke(enabled) },
        notifySpeedVisibilityThresholdChanged = { threshold -> onSpeedVisibilityThresholdChanged?.invoke(threshold) },
        connectGoogle = { onConnectGoogle?.invoke() },
        grantCalendarAccess = { onGrantCalendarAccess?.invoke() },
        disconnectGoogle = { onDisconnectGoogle?.invoke() },
        retryGoogleAuth = { onRetryGoogleAuth?.invoke() },
        shortcutsProvider = { ensureLemonShortcuts().toList() },
        onShortcutsChanged = { updated -> applyShortcuts(updated) },
        savedLayoutNamesProvider = {
            persistenceManager.listLayouts().map { it.name }
        }
    )
    private var layoutNamePrompt: WidgetLayoutNamePrompt? = null
    private val persistenceManager = PersistenceManager(
        context = context,
        handler = Handler(Looper.getMainLooper()),
        captureState = ::captureWidgetStateWithoutLayoutName
    )
    private val layoutManager = WidgetLayoutManager(
        context = context,
        persistenceManager = persistenceManager,
        canApplyLayout = ::isReady,
        captureState = ::captureWidgetStateWithoutLayoutName,
        applyState = ::applyPersistedStateReplacingCurrent,
        buildDeliveredLayout = { name -> BuiltInLayouts.build(name, width.toFloat(), height.toFloat()) },
        showNamePrompt = { suggestedName ->
            dismissMenu()
            layoutNamePrompt?.show(suggestedName)
            onFocusChanged?.invoke(true)
        },
        showNameError = { error -> layoutNamePrompt?.showError(error) },
        dismissNamePrompt = { layoutNamePrompt?.dismiss() },
        showDeleteConfirmation = { name -> layoutDeleteConfirmationPopup?.show(name) },
        beforeApplyLayout = ::requestLayoutSwitchBrightness,
        notifyContentChanged = ::notifyContentChanged
    )

    // Callback to apply window-level brightness (set by MainActivity)
    var onBrightnessChanged: ((Float) -> Unit)? = null
    var onAdaptiveBrightnessChanged: ((Boolean) -> Unit)? = null
    var onMediaBrightnessCapChanged: ((Boolean) -> Unit)? = null
    var onBrowserMinimumBrightnessChanged: ((Boolean) -> Unit)? = null
    var onLayoutSwitchBrightnessRequested: (() -> Unit)? = null

    // Callbacks for head-up wake settings (set by MainActivity)
    var onHeadUpTimeChanged: ((Long) -> Unit)? = null
    var onWakeDurationChanged: ((Long) -> Unit)? = null
    var onAngleThresholdChanged: ((Float) -> Unit)? = null
    var onHeadsUpEnabledChanged: ((Boolean) -> Unit)? = null
    var onSmartAlignmentChanged: ((Boolean) -> Unit)? = null
    var onConnectGoogle: (() -> Unit)? = null
    var onGrantCalendarAccess: (() -> Unit)? = null
    var onDisconnectGoogle: (() -> Unit)? = null
    var onRetryGoogleAuth: (() -> Unit)? = null
    var onGoogleAuthBrowserClosed: (() -> Unit)? = null

    // Access to widget lists via registry
    private val widgets: MutableList<TextBoxWidget>
        get() = registry.textWidgets
    
    private val browserWidgets: MutableList<BrowserWidget>
        get() = registry.browserWidgets

    private val imageWidgets: MutableList<ImageWidget>
        get() = registry.imageWidgets

    private val fileBrowserWidgets: MutableList<FileBrowserWidget>
        get() = registry.fileBrowserWidgets

    // Currently focused widget
    private var focusedWidget: TextBoxWidget? = null
    private var focusedBrowserWidget: BrowserWidget? = null

    // Widget being dragged
    private var activeWidget: BaseWidget? = null
    private var isWidgetLayoutLocked = false

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
    private var fullscreenFileBrowserWidget: FileBrowserWidget? = null
    private var googleAuthBrowserWidget: BrowserWidget? = null
    private var isStatusBarFullscreen = false
    private var isLocationWidgetFullscreen = false
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
        listOf(mirrorDesc, statusBarDesc, locationDesc, calendarDesc, financeDesc, newsDesc, notificationsDesc, speedometerDesc, subtitleDesc)

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

    // ==================== Callbacks ====================
    
    var onFocusChanged: ((Boolean) -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null
    var onTransientContentChanged: (() -> Unit)? = null
    var onMediaFrame: ((String) -> Unit)? = null
    var onBinocularContentSourceChanged: ((String, BinocularContentClass, Boolean) -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null
    var onMirrorRequest: ((Boolean) -> Unit)? = null
    var onBrowserKeyboardRequest: ((Boolean) -> Unit)? = null
    var onClipboardRequest: (() -> Unit)? = null
    var onFilePickerRequest: (() -> Unit)? = null
    var onStoragePermissionRequest: (() -> Unit)? = null
    var onCursorCenterRequested: (() -> Unit)? = null
    var onSpeedUnitChanged: ((SpeedometerWidget.SpeedUnit) -> Unit)? = null
    var onSpeedVisibilityThresholdChanged: ((Float) -> Unit)? = null
    var onFinanceDataRequested: ((visibleConfigs: List<FinanceDashboardTileConfig>, refreshTileIds: List<String>, force: Boolean, reason: String) -> Unit)? = null
    var onFinanceVisibilityChanged: ((visible: Boolean) -> Unit)? = null
    var onNewsDataRequested: ((countryCode: String, force: Boolean, reason: String) -> Unit)? = null
    var onSubtitleControlRequested: ((SubtitleControl) -> Unit)? = null

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
        setupPersistenceHelperCallbacks()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    private fun setupPersistenceHelperCallbacks() {
        persistenceHelper.onWidgetFocusChanged = { widget, focused ->
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
        persistenceHelper.onWidgetDeleted = { widget -> removeWidget(widget) }
        persistenceHelper.onBrowserWidgetClosed = { widget -> removeBrowserWidget(widget) }
        persistenceHelper.onWidgetStateChanged = { notifyContentChanged() }
        persistenceHelper.onTextWidgetFullscreenToggled = { widget, isFullscreen ->
            handleTextWidgetFullscreenToggle(widget, isFullscreen)
        }
        persistenceHelper.onTextWidgetMinimizeToggled = { widget, isMinimized ->
            handleTextWidgetMinimizeToggle(widget, isMinimized)
        }
        persistenceHelper.onBrowserWidgetFullscreenToggled = { widget, isFullscreen ->
            handleBrowserWidgetFullscreenToggle(widget, isFullscreen)
        }
        persistenceHelper.onMinimizeToggled = { widget, isMinimized ->
            handleBrowserWidgetMinimizeToggle(widget, isMinimized)
        }
        persistenceHelper.onBrowserKeyboardRequest = { show ->
            onBrowserKeyboardRequest?.invoke(show)
        }
    }

    // ==================== Public State Queries ====================

    fun hasFullscreenWidget(): Boolean = fullscreenTextWidget != null ||
            fullscreenBrowserWidget != null ||
            fullscreenImageWidget != null ||
            fullscreenFileBrowserWidget != null ||
            isStatusBarFullscreen ||
            isLocationWidgetFullscreen ||
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

    private fun notifyContentChanged() {
        invalidate()
        postInvalidate()
        (parent as? View)?.invalidate()
        if (!suppressContentChangedCallback) {
            onContentChanged?.invoke()
        }
    }

    private fun notifyTransientContentChanged() {
        invalidate()
        postInvalidate()
        (parent as? View)?.invalidate()
        onTransientContentChanged?.invoke()
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
        onMediaFrame?.invoke(sourceId)
    }

    private fun setBinocularMediaSourceActive(sourceId: String, active: Boolean) {
        onBinocularContentSourceChanged?.invoke(sourceId, BinocularContentClass.MEDIA, active)
    }

    private fun notifyMediaBrightnessCapChanged() {
        onMediaBrightnessCapChanged?.invoke(shouldApplyMediaBrightnessCap())
    }

    private fun notifyBrowserMinimumBrightnessChanged() {
        onBrowserMinimumBrightnessChanged?.invoke(shouldApplyBrowserMinimumBrightness())
    }

    private fun requestLayoutSwitchBrightness() {
        onLayoutSwitchBrightnessRequested?.invoke()
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
        widget.onFocusChanged = { focused ->
            if (focused) {
                setFocusedWidget(widget)
            } else if (focusedBrowserWidget == widget) {
                setFocusedWidget(null)
            }
        }
        widget.onCloseRequested = { removeBrowserWidget(widget) }
        widget.onStateChanged = {
            widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
            notifyTransientContentChanged()
        }
        widget.onFullscreenToggled = { isFullscreen ->
            handleBrowserWidgetFullscreenToggle(widget, isFullscreen)
        }
        widget.onMinimizeToggled = { isMinimized ->
            handleBrowserWidgetMinimizeToggle(widget, isMinimized)
        }
        widget.onRequestKeyboard = { show ->
            onBrowserKeyboardRequest?.invoke(show)
        }
        widget.onDisplayVisibilityChanged(isBrowserWidgetVisible(widget))
        notifyBrowserMinimumBrightnessChanged()
    }

    private fun configureScreenMirrorWidget(widget: ScreenMirrorWidget) {
        widget.onStateChanged = { state ->
            when (state) {
                ScreenMirrorWidget.State.MOVING -> mirrorDesc.dragging = true
                ScreenMirrorWidget.State.IDLE -> mirrorDesc.dragging = false
                else -> {}
            }
            widget.onDisplayVisibilityChanged(isMirrorWidgetVisible(widget))
            notifyTransientContentChanged()
        }
        widget.onStreamStateChanged = {
            notifyMediaBrightnessCapChanged()
            notifyContentChanged()
        }
        widget.onFrameReady = {
            if (widget.isBinocularMediaActive()) {
                notifyMediaFrame(widget.binocularSourceId)
            }
        }
        widget.onMediaStateChanged = { active ->
            setBinocularMediaSourceActive(widget.binocularSourceId, active)
        }
        widget.hookupSingleton(mirrorDesc)
        widget.onMirrorRequest = { start ->
            this@WidgetContainer.onMirrorRequest?.invoke(start)
        }
        widget.onCloseRequested = { toggleScreenMirrorWidget() }
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

        contextMenu = ContextMenu(w.toFloat(), h.toFloat()).apply {
            setLayoutLockState(isWidgetLayoutLocked)
            onItemSelected = { item -> handleMenuItemSelected(item) }
            onSubmenuItemSelected = { _, subItem -> handleSubmenuItemSelected(subItem) }
            onSubmenuItemDoubleTapped = { _, subItem -> handleSubmenuItemDoubleTapped(subItem) }
            onSubmenuWillShow = { item -> getUpdatedSubmenuItems(item) }
            onDismissed = { notifyContentChanged() }
        }

        textEditMenu = TextEditContextMenu(w.toFloat(), h.toFloat()).apply {
            onCut = { handleCut() }
            onCopy = { handleCopy() }
            onPaste = { content -> handlePaste(content) }
            onSelectAll = { handleSelectAll() }
            onFormatting = { showFormattingMenu() }
            onDismissed = { notifyContentChanged() }
        }

        formattingMenu = FormattingMenu(w.toFloat(), h.toFloat()).apply {
            onFontSizeChanged = { size -> handleFontSizeChanged(size) }
            onTextWrapToggled = { wrap -> handleTextWrapToggled(wrap) }
            onBoldToggled = { _ -> handleBoldToggled() }
            onItalicToggled = { _ -> handleItalicToggled() }
            onUnderlineToggled = { _ -> handleUnderlineToggled() }
            onBulletListToggled = { _ -> handleBulletListToggled() }
            onNumberedListToggled = { _ -> handleNumberedListToggled() }
            onColumnsChanged = { columns ->
                (focusedWidget as? TextBoxWidget)?.setColumns(columns)
                notifyContentChanged()
            }
            onDismissed = { notifyContentChanged() }
        }

        closeConfirmationPopup = CloseConfirmationPopup(w.toFloat(), h.toFloat()).apply {
            onCloseConfirmed = { onCloseRequested?.invoke() }
            onDismissed = { notifyContentChanged() }
        }

        layoutDeleteConfirmationPopup = WidgetLayoutDeleteConfirmationPopup(w.toFloat(), h.toFloat()).apply {
            onDeleteConfirmed = { name -> layoutManager.deleteLayoutByName(name) }
            onDismissed = { notifyContentChanged() }
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
            onSubmitted = { name -> layoutManager.saveLayoutWithName(name) }
            onDismissed = {
                onFocusChanged?.invoke(hasFocusedWidget())
                notifyContentChanged()
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
        youtubeHistoryHoverControl.setBounds(
            containerWidth - YouTubeHistoryHoverControl.DEFAULT_SIZE,
            containerHeight - YouTubeHistoryHoverControl.DEFAULT_SIZE,
            YouTubeHistoryHoverControl.DEFAULT_SIZE
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
        if (desc.isFullscreen()) {
            desc.widget()?.let { if (it.isFullscreen) it.toggleFullscreen() }
            desc.setFullscreen(false)
        }
    }

    /** Wires the two standard singleton lifecycle callbacks on a widget inside an apply block. */
    private fun BaseWidget.hookupSingleton(desc: SingletonDesc) {
        onFullscreenToggled = { fs  -> handleSingletonFullscreenToggle(desc, fs) }
        onMinimizeToggled   = { min -> handleSingletonMinimizeToggle(desc, min) }
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

    fun setSpeedUnit(unit: SpeedometerWidget.SpeedUnit) {
        if (speedUnit == unit) {
            speedometerWidget?.setUnit(unit)
            return
        }
        speedUnit = unit
        speedometerWidget?.setUnit(unit)
        onSpeedUnitChanged?.invoke(unit)
    }

    fun getSpeedUnit(): SpeedometerWidget.SpeedUnit = speedUnit

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
            hookupSingleton(speedometerDesc)
            onCloseRequested = { toggleSpeedometerWidget() }
            onUnitChanged = { newUnit ->
                speedUnit = newUnit
                onSpeedUnitChanged?.invoke(newUnit)
                notifyContentChanged()
            }
            setUnit(speedUnit)
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
            hookupSingleton(subtitleDesc)
            onCloseRequested = { toggleSubtitleWidget() }
            onStateChanged = { notifyContentChanged() }
            onSubtitleControlRequested = { control -> this@WidgetContainer.onSubtitleControlRequested?.invoke(control) }
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

    fun setGoogleCalendarSnapshot(snapshot: GoogleCalendarSnapshot?) {
        pendingCalendarSnapshot = snapshot
        calendarWidget?.setSnapshot(snapshot)
        notifyContentChanged()
    }

    fun applyWeatherSnapshot(snapshot: WeatherSnapshot) {
        val weather = snapshot.weather ?: return
        val addressText = "${snapshot.town}, ${snapshot.countryCode}"
        val tempText = "${weather.tempCurrent}\u00B0C"
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
        updateFinanceCountryCode(snapshot.countryCode)
        updateNewsCountryCode(snapshot.countryCode)
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

    fun currentFinanceVisibleConfigs(): List<FinanceDashboardTileConfig> =
        financeWidget?.getVisibleTileConfigs().orEmpty()

    // ==================== Z-Order Management ====================

    private fun bringWidgetToFront(widget: BaseWidget) {
        registry.bringToFront(widget, parent as? ViewGroup, this)
        notifyContentChanged()
    }

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
        for (desc in singletonDescs) {
            val hit = desc.dragHit(cx, cy)
            if (hit == DragHit.NONE) continue
            desc.widget()?.let { bringWidgetToFront(it) }
            desc.pendingDrag = true
            pendingDragIsResize = hit == DragHit.RESIZE
            scheduleLongPress()
            return
        }

        for (widget in browserWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { val hitArea = widget.hitTest(cx, cy); bringWidgetToFront(widget); if (hitArea == BrowserWidget.HitArea.CONTENT) { handleBrowserContentDown(widget, cx, cy); return } else if (hitArea == BrowserWidget.HitArea.BORDER) { pendingDragWidget = widget; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BrowserWidget.HitArea.RESIZE_HANDLE) { pendingDragWidget = widget; pendingDragIsResize = true; scheduleLongPress(); return } } }
        for (widget in imageWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { val hitArea = widget.baseHitTest(cx, cy); bringWidgetToFront(widget); if (hitArea == BaseWidget.BaseHitArea.BORDER || hitArea == BaseWidget.BaseHitArea.CONTENT) { pendingDragWidget = widget; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BaseWidget.BaseHitArea.RESIZE_HANDLE) { pendingDragWidget = widget; pendingDragIsResize = true; scheduleLongPress(); return } } }
        for (widget in fileBrowserWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { val hitArea = widget.hitTest(cx, cy); bringWidgetToFront(widget); if (hitArea == FileBrowserWidget.HitArea.BORDER) { pendingDragWidget = widget; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == FileBrowserWidget.HitArea.RESIZE_HANDLE) { pendingDragWidget = widget; pendingDragIsResize = true; scheduleLongPress(); return } else if (hitArea == FileBrowserWidget.HitArea.CONTENT || hitArea == FileBrowserWidget.HitArea.FILE_ITEM) { return } } }
        for (widget in widgets.reversed()) { if (widget.containsPoint(cx, cy)) { val hitArea = widget.hitTest(cx, cy); bringWidgetToFront(widget); if (hitArea == TextBoxWidget.HitArea.BORDER) { pendingDragWidget = widget; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == TextBoxWidget.HitArea.RESIZE_HANDLE) { pendingDragWidget = widget; pendingDragIsResize = true; scheduleLongPress(); return } else if (hitArea == TextBoxWidget.HitArea.CONTENT) { return } } }
    }

    private fun scheduleLongPress() {
        longPressRunnable = Runnable {
            if (!cursorPressed) return@Runnable
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

    private fun updateHover(x: Float, y: Float, isVisible: Boolean) {
        // The hover-controls editor handles its own cursor state and the dashboard widgets/
        // controls behind it are hidden, so suppress the regular hover pass.
        if (settingsController.isHoverControlsLayoutEditorVisible()) {
            hoverControls.forEach { it.clearHover() }
            registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            if (!isTransitioningDisplayState) invalidateHoverVisuals()
            return
        }

        if (!isVisible) {
            hoverControls.forEach { it.clearHover() }
            closeConfirmationPopup?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); formattingMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); textEditMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); contextMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            layoutDeleteConfirmationPopup?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            layoutNamePrompt?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            if (hasFullscreenWidget()) { fullscreenTextWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); fullscreenBrowserWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); fullscreenImageWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isStatusBarFullscreen) statusBarWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isLocationWidgetFullscreen) locationWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isCalendarWidgetFullscreen) calendarWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isFinanceWidgetFullscreen) financeWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isNewsWidgetFullscreen) newsWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isNotificationsWidgetFullscreen) notificationsWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isSpeedometerWidgetFullscreen) speedometerWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isSubtitleWidgetFullscreen) subtitleWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isMirrorWidgetFullscreen) screenMirrorWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
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
            fullscreenTextWidget?.updateHover(if (fullscreenTextWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            fullscreenBrowserWidget?.updateHover(if (fullscreenBrowserWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            fullscreenImageWidget?.updateHover(if (fullscreenImageWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            fullscreenFileBrowserWidget?.updateHover(if (fullscreenFileBrowserWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isStatusBarFullscreen) statusBarWidget?.updateHover(if (statusBarWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isLocationWidgetFullscreen) locationWidget?.updateHover(if (locationWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isCalendarWidgetFullscreen) calendarWidget?.updateHover(if (calendarWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isFinanceWidgetFullscreen) financeWidget?.updateHover(if (financeWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isNewsWidgetFullscreen) newsWidget?.updateHover(if (newsWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isNotificationsWidgetFullscreen) notificationsWidget?.updateHover(if (notificationsWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isSpeedometerWidgetFullscreen) speedometerWidget?.updateHover(if (speedometerWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isSubtitleWidgetFullscreen) subtitleWidget?.updateHover(if (subtitleWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
            if (isMirrorWidgetFullscreen) screenMirrorWidget?.updateHover(if (screenMirrorWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
        } else { 
            // Dashboard settings doesn't need hover updates for now, as it handles its own internal state on drag
            // If we needed hover effects (like button highlighting), we'd implement updateHover in DashboardSettings.
            screenMirrorWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            statusBarWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            locationWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            calendarWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            financeWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            newsWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            notificationsWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            speedometerWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            subtitleWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            // Consolidate widget hover updates by iterating over all widgets sorted by Z-order (reverse order isn't strictly necessary for hover STATE, but good for consistency)
            // However, since updateHover doesn't return "consumed", we can just iterate all lists or the registry.
            // The logic: if dragging, don't update hover.
            
            for (widget in browserWidgets) { 
                if (widget.baseState != BaseWidget.BaseState.MOVING && widget.baseState != BaseWidget.BaseState.RESIZING) { 
                    if (widget.containsPoint(cx, cy)) widget.updateHover(cx, cy) else widget.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) 
                } 
            }
            for (widget in imageWidgets) {
                if (widget.baseState != BaseWidget.BaseState.MOVING && widget.baseState != BaseWidget.BaseState.RESIZING) {
                    if (widget.containsPoint(cx, cy)) widget.updateHover(cx, cy) else widget.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
                }
            }
            for (widget in fileBrowserWidgets) {
                if (widget.baseState != BaseWidget.BaseState.MOVING && widget.baseState != BaseWidget.BaseState.RESIZING) {
                    if (widget.containsPoint(cx, cy)) widget.updateHover(cx, cy) else widget.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
                }
            }
            for (widget in widgets) {
                if (widget.baseState != BaseWidget.BaseState.MOVING && widget.baseState != BaseWidget.BaseState.RESIZING) {
                    if (widget.containsPoint(cx, cy)) widget.updateHover(cx, cy) else widget.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
                }
            }
        }

        if (!isTransitioningDisplayState) {
            invalidateHoverVisuals()
        }
    }

    private fun clearWidgetHoverStates() {
        if (hasFullscreenWidget()) {
            fullscreenTextWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fullscreenBrowserWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fullscreenImageWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            fullscreenFileBrowserWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        }
        registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
    }

    private fun toContentSpace(screenX: Float, screenY: Float): Pair<Float, Float> {
        return screenToContentCoordinates(screenX, screenY)
    }

    private fun handleDragOperations(x: Float, y: Float, dx: Float, dy: Float) {
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

    fun scrollWidgetAtCursor(cursorX: Float, cursorY: Float, dy: Float) {
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
        for (widget in fileBrowserWidgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
        for (widget in widgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
    }

    private fun scrollFullscreenWidgetAt(cx: Float, cy: Float, dy: Float): Boolean {
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

    fun onTap(x: Float, y: Float): Boolean {
        // Menus use screen-space coordinates (head-locked)
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
            fullscreenFileBrowserWidget?.let { if (it.containsPoint(cx, cy)) return handleFileBrowserWidgetTap(it, cx, cy); return false }
            if (isStatusBarFullscreen) { statusBarWidget?.let { if (it.containsPoint(cx, cy)) return handleStatusBarTap(it, cx, cy) }; return false }
            if (isLocationWidgetFullscreen) { locationWidget?.let { if (it.containsPoint(cx, cy)) return handleLocationWidgetTap(it, cx, cy) }; return false }
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
        screenMirrorWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleMirrorWidgetTap(it, cx, cy) } }
        statusBarWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleStatusBarTap(it, cx, cy) } }
        locationWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleLocationWidgetTap(it, cx, cy) } }
        calendarWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleCalendarWidgetTap(it, cx, cy) } }
        financeWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleFinanceWidgetTap(it, cx, cy) } }
        newsWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleNewsWidgetTap(it, cx, cy) } }
        notificationsWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleNotificationsWidgetTap(it, cx, cy) } }
        speedometerWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleSpeedometerWidgetTap(it, cx, cy) } }
        subtitleWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleSubtitleWidgetTap(it, cx, cy) } }
        for (widget in browserWidgets.reversed()) {
            if (widget.containsPoint(cx, cy)) {
                if (widget.isMinimized) { widget.toggleMinimize(); notifyContentChanged(); return true }
                if (widget.hitTest(cx, cy) == BrowserWidget.HitArea.CONTENT && shouldSuppressBrowserContentTap(widget)) {
                    return true
                }
                val result = widget.onTap(cx, cy)
                if (widget.isEditingAddress()) ensureDrawnAboveNativeOverlays()
                return result
            }
        }
        for (widget in imageWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { if (widget.isMinimized) { widget.toggleMinimize(); notifyContentChanged(); return true }; return handleImageWidgetTap(widget, cx, cy) } }
        for (widget in fileBrowserWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { if (widget.isMinimized) { widget.toggleMinimize(); notifyContentChanged(); return true }; return handleFileBrowserWidgetTap(widget, cx, cy) } }
        for (widget in widgets.reversed()) { if (widget.containsPoint(cx, cy)) { if (widget.isMinimized) { widget.toggleMinimize(); notifyContentChanged(); return true }; return handleTextWidgetTap(widget, cx, cy) } }
        if (focusedWidget != null || focusedBrowserWidget != null) { unfocusAll(); notifyContentChanged(); return true }
        return false
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
        BaseWidget.BaseHitArea.CLOSE_BUTTON      -> { widget.onCloseRequested?.invoke(); notifyContentChanged(); true }
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

    private fun handleLocationWidgetTap(loc: LocationWidget, x: Float, y: Float): Boolean =
        handleBaseTap(loc, x, y)

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
        when (item.id) { 
            "layout_lock" -> setWidgetLayoutLocked(!isWidgetLayoutLocked)
            "create_textbox" -> createTextBox(pendingCreateX, pendingCreateY)
            "create_browser" -> createBrowserWidget(pendingCreateX, pendingCreateY)
            "open_file" -> onFilePickerRequest?.invoke()
            "settings" -> settingsController.toggleDashboardSettings(width.toFloat(), height.toFloat())
            "close_app" -> triggerCloseAppConfirmation()
        }
        notifyContentChanged()
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
            return listOf(
                ContextMenu.SubMenuItem("toggle_status", "System", statusBarWidget != null),
                ContextMenu.SubMenuItem("toggle_location", "Location/Weather", locationWidget != null),
                ContextMenu.SubMenuItem("toggle_calendar", "Calendar", calendarWidget != null),
                ContextMenu.SubMenuItem("toggle_speedometer", "Speedometer", speedometerWidget != null),
                ContextMenu.SubMenuItem("toggle_finance", "Finance", financeWidget != null),
                ContextMenu.SubMenuItem("toggle_news", "News", newsWidget != null),
                ContextMenu.SubMenuItem("toggle_notifications", "Notifications", notificationsWidget != null),
                ContextMenu.SubMenuItem("toggle_subtitle", "Subtitles", subtitleWidget != null),
                ContextMenu.SubMenuItem("toggle_mirror", "Screen Mirror", screenMirrorWidget != null)
            )
        }
        if (item.id == "layouts") {
            return layoutManager.layoutSubmenuItems()
        }
        return item.submenu ?: emptyList()
    }

    /**
     * Handle submenu item selection (widget toggles).
     */
    private fun handleSubmenuItemSelected(subItem: ContextMenu.SubMenuItem) {
        when (subItem.id) {
            "toggle_status" -> toggleStatusBarWidget()
            "toggle_location" -> toggleLocationWidget()
            "toggle_calendar" -> toggleCalendarWidget()
            "toggle_speedometer" -> toggleSpeedometerWidget()
            "toggle_finance" -> toggleFinanceWidget()
            "toggle_news" -> toggleNewsWidget()
            "toggle_notifications" -> toggleNotificationsWidget()
            "toggle_subtitle" -> toggleSubtitleWidget()
            "toggle_mirror" -> toggleScreenMirrorWidget()
            else -> {
                if (layoutManager.handleSubmenuItemSelected(subItem)) return
            }
        }
        notifyContentChanged()
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
        return layoutManager.handleSubmenuItemDoubleTapped(subItem)
    }

    /**
     * Toggle StatusBarWidget: remove if present, create at default location if absent.
     */
    private fun toggleStatusBarWidget() {
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
                restoreStatusBarWidget(it)
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
                hookupSingleton(statusBarDesc)
                onCloseRequested = { toggleStatusBarWidget() }
            }
            Log.d(TAG, "StatusBarWidget created")
        }
    }

    /**
     * Toggle LocationWidget: remove if present, create at default location if absent.
     */
    private fun toggleLocationWidget() {
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
                restoreLocationWidget(it)
                Log.d(TAG, "LocationWidget restored from last closed state")
                return
            }
            // Create at default location
            val locWidth = 300f
            val locHeight = 120f
            locationWidget = LocationWidget(context, 20f, height - locHeight - 20f, locWidth, locHeight).apply {
                hookupSingleton(locationDesc)
                onCloseRequested = { toggleLocationWidget() }
                if (pendingLocationAddress != null || pendingLocationWeather != null) {
                    setLocationData(pendingLocationAddress ?: "Loading...", pendingLocationWeather ?: "Checking weather...")
                }
                setSunTimes(pendingLocationSunriseEpochMs, pendingLocationSunsetEpochMs)
            }
            Log.d(TAG, "LocationWidget created")
        }
    }

    /**
     * Toggle CalendarWidget: remove if present, create at default location if absent.
     */
    private fun toggleCalendarWidget() {
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
                restoreCalendarWidget(it)
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
                hookupSingleton(calendarDesc)
                onCloseRequested = { toggleCalendarWidget() }
                onStateChanged = { notifyContentChanged() }
                setSnapshot(pendingCalendarSnapshot)
            }
            Log.d(TAG, "CalendarWidget created")
        }
    }

    /**
     * Toggle SpeedometerWidget: remove if present, create at default location if absent.
     */
    private fun toggleSpeedometerWidget() {
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
                restoreSpeedometerWidget(it)
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
    private fun toggleFinanceWidget() {
        if (financeWidget != null) {
            financeWidget?.let {
                closedWidgetTemplates = closedWidgetTemplates.copy(
                    finance = persistenceHelper.getFinanceWidgetState(it)
                )
            }
            teardownSingleton(financeDesc)
            onFinanceVisibilityChanged?.invoke(false)
            financeWidget?.onDestroy()
            financeWidget = null
            Log.d(TAG, "FinanceWidget removed")
        } else {
            closedWidgetTemplates.finance?.let {
                restoreFinanceWidget(it)
                Log.d(TAG, "FinanceWidget restored from last closed state")
                return
            }
            val fWidth = FinanceWidget.DEFAULT_WIDTH
            val fHeight = FinanceWidget.DEFAULT_HEIGHT
            financeWidget = FinanceWidget(20f, 20f, fWidth, fHeight).apply {
                hookupSingleton(financeDesc)
                onCloseRequested = { toggleFinanceWidget() }
                onStateChanged = { notifyContentChanged() }
                onDataRequested = { visibleConfigs, refreshTileIds, force, reason ->
                    onFinanceDataRequested?.invoke(visibleConfigs, refreshTileIds, force, reason)
                }
                onMinimizeToggled = { isMinimized ->
                    handleSingletonMinimizeToggle(financeDesc, isMinimized)
                    onFinanceVisibilityChanged?.invoke(!isMinimized)
                    if (!isMinimized) requestData(force = false, reason = "finance_unminimized")
                }
                pendingFinanceCountryCode?.let { setCountryCode(it) }
                requestData(force = true, reason = "widget_toggled_on")
                onFinanceVisibilityChanged?.invoke(true)
            }
            Log.d(TAG, "FinanceWidget created")
        }
    }

    /**
     * Toggle NewsWidget: remove if present, create at default location if absent.
     */
    private fun toggleNewsWidget() {
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
                restoreNewsWidget(it)
                Log.d(TAG, "NewsWidget restored from last closed state")
                return
            }
            val nWidth = NewsWidget.DEFAULT_WIDTH
            val nHeight = NewsWidget.DEFAULT_HEIGHT
            val startX = (width - nWidth - 20f).coerceAtLeast(20f)
            val startY = 20f

            newsWidget = NewsWidget(startX, startY, nWidth, nHeight).apply {
                hookupSingleton(newsDesc)
                onCloseRequested = { toggleNewsWidget() }
                onStateChanged = { notifyContentChanged() }
                onDataRequested = { countryCode, force, reason ->
                    onNewsDataRequested?.invoke(countryCode, force, reason)
                }
                pendingNewsCountryCode?.let { setCountryCode(it) }
                requestData(force = true, reason = "widget_toggled_on")
            }
            Log.d(TAG, "NewsWidget created")
        }
    }

    private fun toggleNotificationsWidget() {
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
                restoreNotificationsWidget(it)
                Log.d(TAG, "NotificationsWidget restored from last closed state")
                return
            }
            val nWidth = NotificationsWidget.DEFAULT_WIDTH
            val nHeight = NotificationsWidget.DEFAULT_HEIGHT
            val startX = (width - nWidth - 20f).coerceAtLeast(20f)
            val startY = 20f
            notificationsWidget = NotificationsWidget(context, startX, startY, nWidth, nHeight, height.toFloat()).apply {
                hookupSingleton(notificationsDesc)
                onCloseRequested = { toggleNotificationsWidget() }
                onStateChanged = { notifyContentChanged() }
                setNotificationsSnapshot(latestNotificationsSnapshot)
            }
            Log.d(TAG, "NotificationsWidget created")
        }
    }

    /**
     * Toggle SubtitleWidget: remove if present, create at default location if absent.
     */
    private fun toggleSubtitleWidget() {
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
                restoreSubtitleWidget(it)
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
    private fun toggleScreenMirrorWidget() {
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
                restoreMirrorWidget(it)
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
        if (restoreLastClosedBrowserWidget() != null) {
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
        registry.addBrowserWidget(widget)
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        widget.toggleFullscreen()
        widget.setFocused(true)
        setFocusedWidget(widget)
        notifyContentChanged()
    }

    fun openBrowserAddress(address: String) {
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
        registry.addBrowserWidget(widget)
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        widget.toggleFullscreen()
        return widget
    }

    private fun removeBrowserWidget(widget: BrowserWidget) {
        persistenceHelper.getBrowserWidgetState(widget)?.let {
            closedWidgetTemplates = closedWidgetTemplates.copy(browser = it)
        }
        if (fullscreenBrowserWidget == widget) fullscreenBrowserWidget = null
        if (focusedBrowserWidget == widget) setFocusedWidget(null)
        if (activeWidget == widget) activeWidget = null
        if (googleAuthBrowserWidget == widget) googleAuthBrowserWidget = null
        destroyWidget(widget)
        registry.removeBrowserWidget(widget)
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        updateMinimizedWidgetPositions()
        notifyContentChanged()
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
        registry.addBrowserWidget(widget)
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
            onCloseRequested = { removeImageWidget(this) }
            onFullscreenToggled = { isFullscreen -> handleImageWidgetFullscreenToggle(this, isFullscreen) }
            onMinimizeToggled = { isMinimized -> handleImageWidgetMinimizeToggle(this, isMinimized) }
        }
        registry.addImageWidget(widget)
        notifyContentChanged()
    }

    private fun removeImageWidget(widget: ImageWidget) {
        val imagePath = widget.getImagePath()
        val hasSiblingUsingSamePath = imageWidgets.any { it !== widget && it.getImagePath() == imagePath }

        if (fullscreenImageWidget == widget) fullscreenImageWidget = null
        if (activeWidget == widget) activeWidget = null
        registry.removeImageWidget(widget)

        if (!hasSiblingUsingSamePath) {
            ImageWidgetStorage.deleteIfManaged(context, imagePath)
        }

        updateMinimizedWidgetPositions()
        notifyContentChanged()
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

        val widget = FileBrowserWidget(context, createX, createY, browserWidth, browserHeight, filter).apply {
            onCloseRequested = { removeFileBrowserWidget(this) }
            onFullscreenToggled = { isFullscreen -> handleFileBrowserWidgetFullscreenToggle(this, isFullscreen) }
            onMinimizeToggled = { isMinimized -> handleFileBrowserWidgetMinimizeToggle(this, isMinimized) }
            onStateChanged = { notifyContentChanged() }
            this.onFileSelected = { entry ->
                // Pass the FileEntry directly - caller can use URI or path as needed
                onFileSelected?.invoke(entry)
                // Close the file browser after selection
                removeFileBrowserWidget(this)
            }
        }
        registry.addFileBrowserWidget(widget)
        notifyContentChanged()
    }

    private fun removeFileBrowserWidget(widget: FileBrowserWidget) {
        if (fullscreenFileBrowserWidget == widget) fullscreenFileBrowserWidget = null
        if (activeWidget == widget) activeWidget = null
        registry.removeFileBrowserWidget(widget)
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    private fun handleFileBrowserWidgetFullscreenToggle(widget: FileBrowserWidget, isFullscreen: Boolean) {
        if (isFullscreen) { exitAllFullscreen(); fullscreenFileBrowserWidget = widget; widget.setFullscreenBounds(width.toFloat(), height.toFloat()) }
        else { fullscreenFileBrowserWidget = null }
        notifyContentChanged()
    }

    fun createTextBox(x: Float, y: Float) {
        if (restoreLastClosedTextWidget() != null) {
            notifyContentChanged()
            return
        }
        val boxWidth = width * DEFAULT_TEXTBOX_WIDTH_PERCENT; val boxHeight = height * DEFAULT_TEXTBOX_HEIGHT_PERCENT
        val adjustedX = x.coerceIn(0f, width - boxWidth); val adjustedY = y.coerceIn(0f, height - boxHeight)
        val widget = TextBoxWidget(context, adjustedX, adjustedY, boxWidth, boxHeight).apply {
            onFocusChanged = { focused -> if (focused) setFocusedWidget(this) else if (focusedWidget == this) setFocusedWidget(null) }
            onCloseRequested = { removeWidget(this) }
            onStateChanged = { notifyContentChanged() }
            onFullscreenToggled = { isFullscreen -> handleTextWidgetFullscreenToggle(this, isFullscreen) }
            onMinimizeToggled = { isMinimized -> handleTextWidgetMinimizeToggle(this, isMinimized) }
        }
        registry.addTextWidget(widget); widget.setFocused(true); setFocusedWidget(widget); notifyContentChanged()
    }

    private fun removeWidget(widget: TextBoxWidget) {
        closedWidgetTemplates = closedWidgetTemplates.copy(
            text = persistenceHelper.getTextWidgetState(widget)
        )
        if (fullscreenTextWidget == widget) fullscreenTextWidget = null
        if (focusedWidget == widget) setFocusedWidget(null)
        if (activeWidget == widget) activeWidget = null
        registry.removeTextWidget(widget); updateMinimizedWidgetPositions(); notifyContentChanged()
    }

    private fun setFocusedWidget(widget: BaseWidget?) {
        when (widget) {
            is TextBoxWidget -> { if (focusedWidget == widget) return; focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = null; focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = widget }
            is BrowserWidget -> { if (focusedBrowserWidget == widget) return; focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = null; focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = widget }
            null -> { focusedWidget?.clearSelection(); focusedWidget?.setFocused(false); focusedWidget = null; focusedBrowserWidget?.setFocused(false); focusedBrowserWidget = null }
        }
        formattingMenu?.dismiss(); onFocusChanged?.invoke(widget != null)
    }

    fun unfocusAll() { setFocusedWidget(null) }

    private fun updateMinimizedWidgetPositions() {
        if (width <= 0 || height <= 0) return
        val minimizedWidgets = registry.getMinimizedWidgets(); val squareSize = 64f; val maxPerRow = 7
        minimizedWidgets.forEachIndexed { index, widget -> val row = index / maxPerRow; val col = index % maxPerRow; widget.setPosition(col * squareSize, height - ((row + 1) * squareSize)) }
    }

    fun onKeyPress(key: String) {
        if (layoutNamePrompt?.onKeyPress(key) == true) {
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
        onClipboardRequest?.invoke()
        textEditMenu?.show(widget.x + widget.widgetWidth / 2 - 90f, widget.y - 60f, glassesClipboard ?: phoneClipboard, widget.hasSelection())
        notifyContentChanged()
    }

    private fun showBrowserAddressEditMenu(widget: BrowserWidget, clipboardContent: String?) {
        textEditMenuTarget = TextEditMenuTarget.BROWSER_ADDRESS
        if (clipboardContent != null) phoneClipboard = clipboardContent
        if (!widget.isEditingAddress()) widget.beginAddressEditing(selectAll = true)
        setFocusedWidget(widget)
        ensureDrawnAboveNativeOverlays()
        onClipboardRequest?.invoke()
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
    fun hasFocusedWidget(): Boolean = focusedWidget != null || focusedBrowserWidget != null
    fun isMenuVisible(): Boolean =
        contextMenu?.isVisible == true ||
            textEditMenu?.isVisible == true ||
            formattingMenu?.isVisible == true ||
            layoutNamePrompt?.isVisible == true
    fun isClosePopupVisible(): Boolean =
        closeConfirmationPopup?.isVisible == true ||
            layoutDeleteConfirmationPopup?.isVisible == true
    fun isAnyPopupVisible(): Boolean = isMenuVisible() || isClosePopupVisible()
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
                fullscreenFileBrowserWidget?.isPinned == true -> fullscreenFileBrowserWidget
                isStatusBarFullscreen && statusBarWidget?.isPinned == true -> statusBarWidget
                isLocationWidgetFullscreen && locationWidget?.isPinned == true -> locationWidget
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
                drawOverlayMenus(canvas)
                return
            }

            // No pinned fullscreen widget - draw all pinned widgets normally
            for (widget in registry.getPinnedWidgetsSortedByZOrder()) widget.draw(canvas)
            if (!settingsController.isHoverControlsLayoutEditorVisible()) {
                drawHoverControls(canvas)
            }
            drawVisibleKeyboardOverlays(canvas)
            drawOverlayMenus(canvas)
            return
        }

        // Handle fullscreen widgets (not affected by 3DOF transform)
        if (hasFullscreenWidget()) {
            fullscreenTextWidget?.draw(canvas)
            fullscreenBrowserWidget?.draw(canvas)
            fullscreenImageWidget?.draw(canvas)
            fullscreenFileBrowserWidget?.draw(canvas)
            if (isStatusBarFullscreen) statusBarWidget?.draw(canvas)
            if (isLocationWidgetFullscreen) locationWidget?.draw(canvas)
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

    // ==================== Keyboard overlay input priority ====================

    private fun visibleKeyboardHostAt(cx: Float, cy: Float): BaseWidget? {
        if (hasFullscreenWidget()) {
            fullscreenBrowserWidget?.let {
                if (it.isKeyboardVisible && it.getKeyboard()?.containsPoint(cx, cy) == true) return it
            }
            fullscreenTextWidget?.let {
                if (it.isKeyboardVisible && it.getKeyboard()?.containsPoint(cx, cy) == true) return it
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
            else -> false
        }
    }

    private fun clearWidgetHoverStatesExcept(except: BaseWidget?) {
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
                    onCursorCenterRequested?.invoke()
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

    fun getTextWidgetStates(): List<WidgetPersistence.TextWidgetState> = persistenceHelper.getTextWidgetStates(widgets)
    fun getStatusBarState(): WidgetPersistence.StatusBarState? = persistenceHelper.getStatusBarState(statusBarWidget)
    fun getBrowserWidgetStates(): List<WidgetPersistence.BrowserWidgetState> = persistenceHelper.getBrowserWidgetStates(browserWidgets)
    fun getImageWidgetStates(): List<WidgetPersistence.ImageWidgetState> = persistenceHelper.getImageWidgetStates(imageWidgets)
    fun getLocationWidgetState(): WidgetPersistence.LocationWidgetState? = persistenceHelper.getLocationWidgetState(locationWidget)
    fun getCalendarWidgetState(): WidgetPersistence.CalendarWidgetState? = persistenceHelper.getCalendarWidgetState(calendarWidget)
    fun getFinanceWidgetState(): WidgetPersistence.FinanceWidgetState? = persistenceHelper.getFinanceWidgetState(financeWidget)
    fun getNewsWidgetState(): WidgetPersistence.NewsWidgetState? = persistenceHelper.getNewsWidgetState(newsWidget)
    fun getNotificationsWidgetState(): WidgetPersistence.NotificationsWidgetState? = persistenceHelper.getNotificationsWidgetState(notificationsWidget)
    fun getSpeedometerWidgetState(): WidgetPersistence.SpeedometerWidgetState? = persistenceHelper.getSpeedometerWidgetState(speedometerWidget)
    fun getSubtitleWidgetState(): WidgetPersistence.SubtitleWidgetState? = persistenceHelper.getSubtitleWidgetState(subtitleWidget)
    fun getMirrorWidgetState(): WidgetPersistence.MirrorWidgetState? = persistenceHelper.getMirrorWidgetState(screenMirrorWidget)
    fun getClosedWidgetTemplates(): WidgetPersistence.ClosedWidgetTemplates = closedWidgetTemplates
    fun setClosedWidgetTemplates(templates: WidgetPersistence.ClosedWidgetTemplates) {
        closedWidgetTemplates = templates
    }

    fun capturePersistedState(): WidgetPersistence.PersistedState =
        layoutManager.capturePersistedState()

    private fun captureWidgetStateWithoutLayoutName(): WidgetPersistence.PersistedState =
        WidgetPersistence.createPersistedState(
            textWidgets = getTextWidgetStates(),
            browserWidgets = getBrowserWidgetStates(),
            imageWidgets = getImageWidgetStates(),
            statusBarState = getStatusBarState(),
            locationWidgetState = getLocationWidgetState(),
            calendarWidgetState = getCalendarWidgetState(),
            mirrorWidgetState = getMirrorWidgetState(),
            financeWidgetState = getFinanceWidgetState(),
            newsWidgetState = getNewsWidgetState(),
            notificationsWidgetState = getNotificationsWidgetState(),
            speedometerWidgetState = getSpeedometerWidgetState(),
            subtitleWidgetState = getSubtitleWidgetState(),
            closedTemplates = getClosedWidgetTemplates(),
            hoverControls = settingsController.getHoverControlPlacements(),
            isFirstRun = false,
            activeLayoutName = null,
            isLayoutLocked = isWidgetLayoutLocked
        )

    // ==================== Shortcuts ====================

    private fun applyShortcuts(actions: List<ShortcutAction>) {
        val list = ensureLemonShortcuts()
        list.clear()
        // De-dup by id and cap to MAX_LEMON_SLICES.
        val seen = mutableSetOf<String>()
        for (a in actions) {
            if (a.id in seen) continue
            seen.add(a.id)
            list.add(a)
            if (list.size >= ShortcutAction.MAX_LEMON_SLICES) break
        }
        ShortcutsStore.save(context, list.toList())
        lemonHoverControl.cancelInteraction()
        notifyContentChanged()
    }

    private fun runShortcutAction(action: ShortcutAction) {
        when (action) {
            ShortcutAction.ToggleStatus -> toggleStatusBarWidget()
            ShortcutAction.ToggleLocation -> toggleLocationWidget()
            ShortcutAction.ToggleCalendar -> toggleCalendarWidget()
            ShortcutAction.ToggleFinance -> toggleFinanceWidget()
            ShortcutAction.ToggleNews -> toggleNewsWidget()
            ShortcutAction.ToggleNotifications -> toggleNotificationsWidget()
            ShortcutAction.ToggleSpeedometer -> toggleSpeedometerWidget()
            ShortcutAction.ToggleSubtitle -> toggleSubtitleWidget()
            ShortcutAction.ToggleMirror -> toggleScreenMirrorWidget()
            ShortcutAction.CreateText -> {
                val cx = (width * 0.5f) - (width * DEFAULT_TEXTBOX_WIDTH_PERCENT) / 2f
                val cy = (height * 0.5f) - (height * DEFAULT_TEXTBOX_HEIGHT_PERCENT) / 2f
                pendingCreateX = cx
                pendingCreateY = cy
                createTextBox(cx, cy)
            }
            ShortcutAction.CreateBrowser -> {
                pendingCreateX = 0f
                pendingCreateY = 0f
                createBrowserWidget(0f, 0f)
            }
            ShortcutAction.OpenYouTubeHistory -> createYouTubeBrowserWidget()
            is ShortcutAction.Layout -> loadShortcutLayout(action.name)
        }
        notifyContentChanged()
        onCursorCenterRequested?.invoke()
    }

    private fun loadShortcutLayout(name: String) {
        if (!isReady()) return
        val saved = persistenceManager.loadLayout(name)
        if (saved != null) {
            requestLayoutSwitchBrightness()
            applyPersistedStateReplacingCurrent(saved)
            layoutManager.activeLayoutName = name
            return
        }
        val builtIn = BuiltInLayouts.build(name, width.toFloat(), height.toFloat()) ?: return
        requestLayoutSwitchBrightness()
        applyPersistedStateReplacingCurrent(builtIn)
        layoutManager.activeLayoutName = name
    }

    fun applyPersistedStateReplacingCurrent(state: WidgetPersistence.PersistedState) {
        if (!isReady()) return

        suppressContentChangedCallback = true
        try {
            dismissAllTransientOverlays()
            clearWidgetsForLayoutReplace()
            shouldCreateDefaultSingletons = false
            setClosedWidgetTemplates(state.closedTemplates)

            if (state.text.isNotEmpty()) restoreTextWidgets(state.text)
            if (state.browser.isNotEmpty()) restoreBrowserWidgets(state.browser)
            if (state.image.isNotEmpty()) restoreImageWidgets(state.image)

            state.status?.let { restoreStatusBarWidget(it) }
            state.location?.let { restoreLocationWidget(it) }
            state.calendar?.let { restoreCalendarWidget(it) }
            state.mirror?.let { restoreMirrorWidget(it) }
            state.finance?.let { restoreFinanceWidget(it) }
            state.news?.let { restoreNewsWidget(it) }
            state.notifications?.let { restoreNotificationsWidget(it) }
            state.speedometer?.let { restoreSpeedometerWidget(it) }
            state.subtitle?.let { restoreSubtitleWidget(it) }
            settingsController.applyHoverControlPlacements(state.hoverControls)
            setWidgetLayoutLocked(state.isLayoutLocked, persist = false)

            updateMinimizedWidgetPositions()
            notifyMediaBrightnessCapChanged()
            notifyBrowserMinimumBrightnessChanged()
            layoutManager.activeLayoutName = state.activeLayoutName
        } finally {
            suppressContentChangedCallback = false
        }

        notifyContentChanged()
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
        exitAllFullscreen()

        registry.allWidgets().forEach { destroyWidget(it) }
        imageWidgets.clear()
        fileBrowserWidgets.clear()
        widgets.clear()

        statusBarWidget = null
        locationWidget = null
        calendarWidget = null
        financeWidget = null
        newsWidget = null
        notificationsWidget = null
        speedometerWidget = null
        subtitleWidget = null
        screenMirrorWidget = null

        activeWidget = null
        googleAuthBrowserWidget = null
        isStatusBarFullscreen = false
        isLocationWidgetFullscreen = false
        isCalendarWidgetFullscreen = false
        isFinanceWidgetFullscreen = false
        isNewsWidgetFullscreen = false
        isNotificationsWidgetFullscreen = false
        isSpeedometerWidgetFullscreen = false
        isSubtitleWidgetFullscreen = false
        isMirrorWidgetFullscreen = false
        registry.clear()
        notifyBrowserMinimumBrightnessChanged()
    }

    private fun destroyWidget(widget: BaseWidget) {
        if (widget is ScreenMirrorWidget) {
            setBinocularMediaSourceActive(widget.binocularSourceId, false)
        }
        if (widget is FinanceWidget) {
            onFinanceVisibilityChanged?.invoke(false)
        }
        widget.onDestroy()
    }

    private fun restoreFullscreenIfNeeded(widget: BaseWidget?, shouldBeFullscreen: Boolean) {
        if (widget == null || !shouldBeFullscreen || widget.isMinimized || widget.isFullscreen) return
        widget.toggleFullscreen()
    }

    private fun restoreLastClosedTextWidget(): TextBoxWidget? {
        val template = closedWidgetTemplates.text ?: return null
        val widget = persistenceHelper.restoreTextWidgets(listOf(template), width, height).firstOrNull() ?: return null
        registry.addTextWidget(widget)
        restoreFullscreenIfNeeded(widget, template.isFullscreen)
        if (!widget.isMinimized) {
            widget.setFocused(true)
            setFocusedWidget(widget)
        }
        return widget
    }

    private fun restoreLastClosedBrowserWidget(): BrowserWidget? {
        val template = closedWidgetTemplates.browser ?: return null
        val rootView = (parent as? ViewGroup) ?: return null
        val widget = persistenceHelper
            .restoreBrowserWidgets(listOf(template), rootView, this, width, height)
            .firstOrNull()
            ?: return null

        configureBrowserWidget(widget)
        registry.addBrowserWidget(widget)
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        restoreFullscreenIfNeeded(widget, template.isFullscreen)
        if (!widget.isMinimized) {
            widget.setFocused(true)
            setFocusedWidget(widget)
        }
        return widget
    }

    fun restoreTextWidgets(states: List<WidgetPersistence.TextWidgetState>) {
        val restoredWidgets = persistenceHelper.restoreTextWidgets(states, width, height)
        for ((index, widget) in restoredWidgets.withIndex()) {
            registry.addTextWidget(widget)
            restoreFullscreenIfNeeded(widget, states.getOrNull(index)?.isFullscreen == true)
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
    fun restoreStatusBarWidget(state: WidgetPersistence.StatusBarState) {
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
                hookupSingleton(statusBarDesc)
                onCloseRequested = { toggleStatusBarWidget() }
            }
        }
        persistenceHelper.restoreStatusBarPosition(statusBarWidget, state, width, height)
        restoreFullscreenIfNeeded(statusBarWidget, state.isFullscreen)
        notifyContentChanged()
    }

    fun restoreBrowserWidgets(states: List<WidgetPersistence.BrowserWidgetState>) {
        val rootView = (parent as? ViewGroup) ?: return
        val restoredWidgets = persistenceHelper.restoreBrowserWidgets(states, rootView, this, width, height)
        for ((index, widget) in restoredWidgets.withIndex()) {
            configureBrowserWidget(widget)
            registry.addBrowserWidget(widget)
            restoreFullscreenIfNeeded(widget, states.getOrNull(index)?.isFullscreen == true)
        }
        notifyMediaBrightnessCapChanged()
        notifyBrowserMinimumBrightnessChanged()
        notifyContentChanged()
    }

    fun shouldApplyBrowserMinimumBrightness(): Boolean {
        return browserWidgets.any { !it.isMinimized }
    }

    fun shouldApplyMediaBrightnessCap(): Boolean {
        return screenMirrorWidget?.shouldApplyMediaBrightnessCap() == true
    }

    fun restoreImageWidgets(states: List<WidgetPersistence.ImageWidgetState>) {
        val restoredWidgets = persistenceHelper.restoreImageWidgets(states, width, height)
        val validStates = states.filter { File(it.imagePath).exists() }
        for ((index, widget) in restoredWidgets.withIndex()) {
            widget.onCloseRequested = { removeImageWidget(widget) }
            widget.onFullscreenToggled = { isFullscreen -> handleImageWidgetFullscreenToggle(widget, isFullscreen) }
            widget.onMinimizeToggled = { isMinimized -> handleImageWidgetMinimizeToggle(widget, isMinimized) }
            registry.addImageWidget(widget)
            restoreFullscreenIfNeeded(widget, validStates.getOrNull(index)?.isFullscreen == true)
        }
        notifyContentChanged()
    }

    /**
     * Restore or create location widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    fun restoreLocationWidget(state: WidgetPersistence.LocationWidgetState) {
        // Create location widget if it doesn't exist
        if (locationWidget == null) {
            locationWidget = LocationWidget(context, state.x, state.y, state.width, state.height).apply {
                hookupSingleton(locationDesc)
                onCloseRequested = { toggleLocationWidget() }
                if (pendingLocationAddress != null || pendingLocationWeather != null) {
                    setLocationData(pendingLocationAddress ?: "Loading...", pendingLocationWeather ?: "Checking weather...")
                }
                setSunTimes(pendingLocationSunriseEpochMs, pendingLocationSunsetEpochMs)
            }
        }
        persistenceHelper.restoreLocationWidgetPosition(locationWidget, state, width, height)
        restoreFullscreenIfNeeded(locationWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create calendar widget with the given state.
     */
    fun restoreCalendarWidget(state: WidgetPersistence.CalendarWidgetState) {
        if (calendarWidget == null) {
            calendarWidget = CalendarWidget(state.x, state.y, state.width, state.height)
        }
        calendarWidget?.apply {
            hookupSingleton(calendarDesc)
            onCloseRequested = { toggleCalendarWidget() }
            onStateChanged = { notifyContentChanged() }
            setFontScale(state.fontScale)
            setSnapshot(pendingCalendarSnapshot)
        }
        persistenceHelper.restoreCalendarWidgetPosition(calendarWidget, state, width, height)
        restoreFullscreenIfNeeded(calendarWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create finance widget with the given state.
     */
    fun restoreFinanceWidget(state: WidgetPersistence.FinanceWidgetState) {
        if (financeWidget == null) {
            financeWidget = FinanceWidget(state.x, state.y, state.width, state.height).apply {
                hookupSingleton(financeDesc)
                onCloseRequested = { toggleFinanceWidget() }
                onStateChanged = { notifyContentChanged() }
                onDataRequested = { visibleConfigs, refreshTileIds, force, reason ->
                    onFinanceDataRequested?.invoke(visibleConfigs, refreshTileIds, force, reason)
                }
                onMinimizeToggled = { isMinimized ->
                    handleSingletonMinimizeToggle(financeDesc, isMinimized)
                    onFinanceVisibilityChanged?.invoke(!isMinimized)
                    if (!isMinimized) requestData(force = false, reason = "finance_unminimized")
                }
                pendingFinanceCountryCode?.let { setCountryCode(it) }
            }
        }
        persistenceHelper.restoreFinanceWidgetPosition(financeWidget, state, width, height)
        restoreFullscreenIfNeeded(financeWidget, state.isFullscreen)
        if (financeWidget?.isMinimized == true) {
            onFinanceVisibilityChanged?.invoke(false)
        } else {
            financeWidget?.requestData(force = true, reason = "widget_restored")
            onFinanceVisibilityChanged?.invoke(true)
        }
        notifyContentChanged()
    }

    /**
     * Restore or create news widget with the given state.
     */
    fun restoreNewsWidget(state: WidgetPersistence.NewsWidgetState) {
        if (newsWidget == null) {
            newsWidget = NewsWidget(state.x, state.y, state.width, state.height).apply {
                hookupSingleton(newsDesc)
                onCloseRequested = { toggleNewsWidget() }
                onStateChanged = { notifyContentChanged() }
                onDataRequested = { countryCode, force, reason ->
                    onNewsDataRequested?.invoke(countryCode, force, reason)
                }
                pendingNewsCountryCode?.let { setCountryCode(it) }
            }
        }
        persistenceHelper.restoreNewsWidgetPosition(newsWidget, state, width, height)
        restoreFullscreenIfNeeded(newsWidget, state.isFullscreen)
        newsWidget?.requestData(force = true, reason = "widget_restored")
        notifyContentChanged()
    }

    fun restoreNotificationsWidget(state: WidgetPersistence.NotificationsWidgetState) {
        if (notificationsWidget == null) {
            notificationsWidget = NotificationsWidget(context, state.x, state.y, state.width, state.height, height.toFloat()).apply {
                hookupSingleton(notificationsDesc)
                onCloseRequested = { toggleNotificationsWidget() }
                onStateChanged = { notifyContentChanged() }
            }
        }
        persistenceHelper.restoreNotificationsWidgetPosition(notificationsWidget, state, width, height)
        notificationsWidget?.setScreenHeight(height.toFloat())
        notificationsWidget?.setNotificationsSnapshot(latestNotificationsSnapshot)
        restoreFullscreenIfNeeded(notificationsWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create speedometer widget with the given state.
     */
    fun restoreSpeedometerWidget(state: WidgetPersistence.SpeedometerWidgetState) {
        if (speedometerWidget == null) {
            speedometerWidget = createSpeedometerWidget(state.x, state.y, state.width, state.height)
        }
        persistenceHelper.restoreSpeedometerWidgetPosition(speedometerWidget, state, width, height)
        restoreFullscreenIfNeeded(speedometerWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create subtitle widget with capture off.
     */
    fun restoreSubtitleWidget(state: WidgetPersistence.SubtitleWidgetState) {
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
        restoreFullscreenIfNeeded(subtitleWidget, state.isFullscreen)
        notifyContentChanged()
    }

    /**
     * Restore or create mirror widget with the given state.
     * Creates the widget if it doesn't exist, then restores its state.
     */
    fun restoreMirrorWidget(state: WidgetPersistence.MirrorWidgetState) {
        // Create mirror widget if it doesn't exist
        if (screenMirrorWidget == null) {
            screenMirrorWidget = ScreenMirrorWidget(context, state.x, state.y, state.width, state.height).apply {
                configureScreenMirrorWidget(this)
            }
        }
        persistenceHelper.restoreMirrorWidgetPosition(screenMirrorWidget, state, width, height)
        restoreFullscreenIfNeeded(screenMirrorWidget, state.isFullscreen)
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
        registry.allWidgets().forEach { destroyWidget(it) }
    }
}
