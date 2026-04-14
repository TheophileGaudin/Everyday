package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BulletSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Log
import androidx.core.text.HtmlCompat

/**
 * A dynamic text box widget that can be moved, resized, deleted, and toggled fullscreen.
 * Supports rich text formatting including bold, italic, underline, and lists.
 * 
 * Extends BaseWidget for common functionality (pin, fullscreen, move).
 * 
 * Additional features:
 * - Text editing with rich formatting
 * - Resize handle (bottom-right corner)
 * - Delete button (top-right, next to fullscreen)
 * - Scrollable content with scrollbar
 * 
 * Updated to work with reactive BaseWidget.
 */
class TextBoxWidget(
    private val context: Context,
    x: Float,
    y: Float,
    widgetWidth: Float,
    widgetHeight: Float
) : BaseWidget(x, y, widgetWidth, widgetHeight) {
    
    companion object {
        private const val TAG = "TextBoxWidget"

        // Minimum sizes
        private const val MIN_WIDTH = 80f
        private const val MIN_HEIGHT = 40f

        // Scrolling
        private const val SCROLL_SENSITIVITY = 0.5f
        private const val SCROLLBAR_WIDTH_RATIO = 0.10f

        // List formatting
        private const val BULLET_GAP_WIDTH = 20
    }
    
    // ==================== Extended State ====================
    
    /**
     * Extended states for TextBoxWidget.
     * Includes base states plus resize and editing.
     */
    enum class State {
        IDLE, HOVER_CONTENT, HOVER_BORDER, EDITING, MOVING, RESIZING
    }
    
    /**
     * Extended hit areas for TextBoxWidget (close button handled via BaseHitArea).
     */
    enum class HitArea {
        NONE, CONTENT, BORDER, FULLSCREEN_BUTTON, PIN_BUTTON, MINIMIZE_BUTTON, SCROLLBAR, RESIZE_HANDLE,
        KEYBOARD_BUTTON, KEYBOARD_KEY
    }
    
    // TextBoxWidget uses its own state that maps to base state
    var state = State.IDLE

    // Label for minimized state
    override val minimizeLabel: String = "T"



    // ==================== Text Content ====================
    
    private var richText = SpannableStringBuilder()
    
    val text: String
        get() = richText.toString()
    
    private var cursorPosition: Int = 0
    private var selectionStart: Int = 0
    private var selectionEnd: Int = 0
    private var selectionAnchor: Int = 0
    
    var isSelecting = false
        private set
    
    private var activeBold = false
    private var activeItalic = false
    private var activeUnderline = false
    
    var textFontSize: Float = 28f
        private set
    
    var isTextWrap: Boolean = true
        private set

    // Multi-column settings
    private var columnCount: Int = 1
    private val COLUMN_GAP = 24f

    fun setColumns(count: Int) {
        columnCount = count.coerceIn(1, 3)
        // Reset scroll when changing layout to prevent getting lost
        scrollOffset = 0f
        updateTextLayout()
        onStateChanged?.invoke(state) // Trigger redraw
    }

    //expose the count of columns
    fun getColumnCount(): Int = columnCount

    private var horizontalScrollOffset: Float = 0f
    private var scrollOffset: Float = 0f
    private var maxScrollOffset: Float = 0f
    
    private var isFocused = false
    private var cursorVisible = true
    private var cursorBlinkTime = 0L
    
    private var pixelAccumulator: Float = 0f
    
    // ==================== Callbacks ====================
    // (onCloseRequested is inherited from BaseWidget)

    var onFocusChanged: ((Boolean) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null
    
    // ==================== Additional Paints ====================
    
    private val focusedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
        color = Color.parseColor("#4444AA")
    }
    
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
    }

    // Close button paints are inherited from BaseWidget

    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#806699FF")
        style = Paint.Style.FILL
    }
    
    // ==================== Additional Bounds ====================
    // (closeButtonBounds is inherited from BaseWidget)

    // Use lazy initialization to avoid NullPointerException when called from BaseWidget constructor
    private val scrollbarBounds by lazy { RectF() }

    // ==================== Keyboard ====================

    private val keyboardOverlay = KeyboardOverlayController().apply {
        onKeyPressed = { key -> this@TextBoxWidget.onKeyPress(key) }
    }
    private val keyboardButtonBounds: RectF
        get() = keyboardOverlay.buttonBounds
    val isKeyboardVisible: Boolean
        get() = keyboardOverlay.isVisible

    init {
        // minWidth/minHeight are open properties in BaseWidget, override default values here
        // or just use constants in onDrag
        updateTextLayout()
        // Ensure bounds are set at least once correctly
        updateBaseBounds()
    }
    
    /**
     * Override base bounds update to sync our local bounds.
     * BaseWidget calls this whenever x, y, width, or height changes (and in its init).
     * Button positions (close, fullscreen, minimize, pin) are now handled by BaseWidget.
     */
    override fun updateBaseBounds() {
        super.updateBaseBounds()

        // Scrollbar on right side of content
        val scrollbarWidth = (contentBounds.width() * SCROLLBAR_WIDTH_RATIO).coerceAtLeast(12f)
        scrollbarBounds.set(
            contentBounds.right - scrollbarWidth,
            contentBounds.top,
            contentBounds.right,
            contentBounds.bottom
        )

        // Keyboard button at the bottom center edge
        keyboardOverlay.updateButtonPosition(widgetBounds.centerX(), widgetBounds.bottom)

        // Update keyboard dimensions if visible
        updateKeyboardLayout()

        updateTextLayout()
    }

    /**
     * Update keyboard layout when widget size changes or keyboard is toggled.
     */
    private fun updateKeyboardLayout() {
        keyboardOverlay.updateLayout(
            areaWidth = contentBounds.width(),
            areaHeight = contentBounds.height(),
            areaLeft = contentBounds.left,
            areaTop = contentBounds.top
        )
    }

    /**
     * Get the height of the content area available for text (excluding keyboard).
     */
    private fun getEffectiveContentHeight(): Float {
        return if (isKeyboardVisible) {
            contentBounds.height() - keyboardOverlay.getHeight()
        } else {
            contentBounds.height()
        }
    }

    /**
     * Toggle keyboard visibility.
     */
    fun toggleKeyboard() {
        val nowVisible = keyboardOverlay.toggle()
        if (nowVisible) {
            updateKeyboardLayout()
        }
        updateTextLayout()
        onStateChanged?.invoke(state)
    }

    /**
     * Show keyboard.
     */
    fun showKeyboard() {
        if (keyboardOverlay.show()) {
            updateKeyboardLayout()
            updateTextLayout()
            onStateChanged?.invoke(state)
        }
    }

    /**
     * Hide keyboard.
     */
    fun hideKeyboard() {
        if (keyboardOverlay.hide()) {
            updateTextLayout()
            onStateChanged?.invoke(state)
        }
    }

    /**
     * Get the keyboard for external hover/tap handling.
     */
    fun getKeyboard(): GlassesKeyboardView? = keyboardOverlay.getKeyboard()
    
    // ==================== State Management ====================
    
    override fun isHovering(): Boolean {
        return state == State.HOVER_CONTENT ||
               state == State.HOVER_BORDER ||
               state == State.MOVING ||
               baseState == BaseState.HOVER_RESIZE ||
               baseState == BaseState.RESIZING
    }
    
    // Use unified border hover from BaseWidget - no override needed

    private fun updateBaseStateFromState() {
        baseState = when (state) {
            State.IDLE -> BaseState.IDLE
            State.HOVER_CONTENT -> BaseState.HOVER_CONTENT
            State.HOVER_BORDER -> BaseState.HOVER_BORDER
            State.MOVING -> BaseState.MOVING
            else -> BaseState.IDLE
        }
    }
    
    // ==================== Hit Testing ====================

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

        // Check keyboard key (if keyboard is visible)
        if (keyboardOverlay.containsPoint(px, py)) {
            return HitArea.KEYBOARD_KEY
        }

        // Check keyboard button (at bottom edge)
        if (keyboardButtonBounds.contains(px, py)) {
            return HitArea.KEYBOARD_BUTTON
        }

        // Check scrollbar
        if (maxScrollOffset > 0 && scrollbarBounds.contains(px, py)) {
            return HitArea.SCROLLBAR
        }

        // Check content (but exclude keyboard area if visible)
        if (contentBounds.contains(px, py)) {
            // If keyboard is visible, check if point is above keyboard
            if (isKeyboardVisible) {
                val keyboardBounds = keyboardOverlay.getBounds()
                if (keyboardBounds == null) {
                    return HitArea.CONTENT
                }
                if (py < keyboardBounds.top) {
                    return HitArea.CONTENT
                }
                // Otherwise it's a keyboard key area
                return HitArea.KEYBOARD_KEY
            }
            return HitArea.CONTENT
        }

        // Check border
        val expandedBounds = RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) {
            return HitArea.BORDER
        }

        return HitArea.NONE
    }
    
    override fun isOverScrollbar(px: Float, py: Float): Boolean {
        return maxScrollOffset > 0 && scrollbarBounds.contains(px, py)
    }
    
    override fun containsPoint(px: Float, py: Float): Boolean {
        // Check keyboard button (extends below widget)
        if (keyboardButtonBounds.contains(px, py)) return true
        // All other button bounds are now handled by BaseWidget
        return super.containsPoint(px, py)
    }
    
    // ==================== Hover Update ====================

    override fun updateHover(px: Float, py: Float) {
        if (state == State.MOVING) {
            return
        }

        // Update keyboard hover state
        keyboardOverlay.updateHover(px, py)

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
            state = newState
            updateBaseStateFromState()
        }
    }
    
    // ==================== Tap Handling ====================

    fun onTap(px: Float, py: Float): Boolean {
        // Check close button first (handled by base class)
        if (closeButtonBounds.contains(px, py)) {
            onCloseRequested?.invoke()
            return true
        }

        val hitArea = hitTest(px, py)

        return when (hitArea) {
            HitArea.FULLSCREEN_BUTTON -> {
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
            HitArea.KEYBOARD_BUTTON -> {
                toggleKeyboard()
                true
            }
            HitArea.KEYBOARD_KEY -> {
                // Handle keyboard key tap
                keyboardOverlay.onTap(px, py)
                true
            }
            HitArea.CONTENT -> {
                setFocused(true)
                true
            }
            HitArea.BORDER -> {
                if (!isFullscreen) {
                    state = State.MOVING
                    updateBaseStateFromState()
                    onStateChanged?.invoke(state)
                }
                true
            }
            HitArea.RESIZE_HANDLE -> {
                // Resize started via drag, not tap
                true
            }
            HitArea.SCROLLBAR -> true
            HitArea.NONE -> false
        }
    }
    
    // ==================== Scroll ====================
    
    override fun onScroll(dy: Float) {
        if (state == State.HOVER_CONTENT || state == State.EDITING) {
            scrollOffset = (scrollOffset + dy * SCROLL_SENSITIVITY).coerceIn(0f, maxScrollOffset)
        }
    }
    
    // ==================== Drag / Resize ====================
    
    override fun startDrag(isResize: Boolean) {
        super.startDrag(isResize)
        state = if (isResize) State.RESIZING else State.MOVING
        onStateChanged?.invoke(state)
    }

    override fun onDrag(dx: Float, dy: Float, screenWidth: Float, screenHeight: Float) {
        if (isFullscreen) return
        
        if (state == State.MOVING) {
            super.onDrag(dx, dy, screenWidth, screenHeight)
        } else if (baseState == BaseState.HOVER_RESIZE || baseState == BaseState.RESIZING) {
            super.onDrag(dx, dy, screenWidth, screenHeight)
            // BaseWidget updates bounds -> updateBaseBounds() calls updateTextLayout()
        }
    }
    
    override fun onDragEnd() {
        super.onDragEnd()
        if (state == State.MOVING) {
            state = State.IDLE
            updateBaseStateFromState()
            onStateChanged?.invoke(state)
        }
    }
    
    // ==================== Fullscreen Override ====================
    
    override fun exitFullscreen() {
        super.exitFullscreen()
        updateTextLayout()
    }
    
    override fun setFullscreenBounds(screenWidth: Float, screenHeight: Float) {
        super.setFullscreenBounds(screenWidth, screenHeight)
        updateTextLayout()
    }
    
    // ==================== Focus ====================
    
    fun setFocused(focused: Boolean) {
        if (isFocused == focused) return
        
        isFocused = focused
        cursorVisible = true
        cursorBlinkTime = System.currentTimeMillis()
        
        if (focused) {
            updateActiveFormattingFromCursor()
        }
        
        onFocusChanged?.invoke(focused)
    }
    
    fun isFocused(): Boolean = isFocused
    
    // ==================== Text Content Methods ====================
    
    fun setTextContent(newText: String) {
        richText = SpannableStringBuilder(newText)
        cursorPosition = richText.length
        clearSelection()
        updateTextLayout()
    }
    
    fun setHtmlContent(html: String) {
        try {
            val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            richText = SpannableStringBuilder(spanned)
            cursorPosition = richText.length
            clearSelection()
            updateTextLayout()
            Log.d(TAG, "Restored HTML content: ${richText.length} chars")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HTML, falling back to plain text", e)
            setTextContent(html)
        }
    }
    
    fun getHtmlContent(): String {
        return HtmlCompat.toHtml(richText, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
    }
    
    fun setFontSize(size: Float) {
        textFontSize = size.coerceIn(12f, 72f)
        textPaint.textSize = textFontSize
        hintPaint.textSize = textFontSize
        updateTextLayout()
    }
    
    fun setTextWrap(wrap: Boolean) {
        isTextWrap = wrap
        updateTextLayout()
    }
    
    fun hasSelection(): Boolean = selectionStart != selectionEnd
    
    fun getSelectedText(): String {
        if (!hasSelection()) return ""
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        return richText.subSequence(start, end).toString()
    }
    
    fun getSelectedRichText(): SpannableStringBuilder {
        if (!hasSelection()) return SpannableStringBuilder()
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        return SpannableStringBuilder(richText.subSequence(start, end))
    }
    
    fun clearSelection() {
        selectionStart = cursorPosition
        selectionEnd = cursorPosition
    }
    
    fun deleteSelection(): String {
        if (!hasSelection()) return ""
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val deleted = richText.subSequence(start, end).toString()
        richText.delete(start, end)
        cursorPosition = start.coerceIn(0, richText.length)
        clearSelection()
        updateTextLayout()
        return deleted
    }
    
    fun startSelection() {
        isSelecting = true
        selectionAnchor = cursorPosition
        selectionStart = cursorPosition
        selectionEnd = cursorPosition
    }
    
    fun endSelection() {
        isSelecting = false
    }
    
    fun updateSelectionToCursor() {
        if (!isSelecting) return
        selectionStart = minOf(selectionAnchor, cursorPosition)
        selectionEnd = maxOf(selectionAnchor, cursorPosition)
    }
    
    fun moveCursorByPixels(dx: Float, updateSelection: Boolean = false): Int {
        if (richText.isEmpty()) return 0
        
        val avgCharWidth = textPaint.measureText("m") * 0.5f
        pixelAccumulator += dx
        
        val charDelta = (pixelAccumulator / avgCharWidth).toInt()
        val oldPos = cursorPosition
        
        if (charDelta != 0) {
            pixelAccumulator -= charDelta * avgCharWidth
            cursorPosition = (cursorPosition + charDelta).coerceIn(0, richText.length)
        }
        
        if (updateSelection && isSelecting) {
            updateSelectionToCursor()
        }
        
        return cursorPosition - oldPos
    }

    /**
     * Calculates the starting Y coordinate in the text for each column.
     * Snaps to line boundaries to prevent text from being sliced in half.
     */
    private fun calculateColumnOffsets(layout: Layout, visibleHeight: Float): FloatArray {
        val offsets = FloatArray(columnCount)

        // Column 0 always starts at the current scroll offset
        var currentY = scrollOffset
        offsets[0] = currentY

        for (i in 1 until columnCount) {
            // The "ideal" cut point is exactly one screen height below the previous column's start
            val idealCutY = currentY + visibleHeight

            // Find which line is at this cut point
            // We use idealCutY - 1 to ensure we hit the line inside the boundary, not the one starting exactly after
            val lineIndex = layout.getLineForVertical(idealCutY.toInt() - 1)

            val lineTop = layout.getLineTop(lineIndex)
            val lineBottom = layout.getLineBottom(lineIndex)

            // Check coverage:
            // pixelInPrevCol = idealCutY - lineTop
            // pixelsInNextCol = lineBottom - idealCutY
            val pixelsInPrevCol = idealCutY - lineTop
            val pixelsInNextCol = lineBottom - idealCutY

            // "Pick the column having the most coverage left"
            // If most of the line is in the previous column (>= 50%), keep it there.
            // The NEXT column starts AFTER this line (at lineBottom).
            // Otherwise, push the whole line to the NEXT column.
            // The NEXT column starts BEFORE this line (at lineTop).
            currentY = if (pixelsInPrevCol >= pixelsInNextCol) {
                lineBottom.toFloat()
            } else {
                lineTop.toFloat()
            }

            offsets[i] = currentY
        }

        return offsets
    }
    
    fun resetPixelAccumulator() {
        pixelAccumulator = 0f
    }

    fun setCursorFromScreenPosition(screenX: Float, screenY: Float) {
        if (richText.isEmpty()) {
            cursorPosition = 0
            return
        }

        val padding = BORDER_WIDTH + 8f
        val relativeX = screenX - x - padding
        val relativeY = screenY - y - padding

        // 1. Determine which column was clicked
        val totalAvailableWidth = contentBounds.width()
        val gapTotal = (columnCount - 1) * COLUMN_GAP
        val singleColumnWidth = (totalAvailableWidth - gapTotal) / columnCount
        val columnStride = singleColumnWidth + COLUMN_GAP

        // Clamp column index to valid range
        val colIndex = (relativeX / columnStride).toInt().coerceIn(0, columnCount - 1)

        // 2. Rebuild layout to calculate offsets
        val layout = StaticLayout.Builder.obtain(richText, 0, richText.length, textPaint, singleColumnWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

        // 3. NEW: Get the snapped Y start for this specific column
        val visibleHeight = contentBounds.height()
        val columnOffsets = calculateColumnOffsets(layout, visibleHeight)

        // This is the specific offset for the column you clicked
        val columnStartY = columnOffsets[colIndex]

        // 4. Calculate Virtual Y using the snapped offset
        val virtualY = columnStartY + relativeY

        // 5. Adjust X to be relative to the start of that column
        val columnRelativeX = (relativeX - (colIndex * columnStride)).coerceIn(0f, singleColumnWidth)

        // 6. Find line
        val line = (0 until layout.lineCount).firstOrNull { lineIndex ->
            val lineTop = layout.getLineTop(lineIndex)
            val lineBottom = layout.getLineBottom(lineIndex)
            virtualY >= lineTop && virtualY < lineBottom
        } ?: if (virtualY < 0) 0 else layout.lineCount - 1

        cursorPosition = layout.getOffsetForHorizontal(line, columnRelativeX).coerceIn(0, richText.length)
        updateActiveFormattingFromCursor()
    }
    fun selectAll() {
        selectionStart = 0
        selectionEnd = richText.length
        cursorPosition = richText.length
    }
    
    // ==================== Rich Text Formatting ====================
    
    fun isBoldActive(): Boolean {
        if (hasSelection()) {
            return hasSpanInSelection(StyleSpan::class.java) { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }
        }
        return activeBold || hasSpanAtCursor(StyleSpan::class.java) { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }
    }
    
    fun isItalicActive(): Boolean {
        if (hasSelection()) {
            return hasSpanInSelection(StyleSpan::class.java) { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC }
        }
        return activeItalic || hasSpanAtCursor(StyleSpan::class.java) { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC }
    }
    
    fun isUnderlineActive(): Boolean {
        if (hasSelection()) {
            return hasSpanInSelection(UnderlineSpan::class.java) { true }
        }
        return activeUnderline || hasSpanAtCursor(UnderlineSpan::class.java) { true }
    }
    
    fun toggleBold() {
        if (hasSelection()) {
            toggleStyleSpanOnSelection(Typeface.BOLD)
        } else {
            activeBold = !activeBold
        }
    }
    
    fun toggleItalic() {
        if (hasSelection()) {
            toggleStyleSpanOnSelection(Typeface.ITALIC)
        } else {
            activeItalic = !activeItalic
        }
    }
    
    fun toggleUnderline() {
        if (hasSelection()) {
            toggleUnderlineSpanOnSelection()
        } else {
            activeUnderline = !activeUnderline
        }
    }
    
    fun toggleBulletList() {
        val start = if (hasSelection()) minOf(selectionStart, selectionEnd) else cursorPosition
        val end = if (hasSelection()) maxOf(selectionStart, selectionEnd) else cursorPosition
        
        val lineStart = findLineStart(start)
        val lineEnd = findLineEnd(end)
        
        val existingBullets = richText.getSpans(lineStart, lineEnd, BulletSpan::class.java)
        
        if (existingBullets.isNotEmpty()) {
            for (span in existingBullets) {
                richText.removeSpan(span)
            }
        } else {
            richText.setSpan(
                BulletSpan(BULLET_GAP_WIDTH, Color.WHITE),
                lineStart,
                lineEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        updateTextLayout()
    }
    
    fun toggleNumberedList() {
        val start = if (hasSelection()) minOf(selectionStart, selectionEnd) else cursorPosition
        val lineStart = findLineStart(start)
        
        val lineText = richText.substring(lineStart, minOf(lineStart + 10, richText.length))
        val numberPattern = Regex("^\\d+\\.\\s")
        
        if (numberPattern.containsMatchIn(lineText)) {
            val match = numberPattern.find(lineText)
            if (match != null) {
                richText.delete(lineStart, lineStart + match.value.length)
                cursorPosition = (cursorPosition - match.value.length).coerceAtLeast(lineStart)
            }
        } else {
            richText.insert(lineStart, "1. ")
            cursorPosition += 3
        }
        
        clearSelection()
        updateTextLayout()
    }
    
    private fun findLineStart(pos: Int): Int {
        var start = pos.coerceIn(0, richText.length)
        while (start > 0 && richText[start - 1] != '\n') {
            start--
        }
        return start
    }
    
    private fun findLineEnd(pos: Int): Int {
        var end = pos.coerceIn(0, richText.length)
        while (end < richText.length && richText[end] != '\n') {
            end++
        }
        return end
    }
    
    private fun <T> hasSpanInSelection(spanClass: Class<T>, predicate: (T) -> Boolean): Boolean {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val spans = richText.getSpans(start, end, spanClass)
        return spans.any(predicate)
    }
    
    private fun <T> hasSpanAtCursor(spanClass: Class<T>, predicate: (T) -> Boolean): Boolean {
        if (richText.isEmpty()) return false
        val pos = cursorPosition.coerceIn(0, richText.length)
        val checkPos = if (pos > 0) pos - 1 else pos
        val spans = richText.getSpans(checkPos, checkPos + 1, spanClass)
        return spans.any(predicate)
    }
    
    private fun toggleStyleSpanOnSelection(style: Int) {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        
        if (start == end) return
        
        val existingSpans = richText.getSpans(start, end, StyleSpan::class.java)
        val hasStyle = existingSpans.any { 
            val spanStart = richText.getSpanStart(it)
            val spanEnd = richText.getSpanEnd(it)
            spanStart <= start && spanEnd >= end && (it.style == style || it.style == Typeface.BOLD_ITALIC)
        }
        
        if (hasStyle) {
            for (span in existingSpans) {
                if (span.style == style || span.style == Typeface.BOLD_ITALIC) {
                    val spanStart = richText.getSpanStart(span)
                    val spanEnd = richText.getSpanEnd(span)
                    richText.removeSpan(span)
                    
                    if (span.style == Typeface.BOLD_ITALIC) {
                        val remainingStyle = if (style == Typeface.BOLD) Typeface.ITALIC else Typeface.BOLD
                        if (spanStart < start) {
                            richText.setSpan(StyleSpan(span.style), spanStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        richText.setSpan(StyleSpan(remainingStyle), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (spanEnd > end) {
                            richText.setSpan(StyleSpan(span.style), end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    } else {
                        if (spanStart < start) {
                            richText.setSpan(StyleSpan(span.style), spanStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (spanEnd > end) {
                            richText.setSpan(StyleSpan(span.style), end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }
        } else {
            richText.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        updateTextLayout()
    }
    
    private fun toggleUnderlineSpanOnSelection() {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        
        if (start == end) return
        
        val existingSpans = richText.getSpans(start, end, UnderlineSpan::class.java)
        val hasUnderline = existingSpans.any {
            val spanStart = richText.getSpanStart(it)
            val spanEnd = richText.getSpanEnd(it)
            spanStart <= start && spanEnd >= end
        }
        
        if (hasUnderline) {
            for (span in existingSpans) {
                val spanStart = richText.getSpanStart(span)
                val spanEnd = richText.getSpanEnd(span)
                richText.removeSpan(span)
                
                if (spanStart < start) {
                    richText.setSpan(UnderlineSpan(), spanStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (spanEnd > end) {
                    richText.setSpan(UnderlineSpan(), end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } else {
            richText.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        updateTextLayout()
    }
    
    private fun updateActiveFormattingFromCursor() {
        if (richText.isEmpty() || cursorPosition == 0) {
            activeBold = false
            activeItalic = false
            activeUnderline = false
            return
        }
        
        val checkPos = (cursorPosition - 1).coerceIn(0, richText.length - 1)
        
        val styleSpans = richText.getSpans(checkPos, checkPos + 1, StyleSpan::class.java)
        activeBold = styleSpans.any { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }
        activeItalic = styleSpans.any { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC }
        
        val underlineSpans = richText.getSpans(checkPos, checkPos + 1, UnderlineSpan::class.java)
        activeUnderline = underlineSpans.isNotEmpty()
    }
    
    // ==================== Key Input ====================
    
    fun onKeyPress(key: String) {
        if (!isFocused) return

        Log.d(TAG, "onKeyPress: key='$key', keyLength=${key.length}, isFocused=$isFocused")

        when (key) {
            "BACKSPACE" -> {
                if (hasSelection()) {
                    deleteSelection()
                } else if (cursorPosition > 0) {
                    richText.delete(cursorPosition - 1, cursorPosition)
                    cursorPosition--
                    updateTextLayout()
                }
            }
            else -> {
                if (key.isEmpty()) {
                    Log.w(TAG, "onKeyPress: ignoring empty key")
                    return
                }

                if (hasSelection()) {
                    deleteSelection()
                }

                val insertPos = cursorPosition
                Log.d(TAG, "Inserting key at position $insertPos, text before: '${richText.toString().take(50)}...'")
                richText.insert(insertPos, key)
                Log.d(TAG, "Text after insert: '${richText.toString().take(50)}...'")
                
                val insertEnd = insertPos + key.length
                
                if (activeBold && activeItalic) {
                    richText.setSpan(StyleSpan(Typeface.BOLD_ITALIC), insertPos, insertEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (activeBold) {
                    richText.setSpan(StyleSpan(Typeface.BOLD), insertPos, insertEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (activeItalic) {
                    richText.setSpan(StyleSpan(Typeface.ITALIC), insertPos, insertEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                
                if (activeUnderline) {
                    richText.setSpan(UnderlineSpan(), insertPos, insertEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                
                cursorPosition = insertEnd
                clearSelection()
                updateTextLayout()
                
                scrollOffset = maxScrollOffset
            }
        }
        
        cursorVisible = true
        cursorBlinkTime = System.currentTimeMillis()
    }
    
    fun pasteText(content: String) {
        if (!isFocused) return
        
        if (hasSelection()) {
            deleteSelection()
        }
        
        val insertPos = cursorPosition
        richText.insert(insertPos, content)
        
        val insertEnd = insertPos + content.length
        
        if (activeBold && activeItalic) {
            richText.setSpan(StyleSpan(Typeface.BOLD_ITALIC), insertPos, insertEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (activeBold) {
            richText.setSpan(StyleSpan(Typeface.BOLD), insertPos, insertEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (activeItalic) {
            richText.setSpan(StyleSpan(Typeface.ITALIC), insertPos, insertEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        if (activeUnderline) {
            richText.setSpan(UnderlineSpan(), insertPos, insertEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        cursorPosition = insertEnd
        clearSelection()
        updateTextLayout()
        
        scrollOffset = maxScrollOffset
        cursorVisible = true
        cursorBlinkTime = System.currentTimeMillis()
    }
    
    // ==================== Layout ====================

    private fun updateTextLayout() {
        if (richText.isEmpty()) {
            maxScrollOffset = 0f
            scrollOffset = 0f
            horizontalScrollOffset = 0f
            return
        }

        // Calculate the width of a SINGLE column
        val totalAvailableWidth = contentBounds.width()
        val gapTotal = (columnCount - 1) * COLUMN_GAP
        val singleColumnWidth = ((totalAvailableWidth - gapTotal) / columnCount).toInt().coerceAtLeast(1)

        // Build the text layout constrained to that single column width
        // We assume wrapping is ON for columns (multi-column usually implies wrapping)
        val targetWidth = if (isTextWrap) singleColumnWidth else Int.MAX_VALUE / 2

        val layout = StaticLayout.Builder.obtain(richText, 0, richText.length, textPaint, targetWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

        val textHeight = layout.height.toFloat()
        // Use effective content height (accounting for keyboard)
        val visibleHeight = getEffectiveContentHeight()

        // Multi-column logic: The text flows from the bottom of col 1 to the top of col 2.
        // Total visible capacity = height of one column * number of columns.
        val totalCapacity = visibleHeight * columnCount

        // Max scroll allows the very last line to appear at the bottom of the very last column
        maxScrollOffset = (textHeight - totalCapacity).coerceAtLeast(0f)

        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)
        horizontalScrollOffset = 0f
    }
    
    // ==================== Drawing ====================
    
    private fun drawSelectionHighlight(canvas: Canvas, layout: StaticLayout) {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, richText.length)
        
        if (start == end) return
        
        val selectionPath = Path()
        layout.getSelectionPath(start, end, selectionPath)
        canvas.drawPath(selectionPath, selectionPaint)
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // Cursor blink
        if (isFocused) {
            val elapsed = System.currentTimeMillis() - cursorBlinkTime
            if (elapsed > 530) {
                cursorVisible = !cursorVisible
                cursorBlinkTime = System.currentTimeMillis()
            }
        }

        // Background
        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)

        if (shouldShowBorder()) {
            val borderP = if (isFocused) focusedBorderPaint else hoverBorderPaint
            canvas.drawRoundRect(widgetBounds, 8f, 8f, borderP)
        }

        // Content - clip to area above keyboard if visible
        canvas.save()
        val textClipBounds = if (isKeyboardVisible) {
            RectF(
                contentBounds.left,
                contentBounds.top,
                contentBounds.right,
                contentBounds.bottom - keyboardOverlay.getHeight()
            )
        } else {
            contentBounds
        }
        canvas.clipRect(textClipBounds)

        val showHint = isBorderHovered && !isFocused && richText.isEmpty()
        val displayText: CharSequence = when {
            richText.isNotEmpty() -> richText
            showHint -> "Click to type..."
            isFocused -> ""
            else -> ""
        }
        val paint = if (richText.isEmpty() && showHint) hintPaint else textPaint

        if (displayText.isNotEmpty()) {
            // Recalculate column width for drawing
            val totalAvailableWidth = contentBounds.width()
            val gapTotal = (columnCount - 1) * COLUMN_GAP
            val singleColumnWidth = ((totalAvailableWidth - gapTotal) / columnCount).toInt().coerceAtLeast(1)

            val layout = StaticLayout.Builder.obtain(displayText, 0, displayText.length, paint, singleColumnWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()

            val visibleHeight = getEffectiveContentHeight()

            // NEW: Calculate dynamic offsets that align with text lines
            val columnOffsets = calculateColumnOffsets(layout, visibleHeight)

            // Draw each column
            for (col in 0 until columnCount) {
                // Determine where this column sits horizontally on screen
                val colX = contentBounds.left + col * (singleColumnWidth + COLUMN_GAP)

                // NEW: Use the calculated snapped offset instead of simple math
                val virtualYStart = columnOffsets[col]

                canvas.save()
                // Clip output to just this column
                canvas.clipRect(colX, contentBounds.top, colX + singleColumnWidth, contentBounds.bottom)

                // Move canvas to the column slot, then shift text UP by virtualYStart
                canvas.translate(colX, contentBounds.top - virtualYStart)

                if (hasSelection() && richText.isNotEmpty() && displayText === richText) {
                    drawSelectionHighlight(canvas, layout)
                }

                layout.draw(canvas)

                // Draw Cursor
                if (isFocused && cursorVisible && richText.isNotEmpty()) {
                    val cursorOffset = cursorPosition.coerceIn(0, richText.length)

                    // Only draw cursor if it falls within this column's vertical range
                    val line = layout.getLineForOffset(cursorOffset)
                    val lineTop = layout.getLineTop(line)
                    val lineBottom = layout.getLineBottom(line)

                    // NEW: Accurate visibility check using snapped offsets
                    if (lineBottom > virtualYStart && lineTop < virtualYStart + visibleHeight) {
                        val cursorX = layout.getPrimaryHorizontal(cursorOffset)
                        val cursorTop = layout.getLineTop(line).toFloat()
                        val cursorBottom = layout.getLineBottom(line).toFloat()
                        canvas.drawLine(cursorX, cursorTop, cursorX, cursorBottom, cursorPaint)
                    }
                }

                canvas.restore()
            }
        } else if (isFocused && cursorVisible) {
            // Empty state cursor
            canvas.translate(contentBounds.left, contentBounds.top)
            canvas.drawLine(0f, 0f, 0f, paint.textSize, cursorPaint)
        }

        canvas.restore()

        // Draw keyboard if visible (inside content bounds but at bottom)
        keyboardOverlay.draw(canvas)

        // Border buttons - uses drawBorderButtons() which includes pin, minimize, fullscreen, and close buttons
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
        }

        drawResizeHandle(canvas)

        // Keyboard button at bottom edge (show when border is hovered or keyboard is visible)
        if (isBorderHovered || isKeyboardVisible) {
            drawKeyboardButton(canvas)
        }

        // Scrollbar logic... (rest of your existing scrollbar code)
        if (maxScrollOffset > 0 && isHovering()) {
            // ... keep your existing scrollbar drawing code ...
            val scrollbarWidth = (contentBounds.width() * SCROLLBAR_WIDTH_RATIO).coerceAtLeast(12f)
            val trackPadding = 4f
            val trackHeight = contentBounds.height() - (trackPadding * 2)
            val thumbHeight = ((contentBounds.height() / (contentBounds.height() + maxScrollOffset)) * trackHeight).coerceAtLeast(20f)
            val thumbY = if (maxScrollOffset > 0) {
                (scrollOffset / maxScrollOffset) * (trackHeight - thumbHeight)
            } else {
                0f
            }

            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22666688")
            }
            canvas.drawRoundRect(
                contentBounds.right - scrollbarWidth,
                contentBounds.top + trackPadding,
                contentBounds.right - 2f,
                contentBounds.bottom - trackPadding,
                4f, 4f,
                trackPaint
            )

            val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#66888899")
            }
            canvas.drawRoundRect(
                contentBounds.right - scrollbarWidth + 2f,
                contentBounds.top + trackPadding + thumbY,
                contentBounds.right - 4f,
                contentBounds.top + trackPadding + thumbY + thumbHeight,
                3f, 3f,
                thumbPaint
            )
        }
    }

    /**
     * Draw the keyboard toggle button at the bottom edge.
     */
    private fun drawKeyboardButton(canvas: Canvas) {
        keyboardOverlay.drawButton(canvas)
    }
}
