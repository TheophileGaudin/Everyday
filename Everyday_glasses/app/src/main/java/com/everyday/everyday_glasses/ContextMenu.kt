package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log

/**
 * A popup context menu for creating widgets.
 *
 * Shows at cursor position when double-tapping on empty space.
 * Menu items can be selected by moving cursor and tapping.
 * Supports submenus that appear to the right when hovering parent items.
 */
class ContextMenu(
    private val screenWidth: Float,
    private val screenHeight: Float
) {
    companion object {
        private const val TAG = "ContextMenu"

        private const val MENU_WIDTH = 250f
        private const val ITEM_HEIGHT = 48f
        private const val PADDING = 12f
        private const val CORNER_RADIUS = 8f
        private const val SUBMENU_OFFSET = 4f  // Horizontal gap between menu and submenu
    }

    data class MenuItem(
        val id: String,
        val label: String,
        val icon: String? = null,
        val submenu: List<SubMenuItem>? = null  // If non-null, this item has a submenu
    )

    data class SubMenuItem(
        val id: String,
        val label: String,
        val isEnabled: Boolean = true  // Grey if false, light blue if true (widget present)
    )

    private val items = mutableListOf<MenuItem>()
    private val itemRects = mutableListOf<RectF>()

    // Submenu state
    private var activeSubmenuIndex = -1  // Index of parent item whose submenu is showing
    private val submenuRects = mutableListOf<RectF>()
    private var submenuX = 0f
    private var submenuY = 0f
    private var submenuWidth = 0f
    private var submenuHeight = 0f
    private var hoveredSubmenuIndex = -1

    // Callback for updating submenu item states (e.g., widget presence)
    var onSubmenuWillShow: ((MenuItem) -> List<SubMenuItem>)? = null

    var isVisible = false
        private set

    private var menuX = 0f
    private var menuY = 0f
    private var menuHeight = 0f

    private var hoveredItemIndex = -1

    // Callbacks
    var onItemSelected: ((MenuItem) -> Unit)? = null
    var onSubmenuItemSelected: ((MenuItem, SubMenuItem) -> Unit)? = null
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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }

    // Submenu-specific paints
    private val enabledTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6699FF")  // Light blue for enabled/present widgets
        textSize = 24f
    }

    private val disabledTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")  // Grey for disabled/absent widgets
        textSize = 24f
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
    }

    init {
        // Default menu items (can be customized)
        addItem(MenuItem("create_textbox", "Create Text Box", "📝"))
        addItem(MenuItem("create_browser", "Open Browser", "🌐"))
        addItem(MenuItem("open_file", "Open...", "📂"))
        addItem(MenuItem(
            "toggle_widgets",
            "Toggle",
            "\uD83C\uDF9A\uFE0F",
            submenu = listOf(
                SubMenuItem("toggle_status", "System", false),
                SubMenuItem("toggle_location", "Location/Weather", false),
                SubMenuItem("toggle_calendar", "Calendar", false),
                SubMenuItem("toggle_speedometer", "Speedometer", false),
                SubMenuItem("toggle_finance", "Finance", false),
                SubMenuItem("toggle_news", "News", false),
                SubMenuItem("toggle_mirror", "Screen Mirror", false)
            )
        ))
        addItem(MenuItem("settings", "Settings", "⚙️"))
    }

    fun addItem(item: MenuItem) {
        items.add(item)
    }

    fun clearItems() {
        items.clear()
    }

    /**
     * Show the menu at the specified position.
     */
    fun show(x: Float, y: Float) {
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
        activeSubmenuIndex = -1
        hoveredSubmenuIndex = -1
        isVisible = true

        Log.d(TAG, "Menu shown at ($menuX, $menuY) with ${items.size} items")
    }

    /**
     * Hide the menu.
     */
    fun dismiss() {
        if (isVisible) {
            isVisible = false
            hoveredItemIndex = -1
            activeSubmenuIndex = -1
            hoveredSubmenuIndex = -1
            onDismissed?.invoke()
            Log.d(TAG, "Menu dismissed")
        }
    }

    /**
     * Show submenu for the item at the given index.
     */
    private fun showSubmenu(itemIndex: Int) {
        val item = items[itemIndex]
        if (item.submenu == null) {
            activeSubmenuIndex = -1
            return
        }

        // Get updated submenu items (allows dynamic state like widget presence)
        val submenuItems = onSubmenuWillShow?.invoke(item) ?: item.submenu

        // Update the item's submenu with fresh state
        items[itemIndex] = item.copy(submenu = submenuItems)

        activeSubmenuIndex = itemIndex
        hoveredSubmenuIndex = -1

        // Calculate submenu dimensions
        submenuWidth = MENU_WIDTH
        submenuHeight = submenuItems.size * ITEM_HEIGHT + PADDING * 2

        // Position submenu to the right of the parent item
        val parentRect = itemRects[itemIndex]
        submenuX = menuX + MENU_WIDTH + SUBMENU_OFFSET

        // If submenu would go off screen to the right, show it on the left
        if (submenuX + submenuWidth > screenWidth) {
            submenuX = menuX - submenuWidth - SUBMENU_OFFSET
        }

        // Vertical position: align with parent item, but keep on screen
        submenuY = (parentRect.top - PADDING).coerceIn(0f, screenHeight - submenuHeight)

        // Calculate submenu item rects
        submenuRects.clear()
        var itemY = submenuY + PADDING
        for (subItem in submenuItems) {
            val rect = RectF(
                submenuX + PADDING,
                itemY,
                submenuX + submenuWidth - PADDING,
                itemY + ITEM_HEIGHT - 4f
            )
            submenuRects.add(rect)
            itemY += ITEM_HEIGHT
        }

        Log.d(TAG, "Submenu shown for '${item.label}' with ${submenuItems.size} items")
    }

    /**
     * Hide the submenu.
     */
    private fun hideSubmenu() {
        activeSubmenuIndex = -1
        hoveredSubmenuIndex = -1
        submenuRects.clear()
    }

    /**
     * Update hover state based on cursor position.
     */
    fun updateHover(px: Float, py: Float) {
        if (!isVisible) return

        val prevHoveredIndex = hoveredItemIndex
        hoveredItemIndex = -1
        hoveredSubmenuIndex = -1

        // Check submenu first (if visible)
        if (activeSubmenuIndex >= 0) {
            val submenuBounds = RectF(submenuX, submenuY, submenuX + submenuWidth, submenuY + submenuHeight)
            if (submenuBounds.contains(px, py)) {
                // Cursor is in submenu
                for (i in submenuRects.indices) {
                    if (submenuRects[i].contains(px, py)) {
                        hoveredSubmenuIndex = i
                        break
                    }
                }
                // Keep parent item highlighted
                hoveredItemIndex = activeSubmenuIndex
                return
            }
        }

        // Check main menu items
        for (i in itemRects.indices) {
            if (itemRects[i].contains(px, py)) {
                hoveredItemIndex = i

                // If hovering a different item with submenu, show its submenu
                if (items[i].submenu != null) {
                    if (activeSubmenuIndex != i) {
                        showSubmenu(i)
                    }
                } else {
                    // Hovering item without submenu, hide any open submenu
                    if (activeSubmenuIndex >= 0) {
                        hideSubmenu()
                    }
                }
                return
            }
        }

        // Not hovering any main menu item
        // If cursor moved away from both menu and submenu, hide submenu
        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
        if (!menuBounds.contains(px, py) && activeSubmenuIndex >= 0) {
            val submenuBounds = RectF(submenuX, submenuY, submenuX + submenuWidth, submenuY + submenuHeight)
            if (!submenuBounds.contains(px, py)) {
                hideSubmenu()
            }
        }
    }

    /**
     * Handle tap at the given position.
     * Returns true if the tap was handled.
     */
    fun onTap(px: Float, py: Float): Boolean {
        if (!isVisible) return false

        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)

        // Check submenu tap first
        if (activeSubmenuIndex >= 0) {
            val submenuBounds = RectF(submenuX, submenuY, submenuX + submenuWidth, submenuY + submenuHeight)
            if (submenuBounds.contains(px, py)) {
                for (i in submenuRects.indices) {
                    if (submenuRects[i].contains(px, py)) {
                        val parentItem = items[activeSubmenuIndex]
                        val subItem = parentItem.submenu?.getOrNull(i)
                        if (subItem != null) {
                            Log.d(TAG, "Submenu item selected: ${subItem.label}")
                            onSubmenuItemSelected?.invoke(parentItem, subItem)
                            dismiss()
                            return true
                        }
                    }
                }
                return true  // Consume tap on submenu area
            }
        }

        // Check if tap is inside main menu
        if (!menuBounds.contains(px, py)) {
            // Tap outside both menus - dismiss
            dismiss()
            return true  // Consume the tap
        }

        // Check main menu item taps
        for (i in itemRects.indices) {
            if (itemRects[i].contains(px, py)) {
                val item = items[i]

                // If item has submenu, toggle it instead of selecting
                if (item.submenu != null) {
                    if (activeSubmenuIndex == i) {
                        hideSubmenu()
                    } else {
                        showSubmenu(i)
                    }
                    return true
                }

                Log.d(TAG, "Item selected: ${item.label}")
                onItemSelected?.invoke(item)
                dismiss()
                return true
            }
        }

        return true  // Consume tap on menu area
    }

    /**
     * Check if a point is within the menu area (including submenu if visible).
     */
    fun containsPoint(px: Float, py: Float): Boolean {
        if (!isVisible) return false

        val menuBounds = RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
        if (menuBounds.contains(px, py)) return true

        if (activeSubmenuIndex >= 0) {
            val submenuBounds = RectF(submenuX, submenuY, submenuX + submenuWidth, submenuY + submenuHeight)
            if (submenuBounds.contains(px, py)) return true
        }

        return false
    }

    /**
     * Draw the menu.
     */
    fun draw(canvas: Canvas) {
        if (!isVisible) return

        // Draw main menu
        drawMainMenu(canvas)

        // Draw submenu if active
        if (activeSubmenuIndex >= 0) {
            drawSubmenu(canvas)
        }
    }

    private fun drawMainMenu(canvas: Canvas) {
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
            val itemBgPaint = if (i == hoveredItemIndex) hoverPaint else itemPaint
            canvas.drawRoundRect(rect, 6f, 6f, itemBgPaint)

            // Icon
            val iconX = rect.left + 12f
            val iconY = rect.centerY() + iconPaint.textSize / 3f
            if (item.icon != null) {
                canvas.drawText(item.icon, iconX, iconY, iconPaint)
            }

            // Label
            val labelX = rect.left + (if (item.icon != null) 48f else 12f)
            val labelY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(item.label, labelX, labelY, textPaint)

            // Arrow indicator for items with submenu
            if (item.submenu != null) {
                val arrowX = rect.right - 20f
                val arrowY = rect.centerY() - (arrowPaint.descent() + arrowPaint.ascent()) / 2f
                canvas.drawText("▶", arrowX, arrowY, arrowPaint)
            }
        }
    }

    private fun drawSubmenu(canvas: Canvas) {
        if (activeSubmenuIndex < 0) return

        val parentItem = items[activeSubmenuIndex]
        val submenuItems = parentItem.submenu ?: return

        val submenuBounds = RectF(submenuX, submenuY, submenuX + submenuWidth, submenuY + submenuHeight)

        // Shadow effect
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#40000000")
        }
        canvas.drawRoundRect(
            submenuBounds.left + 4f,
            submenuBounds.top + 4f,
            submenuBounds.right + 4f,
            submenuBounds.bottom + 4f,
            CORNER_RADIUS, CORNER_RADIUS,
            shadowPaint
        )

        // Background
        canvas.drawRoundRect(submenuBounds, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(submenuBounds, CORNER_RADIUS, CORNER_RADIUS, borderPaint)

        // Items
        for (i in submenuItems.indices) {
            if (i >= submenuRects.size) break

            val rect = submenuRects[i]
            val subItem = submenuItems[i]

            // Item background
            val itemBgPaint = if (i == hoveredSubmenuIndex) hoverPaint else itemPaint
            canvas.drawRoundRect(rect, 6f, 6f, itemBgPaint)

            // Label with color based on enabled state (widget presence)
            val labelPaint = if (subItem.isEnabled) enabledTextPaint else disabledTextPaint
            val labelX = rect.left + 12f
            val labelY = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(subItem.label, labelX, labelY, labelPaint)

            // Optional: draw a small indicator for enabled items
            if (subItem.isEnabled) {
                val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#6699FF")
                }
                canvas.drawCircle(rect.right - 16f, rect.centerY(), 4f, indicatorPaint)
            }
        }
    }
}
