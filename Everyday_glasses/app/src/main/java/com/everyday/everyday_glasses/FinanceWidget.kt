package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceStockIndex
import com.everyday.shared.sync.FinanceTimeRange

/**
 * Finance widget that displays stock index charts.
 *
 * Features:
 * - Auto-selects the local stock index based on the user's country
 * - Line chart with percentage change
 * - Time range selector (1D..5Y) shown on hover
 * - Index dropdown for switching between indices
 * - Data from Yahoo Finance public chart API (no API key needed)
 */
class FinanceWidget(
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(x, y, width, height) {

    companion object {
        private const val TAG = "FinanceWidget"
        const val DEFAULT_WIDTH = 360f
        const val DEFAULT_HEIGHT = 220f

        private const val HEADER_HEIGHT = 32f
        private const val TIME_RANGE_HEIGHT = 28f
        private const val DROPDOWN_ITEM_HEIGHT = 34f
    }

    // ==================== Data Model ====================

    private val allIndices = FinanceDataProvider.allIndices

    // ==================== State ====================

    override val minimizeLabel: String = "$"

    var currentSymbol: String = "^GSPC"
        private set
    var currentRange: FinanceTimeRange = FinanceTimeRange.ONE_DAY
        private set

    private var currentIndex: FinanceStockIndex = allIndices[0]
    private var dataPoints: List<Float> = emptyList()
    private var percentChange: Float = 0f
    private val dataState = WidgetDataState(
        loadingText = "Loading...",
        emptyText = "No data"
    )
    private var countryCodeSet = false  // Track if country was already auto-set

    private val chartCache = mutableMapOf<String, FinanceSnapshot>()
    private val lastErrorByKey = mutableMapOf<String, String>()

    var onStateChanged: (() -> Unit)? = null
    var onDataRequested: ((symbol: String, range: String, force: Boolean, reason: String) -> Unit)? = null

    // ==================== Interaction State ====================

    private enum class Interaction {
        NONE,
        DROPDOWN_OPEN
    }

    private var interaction = Interaction.NONE
    private var hoveredTimeRangeIndex = -1
    private var hoveredDropdownIndex = -1
    private var dropdownScrollOffset = 0f
    private var showTimeRangeBar = false  // Whether time range bar is visible (on hover)

    // ==================== Bounds for hit areas ====================

    private val indexNameBounds = RectF()
    private val timeRangeBarBounds = RectF()
    private val timeRangeButtonBounds = Array(FinanceTimeRange.values().size) { RectF() }
    private val dropdownPanelBounds = RectF()
    private val chartAreaBounds = RectF()

    // ==================== Paints ====================

    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
    }

    private val dropdownArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 14f
    }

    private val percentPaintPositive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44CC66")
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val percentPaintNegative = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC4444")
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val chartLinePaintPositive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44CC66")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chartLinePaintNegative = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC4444")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chartFillPaintPositive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A44CC66")
        style = Paint.Style.FILL
    }

    private val chartFillPaintNegative = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1ACC4444")
        style = Paint.Style.FILL
    }

    private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = 16f
        textAlign = Paint.Align.CENTER
    }

    private val timeRangeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f
        textAlign = Paint.Align.CENTER
    }

    private val timeRangeSelectedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4488AA")
        style = Paint.Style.FILL
    }

    private val dropdownBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD1A1A2E")
        style = Paint.Style.FILL
    }

    private val dropdownItemTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f
    }

    private val dropdownItemHoveredBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }

    private val dropdownItemSelectedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4488AA")
        style = Paint.Style.FILL
    }

    private val dropdownDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444466")
        strokeWidth = 1f
    }

    // ==================== Bounds Update ====================

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        updateInternalBounds()
    }

    private fun updateInternalBounds() {
        val padding = BORDER_WIDTH + 8f

        // Index name area (top-left of content)
        val nameWidth = headerTextPaint.measureText(currentIndex.displayName) + 20f  // +20 for arrow
        indexNameBounds.set(
            x + padding,
            y + padding,
            x + padding + nameWidth,
            y + padding + HEADER_HEIGHT
        )

        // Time range bar (bottom of content)
        timeRangeBarBounds.set(
            x + padding,
            y + widgetHeight - padding - TIME_RANGE_HEIGHT,
            x + widgetWidth - padding,
            y + widgetHeight - padding
        )

        // Individual time range buttons
        val buttonCount = FinanceTimeRange.values().size
        val totalBarWidth = timeRangeBarBounds.width()
        val buttonWidth = totalBarWidth / buttonCount
        for (i in FinanceTimeRange.values().indices) {
            timeRangeButtonBounds[i].set(
                timeRangeBarBounds.left + i * buttonWidth,
                timeRangeBarBounds.top,
                timeRangeBarBounds.left + (i + 1) * buttonWidth,
                timeRangeBarBounds.bottom
            )
        }

        // Chart area
        // Keep chart height stable; the time-range bar overlays the chart on hover.
        val chartBottom = y + widgetHeight - padding
        chartAreaBounds.set(
            x + padding + 4f,
            y + padding + HEADER_HEIGHT + 4f,
            x + widgetWidth - padding - 4f,
            chartBottom
        )

        // Dropdown panel (covers chart area)
        dropdownPanelBounds.set(
            x + padding,
            y + padding + HEADER_HEIGHT,
            x + widgetWidth - padding,
            y + widgetHeight - padding
        )
    }

    init {
        updateInternalBounds()
    }

    private fun buildSeriesKey(
        symbol: String = currentSymbol,
        range: FinanceTimeRange = currentRange
    ): String = "$symbol:${range.range}"

    private fun hasRenderableCache(key: String): Boolean {
        return (chartCache[key]?.points?.size ?: 0) >= 2
    }

    private fun applyCurrentSeriesFromCache() {
        val key = buildSeriesKey()
        val cached = chartCache[key]
        if (cached != null && cached.points.size >= 2) {
            dataPoints = cached.points
            percentChange = cached.percentChange
            dataState.markLoaded()
        } else {
            dataPoints = emptyList()
            percentChange = 0f
            dataState.setIdleError(lastErrorByKey[key])
        }
        updateInternalBounds()
        onStateChanged?.invoke()
    }

    override fun onDestroy() {
        Log.d(TAG, "Finance widget released")
    }

    fun release() = onDestroy()

    // ==================== Public API ====================

    fun setCountryCode(code: String) {
        if (countryCodeSet) {
            Log.d(TAG, "Auto-country update ignored (manual/restored selection already set): country=$code")
            return
        }
        val index = FinanceDataProvider.defaultIndexForCountry(code)
        if (currentIndex.symbol != index.symbol) {
            currentIndex = index
            currentSymbol = index.symbol
            countryCodeSet = true
            Log.d(TAG, "Auto-selected finance index from country: country=${code.uppercase()} symbol=$currentSymbol range=${currentRange.label}")
            applyCurrentSeriesFromCache()
            requestData(force = true, reason = "country_auto")
        }
    }

    fun setSymbolAndRange(symbol: String, range: String) {
        val index = allIndices.find { it.symbol == symbol }
        if (index != null) {
            currentIndex = index
            currentSymbol = symbol
            countryCodeSet = true
        }
        currentRange = FinanceTimeRange.fromRange(range)
        Log.d(TAG, "Restored finance widget selection: symbol=$currentSymbol range=${currentRange.label}")
        applyCurrentSeriesFromCache()
    }

    fun requestData(force: Boolean = false, reason: String = "manual") {
        val key = buildSeriesKey()
        val hasCachedData = hasRenderableCache(key)
        dataState.setLoadingIfEmpty(hasCachedData)
        onStateChanged?.invoke()
        onDataRequested?.invoke(currentSymbol, currentRange.range, force, reason)
    }

    fun applySnapshot(snapshot: FinanceSnapshot) {
        if (snapshot.symbol != currentSymbol || snapshot.range != currentRange.range) {
            return
        }
        val key = buildSeriesKey(snapshot.symbol, currentRange)
        chartCache[key] = snapshot
        lastErrorByKey.remove(key)
        applyCurrentSeriesFromCache()
    }

    fun applyError(message: String = "No data") {
        val key = buildSeriesKey()
        lastErrorByKey[key] = message
        applyCurrentSeriesFromCache()
    }

    // ==================== Tap Handling ====================

    /**
     * Handle a tap within the widget.
     * Returns true if the tap was consumed.
     */
    fun onTap(px: Float, py: Float): Boolean {
        // If dropdown is open, handle dropdown taps
        if (interaction == Interaction.DROPDOWN_OPEN) {
            val idx = findDropdownItemAt(px, py)
            if (idx >= 0 && idx < allIndices.size) {
                selectIndex(allIndices[idx])
                interaction = Interaction.NONE
                return true
            }
            // Tap outside dropdown closes it
            interaction = Interaction.NONE
            return true
        }

        // Tap on index name opens dropdown
        if (indexNameBounds.contains(px, py)) {
            interaction = Interaction.DROPDOWN_OPEN
            dropdownScrollOffset = 0f
            hoveredDropdownIndex = -1
            return true
        }

        // Tap on time range button
        if (showTimeRangeBar) {
            for (i in FinanceTimeRange.values().indices) {
                if (timeRangeButtonBounds[i].contains(px, py)) {
                    currentRange = FinanceTimeRange.values()[i]
                    Log.d(TAG, "Time range selected: symbol=$currentSymbol range=${currentRange.label}")
                    applyCurrentSeriesFromCache()
                    requestData(force = true, reason = "range_changed")
                    return true
                }
            }
        }

        return false
    }

    private fun selectIndex(index: FinanceStockIndex) {
        currentIndex = index
        currentSymbol = index.symbol
        countryCodeSet = true  // Manual selection overrides auto-detection
        Log.d(TAG, "Index selected: symbol=$currentSymbol name=${currentIndex.displayName} range=${currentRange.label}")
        applyCurrentSeriesFromCache()
        requestData(force = true, reason = "index_changed")
    }

    private fun findDropdownItemAt(px: Float, py: Float): Int {
        if (!dropdownPanelBounds.contains(px, py)) return -1
        val relativeY = py - dropdownPanelBounds.top + dropdownScrollOffset
        val idx = (relativeY / DROPDOWN_ITEM_HEIGHT).toInt()
        return if (idx in allIndices.indices) idx else -1
    }

    // ==================== Hover ====================

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)

        // Determine if we should show the time range bar
        val wasShowingTimeRange = showTimeRangeBar
        showTimeRangeBar = (baseState == BaseState.HOVER_CONTENT ||
                baseState == BaseState.HOVER_BORDER ||
                baseState == BaseState.HOVER_RESIZE)

        if (showTimeRangeBar != wasShowingTimeRange) {
            updateInternalBounds()
        }

        if (interaction == Interaction.DROPDOWN_OPEN) {
            hoveredDropdownIndex = findDropdownItemAt(px, py)
            // Close dropdown if cursor goes fully outside widget
            if (!containsPoint(px, py)) {
                interaction = Interaction.NONE
            }
            return
        }

        // Check time range button hover
        hoveredTimeRangeIndex = -1
        if (showTimeRangeBar) {
            for (i in FinanceTimeRange.values().indices) {
                if (timeRangeButtonBounds[i].contains(px, py)) {
                    hoveredTimeRangeIndex = i
                    break
                }
            }
        }
    }

    // ==================== Scroll ====================

    override fun onScroll(dy: Float) {
        if (interaction == Interaction.DROPDOWN_OPEN) {
            val maxScroll = (allIndices.size * DROPDOWN_ITEM_HEIGHT - dropdownPanelBounds.height())
                .coerceAtLeast(0f)
            dropdownScrollOffset = (dropdownScrollOffset + dy).coerceIn(0f, maxScroll)
        }
    }

    // ==================== Drawing ====================

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // Background
        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)

        if (shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        }
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }

        // Header
        drawHeader(canvas)

        // Keep the last successful chart visible during refresh/failure.
        if (dataPoints.size >= 2) {
            drawChart(canvas)
        } else {
            drawCenteredText(canvas, dataState.displayText())
        }

        // Time range bar (on hover)
        if (showTimeRangeBar) {
            drawTimeRangeBar(canvas)
        }

        // Dropdown overlay (if open)
        if (interaction == Interaction.DROPDOWN_OPEN) {
            drawDropdown(canvas)
        }
    }

    private fun drawHeader(canvas: Canvas) {
        val padding = BORDER_WIDTH + 8f
        val textY = y + padding + HEADER_HEIGHT * 0.7f

        // Index name
        canvas.drawText(currentIndex.displayName, x + padding + 4f, textY, headerTextPaint)

        // Dropdown arrow (small triangle)
        val arrowX = x + padding + 4f + headerTextPaint.measureText(currentIndex.displayName) + 8f
        val arrowY = textY - 10f
        val arrowPath = Path().apply {
            moveTo(arrowX, arrowY)
            lineTo(arrowX + 8f, arrowY)
            lineTo(arrowX + 4f, arrowY + 6f)
            close()
        }
        canvas.drawPath(arrowPath, dropdownArrowPaint)

        // Percentage change
        val percentStr = if (percentChange >= 0) {
            String.format("+%.2f%%", percentChange)
        } else {
            String.format("%.2f%%", percentChange)
        }
        val percentPaint = if (percentChange >= 0) percentPaintPositive else percentPaintNegative
        canvas.drawText(percentStr, x + widgetWidth - padding - 4f, textY, percentPaint)
    }

    private fun drawChart(canvas: Canvas) {
        if (dataPoints.size < 2) return

        val left = chartAreaBounds.left
        val right = chartAreaBounds.right
        val top = chartAreaBounds.top
        val bottom = chartAreaBounds.bottom

        if (right <= left || bottom <= top) return

        val minPrice = dataPoints.min()
        val maxPrice = dataPoints.max()
        val priceRange = (maxPrice - minPrice).coerceAtLeast(0.01f)

        val stepX = (right - left) / (dataPoints.size - 1)

        val isPositive = percentChange >= 0
        val linePaint = if (isPositive) chartLinePaintPositive else chartLinePaintNegative
        val fillPaint = if (isPositive) chartFillPaintPositive else chartFillPaintNegative

        // Build path
        val linePath = Path()
        for (i in dataPoints.indices) {
            val px = left + i * stepX
            val py = bottom - ((dataPoints[i] - minPrice) / priceRange) * (bottom - top)
            if (i == 0) linePath.moveTo(px, py) else linePath.lineTo(px, py)
        }

        // Fill under the line
        val fillPath = Path(linePath)
        fillPath.lineTo(right, bottom)
        fillPath.lineTo(left, bottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Draw the line
        canvas.drawPath(linePath, linePaint)
    }

    private fun drawCenteredText(canvas: Canvas, text: String) {
        val cx = chartAreaBounds.centerX()
        val cy = chartAreaBounds.centerY()
        canvas.drawText(text, cx, cy, loadingPaint)
    }

    private fun drawTimeRangeBar(canvas: Canvas) {
        val values = FinanceTimeRange.values()
        for (i in values.indices) {
            val bounds = timeRangeButtonBounds[i]
            val isSelected = values[i] == currentRange
            val isHovered = i == hoveredTimeRangeIndex

            // Selected pill background
            if (isSelected) {
                val pillRect = RectF(
                    bounds.left + 2f, bounds.top + 2f,
                    bounds.right - 2f, bounds.bottom - 2f
                )
                canvas.drawRoundRect(pillRect, 10f, 10f, timeRangeSelectedBgPaint)
            } else if (isHovered) {
                val pillRect = RectF(
                    bounds.left + 2f, bounds.top + 2f,
                    bounds.right - 2f, bounds.bottom - 2f
                )
                canvas.drawRoundRect(pillRect, 10f, 10f, dropdownItemHoveredBgPaint)
            }

            // Label
            val fm = timeRangeTextPaint.fontMetrics
            val textY = bounds.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(values[i].label, bounds.centerX(), textY, timeRangeTextPaint)
        }
    }

    private fun drawDropdown(canvas: Canvas) {
        // Background panel
        canvas.drawRoundRect(dropdownPanelBounds, 6f, 6f, dropdownBgPaint)

        // Clip to panel bounds
        canvas.save()
        canvas.clipRect(dropdownPanelBounds)

        val startY = dropdownPanelBounds.top - dropdownScrollOffset

        for (i in allIndices.indices) {
            val itemTop = startY + i * DROPDOWN_ITEM_HEIGHT
            val itemBottom = itemTop + DROPDOWN_ITEM_HEIGHT

            // Skip if fully outside visible area
            if (itemBottom < dropdownPanelBounds.top || itemTop > dropdownPanelBounds.bottom) continue

            val itemRect = RectF(
                dropdownPanelBounds.left, itemTop,
                dropdownPanelBounds.right, itemBottom
            )

            // Highlight
            val isSelected = allIndices[i].symbol == currentSymbol
            val isHovered = i == hoveredDropdownIndex

            if (isSelected) {
                canvas.drawRect(itemRect, dropdownItemSelectedBgPaint)
            } else if (isHovered) {
                canvas.drawRect(itemRect, dropdownItemHoveredBgPaint)
            }

            // Text
            val fm = dropdownItemTextPaint.fontMetrics
            val textY = itemRect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(
                allIndices[i].displayName,
                dropdownPanelBounds.left + 12f,
                textY,
                dropdownItemTextPaint
            )

            // Divider after the first 3 (popular) indices
            if (i == 2) {
                val divY = itemBottom
                canvas.drawLine(
                    dropdownPanelBounds.left + 8f, divY,
                    dropdownPanelBounds.right - 8f, divY,
                    dropdownDividerPaint
                )
            }
        }

        canvas.restore()
    }
}
