package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log

/**
 * Abstract base class for all widgets in the Everyday glasses app.
 * 
 * Provides common functionality:
 * - Position and size management (auto-updates bounds on change)
 * - Pin state (for persistence during SLEEP mode)
 * - Fullscreen mode with save/restore of bounds
 * - Border hover detection and drawing
 * - Pin button and fullscreen button at corners
 * - Basic drag (move) support
 * 
 * Subclasses extend this to add specific features like:
 * - TextBoxWidget: text editing, resize handle, delete button, scrolling
 * - StatusBarWidget: status display, auto-sizing
 * - ScreenMirrorWidget: video streaming, resize handle
 */
abstract class BaseWidget(
    x: Float,
    y: Float,
    widgetWidth: Float,
    widgetHeight: Float
) {
    companion object {
        private const val TAG = "BaseWidget"
        
        // Common sizes for all widgets
        const val BORDER_WIDTH = 3f
        const val FULLSCREEN_BUTTON_SIZE = 28f
        const val MINIMIZE_BUTTON_SIZE = 28f
        const val PIN_BUTTON_SIZE = 28f
        const val CLOSE_BUTTON_SIZE = 28f
        const val RESIZE_HANDLE_SIZE = 40f
        const val BORDER_HIT_AREA = 12f
        const val BUTTON_SPACING = 6f
    }
    
    // Properties with reactive setters to ensure bounds are always in sync
    var x: Float = x
        set(value) {
            if (field != value) {
                field = value
                updateBaseBounds()
            }
        }
        
    var y: Float = y
        set(value) {
            if (field != value) {
                field = value
                updateBaseBounds()
            }
        }
        
    var widgetWidth: Float = widgetWidth
        set(value) {
            if (field != value) {
                field = value
                updateBaseBounds()
            }
        }
        
    var widgetHeight: Float = widgetHeight
        set(value) {
            if (field != value) {
                field = value
                updateBaseBounds()
            }
        }
    
    // ==================== Base State ====================
    
    enum class BaseState {
        IDLE,           // Normal display
        HOVER_CONTENT,  // Cursor over content area
        HOVER_BORDER,   // Cursor on border (shows buttons, can drag)
        HOVER_RESIZE,   // Cursor on resize handle
        MOVING,         // Being dragged to new position
        RESIZING        // Being resized
    }
    
    enum class BaseHitArea {
        NONE,
        CONTENT,
        BORDER,
        FULLSCREEN_BUTTON,
        PIN_BUTTON,
        MINIMIZE_BUTTON,
        CLOSE_BUTTON,
        RESIZE_HANDLE
    }
    
    // Current base state
    var baseState = BaseState.IDLE

    // Tracks whether the cursor is in the widget's border interaction zone
    // (border band, resize handle, or window buttons).
    // Border visibility itself is derived separately so every widget can show
    // the hover border when any part of the widget is hovered.
    private var _isBorderHovered = false

    /**
     * Whether the cursor is currently over the widget's border interaction zone.
     * Use shouldShowBorder() for general hover-border visibility decisions.
     */
    val isBorderHovered: Boolean
        get() = _isBorderHovered

    // Z-order for unified drawing order (higher = drawn on top)
    var zOrder: Int = 0
    
    // Pin state - pinned widgets remain visible during SLEEP mode
    var isPinned = true  // Default to pinned on creation
    
    // Fullscreen state
    var isFullscreen = false
        protected set

    // Minimize state
    var isMinimized = false
        protected set

    // Label for minimized state
    open val minimizeLabel: String = "?"

    open val minWidth = 64f
    open val minHeight = 64f
    
    // Saved position for fullscreen restore
    protected var savedX = 0f
    protected var savedY = 0f
    protected var savedWidth = 0f
    protected var savedHeight = 0f

    // Saved position for minimize restore
    var savedMinX = 0f
    var savedMinY = 0f
    var savedMinWidth = 0f
    var savedMinHeight = 0f

    // Track if widget was fullscreen before being minimized
    // This allows proper restoration to fullscreen when un-minimizing
    var wasFullscreenBeforeMinimize = false
        protected set
    
    // ==================== Callbacks ====================

    var onFullscreenToggled: ((Boolean) -> Unit)? = null
    var onMinimizeToggled: ((Boolean) -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null
    
    // ==================== Paints ====================
    
    protected val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }
    
    protected val hoverBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
        color = Color.parseColor("#6666AA")
    }
    
    // Fullscreen button paints
    protected val fullscreenButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4488AA")
    }
    
    protected val fullscreenIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Minimize button paints
    protected val minimizeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4488AA")
    }

    protected val minimizeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    protected val minimizeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    
    // Pin button paints
    protected val pinButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666688")  // Neutral gray when unpinned
    }
    
    protected val pinButtonPinnedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44AA66")  // Green when pinned
    }
    
    protected val pinIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    protected val pinIconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    protected val resizeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888899")
        strokeWidth = 2f
    }

    // Close button paints
    protected val closeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA4444")  // Red color for close button
    }

    protected val closeXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // ==================== Bounds ====================
    
    protected val widgetBounds = RectF()
    protected val contentBounds = RectF()
    protected val closeButtonBounds = RectF()
    protected val fullscreenButtonBounds = RectF()
    protected val minimizeButtonBounds = RectF()
    protected val pinButtonBounds = RectF()
    protected val resizeHandleBounds = RectF()

    init {
        // IMPORTANT: do NOT call open functions from base init
        updateCommonBounds()
    }

    // Non-open: safe to call during base construction
    private fun updateCommonBounds() {
        widgetBounds.set(x, y, x + widgetWidth, y + widgetHeight)

        val padding = BORDER_WIDTH + 8f
        contentBounds.set(
            x + padding,
            y + padding,
            x + widgetWidth - padding,
            y + widgetHeight - padding
        )

        // Close button at top-right corner (rightmost position)
        closeButtonBounds.set(
            x + widgetWidth - CLOSE_BUTTON_SIZE / 2,
            y - CLOSE_BUTTON_SIZE / 2,
            x + widgetWidth + CLOSE_BUTTON_SIZE / 2,
            y + CLOSE_BUTTON_SIZE / 2
        )

        // Fullscreen button to the left of close button
        fullscreenButtonBounds.set(
            closeButtonBounds.left - BUTTON_SPACING - FULLSCREEN_BUTTON_SIZE,
            y - FULLSCREEN_BUTTON_SIZE / 2,
            closeButtonBounds.left - BUTTON_SPACING,
            y + FULLSCREEN_BUTTON_SIZE / 2
        )

        // Minimize button to the left of fullscreen button
        minimizeButtonBounds.set(
            fullscreenButtonBounds.left - BUTTON_SPACING - MINIMIZE_BUTTON_SIZE,
            y - MINIMIZE_BUTTON_SIZE / 2,
            fullscreenButtonBounds.left - BUTTON_SPACING,
            y + MINIMIZE_BUTTON_SIZE / 2
        )

        // Pin button at top-left corner
        pinButtonBounds.set(
            x - PIN_BUTTON_SIZE / 2,
            y - PIN_BUTTON_SIZE / 2,
            x + PIN_BUTTON_SIZE / 2,
            y + PIN_BUTTON_SIZE / 2
        )

        resizeHandleBounds.set(
            x + widgetWidth - RESIZE_HANDLE_SIZE,
            y + widgetHeight - RESIZE_HANDLE_SIZE,
            x + widgetWidth,
            y + widgetHeight
        )
    }

    // Still overridable for subclasses, but now safe because it won't be called from init
    protected open fun updateBaseBounds() {
        updateCommonBounds()
    }
    
    fun getBounds(): RectF = widgetBounds
    
    // ==================== State Helpers ====================

    /**
     * Check if widget is in any hover or active state (including content).
     */
    protected open fun isHovering(): Boolean {
        return baseState == BaseState.HOVER_CONTENT ||
               baseState == BaseState.HOVER_BORDER ||
               baseState == BaseState.HOVER_RESIZE ||
               baseState == BaseState.MOVING ||
               baseState == BaseState.RESIZING
    }

    /**
     * Check if cursor is hovering over the border area (not content).
     * DEPRECATED: Use isBorderHovered instead for visibility decisions.
     */
    protected open fun isHoveringBorder(): Boolean {
        return _isBorderHovered
    }

    /**
     * Check if border buttons should be shown.
     * Buttons remain a border-specific affordance even though the border itself
     * is now visible whenever the widget is hovered anywhere.
     */
    protected open fun shouldShowBorderButtons(): Boolean {
        return _isBorderHovered
    }

    /**
     * Check if the hover border should be shown.
     * This is homogeneous across widgets: hovering any part of the widget shell
     * or content reveals the border, while buttons stay border-specific.
     */
    fun shouldShowBorder(): Boolean {
        return when (baseState) {
            BaseState.HOVER_CONTENT,
            BaseState.HOVER_BORDER,
            BaseState.HOVER_RESIZE,
            BaseState.MOVING,
            BaseState.RESIZING -> true
            BaseState.IDLE -> false
        }
    }
    
    // ==================== Hit Testing ====================
    
    /**
     * Base hit test for common areas.
     * Returns BaseHitArea. Subclasses should override to add their own areas.
     */
    open fun baseHitTest(px: Float, py: Float): BaseHitArea {
        // Check buttons first
        // We always check buttons because they might extend outside the widget bounds
        // and we want them to "wake up" the widget.
        if (closeButtonBounds.contains(px, py)) {
            return BaseHitArea.CLOSE_BUTTON
        }
        if (fullscreenButtonBounds.contains(px, py)) {
            return BaseHitArea.FULLSCREEN_BUTTON
        }
        if (minimizeButtonBounds.contains(px, py)) {
            return BaseHitArea.MINIMIZE_BUTTON
        }
        if (pinButtonBounds.contains(px, py)) {
            return BaseHitArea.PIN_BUTTON
        }

        // Check resize handle (if not fullscreen or minimized)
        if (!isFullscreen && !isMinimized) {
            val expandedResizeBounds = RectF(
                resizeHandleBounds.left - 10f,
                resizeHandleBounds.top - 10f,
                resizeHandleBounds.right + 10f,
                resizeHandleBounds.bottom + 10f
            )
            if (expandedResizeBounds.contains(px, py)) {
                return BaseHitArea.RESIZE_HANDLE
            }
        }
        
        // Check content area
        if (contentBounds.contains(px, py)) {
            return BaseHitArea.CONTENT
        }
        
        // Check border (expanded hit area)
        val expandedBounds = RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) {
            return BaseHitArea.BORDER
        }
        
        return BaseHitArea.NONE
    }
    
    /**
     * Check if a point is within the widget's interactive area.
     * Includes buttons when they're visible.
     */
    open fun containsPoint(px: Float, py: Float): Boolean {
        // Always include buttons in hit detection
        if (closeButtonBounds.contains(px, py)) return true
        if (fullscreenButtonBounds.contains(px, py)) return true
        if (minimizeButtonBounds.contains(px, py)) return true
        if (pinButtonBounds.contains(px, py)) return true

        if (!isFullscreen && !isMinimized) {
            val expandedResizeBounds = RectF(
                resizeHandleBounds.left - 10f,
                resizeHandleBounds.top - 10f,
                resizeHandleBounds.right + 10f,
                resizeHandleBounds.bottom + 10f
            )
            if (expandedResizeBounds.contains(px, py)) return true
        }
        
        val expandedBounds = RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        return expandedBounds.contains(px, py)
    }
    
    // ==================== Hover Update ====================

    /**
     * Compute the expanded border hit area.
     * Returns a RectF that extends BORDER_HIT_AREA pixels outside the widget bounds.
     */
    protected fun getBorderHitArea(): RectF {
        return RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
    }

    /**
     * Check if a point is in the border zone (between content and expanded bounds).
     * This includes the resize handle area.
     */
    protected fun isPointInBorderZone(px: Float, py: Float): Boolean {
        val expandedBounds = getBorderHitArea()

        // Must be within expanded bounds
        if (!expandedBounds.contains(px, py)) return false

        // Must NOT be in content area (border is the frame around content)
        if (contentBounds.contains(px, py)) return false

        return true
    }

    /**
     * Check if a point is over any border button.
     * Subclasses can override to add additional buttons.
     */
    protected open fun isPointOverBorderButton(px: Float, py: Float): Boolean {
        if (closeButtonBounds.contains(px, py)) return true
        if (fullscreenButtonBounds.contains(px, py)) return true
        if (minimizeButtonBounds.contains(px, py)) return true
        if (pinButtonBounds.contains(px, py)) return true

        // Resize handle counts as border
        if (!isFullscreen && !isMinimized) {
            val expandedResizeBounds = RectF(
                resizeHandleBounds.left - 10f,
                resizeHandleBounds.top - 10f,
                resizeHandleBounds.right + 10f,
                resizeHandleBounds.bottom + 10f
            )
            if (expandedResizeBounds.contains(px, py)) return true
        }

        return false
    }

    /**
     * UNIFIED hover update method.
     * Call this from subclasses' updateHover methods.
     * Sets both baseState and _isBorderHovered consistently.
     *
     * @param px Cursor X position
     * @param py Cursor Y position
     * @return The new BaseState
     */
    open fun updateHoverState(px: Float, py: Float): BaseState {
        // During drag operations, maintain current state but keep border visible
        if (baseState == BaseState.MOVING || baseState == BaseState.RESIZING) {
            _isBorderHovered = true
            return baseState
        }

        // Check if hovering any border button
        val overButton = isPointOverBorderButton(px, py)

        // Check if in border zone
        val inBorderZone = isPointInBorderZone(px, py)

        // Check content
        val inContent = contentBounds.contains(px, py)

        // Determine new state
        val newState = when {
            overButton -> BaseState.HOVER_BORDER
            inBorderZone -> BaseState.HOVER_BORDER
            inContent -> BaseState.HOVER_CONTENT
            else -> BaseState.IDLE
        }

        // UNIFIED RULE: Border is hovered if and only if we're in HOVER_BORDER state
        // (or HOVER_RESIZE, MOVING, RESIZING)
        _isBorderHovered = when (newState) {
            BaseState.HOVER_BORDER, BaseState.HOVER_RESIZE, BaseState.MOVING, BaseState.RESIZING -> true
            else -> false
        }

        // Also check resize handle specifically
        if (!isFullscreen && !isMinimized) {
            val expandedResizeBounds = RectF(
                resizeHandleBounds.left - 10f,
                resizeHandleBounds.top - 10f,
                resizeHandleBounds.right + 10f,
                resizeHandleBounds.bottom + 10f
            )
            if (expandedResizeBounds.contains(px, py)) {
                baseState = BaseState.HOVER_RESIZE
                _isBorderHovered = true
                return BaseState.HOVER_RESIZE
            }
        }

        baseState = newState
        return newState
    }

    /**
     * Update hover state from cursor position.
     * Subclasses can override this to handle specific hover logic (like TextBoxWidget's edit mode).
     * Default implementation calls updateHoverState.
     */
    open fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)
    }

    /**
     * Clear hover state - call when cursor leaves the widget entirely.
     */
    fun clearHoverState() {
        if (baseState != BaseState.MOVING && baseState != BaseState.RESIZING) {
            baseState = BaseState.IDLE
            _isBorderHovered = false
        }
    }

    /**
     * Update base hover state from cursor position.
     * DEPRECATED: Use updateHoverState instead for unified behavior.
     */
    protected open fun updateBaseHover(px: Float, py: Float): BaseState {
        return updateHoverState(px, py)
    }
    
    // ==================== Fullscreen ====================
    
    fun toggleFullscreen() {
        if (isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }
    
    protected open fun enterFullscreen() {
        if (isFullscreen) return
        
        // Save current position/size
        savedX = x
        savedY = y
        savedWidth = widgetWidth
        savedHeight = widgetHeight
        
        isFullscreen = true
        Log.d(TAG, "Entering fullscreen, saved bounds: ($savedX, $savedY) ${savedWidth}x${savedHeight}")
        onFullscreenToggled?.invoke(true)
    }
    
    protected open fun exitFullscreen() {
        if (!isFullscreen) return
        
        // Restore saved position/size - using properties triggers updateBaseBounds()
        x = savedX
        y = savedY
        widgetWidth = savedWidth
        widgetHeight = savedHeight
        
        isFullscreen = false
        Log.d(TAG, "Exiting fullscreen, restored bounds: ($x, $y) ${widgetWidth}x${widgetHeight}")
        onFullscreenToggled?.invoke(false)
    }
    
    /**
     * Set fullscreen bounds (called by container after entering fullscreen).
     */
    open fun setFullscreenBounds(screenWidth: Float, screenHeight: Float) {
        if (!isFullscreen) return
        
        // Using properties triggers updateBaseBounds()
        x = 0f
        y = 0f
        widgetWidth = screenWidth
        widgetHeight = screenHeight
        Log.d(TAG, "Set fullscreen bounds: ${widgetWidth}x${widgetHeight}")
    }

    // ==================== Minimize ====================

    open fun toggleMinimize() {
        if (isMinimized) {
            restore()
        } else {
            minimize()
        }
    }

    open fun minimize() {
        if (isMinimized) return

        // Track if we're minimizing from fullscreen
        wasFullscreenBeforeMinimize = isFullscreen

        if (isFullscreen) {
            // When minimizing from fullscreen, save the PRE-FULLSCREEN bounds
            // (which are stored in savedX/Y/Width/Height), not the current fullscreen bounds
            savedMinX = savedX
            savedMinY = savedY
            savedMinWidth = savedWidth
            savedMinHeight = savedHeight

            // Exit fullscreen mode so other widgets become visible
            // Note: We do this manually rather than calling exitFullscreen() to avoid
            // triggering the onFullscreenToggled callback which would cause UI issues
            isFullscreen = false
            Log.d(TAG, "Minimizing from fullscreen, saved pre-fullscreen bounds: ($savedMinX, $savedMinY) ${savedMinWidth}x${savedMinHeight}")
        } else {
            // Normal case: save current bounds
            savedMinX = x
            savedMinY = y
            savedMinWidth = widgetWidth
            savedMinHeight = widgetHeight
            Log.d(TAG, "Minimized, saved bounds: ($savedMinX, $savedMinY) ${savedMinWidth}x${savedMinHeight}")
        }

        isMinimized = true
        widgetWidth = 64f
        widgetHeight = 64f
        // Position will be handled by container

        onMinimizeToggled?.invoke(true)
    }

    protected open fun restore() {
        if (!isMinimized) return

        // First restore to the saved non-fullscreen bounds
        x = savedMinX
        y = savedMinY
        widgetWidth = savedMinWidth
        widgetHeight = savedMinHeight

        isMinimized = false
        Log.d(TAG, "Restored from minimize: ($x, $y) ${widgetWidth}x${widgetHeight}, wasFullscreen=$wasFullscreenBeforeMinimize")

        // If we were fullscreen before minimizing, re-enter fullscreen
        // The container will handle setting the fullscreen bounds via the callback
        if (wasFullscreenBeforeMinimize) {
            // Re-save the bounds we just restored (they are the pre-fullscreen bounds)
            savedX = x
            savedY = y
            savedWidth = widgetWidth
            savedHeight = widgetHeight

            isFullscreen = true
            wasFullscreenBeforeMinimize = false
            Log.d(TAG, "Re-entering fullscreen after restore")
        }

        onMinimizeToggled?.invoke(false)
    }
    
    // ==================== Drag / Move ====================
    
    /**
     * Start a drag operation (move or resize).
     * Sets the appropriate state.
     */
    open fun startDrag(isResize: Boolean) {
        baseState = if (isResize) BaseState.RESIZING else BaseState.MOVING
    }

    /**
     * Handle drag (move) for the widget.
     * Subclasses can override to add resize support.
     */
    open fun onDrag(dx: Float, dy: Float, screenWidth: Float, screenHeight: Float) {
        if (isFullscreen) return

        // If the container begins dragging after a long-press on the border,
        // baseState will typically still be HOVER_BORDER. Promote it to MOVING.
        if (baseState == BaseState.HOVER_BORDER) {
            baseState = BaseState.MOVING
        }
        // If container begins dragging after long press on resize handle
        if (baseState == BaseState.HOVER_RESIZE) {
            baseState = BaseState.RESIZING
        }

        when (baseState) {
            BaseState.MOVING -> {
                // Handle case where widget is larger than screen (avoid invalid coerceIn range)
                val maxX = (screenWidth - widgetWidth).coerceAtLeast(0f)
                val maxY = (screenHeight - widgetHeight).coerceAtLeast(0f)
                val newX = (x + dx).coerceIn(0f, maxX)
                val newY = (y + dy).coerceIn(0f, maxY)
                x = newX
                y = newY
            }
            BaseState.RESIZING -> {
                // Updating properties triggers updateBaseBounds()
                val maxWidth = (screenWidth - x).coerceAtLeast(minWidth)
                val maxHeight = (screenHeight - y).coerceAtLeast(minHeight)
                widgetWidth = (widgetWidth + dx).coerceIn(minWidth, maxWidth)
                widgetHeight = (widgetHeight + dy).coerceIn(minHeight, maxHeight)
            }
            else -> {}
        }
    }
    
    /**
     * End drag operation.
     * Clears border hover state since cursor position is unknown after drag.
     */
    open fun onDragEnd() {
        if (baseState == BaseState.MOVING || baseState == BaseState.RESIZING) {
            baseState = BaseState.IDLE
            _isBorderHovered = false
        }
    }
    
    /**
     * Set position directly (used for restoring state).
     */
    fun setPosition(newX: Float, newY: Float) {
        // Setting properties triggers updateBaseBounds()
        x = newX
        y = newY
    }

    /**
     * Persistence currently restores widgets in windowed mode.
     * When a widget is fullscreen, save the pre-fullscreen bounds so reopening
     * the app restores the same windowed position/size that exiting fullscreen would use.
     */
    open fun getPersistenceBounds(): RectF {
        val hasSavedWindowedBounds = isFullscreen && savedWidth > 0f && savedHeight > 0f
        return if (hasSavedWindowedBounds) {
            RectF(savedX, savedY, savedX + savedWidth, savedY + savedHeight)
        } else {
            RectF(x, y, x + widgetWidth, y + widgetHeight)
        }
    }
    
    // ==================== Drawing ====================
    
    /**
     * Draw the fullscreen icon inside the fullscreen button.
     */
    protected fun drawFullscreenIcon(canvas: Canvas) {
        val cx = fullscreenButtonBounds.centerX()
        val cy = fullscreenButtonBounds.centerY()
        val iconSize = FULLSCREEN_BUTTON_SIZE * 0.3f
        
        if (isFullscreen) {
            // Draw restore icon (two overlapping squares)
            val smallOffset = iconSize * 0.3f
            
            // Back square (top-right)
            canvas.drawRect(
                cx - iconSize + smallOffset,
                cy - iconSize,
                cx + iconSize,
                cy + iconSize - smallOffset,
                fullscreenIconPaint
            )
            
            // Front square (bottom-left) - filled background first
            val frontFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4488AA")
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                cx - iconSize,
                cy - iconSize + smallOffset,
                cx + iconSize - smallOffset,
                cy + iconSize,
                frontFillPaint
            )
            canvas.drawRect(
                cx - iconSize,
                cy - iconSize + smallOffset,
                cx + iconSize - smallOffset,
                cy + iconSize,
                fullscreenIconPaint
            )
        } else {
            // Draw fullscreen icon (square with arrows pointing outward)
            canvas.drawRect(
                cx - iconSize,
                cy - iconSize,
                cx + iconSize,
                cy + iconSize,
                fullscreenIconPaint
            )
            
            // Draw corner arrows
            val arrowSize = iconSize * 0.5f
            canvas.drawLine(cx - iconSize, cy - iconSize, cx - iconSize - arrowSize, cy - iconSize - arrowSize, fullscreenIconPaint)
            canvas.drawLine(cx + iconSize, cy - iconSize, cx + iconSize + arrowSize, cy - iconSize - arrowSize, fullscreenIconPaint)
            canvas.drawLine(cx - iconSize, cy + iconSize, cx - iconSize - arrowSize, cy + iconSize + arrowSize, fullscreenIconPaint)
            canvas.drawLine(cx + iconSize, cy + iconSize, cx + iconSize + arrowSize, cy + iconSize + arrowSize, fullscreenIconPaint)
        }
    }
    
    /**
     * Draw the pin icon - cloud when unpinned, pushpin when pinned.
     */
    protected fun drawPinIcon(canvas: Canvas) {
        val cx = pinButtonBounds.centerX()
        val cy = pinButtonBounds.centerY()
        val iconSize = PIN_BUTTON_SIZE * 0.35f
        
        if (isPinned) {
            // Draw a pin icon (pushpin shape)
            val path = Path()
            // Pin head (circle at top)
            canvas.drawCircle(cx, cy - iconSize * 0.3f, iconSize * 0.35f, pinIconFillPaint)
            // Pin body (triangle pointing down)
            path.moveTo(cx - iconSize * 0.25f, cy - iconSize * 0.1f)
            path.lineTo(cx + iconSize * 0.25f, cy - iconSize * 0.1f)
            path.lineTo(cx, cy + iconSize * 0.6f)
            path.close()
            canvas.drawPath(path, pinIconFillPaint)
            // Pin stick
            canvas.drawLine(cx, cy + iconSize * 0.6f, cx, cy + iconSize * 0.9f, pinIconPaint)
        } else {
            // Draw a cloud icon (unpinned = floating/ephemeral)
            // Main cloud body - three overlapping circles
            val baseY = cy + iconSize * 0.15f
            // Left bump
            canvas.drawCircle(cx - iconSize * 0.35f, baseY, iconSize * 0.35f, pinIconPaint)
            // Center bump (larger, higher)
            canvas.drawCircle(cx, cy - iconSize * 0.1f, iconSize * 0.45f, pinIconPaint)
            // Right bump
            canvas.drawCircle(cx + iconSize * 0.35f, baseY, iconSize * 0.35f, pinIconPaint)
            // Bottom flat line
            canvas.drawLine(
                cx - iconSize * 0.6f, baseY + iconSize * 0.25f,
                cx + iconSize * 0.6f, baseY + iconSize * 0.25f,
                pinIconPaint
            )
        }
    }
    
    /**
     * Draw the pin button (at top-left corner).
     */
    protected fun drawPinButton(canvas: Canvas) {
        canvas.drawOval(pinButtonBounds, if (isPinned) pinButtonPinnedPaint else pinButtonPaint)
        drawPinIcon(canvas)
    }
    
    /**
     * Draw the fullscreen button (at top-right corner).
     */
    protected fun drawFullscreenButton(canvas: Canvas) {
        canvas.drawOval(fullscreenButtonBounds, fullscreenButtonPaint)
        drawFullscreenIcon(canvas)
    }

    /**
     * Draw the minimize button.
     */
    protected fun drawMinimizeButton(canvas: Canvas) {
        canvas.drawOval(minimizeButtonBounds, minimizeButtonPaint)
        // Draw minus sign
        val cx = minimizeButtonBounds.centerX()
        val cy = minimizeButtonBounds.centerY()
        val w = MINIMIZE_BUTTON_SIZE * 0.4f
        canvas.drawLine(cx - w, cy, cx + w, cy, minimizeIconPaint)
    }

    protected fun drawResizeHandle(canvas: Canvas) {
        if (!isFullscreen && !isMinimized && isHoveringBorder()) {
            val hx = resizeHandleBounds.right - 4f
            val hy = resizeHandleBounds.bottom - 4f
            canvas.drawLine(hx - 16f, hy, hx, hy - 16f, resizeHandlePaint)
            canvas.drawLine(hx - 11f, hy, hx, hy - 11f, resizeHandlePaint)
            canvas.drawLine(hx - 6f, hy, hx, hy - 6f, resizeHandlePaint)
        }
    }

    /**
     * Draw the close button (X) at top-right corner.
     */
    protected fun drawCloseButton(canvas: Canvas) {
        canvas.drawOval(closeButtonBounds, closeButtonPaint)
        val cx = closeButtonBounds.centerX()
        val cy = closeButtonBounds.centerY()
        val offset = CLOSE_BUTTON_SIZE * 0.25f
        // Draw X
        canvas.drawLine(cx - offset, cy - offset, cx + offset, cy + offset, closeXPaint)
        canvas.drawLine(cx + offset, cy - offset, cx - offset, cy + offset, closeXPaint)
    }

    /**
     * Draw all border buttons: pin, minimize, fullscreen, and close.
     * Called when shouldShowBorderButtons() returns true.
     */
    protected fun drawBorderButtons(canvas: Canvas) {
        drawPinButton(canvas)
        drawMinimizeButton(canvas)
        drawFullscreenButton(canvas)
        drawCloseButton(canvas)
    }

    /**
     * Draw the minimized widget (small square).
     */
    fun drawMinimized(canvas: Canvas) {
        if (!isHovering()) return

        canvas.drawRect(widgetBounds, backgroundPaint)
        canvas.drawRect(widgetBounds, hoverBorderPaint)
        
        val fm = minimizeTextPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val textOffset = (textHeight / 2) - fm.descent
        
        canvas.drawText(
            minimizeLabel,
            widgetBounds.centerX(),
            widgetBounds.centerY() + textOffset,
            minimizeTextPaint
        )
    }
    
    /**
     * Abstract method - subclasses must implement drawing.
     */
    abstract fun draw(canvas: Canvas)

    // ==================== Scrollbar Support ====================

    /**
     * Check if the coordinates are over the scrollbar (if any).
     */
    open fun isOverScrollbar(x: Float, y: Float): Boolean {
        return false
    }

    /**
     * Handle scrolling delta (e.g. from scroll wheel or gesture).
     */
    open fun onScroll(dy: Float) {
        // Default no-op
    }

    /**
     * Handle scrollbar dragging.
     * By default, this maps to scrolling, but subclasses can customize sensitivity or behavior.
     */
    open fun onScrollbarDrag(dy: Float) {
        onScroll(dy)
    }
}
