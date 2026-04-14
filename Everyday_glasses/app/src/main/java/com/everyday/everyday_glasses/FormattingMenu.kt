package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log

/**
 * A floating formatting toolbar for text editing.
 * 
 * Features:
 * - Bold, Italic, Underline toggles
 * - Font size +/- buttons and direct input
 * - Text wrap toggle
 * - Bulleted and numbered list toggles
 * - Movable by dragging the edge/title bar
 * - Close button
 * - Auto-closes when text field loses focus
 */
class FormattingMenu(
    private val screenWidth: Float,
    private val screenHeight: Float
) {
    companion object {
        private const val TAG = "FormattingMenu"
        
        // Menu dimensions
        private const val MENU_WIDTH = 280f
        private const val TITLE_HEIGHT = 32f
        private const val BUTTON_SIZE = 36f
        private const val BUTTON_SPACING = 8f
        private const val PADDING = 10f
        private const val CORNER_RADIUS = 8f
        private const val CLOSE_BUTTON_SIZE = 24f
        private const val FONT_SIZE_INPUT_WIDTH = 48f
        
        // Font size limits
        private const val MIN_FONT_SIZE = 12f
        private const val MAX_FONT_SIZE = 72f
        private const val FONT_SIZE_STEP = 2f
    }
    
    // State
    var isVisible = false
        private set
    
    private var menuX = 0f
    private var menuY = 0f
    private var menuHeight = 0f
    
    // Dragging state
    private var isDragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    
    // Long-press drag state (like other widgets)
    private var isLongPressPending = false
    private var longPressStartX = 0f
    private var longPressStartY = 0f
    
    // Current formatting state
    var isBold = false
    var isItalic = false
    var isUnderline = false
    var fontSize = 28f
        private set
    var isTextWrap = true
        private set
    var isBulletList = false
    var isNumberedList = false
    var currentColumns = 1
        private set
    
    // Hovered button
    private var hoveredButton: String? = null
    
    // Button rects
    private val closeButtonRect = RectF()
    private val titleBarRect = RectF()
    private val boldButtonRect = RectF()
    private val italicButtonRect = RectF()
    private val underlineButtonRect = RectF()
    private val fontSizeMinusRect = RectF()
    private val fontSizeInputRect = RectF()
    private val fontSizePlusRect = RectF()
    private val wrapButtonRect = RectF()
    private val bulletListRect = RectF()
    private val numberedListRect = RectF()
    private val col1ButtonRect = RectF()
    private val col2ButtonRect = RectF()
    private val col3ButtonRect = RectF()


    // Callbacks
    var onBoldToggled: ((Boolean) -> Unit)? = null
    var onItalicToggled: ((Boolean) -> Unit)? = null
    var onUnderlineToggled: ((Boolean) -> Unit)? = null
    var onFontSizeChanged: ((Float) -> Unit)? = null
    var onTextWrapToggled: ((Boolean) -> Unit)? = null
    var onBulletListToggled: ((Boolean) -> Unit)? = null
    var onNumberedListToggled: ((Boolean) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null
    var onColumnsChanged: ((Int) -> Unit)? = null

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2a2a3e")
    }
    
    private val titleBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3a3a5e")
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#5a5a7e")
    }
    
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3a3a5e")
    }
    
    private val buttonActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5050AA")
    }
    
    private val buttonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4a4a7e")
    }
    
    private val closeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA4444")
    }
    
    private val closeButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC5555")
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    
    private val boldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    
    private val italicTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    
    private val underlineTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isUnderlineText = true
    }
    
    private val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAACC")
        textSize = 16f
    }
    
    private val inputBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222233")
    }
    
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }
    
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    
    /**
     * Show the formatting menu at the specified position.
     * @param x X position for menu
     * @param y Y position for menu (menu will appear above this point)
     * @param currentFontSize The current font size at cursor position
     * @param currentWrap Whether text wrap is currently enabled
     */
    fun show(x: Float, y: Float, currentFontSize: Float, currentWrap: Boolean, columns: Int = 1) {
        fontSize = currentFontSize
        isTextWrap = currentWrap
        currentColumns = columns
        
        // Reset formatting state (these will be set by the widget based on selection/cursor)
        isBold = false
        isItalic = false
        isUnderline = false
        isBulletList = false
        isNumberedList = false
        
        // Calculate menu height (3 rows of buttons + title bar + padding)
        menuHeight = TITLE_HEIGHT + (BUTTON_SIZE + BUTTON_SPACING) * 3 + PADDING * 4

        // Position menu above the invocation point
        menuX = (x - MENU_WIDTH / 2).coerceIn(0f, screenWidth - MENU_WIDTH)
        menuY = (y - menuHeight - 10f).coerceIn(0f, screenHeight - menuHeight)
        
        updateButtonRects()
        
        hoveredButton = null
        isDragging = false
        isVisible = true
        
        Log.d(TAG, "Formatting menu shown at ($menuX, $menuY), fontSize=$fontSize, wrap=$isTextWrap")
    }
    
    /**
     * Hide the menu.
     */
    fun dismiss() {
        if (isVisible) {
            isVisible = false
            hoveredButton = null
            isDragging = false
            onDismissed?.invoke()
            Log.d(TAG, "Formatting menu dismissed")
        }
    }
    
    /**
     * Update button rectangles based on current menu position.
     */
    private fun updateButtonRects() {
        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
        
        // Title bar (entire top area for dragging)
        titleBarRect.set(
            menuX,
            menuY,
            menuX + MENU_WIDTH - CLOSE_BUTTON_SIZE - PADDING,
            menuY + TITLE_HEIGHT
        )
        
        // Close button (top-right)
        closeButtonRect.set(
            menuX + MENU_WIDTH - CLOSE_BUTTON_SIZE - PADDING / 2,
            menuY + (TITLE_HEIGHT - CLOSE_BUTTON_SIZE) / 2,
            menuX + MENU_WIDTH - PADDING / 2,
            menuY + (TITLE_HEIGHT + CLOSE_BUTTON_SIZE) / 2
        )
        
        // Row 1: Bold, Italic, Underline, Wrap
        var rowY = menuY + TITLE_HEIGHT + PADDING
        var buttonX = menuX + PADDING
        
        boldButtonRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)
        buttonX += BUTTON_SIZE + BUTTON_SPACING
        
        italicButtonRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)
        buttonX += BUTTON_SIZE + BUTTON_SPACING
        
        underlineButtonRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)
        buttonX += BUTTON_SIZE + BUTTON_SPACING * 2  // Extra spacing before wrap
        
        wrapButtonRect.set(buttonX, rowY, buttonX + BUTTON_SIZE * 1.5f, rowY + BUTTON_SIZE)
        
        // Row 2: Font size controls, Bullet list, Numbered list
        rowY += BUTTON_SIZE + BUTTON_SPACING
        buttonX = menuX + PADDING
        
        fontSizeMinusRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)
        buttonX += BUTTON_SIZE + 2f
        
        fontSizeInputRect.set(buttonX, rowY, buttonX + FONT_SIZE_INPUT_WIDTH, rowY + BUTTON_SIZE)
        buttonX += FONT_SIZE_INPUT_WIDTH + 2f
        
        fontSizePlusRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)
        buttonX += BUTTON_SIZE + BUTTON_SPACING * 2
        
        bulletListRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)
        buttonX += BUTTON_SIZE + BUTTON_SPACING
        
        numberedListRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)

        // Start Row 3
        rowY += BUTTON_SIZE + BUTTON_SPACING
        buttonX = menuX + PADDING


        // Center the 3 buttons or left-align them
        col1ButtonRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)
        buttonX += BUTTON_SIZE + BUTTON_SPACING

        col2ButtonRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)
        buttonX += BUTTON_SIZE + BUTTON_SPACING

        col3ButtonRect.set(buttonX, rowY, buttonX + BUTTON_SIZE, rowY + BUTTON_SIZE)

    }
    
    /**
     * Update hover state based on cursor position.
     */
    fun updateHover(px: Float, py: Float) {
        if (!isVisible) return
        
        hoveredButton = when {
            closeButtonRect.contains(px, py) -> "close"
            boldButtonRect.contains(px, py) -> "bold"
            italicButtonRect.contains(px, py) -> "italic"
            underlineButtonRect.contains(px, py) -> "underline"
            fontSizeMinusRect.contains(px, py) -> "fontMinus"
            fontSizePlusRect.contains(px, py) -> "fontPlus"
            fontSizeInputRect.contains(px, py) -> "fontInput"
            wrapButtonRect.contains(px, py) -> "wrap"
            bulletListRect.contains(px, py) -> "bullet"
            numberedListRect.contains(px, py) -> "numbered"
            titleBarRect.contains(px, py) -> "titleBar"
            col1ButtonRect.contains(px, py) -> "col1"
            col2ButtonRect.contains(px, py) -> "col2"
            col3ButtonRect.contains(px, py) -> "col3"
            else -> null
        }
    }
    
    /**
     * Check if a point is within the menu area.
     */
    fun containsPoint(px: Float, py: Float): Boolean {
        if (!isVisible) return false
        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
        return menuBounds.contains(px, py)
    }
    
    /**
     * Check if a long-press should be scheduled for dragging.
     * Returns true if long-press should be scheduled.
     */
    fun shouldScheduleLongPress(px: Float, py: Float): Boolean {
        if (!isVisible) return false
        
        // Check if press is on title bar (drag area)
        if (titleBarRect.contains(px, py)) {
            isLongPressPending = true
            longPressStartX = px
            longPressStartY = py
            return true
        }
        return false
    }
    
    /**
     * Called when long-press is triggered - start actual drag.
     */
    fun onLongPressTriggered(): Boolean {
        if (!isVisible || !isLongPressPending) return false
        
        isDragging = true
        dragOffsetX = longPressStartX - menuX
        dragOffsetY = longPressStartY - menuY
        isLongPressPending = false
        Log.d(TAG, "Started dragging formatting menu via long-press")
        return true
    }
    
    /**
     * Cancel pending long-press.
     */
    fun cancelLongPress() {
        isLongPressPending = false
    }
    
    /**
     * Check if movement should cancel the long-press.
     */
    fun checkLongPressCancellation(px: Float, py: Float, maxMovement: Float): Boolean {
        if (!isLongPressPending) return false
        
        val dx = px - longPressStartX
        val dy = py - longPressStartY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        
        if (distance > maxMovement) {
            isLongPressPending = false
            return true
        }
        return false
    }
    
    /**
     * Check if long-press is pending.
     */
    fun isLongPressPending(): Boolean = isLongPressPending
    
    /**
     * Handle drag movement.
     */
    fun onDrag(px: Float, py: Float) {
        if (!isVisible || !isDragging) return
        
        menuX = (px - dragOffsetX).coerceIn(0f, screenWidth - MENU_WIDTH)
        menuY = (py - dragOffsetY).coerceIn(0f, screenHeight - menuHeight)
        updateButtonRects()
    }
    
    /**
     * End drag operation.
     */
    fun onDragEnd() {
        isDragging = false
    }
    
    /**
     * Handle tap at the given position.
     * Returns true if the tap was handled.
     */
    fun onTap(px: Float, py: Float): Boolean {
        if (!isVisible) return false
        
        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
        
        if (!menuBounds.contains(px, py)) {
            // Tap outside - dismiss
            dismiss()
            return true
        }
        
        // Check button taps
        when {
            closeButtonRect.contains(px, py) -> {
                Log.d(TAG, "Close button tapped")
                dismiss()
                return true
            }
            boldButtonRect.contains(px, py) -> {
                isBold = !isBold
                Log.d(TAG, "Bold toggled: $isBold")
                onBoldToggled?.invoke(isBold)
                return true
            }
            italicButtonRect.contains(px, py) -> {
                isItalic = !isItalic
                Log.d(TAG, "Italic toggled: $isItalic")
                onItalicToggled?.invoke(isItalic)
                return true
            }
            underlineButtonRect.contains(px, py) -> {
                isUnderline = !isUnderline
                Log.d(TAG, "Underline toggled: $isUnderline")
                onUnderlineToggled?.invoke(isUnderline)
                return true
            }
            fontSizeMinusRect.contains(px, py) -> {
                fontSize = (fontSize - FONT_SIZE_STEP).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                Log.d(TAG, "Font size decreased: $fontSize")
                onFontSizeChanged?.invoke(fontSize)
                return true
            }
            fontSizePlusRect.contains(px, py) -> {
                fontSize = (fontSize + FONT_SIZE_STEP).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                Log.d(TAG, "Font size increased: $fontSize")
                onFontSizeChanged?.invoke(fontSize)
                return true
            }
            wrapButtonRect.contains(px, py) -> {
                isTextWrap = !isTextWrap
                Log.d(TAG, "Text wrap toggled: $isTextWrap")
                onTextWrapToggled?.invoke(isTextWrap)
                return true
            }
            bulletListRect.contains(px, py) -> {
                isBulletList = !isBulletList
                if (isBulletList) isNumberedList = false  // Mutually exclusive
                Log.d(TAG, "Bullet list toggled: $isBulletList")
                onBulletListToggled?.invoke(isBulletList)
                return true
            }
            numberedListRect.contains(px, py) -> {
                isNumberedList = !isNumberedList
                if (isNumberedList) isBulletList = false  // Mutually exclusive
                Log.d(TAG, "Numbered list toggled: $isNumberedList")
                onNumberedListToggled?.invoke(isNumberedList)
                return true
            }
            col1ButtonRect.contains(px, py) -> {
                currentColumns = 1
                Log.d(TAG, "Columns set to 1")
                onColumnsChanged?.invoke(1)
                return true
            }
            col2ButtonRect.contains(px, py) -> {
                currentColumns = 2
                Log.d(TAG, "Columns set to 2")
                onColumnsChanged?.invoke(2)
                return true
            }
            col3ButtonRect.contains(px, py) -> {
                currentColumns = 3
                Log.d(TAG, "Columns set to 3")
                onColumnsChanged?.invoke(3)
                return true
            }
        }
        
        return true  // Consume tap on menu area
    }
    
    /**
     * Update the current font size display (e.g., when changed externally).
     */
    fun setCurrentFontSize(size: Float) {
        fontSize = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }
    
    /**
     * Set the formatting state (called by widget to sync state).
     */
    fun setFormattingState(bold: Boolean, italic: Boolean, underline: Boolean, wrap: Boolean) {
        isBold = bold
        isItalic = italic
        isUnderline = underline
        isTextWrap = wrap
    }

    private fun drawColumnButton(canvas: Canvas, rect: RectF, columns: Int, isActive: Boolean, isHovered: Boolean) {
        // Draw background
        val bgPaint = when {
            isActive -> buttonActivePaint
            isHovered -> buttonHoverPaint
            else -> buttonPaint
        }
        canvas.drawRoundRect(rect, 4f, 4f, bgPaint)

        // Draw Icon: "continuous columns of three horizontal lines"
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            strokeCap = Paint.Cap.BUTT // Sharp ends for cleaner column look
        }

        val padding = 6f
        val availableWidth = rect.width() - (padding * 2)
        val availableHeight = rect.height() - (padding * 2)

        val gapSize = 3f // Gap between columns
        // Width of a single column bar
        val colWidth = (availableWidth - (gapSize * (columns - 1))) / columns

        val startX = rect.left + padding
        val startY = rect.top + padding

        // Draw 3 horizontal lines (rows)
        val lineCount = 3
        val lineHeight = 2f // Thickness of line
        // Space lines evenly vertically
        val lineSpacing = (availableHeight - (lineCount * lineHeight)) / (lineCount - 1)

        for (row in 0 until lineCount) {
            val y = startY + row * (lineHeight + lineSpacing) + (lineHeight/2)

            for (col in 0 until columns) {
                val x = startX + col * (colWidth + gapSize)
                canvas.drawLine(x, y, x + colWidth, y, iconPaint)
            }
        }
    }
    
    /**
     * Check if currently dragging.
     */
    fun isDragging(): Boolean = isDragging
    
    /**
     * Draw the formatting menu.
     */
    fun draw(canvas: Canvas) {
        if (!isVisible) return
        
        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
        
        // Shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#60000000")
        }
        canvas.drawRoundRect(
            menuBounds.left + 4f, menuBounds.top + 4f,
            menuBounds.right + 4f, menuBounds.bottom + 4f,
            CORNER_RADIUS, CORNER_RADIUS, shadowPaint
        )
        
        // Background
        canvas.drawRoundRect(menuBounds, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(menuBounds, CORNER_RADIUS, CORNER_RADIUS, borderPaint)
        
        // Title bar
        canvas.drawRoundRect(
            menuX, menuY, menuX + MENU_WIDTH, menuY + TITLE_HEIGHT,
            CORNER_RADIUS, CORNER_RADIUS, titleBarPaint
        )
        
        // Title text
        canvas.drawText("Format", menuX + PADDING + 4f, menuY + TITLE_HEIGHT / 2 + 5f, titleTextPaint)
        
        // Close button
        val closePaint = if (hoveredButton == "close") closeButtonHoverPaint else closeButtonPaint
        canvas.drawRoundRect(closeButtonRect, 4f, 4f, closePaint)
        val cx = closeButtonRect.centerX()
        val cy = closeButtonRect.centerY()
        val offset = CLOSE_BUTTON_SIZE * 0.25f
        canvas.drawLine(cx - offset, cy - offset, cx + offset, cy + offset, xPaint)
        canvas.drawLine(cx + offset, cy - offset, cx - offset, cy + offset, xPaint)
        
        // Bold button
        drawButton(canvas, boldButtonRect, "B", isBold, hoveredButton == "bold", boldTextPaint)
        
        // Italic button
        drawButton(canvas, italicButtonRect, "I", isItalic, hoveredButton == "italic", italicTextPaint)
        
        // Underline button
        drawButton(canvas, underlineButtonRect, "U", isUnderline, hoveredButton == "underline", underlineTextPaint)
        
        // Wrap button
        drawWrapButton(canvas, wrapButtonRect, isTextWrap, hoveredButton == "wrap")
        
        // Font size minus button
        drawButton(canvas, fontSizeMinusRect, "−", false, hoveredButton == "fontMinus", textPaint)
        
        // Font size input display
        canvas.drawRoundRect(fontSizeInputRect, 4f, 4f, inputBackgroundPaint)
        val fontSizeText = fontSize.toInt().toString()
        canvas.drawText(
            fontSizeText,
            fontSizeInputRect.centerX(),
            fontSizeInputRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2,
            textPaint
        )
        
        // Font size plus button
        drawButton(canvas, fontSizePlusRect, "+", false, hoveredButton == "fontPlus", textPaint)
        
        // Bullet list button
        drawBulletListButton(canvas, bulletListRect, isBulletList, hoveredButton == "bullet")
        
        // Numbered list button
        drawNumberedListButton(canvas, numberedListRect, isNumberedList, hoveredButton == "numbered")

        // Draw Column Buttons (Row 3)
        drawColumnButton(canvas, col1ButtonRect, 1, currentColumns == 1, hoveredButton == "col1")
        drawColumnButton(canvas, col2ButtonRect, 2, currentColumns == 2, hoveredButton == "col2")
        drawColumnButton(canvas, col3ButtonRect, 3, currentColumns == 3, hoveredButton == "col3")
    }
    
    /**
     * Draw a standard button.
     */
    private fun drawButton(canvas: Canvas, rect: RectF, label: String, isActive: Boolean, isHovered: Boolean, paint: Paint) {
        val bgPaint = when {
            isActive -> buttonActivePaint
            isHovered -> buttonHoverPaint
            else -> buttonPaint
        }
        canvas.drawRoundRect(rect, 4f, 4f, bgPaint)
        
        canvas.drawText(
            label,
            rect.centerX(),
            rect.centerY() - (paint.descent() + paint.ascent()) / 2,
            paint
        )
    }
    
    /**
     * Draw the text wrap button with icon.
     */
    private fun drawWrapButton(canvas: Canvas, rect: RectF, isActive: Boolean, isHovered: Boolean) {
        val bgPaint = when {
            isActive -> buttonActivePaint
            isHovered -> buttonHoverPaint
            else -> buttonPaint
        }
        canvas.drawRoundRect(rect, 4f, 4f, bgPaint)
        
        // Draw wrap icon: lines with arrow wrapping around
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        
        val cx = rect.centerX()
        val cy = rect.centerY()
        val lineWidth = rect.width() * 0.35f
        val lineSpacing = 6f
        
        // Top line (full)
        canvas.drawLine(cx - lineWidth, cy - lineSpacing, cx + lineWidth, cy - lineSpacing, iconPaint)
        
        if (isActive) {
            // Wrap mode: show wrapped line with arrow
            canvas.drawLine(cx - lineWidth, cy + lineSpacing, cx + lineWidth * 0.5f, cy + lineSpacing, iconPaint)
            
            // Curved arrow indicating wrap
            val arrowPaint = Paint(iconPaint).apply { style = Paint.Style.FILL }
            canvas.drawLine(cx + lineWidth, cy - lineSpacing + 3f, cx + lineWidth, cy + lineSpacing - 3f, iconPaint)
            // Arrow head
            canvas.drawLine(cx + lineWidth - 3f, cy + lineSpacing - 6f, cx + lineWidth, cy + lineSpacing - 3f, iconPaint)
        } else {
            // No wrap mode: line extending beyond with arrow
            canvas.drawLine(cx - lineWidth, cy + lineSpacing, cx + lineWidth + 8f, cy + lineSpacing, iconPaint)
            // Arrow pointing right
            canvas.drawLine(cx + lineWidth + 4f, cy + lineSpacing - 4f, cx + lineWidth + 8f, cy + lineSpacing, iconPaint)
            canvas.drawLine(cx + lineWidth + 4f, cy + lineSpacing + 4f, cx + lineWidth + 8f, cy + lineSpacing, iconPaint)
        }
    }
    
    /**
     * Draw the bullet list button with icon.
     */
    private fun drawBulletListButton(canvas: Canvas, rect: RectF, isActive: Boolean, isHovered: Boolean) {
        val bgPaint = when {
            isActive -> buttonActivePaint
            isHovered -> buttonHoverPaint
            else -> buttonPaint
        }
        canvas.drawRoundRect(rect, 4f, 4f, bgPaint)
        
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
        }
        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        
        val cx = rect.centerX()
        val cy = rect.centerY()
        val bulletRadius = 2.5f
        val lineLength = 12f
        val spacing = 7f
        
        // Three bullet points with lines
        for (i in -1..1) {
            val y = cy + i * spacing
            canvas.drawCircle(cx - 8f, y, bulletRadius, bulletPaint)
            canvas.drawLine(cx - 3f, y, cx + lineLength - 3f, y, iconPaint)
        }
    }
    
    /**
     * Draw the numbered list button with icon.
     */
    private fun drawNumberedListButton(canvas: Canvas, rect: RectF, isActive: Boolean, isHovered: Boolean) {
        val bgPaint = when {
            isActive -> buttonActivePaint
            isHovered -> buttonHoverPaint
            else -> buttonPaint
        }
        canvas.drawRoundRect(rect, 4f, 4f, bgPaint)
        
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
        }
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10f
            textAlign = Paint.Align.CENTER
        }
        
        val cx = rect.centerX()
        val cy = rect.centerY()
        val lineLength = 12f
        val spacing = 7f
        
        // Three numbered lines
        val numbers = listOf("1", "2", "3")
        for (i in -1..1) {
            val y = cy + i * spacing
            canvas.drawText(numbers[i + 1], cx - 8f, y + 3.5f, numberPaint)
            canvas.drawLine(cx - 1f, y, cx + lineLength - 1f, y, iconPaint)
        }
    }
}
