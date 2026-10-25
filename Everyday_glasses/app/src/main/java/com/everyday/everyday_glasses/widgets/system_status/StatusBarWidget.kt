package com.everyday.everyday_glasses.widgets.system_status

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.Log
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets._common.WidgetInteractionState
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseCommonFields
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import androidx.core.graphics.toColorInt
import com.everyday.everyday_glasses.runtime.StatusSnapshot
import org.json.JSONObject

/**
 * A movable status bar widget that displays:
 * - Line 1: Time and date (HH:mm DD MMM YYYY format)
 * - Line 2: Phone battery and glasses battery
 *
 * The text scales automatically to fit within the widget bounds when resized.
 * Can be moved by clicking on its edge when cursor hovers over it.
 * Can be toggled to fullscreen mode.
 * 
 * Refactored to extend BaseWidget for common functionality.
 */
class StatusBarWidget(
    x: Float,
    y: Float,
    widgetWidth: Float,
    widgetHeight: Float
) : BaseWidget(WidgetTypeId.STATUS, x, y, widgetWidth, widgetHeight) {
    companion object {
        private const val TAG = "StatusBarWidget"
        
        private const val PADDING = 12f
        private const val TOGGLE_MENU_WIDTH = 220f
        private const val TOGGLE_MENU_PADDING = 10f
        private const val TOGGLE_MENU_ROW_HEIGHT = 32f
    }
    
    enum class State {
        IDLE, HOVER_CONTENT, HOVER_BORDER, MOVING
    }
    
    enum class HitArea {
        NONE, CONTENT, BORDER, FULLSCREEN_BUTTON, PIN_BUTTON, MINIMIZE_BUTTON, RESIZE_HANDLE
    }

    private enum class StatusElement {
        TIME,
        DATE,
        PHONE_BATTERY,
        GLASSES_BATTERY
    }
    
    var state = State.IDLE
    
    // Label for minimized state
    override val minimizeLabel: String = "S"
    
    // Data to display
    var isPhoneConnected: Boolean = false
    var phoneBattery: Int = -1  // -1 means not connected
    var glassesBattery: Int = -1  // -1 means unknown
    var timeString: String = "--:--"
    var dateString: String = "-- --- ----"

    /** Shows the phone, battery and clock data of [state]. Returns true when anything changed. */
    fun show(state: StatusSnapshot): Boolean {
        val changed = isPhoneConnected != state.isPhoneConnected ||
                phoneBattery != state.phoneBatteryPercent ||
                glassesBattery != state.glassesBatteryPercent ||
                timeString != state.time ||
                dateString != state.date
        if (!changed) return false
        isPhoneConnected = state.isPhoneConnected
        phoneBattery = state.phoneBatteryPercent
        glassesBattery = state.glassesBatteryPercent
        timeString = state.time
        dateString = state.date
        return true
    }

    private var showTime = true
    private var showDate = true
    private var showPhoneBattery = true
    private var showGlassesBattery = true
    private var showToggleMenu = false
    private var hoveredToggleElement: StatusElement? = null
    private val toggleMenuBounds = RectF()
    private val toggleRowBounds = Array(StatusElement.entries.size) { RectF() }

    // onFullscreenToggled provided by BaseWidget
    
    // Paints
    private val transparentBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#22000000".toColorInt()
    }
    
    // hoverBorderPaint is provided by BaseWidget (#6666AA)
    // If we want the original #666688 color, we can override or just use BaseWidget's.
    // Using BaseWidget's for consistency.
    
    // Base text size (will be scaled dynamically)
    private var currentTextSize = 22f
    
    // Use same size for all text
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    private val disconnectedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#666666".toColorInt()
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    private val batteryGoodPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#4CAF50".toColorInt()
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    private val batteryLowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FF9800".toColorInt()
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    private val batteryCriticalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F44336".toColorInt()
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    // Cached font size calculation
    private var cachedFontSize = 22f
    private var lastWidth = 0f
    private var lastHeight = 0f
    
    // Strikethrough paint for disconnected phone
    private val strikethroughPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#888888".toColorInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val toggleMenuBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#DD111827".toColorInt()
        style = Paint.Style.FILL
    }

    private val toggleMenuBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#667FA1C4".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val toggleRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#334A6178".toColorInt()
        style = Paint.Style.FILL
    }

    private val toggleRowHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#5578A7D5".toColorInt()
        style = Paint.Style.FILL
    }

    private val toggleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.LEFT
    }

    private val toggleValueOnPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#8BC34A".toColorInt()
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val toggleValueOffPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#B0BEC5".toColorInt()
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }
    
    init {
        // Explicitly initialize with subclass bounds update
        updateBounds()
    }
    
    private fun updateBounds() {
        super.updateBaseBounds()
        
        // Override contentBounds padding to match original logic
        contentBounds.set(
            x + PADDING,
            y + PADDING,
            x + widgetWidth - PADDING,
            y + widgetHeight - PADDING
        )
        updateToggleMenuBounds()
    }
    
    // Do NOT override updateBaseBounds to avoid initialization loop
    // But since we use manual updateBounds(), we need to call it when properties change?
    // BaseWidget properties now trigger updateBaseBounds().
    // So we MUST override updateBaseBounds().
    
    override fun updateBaseBounds() {
        super.updateBaseBounds()
        
        // Update content bounds with our specific padding
        contentBounds.set(
            x + PADDING,
            y + PADDING,
            x + widgetWidth - PADDING,
            y + widgetHeight - PADDING
        )
        updateToggleMenuBounds()
    }
    
    override fun enterFullscreen() {
        super.enterFullscreen()
        showToggleMenu = false
        updateBounds()
    }

    override fun exitFullscreen() {
        super.exitFullscreen()
        showToggleMenu = false
        updateBounds()
    }
    
    override fun setFullscreenBounds(screenWidth: Float, screenHeight: Float) {
        super.setFullscreenBounds(screenWidth, screenHeight)
        showToggleMenu = false
        updateBounds()
    }

    fun setElementVisibility(
        showTime: Boolean,
        showDate: Boolean,
        showPhoneBattery: Boolean,
        showGlassesBattery: Boolean
    ) {
        this.showTime = showTime
        this.showDate = showDate
        this.showPhoneBattery = showPhoneBattery
        this.showGlassesBattery = showGlassesBattery
    }

    fun isTimeVisible(): Boolean = showTime
    fun isDateVisible(): Boolean = showDate
    fun isPhoneBatteryVisible(): Boolean = showPhoneBattery
    fun isGlassesBatteryVisible(): Boolean = showGlassesBattery

    override fun contentSignature(): List<Any?> = listOf(isTimeVisible(), isDateVisible(), isPhoneBatteryVisible(), isGlassesBatteryVisible())
    override fun onModalTap(px: Float, py: Float): Boolean = handleToggleMenuTapOrDismiss(px, py)
    fun handleToggleMenuTapOrDismiss(px: Float, py: Float): Boolean {
        if (!showToggleMenu) return false

        val tappedElement = StatusElement.entries.firstOrNull { toggleRowBounds[it.ordinal].contains(px, py) }
        if (tappedElement != null) {
            toggleElement(tappedElement)
        } else {
            showToggleMenu = false
            hoveredToggleElement = null
        }
        return true
    }

    override fun onDoubleTap(px: Float, py: Float): Boolean {
        if (isMinimized || !containsPoint(px, py)) return false
        showToggleMenu = !showToggleMenu
        hoveredToggleElement = null
        return true
    }
    
    /**
     * Calculate optimal font size to fit the content within the widget bounds.
     * Layout: Large time on top (3x), smaller date (1x), batteries on bottom (1x)
     */
    private fun calculateOptimalFontSize(): Float {
        val availableWidth = widgetWidth - PADDING * 2
        val availableHeight = widgetHeight - PADDING * 2

        // Layout: time takes ~3 parts of height, date ~1 part, batteries ~1 part, plus spacing
        // Total ratio: 3 (time) + 0.3 (spacing) + 1 (date) + 0.3 (spacing) + 1 (batteries) = 5.6
        val smallFontSize = availableHeight / 5.6f
        val largeFontSize = smallFontSize * 3f

        // Measure time with large font
        statusPaint.textSize = largeFontSize
        val timeWidth = statusPaint.measureText(timeString)

        // Measure date and battery lines with small font
        statusPaint.textSize = smallFontSize
        val dateWidth = statusPaint.measureText(dateString)
        val batteryLine = "\uD83D\uDCF1${if (phoneBattery >= 0) "$phoneBattery%" else "-"}  \uD83D\uDD76\uFE0F${if (glassesBattery >= 0) "$glassesBattery%" else "-"}"
        val batteryWidth = statusPaint.measureText(batteryLine)

        val maxTextWidth = maxOf(timeWidth, dateWidth, batteryWidth)

        // Scale down if too wide (return the small font size, large is derived from it)
        var resultSmallFont = smallFontSize
        if (maxTextWidth > availableWidth) {
            resultSmallFont *= (availableWidth / maxTextWidth)
        }

        return resultSmallFont.coerceIn(6f, 32f)
    }
    
    /**
     * Update all paint text sizes to the given size.
     */
    private fun updateAllPaintSizes(size: Float) {
        currentTextSize = size
        statusPaint.textSize = size
        disconnectedPaint.textSize = size
        batteryGoodPaint.textSize = size
        batteryLowPaint.textSize = size
        batteryCriticalPaint.textSize = size
    }
    
    /**
     * Update cached font size if dimensions changed.
     */
    private fun updateFontSizeIfNeeded() {
        if (widgetWidth != lastWidth || widgetHeight != lastHeight) {
            cachedFontSize = calculateOptimalFontSize()
            lastWidth = widgetWidth
            lastHeight = widgetHeight
        }
    }
    
    override fun isHovering(): Boolean {
        return state == State.HOVER_CONTENT ||
               state == State.HOVER_BORDER ||
               state == State.MOVING ||
               baseState == BaseState.MOVING ||
               baseState == BaseState.RESIZING ||
               baseState == BaseState.HOVER_RESIZE
    }
    
    // Use unified border hover from BaseWidget - no override needed
    override fun isBorderHit(px: Float, py: Float): Boolean = hitTest(px, py) == HitArea.BORDER
    fun hitTest(px: Float, py: Float): HitArea {
        // Check base widget buttons first (close button is handled by baseHitTest)
        val baseResult = baseHitTest(px, py)
        when (baseResult) {
            BaseHitArea.CLOSE_BUTTON -> return HitArea.NONE  // Handled via onCloseRequested callback
            BaseHitArea.FULLSCREEN_BUTTON -> return HitArea.FULLSCREEN_BUTTON
            BaseHitArea.MINIMIZE_BUTTON -> return HitArea.MINIMIZE_BUTTON
            BaseHitArea.PIN_BUTTON -> return HitArea.PIN_BUTTON
            BaseHitArea.RESIZE_HANDLE -> return HitArea.RESIZE_HANDLE
            else -> {}
        }

        if (contentBounds.contains(px, py)) return HitArea.CONTENT

        val expandedBounds = RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) return HitArea.BORDER

        return HitArea.NONE
    }

    override fun dragHitTest(px: Float, py: Float): DragHit = when (hitTest(px, py)) {
        HitArea.BORDER, HitArea.CONTENT -> DragHit.DRAG
        HitArea.RESIZE_HANDLE -> DragHit.RESIZE
        else -> DragHit.NONE
    }
    
    override fun updateHover(px: Float, py: Float) {
        if (baseState == BaseState.MOVING || baseState == BaseState.RESIZING) return

        // Use unified hover state from BaseWidget
        val baseResult = updateHoverState(px, py)

        val newState = WidgetInteractionState(baseResult).toChromeLocal(
            idle = State.IDLE,
            content = State.HOVER_CONTENT,
            border = State.HOVER_BORDER,
            moving = State.MOVING
        )

        if (newState != state) {
            Log.d(TAG, "State changed: $state -> $newState")
            state = newState
        }

        hoveredToggleElement = if (showToggleMenu) {
            StatusElement.entries.firstOrNull { toggleRowBounds[it.ordinal].contains(px, py) }
        } else {
            null
        }
    }

    override fun containsPoint(px: Float, py: Float): Boolean {
        if (fullscreenButtonBounds.contains(px, py)) return true
        if (minimizeButtonBounds.contains(px, py)) return true
        if (pinButtonBounds.contains(px, py)) return true

        if (!isFullscreen && !isMinimized && resizeHandleBounds.contains(px, py)) return true
        
        val expandedBounds = RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        return expandedBounds.contains(px, py)
    }
    
    private fun getBatteryPaint(level: Int): TextPaint {
        return when {
            level < 0 -> disconnectedPaint
            level <= 15 -> batteryCriticalPaint
            level <= 30 -> batteryLowPaint
            else -> batteryGoodPaint
        }
    }
    
    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // Background - Use local transparent background paint instead of BaseWidget's black
        canvas.drawRoundRect(widgetBounds, 12f, 12f, transparentBackgroundPaint)

        if (shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, 12f, 12f, hoverBorderPaint)
        }

        // Update font size based on current dimensions
        updateFontSizeIfNeeded()

        val centerX = widgetBounds.centerX()
        val smallFontSize = cachedFontSize
        val largeFontSize = smallFontSize * 3f
        val lineSpacing = smallFontSize * 0.3f

        // Calculate total height: large time + spacing + date + spacing + batteries
        val totalHeight = largeFontSize + (lineSpacing * 2) + (smallFontSize * 2)

        // Calculate Y positions (vertically centered)
        val startY = widgetBounds.centerY() - totalHeight / 2
        
        val timeLineY = startY + largeFontSize
        val dateLineY = timeLineY + lineSpacing + smallFontSize
        val batteryLineY = dateLineY + lineSpacing + smallFontSize

        // ===== LINE 1: Large Time =====
        updateAllPaintSizes(largeFontSize)
        val timeWidth = statusPaint.measureText(timeString)
        if (showTime) {
            canvas.drawText(timeString, centerX - timeWidth / 2, timeLineY, statusPaint)
        }

        // ===== LINES 2 & 3: Date and Batteries (smaller) =====
        updateAllPaintSizes(smallFontSize)

        // LINE 2: Date
        val dateWidth = statusPaint.measureText(dateString)
        if (showDate) {
            canvas.drawText(dateString, centerX - dateWidth / 2, dateLineY, statusPaint)
        }

        // LINE 3: Batteries
        val spacing = statusPaint.measureText("  ")

        // Phone status
        val phoneEmoji = "\uD83D\uDCF1"
        val phoneBatteryText = if (isPhoneConnected && phoneBattery >= 0) "$phoneBattery%" else "-"
        val phonePaint = if (isPhoneConnected) statusPaint else disconnectedPaint
        val phoneBatteryPaint = if (isPhoneConnected) getBatteryPaint(phoneBattery) else disconnectedPaint

        // Glasses status
        val glassesEmoji = "\uD83D\uDD76\uFE0F"
        val glassesBatteryText = if (glassesBattery >= 0) "$glassesBattery%" else "-"
        val glassesBatteryPaint = getBatteryPaint(glassesBattery)

        // Calculate widths
        val phoneEmojiWidth = phonePaint.measureText(phoneEmoji)
        val phoneBatteryWidth = phoneBatteryPaint.measureText(phoneBatteryText)
        val glassesEmojiWidth = statusPaint.measureText(glassesEmoji)
        val glassesBatteryWidth = glassesBatteryPaint.measureText(glassesBatteryText)

        // Battery line: phone icon + phone percent + glasses icon + glasses percent
        val batteryLineWidth = phoneEmojiWidth + phoneBatteryWidth + spacing +
                glassesEmojiWidth + glassesBatteryWidth
        var drawX = centerX - batteryLineWidth / 2

        // Draw phone emoji
        if (showPhoneBattery) {
            canvas.drawText(phoneEmoji, drawX, batteryLineY, phonePaint)
        }

        // Draw strikethrough if disconnected
        if (showPhoneBattery && !isPhoneConnected) {
            val emojiTop = batteryLineY - statusPaint.textSize * 0.8f
            val emojiBottom = batteryLineY + statusPaint.textSize * 0.1f
            canvas.drawLine(
                drawX - 2f,
                emojiBottom,
                drawX + phoneEmojiWidth + 2f,
                emojiTop,
                strikethroughPaint
            )
        }
        drawX += phoneEmojiWidth

        // Draw phone battery
        if (showPhoneBattery) {
            canvas.drawText(phoneBatteryText, drawX, batteryLineY, phoneBatteryPaint)
        }
        drawX += phoneBatteryWidth + spacing

        // Draw glasses emoji
        if (showGlassesBattery) {
            canvas.drawText(glassesEmoji, drawX, batteryLineY, statusPaint)
        }
        drawX += glassesEmojiWidth

        // Draw glasses battery
        if (showGlassesBattery) {
            canvas.drawText(glassesBatteryText, drawX, batteryLineY, glassesBatteryPaint)
        }

        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
        }

        drawResizeHandle(canvas)

        if (showToggleMenu) {
            drawToggleMenu(canvas)
        }
    }

    private fun updateToggleMenuBounds() {
        val menuWidth = TOGGLE_MENU_WIDTH.coerceAtMost((widgetWidth - PADDING * 2).coerceAtLeast(120f))
        val menuHeight = TOGGLE_MENU_PADDING * 2 + TOGGLE_MENU_ROW_HEIGHT * StatusElement.entries.size
        val left = widgetBounds.centerX() - menuWidth / 2f
        val top = contentBounds.top + 8f
        toggleMenuBounds.set(left, top, left + menuWidth, top + menuHeight)

        StatusElement.entries.forEachIndexed { index, _ ->
            val rowTop = toggleMenuBounds.top + TOGGLE_MENU_PADDING + index * TOGGLE_MENU_ROW_HEIGHT
            toggleRowBounds[index].set(
                toggleMenuBounds.left + TOGGLE_MENU_PADDING,
                rowTop,
                toggleMenuBounds.right - TOGGLE_MENU_PADDING,
                rowTop + TOGGLE_MENU_ROW_HEIGHT - 4f
            )
        }
    }

    private fun toggleElement(element: StatusElement) {
        when (element) {
            StatusElement.TIME -> showTime = !showTime
            StatusElement.DATE -> showDate = !showDate
            StatusElement.PHONE_BATTERY -> showPhoneBattery = !showPhoneBattery
            StatusElement.GLASSES_BATTERY -> showGlassesBattery = !showGlassesBattery
        }
    }

    private fun drawToggleMenu(canvas: Canvas) {
        canvas.drawRoundRect(toggleMenuBounds, 12f, 12f, toggleMenuBackgroundPaint)
        canvas.drawRoundRect(toggleMenuBounds, 12f, 12f, toggleMenuBorderPaint)

        StatusElement.entries.forEachIndexed { index, element ->
            val bounds = toggleRowBounds[index]
            val rowPaint = if (hoveredToggleElement == element) toggleRowHoverPaint else toggleRowPaint
            canvas.drawRoundRect(bounds, 8f, 8f, rowPaint)

            val labelY = bounds.centerY() - (toggleTextPaint.descent() + toggleTextPaint.ascent()) / 2f
            canvas.drawText(toggleLabel(element), bounds.left + 10f, labelY, toggleTextPaint)

            val enabled = when (element) {
                StatusElement.TIME -> showTime
                StatusElement.DATE -> showDate
                StatusElement.PHONE_BATTERY -> showPhoneBattery
                StatusElement.GLASSES_BATTERY -> showGlassesBattery
            }
            val valuePaint = if (enabled) toggleValueOnPaint else toggleValueOffPaint
            canvas.drawText(if (enabled) "On" else "Off", bounds.right - 10f, labelY, valuePaint)
        }
    }

    private fun toggleLabel(element: StatusElement): String = when (element) {
        StatusElement.TIME -> "Time"
        StatusElement.DATE -> "Date"
        StatusElement.PHONE_BATTERY -> "Phone battery"
        StatusElement.GLASSES_BATTERY -> "Glasses battery"
    }
}

/** Puts the status bar on the dashboard and feeds it the phone, battery and clock data. */
class StatusBarPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val current: () -> StatusBarWidget? = { services.singleton(WidgetTypeId.STATUS) as? StatusBarWidget }
    // The last data received, so that a status bar opened later shows it right away.
    private val latest: StatusSnapshot get() = services.appData.latest(AppChannels.status) ?: StatusSnapshot()

    init {
        services.appData.subscribe(AppChannels.status) { state ->
            if (current()?.show(state) == true) services.notifyTransientChange()
        }
    }

    override val lifecycle = WidgetLifecycleRegistration.singleton(
        StatusBarWidget::class, StatusBarState.Codec,
        current = current,
        create = ::create,
        restore = ::restore,
        capture = ::capture,
    )

    private fun create(): StatusBarWidget = StatusBarWidget(
        (services.viewportWidth - WIDTH) / 2f,
        services.viewportHeight - HEIGHT - services.viewportHeight * 0.15f,
        WIDTH,
        HEIGHT
    ).apply { show(latest) }

    private fun restore(state: StatusBarState): StatusBarWidget {
        val widget = StatusBarWidget(state.x, state.y, WIDTH, HEIGHT).apply { show(latest) }
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight) {
            widget.setElementVisibility(
                showTime = state.showTime,
                showDate = state.showDate,
                showPhoneBattery = state.showPhoneBattery,
                showGlassesBattery = state.showGlassesBattery
            )
        }
        return widget
    }

    private fun capture(widget: StatusBarWidget): StatusBarState {
        val c = services.captureRestore.extractCommonFields(widget)
        return StatusBarState(
            x = c.x, y = c.y,
            showTime = widget.isTimeVisible(),
            showDate = widget.isDateVisible(),
            showPhoneBattery = widget.isPhoneBatteryVisible(),
            showGlassesBattery = widget.isGlassesBatteryVisible(),
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    private companion object {
        const val WIDTH = 220f
        const val HEIGHT = 110f
    }
}

/** What a saved dashboard keeps of the status bar. It has no size: the status bar keeps its own. */
data class StatusBarState(
    override val x: Float,
    override val y: Float,
    val showTime: Boolean = true,
    val showDate: Boolean = true,
    val showPhoneBattery: Boolean = true,
    val showGlassesBattery: Boolean = true,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.CommonWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<StatusBarState>(WidgetTypeId.STATUS, StatusBarState::class) {
        override fun toJson(state: StatusBarState): JSONObject = JSONObject().apply {
            putCommonFields(state, includeSize = false)
            put("showTime", state.showTime)
            put("showDate", state.showDate)
            put("showPhoneBattery", state.showPhoneBattery)
            put("showGlassesBattery", state.showGlassesBattery)
        }

        override fun fromJson(payload: JSONObject): StatusBarState? {
            return try {
                val c = parseCommonFields(payload)
                if (c.x < 0 || c.y < 0) return null

                StatusBarState(
                    x = c.x, y = c.y,
                    showTime = payload.optBoolean("showTime", true),
                    showDate = payload.optBoolean("showDate", true),
                    showPhoneBattery = payload.optBoolean("showPhoneBattery", true),
                    showGlassesBattery = payload.optBoolean("showGlassesBattery", true),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
