package com.everyday.everyday_glasses

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import java.io.File

/**
 * Helper class for saving and restoring widget states.
 *
 * This class extracts all persistence-related logic from WidgetContainer,
 * handling the conversion between widgets and their persistent state DTOs.
 *
 * Responsibilities:
 * - Extract state from widgets into DTOs
 * - Recreate widgets from DTOs
 * - Validate bounds against screen size
 */
class WidgetPersistenceHelper(private val context: Context) {

    companion object {
        private const val TAG = "WidgetPersistenceHelper"
    }

    // Callbacks for widget events (set by WidgetContainer)
    var onWidgetFocusChanged: ((widget: BaseWidget?, focused: Boolean) -> Unit)? = null
    var onWidgetDeleted: ((widget: TextBoxWidget) -> Unit)? = null
    var onBrowserWidgetClosed: ((widget: BrowserWidget) -> Unit)? = null
    var onWidgetStateChanged: (() -> Unit)? = null
    var onTextWidgetFullscreenToggled: ((widget: TextBoxWidget, isFullscreen: Boolean) -> Unit)? = null
    var onTextWidgetMinimizeToggled: ((widget: TextBoxWidget, isMinimized: Boolean) -> Unit)? = null
    var onBrowserWidgetFullscreenToggled: ((widget: BrowserWidget, isFullscreen: Boolean) -> Unit)? = null
    var onMinimizeToggled: ((widget: BrowserWidget, isMinimized: Boolean) -> Unit)? = null
    var onBrowserKeyboardRequest: ((show: Boolean) -> Unit)? = null

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
     * Restore isPinned and minimize state on a widget from a [CommonWidgetFields] state.
     *
     * @param widget The widget to restore state on
     * @param state  The persisted common fields
     * @param adjustedX The screen-bounds-adjusted X position for the widget
     * @param adjustedY The screen-bounds-adjusted Y position for the widget
     */
    private fun restoreCommonState(
        widget: BaseWidget,
        state: WidgetPersistence.CommonWidgetFields,
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
    fun getTextWidgetState(widget: TextBoxWidget): WidgetPersistence.TextWidgetState {
        val c = extractCommonFields(widget)
        return WidgetPersistence.TextWidgetState(
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
     * Get all text widget states for persistence.
     * Includes HTML content for rich text formatting, minimize state, and pin state.
     */
    fun getTextWidgetStates(widgets: List<TextBoxWidget>): List<WidgetPersistence.TextWidgetState> {
        return widgets.map(::getTextWidgetState)
    }

    /**
     * Get all image widget states for persistence.
     */
    fun getImageWidgetStates(widgets: List<ImageWidget>): List<WidgetPersistence.ImageWidgetState> {
        return widgets.mapNotNull { widget ->
            val imagePath = widget.getImagePath()
            if (imagePath.isBlank()) {
                null
            } else {
                val c = extractCommonFields(widget)
                WidgetPersistence.ImageWidgetState(
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

    /**
     * Get status bar state for persistence.
     * Includes position, minimize state, and pin state.
     */
    fun getStatusBarState(statusBarWidget: StatusBarWidget?): WidgetPersistence.StatusBarState? {
        return statusBarWidget?.let {
            val c = extractCommonFields(it)
            WidgetPersistence.StatusBarState(
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
    fun getBrowserWidgetState(widget: BrowserWidget): WidgetPersistence.BrowserWidgetState? {
        if (!widget.shouldPersist) {
            return null
        }

        val c = extractCommonFields(widget)
        return WidgetPersistence.BrowserWidgetState(
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
     * Get all browser widget states for persistence.
     */
    fun getBrowserWidgetStates(browserWidgets: List<BrowserWidget>): List<WidgetPersistence.BrowserWidgetState> {
        return browserWidgets
            .mapNotNull(::getBrowserWidgetState)
    }

    /**
     * Get location widget state for persistence.
     */
    fun getLocationWidgetState(locationWidget: LocationWidget?): WidgetPersistence.LocationWidgetState? {
        return locationWidget?.let {
            val c = extractCommonFields(it)
            WidgetPersistence.LocationWidgetState(
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

    /**
     * Get calendar widget state for persistence.
     */
    fun getCalendarWidgetState(calendarWidget: CalendarWidget?): WidgetPersistence.CalendarWidgetState? {
        return calendarWidget?.let {
            val c = extractCommonFields(it)
            WidgetPersistence.CalendarWidgetState(
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
    fun getMirrorWidgetState(screenMirrorWidget: ScreenMirrorWidget?): WidgetPersistence.MirrorWidgetState? {
        return screenMirrorWidget?.let {
            val c = extractCommonFields(it)
            WidgetPersistence.MirrorWidgetState(
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
    fun getNewsWidgetState(newsWidget: NewsWidget?): WidgetPersistence.NewsWidgetState? {
        return newsWidget?.let {
            val c = extractCommonFields(it)
            WidgetPersistence.NewsWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                countryCode = it.getCountryCode(),
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

    /**
     * Get speedometer widget state for persistence.
     */
    fun getSpeedometerWidgetState(speedometerWidget: SpeedometerWidget?): WidgetPersistence.SpeedometerWidgetState? {
        return speedometerWidget?.let {
            val c = extractCommonFields(it)
            WidgetPersistence.SpeedometerWidgetState(
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
     * Get finance widget state for persistence.
     */
    fun getFinanceWidgetState(financeWidget: FinanceWidget?): WidgetPersistence.FinanceWidgetState? {
        return financeWidget?.let {
            val c = extractCommonFields(it)
            WidgetPersistence.FinanceWidgetState(
                x = c.x, y = c.y, width = c.width, height = c.height,
                selectedSymbol = it.currentSymbol,
                selectedRange = it.currentRange.range,
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
        states: List<WidgetPersistence.TextWidgetState>,
        screenWidth: Int,
        screenHeight: Int
    ): List<TextBoxWidget> {
        Log.d(TAG, "Restoring ${states.size} text widgets")

        return states.map { state ->
            // Validate bounds against current screen size
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(80f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(40f, screenHeight.toFloat())

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

                onFocusChanged = { focused ->
                    onWidgetFocusChanged?.invoke(this, focused)
                }
                onCloseRequested = {
                    onWidgetDeleted?.invoke(this)
                }
                onStateChanged = { _ ->
                    onWidgetStateChanged?.invoke()
                }
                onFullscreenToggled = { isFullscreen ->
                    onTextWidgetFullscreenToggled?.invoke(this, isFullscreen)
                }
                onMinimizeToggled = { isMinimized ->
                    onTextWidgetMinimizeToggled?.invoke(this, isMinimized)
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
        states: List<WidgetPersistence.ImageWidgetState>,
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

            val adjustedWidth = state.width.coerceIn(80f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(80f, screenHeight.toFloat())
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
        state: WidgetPersistence.StatusBarState,
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

    /**
     * Restore browser widgets from saved states.
     *
     * @param states The list of saved browser widget states
     * @param rootView The root ViewGroup to add WebViews to
     * @param containerView The WidgetContainer view (for z-ordering)
     * @param screenWidth Current screen width
     * @param screenHeight Current screen height
     * @return List of restored BrowserWidget instances
     */
    fun restoreBrowserWidgets(
        states: List<WidgetPersistence.BrowserWidgetState>,
        rootView: ViewGroup,
        containerView: android.view.View,
        screenWidth: Int,
        screenHeight: Int
    ): List<BrowserWidget> {
        Log.d(TAG, "Restoring ${states.size} browser widgets")

        return states.map { state ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(200f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(150f, screenHeight.toFloat())

            BrowserWidget(
                context,
                rootView,
                adjustedX,
                adjustedY,
                adjustedWidth,
                adjustedHeight
            ).apply {
                getWebView()?.loadUrl(state.url)

                restoreCommonState(this, state, adjustedX, adjustedY)

                onFocusChanged = { focused ->
                    onWidgetFocusChanged?.invoke(this, focused)
                }
                onCloseRequested = {
                    onBrowserWidgetClosed?.invoke(this)
                }
                onStateChanged = { _ ->
                    onWidgetStateChanged?.invoke()
                }
                onFullscreenToggled = { isFullscreen ->
                    onBrowserWidgetFullscreenToggled?.invoke(this, isFullscreen)
                }
                onMinimizeToggled = { isMinimized ->
                    this@WidgetPersistenceHelper.onMinimizeToggled?.invoke(this, isMinimized)
                }
                onRequestKeyboard = { show ->
                    onBrowserKeyboardRequest?.invoke(show)
                }

                // Fix z-order
                val containerIndex = rootView.indexOfChild(containerView)
                val insertIndex = if (containerIndex >= 0) containerIndex else 0
                getWebView()?.let { webView ->
                    if (webView.parent == rootView) {
                        rootView.removeView(webView)
                    }
                    rootView.addView(webView, insertIndex)
                }
            }
        }
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
        state: WidgetPersistence.LocationWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        locationWidget?.let { loc ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(150f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(60f, screenHeight.toFloat())

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

    /**
     * Restore calendar widget position from saved state.
     */
    fun restoreCalendarWidgetPosition(
        calendarWidget: CalendarWidget?,
        state: WidgetPersistence.CalendarWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        calendarWidget?.let { widget ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(180f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(90f, screenHeight.toFloat())

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
        state: WidgetPersistence.MirrorWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        screenMirrorWidget?.let { mirror ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(120f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(80f, screenHeight.toFloat())

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
        state: WidgetPersistence.FinanceWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        financeWidget?.let { fw ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(200f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(120f, screenHeight.toFloat())

            fw.setPosition(adjustedX, adjustedY)
            fw.widgetWidth = adjustedWidth
            fw.widgetHeight = adjustedHeight

            // Restore selected symbol and range
            fw.setSymbolAndRange(state.selectedSymbol, state.selectedRange)

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
        state: WidgetPersistence.NewsWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        newsWidget?.let { nw ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(220f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(140f, screenHeight.toFloat())

            nw.setPosition(adjustedX, adjustedY)
            nw.widgetWidth = adjustedWidth
            nw.widgetHeight = adjustedHeight
            nw.setCountryCode(state.countryCode)
            nw.setSelectedIndex(state.selectedIndex)
            nw.setFontScale(state.fontScale)

            restoreCommonState(nw, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored news widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight} country=${state.countryCode} index=${state.selectedIndex} fontScale=${state.fontScale}")
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
        state: WidgetPersistence.SpeedometerWidgetState,
        screenWidth: Int,
        screenHeight: Int
    ) {
        speedometerWidget?.let { sw ->
            val adjustedX = state.x.coerceIn(0f, (screenWidth - state.width).coerceAtLeast(0f))
            val adjustedY = state.y.coerceIn(0f, (screenHeight - state.height).coerceAtLeast(0f))
            val adjustedWidth = state.width.coerceIn(160f, screenWidth.toFloat())
            val adjustedHeight = state.height.coerceIn(80f, screenHeight.toFloat())

            sw.setPosition(adjustedX, adjustedY)
            sw.widgetWidth = adjustedWidth
            sw.widgetHeight = adjustedHeight

            restoreCommonState(sw, state, adjustedX, adjustedY)

            Log.d(TAG, "Restored speedometer widget: ($adjustedX, $adjustedY) ${adjustedWidth}x${adjustedHeight}")
        }
    }
}
