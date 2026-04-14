package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.binocular.BinocularContentClass
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
        private const val CLOSE_BUTTON_SIZE = 40f
        private const val CLOSE_BUTTON_MARGIN = 16f
        
        // Phone trackpad sensitivity multiplier
        private const val PHONE_TRACKPAD_SENSITIVITY = 1.5f
    }

    // ==================== Delegated Components ====================

    private val cursorStabilizer = CursorStabilizer()
    private val registry = WidgetRegistry()
    private val persistenceHelper = WidgetPersistenceHelper(context)
    private val threeDofOverlay = ThreeDofOverlay()
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
    private var formattingMenu: FormattingMenu? = null
    private var closeConfirmationPopup: CloseConfirmationPopup? = null

    // Close button
    private val closeButtonRect = RectF()
    private var closeButtonHovered = false

    // YouTube button (appears on hover, opens YouTube history in browser widget)
    private val youtubeButtonRect = RectF()
    private var youtubeButtonHovered = false
    private val YOUTUBE_BUTTON_SIZE = 64f  // Same size as minimized widgets

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

    // Speedometer widget - delegated to registry
    var speedometerWidget: SpeedometerWidget?
        get() = registry.speedometerWidget
        private set(value) { registry.speedometerWidget = value }

    // Pending speed data for speedometer widget
    private var pendingSpeedKmh = 0f
    private var pendingSpeedQualityOk = false
    private var pendingSpeedMoving = false
    private var speedVisibilityThresholdKmh = DEFAULT_SPEED_VISIBILITY_THRESHOLD_KMH
    private var speedUnit: SpeedometerWidget.SpeedUnit = SpeedometerWidget.SpeedUnit.KMH

    // Screen mirror widget - delegated to registry
    var screenMirrorWidget: ScreenMirrorWidget?
        get() = registry.screenMirrorWidget
        private set(value) { registry.screenMirrorWidget = value }

    // Dashboard settings - standalone menu
    private var dashboardSettings: DashboardSettings? = null
    private var speedometerSettingsMenu: SpeedometerSettingsMenu? = null

    // Brightness value (0.0 to 1.0), default 1.0 (no dimming)
    // 1.0 = full brightness (system default, no dimming)
    // 0.0 = minimum brightness (maximum dimming)
    private var brightnessValue = 1.0f
    private var adaptiveBrightnessEnabled = true

    // Callback to apply window-level brightness (set by MainActivity)
    var onBrightnessChanged: ((Float) -> Unit)? = null
    var onAdaptiveBrightnessChanged: ((Boolean) -> Unit)? = null
    var onMediaBrightnessCapChanged: ((Boolean) -> Unit)? = null

    // Callbacks for head-up wake settings (set by MainActivity)
    var onHeadUpTimeChanged: ((Long) -> Unit)? = null
    var onWakeDurationChanged: ((Long) -> Unit)? = null
    var onAngleThresholdChanged: ((Float) -> Unit)? = null
    var onHeadsUpEnabledChanged: ((Boolean) -> Unit)? = null
    var onConnectGoogle: (() -> Unit)? = null
    var onGrantCalendarAccess: (() -> Unit)? = null
    var onDisconnectGoogle: (() -> Unit)? = null
    var onRetryGoogleAuth: (() -> Unit)? = null
    var onGoogleAuthBrowserClosed: (() -> Unit)? = null

    // Current head-up settings (stored for settings restoration)
    private var headUpTimeMs = HeadUpWakeManager.DEFAULT_HEAD_UP_HOLD_TIME_MS
    private var wakeDurationMs = HeadUpWakeManager.DEFAULT_WAKE_DURATION_MS
    private var angleThresholdDegrees = HeadUpWakeManager.DEFAULT_PITCH_CHANGE_THRESHOLD
    private var headsUpEnabled = true
    private var googleAuthState = GoogleAuthState.signedOut()

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

    // Drag state flags
    private var mirrorWidgetDragging = false
    private var mirrorWidgetResizing = false
    private var statusBarDragging = false
    private var locationWidgetDragging = false
    private var financeWidgetDragging = false
    private var newsWidgetDragging = false
    private var speedometerWidgetDragging = false
    private var calendarWidgetDragging = false
    private var cursorPressed = false

    // ==================== Cursor State ====================
    
    private var cursorX = 0f
    private var cursorY = 0f
    private var lastCursorX = 0f
    private var lastCursorY = 0f

    // ==================== Long-press Detection ====================
    
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressStartX = 0f
    private var longPressStartY = 0f
    private var pendingDragWidget: BaseWidget? = null
    private var pendingDragStatusBar = false
    private var pendingDragLocationWidget = false
    private var pendingDragCalendarWidget = false
    private var pendingDragFinanceWidget = false
    private var pendingDragNewsWidget = false
    private var pendingDragSpeedometerWidget = false
    private var pendingDragMirrorWidget = false
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
    private var isSpeedometerWidgetFullscreen = false
    private var isMirrorWidgetFullscreen = false

    // ==================== Singleton Widget Descriptors ====================
    // Each descriptor binds a singleton widget's fullscreen flag and widget reference to the
    // shared lifecycle helpers (teardownSingleton / hookupSingleton / handleSingletonXxx).

    private inner class SingletonDesc(
        val isFullscreen: () -> Boolean,
        val setFullscreen: (Boolean) -> Unit,
        val widget: () -> BaseWidget?
    )

    private val statusBarDesc   = SingletonDesc({ isStatusBarFullscreen },        { isStatusBarFullscreen = it },        { statusBarWidget })
    private val locationDesc    = SingletonDesc({ isLocationWidgetFullscreen },    { isLocationWidgetFullscreen = it },    { locationWidget })
    private val calendarDesc    = SingletonDesc({ isCalendarWidgetFullscreen },    { isCalendarWidgetFullscreen = it },    { calendarWidget })
    private val financeDesc     = SingletonDesc({ isFinanceWidgetFullscreen },     { isFinanceWidgetFullscreen = it },     { financeWidget })
    private val newsDesc        = SingletonDesc({ isNewsWidgetFullscreen },        { isNewsWidgetFullscreen = it },        { newsWidget })
    private val speedometerDesc = SingletonDesc({ isSpeedometerWidgetFullscreen }, { isSpeedometerWidgetFullscreen = it }, { speedometerWidget })
    private val mirrorDesc      = SingletonDesc({ isMirrorWidgetFullscreen },      { isMirrorWidgetFullscreen = it },      { screenMirrorWidget })

    private val singletonDescs: List<SingletonDesc>
        get() = listOf(statusBarDesc, locationDesc, calendarDesc, financeDesc, newsDesc, speedometerDesc, mirrorDesc)

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
    var onSpeedUnitChanged: ((SpeedometerWidget.SpeedUnit) -> Unit)? = null
    var onSpeedVisibilityThresholdChanged: ((Float) -> Unit)? = null

    private var isCursorVisible = false
    private var displayState: WakeSleepManager.DisplayState = WakeSleepManager.DisplayState.WAKE

    // Flag to suppress aggressive invalidation during display state transitions
    private var isTransitioningDisplayState = false

    // Flag to control whether default singleton widgets should be created.
    // Set to false after restoring state on a returning user to prevent recreating closed widgets.
    private var shouldCreateDefaultSingletons = true

    // ==================== Paints ====================

    private val closeButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
    }

    private val closeButtonCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // YouTube button paints
    private val youtubeButtonBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")  // Semi-transparent black background
    }

    private val youtubeButtonIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0000")  // YouTube red
        style = Paint.Style.FILL
    }

    private val youtubePlayIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    init {
        setWillNotDraw(false)
        setupPersistenceHelperCallbacks()
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
            isSpeedometerWidgetFullscreen ||
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

            screenMirrorWidget?.onDisplayVisibilityChanged(
                screenMirrorWidget?.let { isMirrorWidgetVisible(it) } ?: false
            )

            // Keep finance/news timers alive in SLEEP so they stay fresh without phone interaction.
            val keepRefreshing = state != WakeSleepManager.DisplayState.OFF
            financeWidget?.setRefreshActive(keepRefreshing)
            newsWidget?.setRefreshActive(keepRefreshing)

            if (state == WakeSleepManager.DisplayState.WAKE) {
                Log.d(TAG, "Display wake -> requesting finance refresh (news uses periodic timer)")
                financeWidget?.refreshOnWake()
            }

            // Clear transition flag and do a single, controlled invalidation
            isTransitioningDisplayState = false

            // Use a single invalidate() instead of the aggressive notifyContentChanged()
            // to prevent power spike from rapid redraw cascade
            invalidate()
        }
    }

    fun onAppResumed() {
        Log.d(TAG, "App resume -> requesting finance refresh (news uses periodic timer)")
        browserWidgets.forEach { it.onHostResume() }
        screenMirrorWidget?.onHostResume()
        financeWidget?.setRefreshActive(true)
        financeWidget?.refreshOnWake()
        newsWidget?.setRefreshActive(true)
    }

    fun onAppPaused() {
        Log.d(TAG, "App paused -> suspending finance/news widget auto-refresh")
        browserWidgets.forEach { it.onHostPause() }
        screenMirrorWidget?.onHostPause()
        financeWidget?.setRefreshActive(false)
        newsWidget?.setRefreshActive(false)
    }

    fun onPhoneReconnected() {
        Log.d(TAG, "Phone reconnected -> forcing finance refresh (news uses periodic timer)")
        financeWidget?.setRefreshActive(displayState != WakeSleepManager.DisplayState.OFF)
        newsWidget?.setRefreshActive(displayState != WakeSleepManager.DisplayState.OFF)
        financeWidget?.refreshOnWake()
    }

    fun onHeadUpWake() {
        Log.d(TAG, "Head-up wake -> advance news item and refresh finance")
        financeWidget?.setRefreshActive(true)
        newsWidget?.setRefreshActive(true)
        newsWidget?.showNextItemOnHeadUpWake()
        financeWidget?.refreshOnWake()
    }

    fun hasPinnedWidgets(): Boolean = registry.hasPinnedWidgets()
    fun isDisplayAwake(): Boolean = displayState == WakeSleepManager.DisplayState.WAKE

    private fun notifyContentChanged() {
        invalidate()
        postInvalidate()
        (parent as? View)?.invalidate()
        onContentChanged?.invoke()
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
        notifyMediaBrightnessCapChanged()
    }

    private fun configureScreenMirrorWidget(widget: ScreenMirrorWidget) {
        widget.onStateChanged = { state ->
            when (state) {
                ScreenMirrorWidget.State.MOVING -> mirrorWidgetDragging = true
                ScreenMirrorWidget.State.IDLE -> {
                    mirrorWidgetDragging = false
                    mirrorWidgetResizing = false
                }
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

        closeButtonRect.set(
            w - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN,
            CLOSE_BUTTON_MARGIN,
            w - CLOSE_BUTTON_MARGIN,
            CLOSE_BUTTON_MARGIN + CLOSE_BUTTON_SIZE
        )

        // YouTube button - positioned at bottom-right corner, same hitbox size as minimized widgets
        youtubeButtonRect.set(
            w - YOUTUBE_BUTTON_SIZE,
            h - YOUTUBE_BUTTON_SIZE,
            w.toFloat(),
            h.toFloat()
        )

        contextMenu = ContextMenu(w.toFloat(), h.toFloat()).apply {
            onItemSelected = { item -> handleMenuItemSelected(item) }
            onSubmenuItemSelected = { _, subItem -> handleSubmenuItemSelected(subItem) }
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

        speedometerSettingsMenu = SpeedometerSettingsMenu(w.toFloat(), h.toFloat()).apply {
            setThreshold(speedVisibilityThresholdKmh, notify = false)
            onThresholdChanged = { threshold ->
                if (speedVisibilityThresholdKmh != threshold) {
                    speedVisibilityThresholdKmh = threshold
                    speedometerWidget?.setForceVisibleWhenIdle(speedVisibilityThresholdKmh <= 0f)
                    onSpeedVisibilityThresholdChanged?.invoke(threshold)
                }
                invalidate()
            }
            onDismissed = { notifyContentChanged() }
        }

        // Only create default singleton widgets on first run.
        // For returning users, the restoration code will create widgets based on saved state.
        if (shouldCreateDefaultSingletons) {
            if (statusBarWidget == null) {
                val statusWidth = 220f
                val statusHeight = 110f
                statusBarWidget = StatusBarWidget(
                    context, (w - statusWidth) / 2f, h - statusHeight - h * 0.15f, statusWidth, statusHeight
                ).apply {
                    onStateChanged = { state ->
                        if (state == StatusBarWidget.State.MOVING) statusBarDragging = true
                        else if (statusBarDragging && state == StatusBarWidget.State.IDLE) statusBarDragging = false
                        notifyContentChanged()
                    }
                    hookupSingleton(statusBarDesc)
                    onCloseRequested = { toggleStatusBarWidget() }
                }
            }

            if (locationWidget == null) {
                val locWidth = 300f
                val locHeight = 120f
                locationWidget = LocationWidget(context, 20f, h - locHeight - 20f, locWidth, locHeight).apply {
                    hookupSingleton(locationDesc)
                    onCloseRequested = { toggleLocationWidget() }
                    if (pendingLocationAddress != null || pendingLocationWeather != null) {
                        setLocationData(pendingLocationAddress ?: "Loading...", pendingLocationWeather ?: "Checking weather...")
                    }
                    setSunTimes(pendingLocationSunriseEpochMs, pendingLocationSunsetEpochMs)
                }
            }

            if (screenMirrorWidget == null) {
                val mirrorWidth = w * 0.35f
                val mirrorHeight = h * 0.40f
                screenMirrorWidget = ScreenMirrorWidget(context, 20f, 20f, mirrorWidth, mirrorHeight).apply {
                    configureScreenMirrorWidget(this)
                }
            }
        }

        updateFullscreenBounds()
        Log.d(TAG, "Container sized: ${w}x${h}")
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
        val clamped = thresholdKmh.coerceIn(0f, 1f)
        val forceVisibleChanged = speedometerWidget?.setForceVisibleWhenIdle(clamped <= 0f) == true
        if (speedVisibilityThresholdKmh == clamped) {
            speedometerSettingsMenu?.setThreshold(clamped, notify = false)
            if (forceVisibleChanged && displayState != WakeSleepManager.DisplayState.OFF) {
                notifyContentChanged()
            }
            return
        }
        speedVisibilityThresholdKmh = clamped
        speedometerSettingsMenu?.setThreshold(clamped, notify = false)
        onSpeedVisibilityThresholdChanged?.invoke(clamped)
        if (forceVisibleChanged && displayState != WakeSleepManager.DisplayState.OFF) {
            notifyContentChanged()
        }
    }

    fun getSpeedVisibilityThreshold(): Float = speedVisibilityThresholdKmh

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
            setForceVisibleWhenIdle(speedVisibilityThresholdKmh <= 0f)
            setSpeedData(pendingSpeedKmh, pendingSpeedQualityOk, pendingSpeedMoving)
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

    // ==================== Z-Order Management ====================

    private fun bringWidgetToFront(widget: BaseWidget) {
        registry.bringToFront(widget, parent as? ViewGroup, this)
        notifyContentChanged()
    }

    // ==================== Input Handling ====================

    override fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        pendingDragWidget = null; pendingDragStatusBar = false; pendingDragLocationWidget = false
        pendingDragCalendarWidget = false; pendingDragFinanceWidget = false; pendingDragNewsWidget = false
        pendingDragSpeedometerWidget = false; pendingDragMirrorWidget = false; pendingDragIsResize = false; pendingTextSelection = false
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

        // Transform to content space for hit testing against widgets
        val (cx, cy) = toContentSpace(x, y)

        val scrollbarWidget = getScrollbarHoveredWidget(cx, cy)
        if (scrollbarWidget != null) { isScrollbarDragActive = true; this.scrollbarDragWidget = scrollbarWidget }
        else { isScrollbarDragActive = false; this.scrollbarDragWidget = null }

        // Menus are NOT transformed (they stay head-locked like cursor)
        formattingMenu?.let { menu -> if (menu.isVisible && menu.shouldScheduleLongPress(x, y)) { pendingFormattingMenuDrag = true; scheduleLongPress(); return } }

        speedometerSettingsMenu?.let { menu ->
            if (menu.isVisible) {
                menu.onDown(x, y)
                notifyContentChanged()
                return
            }
        }
        
        // Widgets use content-space coordinates
        focusedWidget?.let { widget -> if (widget.isFocused() && widget.containsPoint(cx, cy)) { bringWidgetToFront(widget); if (widget.hitTest(cx, cy) == TextBoxWidget.HitArea.CONTENT) { pendingTextSelection = true; scheduleLongPress(); return } } }
        fullscreenTextWidget?.let { widget -> if (widget.isFocused() && widget.containsPoint(cx, cy)) { if (widget.hitTest(cx, cy) == TextBoxWidget.HitArea.CONTENT) { pendingTextSelection = true; scheduleLongPress(); return } } }
        
        if (hasFullscreenWidget()) return

        // Modal dashboard check - prioritize input if visible
        dashboardSettings?.let { settings ->
            if (settings.isVisible) {
                settings.onDown(x, y)   // IMPORTANT: screen coords, not (cx, cy)
                notifyContentChanged()
                return
            }
        }
        screenMirrorWidget?.let { mirror -> if (mirror.containsPoint(cx, cy)) { val hitArea = mirror.hitTest(cx, cy); if (hitArea == ScreenMirrorWidget.HitArea.BORDER || hitArea == ScreenMirrorWidget.HitArea.CONTENT) { bringWidgetToFront(mirror); pendingDragMirrorWidget = true; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == ScreenMirrorWidget.HitArea.RESIZE_HANDLE) { bringWidgetToFront(mirror); pendingDragMirrorWidget = true; pendingDragIsResize = true; scheduleLongPress(); return } } }
        statusBarWidget?.let { statusBar -> val hitArea = statusBar.hitTest(cx, cy); if (hitArea == StatusBarWidget.HitArea.BORDER || hitArea == StatusBarWidget.HitArea.CONTENT) { bringWidgetToFront(statusBar); pendingDragStatusBar = true; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == StatusBarWidget.HitArea.RESIZE_HANDLE) { bringWidgetToFront(statusBar); pendingDragStatusBar = true; pendingDragIsResize = true; scheduleLongPress(); return } }
        locationWidget?.let { loc -> val hitArea = loc.baseHitTest(cx, cy); if (hitArea == BaseWidget.BaseHitArea.BORDER || hitArea == BaseWidget.BaseHitArea.CONTENT) { bringWidgetToFront(loc); pendingDragLocationWidget = true; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BaseWidget.BaseHitArea.RESIZE_HANDLE) { bringWidgetToFront(loc); pendingDragLocationWidget = true; pendingDragIsResize = true; scheduleLongPress(); return } }
        calendarWidget?.let { cal -> val hitArea = cal.baseHitTest(cx, cy); if (hitArea == BaseWidget.BaseHitArea.BORDER || hitArea == BaseWidget.BaseHitArea.CONTENT) { bringWidgetToFront(cal); pendingDragCalendarWidget = true; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BaseWidget.BaseHitArea.RESIZE_HANDLE) { bringWidgetToFront(cal); pendingDragCalendarWidget = true; pendingDragIsResize = true; scheduleLongPress(); return } }
        financeWidget?.let { fw -> val hitArea = fw.baseHitTest(cx, cy); if (hitArea == BaseWidget.BaseHitArea.BORDER || hitArea == BaseWidget.BaseHitArea.CONTENT) { bringWidgetToFront(fw); pendingDragFinanceWidget = true; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BaseWidget.BaseHitArea.RESIZE_HANDLE) { bringWidgetToFront(fw); pendingDragFinanceWidget = true; pendingDragIsResize = true; scheduleLongPress(); return } }
        newsWidget?.let { nw -> val hitArea = nw.baseHitTest(cx, cy); if (hitArea == BaseWidget.BaseHitArea.BORDER || hitArea == BaseWidget.BaseHitArea.CONTENT) { bringWidgetToFront(nw); pendingDragNewsWidget = true; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BaseWidget.BaseHitArea.RESIZE_HANDLE) { bringWidgetToFront(nw); pendingDragNewsWidget = true; pendingDragIsResize = true; scheduleLongPress(); return } }
        speedometerWidget?.let { sw -> val hitArea = sw.baseHitTest(cx, cy); if (hitArea == BaseWidget.BaseHitArea.BORDER || hitArea == BaseWidget.BaseHitArea.CONTENT) { bringWidgetToFront(sw); pendingDragSpeedometerWidget = true; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BaseWidget.BaseHitArea.RESIZE_HANDLE) { bringWidgetToFront(sw); pendingDragSpeedometerWidget = true; pendingDragIsResize = true; scheduleLongPress(); return } }

        for (widget in browserWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { val hitArea = widget.hitTest(cx, cy); bringWidgetToFront(widget); if (hitArea == BrowserWidget.HitArea.CONTENT) { widget.setFocused(true); setFocusedWidget(widget); return } else if (hitArea == BrowserWidget.HitArea.BORDER) { pendingDragWidget = widget; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BrowserWidget.HitArea.RESIZE_HANDLE) { pendingDragWidget = widget; pendingDragIsResize = true; scheduleLongPress(); return } } }
        for (widget in imageWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { val hitArea = widget.baseHitTest(cx, cy); bringWidgetToFront(widget); if (hitArea == BaseWidget.BaseHitArea.BORDER || hitArea == BaseWidget.BaseHitArea.CONTENT) { pendingDragWidget = widget; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == BaseWidget.BaseHitArea.RESIZE_HANDLE) { pendingDragWidget = widget; pendingDragIsResize = true; scheduleLongPress(); return } } }
        for (widget in fileBrowserWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { val hitArea = widget.hitTest(cx, cy); bringWidgetToFront(widget); if (hitArea == FileBrowserWidget.HitArea.BORDER) { pendingDragWidget = widget; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == FileBrowserWidget.HitArea.RESIZE_HANDLE) { pendingDragWidget = widget; pendingDragIsResize = true; scheduleLongPress(); return } else if (hitArea == FileBrowserWidget.HitArea.CONTENT || hitArea == FileBrowserWidget.HitArea.FILE_ITEM) { return } } }
        for (widget in widgets.reversed()) { if (widget.containsPoint(cx, cy)) { val hitArea = widget.hitTest(cx, cy); bringWidgetToFront(widget); if (hitArea == TextBoxWidget.HitArea.BORDER) { pendingDragWidget = widget; pendingDragIsResize = false; scheduleLongPress(); return } else if (hitArea == TextBoxWidget.HitArea.RESIZE_HANDLE) { pendingDragWidget = widget; pendingDragIsResize = true; scheduleLongPress(); return } else if (hitArea == TextBoxWidget.HitArea.CONTENT) { return } } }
    }

    private fun scheduleLongPress() {
        longPressRunnable = Runnable {
            if (!cursorPressed) return@Runnable
            if (pendingFormattingMenuDrag) { formattingMenu?.let { if (it.onLongPressTriggered()) { } }; pendingFormattingMenuDrag = false; notifyContentChanged(); return@Runnable }
            if (pendingTextSelection) { val widget = focusedWidget ?: fullscreenTextWidget; widget?.let { if (it.isFocused()) { val (cx, cy) = screenToContentCoordinates(longPressStartX, longPressStartY); it.setCursorFromScreenPosition(cx, cy); it.resetPixelAccumulator(); it.startSelection(); isLongPressSelectionMode = true; isTextSelectionActive = false } }; pendingTextSelection = false; notifyContentChanged(); return@Runnable }
            if (pendingDragMirrorWidget) { screenMirrorWidget?.let { it.startDrag(pendingDragIsResize); if (pendingDragIsResize) mirrorWidgetResizing = true else mirrorWidgetDragging = true }; pendingDragMirrorWidget = false; pendingDragIsResize = false; notifyContentChanged(); return@Runnable }
            if (pendingDragStatusBar) { statusBarWidget?.let { it.startDrag(pendingDragIsResize); statusBarDragging = true }; pendingDragStatusBar = false; pendingDragIsResize = false; notifyContentChanged(); return@Runnable }
            if (pendingDragLocationWidget) { locationWidget?.let { it.startDrag(pendingDragIsResize); locationWidgetDragging = true }; pendingDragLocationWidget = false; pendingDragIsResize = false; notifyContentChanged(); return@Runnable }
            if (pendingDragCalendarWidget) { calendarWidget?.let { it.startDrag(pendingDragIsResize); calendarWidgetDragging = true }; pendingDragCalendarWidget = false; pendingDragIsResize = false; notifyContentChanged(); return@Runnable }
            if (pendingDragFinanceWidget) { financeWidget?.let { it.startDrag(pendingDragIsResize); financeWidgetDragging = true }; pendingDragFinanceWidget = false; pendingDragIsResize = false; notifyContentChanged(); return@Runnable }
            if (pendingDragNewsWidget) { newsWidget?.let { it.startDrag(pendingDragIsResize); newsWidgetDragging = true }; pendingDragNewsWidget = false; pendingDragIsResize = false; notifyContentChanged(); return@Runnable }
            if (pendingDragSpeedometerWidget) { speedometerWidget?.let { it.startDrag(pendingDragIsResize); speedometerWidgetDragging = true }; pendingDragSpeedometerWidget = false; pendingDragIsResize = false; notifyContentChanged(); return@Runnable }
            pendingDragWidget?.let { widget -> widget.startDrag(pendingDragIsResize); activeWidget = widget; notifyContentChanged() }
            pendingDragWidget = null; pendingDragIsResize = false; longPressRunnable = null
        }
        handler.postDelayed(longPressRunnable!!, LONG_PRESS_DELAY_MS)
    }

    fun onCursorUp(x: Float, y: Float) {
        speedometerSettingsMenu?.let { menu ->
            if (menu.isVisible) {
                menu.onUp()
                notifyContentChanged()
            }
        }

        // If DashboardSettings is open, end its interaction (e.g., stop slider drag).
        dashboardSettings?.let { settings ->
            if (settings.isVisible) {
                settings.onUp()
                notifyContentChanged()
                // Do not return; we still want to reset scroll mode flags below.
            }
        }

        cursorPressed = false; cancelLongPress()
        if (isScrollbarDragActive) { isScrollbarDragActive = false; scrollbarDragWidget = null }
        formattingMenu?.let { if (it.isDragging()) it.onDragEnd() }
        if (isLongPressSelectionMode) { (focusedWidget ?: fullscreenTextWidget)?.endSelection(); isLongPressSelectionMode = false; isTextSelectionActive = false }
        if (mirrorWidgetDragging || mirrorWidgetResizing) { screenMirrorWidget?.onDragEnd(); mirrorWidgetDragging = false; mirrorWidgetResizing = false; notifyContentChanged() }
        if (statusBarDragging) { statusBarWidget?.onDragEnd(); statusBarDragging = false; notifyContentChanged() }
        if (locationWidgetDragging) { locationWidget?.onDragEnd(); locationWidgetDragging = false; notifyContentChanged() }
        if (calendarWidgetDragging) { calendarWidget?.onDragEnd(); calendarWidgetDragging = false; notifyContentChanged() }
        if (financeWidgetDragging) { financeWidget?.onDragEnd(); financeWidgetDragging = false; notifyContentChanged() }
        if (newsWidgetDragging) { newsWidget?.onDragEnd(); newsWidgetDragging = false; notifyContentChanged() }
        if (speedometerWidgetDragging) { speedometerWidget?.onDragEnd(); speedometerWidgetDragging = false; notifyContentChanged() }
        if (activeWidget != null) { activeWidget?.onDragEnd(); activeWidget = null; notifyContentChanged() }
        isTwoFingerScrollMode = false; currentPointerCount = 1
    }
    
    fun onPointerCountChanged(pointerCount: Int) {
        val wasScrollMode = isTwoFingerScrollMode; currentPointerCount = pointerCount; isTwoFingerScrollMode = pointerCount >= 2
        if (isTwoFingerScrollMode && !wasScrollMode) cancelLongPress()
    }

    fun setCursorPosition(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, width.toFloat()); cursorY = y.coerceIn(0f, height.toFloat())
        lastCursorX = cursorX; lastCursorY = cursorY
        updateHover(cursorX, cursorY, isCursorVisible)
    }

    fun onMove(dx: Float, dy: Float, source: InputSource = InputSource.GLASSES_TEMPLE): Pair<Float, Float> {
        if (source == InputSource.PHONE_TRACKPAD) {
            cursorX = (cursorX + (dx * PHONE_TRACKPAD_SENSITIVITY)).coerceIn(0f, width.toFloat())
            cursorY = (cursorY + (dy * PHONE_TRACKPAD_SENSITIVITY)).coerceIn(0f, height.toFloat())
        } else {
            val stabilized = cursorStabilizer.process(dx, dy)
            cursorX = (cursorX + stabilized.x).coerceIn(0f, width.toFloat())
            cursorY = (cursorY + stabilized.y).coerceIn(0f, height.toFloat())
        }
        updateHover(cursorX, cursorY, isCursorVisible)

        // If SpeedometerSettings is open, treat it as modal and route movement to it.
        speedometerSettingsMenu?.let { menu ->
            if (menu.isVisible) {
                menu.onMove(cursorX, cursorY)
                notifyContentChanged()
                lastCursorX = cursorX; lastCursorY = cursorY
                return Pair(cursorX, cursorY)
            }
        }

        // If DashboardSettings is open, treat it as modal and route movement to it.
        // This allows slider dragging and prevents dragging widgets behind it.
        dashboardSettings?.let { settings ->
            if (settings.isVisible) {
                settings.onMove(cursorX, cursorY)
                notifyContentChanged()
                lastCursorX = cursorX; lastCursorY = cursorY
                return Pair(cursorX, cursorY)
            }
        }

        val moveDx = cursorX - lastCursorX; val moveDy = cursorY - lastCursorY
        lastCursorX = cursorX; lastCursorY = cursorY
        handleDragOperations(cursorX, cursorY, moveDx, moveDy)
        return Pair(cursorX, cursorY)
    }

    fun updateCursor(x: Float, y: Float, isVisible: Boolean = true): Pair<Float, Float> {
        isCursorVisible = isVisible; cursorX = x; cursorY = y
        updateHover(x, y, isVisible)
        return Pair(x, y)
    }

    private fun updateHover(x: Float, y: Float, isVisible: Boolean) {
        if (!isVisible) {
            closeButtonHovered = false
            youtubeButtonHovered = false
            closeConfirmationPopup?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); formattingMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); textEditMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); contextMenu?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
            if (hasFullscreenWidget()) { fullscreenTextWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); fullscreenBrowserWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); fullscreenImageWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isStatusBarFullscreen) statusBarWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isLocationWidgetFullscreen) locationWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isCalendarWidgetFullscreen) calendarWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isFinanceWidgetFullscreen) financeWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isNewsWidgetFullscreen) newsWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isSpeedometerWidgetFullscreen) speedometerWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY); if (isMirrorWidgetFullscreen) screenMirrorWidget?.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
            else { registry.forEachWidget { it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) } }
            cursorStabilizer.reset()
            // Hover clear is transient visual state only.
            if (!isTransitioningDisplayState) { invalidateHoverVisuals() }
            return
        }
        
        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)
        
        // Close button uses content-space coords (it's drawn with 3DOF transform)
        closeButtonHovered = !hasFullscreenWidget() && closeButtonRect.contains(cx, cy)

        // YouTube button uses content-space coords (it's drawn with 3DOF transform)
        youtubeButtonHovered = !hasFullscreenWidget() && youtubeButtonRect.contains(cx, cy)

        // Menus stay head-locked, use screen coords
        closeConfirmationPopup?.updateHover(x, y); formattingMenu?.updateHover(x, y); textEditMenu?.updateHover(x, y); contextMenu?.updateHover(x, y)
        
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
            if (isSpeedometerWidgetFullscreen) speedometerWidget?.updateHover(if (speedometerWidget!!.containsPoint(cx, cy)) cx else Float.NEGATIVE_INFINITY, cy)
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
            speedometerWidget?.let { if (it.containsPoint(cx, cy)) it.updateHover(cx, cy) else it.updateHover(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY) }
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
        if (mirrorWidgetDragging || mirrorWidgetResizing) { screenMirrorWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
        if (statusBarDragging) { statusBarWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
        if (locationWidgetDragging) { locationWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
        if (calendarWidgetDragging) { calendarWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
        if (financeWidgetDragging) { financeWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
        if (newsWidgetDragging) { newsWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
        if (speedometerWidgetDragging) { speedometerWidget?.onDrag(dx, dy, width.toFloat(), height.toFloat()); notifyContentChanged(); return }
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

    fun isCursorOverScrollbar(x: Float, y: Float): Boolean = getScrollbarHoveredWidget(x, y) != null
    fun isScrollbarDragActive(): Boolean = isScrollbarDragActive
    fun getScrollbarDragWidget(): TextBoxWidget? = scrollbarDragWidget

    fun onScrollWithPosition(dy: Float, cursorX: Float, cursorY: Float): Boolean {
        val scrollbarWidget = getScrollbarHoveredWidget(cursorX, cursorY)
        if (scrollbarWidget != null) { scrollbarWidget.onScroll(dy); notifyContentChanged(); return true }
        onScroll(dy); return false
    }

    fun onScroll(dy: Float) {
        for (widget in widgets) { if (widget.state == TextBoxWidget.State.HOVER_CONTENT || widget.state == TextBoxWidget.State.EDITING) { widget.onScroll(dy); notifyContentChanged(); break } }
        fullscreenTextWidget?.let { widget -> if (widget.state == TextBoxWidget.State.HOVER_CONTENT || widget.state == TextBoxWidget.State.EDITING) { widget.onScroll(dy); notifyContentChanged() } }
    }

    fun scrollWidgetAtCursor(cursorX: Float, cursorY: Float, dy: Float) {
        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(cursorX, cursorY)
        
        fullscreenTextWidget?.let { if (it.containsPoint(cx, cy)) { it.onScroll(dy); notifyContentChanged(); return } }
        fullscreenBrowserWidget?.let {
            if (it.hitTest(cx, cy) == BrowserWidget.HitArea.CONTENT) {
                it.onScroll(dy)
                notifyContentChanged()
                return
            }
        }
        fullscreenFileBrowserWidget?.let { if (it.containsPoint(cx, cy)) { it.onScroll(dy); notifyContentChanged(); return } }
        calendarWidget?.let { if (it.containsPoint(cx, cy) && !it.isMinimized) { it.onScroll(dy); notifyContentChanged(); return } }
        financeWidget?.let { if (it.containsPoint(cx, cy) && !it.isMinimized) { it.onScroll(dy); notifyContentChanged(); return } }
        newsWidget?.let { if (it.containsPoint(cx, cy) && !it.isMinimized) { it.onScroll(dy); notifyContentChanged(); return } }
        for (widget in browserWidgets.reversed()) {
            if (!widget.isMinimized && widget.hitTest(cx, cy) == BrowserWidget.HitArea.CONTENT) {
                widget.onScroll(dy)
                notifyContentChanged()
                return
            }
        }
        for (widget in imageWidgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
        for (widget in fileBrowserWidgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
        for (widget in widgets.reversed()) { if (widget.containsPoint(cx, cy) && !widget.isMinimized) { widget.onScroll(dy); notifyContentChanged(); return } }
    }

    // ==================== Tap Handling ====================

    fun onTap(x: Float, y: Float): Boolean {
        // Menus use screen-space coordinates (head-locked)
        if (closeConfirmationPopup?.isVisible == true) return closeConfirmationPopup?.onTap(x, y) ?: false
        if (formattingMenu?.isVisible == true) return formattingMenu?.onTap(x, y) ?: false
        if (textEditMenu?.isVisible == true) return textEditMenu?.onTap(x, y) ?: false
        if (contextMenu?.isVisible == true) return contextMenu?.onTap(x, y) ?: false
        if (speedometerSettingsMenu?.isVisible == true) return speedometerSettingsMenu?.onTap(x, y) ?: false

        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)

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

        if (hasFullscreenWidget()) {
            fullscreenTextWidget?.let { if (it.containsPoint(cx, cy)) return handleTextWidgetTap(it, cx, cy); return false }
            fullscreenBrowserWidget?.let { if (it.containsPoint(cx, cy)) return it.onTap(cx, cy); return false }
            fullscreenImageWidget?.let { if (it.containsPoint(cx, cy)) return handleImageWidgetTap(it, cx, cy); return false }
            fullscreenFileBrowserWidget?.let { if (it.containsPoint(cx, cy)) return handleFileBrowserWidgetTap(it, cx, cy); return false }
            if (isStatusBarFullscreen) { statusBarWidget?.let { if (it.containsPoint(cx, cy)) return handleStatusBarTap(it, cx, cy) }; return false }
            if (isLocationWidgetFullscreen) { locationWidget?.let { if (it.containsPoint(cx, cy)) return handleLocationWidgetTap(it, cx, cy) }; return false }
            if (isCalendarWidgetFullscreen) { calendarWidget?.let { if (it.containsPoint(cx, cy)) return handleCalendarWidgetTap(it, cx, cy) }; return false }
            if (isFinanceWidgetFullscreen) { financeWidget?.let { if (it.containsPoint(cx, cy)) return handleFinanceWidgetTap(it, cx, cy) }; return false }
            if (isNewsWidgetFullscreen) { newsWidget?.let { if (it.containsPoint(cx, cy)) return handleNewsWidgetTap(it, cx, cy) }; return false }
            if (isSpeedometerWidgetFullscreen) { speedometerWidget?.let { if (it.containsPoint(cx, cy)) return handleSpeedometerWidgetTap(it, cx, cy) }; return false }
            if (isMirrorWidgetFullscreen) { screenMirrorWidget?.let { if (it.containsPoint(cx, cy)) return handleMirrorWidgetTap(it, cx, cy) }; return false }
        }

        if (!hasFullscreenWidget() && closeButtonRect.contains(cx, cy)) { closeConfirmationPopup?.show(); notifyContentChanged(); return true }

        // YouTube button - opens YouTube history in fullscreen browser widget
        if (!hasFullscreenWidget() && youtubeButtonRect.contains(cx, cy)) {
            createYouTubeBrowserWidget()
            return true
        }

        // Modal dashboard check
        dashboardSettings?.let { settings ->
            if (settings.isVisible) {
                val consumed = settings.onTap(x, y) // close button will dismiss internally
                if (consumed) notifyContentChanged()
                return true // modal: swallow tap so it doesn't click widgets behind
            }
        }
        screenMirrorWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleMirrorWidgetTap(it, cx, cy) } }
        statusBarWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleStatusBarTap(it, cx, cy) } }
        locationWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleLocationWidgetTap(it, cx, cy) } }
        calendarWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleCalendarWidgetTap(it, cx, cy) } }
        financeWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleFinanceWidgetTap(it, cx, cy) } }
        newsWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleNewsWidgetTap(it, cx, cy) } }
        speedometerWidget?.let { if (it.containsPoint(cx, cy)) { if (it.isMinimized) { it.toggleMinimize(); notifyContentChanged(); return true }; return handleSpeedometerWidgetTap(it, cx, cy) } }
        for (widget in browserWidgets.reversed()) { if (widget.containsPoint(cx, cy)) { if (widget.isMinimized) { widget.toggleMinimize(); notifyContentChanged(); return true }; return widget.onTap(cx, cy) } }
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

    private fun handleSpeedometerWidgetTap(sw: SpeedometerWidget, x: Float, y: Float): Boolean {
        if (sw.onTap(x, y)) { notifyContentChanged(); return true }
        return handleBaseTap(sw, x, y)
    }

    fun onDoubleTap(x: Float, y: Float): Boolean {
        // Fix: Check for browser video fullscreen mode (e.g. YouTube) and exit it
        for (widget in browserWidgets) {
            if (widget.isInVideoFullscreen()) {
                widget.exitVideoFullscreen()
                return true
            }
        }

        // Transform to content space for widget hit testing
        val (cx, cy) = toContentSpace(x, y)

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

        if (isNewsWidgetFullscreen) {
            newsWidget?.let {
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
        }

        if (isSpeedometerWidgetFullscreen) {
            speedometerWidget?.let {
                if (it.containsPoint(cx, cy)) {
                    showSpeedometerSettingsMenu()
                    return true
                }
            }
            return false
        }

        if (hasFullscreenWidget() && fullscreenTextWidget == null) return false
        focusedWidget?.let { if (it.isFocused() && it.containsPoint(cx, cy)) { showTextEditMenu(phoneClipboard); return true } }
        fullscreenTextWidget?.let { if (it.isFocused() && it.containsPoint(cx, cy)) { showTextEditMenu(phoneClipboard); return true } }
        if (hasFullscreenWidget()) return false
        for (widget in widgets) { if (widget.containsPoint(cx, cy)) return false }
        for (widget in browserWidgets) { if (widget.containsPoint(cx, cy)) return false }
        screenMirrorWidget?.let { if (it.containsPoint(cx, cy)) return false }
        statusBarWidget?.let { if (it.containsPoint(cx, cy)) return false }
        calendarWidget?.let { if (it.containsPoint(cx, cy)) return false }
        locationWidget?.let { if (it.containsPoint(cx, cy)) return false }
        financeWidget?.let { if (it.containsPoint(cx, cy)) return false }
        newsWidget?.let { if (it.containsPoint(cx, cy)) return false }
        speedometerWidget?.let {
            if (it.containsPoint(cx, cy)) {
                showSpeedometerSettingsMenu()
                return true
            }
        }

        // Store content-space coordinates for widget creation
        pendingCreateX = cx; pendingCreateY = cy
        // Show context menu at screen position (head-locked)
        contextMenu?.show(x, y); notifyContentChanged()
        return true
    }

    fun onDragEnd() {
        cancelLongPress()
        if (mirrorWidgetDragging || mirrorWidgetResizing) { screenMirrorWidget?.onDragEnd(); mirrorWidgetDragging = false; mirrorWidgetResizing = false; notifyContentChanged() }
        if (statusBarDragging) { statusBarWidget?.onDragEnd(); statusBarDragging = false; notifyContentChanged() }
        if (locationWidgetDragging) { locationWidget?.onDragEnd(); locationWidgetDragging = false; notifyContentChanged() }
        if (calendarWidgetDragging) { calendarWidget?.onDragEnd(); calendarWidgetDragging = false; notifyContentChanged() }
        if (financeWidgetDragging) { financeWidget?.onDragEnd(); financeWidgetDragging = false; notifyContentChanged() }
        if (newsWidgetDragging) { newsWidget?.onDragEnd(); newsWidgetDragging = false; notifyContentChanged() }
        if (speedometerWidgetDragging) { speedometerWidget?.onDragEnd(); speedometerWidgetDragging = false; notifyContentChanged() }
        if (activeWidget != null) { activeWidget?.onDragEnd(); activeWidget = null; notifyContentChanged() }
    }

    // ==================== Widget Creation ====================

    private fun handleMenuItemSelected(item: ContextMenu.MenuItem) {
        when (item.id) { 
            "create_textbox" -> createTextBox(pendingCreateX, pendingCreateY)
            "create_browser" -> createBrowserWidget(pendingCreateX, pendingCreateY)
            "open_file" -> onFilePickerRequest?.invoke()
            "settings" -> toggleDashboardSettings()
        }
        notifyContentChanged()
    }

    /**
     * Toggle DashboardSettings.
     */
    private fun toggleDashboardSettings() {
        if (dashboardSettings != null) {
            dashboardSettings = null
            Log.d(TAG, "DashboardSettings removed")
        } else {
            createDashboardSettings()
            Log.d(TAG, "DashboardSettings created")
        }
    }

    private fun showSpeedometerSettingsMenu() {
        speedometerSettingsMenu?.let { menu ->
            menu.setThreshold(speedVisibilityThresholdKmh, notify = false)
            menu.show()
            notifyContentChanged()
        }
    }

    private fun createDashboardSettings() {
        dashboardSettings = DashboardSettings(width.toFloat(), height.toFloat()).apply {
            // Restore current values
            brightnessValue = this@WidgetContainer.brightnessValue
            adaptiveBrightnessEnabled = this@WidgetContainer.adaptiveBrightnessEnabled
            setHeadUpTimeMs(this@WidgetContainer.headUpTimeMs)
            setWakeDurationMs(this@WidgetContainer.wakeDurationMs)
            setAngleThresholdDegrees(this@WidgetContainer.angleThresholdDegrees)
            headsUpEnabled = this@WidgetContainer.headsUpEnabled
            googleAuthState = this@WidgetContainer.googleAuthState

            onBrightnessChanged = { value ->
                this@WidgetContainer.brightnessValue = value
                // Notify MainActivity to apply window-level brightness
                this@WidgetContainer.onBrightnessChanged?.invoke(value)
                invalidate()
            }

            onAdaptiveBrightnessChanged = { enabled ->
                this@WidgetContainer.adaptiveBrightnessEnabled = enabled
                this@WidgetContainer.onAdaptiveBrightnessChanged?.invoke(enabled)
                invalidate()
            }

            onHeadUpTimeChanged = { value ->
                val ms = headUpTimeValueToMs(value)
                this@WidgetContainer.headUpTimeMs = ms
                // Notify MainActivity to update HeadUpWakeManager
                this@WidgetContainer.onHeadUpTimeChanged?.invoke(ms)
                invalidate()
            }

            onWakeDurationChanged = { value ->
                val ms = wakeDurationValueToMs(value)
                this@WidgetContainer.wakeDurationMs = ms
                // Notify MainActivity to update HeadUpWakeManager
                this@WidgetContainer.onWakeDurationChanged?.invoke(ms)
                invalidate()
            }

            onAngleThresholdChanged = { degrees ->
                this@WidgetContainer.angleThresholdDegrees = degrees
                // Notify MainActivity to update HeadUpWakeManager
                this@WidgetContainer.onAngleThresholdChanged?.invoke(degrees)
                invalidate()
            }

            onHeadsUpEnabledChanged = { enabled ->
                this@WidgetContainer.headsUpEnabled = enabled
                // Notify MainActivity to update HeadUpWakeManager
                this@WidgetContainer.onHeadsUpEnabledChanged?.invoke(enabled)
                invalidate()
            }

            onConnectGoogle = {
                this@WidgetContainer.onConnectGoogle?.invoke()
                invalidate()
            }

            onGrantCalendarAccess = {
                this@WidgetContainer.onGrantCalendarAccess?.invoke()
                invalidate()
            }

            onDisconnectGoogle = {
                this@WidgetContainer.onDisconnectGoogle?.invoke()
                invalidate()
            }

            onRetryGoogleAuth = {
                this@WidgetContainer.onRetryGoogleAuth?.invoke()
                invalidate()
            }

            // When the menu dismisses (close button or outside tap), remove it from the container
            onDismissed = {
                toggleDashboardSettings() // sets dashboardSettings = null and triggers redraw flow
            }

            show()
        }
        notifyContentChanged()
    }

    /**
     * Set the brightness value programmatically (used for restoring persisted state).
     */
    fun setBrightness(value: Float) {
        brightnessValue = value.coerceIn(0f, 1f)
        onBrightnessChanged?.invoke(brightnessValue)
    }

    /**
     * Get the current brightness value (used for persistence).
     */
    fun getBrightness(): Float = brightnessValue

    fun setAdaptiveBrightnessEnabled(enabled: Boolean) {
        adaptiveBrightnessEnabled = enabled
        onAdaptiveBrightnessChanged?.invoke(adaptiveBrightnessEnabled)
    }

    fun getAdaptiveBrightnessEnabled(): Boolean = adaptiveBrightnessEnabled

    /**
     * Set the head-up time programmatically (used for restoring persisted state).
     */
    fun setHeadUpTime(ms: Long) {
        headUpTimeMs = ms.coerceIn(
            HeadUpWakeManager.MIN_HEAD_UP_HOLD_TIME_MS,
            HeadUpWakeManager.MAX_HEAD_UP_HOLD_TIME_MS
        )
        onHeadUpTimeChanged?.invoke(headUpTimeMs)
    }

    /**
     * Get the current head-up time (used for persistence).
     */
    fun getHeadUpTime(): Long = headUpTimeMs

    /**
     * Set the wake duration programmatically (used for restoring persisted state).
     */
    fun setWakeDuration(ms: Long) {
        wakeDurationMs = ms.coerceIn(
            HeadUpWakeManager.MIN_WAKE_DURATION_MS,
            HeadUpWakeManager.MAX_WAKE_DURATION_MS
        )
        onWakeDurationChanged?.invoke(wakeDurationMs)
    }

    /**
     * Get the current wake duration (used for persistence).
     */
    fun getWakeDuration(): Long = wakeDurationMs

    /**
     * Set the angle threshold programmatically (used for restoring persisted state).
     */
    fun setAngleThreshold(degrees: Float) {
        angleThresholdDegrees = degrees.coerceIn(
            DashboardSettings.MIN_ANGLE_THRESHOLD,
            DashboardSettings.MAX_ANGLE_THRESHOLD
        )
        onAngleThresholdChanged?.invoke(angleThresholdDegrees)
    }

    /**
     * Get the current angle threshold (used for persistence).
     */
    fun getAngleThreshold(): Float = angleThresholdDegrees

    /**
     * Set the heads-up enabled state programmatically (used for restoring persisted state).
     */
    fun setHeadsUpEnabled(enabled: Boolean) {
        headsUpEnabled = enabled
        onHeadsUpEnabledChanged?.invoke(headsUpEnabled)
    }

    /**
     * Get the current heads-up enabled state (used for persistence).
     */
    fun getHeadsUpEnabled(): Boolean = headsUpEnabled

    fun setGoogleAuthState(state: GoogleAuthState) {
        googleAuthState = state
        dashboardSettings?.googleAuthState = state
        invalidate()
        notifyContentChanged()
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
                ContextMenu.SubMenuItem("toggle_mirror", "Screen Mirror", screenMirrorWidget != null)
            )
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
            "toggle_mirror" -> toggleScreenMirrorWidget()
        }
        notifyContentChanged()
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
                    if (state == StatusBarWidget.State.MOVING) statusBarDragging = true
                    else if (statusBarDragging && state == StatusBarWidget.State.IDLE) statusBarDragging = false
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
            speedometerSettingsMenu?.dismiss()
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
            financeWidget?.release()
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
                pendingFinanceCountryCode?.let { setCountryCode(it) }
                setRefreshActive(displayState != WakeSleepManager.DisplayState.OFF)
                fetchData(force = true, reason = "widget_toggled_on")
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
            newsWidget?.release()
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
                pendingNewsCountryCode?.let { setCountryCode(it) }
                setRefreshActive(displayState != WakeSleepManager.DisplayState.OFF)
                refreshOnWake()
            }
            Log.d(TAG, "NewsWidget created")
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
            screenMirrorWidget?.release()
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
        widget.toggleFullscreen()
        widget.setFocused(true)
        setFocusedWidget(widget)
        notifyContentChanged()
    }

    private fun removeBrowserWidget(widget: BrowserWidget) {
        persistenceHelper.getBrowserWidgetState(widget)?.let {
            closedWidgetTemplates = closedWidgetTemplates.copy(browser = it)
        }
        if (fullscreenBrowserWidget == widget) fullscreenBrowserWidget = null
        if (focusedBrowserWidget == widget) setFocusedWidget(null)
        if (activeWidget == widget) activeWidget = null
        if (googleAuthBrowserWidget == widget) googleAuthBrowserWidget = null
        widget.destroy()
        registry.removeBrowserWidget(widget)
        notifyMediaBrightnessCapChanged()
        updateMinimizedWidgetPositions()
        notifyContentChanged()
    }

    fun showGoogleAuthBrowser(url: String) {
        closeGoogleAuthBrowser()

        val rootView = (parent as? ViewGroup) ?: return
        val widget = BrowserWidget(context, rootView, 0f, 0f, width.toFloat(), height.toFloat()).apply {
            shouldPersist = false
            // Keep auth visible while the display is asleep; only hide it when the display is OFF.
            isPinned = true
            configureBrowserWidget(this)
            onCloseRequested = {
                val wasActiveAuthBrowser = googleAuthBrowserWidget == this
                removeBrowserWidget(this)
                if (wasActiveAuthBrowser) {
                    onGoogleAuthBrowserClosed?.invoke()
                }
            }
        }

        val containerIndex = rootView.indexOfChild(this)
        val insertIndex = if (containerIndex >= 0) containerIndex else 0
        widget.getWebView()?.let { webView ->
            if (webView.parent == rootView) rootView.removeView(webView)
            rootView.addView(webView, insertIndex)
            webView.loadUrl(url)
        }

        registry.addBrowserWidget(widget)
        googleAuthBrowserWidget = widget
        widget.toggleFullscreen()
        widget.setFocused(true)
        setFocusedWidget(widget)
        notifyContentChanged()
    }

    fun closeGoogleAuthBrowser() {
        val authWidget = googleAuthBrowserWidget ?: return
        googleAuthBrowserWidget = null
        removeBrowserWidget(authWidget)
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

    fun onKeyPress(key: String) { focusedWidget?.onKeyPress(key); focusedBrowserWidget?.onKeyPress(key); notifyContentChanged() }

    // ==================== Text Edit Menu ====================

    fun showTextEditMenu(clipboardContent: String?) {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.isFocused()) return
        if (clipboardContent != null) phoneClipboard = clipboardContent
        // Request fresh clipboard from phone (will arrive async and update the menu)
        onClipboardRequest?.invoke()
        textEditMenu?.show(widget.x + widget.widgetWidth / 2 - 90f, widget.y - 60f, glassesClipboard ?: phoneClipboard, widget.hasSelection())
        notifyContentChanged()
    }
    
    /**
     * Update the visible text edit menu with new clipboard content.
     * Called when clipboard is received from phone while menu is visible.
     */
    fun updateTextEditMenuClipboard() {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return
        if (textEditMenu?.isVisible == true && widget.isFocused()) {
            textEditMenu?.updateClipboard(glassesClipboard ?: phoneClipboard)
            notifyContentChanged()
        }
    }

    private fun handleCut() { val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.hasSelection()) return; glassesClipboard = widget.getSelectedText(); widget.deleteSelection(); notifyContentChanged() }
    private fun handleCopy() { val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.hasSelection()) return; glassesClipboard = widget.getSelectedText(); notifyContentChanged() }
    private fun handlePaste(content: String) { (focusedWidget ?: fullscreenTextWidget)?.pasteText(content); notifyContentChanged() }
    private fun handleSelectAll() { (focusedWidget ?: fullscreenTextWidget)?.selectAll(); notifyContentChanged() }

    private fun showFormattingMenu() {
        val widget = focusedWidget ?: fullscreenTextWidget ?: return; if (!widget.isFocused()) return
        textEditMenu?.dismiss()
        formattingMenu?.show(widget.x + widget.widgetWidth / 2, widget.y - 20f, widget.textFontSize, widget.isTextWrap, widget.getColumnCount())
        formattingMenu?.apply { isBold = widget.isBoldActive(); isItalic = widget.isItalicActive(); isUnderline = widget.isUnderlineActive() }
        notifyContentChanged()
    }

    private fun handleFontSizeChanged(size: Float) { (focusedWidget ?: fullscreenTextWidget)?.setFontSize(size); notifyContentChanged() }
    private fun handleTextWrapToggled(wrap: Boolean) { (focusedWidget ?: fullscreenTextWidget)?.setTextWrap(wrap); notifyContentChanged() }
    private fun handleBoldToggled() { val widget = focusedWidget ?: fullscreenTextWidget ?: return; widget.toggleBold(); formattingMenu?.isBold = widget.isBoldActive(); notifyContentChanged() }
    private fun handleItalicToggled() { val widget = focusedWidget ?: fullscreenTextWidget ?: return; widget.toggleItalic(); formattingMenu?.isItalic = widget.isItalicActive(); notifyContentChanged() }
    private fun handleUnderlineToggled() { val widget = focusedWidget ?: fullscreenTextWidget ?: return; widget.toggleUnderline(); formattingMenu?.isUnderline = widget.isUnderlineActive(); notifyContentChanged() }
    private fun handleBulletListToggled() { (focusedWidget ?: fullscreenTextWidget)?.toggleBulletList(); notifyContentChanged() }
    private fun handleNumberedListToggled() { (focusedWidget ?: fullscreenTextWidget)?.toggleNumberedList(); notifyContentChanged() }

    // ==================== Public Accessors ====================

    fun getEffectiveClipboard(): String? = glassesClipboard ?: phoneClipboard
    fun setPhoneClipboard(content: String?) {
        phoneClipboard = content
        // Update visible text edit menu with new clipboard content
        updateTextEditMenuClipboard()
    }
    fun hasFocusedWidget(): Boolean = focusedWidget != null || focusedBrowserWidget != null
    fun isMenuVisible(): Boolean = contextMenu?.isVisible == true || textEditMenu?.isVisible == true || formattingMenu?.isVisible == true
    fun isClosePopupVisible(): Boolean = closeConfirmationPopup?.isVisible == true
    fun isAnyPopupVisible(): Boolean = isMenuVisible() || isClosePopupVisible()
    fun dismissMenu() { contextMenu?.dismiss(); textEditMenu?.dismiss(); formattingMenu?.dismiss() }
    fun dismissClosePopup() { closeConfirmationPopup?.dismiss() }
    fun isDragging(): Boolean =
        activeWidget != null ||
        statusBarDragging ||
        locationWidgetDragging ||
        calendarWidgetDragging ||
        financeWidgetDragging ||
        newsWidgetDragging ||
        speedometerWidgetDragging ||
        mirrorWidgetDragging ||
        mirrorWidgetResizing ||
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

    /**
     * Get the current 3DOF content offset.
     */
    fun get3DofOffset(): Pair<Float, Float> = Pair(threeDofOffsetX, threeDofOffsetY)

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
                isSpeedometerWidgetFullscreen && speedometerWidget?.isPinned == true -> speedometerWidget
                isMirrorWidgetFullscreen && screenMirrorWidget?.isPinned == true -> screenMirrorWidget
                else -> null
            }

            if (pinnedFullscreenWidget != null) {
                // Only draw the pinned fullscreen widget - it covers everything else
                pinnedFullscreenWidget.draw(canvas)
                drawOverlayMenus(canvas)
                return
            }

            // No pinned fullscreen widget - draw all pinned widgets normally
            for (widget in registry.getPinnedWidgetsSortedByZOrder()) widget.draw(canvas)
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
            if (isSpeedometerWidgetFullscreen) speedometerWidget?.draw(canvas)
            if (isMirrorWidgetFullscreen) screenMirrorWidget?.draw(canvas)
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

        // Draw close button (affected by 3DOF to stay with content)
        drawCloseButton(canvas)

        // Draw YouTube button (only when hovered, affected by 3DOF to stay with content)
        drawYoutubeButton(canvas)

        // Draw all widgets (affected by 3DOF transformation)
        for (widget in registry.getAllSortedByZOrder()) {
            widget.draw(canvas)
        }

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
        contextMenu?.draw(canvas)
        textEditMenu?.draw(canvas)
        formattingMenu?.draw(canvas)
        closeConfirmationPopup?.draw(canvas)
        dashboardSettings?.draw(canvas)
        speedometerSettingsMenu?.draw(canvas)
    }

    private fun drawCloseButton(canvas: Canvas) {
        if (!closeButtonHovered) return
        canvas.drawRoundRect(closeButtonRect, 4f, 4f, closeButtonHoverPaint)
        val padding = 10f
        canvas.drawLine(closeButtonRect.left + padding, closeButtonRect.top + padding, closeButtonRect.right - padding, closeButtonRect.bottom - padding, closeButtonCrossPaint)
        canvas.drawLine(closeButtonRect.right - padding, closeButtonRect.top + padding, closeButtonRect.left + padding, closeButtonRect.bottom - padding, closeButtonCrossPaint)
    }

    /**
     * Draw the YouTube button with YouTube icon.
     * Only draws when the button is hovered.
     */
    private fun drawYoutubeButton(canvas: Canvas) {
        if (!youtubeButtonHovered) return

        // Draw background
        canvas.drawRoundRect(youtubeButtonRect, 8f, 8f, youtubeButtonBackgroundPaint)

        // Draw YouTube icon (rounded rectangle with play button)
        val cx = youtubeButtonRect.centerX()
        val cy = youtubeButtonRect.centerY()
        val iconWidth = YOUTUBE_BUTTON_SIZE * 0.6f
        val iconHeight = YOUTUBE_BUTTON_SIZE * 0.42f
        val cornerRadius = 8f

        // YouTube red rounded rectangle
        val iconRect = RectF(
            cx - iconWidth / 2,
            cy - iconHeight / 2,
            cx + iconWidth / 2,
            cy + iconHeight / 2
        )
        canvas.drawRoundRect(iconRect, cornerRadius, cornerRadius, youtubeButtonIconPaint)

        // White play triangle
        val playSize = iconHeight * 0.4f
        val playPath = android.graphics.Path()
        playPath.moveTo(cx - playSize * 0.4f, cy - playSize)
        playPath.lineTo(cx - playSize * 0.4f, cy + playSize)
        playPath.lineTo(cx + playSize * 0.8f, cy)
        playPath.close()
        canvas.drawPath(playPath, youtubePlayIconPaint)
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
    fun getSpeedometerWidgetState(): WidgetPersistence.SpeedometerWidgetState? = persistenceHelper.getSpeedometerWidgetState(speedometerWidget)
    fun getMirrorWidgetState(): WidgetPersistence.MirrorWidgetState? = persistenceHelper.getMirrorWidgetState(screenMirrorWidget)
    fun getClosedWidgetTemplates(): WidgetPersistence.ClosedWidgetTemplates = closedWidgetTemplates
    fun setClosedWidgetTemplates(templates: WidgetPersistence.ClosedWidgetTemplates) {
        closedWidgetTemplates = templates
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
                    if (s == StatusBarWidget.State.MOVING) statusBarDragging = true
                    else if (statusBarDragging && s == StatusBarWidget.State.IDLE) statusBarDragging = false
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
        notifyContentChanged()
    }

    fun hasVisibleBrowserWidget(): Boolean = browserWidgets.any { isBrowserWidgetVisible(it) }

    fun shouldApplyMediaBrightnessCap(): Boolean {
        val browserCapActive = hasVisibleBrowserWidget()
        val mirrorCapActive = screenMirrorWidget?.shouldApplyMediaBrightnessCap() == true
        return browserCapActive || mirrorCapActive
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
                pendingFinanceCountryCode?.let { setCountryCode(it) }
                setRefreshActive(displayState != WakeSleepManager.DisplayState.OFF)
            }
        }
        persistenceHelper.restoreFinanceWidgetPosition(financeWidget, state, width, height)
        restoreFullscreenIfNeeded(financeWidget, state.isFullscreen)
        financeWidget?.fetchData(force = true, reason = "widget_restored")
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
                pendingNewsCountryCode?.let { setCountryCode(it) }
                setRefreshActive(displayState != WakeSleepManager.DisplayState.OFF)
            }
        }
        persistenceHelper.restoreNewsWidgetPosition(newsWidget, state, width, height)
        restoreFullscreenIfNeeded(newsWidget, state.isFullscreen)
        newsWidget?.refreshOnWake()
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

    // Keep old methods for backwards compatibility but mark them as deprecated
    @Deprecated("Use restoreStatusBarWidget instead", ReplaceWith("restoreStatusBarWidget(state)"))
    fun restoreStatusBarPosition(state: WidgetPersistence.StatusBarState) { restoreStatusBarWidget(state) }
    @Deprecated("Use restoreLocationWidget instead", ReplaceWith("restoreLocationWidget(state)"))
    fun restoreLocationWidgetPosition(state: WidgetPersistence.LocationWidgetState) { restoreLocationWidget(state) }
    @Deprecated("Use restoreMirrorWidget instead", ReplaceWith("restoreMirrorWidget(state)"))
    fun restoreMirrorWidgetPosition(state: WidgetPersistence.MirrorWidgetState) { restoreMirrorWidget(state) }

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
        financeWidget?.release()
        newsWidget?.release()
        screenMirrorWidget?.let { setBinocularMediaSourceActive(it.binocularSourceId, false) }
        screenMirrorWidget?.release()
        for (widget in browserWidgets) {
            widget.destroy()
        }
    }
}
