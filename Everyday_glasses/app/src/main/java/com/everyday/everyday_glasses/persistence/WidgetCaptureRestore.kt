package com.everyday.everyday_glasses.persistence

import android.content.Context
import android.util.Log
import com.everyday.everyday_glasses.runtime.TextBoxWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetPersistenceListener
import com.everyday.everyday_glasses.ui.widgets.BaseWidget
import com.everyday.everyday_glasses.ui.widgets.BrowserWidget
import com.everyday.everyday_glasses.ui.widgets.CalendarWidget
import com.everyday.everyday_glasses.ui.widgets.FinanceWidget
import com.everyday.everyday_glasses.ui.widgets.ImageWidget
import com.everyday.everyday_glasses.ui.widgets.LocationWidget
import com.everyday.everyday_glasses.ui.widgets.MapWidget
import com.everyday.everyday_glasses.ui.widgets.NewsWidget
import com.everyday.everyday_glasses.ui.widgets.NotificationsWidget
import com.everyday.everyday_glasses.ui.widgets.PdfWidget
import com.everyday.everyday_glasses.ui.widgets.ScreenMirrorWidget
import com.everyday.everyday_glasses.ui.widgets.SpeedometerWidget
import com.everyday.everyday_glasses.ui.widgets.StatusBarWidget
import com.everyday.everyday_glasses.ui.widgets.SubtitleWidget
import com.everyday.everyday_glasses.ui.widgets.TextBoxWidget
import com.everyday.everyday_glasses.ui.widgets.VideoWidget
import java.io.File

/**
 * Captures saveable properties from live widgets and restores those properties from
 * [DashboardPersistence] descriptions.
 *
 * This class does not read/write durable storage, encode/decode JSON, decide when saving occurs,
 * or own widget lifecycle registration and destruction.
 *
 * Some legacy bulk-restore methods still construct widgets. Move those construction paths into
 * focused factories as their widget types are refactored; do not expand that legacy role.
 */
class WidgetCaptureRestore(private val context: Context) {

    companion object {
        private const val TAG = "WidgetCaptureRestore"
    }

    /** Widget restore events consumed by [com.everyday.everyday_glasses.WidgetContainer]. */
    var listener: WidgetPersistenceListener? = null
    private val onWidgetFocusChanged: ((BaseWidget?, Boolean) -> Unit)?
        get() = listener?.let { value -> { widget, focused -> value.onWidgetFocusChanged(widget, focused) } }
    private val onWidgetStateChanged: (() -> Unit)? get() = listener?.let { value -> { value.onWidgetStateChanged() } }

    // ==================== Common-field helpers ====================

    /**
     * Container for the common persistence fields extracted from a [BaseWidget].
     * Used as an intermediate representation when building widget state DTOs.
     */
    data class ExtractedCommonFields(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val isMinimized: Boolean,
        val isFullscreen: Boolean,
        val savedMinX: Float,
        val savedMinY: Float,
        val savedMinWidth: Float,
        val savedMinHeight: Float,
        val isPinned: Boolean
    )

    /** Extract the common persistence fields from any [BaseWidget]. */
    private fun extractCommonFields(widget: BaseWidget): ExtractedCommonFields {
        val persistenceBounds = widget.getPersistenceBounds()
        return ExtractedCommonFields(
            x = persistenceBounds.left,
            y = persistenceBounds.top,
            width = persistenceBounds.width(),
            height = persistenceBounds.height(),
            isMinimized = widget.isMinimized,
            isFullscreen = widget.isFullscreen,
            savedMinX = widget.savedMinX,
            savedMinY = widget.savedMinY,
            savedMinWidth = widget.savedMinWidth,
            savedMinHeight = widget.savedMinHeight,
            isPinned = widget.isPinned
        )
    }

    /**
     * Restore isPinned and minimize state on a widget from a [DashboardPersistence.CommonWidgetFields] state.
     *
     * @param widget The widget to restore state on
     * @param state  The persisted common fields
     * @param adjustedX The screen-bounds-adjusted X position for the widget
     * @param adjustedY The screen-bounds-adjusted Y position for the widget
     */
    private fun restoreCommonState(
        widget: BaseWidget,
        state: DashboardPersistence.CommonWidgetFields,
        adjustedX: Float,
        adjustedY: Float
    ) {
        widget.isPinned = state.isPinned

        if (state.isMinimized) {
            // Set the saved restore bounds first
            widget.setPosition(state.savedMinX, state.savedMinY)
            widget.widgetWidth = state.savedMinWidth
            widget.widgetHeight = state.savedMinHeight

            // Trigger minimize
            widget.toggleMinimize()

            // Position to the minimized location
            widget.setPosition(adjustedX, adjustedY)
        }
    }

    // ==================== Extract State ====================

    /**
     * Get a single text widget state for persistence.
     */
    fun getTextWidgetState(widget: TextBoxWidget): DashboardPersistence.TextWidgetState {
        val c = extractCommonFields(widget)
        return DashboardPersistence.TextWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            text = widget.text,
            html = widget.getHtmlContent(),
            fontSize = widget.textFontSize,
            isTextWrap = widget.isTextWrap,
            columnCount = widget.getColumnCount(),
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    /**
     * Get all image widget states for persistence.
     */
    fun getImageWidgetStates(widgets: List<ImageWidget>): List<DashboardPersistence.ImageWidgetState> {
        return widgets.mapNotNull { widget ->
            val imagePath = widget.getImagePath()
            if (imagePath.isBlank()) {
                null
            } else {
                val c = extractCommonFields(widget)
                DashboardPersistence.ImageWidgetState(
                    x = c.x, y = c.y, width = c.width, height = c.height,
                    imagePath = imagePath,
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }
        }
    }

    fun getImageWidgetState(widget: ImageWidget): DashboardPersistence.ImageWidgetState? =
        getImageWidgetStates(listOf(widget)).singleOrNull()

    fun getVideoWidgetStates(widgets: List<VideoWidget>): List<DashboardPersistence.VideoWidgetState> {
        return widgets.map { widget ->
            val c = extractCommonFields(widget)
            DashboardPersistence.VideoWidgetState(
                x = c.x,
                y = c.y,
                width = c.width,
                height = c.height,
                uri = widget.contentUri.toString(),
                displayName = widget.displayName,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX,
                savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth,
                savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned,
            )
        }
    }

    fun getVideoWidgetState(widget: VideoWidget): DashboardPersistence.VideoWidgetState =
        getVideoWidgetStates(listOf(widget)).single()

    fun restoreVideoWidgetState(
        widget: VideoWidget,
        state: DashboardPersistence.VideoWidgetState,
        adjustedX: Float,
        adjustedY: Float,
    ) {
        restoreCommonState(widget, state, adjustedX, adjustedY)
    }

    fun getPdfWidgetStates(widgets: List<PdfWidget>): List<DashboardPersistence.PdfWidgetState> {
        return widgets.map { widget ->
            val c = extractCommonFields(widget)
            DashboardPersistence.PdfWidgetState(
                x = c.x,
                y = c.y,
                width = c.width,
                height = c.height,
                uri = widget.contentUri.toString(),
                displayName = widget.displayName,
                page = widget.currentPage,
                zoom = widget.zoom,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX,
                savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth,
                savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned,
            )
        }
    }

    fun getPdfWidgetState(widget: PdfWidget): DashboardPersistence.PdfWidgetState =
        getPdfWidgetStates(listOf(widget)).single()

    fun restorePdfWidgetState(
        widget: PdfWidget,
        state: DashboardPersistence.PdfWidgetState,
        adjustedX: Float,
        adjustedY: Float,
    ) {
        restoreCommonState(widget, state, adjustedX, adjustedY)
    }

    /**
     * Get status bar state for persistence.
     * Includes position, minimize state, and pin state.
     */
    fun getStatusBarState(statusBarWidget: StatusBarWidget?): DashboardPersistence.StatusBarState? {
        return statusBarWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.StatusBarState(
                x = c.x, y = c.y,
                showTime = it.isTimeVisible(),
                showDate = it.isDateVisible(),
                showPhoneBattery = it.isPhoneBatteryVisible(),
                showGlassesBattery = it.isGlassesBatteryVisible(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    /**
     * Get a single browser widget state for persistence.
     */
    fun getBrowserWidgetState(widget: BrowserWidget): DashboardPersistence.BrowserWidgetState? {
        if (!widget.shouldPersist) {
            return null
        }

        val c = extractCommonFields(widget)
        return DashboardPersistence.BrowserWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            url = widget.getUrl() ?: "https://www.google.com",
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    /**
     * Get location widget state for persistence.
     */
    fun getLocationWidgetState(locationWidget: LocationWidget?): DashboardPersistence.LocationWidgetState? {
        return locationWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.LocationWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                showWeather = it.isWeatherVisible(),
                showTemperature = it.isTemperatureVisible(),
                showLocation = it.isLocationVisible(),
                showCountry = it.isCountryVisible(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    fun getMapWidgetState(widget: MapWidget?): DashboardPersistence.MapWidgetState? = widget?.let {
        val c = extractCommonFields(it)
        DashboardPersistence.MapWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            mode = it.mode.name, zoom = it.zoom.name,
            showLocation = it.isLocationVisible(), showCountry = it.isCountryVisible(),
            poiConfig = it.poiDisplayConfig,
            isMinimized = c.isMinimized, isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    /**
     * Get calendar widget state for persistence.
     */
    fun getCalendarWidgetState(calendarWidget: CalendarWidget?): DashboardPersistence.CalendarWidgetState? {
        return calendarWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.CalendarWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                fontScale = it.getFontScale(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    /**
     * Get mirror widget state for persistence.
     */
    fun getMirrorWidgetState(screenMirrorWidget: ScreenMirrorWidget?): DashboardPersistence.MirrorWidgetState? {
        return screenMirrorWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.MirrorWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    /**
     * Get news widget state for persistence.
     */
    fun getNewsWidgetState(newsWidget: NewsWidget?): DashboardPersistence.NewsWidgetState? {
        return newsWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.NewsWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                countryCode = it.getCountryCode(),
                sourceSpec = it.getSourceSpec(),
                selectedIndex = it.getSelectedIndex(),
                fontScale = it.getFontScale(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    fun getNotificationsWidgetState(notificationsWidget: NotificationsWidget?): DashboardPersistence.NotificationsWidgetState? {
        return notificationsWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.NotificationsWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                widgetFontScale = it.getWidgetFontScale(),
                toastFontScale = it.getToastFontScale(),
                scrollIndex = it.getScrollIndex(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    /**
     * Get speedometer widget state for persistence.
     */
    fun getSpeedometerWidgetState(speedometerWidget: SpeedometerWidget?): DashboardPersistence.SpeedometerWidgetState? {
        return speedometerWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.SpeedometerWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    /**
     * Get subtitle widget state for persistence. Capture-enabled is deliberately omitted.
     */
    fun getSubtitleWidgetState(subtitleWidget: SubtitleWidget?): DashboardPersistence.SubtitleWidgetState? {
        return subtitleWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.SubtitleWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                phonePlaybackEnabled = it.options.phonePlaybackEnabled,
                microphoneEnabled = it.options.microphoneEnabled,
                translationEnabled = it.options.translationEnabled,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    /**
     * Get finance widget state for persistence.
     */
    fun getFinanceWidgetState(financeWidget: FinanceWidget?): DashboardPersistence.FinanceWidgetState? {
        return financeWidget?.let {
            val c = extractCommonFields(it)
            DashboardPersistence.FinanceWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                selectedSymbol = it.currentSymbol,
                selectedRange = it.currentRange.range,
                tiles = it.getTileConfigs(),
                navigationIndex = it.getNavigationIndex(),
                tilingSpan = it.getTilingSpan(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }
    }

    // ==================== Restore State ====================

    /**
     * Restore text widgets from saved states.
     * Uses HTML content if available for rich text, otherwise falls back to plain text.
     * Restores minimize state and pin state.
     *
     * @param states The list of saved text widget states
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     * @return List of restored TextBoxWidget instances
     */
    fun restoreTextWidgets(
        states: List<DashboardPersistence.TextWidgetState>,
        screenWidth: Int,
        screenHeight: Int
    ): List<TextBoxWidget> {
        Log.d(TAG, "Restoring ${states.size} text widgets")

        return states.map { state ->
            // Validate bounds against current screen size
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            TextBoxWidget(
                context,
                adjustedX,
                adjustedY,
                adjustedWidth,
                adjustedHeight
            ).apply {
                // Restore content: use HTML if available, otherwise plain text
                if (!state.html.isNullOrEmpty()) {
                    setHtmlContent(state.html)
                } else {
                    setTextContent(state.text)
                }
                setFontSize(state.fontSize)
                setTextWrap(state.isTextWrap)
                setColumns(state.columnCount)

                restoreCommonState(this, state, adjustedX, adjustedY)

                listener = object : TextBoxWidgetListener {
                    override fun onFocusChanged(focused: Boolean) {
                        onWidgetFocusChanged?.invoke(this@apply, focused)
                    }
                    override fun onStateChanged(state: TextBoxWidget.State) {
                        onWidgetStateChanged?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Restore image widgets from saved states.
     *
     * Missing image files are skipped so one bad path does not block the rest.
     */
    fun restoreImageWidgets(
        states: List<DashboardPersistence.ImageWidgetState>,
        screenWidth: Int,
        screenHeight: Int
    ): List<ImageWidget> {
        Log.d(TAG, "Restoring ${states.size} image widgets")

        return states.mapNotNull { state ->
            val imageFile = File(state.imagePath)
            if (!imageFile.exists()) {
                Log.w(TAG, "Skipping missing image widget file: ${state.imagePath}")
                return@mapNotNull null
            }

            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())
            val adjustedX = state.x.coerceIn(0f, (screenWidth - adjustedWidth).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - adjustedHeight).coerceAtLeast(0f))

            ImageWidget(
                adjustedX,
                adjustedY,
                state.imagePath
            ).apply {
                widgetWidth = adjustedWidth
                widgetHeight = adjustedHeight
                restoreCommonState(this, state, adjustedX, adjustedY)
            }
        }
    }

    /**
     * Restore status bar state from saved state.
     * Restores position, minimize state, and pin state.
     *
     * @param statusBarWidget The status bar widget to restore state for
     * @param state The saved state
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     */
    fun restoreStatusBarPosition(
        statusBarWidget: StatusBarWidget?,
        state: DashboardPersistence.StatusBarState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        statusBarWidget?.let { statusBar ->
            // Validate bounds against current screen size
            val adjustedX = state.x.coerceIn(0f, (screenWidth - statusBar.widgetWidth).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - statusBar.widgetHeight).coerceAtLeast(0f))

            statusBar.setPosition(adjustedX, adjustedY)
            statusBar.setElementVisibility(
                showTime = state.showTime,
                showDate = state.showDate,
                showPhoneBattery = state.showPhoneBattery,
                showGlassesBattery = state.showGlassesBattery
            )
            restoreCommonState(statusBar, state, adjustedX, adjustedY)

            Log.d(
                TAG,
                "Restored status bar: ($adjustedX, $adjustedY) pinned=${state.isPinned} minimized=${state.isMinimized} " +
                    "time=${state.showTime} date=${state.showDate} phone=${state.showPhoneBattery} glasses=${state.showGlassesBattery}"
            )
        }
    }
    
    /** Apply saved browser-specific and common fields to an already-created widget. */
    fun restoreBrowserWidgetState(
        widget: BrowserWidget,
        state: DashboardPersistence.BrowserWidgetState,
        adjustedX: Float,
        adjustedY: Float,
    ) {
        widget.getWebView()?.loadUrl(state.url)
        restoreCommonState(widget, state, adjustedX, adjustedY)
    }

    /**
     * Restore location widget position from saved state.
     *
     * @param locationWidget The location widget to restore position for
     * @param state The saved state
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     */
    fun restoreLocationWidgetPosition(
        locationWidget: LocationWidget?,
        state: DashboardPersistence.LocationWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        locationWidget?.let { loc ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            loc.setPosition(adjustedX, adjustedY)
            loc.widgetWidth = adjustedWidth
            loc.widgetHeight = adjustedHeight
            loc.setElementVisibility(
                showWeather = state.showWeather,
                showTemperature = state.showTemperature,
                showLocation = state.showLocation,
                showCountry = state.showCountry
            )
            restoreCommonState(loc, state, adjustedX, adjustedY)

            Log.d(
                TAG,
                "Restored location widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight} " +
                    "weather=${state.showWeather} temp=${state.showTemperature} location=${state.showLocation} country=${state.showCountry}"
            )
        }
    }

    fun restoreMapWidgetPosition(
        widget: MapWidget?, state: DashboardPersistence.MapWidgetState,
        screenWidth: Int, screenHeight: Int
    ) {
        widget ?: return
        val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
        val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())
        val adjustedX = state.x.coerceIn(0f, (screenWidth - adjustedWidth).coerceAtLeast(0f))
        val adjustedY = state.y.coerceIn(0f, (screenHeight - adjustedHeight).coerceAtLeast(0f))
        widget.setPosition(adjustedX, adjustedY)
        widget.widgetWidth = adjustedWidth
        widget.widgetHeight = adjustedHeight
        restoreCommonState(widget, state, adjustedX, adjustedY)
        widget.restorePreferences(
            state.mode,
            state.zoom,
            state.showLocation,
            state.showCountry,
            state.poiConfig
        )
    }

    /**
     * Restore calendar widget position from saved state.
     */
    fun restoreCalendarWidgetPosition(
        calendarWidget: CalendarWidget?,
        state: DashboardPersistence.CalendarWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        calendarWidget?.let { widget ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            widget.setPosition(adjustedX, adjustedY)
            widget.widgetWidth = adjustedWidth
            widget.widgetHeight = adjustedHeight
            restoreCommonState(widget, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored calendar widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight}")
        }
    }

    /**
     * Restore mirror widget state from saved state.
     *
     * @param screenMirrorWidget The mirror widget to restore state for
     * @param state The saved state
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     */
    fun restoreMirrorWidgetPosition(
        screenMirrorWidget: ScreenMirrorWidget?,
        state: DashboardPersistence.MirrorWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        screenMirrorWidget?.let { mirror ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            mirror.setPosition(adjustedX, adjustedY)
            mirror.widgetWidth = adjustedWidth
            mirror.widgetHeight = adjustedHeight
            restoreCommonState(mirror, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored mirror widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight} pinned=${state.isPinned} minimized=${state.isMinimized}")
        }
    }

    /**
     * Restore finance widget position from saved state.
     *
     * @param financeWidget The finance widget to restore position for
     * @param state The saved state
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     */
    fun restoreFinanceWidgetPosition(
        financeWidget: FinanceWidget?,
        state: DashboardPersistence.FinanceWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        financeWidget?.let { fw ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            fw.setPosition(adjustedX, adjustedY)
            fw.widgetWidth = adjustedWidth
            fw.widgetHeight = adjustedHeight

            fw.restoreDashboard(state.tiles, state.navigationIndex, state.tilingSpan)

            restoreCommonState(fw, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored finance widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight} symbol=${state.selectedSymbol} range=${state.selectedRange}")
        }
    }

    /**
     * Restore news widget position from saved state.
     *
     * @param newsWidget The news widget to restore position for
     * @param state The saved state
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     */
    fun restoreNewsWidgetPosition(
        newsWidget: NewsWidget?,
        state: DashboardPersistence.NewsWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        newsWidget?.let { nw ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            nw.setPosition(adjustedX, adjustedY)
            nw.widgetWidth = adjustedWidth
            nw.widgetHeight = adjustedHeight
            nw.setSourceSpec(state.sourceSpec)
            nw.setCountryCode(state.countryCode)
            nw.setSelectedIndex(state.selectedIndex)
            nw.setFontScale(state.fontScale)

            restoreCommonState(nw, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored news widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight} country=${state.countryCode} index=${state.selectedIndex} fontScale=${state.fontScale}")
        }
    }

    fun restoreNotificationsWidgetPosition(
        notificationsWidget: NotificationsWidget?,
        state: DashboardPersistence.NotificationsWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        notificationsWidget?.let { widget ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            widget.setPosition(adjustedX, adjustedY)
            widget.widgetWidth = adjustedWidth
            widget.widgetHeight = adjustedHeight
            widget.setWidgetFontScale(state.widgetFontScale)
            widget.setToastFontScale(state.toastFontScale)
            widget.setScrollIndex(state.scrollIndex)
            restoreCommonState(widget, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored notifications widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight} index=${state.scrollIndex}")
        }
    }

    /**
     * Restore speedometer widget position from saved state.
     *
     * @param speedometerWidget The speedometer widget to restore position for
     * @param state The saved state
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     */
    fun restoreSpeedometerWidgetPosition(
        speedometerWidget: SpeedometerWidget?,
        state: DashboardPersistence.SpeedometerWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        speedometerWidget?.let { sw ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            sw.setPosition(adjustedX, adjustedY)
            sw.widgetWidth = adjustedWidth
            sw.widgetHeight = adjustedHeight

            restoreCommonState(sw, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored speedometer widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight}")
        }
    }

    /**
     * Restore subtitle widget position and persisted options from saved state.
     */
    fun restoreSubtitleWidgetPosition(
        subtitleWidget: SubtitleWidget?,
        state: DashboardPersistence.SubtitleWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        subtitleWidget?.let { sw ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())

            sw.setPosition(adjustedX, adjustedY)
            sw.widgetWidth = adjustedWidth
            sw.widgetHeight = adjustedHeight
            sw.restoreOptions(
                phonePlaybackEnabled = state.phonePlaybackEnabled,
                microphoneEnabled = state.microphoneEnabled,
                translationEnabled = state.translationEnabled
            )

            restoreCommonState(sw, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored subtitle widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight}")
        }
    }
}