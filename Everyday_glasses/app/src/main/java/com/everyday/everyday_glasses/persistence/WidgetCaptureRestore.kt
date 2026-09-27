package com.everyday.everyday_glasses.persistence

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.browser.BrowserWidget
import com.everyday.everyday_glasses.widgets.calendar.CalendarWidget
import com.everyday.everyday_glasses.widgets.finance.FinanceWidget
import com.everyday.everyday_glasses.widgets.file_browser.ImageWidget
import com.everyday.everyday_glasses.widgets.weather_location.WeatherWidget
import com.everyday.everyday_glasses.widgets.map.MapWidget
import com.everyday.everyday_glasses.widgets.news.NewsWidget
import com.everyday.everyday_glasses.widgets.notifications.NotificationsWidget
import com.everyday.everyday_glasses.widgets.file_browser.PdfWidget
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.ScreenMirrorWidget
import com.everyday.everyday_glasses.widgets.speedometer.SpeedometerWidget
import com.everyday.everyday_glasses.widgets.system_status.StatusBarWidget
import com.everyday.everyday_glasses.widgets.speech_to_text.SubtitleWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import com.everyday.everyday_glasses.widgets.file_browser.VideoWidget
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
class WidgetCaptureRestore {

    companion object {
        private const val TAG = "WidgetCaptureRestore"

        /** The saved content URI, if it can still be opened for reading; otherwise null. */
        fun readableUri(context: Context, uriString: String): Uri? {
            val uri = runCatching { uriString.toUri() }.getOrNull() ?: return null
            val readable = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            return uri.takeIf { readable }
        }
    }

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
     * Restore the fields every widget saves, in this order:
     * 1. its size (a status bar keeps its own size, since its state has none) and its position,
     *    both clamped to the screen;
     * 2. [restoreOwnFields]: the fields only this widget type saves;
     * 3. pinned, then either minimized or fullscreen (a minimized widget is never fullscreen).
     *
     * Fullscreen is applied here, before the widget is registered; the lifecycle controller then
     * fits it to the viewport.
     */
    fun restoreCommonFields(
        widget: BaseWidget,
        state: DashboardPersistence.CommonWidgetFields,
        screenWidth: Int,
        screenHeight: Int,
        restoreOwnFields: () -> Unit = {}
    ) {
        if (state is DashboardPersistence.SizedWidgetFields) {
            widget.widgetWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            widget.widgetHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())
        }
        val adjustedX = state.x.coerceIn(0f, (screenWidth - widget.widgetWidth).coerceAtLeast(0f))
        val adjustedY = state.y.coerceIn(0f, (screenHeight - widget.widgetHeight).coerceAtLeast(0f))
        widget.setPosition(adjustedX, adjustedY)
        Log.d(
            TAG,
            "Restored ${widget.type.persistedId}: ($adjustedX, $adjustedY) ${widget.widgetWidth}x${widget.widgetHeight} " +
                    "pinned=${state.isPinned} minimized=${state.isMinimized} fullscreen=${state.isFullscreen}"
        )

        restoreOwnFields()

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
        } else if (state.isFullscreen && !widget.isFullscreen) {
            widget.toggleFullscreen()
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
    fun getLocationWidgetState(weatherWidget: WeatherWidget?): DashboardPersistence.LocationWidgetState? {
        return weatherWidget?.let {
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
     */
    fun restoreTextWidgets(
        states: List<DashboardPersistence.TextWidgetState>,
        screenWidth: Int,
        screenHeight: Int
    ): List<TextBoxWidget> {
        Log.d(TAG, "Restoring ${states.size} text widgets")

        return states.map { state ->
            TextBoxWidget(state.x, state.y, state.width, state.height).apply {
                restoreCommonFields(this, state, screenWidth, screenHeight) {
                    // Restore content: use HTML if available, otherwise plain text
                    if (!state.html.isNullOrEmpty()) {
                        setHtmlContent(state.html)
                    } else {
                        setTextContent(state.text)
                    }
                    setFontSize(state.fontSize)
                    setTextWrap(state.isTextWrap)
                    setColumns(state.columnCount)
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

            ImageWidget(state.x, state.y, state.imagePath).apply {
                restoreCommonFields(this, state, screenWidth, screenHeight)
            }
        }
    }

    /** Restore the status bar's element visibility and common fields. It keeps its own size. */
    fun restoreStatusBarPosition(
        statusBarWidget: StatusBarWidget?,
        state: DashboardPersistence.StatusBarState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        statusBarWidget?.let { statusBar ->
            restoreCommonFields(statusBar, state, screenWidth, screenHeight) {
                statusBar.setElementVisibility(
                    showTime = state.showTime,
                    showDate = state.showDate,
                    showPhoneBattery = state.showPhoneBattery,
                    showGlassesBattery = state.showGlassesBattery
                )
            }
        }
    }

    /** Load the saved page of an already-created browser, then restore its common fields. */
    fun restoreBrowserWidgetState(
        widget: BrowserWidget,
        state: DashboardPersistence.BrowserWidgetState,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        restoreCommonFields(widget, state, screenWidth, screenHeight) {
            widget.getWebView()?.loadUrl(state.url)
        }
    }

    /** Restore the location widget's element visibility and common fields. */
    fun restoreLocationWidgetPosition(
        weatherWidget: WeatherWidget?,
        state: DashboardPersistence.LocationWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        weatherWidget?.let { loc ->
            restoreCommonFields(loc, state, screenWidth, screenHeight) {
                loc.setElementVisibility(
                    showWeather = state.showWeather,
                    showTemperature = state.showTemperature,
                    showLocation = state.showLocation,
                    showCountry = state.showCountry
                )
            }
        }
    }

    /**
     * Restore the map's common fields, then its view preferences. The projected mode is only
     * kept for a fullscreen map, so the preferences must come after the fullscreen state.
     */
    fun restoreMapWidgetPosition(
        widget: MapWidget?, state: DashboardPersistence.MapWidgetState,
        screenWidth: Int, screenHeight: Int
    ) {
        widget ?: return
        restoreCommonFields(widget, state, screenWidth, screenHeight)
        widget.restorePreferences(
            state.mode,
            state.zoom,
            state.showLocation,
            state.showCountry,
            state.poiConfig
        )
    }

    /** Restore the finance widget's tiles and common fields. */
    fun restoreFinanceWidgetPosition(
        financeWidget: FinanceWidget?,
        state: DashboardPersistence.FinanceWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        financeWidget?.let { fw ->
            restoreCommonFields(fw, state, screenWidth, screenHeight) {
                fw.restoreDashboard(state.tiles, state.navigationIndex, state.tilingSpan)
            }
        }
    }

    /** Restore the news widget's source, country, selection and font scale, and common fields. */
    fun restoreNewsWidgetPosition(
        newsWidget: NewsWidget?,
        state: DashboardPersistence.NewsWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        newsWidget?.let { nw ->
            restoreCommonFields(nw, state, screenWidth, screenHeight) {
                nw.setSourceSpec(state.sourceSpec)
                nw.setCountryCode(state.countryCode)
                nw.setSelectedIndex(state.selectedIndex)
                nw.setFontScale(state.fontScale)
            }
        }
    }

    /** Restore the notifications widget's font scales and scroll position, and common fields. */
    fun restoreNotificationsWidgetPosition(
        notificationsWidget: NotificationsWidget?,
        state: DashboardPersistence.NotificationsWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        notificationsWidget?.let { widget ->
            restoreCommonFields(widget, state, screenWidth, screenHeight) {
                widget.setWidgetFontScale(state.widgetFontScale)
                widget.setToastFontScale(state.toastFontScale)
                widget.setScrollIndex(state.scrollIndex)
            }
        }
    }

    /** Restore the subtitle widget's persisted options and common fields. */
    fun restoreSubtitleWidgetPosition(
        subtitleWidget: SubtitleWidget?,
        state: DashboardPersistence.SubtitleWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        subtitleWidget?.let { sw ->
            restoreCommonFields(sw, state, screenWidth, screenHeight) {
                sw.restoreOptions(
                    phonePlaybackEnabled = state.phonePlaybackEnabled,
                    microphoneEnabled = state.microphoneEnabled,
                    translationEnabled = state.translationEnabled
                )
            }
        }
    }
}