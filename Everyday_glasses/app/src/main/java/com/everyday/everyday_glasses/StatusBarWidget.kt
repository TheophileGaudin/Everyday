package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.Log

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
    private val context: Context,
    x: Float,
    y: Float,
    widgetWidth: Float,
    widgetHeight: Float
) : BaseWidget(x, y, widgetWidth, widgetHeight) {
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
    private var showTime = true
    private var showDate = true
    private var showPhoneBattery = true
    private var showGlassesBattery = true
    private var showToggleMenu = false
    private var hoveredToggleElement: StatusElement? = null
    private val toggleMenuBounds = RectF()
    private val toggleRowBounds = Array(StatusElement.values().size) { RectF() }
    
    var onStateChanged: ((State) -> Unit)? = null
    // onFullscreenToggled provided by BaseWidget
    
    // Paints
    private val transparentBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22000000")
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
        color = Color.parseColor("#666666")
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    private val batteryGoodPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    private val batteryLowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    private val batteryCriticalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        textSize = currentTextSize
        textAlign = Paint.Align.LEFT
    }
    
    // Cached font size calculation
    private var cachedFontSize = 22f
    private var lastWidth = 0f
    private var lastHeight = 0f
    
    // Strikethrough paint for disconnected phone
    private val strikethroughPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val toggleMenuBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD111827")
        style = Paint.Style.FILL
    }

    private val toggleMenuBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#667FA1C4")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val toggleRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334A6178")
        style = Paint.Style.FILL
    }

    private val toggleRowHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5578A7D5")
        style = Paint.Style.FILL
    }

    private val toggleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.LEFT
    }

    private val toggleValueOnPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8BC34A")
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val toggleValueOffPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
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

    fun handleToggleMenuTapOrDismiss(px: Float, py: Float): Boolean {
        if (!showToggleMenu) return false

        val tappedElement = StatusElement.values().firstOrNull { toggleRowBounds[it.ordinal].contains(px, py) }
        if (tappedElement != null) {
            toggleElement(tappedElement)
        } else {
            showToggleMenu = false
            hoveredToggleElement = null
        }
        return true
    }

    fun onDoubleTap(px: Float, py: Float): Boolean {
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
               baseState == BaseState.HOVER_RESIZE ||
               baseState == BaseState.RESIZING
    }
    
    // Use unified border hover from BaseWidget - no override needed
    
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
            widgetBounds.left - BaseWidget.BORDER_HIT_AREA,
            widgetBounds.top - BaseWidget.BORDER_HIT_AREA,
            widgetBounds.right + BaseWidget.BORDER_HIT_AREA,
            widgetBounds.bottom + BaseWidget.BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) return HitArea.BORDER

        return HitArea.NONE
    }
    
    override fun updateHover(px: Float, py: Float) {
        if (state == State.MOVING) return

        // Use unified hover state from BaseWidget
        val baseResult = updateHoverState(px, py)

        // Map base state to local state
        val newState = when (baseResult) {
            BaseState.HOVER_RESIZE, BaseState.RESIZING -> State.HOVER_BORDER
            BaseState.HOVER_BORDER -> State.HOVER_BORDER
            BaseState.HOVER_CONTENT -> State.HOVER_CONTENT
            BaseState.MOVING -> State.MOVING
            else -> State.IDLE
        }

        if (newState != state) {
            Log.d(TAG, "State changed: $state -> $newState")
            state = newState

            // Sync with baseState
            baseState = when (state) {
                State.MOVING -> BaseState.MOVING
                State.HOVER_BORDER -> BaseState.HOVER_BORDER
                State.HOVER_CONTENT -> BaseState.HOVER_CONTENT
                else -> BaseState.IDLE
            }
        }

        hoveredToggleElement = if (showToggleMenu) {
            StatusElement.values().firstOrNull { toggleRowBounds[it.ordinal].contains(px, py) }
        } else {
            null
        }
    }
    
    fun onTap(px: Float, py: Float): Boolean {
        if (handleToggleMenuTapOrDismiss(px, py)) {
            return true
        }

        // Check close button first (handled by base class)
        if (closeButtonBounds.contains(px, py)) {
            onCloseRequested?.invoke()
            return true
        }

        val hitArea = hitTest(px, py)
        Log.d(TAG, "onTap at ($px, $py) -> hitArea=$hitArea, currentState=$state")

        return when (hitArea) {
            HitArea.RESIZE_HANDLE -> {
                // Resize started via drag, not tap
                true
            }
            HitArea.FULLSCREEN_BUTTON -> {
                Log.d(TAG, "Fullscreen button tapped")
                toggleFullscreen()
                true
            }
            HitArea.MINIMIZE_BUTTON -> {
                toggleMinimize()
                true
            }
            HitArea.PIN_BUTTON -> {
                isPinned = !isPinned
                Log.d(TAG, "Pin toggled: $isPinned")
                true
            }
            HitArea.BORDER -> {
                if (!isFullscreen) {
                    Log.d(TAG, "Border tapped - starting move")
                    state = State.MOVING
                    baseState = BaseState.MOVING
                    onStateChanged?.invoke(state)
                }
                true
            }
            HitArea.CONTENT -> true
            HitArea.NONE -> false
        }
    }
    
    override fun startDrag(isResize: Boolean) {
        super.startDrag(isResize)
        state = if (isResize) {
            // StatusBarWidget also lacks RESIZING state
            State.IDLE
        } else {
            State.MOVING
        }
        onStateChanged?.invoke(state)
    }

    override fun onDragEnd() {
        super.onDragEnd() // BaseWidget.onDragEnd resets baseState if MOVING or RESIZING
        if (state == State.MOVING) {
            Log.d(TAG, "Drag ended")
            state = State.IDLE
            onStateChanged?.invoke(state)
        }
    }
    
    override fun containsPoint(px: Float, py: Float): Boolean {
        if (fullscreenButtonBounds.contains(px, py)) return true
        if (minimizeButtonBounds.contains(px, py)) return true
        if (pinButtonBounds.contains(px, py)) return true

        if (!isFullscreen && !isMinimized && resizeHandleBounds.contains(px, py)) return true
        
        val expandedBounds = RectF(
            widgetBounds.left - BaseWidget.BORDER_HIT_AREA,
            widgetBounds.top - BaseWidget.BORDER_HIT_AREA,
            widgetBounds.right + BaseWidget.BORDER_HIT_AREA,
            widgetBounds.bottom + BaseWidget.BORDER_HIT_AREA
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
        val menuHeight = TOGGLE_MENU_PADDING * 2 + TOGGLE_MENU_ROW_HEIGHT * StatusElement.values().size
        val left = widgetBounds.centerX() - menuWidth / 2f
        val top = contentBounds.top + 8f
        toggleMenuBounds.set(left, top, left + menuWidth, top + menuHeight)

        StatusElement.values().forEachIndexed { index, _ ->
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

        StatusElement.values().forEachIndexed { index, element ->
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
