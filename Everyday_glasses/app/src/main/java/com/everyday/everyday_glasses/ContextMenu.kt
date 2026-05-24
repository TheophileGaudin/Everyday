package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Head-locked context menu optimized for the 640x480 glasses display.
 *
 * The menu uses drill-in pages instead of hover-open flyouts so shaky cursor
 * movement cannot accidentally close a submenu.
 */
class ContextMenu(
    private val screenWidth: Float,
    private val screenHeight: Float
) {
    companion object {
        private const val TAG = "ContextMenu"

        private const val MENU_WIDTH = 250f
        private const val ITEM_HEIGHT = 48f
        private const val HEADER_HEIGHT = 44f
        private const val PADDING = 12f
        private const val CORNER_RADIUS = 8f
        private const val MAX_VISIBLE_ROWS = 6
        private const val SCROLLBAR_WIDTH = 5f
    }

    data class MenuItem(
        val id: String,
        val label: String,
        val icon: String? = null,
        val submenu: List<SubMenuItem>? = null
    )

    data class SubMenuItem(
        val id: String,
        val label: String,
        val isEnabled: Boolean = true,
        val submenu: List<SubMenuItem>? = null
    )

    private data class Page(
        val title: String,
        val parentMenuItem: MenuItem?,
        val entries: List<Entry>,
        var scrollOffset: Float = 0f
    )

    private sealed class Entry {
        data class Menu(val item: MenuItem) : Entry()
        data class SubMenu(val item: SubMenuItem, val parent: MenuItem) : Entry()
        data class Back(val label: String = "Back") : Entry()
    }

    private val items = mutableListOf<MenuItem>()
    private val pageStack = mutableListOf<Page>()
    private val rowRects = mutableListOf<Pair<RectF, Entry>>()
    private val backRect = RectF()

    private var hoveredRowIndex = -1
    private var isBackHovered = false
    private var menuX = 0f
    private var menuY = 0f
    private var menuHeight = 0f
    private var contentTop = 0f
    private var visibleRows = MAX_VISIBLE_ROWS

    var onSubmenuWillShow: ((MenuItem) -> List<SubMenuItem>)? = null

    var isVisible = false
        private set

    var onItemSelected: ((MenuItem) -> Unit)? = null
    var onSubmenuItemSelected: ((MenuItem, SubMenuItem) -> Unit)? = null
    var onSubmenuItemDoubleTapped: ((MenuItem, SubMenuItem) -> Boolean)? = null
    var onDismissed: (() -> Unit)? = null

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

    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#202033")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }

    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        isFakeBoldText = true
    }

    private val activeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6699FF")
        textSize = 24f
    }

    private val inactiveTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
    }

    private val disabledTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#777777")
        textSize = 24f
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
    }

    private val scrollbarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#353550")
    }

    private val scrollbarThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8888CC")
    }

    init {
        addItem(MenuItem("layout_lock", "Lock", "lock_icon"))
        addItem(
            MenuItem(
                "create",
                "Create",
                "+",
                submenu = listOf(
                    SubMenuItem("create_textbox", "Text Box"),
                    SubMenuItem("create_browser", "Browser")
                )
            )
        )
        addItem(
            MenuItem(
                "toggle_widgets",
                "Widgets",
                null,
                submenu = listOf(
                    SubMenuItem("toggle_status", "System", false),
                    SubMenuItem("toggle_location", "Location/Weather", false),
                    SubMenuItem("toggle_calendar", "Calendar", false),
                    SubMenuItem("toggle_speedometer", "Speedometer", false),
                    SubMenuItem("toggle_finance", "Finance", false),
                    SubMenuItem("toggle_news", "News", false),
                    SubMenuItem("toggle_subtitle", "Subtitles", false),
                    SubMenuItem("toggle_mirror", "Screen Mirror", false)
                )
            )
        )
        addItem(
            MenuItem(
                "layouts",
                "Layouts",
                null,
                submenu = listOf(
                    SubMenuItem("layout_save", "Save"),
                    SubMenuItem("layout_save_as", "Save As...")
                )
            )
        )
        addItem(
            MenuItem(
                "files",
                "Files",
                null,
                submenu = listOf(SubMenuItem("open_file", "Open..."))
            )
        )
        addItem(MenuItem("settings", "Settings", null))
        addItem(MenuItem("close_app", "Close", null))
    }

    fun addItem(item: MenuItem) {
        items.add(item)
    }

    fun clearItems() {
        items.clear()
    }

    fun setLayoutLockState(isLocked: Boolean) {
        val lockItem = MenuItem(
            id = "layout_lock",
            label = if (isLocked) "Unlock" else "Lock",
            icon = if (isLocked) "unlock_icon" else "lock_icon"
        )
        val existingIndex = items.indexOfFirst { it.id == lockItem.id }
        if (existingIndex >= 0) {
            items[existingIndex] = lockItem
        } else {
            items.add(0, lockItem)
        }
        pageStack.firstOrNull()?.let { root ->
            pageStack[0] = root.copy(entries = items.map { Entry.Menu(it) })
            rebuildRows()
        }
    }

    fun show(x: Float, y: Float) {
        pageStack.clear()
        pageStack.add(Page("Menu", null, items.map { Entry.Menu(it) }))
        hoveredRowIndex = -1
        isBackHovered = false
        isVisible = true
        recalculateLayout(x, y)
        Log.d(TAG, "Menu shown at ($menuX, $menuY) with ${items.size} root items")
    }

    fun dismiss() {
        if (!isVisible) return

        isVisible = false
        hoveredRowIndex = -1
        isBackHovered = false
        pageStack.clear()
        rowRects.clear()
        backRect.setEmpty()
        onDismissed?.invoke()
        Log.d(TAG, "Menu dismissed")
    }

    fun updateHover(px: Float, py: Float) {
        if (!isVisible) return

        hoveredRowIndex = -1
        isBackHovered = !backRect.isEmpty && backRect.contains(px, py)
        rowRects.forEachIndexed { index, (rect, _) ->
            if (rect.contains(px, py)) {
                hoveredRowIndex = index
                return
            }
        }
    }

    fun onTap(px: Float, py: Float): Boolean {
        if (!isVisible) return false

        if (!currentMenuBounds().contains(px, py)) {
            dismiss()
            return true
        }

        if (!backRect.isEmpty && backRect.contains(px, py)) {
            navigateBack()
            return true
        }

        for ((rect, entry) in rowRects) {
            if (!rect.contains(px, py)) continue

            when (entry) {
                is Entry.Back -> navigateBack()
                is Entry.Menu -> handleMenuTap(entry.item)
                is Entry.SubMenu -> handleSubMenuTap(entry.parent, entry.item)
            }
            return true
        }

        return true
    }

    fun onDoubleTap(px: Float, py: Float): Boolean {
        if (!isVisible) return false

        for ((rect, entry) in rowRects) {
            if (!rect.contains(px, py)) continue

            if (entry is Entry.SubMenu && isSelectable(entry.item)) {
                val handled = onSubmenuItemDoubleTapped?.invoke(entry.parent, entry.item) == true
                if (handled) {
                    dismiss()
                } else if (pageStack.size > 1) {
                    navigateBack()
                }
                return true
            }

            if (pageStack.size > 1) {
                navigateBack()
            }
            return true
        }

        if (pageStack.size > 1 && currentMenuBounds().contains(px, py)) {
            navigateBack()
            return true
        }

        return currentMenuBounds().contains(px, py)
    }

    fun onNavigationTap(px: Float, py: Float): Boolean {
        if (!isVisible) return false

        if (!currentMenuBounds().contains(px, py)) {
            dismiss()
            return true
        }

        if (!backRect.isEmpty && backRect.contains(px, py)) {
            navigateBack()
            return true
        }

        for ((rect, entry) in rowRects) {
            if (!rect.contains(px, py)) continue

            when (entry) {
                is Entry.Back -> {
                    navigateBack()
                    return true
                }
                is Entry.Menu -> {
                    if (entry.item.submenu == null) return false
                    handleMenuTap(entry.item)
                    return true
                }
                is Entry.SubMenu -> {
                    if (entry.item.submenu == null) return false
                    handleSubMenuTap(entry.parent, entry.item)
                    return true
                }
            }
        }

        return false
    }

    fun onScroll(dy: Float): Boolean {
        if (!isVisible) return false

        val page = currentPage() ?: return false
        val maxOffset = maxScrollOffset(page)
        if (maxOffset <= 0f) return true

        page.scrollOffset = (page.scrollOffset + dy).coerceIn(0f, maxOffset)
        rebuildRows()
        return true
    }

    fun containsPoint(px: Float, py: Float): Boolean {
        return isVisible && currentMenuBounds().contains(px, py)
    }

    fun draw(canvas: Canvas) {
        if (!isVisible) return

        val bounds = currentMenuBounds()
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#40000000")
        }

        canvas.drawRoundRect(
            bounds.left + 4f,
            bounds.top + 4f,
            bounds.right + 4f,
            bounds.bottom + 4f,
            CORNER_RADIUS,
            CORNER_RADIUS,
            shadowPaint
        )
        canvas.drawRoundRect(bounds, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(bounds, CORNER_RADIUS, CORNER_RADIUS, borderPaint)

        drawHeader(canvas, bounds)
        drawPinnedBackRow(canvas)
        drawRows(canvas)
        drawScrollbar(canvas, bounds)
    }

    private fun handleMenuTap(item: MenuItem) {
        if (item.submenu != null) {
            openMenuPage(item)
            return
        }

        onItemSelected?.invoke(item)
        dismiss()
    }

    private fun handleSubMenuTap(parent: MenuItem, item: SubMenuItem) {
        if (item.submenu != null) {
            openSubMenuPage(parent, item)
            return
        }

        if (!isSelectable(item)) return

        when (item.id) {
            "create_textbox", "create_browser", "open_file" -> {
                onItemSelected?.invoke(MenuItem(item.id, item.label))
            }
            else -> {
                onSubmenuItemSelected?.invoke(parent, item)
            }
        }
        dismiss()
    }

    private fun openMenuPage(item: MenuItem) {
        val freshItems = onSubmenuWillShow?.invoke(item) ?: item.submenu ?: emptyList()
        val refreshedItem = item.copy(submenu = freshItems)
        replaceRootItem(refreshedItem)
        pageStack.add(
            Page(
                title = item.label,
                parentMenuItem = refreshedItem,
                entries = freshItems.map { Entry.SubMenu(it, refreshedItem) }
            )
        )
        hoveredRowIndex = -1
        recalculateLayout(menuX, menuY)
    }

    private fun openSubMenuPage(parent: MenuItem, item: SubMenuItem) {
        val nestedItems = item.submenu ?: emptyList()
        pageStack.add(
            Page(
                title = item.label,
                parentMenuItem = parent,
                entries = nestedItems.map { Entry.SubMenu(it, parent) }
            )
        )
        hoveredRowIndex = -1
        recalculateLayout(menuX, menuY)
    }

    private fun navigateBack() {
        if (pageStack.size > 1) {
            pageStack.removeAt(pageStack.lastIndex)
            hoveredRowIndex = -1
            isBackHovered = false
            recalculateLayout(menuX, menuY)
        } else {
            dismiss()
        }
    }

    private fun replaceRootItem(updated: MenuItem) {
        val index = items.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            items[index] = updated
        }
        pageStack.firstOrNull()?.let { root ->
            val newEntries = items.map { Entry.Menu(it) }
            pageStack[0] = root.copy(entries = newEntries)
        }
    }

    private fun recalculateLayout(anchorX: Float, anchorY: Float) {
        val page = currentPage() ?: return
        visibleRows = min(MAX_VISIBLE_ROWS, max(1, page.entries.size))
        menuHeight = HEADER_HEIGHT + PADDING * 2 + backRowHeight() + visibleRows * ITEM_HEIGHT
        menuX = anchorX.coerceIn(0f, (screenWidth - MENU_WIDTH).coerceAtLeast(0f))
        menuY = anchorY.coerceIn(0f, (screenHeight - menuHeight).coerceAtLeast(0f))
        layoutBackRow()
        contentTop = menuY + HEADER_HEIGHT + PADDING + backRowHeight()
        page.scrollOffset = page.scrollOffset.coerceIn(0f, maxScrollOffset(page))
        rebuildRows()
    }

    private fun rebuildRows() {
        val page = currentPage() ?: return
        rowRects.clear()

        val firstIndex = (page.scrollOffset / ITEM_HEIGHT).toInt().coerceAtLeast(0)
        val offsetInRow = page.scrollOffset - firstIndex * ITEM_HEIGHT
        var y = contentTop - offsetInRow
        var index = firstIndex
        val bottom = contentTop + visibleRows * ITEM_HEIGHT

        while (index < page.entries.size && y < bottom) {
            val rowTop = max(y, contentTop)
            val rowBottom = min(y + ITEM_HEIGHT - 4f, bottom - 4f)
            if (rowBottom > rowTop) {
                rowRects.add(
                    RectF(
                        menuX + PADDING,
                        rowTop,
                        menuX + MENU_WIDTH - PADDING,
                        rowBottom
                    ) to page.entries[index]
                )
            }
            y += ITEM_HEIGHT
            index++
        }
    }

    private fun drawHeader(canvas: Canvas, bounds: RectF) {
        val headerBounds = RectF(bounds.left, bounds.top, bounds.right, bounds.top + HEADER_HEIGHT)
        canvas.drawRoundRect(headerBounds, CORNER_RADIUS, CORNER_RADIUS, headerPaint)

        val page = currentPage() ?: return
        val labelX = bounds.left + PADDING + 4f
        val labelY = headerBounds.centerY() - (headerTextPaint.descent() + headerTextPaint.ascent()) / 2f
        canvas.drawText(page.title, labelX, labelY, headerTextPaint)
    }

    private fun drawRows(canvas: Canvas) {
        rowRects.forEachIndexed { index, (rect, entry) ->
            val itemBgPaint = if (index == hoveredRowIndex) hoverPaint else itemPaint
            canvas.drawRoundRect(rect, 6f, 6f, itemBgPaint)

            when (entry) {
                is Entry.Back -> drawBackRow(canvas, rect, entry)
                is Entry.Menu -> drawMenuRow(canvas, rect, entry.item)
                is Entry.SubMenu -> drawSubMenuRow(canvas, rect, entry.item)
            }
        }
    }

    private fun drawPinnedBackRow(canvas: Canvas) {
        if (backRect.isEmpty) return

        val itemBgPaint = if (isBackHovered) hoverPaint else itemPaint
        canvas.drawRoundRect(backRect, 6f, 6f, itemBgPaint)
        drawBackRow(canvas, backRect, Entry.Back())
    }

    private fun drawBackRow(canvas: Canvas, rect: RectF, entry: Entry.Back) {
        val labelY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("<", rect.left + 12f, labelY, arrowPaint)
        canvas.drawText(entry.label, rect.left + 42f, labelY, textPaint)
    }

    private fun drawMenuRow(canvas: Canvas, rect: RectF, item: MenuItem) {
        val labelPaint = textPaint
        val labelY = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        val labelX = if (item.icon != null) {
            drawMenuIcon(canvas, item.icon, rect.left + 12f, rect.centerY())
            rect.left + 48f
        } else {
            rect.left + 12f
        }

        drawTextClipped(canvas, item.label, labelX, labelY, rect.right - 34f, labelPaint)

        if (item.submenu != null) {
            val arrowY = rect.centerY() - (arrowPaint.descent() + arrowPaint.ascent()) / 2f
            canvas.drawText(">", rect.right - 20f, arrowY, arrowPaint)
        }
    }

    private fun drawMenuIcon(canvas: Canvas, icon: String, left: Float, centerY: Float) {
        when (icon) {
            "lock_icon" -> drawLockIcon(canvas, left, centerY, locked = true)
            "unlock_icon" -> drawLockIcon(canvas, left, centerY, locked = false)
            else -> {
                val oldStyle = iconPaint.style
                iconPaint.style = Paint.Style.FILL
                canvas.drawText(icon, left, centerY + iconPaint.textSize / 3f, iconPaint)
                iconPaint.style = oldStyle
            }
        }
    }

    private fun drawLockIcon(canvas: Canvas, left: Float, centerY: Float, locked: Boolean) {
        val bodyLeft = left + 2f
        val bodyTop = centerY - 2f
        val bodyRight = left + 24f
        val bodyBottom = centerY + 15f
        canvas.drawRoundRect(RectF(bodyLeft, bodyTop, bodyRight, bodyBottom), 3f, 3f, iconPaint)

        val shackleLeft = if (locked) left + 7f else left + 12f
        val shackleRight = if (locked) left + 19f else left + 27f
        val shackleTop = centerY - 15f
        val shackleBottom = centerY + 1f
        canvas.drawArc(
            RectF(shackleLeft, shackleTop, shackleRight, shackleBottom),
            180f,
            if (locked) 180f else -225f,
            false,
            iconPaint
        )
        if (locked) {
            canvas.drawLine(shackleLeft, centerY - 7f, shackleLeft, centerY + 1f, iconPaint)
            canvas.drawLine(shackleRight, centerY - 7f, shackleRight, centerY + 1f, iconPaint)
        } else {
            canvas.drawLine(shackleLeft, centerY - 7f, shackleLeft, centerY + 1f, iconPaint)
        }
    }

    private fun drawSubMenuRow(canvas: Canvas, rect: RectF, item: SubMenuItem) {
        val selectable = isSelectable(item)
        val labelPaint = when {
            !selectable -> disabledTextPaint
            item.id.startsWith("toggle_") && item.isEnabled -> activeTextPaint
            else -> inactiveTextPaint
        }
        val labelX = rect.left + 12f
        val labelY = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        drawTextClipped(canvas, item.label, labelX, labelY, rect.right - 34f, labelPaint)

        if (item.submenu != null) {
            val arrowY = rect.centerY() - (arrowPaint.descent() + arrowPaint.ascent()) / 2f
            canvas.drawText(">", rect.right - 20f, arrowY, arrowPaint)
        } else if (selectable && item.isEnabled && item.id.startsWith("toggle_")) {
            val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#6699FF")
            }
            canvas.drawCircle(rect.right - 16f, rect.centerY(), 4f, indicatorPaint)
        }
    }

    private fun drawScrollbar(canvas: Canvas, bounds: RectF) {
        val page = currentPage() ?: return
        val maxOffset = maxScrollOffset(page)
        if (maxOffset <= 0f) return

        val trackTop = contentTop
        val trackBottom = contentTop + visibleRows * ITEM_HEIGHT - 4f
        val trackLeft = bounds.right - PADDING + 3f
        val trackRight = trackLeft + SCROLLBAR_WIDTH
        val track = RectF(trackLeft, trackTop, trackRight, trackBottom)
        canvas.drawRoundRect(track, 3f, 3f, scrollbarTrackPaint)

        val contentHeight = page.entries.size * ITEM_HEIGHT
        val viewportHeight = visibleRows * ITEM_HEIGHT
        val thumbHeight = (viewportHeight / contentHeight * track.height()).coerceAtLeast(28f)
        val thumbTop = track.top + (page.scrollOffset / maxOffset) * (track.height() - thumbHeight)
        val thumb = RectF(track.left, thumbTop, track.right, thumbTop + thumbHeight)
        canvas.drawRoundRect(thumb, 3f, 3f, scrollbarThumbPaint)
    }

    private fun drawTextClipped(canvas: Canvas, label: String, x: Float, y: Float, maxRight: Float, paint: Paint) {
        val available = (maxRight - x).coerceAtLeast(0f)
        val measured = paint.measureText(label)
        if (measured <= available) {
            canvas.drawText(label, x, y, paint)
            return
        }

        val ellipsis = "..."
        val ellipsisWidth = paint.measureText(ellipsis)
        var end = label.length
        while (end > 0 && paint.measureText(label, 0, end) + ellipsisWidth > available) {
            end--
        }
        canvas.drawText(label.substring(0, end) + ellipsis, x, y, paint)
    }

    private fun currentPage(): Page? = pageStack.lastOrNull()

    private fun currentMenuBounds(): RectF {
        return RectF(menuX, menuY, menuX + MENU_WIDTH, menuY + menuHeight)
    }

    private fun backRowHeight(): Float {
        return if (pageStack.size > 1) ITEM_HEIGHT else 0f
    }

    private fun layoutBackRow() {
        if (pageStack.size <= 1) {
            backRect.setEmpty()
            return
        }

        val top = menuY + HEADER_HEIGHT + PADDING
        backRect.set(
            menuX + PADDING,
            top,
            menuX + MENU_WIDTH - PADDING,
            top + ITEM_HEIGHT - 4f
        )
    }

    private fun maxScrollOffset(page: Page): Float {
        val contentHeight = page.entries.size * ITEM_HEIGHT
        val viewportHeight = visibleRows * ITEM_HEIGHT
        return (contentHeight - viewportHeight).coerceAtLeast(0f)
    }

    private fun isSelectable(item: SubMenuItem): Boolean {
        return item.isEnabled || item.id.startsWith("toggle_")
    }
}
