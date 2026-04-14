package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log

/**
 * A popup context menu for text editing operations (paste, etc.).
 * 
 * Shows when user long-presses while editing a text field.
 * Menu items can be selected by moving cursor and tapping.
 */
class TextEditContextMenu(
    private val screenWidth: Float,
    private val screenHeight: Float
) {
    companion object {
        private const val TAG = "TextEditContextMenu"
        
        private const val MENU_WIDTH = 230f
        private const val ITEM_HEIGHT = 48f
        private const val PADDING = 12f
        private const val CORNER_RADIUS = 8f
    }
    
    data class MenuItem(
        val id: String,
        val label: String,
        val icon: String? = null,
        val enabled: Boolean = true
    )
    
    private val items = mutableListOf<MenuItem>()
    private val itemRects = mutableListOf<RectF>()
    
    var isVisible = false
        private set
    
    private var menuX = 0f
    private var menuY = 0f
    private var menuHeight = 0f
    
    private var hoveredItemIndex = -1
    
    // Stored clipboard content (set when showing menu)
    private var clipboardContent: String? = null
    
    // Whether there's selected text (for enabling cut/copy)
    private var hasSelection: Boolean = false
    
    // Callbacks
    var onCut: (() -> Unit)? = null
    var onCopy: (() -> Unit)? = null
    var onPaste: ((String) -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null
    var onFormatting: (() -> Unit)? = null
    var onDismissed: (() -> Unit)? = null
    
    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2a2a3e")
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4a4a6e")
    }
    
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3a3a5e")
    }
    
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5050AA")
    }
    
    private val disabledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2a2a3e")
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }
    
    private val disabledTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 24f
    }
    
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }
    
    private val disabledIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 28f
    }
    
    /**
     * Show the menu at the specified position with clipboard content.
     * @param x X position for menu
     * @param y Y position for menu
     * @param clipboard Current clipboard content (null if empty)
     * @param hasSelectedText Whether there is currently selected text
     */
    fun show(x: Float, y: Float, clipboard: String?, hasSelectedText: Boolean = false) {
        clipboardContent = clipboard
        hasSelection = hasSelectedText
        
        // Build menu items
        items.clear()
        
        // Cut - only enabled if text is selected
        items.add(MenuItem(
            id = "cut",
            label = "Cut",
            icon = "✂️",
            enabled = hasSelectedText
        ))
        
        // Copy - only enabled if text is selected
        items.add(MenuItem(
            id = "copy",
            label = "Copy",
            icon = "📄",
            enabled = hasSelectedText
        ))
        
        // Paste - only enabled if clipboard has content
        items.add(MenuItem(
            id = "paste",
            label = "Paste",
            icon = "📋",
            enabled = !clipboard.isNullOrEmpty()
        ))
        
        // Select All - always enabled if there's text
        items.add(MenuItem(
            id = "selectall",
            label = "Select All",
            icon = "📝",
            enabled = true  // Will be disabled if no text, handled in widget
        ))
        
        // Formatting - always enabled
        items.add(MenuItem(
            id = "formatting",
            label = "Formatting",
            icon = "🔤",
            enabled = true
        ))
        
        menuHeight = items.size * ITEM_HEIGHT + PADDING * 2
        
        // Position menu, keeping it on screen
        menuX = x.coerceIn(0f, screenWidth - MENU_WIDTH)
        menuY = y.coerceIn(0f, screenHeight - menuHeight)
        
        // Calculate item rects
        itemRects.clear()
        var itemY = menuY + PADDING
        for (item in items) {
            val rect = RectF(
                menuX + PADDING,
                itemY,
                menuX + MENU_WIDTH - PADDING,
                itemY + ITEM_HEIGHT - 4f
            )
            itemRects.add(rect)
            itemY += ITEM_HEIGHT
        }
        
        hoveredItemIndex = -1
        isVisible = true
        
        Log.d(TAG, "Menu shown at ($menuX, $menuY), clipboard has ${clipboard?.length ?: 0} chars")
    }
    
    /**
     * Hide the menu.
     */
    fun dismiss() {
        if (isVisible) {
            isVisible = false
            hoveredItemIndex = -1
            clipboardContent = null
            onDismissed?.invoke()
            Log.d(TAG, "Menu dismissed")
        }
    }
    
    /**
     * Update clipboard content while menu is visible.
     * This allows dynamically enabling the paste option when clipboard arrives.
     */
    fun updateClipboard(clipboard: String?) {
        if (!isVisible) return
        
        clipboardContent = clipboard
        
        // Update paste menu item enabled state
        val pasteIndex = items.indexOfFirst { it.id == "paste" }
        if (pasteIndex >= 0) {
            val oldItem = items[pasteIndex]
            val newItem = oldItem.copy(enabled = !clipboard.isNullOrEmpty())
            items[pasteIndex] = newItem
            Log.d(TAG, "Updated paste button: enabled=${newItem.enabled}")
        }
    }
    
    /**
     * Update hover state based on cursor position.
     */
    fun updateHover(px: Float, py: Float) {
        if (!isVisible) return
        
        hoveredItemIndex = -1
        for (i in itemRects.indices) {
            if (itemRects[i].contains(px, py) && items[i].enabled) {
                hoveredItemIndex = i
                break
            }
        }
    }
    
    /**
     * Handle tap at the given position.
     * Returns true if the tap was handled.
     */
    fun onTap(px: Float, py: Float): Boolean {
        if (!isVisible) return false
        
        // Check if tap is inside menu
        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
        
        if (!menuBounds.contains(px, py)) {
            // Tap outside - dismiss
            dismiss()
            return true  // Consume the tap
        }
        
        // Check item taps
        for (i in itemRects.indices) {
            if (itemRects[i].contains(px, py)) {
                val item = items[i]
                if (!item.enabled) {
                    Log.d(TAG, "Item disabled: ${item.label}")
                    return true  // Consume but don't act
                }
                
                Log.d(TAG, "Item selected: ${item.label}")
                
                when (item.id) {
                    "cut" -> {
                        onCut?.invoke()
                    }
                    "copy" -> {
                        onCopy?.invoke()
                    }
                    "paste" -> {
                        clipboardContent?.let { content ->
                            onPaste?.invoke(content)
                        }
                    }
                    "selectall" -> {
                        onSelectAll?.invoke()
                    }
                    "formatting" -> {
                        onFormatting?.invoke()
                    }
                }
                
                // Don't dismiss for formatting - it opens another menu
                if (item.id != "formatting") {
                    dismiss()
                } else {
                    dismiss()  // Still dismiss this menu, formatting menu opens separately
                }
                return true
            }
        }
        
        return true  // Consume tap on menu area
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
     * Draw the menu.
     */
    fun draw(canvas: Canvas) {
        if (!isVisible) return
        
        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
        
        // Shadow effect
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#40000000")
        }
        canvas.drawRoundRect(
            menuBounds.left + 4f,
            menuBounds.top + 4f,
            menuBounds.right + 4f,
            menuBounds.bottom + 4f,
            CORNER_RADIUS, CORNER_RADIUS,
            shadowPaint
        )
        
        // Background
        canvas.drawRoundRect(menuBounds, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(menuBounds, CORNER_RADIUS, CORNER_RADIUS, borderPaint)
        
        // Items
        for (i in items.indices) {
            val rect = itemRects[i]
            val item = items[i]
            
            // Item background
            val itemBgPaint = when {
                !item.enabled -> disabledPaint
                i == hoveredItemIndex -> hoverPaint
                else -> itemPaint
            }
            canvas.drawRoundRect(rect, 6f, 6f, itemBgPaint)
            
            // Icon
            val iconX = rect.left + 12f
            val iconY = rect.centerY() + iconPaint.textSize / 3f
            val iconP = if (item.enabled) iconPaint else disabledIconPaint
            if (item.icon != null) {
                canvas.drawText(item.icon, iconX, iconY, iconP)
            }
            
            // Label
            val labelX = rect.left + (if (item.icon != null) 48f else 12f)
            val labelY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            val textP = if (item.enabled) textPaint else disabledTextPaint
            canvas.drawText(item.label, labelX, labelY, textP)
        }
    }
}
