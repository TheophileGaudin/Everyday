package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Modal full-screen overlay for configuring the lemon hover control's shortcut list.
 *
 * Layout:
 * - Title strip with a small lemon preview.
 * - Two columns: left lists actions NOT currently assigned to the lemon; right lists the
 *   actions currently on the lemon, in their slice order. Tap an item to move it across.
 *   Right-column items also expose ↑/↓ arrows for reordering.
 * - Tap outside the columns (or on the close button) to dismiss.
 */
class ShortcutsSettingsMenu(
    private val screenWidth: Float,
    private val screenHeight: Float,
    private val savedLayoutsProvider: () -> List<String>,
    private val initialShortcuts: List<ShortcutAction>
) {

    var isVisible: Boolean = false
        private set

    private val available = mutableListOf<ShortcutAction>()
    private val onLemon = mutableListOf<ShortcutAction>()

    private var leftScroll: Float = 0f
    private var rightScroll: Float = 0f

    var onChanged: ((List<ShortcutAction>) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    private val backdropRect = RectF()
    private val panelRect = RectF()
    private val closeButtonRect = RectF()
    private val leftRect = RectF()
    private val rightRect = RectF()
    private val leftHeaderRect = RectF()
    private val rightHeaderRect = RectF()
    private val previewRect = RectF()

    private data class Row(val rect: RectF, val action: ShortcutAction, val isOnLemon: Boolean, val index: Int)
    private data class ArrowHit(val rect: RectF, val action: ShortcutAction, val delta: Int)

    private val leftRows = mutableListOf<Row>()
    private val rightRows = mutableListOf<Row>()
    private val rightArrows = mutableListOf<ArrowHit>()

    // ==================== Paints ====================

    private val backdropPaint = Paint().apply { color = Color.parseColor("#D8000000") }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F0222232") }
    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD51F")
        textSize = 16f
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val columnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A28") }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33384F") }
    private val rowPaintAlt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2F44") }
    private val rowFullPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6B5A1B") }
    private val rowLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.LEFT
    }
    private val closeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935") }
    private val closeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#444A6B") }
    private val arrowIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val capacityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        textSize = 13f
        textAlign = Paint.Align.RIGHT
    }

    init {
        loadInitial()
        updateLayout()
    }

    fun show() {
        isVisible = true
        loadInitial()
        leftScroll = 0f
        rightScroll = 0f
        updateLayout()
    }

    fun dismiss() {
        if (!isVisible) return
        isVisible = false
        onDismissed?.invoke()
    }

    fun containsPoint(x: Float, y: Float): Boolean = isVisible

    // ==================== Layout ====================

    private fun loadInitial() {
        onLemon.clear()
        for (a in initialShortcuts) {
            if (onLemon.none { it.id == a.id }) onLemon.add(a)
            if (onLemon.size >= ShortcutAction.MAX_LEMON_SLICES) break
        }
        rebuildAvailable()
    }

    private fun rebuildAvailable() {
        available.clear()
        val onIds = onLemon.map { it.id }.toSet()
        for (action in ShortcutAction.BUILTIN) {
            if (action.id !in onIds) available.add(action)
        }
        // Discovered saved layouts (filenames the user saved at runtime).
        for (name in savedLayoutsProvider()) {
            if (name.isBlank()) continue
            val candidate = ShortcutAction.Layout(name)
            if (candidate.id !in onIds && available.none { it.id == candidate.id }) {
                available.add(candidate)
            }
        }
    }

    private fun updateLayout() {
        backdropRect.set(0f, 0f, screenWidth, screenHeight)
        val panelMargin = 24f
        panelRect.set(panelMargin, panelMargin, screenWidth - panelMargin, screenHeight - panelMargin)

        val closeSize = 36f
        closeButtonRect.set(
            panelRect.right - closeSize - 12f,
            panelRect.top + 12f,
            panelRect.right - 12f,
            panelRect.top + 12f + closeSize
        )

        previewRect.set(panelRect.left + 16f, panelRect.top + 10f, panelRect.left + 70f, panelRect.top + 64f)

        val topReserved = panelRect.top + 80f
        val bottomReserved = panelRect.bottom - 12f
        val columnGap = 16f
        val columnsTop = topReserved
        val columnsLeft = panelRect.left + 16f
        val columnsRight = panelRect.right - 16f
        val columnWidth = (columnsRight - columnsLeft - columnGap) / 2f

        leftRect.set(columnsLeft, columnsTop, columnsLeft + columnWidth, bottomReserved)
        rightRect.set(leftRect.right + columnGap, columnsTop, leftRect.right + columnGap + columnWidth, bottomReserved)

        leftHeaderRect.set(leftRect.left, leftRect.top, leftRect.right, leftRect.top + 28f)
        rightHeaderRect.set(rightRect.left, rightRect.top, rightRect.right, rightRect.top + 28f)

        rebuildRows()
    }

    private fun rebuildRows() {
        leftRows.clear()
        rightRows.clear()
        rightArrows.clear()

        val rowHeight = 44f
        val rowGap = 6f
        val rowPadding = 8f
        var y = leftHeaderRect.bottom + rowPadding - leftScroll
        for ((i, action) in available.withIndex()) {
            val r = RectF(leftRect.left + 4f, y, leftRect.right - 4f, y + rowHeight)
            leftRows.add(Row(r, action, isOnLemon = false, index = i))
            y += rowHeight + rowGap
        }
        y = rightHeaderRect.bottom + rowPadding - rightScroll
        for ((i, action) in onLemon.withIndex()) {
            val r = RectF(rightRect.left + 4f, y, rightRect.right - 4f, y + rowHeight)
            rightRows.add(Row(r, action, isOnLemon = true, index = i))
            // Up / Down arrow hit-areas at the right edge.
            val arrowSize = 28f
            val upRect = RectF(r.right - arrowSize - 4f - arrowSize - 4f, r.top + 8f, r.right - arrowSize - 8f, r.top + 8f + arrowSize)
            val downRect = RectF(r.right - arrowSize - 4f, r.top + 8f, r.right - 4f, r.top + 8f + arrowSize)
            if (i > 0) rightArrows.add(ArrowHit(upRect, action, -1))
            if (i < onLemon.size - 1) rightArrows.add(ArrowHit(downRect, action, +1))
            y += rowHeight + rowGap
        }
    }

    // ==================== Drawing ====================

    fun draw(canvas: Canvas) {
        if (!isVisible) return
        canvas.drawRect(backdropRect, backdropPaint)
        canvas.drawRoundRect(panelRect, 12f, 12f, panelPaint)
        canvas.drawRoundRect(panelRect, 12f, 12f, panelBorderPaint)

        // Title with the 6-branched lemon preview.
        LemonDrawing.draw(canvas, previewRect, slices = 6, selectedIndex = -1)
        canvas.drawText("Shortcuts on the lemon", previewRect.right + 12f, previewRect.centerY() + 8f, titlePaint)

        // Close button (X).
        canvas.drawRoundRect(closeButtonRect, 6f, 6f, closeButtonPaint)
        val ccx = closeButtonRect.centerX(); val ccy = closeButtonRect.centerY()
        val off = closeButtonRect.width() * 0.25f
        canvas.drawLine(ccx - off, ccy - off, ccx + off, ccy + off, closeIconPaint)
        canvas.drawLine(ccx + off, ccy - off, ccx - off, ccy + off, closeIconPaint)

        // Column backgrounds + headers.
        canvas.drawRoundRect(leftRect, 8f, 8f, columnBgPaint)
        canvas.drawRoundRect(rightRect, 8f, 8f, columnBgPaint)
        canvas.drawText("Available", leftHeaderRect.left + 10f, leftHeaderRect.bottom - 6f, headerPaint)
        canvas.drawText("On the lemon", rightHeaderRect.left + 10f, rightHeaderRect.bottom - 6f, headerPaint)
        val capacity = "${onLemon.size} / ${ShortcutAction.MAX_LEMON_SLICES}"
        canvas.drawText(capacity, rightHeaderRect.right - 10f, rightHeaderRect.bottom - 6f, capacityPaint)

        canvas.save()
        canvas.clipRect(leftRect.left, leftHeaderRect.bottom, leftRect.right, leftRect.bottom)
        for ((i, row) in leftRows.withIndex()) drawRow(canvas, row, alt = i % 2 == 1, full = false)
        canvas.restore()

        canvas.save()
        canvas.clipRect(rightRect.left, rightHeaderRect.bottom, rightRect.right, rightRect.bottom)
        val full = onLemon.size >= ShortcutAction.MAX_LEMON_SLICES
        for ((i, row) in rightRows.withIndex()) drawRow(canvas, row, alt = i % 2 == 1, full = false)
        for (arrow in rightArrows) drawArrowButton(canvas, arrow.rect, up = arrow.delta < 0)
        if (full) {
            canvas.drawText("Lemon is full", rightRect.centerX(), rightRect.bottom - 14f, capacityPaint.apply { textAlign = Paint.Align.CENTER })
            capacityPaint.textAlign = Paint.Align.RIGHT
        }
        canvas.restore()
    }

    private fun drawRow(canvas: Canvas, row: Row, alt: Boolean, full: Boolean) {
        val paint = when {
            full -> rowFullPaint
            alt -> rowPaintAlt
            else -> rowPaint
        }
        canvas.drawRoundRect(row.rect, 6f, 6f, paint)
        val label = row.action.label
        canvas.drawText(label, row.rect.left + 12f, row.rect.centerY() + 5f, rowLabelPaint)
    }

    private fun drawArrowButton(canvas: Canvas, rect: RectF, up: Boolean) {
        canvas.drawRoundRect(rect, 6f, 6f, arrowBgPaint)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val sz = rect.width() * 0.22f
        if (up) {
            canvas.drawLine(cx - sz, cy + sz / 2f, cx, cy - sz / 2f, arrowIconPaint)
            canvas.drawLine(cx, cy - sz / 2f, cx + sz, cy + sz / 2f, arrowIconPaint)
        } else {
            canvas.drawLine(cx - sz, cy - sz / 2f, cx, cy + sz / 2f, arrowIconPaint)
            canvas.drawLine(cx, cy + sz / 2f, cx + sz, cy - sz / 2f, arrowIconPaint)
        }
    }

    // ==================== Input ====================

    @Suppress("UNUSED_PARAMETER")
    fun onDown(x: Float, y: Float): Boolean {
        if (!isVisible) return false
        return true  // modal: swallow everything
    }

    @Suppress("UNUSED_PARAMETER")
    fun onMove(x: Float, y: Float): Boolean = isVisible

    fun onUp(): Boolean = isVisible

    fun onTap(x: Float, y: Float): Boolean {
        if (!isVisible) return false
        if (closeButtonRect.contains(x, y)) { dismiss(); return true }

        // Arrow reorders first — they sit inside right-column rows.
        for (arrow in rightArrows) {
            if (arrow.rect.contains(x, y)) {
                reorder(arrow.action, arrow.delta)
                return true
            }
        }

        for (row in rightRows) {
            if (row.rect.contains(x, y)) {
                moveToAvailable(row.action)
                return true
            }
        }
        for (row in leftRows) {
            if (row.rect.contains(x, y)) {
                moveToLemon(row.action)
                return true
            }
        }
        // Outside columns but still inside the panel: swallow. Outside panel: dismiss.
        if (!panelRect.contains(x, y)) {
            dismiss()
            return true
        }
        return true
    }

    fun onScroll(x: Float, y: Float, dy: Float) {
        if (!isVisible) return
        if (leftRect.contains(x, y)) {
            leftScroll = max(0f, leftScroll - dy)
            updateLayout()
        } else if (rightRect.contains(x, y)) {
            rightScroll = max(0f, rightScroll - dy)
            updateLayout()
        }
    }

    private fun moveToLemon(action: ShortcutAction) {
        if (onLemon.size >= ShortcutAction.MAX_LEMON_SLICES) return
        if (onLemon.any { it.id == action.id }) return
        onLemon.add(action)
        rebuildAvailable()
        updateLayout()
        notifyChanged()
    }

    private fun moveToAvailable(action: ShortcutAction) {
        if (onLemon.removeAll { it.id == action.id }) {
            rebuildAvailable()
            updateLayout()
            notifyChanged()
        }
    }

    private fun reorder(action: ShortcutAction, delta: Int) {
        val idx = onLemon.indexOfFirst { it.id == action.id }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, onLemon.size - 1)
        if (target == idx) return
        val item = onLemon.removeAt(idx)
        onLemon.add(target, item)
        updateLayout()
        notifyChanged()
    }

    private fun notifyChanged() {
        onChanged?.invoke(onLemon.toList())
    }
}
