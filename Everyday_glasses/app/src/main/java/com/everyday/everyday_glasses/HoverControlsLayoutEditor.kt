package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs

/**
 * Full-screen modal overlay that lets the user edit the placement of dashboard hover controls
 * on a fixed grid. Drag to move; double-tap a square to add, replace, or remove a control.
 *
 * The editor owns a single placements map keyed by hover control id; placements are projected
 * onto the controls (`isPlaced` + `bounds`) so the dashboard reflects edits immediately when
 * the editor closes.
 */
class HoverControlsLayoutEditor(
    private val screenWidth: Float,
    private val screenHeight: Float,
    private val controls: List<BaseHoverControl>
) {
    companion object {
        private const val GRID_COLS = 8
        private const val GRID_ROWS = 5

        private const val DASH_LENGTH = 6f
        private const val DASH_GAP = 6f

        private const val MENU_WIDTH = 180f
        private const val MENU_ITEM_HEIGHT = 36f
        private const val MENU_PADDING_X = 14f
        private const val MENU_PADDING_Y = 4f
        private const val MENU_CORNER_RADIUS = 8f

        private const val DRAG_SLOP = 4f
    }

    data class GridCell(val col: Int, val row: Int)

    val cellWidth: Float = screenWidth / GRID_COLS
    val cellHeight: Float = screenHeight / GRID_ROWS

    var isVisible: Boolean = false
        private set

    private val placements = mutableMapOf<String, GridCell>()

    // Drag state
    private var draggingId: String? = null
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var dragX: Float = 0f
    private var dragY: Float = 0f
    private var dragMoved: Boolean = false
    private var ignoreNextDownCell: GridCell? = null

    // Context-menu state
    private enum class MenuKind { NONE, ROOT, ADD_REPLACE }
    private var menuKind: MenuKind = MenuKind.NONE
    private var menuCell: GridCell? = null
    private var menuAnchorX: Float = 0f
    private var menuAnchorY: Float = 0f
    private val menuRect = RectF()
    private val menuItemRects = mutableListOf<MenuItem>()

    private sealed class Action {
        object AddOrReplace : Action()
        object Remove : Action()
        object Done : Action()
        data class Pick(val controlId: String) : Action()
    }

    private data class MenuItem(val rect: RectF, val action: Action, val label: String, val hasSubmenu: Boolean)

    // Callbacks
    var onDismissed: (() -> Unit)? = null
    var onLayoutChanged: (() -> Unit)? = null

    // ==================== Paints ====================

    private val backdropPaint = Paint().apply {
        color = Color.parseColor("#D8000000")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(DASH_LENGTH, DASH_GAP), 0f)
    }

    private val dragHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#552196F3")
        style = Paint.Style.FILL
    }

    private val menuBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F02A2A3E")
        style = Paint.Style.FILL
    }

    private val menuBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val menuTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.LEFT
    }

    private val menuArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    // ==================== Layout ====================

    init {
        resetPlacementsToDefault(notify = false)
        applyPlacementsToControls()
    }

    private fun defaultCellFor(controlId: String): GridCell = when (controlId) {
        CloseAppHoverControl.ID -> GridCell(GRID_COLS - 1, 0)
        YouTubeHistoryHoverControl.ID -> GridCell(GRID_COLS - 1, GRID_ROWS - 1)
        LemonHoverControl.ID -> GridCell(0, GRID_ROWS - 1)
        else -> GridCell(GRID_COLS - 1, GRID_ROWS - 1)
    }

    fun show() {
        isVisible = true
        menuKind = MenuKind.NONE
        draggingId = null
        ignoreNextDownCell = null
        applyPlacementsToControls()
    }

    fun dismiss() {
        if (!isVisible) return
        isVisible = false
        menuKind = MenuKind.NONE
        draggingId = null
        ignoreNextDownCell = null
        onDismissed?.invoke()
    }

    fun exportPlacements(): List<WidgetPersistence.HoverControlPlacementState> =
        controls.mapNotNull { control ->
            placements[control.id]?.let { cell ->
                WidgetPersistence.HoverControlPlacementState(control.id, cell.col, cell.row)
            }
        }

    fun applyPersistedPlacements(states: List<WidgetPersistence.HoverControlPlacementState>?) {
        if (states == null) {
            resetPlacementsToDefault(notify = true)
            return
        }

        placements.clear()
        ignoreNextDownCell = null
        val validIds = controls.map { it.id }.toSet()
        states.forEach { state ->
            if (state.controlId in validIds) {
                placements[state.controlId] = GridCell(
                    state.col.coerceIn(0, GRID_COLS - 1),
                    state.row.coerceIn(0, GRID_ROWS - 1)
                )
            }
        }
        // For any controls the persisted set doesn't mention (e.g. new controls added in
        // a later version), drop them into their default cell when that cell is free.
        // Otherwise leave them unplaced so the user can position them from the editor.
        val occupied = placements.values.toMutableSet()
        for (control in controls) {
            if (placements.containsKey(control.id)) continue
            val defaultCell = defaultCellFor(control.id)
            if (defaultCell !in occupied) {
                placements[control.id] = defaultCell
                occupied.add(defaultCell)
            }
        }
        applyPlacementsToControls()
    }

    fun resetPlacementsToDefault() {
        resetPlacementsToDefault(notify = true)
    }

    private fun resetPlacementsToDefault(notify: Boolean) {
        placements.clear()
        ignoreNextDownCell = null
        for (control in controls) {
            placements[control.id] = defaultCellFor(control.id)
        }
        if (notify) applyPlacementsToControls()
    }

    private fun applyPlacementsToControls() {
        for (control in controls) {
            val cell = placements[control.id]
            if (cell != null) {
                control.isPlaced = true
                val (cx, cy) = cellCenter(cell)
                control.setBounds(cx - control.size / 2f, cy - control.size / 2f)
            } else {
                control.isPlaced = false
                control.clearHover()
            }
        }
        onLayoutChanged?.invoke()
    }

    private fun cellCenter(cell: GridCell): Pair<Float, Float> =
        Pair(cell.col * cellWidth + cellWidth / 2f, cell.row * cellHeight + cellHeight / 2f)

    private fun cellRect(cell: GridCell): RectF {
        val left = cell.col * cellWidth
        val top = cell.row * cellHeight
        return RectF(left, top, left + cellWidth, top + cellHeight)
    }

    private fun cellAt(x: Float, y: Float): GridCell? {
        if (x < 0f || y < 0f || x >= screenWidth || y >= screenHeight) return null
        val col = (x / cellWidth).toInt().coerceIn(0, GRID_COLS - 1)
        val row = (y / cellHeight).toInt().coerceIn(0, GRID_ROWS - 1)
        return GridCell(col, row)
    }

    private fun controlIdAt(cell: GridCell): String? =
        placements.entries.firstOrNull { it.value == cell }?.key

    private fun controlAt(cell: GridCell): BaseHoverControl? {
        val id = controlIdAt(cell) ?: return null
        return controls.firstOrNull { it.id == id }
    }

    fun containsPoint(x: Float, y: Float): Boolean = isVisible

    // ==================== Drawing ====================

    fun draw(canvas: Canvas) {
        if (!isVisible) return

        // Opaque-enough backdrop to hide widgets while keeping a sense of the dashboard space.
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, backdropPaint)

        // Dashed grid lines
        for (c in 0..GRID_COLS) {
            val x = c * cellWidth
            val path = Path().apply {
                moveTo(x, 0f)
                lineTo(x, screenHeight)
            }
            canvas.drawPath(path, gridPaint)
        }
        for (r in 0..GRID_ROWS) {
            val y = r * cellHeight
            val path = Path().apply {
                moveTo(0f, y)
                lineTo(screenWidth, y)
            }
            canvas.drawPath(path, gridPaint)
        }

        // Highlight the cell the dragged control would land in
        if (draggingId != null) {
            cellAt(dragX, dragY)?.let { canvas.drawRect(cellRect(it), dragHighlightPaint) }
        }

        // Render placed controls at their cell centers.
        for (control in controls) {
            if (control.id == draggingId) continue
            val cell = placements[control.id] ?: continue
            val (cx, cy) = cellCenter(cell)
            control.setBounds(cx - control.size / 2f, cy - control.size / 2f)
            control.drawForEditor(canvas)
        }

        // Render the dragged control following the cursor.
        draggingId?.let { id ->
            val control = controls.firstOrNull { it.id == id } ?: return@let
            control.setBounds(dragX - control.size / 2f, dragY - control.size / 2f)
            control.drawForEditor(canvas)
        }

        if (menuKind != MenuKind.NONE) drawContextMenu(canvas)
    }

    private fun drawContextMenu(canvas: Canvas) {
        canvas.drawRoundRect(menuRect, MENU_CORNER_RADIUS, MENU_CORNER_RADIUS, menuBackgroundPaint)
        canvas.drawRoundRect(menuRect, MENU_CORNER_RADIUS, MENU_CORNER_RADIUS, menuBorderPaint)
        for (item in menuItemRects) {
            val ty = item.rect.centerY() - (menuTextPaint.descent() + menuTextPaint.ascent()) / 2f
            canvas.drawText(item.label, item.rect.left + MENU_PADDING_X, ty, menuTextPaint)
            if (item.hasSubmenu) {
                canvas.drawText("▶", item.rect.right - MENU_PADDING_X, ty, menuArrowPaint)
            }
        }
    }

    // ==================== Input ====================

    fun onDown(x: Float, y: Float): Boolean {
        if (!isVisible) return false

        // Menu open: consume; tap handler picks an item.
        if (menuKind != MenuKind.NONE) return true

        ignoreNextDownCell?.let { cell ->
            ignoreNextDownCell = null
            if (cellRect(cell).contains(x, y)) {
                return true
            }
        }

        // Begin drag if the press lands on a placed control's cell.
        for (control in controls) {
            val cell = placements[control.id] ?: continue
            if (cellRect(cell).contains(x, y)) {
                draggingId = control.id
                dragStartX = x
                dragStartY = y
                dragX = x
                dragY = y
                dragMoved = false
                return true
            }
        }
        return true  // editor is modal, swallow everything
    }

    fun onMove(x: Float, y: Float): Boolean {
        if (!isVisible) return false
        if (draggingId != null) {
            dragX = x
            dragY = y
            if (abs(x - dragStartX) > DRAG_SLOP || abs(y - dragStartY) > DRAG_SLOP) {
                dragMoved = true
            }
        }
        return true
    }

    fun onUp(): Boolean {
        if (!isVisible) return false
        val id = draggingId ?: return true
        if (dragMoved) {
            val target = cellAt(dragX, dragY)
            if (target != null) {
                val previousCell = placements[id]
                val occupantId = placements.entries
                    .firstOrNull { it.value == target && it.key != id }?.key
                if (occupantId != null && previousCell != null) {
                    // Swap: occupant moves into the control's previous cell.
                    placements[occupantId] = previousCell
                } else if (occupantId != null) {
                    placements.remove(occupantId)
                }
                placements[id] = target
                ignoreNextDownCell = target
            }
        }
        draggingId = null
        dragMoved = false
        applyPlacementsToControls()
        return true
    }

    fun onTap(x: Float, y: Float): Boolean {
        if (!isVisible) return false

        if (menuKind != MenuKind.NONE) {
            for (item in menuItemRects) {
                if (item.rect.contains(x, y)) return handleMenuAction(item.action)
            }
            // Tap outside the menu closes it.
            closeMenu()
            return true
        }

        return true  // modal: swallow stray taps
    }

    fun onDoubleTap(x: Float, y: Float): Boolean {
        if (!isVisible) return false
        if (menuKind != MenuKind.NONE) {
            closeMenu()
            return true
        }
        val cell = cellAt(x, y) ?: return true
        menuCell = cell
        menuAnchorX = x
        menuAnchorY = y
        showRootMenu()
        return true
    }

    private fun showRootMenu() {
        val cell = menuCell ?: return
        val occupied = controlIdAt(cell) != null
        val items = mutableListOf<Pair<Action, String>>()
        items.add(Action.AddOrReplace to if (occupied) "Replace" else "Add")
        if (occupied) items.add(Action.Remove to "Remove")
        items.add(Action.Done to "Done")
        layoutMenu(menuAnchorX, menuAnchorY, items, withSubmenuIndicators = true)
        menuKind = MenuKind.ROOT
    }

    private fun showAddReplaceSubmenu() {
        val cell = menuCell ?: return
        val occupantId = controlIdAt(cell)
        val items = controls
            .filter { it.id != occupantId }
            .map { Action.Pick(it.id) as Action to it.label }
        if (items.isEmpty()) {
            closeMenu()
            return
        }
        layoutMenu(menuAnchorX, menuAnchorY, items, withSubmenuIndicators = false)
        menuKind = MenuKind.ADD_REPLACE
    }

    private fun layoutMenu(
        originX: Float,
        originY: Float,
        items: List<Pair<Action, String>>,
        withSubmenuIndicators: Boolean
    ) {
        val height = items.size * MENU_ITEM_HEIGHT + MENU_PADDING_Y * 2f
        var left = originX
        var top = originY
        if (left + MENU_WIDTH > screenWidth - 8f) left = screenWidth - MENU_WIDTH - 8f
        if (top + height > screenHeight - 8f) top = screenHeight - height - 8f
        if (left < 8f) left = 8f
        if (top < 8f) top = 8f
        menuRect.set(left, top, left + MENU_WIDTH, top + height)

        menuItemRects.clear()
        items.forEachIndexed { i, (action, label) ->
            val r = RectF(
                left + 4f,
                top + MENU_PADDING_Y + i * MENU_ITEM_HEIGHT,
                left + MENU_WIDTH - 4f,
                top + MENU_PADDING_Y + (i + 1) * MENU_ITEM_HEIGHT
            )
            val showArrow = withSubmenuIndicators && action is Action.AddOrReplace
            menuItemRects.add(MenuItem(r, action, label, showArrow))
        }
    }

    private fun handleMenuAction(action: Action): Boolean {
        when (action) {
            is Action.AddOrReplace -> {
                showAddReplaceSubmenu()
            }
            is Action.Remove -> {
                val cell = menuCell
                if (cell != null) {
                    controlIdAt(cell)?.let { placements.remove(it) }
                    applyPlacementsToControls()
                }
                closeMenu()
            }
            is Action.Done -> {
                closeMenu()
                dismiss()
            }
            is Action.Pick -> {
                val cell = menuCell
                if (cell != null) {
                    // Move the picked control into this cell, removing whoever was there
                    // and unplacing the control from any previous cell.
                    placements.remove(action.controlId)
                    controlIdAt(cell)?.let { placements.remove(it) }
                    placements[action.controlId] = cell
                    applyPlacementsToControls()
                }
                closeMenu()
            }
        }
        return true
    }

    private fun closeMenu() {
        menuKind = MenuKind.NONE
        menuCell = null
        menuItemRects.clear()
    }
}
