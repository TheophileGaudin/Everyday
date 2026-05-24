package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceCandle
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceTileSnapshot
import com.everyday.shared.sync.FinanceTimeRange
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
        const val MAX_TILES = 9

        private const val HEADER_HEIGHT = 0f
        private const val NAV_WIDTH = 24f
        private const val TILE_GAP = 6f
        private const val SETTINGS_WIDTH = 420f
        private const val SETTINGS_HEIGHT = 330f
        private const val ROW_HEIGHT = 32f
        private const val DEFAULT_FULLSCREEN_WIDTH = 640f
        private const val DEFAULT_FULLSCREEN_HEIGHT = 480f
    }

    private enum class TileControlAction { INSTRUMENT, LINE, CANDLE, RANGE }

    private data class TileControlHit(
        val tileIndex: Int,
        val action: TileControlAction,
        val value: String?,
        val bounds: RectF
    )

    override val minimizeLabel: String = "$"

    var currentSymbol: String = "^GSPC"
        private set
    var currentRange: FinanceTimeRange = FinanceTimeRange.ONE_DAY
        private set

    private var countryCodeSet = false
    private var selectedTileIndex = 0
    private var navigationIndex = 0
    private val chartCache = mutableMapOf<String, FinanceTileSnapshot>()
    private val lastErrorByKey = mutableMapOf<String, String>()
    private val tileBounds = mutableListOf<Pair<Int, RectF>>()
    private val slotBounds = mutableListOf<Pair<Int, RectF>>()
    private val tileControlBounds = mutableListOf<TileControlHit>()
    private val navBounds = mutableListOf<Pair<Int, RectF>>()
    private val settingsRows = mutableListOf<Pair<Int, RectF>>()
    private val settingsButtonBounds = mutableMapOf<String, RectF>()
    private val tilingMenuButtonBounds = mutableMapOf<String, RectF>()
    private val dashboardContentBounds = RectF()
    private val settingsBounds = RectF()
    private val tilingMenuBounds = RectF()
    private val tilingMenuHoverBounds = RectF()

    private var settingsVisible = false
    private var dropdownVisible = false
    private var dropdownTileIndex = -1
    private var dropdownScrollOffset = 0f
    private var hoveredTileIndex = -1
    private var pageNavigationVisible = false
    private var fullscreenTilingMenuVisible = false
    private var tilingSpan = 3
    private var fullscreenReferenceWidth = DEFAULT_FULLSCREEN_WIDTH
    private var fullscreenReferenceHeight = DEFAULT_FULLSCREEN_HEIGHT
    private var tileDragIndex = -1
    private var tileDragOverSlot = -1
    private var tileDragActive = false

    private var tiles = mutableListOf(defaultTile("^GSPC"))

    var onStateChanged: (() -> Unit)? = null
    var onDataRequested: ((config: FinanceDashboardTileConfig, force: Boolean, reason: String) -> Unit)? = null

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8C8D8")
        textSize = 10f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f
        textAlign = Paint.Align.RIGHT
    }
    private val positivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44CC66")
        textSize = 14f
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }
    private val negativePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC4444")
        textSize = 14f
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }
    private val linePositivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44CC66")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lineNegativePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC4444")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lineFillPositivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A44CC66")
        style = Paint.Style.FILL
    }
    private val lineFillNegativePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1ACC4444")
        style = Paint.Style.FILL
    }
    private val candleUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44CC66")
        style = Paint.Style.FILL
    }
    private val candleDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC4444")
        style = Paint.Style.FILL
    }
    private val candleWickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 1.4f
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC141426")
        style = Paint.Style.FILL
    }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
    }
    private val selectedTilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#664A90D9")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val dropSlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66888888")
        style = Paint.Style.FILL
    }
    private val borderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val navPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33384D")
        style = Paint.Style.FILL
    }
    private val navActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90D9")
        style = Paint.Style.FILL
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 13f
        textAlign = Paint.Align.CENTER
    }
    private val settingsTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        isFakeBoldText = true
    }
    private val settingsTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f
    }
    private val settingsMutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AEB4C4")
        textSize = 12f
    }

    init {
        updateInternalBounds()
    }

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        updateInternalBounds()
    }

    fun release() = onDestroy()

    override fun onDestroy() {
        Log.d(TAG, "Finance widget released")
    }

    fun getTileConfigs(): List<FinanceDashboardTileConfig> = tiles.toList()
    fun getVisibleTileConfigs(): List<FinanceDashboardTileConfig> = visibleTileConfigs()
    fun getNavigationIndex(): Int = navigationIndex
    fun getTilingSpan(): Int = tilingSpan

    fun restoreDashboard(
        configs: List<FinanceDashboardTileConfig>,
        restoredNavigationIndex: Int,
        restoredTilingSpan: Int = 3
    ) {
        tiles = normalizeConfigs(configs).toMutableList()
        tilingSpan = restoredTilingSpan.coerceIn(1, 3)
        countryCodeSet = true
        selectedTileIndex = selectedTileIndex.coerceIn(0, tiles.lastIndex.coerceAtLeast(0))
        navigationIndex = restoredNavigationIndex.coerceIn(0, tiles.lastIndex.coerceAtLeast(0))
        updateLegacySelection()
        updateInternalBounds()
    }

    fun setCountryCode(code: String) {
        if (countryCodeSet) return
        val index = FinanceDataProvider.defaultIndexForCountry(code)
        val first = tiles.firstOrNull()
        if (first == null || first.assetType == FinanceAssetType.INDEX) {
            tiles[0] = defaultTile(index.symbol).copy(slot = slotForIndex(0))
            countryCodeSet = true
            updateLegacySelection()
            requestData(force = true, reason = "country_auto")
        }
    }

    fun setSymbolAndRange(symbol: String, range: String) {
        countryCodeSet = true
        tiles = mutableListOf(defaultTile(symbol).copy(range = FinanceTimeRange.fromRange(range).range, slot = 0))
        navigationIndex = 0
        selectedTileIndex = 0
        updateLegacySelection()
        updateInternalBounds()
    }

    fun requestData(force: Boolean = false, reason: String = "manual") {
        visibleTileConfigs().forEach { config ->
            val needsData = shouldRequest(config)
            if (force || needsData) {
                onDataRequested?.invoke(config, force || needsData, reason)
            }
        }
    }

    fun applySnapshot(snapshot: FinanceSnapshot) {
        val snapshots = if (snapshot.tiles.isNotEmpty()) {
            snapshot.tiles
        } else {
            listOf(
                FinanceTileSnapshot(
                    id = tileIdFor(snapshot.assetType, snapshot.symbol, snapshot.range),
                    assetType = snapshot.assetType,
                    symbol = snapshot.symbol,
                    displayName = snapshot.displayName,
                    country = snapshot.country,
                    range = snapshot.range,
                    rangeLabel = snapshot.rangeLabel,
                    chartType = snapshot.chartType,
                    points = snapshot.points,
                    candles = snapshot.candles,
                    percentChange = snapshot.percentChange,
                    source = "YAHOO",
                    fetchedAtMs = snapshot.fetchedAtMs,
                    staleAfterMs = snapshot.staleAfterMs
                )
            )
        }
        snapshots.forEach { tile ->
            chartCache[seriesKey(tile.assetType, tile.symbol, tile.range)] = tile
            lastErrorByKey.remove(seriesKey(tile.assetType, tile.symbol, tile.range))
        }
    }

    fun applyError(message: String = "No data") {
        visibleTileConfigs().forEach {
            lastErrorByKey[seriesKey(it.assetType, it.symbol, it.range)] = message
        }
    }

    fun onTap(px: Float, py: Float): Boolean {
        if (dropdownVisible) return handleDropdownTap(px, py)
        if (handleTilingMenuTap(px, py)) return true
        if (handleTileControlTap(px, py)) return true

        navBounds.firstOrNull { it.second.contains(px, py) }?.let {
            navigationIndex = it.first
            updateInternalBounds()
            requestData(force = false, reason = "finance_nav_visible")
            onStateChanged?.invoke()
            return true
        }

        val tileHit = tileBounds.firstOrNull { it.second.contains(px, py) }
        if (tileHit != null) {
            selectedTileIndex = tileHit.first
            onStateChanged?.invoke()
            return true
        }
        return false
    }

    fun onDoubleTap(px: Float, py: Float): Boolean {
        if (!containsPoint(px, py)) return false
        if (!isFullscreen) {
            toggleFullscreen()
            onStateChanged?.invoke()
        }
        return true
    }

    override fun onScroll(dy: Float) {
        if (dropdownVisible) {
            val maxScroll = (FinanceDataProvider.allInstruments.size * ROW_HEIGHT - dropdownPanelBounds().height()).coerceAtLeast(0f)
            dropdownScrollOffset = (dropdownScrollOffset + dy).coerceIn(0f, maxScroll)
            return
        }
        if (tileBounds.size < tiles.size) {
            val next = if (dy > 0) navigationIndex + 1 else navigationIndex - 1
            val updated = next.coerceIn(0, tiles.lastIndex)
            if (updated != navigationIndex) {
                navigationIndex = updated
                updateInternalBounds()
                requestData(force = false, reason = "finance_scroll_visible")
            }
        }
    }

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)
        hoveredTileIndex = tileBounds.firstOrNull { it.second.contains(px, py) }?.first ?: -1
        pageNavigationVisible = hoveredTileIndex >= 0 || navBounds.any { it.second.contains(px, py) }
        fullscreenTilingMenuVisible = isFullscreen && (
            tilingMenuHoverBounds.contains(px, py) ||
                tilingMenuBounds.contains(px, py) ||
                tilingMenuButtonBounds.values.any { it.contains(px, py) }
            )
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }
        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)
        if (shouldShowBorder()) canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }
        drawTiles(canvas)
        if (pageNavigationVisible || fullscreenTilingMenuVisible || tileDragActive) drawNavigation(canvas)
        if (fullscreenTilingMenuVisible) drawTilingMenu(canvas)
        if (dropdownVisible) drawDropdown(canvas)
    }

    override fun setFullscreenBounds(screenWidth: Float, screenHeight: Float) {
        fullscreenReferenceWidth = screenWidth.coerceAtLeast(1f)
        fullscreenReferenceHeight = screenHeight.coerceAtLeast(1f)
        super.setFullscreenBounds(screenWidth, screenHeight)
        requestData(force = false, reason = "finance_fullscreen_visible")
    }

    override fun exitFullscreen() {
        super.exitFullscreen()
        updateInternalBounds()
        requestData(force = false, reason = "finance_windowed_visible")
    }

    private fun updateInternalBounds() {
        val padding = BORDER_WIDTH + 7f
        val showNav = visibleTileCapacity() < tiles.size
        dashboardContentBounds.set(
            x + padding,
            y + padding + HEADER_HEIGHT,
            x + widgetWidth - padding,
            y + widgetHeight - padding
        )
        layoutTiles()
        layoutNavigation(showNav)
        layoutTilingMenu()
        layoutTileControls()
    }

    private fun drawTiles(canvas: Canvas) {
        if (tileDragActive) {
            slotBounds.firstOrNull { it.first == tileDragOverSlot }?.let { (_, bounds) ->
                canvas.drawRoundRect(bounds, 6f, 6f, dropSlotPaint)
            }
        }
        tileBounds.forEach { (index, bounds) ->
            val config = tiles.getOrNull(index) ?: return@forEach
            val snapshot = chartCache[seriesKey(config.assetType, config.symbol, config.range)]
            if (index == selectedTileIndex && tiles.size > 1) {
                canvas.drawRoundRect(bounds, 6f, 6f, selectedTilePaint)
            } else {
                canvas.drawRoundRect(bounds, 6f, 6f, tilePaint)
            }
            drawTileHeader(canvas, index, bounds, config, snapshot)
            if (snapshot == null) {
                drawCentered(canvas, bounds, lastErrorByKey[seriesKey(config.assetType, config.symbol, config.range)] ?: "Loading...")
            } else if (config.chartType == FinanceChartType.CANDLE && snapshot.candles.isNotEmpty()) {
                drawCandleChart(canvas, chartRect(bounds), snapshot.candles)
            } else {
                drawLineChart(canvas, chartRect(bounds), snapshot.points.ifEmpty { snapshot.candles.map { it.close } }, snapshot.percentChange)
            }
            if (hoveredTileIndex == index || tileDragActive) {
                drawTileHoverControls(canvas, index, config)
            }
            if (tileDragActive && slotForIndex(index) == tileDragOverSlot) {
                canvas.drawRoundRect(bounds, 6f, 6f, selectedTilePaint)
            }
        }
    }

    private fun drawTileHeader(
        canvas: Canvas,
        index: Int,
        bounds: RectF,
        config: FinanceDashboardTileConfig,
        snapshot: FinanceTileSnapshot?
    ) {
        val label = snapshot?.displayName ?: FinanceDataProvider.instrumentFor(config.assetType, config.symbol).displayName
        canvas.drawText(label, bounds.left + 6f, bounds.top + 18f, labelPaint)
        val latest = snapshot?.latestValue
        if (latest != null) {
            canvas.drawText(formatValue(latest), bounds.right - 6f, bounds.top + 18f, valuePaint)
        }
        val changePaint = if ((snapshot?.percentChange ?: 0f) >= 0f) positivePaint else negativePaint
        canvas.drawText(formatPercent(snapshot?.percentChange ?: 0f), bounds.right - 6f, bounds.top + 34f, changePaint)
        if (hoveredTileIndex == index) {
            val status = snapshot?.streamStatus ?: if (snapshot != null) "Snapshot" else "Loading"
            val range = FinanceTimeRange.fromRange(config.range).label
            canvas.drawText(range, bounds.right - 6f, bounds.top + 48f, smallPaint.apply { textAlign = Paint.Align.RIGHT })
            smallPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(status, bounds.left + 6f, bounds.top + 34f, smallPaint)
        }
    }

    private fun drawTileHoverControls(
        canvas: Canvas,
        index: Int,
        config: FinanceDashboardTileConfig
    ) {
        tileControlBounds.filter { it.tileIndex == index }.forEach { hit ->
            val active = when (hit.action) {
                TileControlAction.LINE -> config.chartType == FinanceChartType.LINE
                TileControlAction.CANDLE -> config.chartType == FinanceChartType.CANDLE
                TileControlAction.RANGE -> config.range == hit.value
                TileControlAction.INSTRUMENT -> false
            }
            canvas.drawRoundRect(hit.bounds, 4f, 4f, if (active) navActivePaint else navPaint)
            val label = when (hit.action) {
                TileControlAction.INSTRUMENT -> FinanceDataProvider.instrumentFor(config.assetType, config.symbol).displayName
                TileControlAction.LINE -> "Line"
                TileControlAction.CANDLE -> "Cndl"
                TileControlAction.RANGE -> FinanceTimeRange.fromRange(hit.value).label
            }
            drawFittedText(canvas, label, hit.bounds, settingsTextPaint)
        }
    }

    private fun drawLineChart(canvas: Canvas, rect: RectF, points: List<Float>, percentChange: Float) {
        if (points.size < 2 || rect.width() <= 0f || rect.height() <= 0f) return
        val minPrice = points.minOrNull() ?: return
        val maxPrice = points.maxOrNull() ?: return
        val range = (maxPrice - minPrice).coerceAtLeast(0.01f)
        val step = rect.width() / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, value ->
            val px = rect.left + i * step
            val py = rect.bottom - ((value - minPrice) / range) * rect.height()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val fillPath = Path(path)
        fillPath.lineTo(rect.right, rect.bottom)
        fillPath.lineTo(rect.left, rect.bottom)
        fillPath.close()
        val isPositive = percentChange >= 0f
        canvas.drawPath(fillPath, if (isPositive) lineFillPositivePaint else lineFillNegativePaint)
        canvas.drawPath(path, if (isPositive) linePositivePaint else lineNegativePaint)
    }

    private fun drawCandleChart(canvas: Canvas, rect: RectF, candles: List<FinanceCandle>) {
        if (candles.isEmpty() || rect.width() <= 0f || rect.height() <= 0f) return
        val visible = candles
        val minPrice = visible.minOf { it.low }
        val maxPrice = visible.maxOf { it.high }
        val priceRange = (maxPrice - minPrice).coerceAtLeast(0.01f)
        val slot = rect.width() / visible.size
        val bodyWidth = (slot * 0.55f).coerceIn(1f, 10f)
        visible.forEachIndexed { i, candle ->
            val cx = rect.left + slot * i + slot / 2f
            fun yFor(value: Float): Float = rect.bottom - ((value - minPrice) / priceRange) * rect.height()
            val highY = yFor(candle.high)
            val lowY = yFor(candle.low)
            val openY = yFor(candle.open)
            val closeY = yFor(candle.close)
            val paint = if (candle.close >= candle.open) candleUpPaint else candleDownPaint
            canvas.drawLine(cx, highY, cx, lowY, candleWickPaint)
            val top = min(openY, closeY)
            val bottom = max(openY, closeY).coerceAtLeast(top + 1f)
            canvas.drawRect(cx - bodyWidth / 2f, top, cx + bodyWidth / 2f, bottom, paint)
        }
    }

    private fun drawCentered(canvas: Canvas, bounds: RectF, text: String) {
        canvas.drawText(text, bounds.centerX(), bounds.centerY(), centerPaint)
    }

    private fun drawNavigation(canvas: Canvas) {
        navBounds.forEach { (index, rect) ->
            canvas.drawRoundRect(rect, 4f, 4f, if (index == navigationIndex) navActivePaint else navPaint)
        }
    }

    private fun drawTilingMenu(canvas: Canvas) {
        canvas.drawRoundRect(tilingMenuBounds, 6f, 6f, panelPaint)
        canvas.drawRoundRect(tilingMenuBounds, 6f, 6f, borderLinePaint)
        tilingMenuButtonBounds.forEach { (id, rect) ->
            val active = id == "span_$tilingSpan"
            canvas.drawRoundRect(rect, 4f, 4f, if (active) navActivePaint else navPaint)
            drawFittedText(canvas, buttonLabel(id), rect, settingsTextPaint)
        }
    }

    private fun drawDropdown(canvas: Canvas) {
        val panel = dropdownPanelBounds()
        canvas.drawRoundRect(panel, 6f, 6f, panelPaint)
        canvas.drawRoundRect(panel, 6f, 6f, borderLinePaint)
        canvas.save()
        canvas.clipRect(panel)
        val instruments = FinanceDataProvider.allInstruments
        var rowTop = panel.top - dropdownScrollOffset
        instruments.forEach { instrument ->
            val row = RectF(panel.left, rowTop, panel.right, rowTop + ROW_HEIGHT)
            if (row.bottom >= panel.top && row.top <= panel.bottom) {
                val label = "${instrument.displayName}  ${instrument.assetType.name.lowercase(Locale.US)}"
                canvas.drawText(label, row.left + 8f, row.centerY() + 5f, settingsTextPaint)
            }
            rowTop += ROW_HEIGHT
        }
        canvas.restore()
    }

    private fun handleTilingMenuTap(px: Float, py: Float): Boolean {
        if (!fullscreenTilingMenuVisible) return false
        val id = tilingMenuButtonBounds.firstNotNullOfOrNull { (buttonId, rect) ->
            buttonId.takeIf { rect.contains(px, py) }
        } ?: return tilingMenuBounds.contains(px, py)
        when (id) {
            "span_3" -> {
                tilingSpan = 3
                compactSlots()
            }
            "span_2" -> {
                tilingSpan = 2
                compactSlots()
            }
            "span_1" -> {
                tilingSpan = 1
                compactSlots()
            }
            "add" -> if (tiles.size < MAX_TILES) {
                tiles.add(
                    defaultTile("^GSPC").copy(
                        id = "tile_${System.currentTimeMillis()}_${tiles.size}",
                        slot = nextAvailableSlot()
                    )
                )
            }
            "remove" -> if (tiles.size > 1) {
                tiles.removeAt(selectedTileIndex.coerceIn(0, tiles.lastIndex))
            }
        }
        selectedTileIndex = selectedTileIndex.coerceIn(0, tiles.lastIndex)
        navigationIndex = navigationIndex.coerceIn(0, (tiles.size - visibleTileCapacity()).coerceAtLeast(0))
        updateLegacySelection()
        updateInternalBounds()
        requestData(force = false, reason = "dashboard_layout_changed")
        onStateChanged?.invoke()
        return true
    }

    private fun handleTileControlTap(px: Float, py: Float): Boolean {
        val hit = tileControlBounds.firstOrNull {
            it.tileIndex == hoveredTileIndex && it.bounds.contains(px, py)
        } ?: return false
        val tile = tiles.getOrNull(hit.tileIndex) ?: return true
        selectedTileIndex = hit.tileIndex
        when (hit.action) {
            TileControlAction.INSTRUMENT -> {
                dropdownVisible = true
                dropdownTileIndex = hit.tileIndex
                dropdownScrollOffset = 0f
            }
            TileControlAction.LINE -> {
                tiles[hit.tileIndex] = tile.copy(chartType = FinanceChartType.LINE)
                requestTileIfVisible(hit.tileIndex, force = false, reason = "tile_chart_type_changed")
            }
            TileControlAction.CANDLE -> {
                tiles[hit.tileIndex] = tile.copy(chartType = FinanceChartType.CANDLE)
                requestTileIfVisible(hit.tileIndex, force = false, reason = "tile_chart_type_changed")
            }
            TileControlAction.RANGE -> {
                val range = FinanceTimeRange.fromRange(hit.value).range
                tiles[hit.tileIndex] = tile.copy(range = range)
                requestTileIfVisible(hit.tileIndex, force = true, reason = "tile_range_changed")
            }
        }
        updateLegacySelection()
        updateInternalBounds()
        onStateChanged?.invoke()
        return true
    }

    private fun handleDropdownTap(px: Float, py: Float): Boolean {
        val panel = dropdownPanelBounds()
        if (!panel.contains(px, py)) {
            dropdownVisible = false
            return true
        }
        val index = ((py - panel.top + dropdownScrollOffset) / ROW_HEIGHT).toInt()
        val instrument = FinanceDataProvider.allInstruments.getOrNull(index) ?: return true
        val tileIndex = dropdownTileIndex.coerceIn(0, tiles.lastIndex)
        tiles[tileIndex] = tiles[tileIndex].copy(
            assetType = instrument.assetType,
            symbol = instrument.symbol,
            chartType = if (instrument.assetType == FinanceAssetType.CRYPTO) FinanceChartType.CANDLE else FinanceChartType.LINE
        )
        dropdownVisible = false
        updateLegacySelection()
        requestTileIfVisible(tileIndex, force = true, reason = "tile_symbol_changed")
        onStateChanged?.invoke()
        return true
    }

    private fun layoutTiles() {
        tileBounds.clear()
        slotBounds.clear()
        if (!isFullscreen) {
            layoutWindowedTiles()
            return
        }
        val capacity = visibleTileCapacity().coerceAtLeast(1)
        val visibleIndices = visibleIndices(capacity)
        val cols = tilingSpan
        val rows = tilingSpan
        val gap = TILE_GAP
        val tileW = (dashboardContentBounds.width() - gap * (cols - 1)) / cols
        val tileH = (dashboardContentBounds.height() - gap * (rows - 1)) / rows
        val slotCount = cols * rows
        repeat(slotCount) { slot ->
            val row = slot / cols
            val col = slot % cols
            val left = dashboardContentBounds.left + col * (tileW + gap)
            val top = dashboardContentBounds.top + row * (tileH + gap)
            slotBounds.add(slot to RectF(left, top, left + tileW, top + tileH))
        }
        visibleIndices.forEachIndexed { position, tileIndex ->
            val slot = if (isFullscreen) {
                slotForIndex(tileIndex).coerceIn(0, slotCount - 1)
            } else {
                position
            }
            slotBounds.firstOrNull { it.first == slot }?.let { (_, rect) ->
                tileBounds.add(tileIndex to RectF(rect))
            }
        }
    }

    private fun layoutWindowedTiles() {
        val maxCols = availableColumnSlots()
        val maxRows = availableRowSlots()
        val visibleIndices = visibleIndices((maxCols * maxRows).coerceAtLeast(1))
        if (visibleIndices.isEmpty()) return

        val occupiedRows = ceil(visibleIndices.size / maxCols.toFloat()).toInt()
            .coerceIn(1, maxRows)
        val rowH = dashboardContentBounds.height() / occupiedRows

        repeat(occupiedRows) { row ->
            val start = row * maxCols
            val rowItems = visibleIndices.drop(start).take(maxCols)
            if (rowItems.isEmpty()) return@repeat
            val cellW = dashboardContentBounds.width() / rowItems.size
            val top = dashboardContentBounds.top + row * rowH
            rowItems.forEachIndexed { col, tileIndex ->
                val left = dashboardContentBounds.left + col * cellW
                val slot = start + col
                val rect = RectF(left, top, left + cellW, top + rowH)
                slotBounds.add(slot to rect)
                tileBounds.add(tileIndex to RectF(rect))
            }
        }
    }

    private fun layoutTileControls() {
        tileControlBounds.clear()
        tileBounds.forEach { (index, bounds) ->
            val top = bounds.top + 22f
            val controlHeight = 20f
            val instrumentRight = (bounds.left + min(120f, bounds.width() * 0.56f)).coerceAtMost(bounds.right - 88f)
            if (instrumentRight > bounds.left + 40f) {
                tileControlBounds.add(
                    TileControlHit(index, TileControlAction.INSTRUMENT, null, RectF(bounds.left + 4f, top, instrumentRight, top + controlHeight))
                )
            }
            tileControlBounds.add(TileControlHit(index, TileControlAction.LINE, null, RectF(bounds.right - 84f, top, bounds.right - 44f, top + controlHeight)))
            tileControlBounds.add(TileControlHit(index, TileControlAction.CANDLE, null, RectF(bounds.right - 42f, top, bounds.right - 4f, top + controlHeight)))

            val ranges = listOf(
                FinanceTimeRange.ONE_DAY,
                FinanceTimeRange.ONE_WEEK,
                FinanceTimeRange.ONE_MONTH,
                FinanceTimeRange.THREE_MONTHS,
                FinanceTimeRange.ONE_YEAR,
                FinanceTimeRange.FIVE_YEARS
            )
            val rowLeft = bounds.left + 4f
            val rowRight = bounds.right - 4f
            val buttonW = ((rowRight - rowLeft) - TILE_GAP * (ranges.size - 1)) / ranges.size
            if (buttonW >= 18f && bounds.height() >= 88f) {
                val buttonTop = bounds.bottom - 24f
                ranges.forEachIndexed { position, range ->
                    val left = rowLeft + position * (buttonW + TILE_GAP)
                    tileControlBounds.add(
                        TileControlHit(index, TileControlAction.RANGE, range.range, RectF(left, buttonTop, left + buttonW, buttonTop + 20f))
                    )
                }
            }
        }
    }

    private fun layoutNavigation(showNav: Boolean) {
        navBounds.clear()
        if (!showNav) return
        val ordered = orderedTileIndices()
        val buttonW = 18f
        val buttonH = 14f
        val gap = 4f
        val totalW = ordered.size * buttonW + (ordered.size - 1).coerceAtLeast(0) * gap
        var left = (x + widgetWidth / 2f - totalW / 2f).coerceIn(x + BORDER_WIDTH + 8f, x + widgetWidth - totalW - BORDER_WIDTH - 8f)
        val top = y + BORDER_WIDTH + 8f
        ordered.forEachIndexed { position, _ ->
            navBounds.add(position to RectF(left, top, left + buttonW, top + buttonH))
            left += buttonW + gap
        }
    }

    private fun layoutSettings() {
        settingsButtonBounds.clear()
        settingsRows.clear()
        val left = x + (widgetWidth - SETTINGS_WIDTH).coerceAtLeast(0f) / 2f
        val top = y + (widgetHeight - SETTINGS_HEIGHT).coerceAtLeast(0f) / 2f
        settingsBounds.set(left, top, (left + SETTINGS_WIDTH).coerceAtMost(x + widgetWidth), (top + SETTINGS_HEIGHT).coerceAtMost(y + widgetHeight))
        val buttonTop = settingsBounds.top + 66f
        var buttonLeft = settingsBounds.left + 18f
        listOf("add", "remove", "up", "down", "done").forEach { id ->
            val width = if (id == "done") 72f else 64f
            settingsButtonBounds[id] = RectF(buttonLeft, buttonTop, buttonLeft + width, buttonTop + 28f)
            buttonLeft += width + 8f
        }
        var rowTop = buttonTop + 42f
        tiles.indices.forEach { index ->
            if (rowTop + ROW_HEIGHT <= settingsBounds.bottom - 12f) {
                settingsRows.add(index to RectF(settingsBounds.left + 18f, rowTop, settingsBounds.right - 18f, rowTop + ROW_HEIGHT - 4f))
            }
            rowTop += ROW_HEIGHT
        }
    }

    private fun layoutTilingMenu() {
        tilingMenuButtonBounds.clear()
        val buttonW = 32f
        val buttonH = 24f
        val gap = 5f
        val ids = listOf("span_3", "span_2", "span_1", "add", "remove")
        val width = ids.size * buttonW + (ids.size + 1) * gap
        val right = x + widgetWidth - BORDER_WIDTH - 8f
        val top = y + BORDER_WIDTH + 8f
        tilingMenuBounds.set(right - width, top, right, top + buttonH + 2f * gap)
        tilingMenuHoverBounds.set(right - 120f, y, x + widgetWidth, y + 72f)
        var left = tilingMenuBounds.left + gap
        ids.forEach { id ->
            tilingMenuButtonBounds[id] = RectF(left, tilingMenuBounds.top + gap, left + buttonW, tilingMenuBounds.top + gap + buttonH)
            left += buttonW + gap
        }
    }

    private fun visibleTileCapacity(): Int {
        val cols = availableColumnSlots()
        val rows = availableRowSlots()
        return (cols * rows).coerceIn(1, min(MAX_TILES, tiles.size))
    }

    private fun availableColumnSlots(): Int {
        if (isFullscreen) return tilingSpan
        val availableW = dashboardContentBounds.width().coerceAtLeast(1f)
        val referenceW = fullscreenReferenceWidth.coerceAtLeast(1f)
        val cellW = (referenceW / tilingSpan).coerceAtLeast(1f)
        return floor(availableW / cellW).toInt().coerceIn(1, tilingSpan)
    }

    private fun availableRowSlots(): Int {
        if (isFullscreen) return tilingSpan
        val availableH = dashboardContentBounds.height().coerceAtLeast(1f)
        val referenceH = fullscreenReferenceHeight.coerceAtLeast(1f)
        val cellH = (referenceH / tilingSpan).coerceAtLeast(1f)
        return floor(availableH / cellH).toInt().coerceIn(1, tilingSpan)
    }

    fun canStartTileDrag(px: Float, py: Float): Boolean {
        if (!isFullscreen || tiles.size <= 1 || dropdownVisible || settingsVisible) return false
        if (isFullscreen && fullscreenTilingMenuVisible && tilingMenuBounds.contains(px, py)) return false
        if (tileControlBounds.any { it.bounds.contains(px, py) }) return false
        return tileBounds.any { it.second.contains(px, py) }
    }

    fun startTileDrag(px: Float, py: Float): Boolean {
        val hit = tileBounds.firstOrNull { it.second.contains(px, py) } ?: return false
        tileDragIndex = hit.first
        tileDragOverSlot = slotForIndex(hit.first)
        selectedTileIndex = hit.first
        tileDragActive = true
        return true
    }

    fun onTileDrag(px: Float, py: Float) {
        if (!tileDragActive) return
        tileDragOverSlot = slotBounds.firstOrNull { it.second.contains(px, py) }?.first ?: tileDragOverSlot
    }

    fun endTileDrag(): Boolean {
        if (!tileDragActive) return false
        val from = tileDragIndex
        val targetSlot = tileDragOverSlot
        tileDragActive = false
        tileDragIndex = -1
        tileDragOverSlot = -1
        if (from in tiles.indices && targetSlot >= 0 && slotForIndex(from) != targetSlot) {
            val fromSlot = slotForIndex(from)
            val occupiedIndex = tiles.indices.firstOrNull { it != from && slotForIndex(it) == targetSlot }
            tiles[from] = tiles[from].copy(slot = targetSlot)
            if (occupiedIndex != null) {
                tiles[occupiedIndex] = tiles[occupiedIndex].copy(slot = fromSlot)
            }
            selectedTileIndex = from
            navigationIndex = navigationIndex.coerceIn(0, (tiles.size - visibleTileCapacity()).coerceAtLeast(0))
            updateLegacySelection()
            updateInternalBounds()
            requestData(force = false, reason = "finance_tile_reordered")
            onStateChanged?.invoke()
            return true
        }
        return false
    }

    private fun visibleIndices(capacity: Int): List<Int> {
        val ordered = orderedTileIndices()
        if (capacity >= ordered.size) return ordered
        val start = navigationIndex.coerceIn(0, (ordered.size - capacity).coerceAtLeast(0))
        return ordered.drop(start).take(capacity)
    }

    private fun visibleTileConfigs(): List<FinanceDashboardTileConfig> =
        visibleIndices(visibleTileCapacity()).mapNotNull { tiles.getOrNull(it) }

    private fun orderedTileIndices(): List<Int> =
        tiles.indices.sortedWith(compareBy({ slotForIndex(it) }, { it }))

    private fun slotForIndex(index: Int): Int =
        tiles.getOrNull(index)?.slot?.takeIf { it >= 0 } ?: index

    private fun requestTileIfVisible(index: Int, force: Boolean, reason: String) {
        val capacity = visibleTileCapacity()
        if (index !in visibleIndices(capacity)) return
        val config = tiles.getOrNull(index) ?: return
        val needsData = shouldRequest(config)
        if (force || needsData) {
            onDataRequested?.invoke(config, force || needsData, reason)
        }
    }

    private fun shouldRequest(config: FinanceDashboardTileConfig): Boolean {
        val snapshot = chartCache[seriesKey(config.assetType, config.symbol, config.range)] ?: return true
        return config.chartType == FinanceChartType.CANDLE && snapshot.candles.isEmpty()
    }

    private fun chartRect(bounds: RectF): RectF =
        RectF(
            bounds.left + 6f,
            bounds.top + 42f,
            bounds.right - 6f,
            bounds.bottom - 6f
        )

    private fun dropdownPanelBounds(): RectF {
        val anchor = tileBounds.firstOrNull { it.first == dropdownTileIndex }?.second ?: dashboardContentBounds
        val width = min(240f, widgetWidth - 24f)
        val left = anchor.left.coerceIn(x + 8f, x + widgetWidth - width - 8f)
        val top = (anchor.top + 24f).coerceAtMost(y + widgetHeight - 170f)
        return RectF(left, top, left + width, top + 170f)
    }

    private fun defaultTile(symbol: String): FinanceDashboardTileConfig =
        FinanceDashboardTileConfig(
            id = tileIdFor(FinanceAssetType.INDEX, symbol, FinanceTimeRange.ONE_DAY.range),
            assetType = FinanceAssetType.INDEX,
            symbol = symbol,
            range = FinanceTimeRange.ONE_DAY.range,
            chartType = FinanceChartType.LINE,
            slot = 0
        )

    private fun normalizeConfigs(configs: List<FinanceDashboardTileConfig>): List<FinanceDashboardTileConfig> =
        configs
            .take(MAX_TILES)
            .ifEmpty { listOf(defaultTile("^GSPC").copy(slot = 0)) }
            .let { configsWithSlots ->
                val used = mutableSetOf<Int>()
                configsWithSlots.mapIndexed { index, config ->
                    val preferred = config.slot.takeIf { it in 0 until MAX_TILES } ?: index
                    val slot = if (preferred !in used) preferred else (0 until MAX_TILES).first { it !in used }
                    used.add(slot)
                    config.copy(slot = slot)
                }
            }

    private fun nextAvailableSlot(): Int {
        val used = tiles.map { it.slot }.filter { it >= 0 }.toSet()
        return (0 until MAX_TILES).firstOrNull { it !in used } ?: tiles.size.coerceAtMost(MAX_TILES - 1)
    }

    private fun compactSlots() {
        orderedTileIndices().forEachIndexed { position, index ->
            tiles[index] = tiles[index].copy(slot = position.coerceAtMost(MAX_TILES - 1))
        }
    }

    private fun drawFittedText(canvas: Canvas, text: String, bounds: RectF, paint: Paint) {
        val originalSize = paint.textSize
        val originalAlign = paint.textAlign
        paint.textAlign = Paint.Align.CENTER
        var size = originalSize.coerceAtMost(12f)
        paint.textSize = size
        val maxWidth = (bounds.width() - 6f).coerceAtLeast(4f)
        while (paint.measureText(text) > maxWidth && size > 7f) {
            size -= 1f
            paint.textSize = size
        }
        canvas.drawText(text, bounds.centerX(), bounds.centerY() - (paint.descent() + paint.ascent()) / 2f, paint)
        paint.textSize = originalSize
        paint.textAlign = originalAlign
    }

    private fun updateLegacySelection() {
        val first = tiles.firstOrNull() ?: defaultTile("^GSPC")
        currentSymbol = first.symbol
        currentRange = FinanceTimeRange.fromRange(first.range)
    }

    private fun seriesKey(assetType: FinanceAssetType, symbol: String, range: String): String =
        "${assetType.name}:$symbol:$range"

    private fun tileIdFor(assetType: FinanceAssetType, symbol: String, range: String): String =
        "${assetType.name.lowercase(Locale.US)}_${symbol}_$range"

    private fun formatPercent(value: Float): String =
        if (value >= 0f) String.format(Locale.US, "+%.2f%%", value) else String.format(Locale.US, "%.2f%%", value)

    private fun formatValue(value: Float): String =
        when {
            value >= 1000f -> String.format(Locale.US, "%.0f", value)
            value >= 10f -> String.format(Locale.US, "%.2f", value)
            else -> String.format(Locale.US, "%.4f", value)
        }

    private fun buttonLabel(id: String): String =
        when (id) {
            "add" -> "+ Chart"
            "remove" -> "Remove"
            "span_3" -> "3"
            "span_2" -> "2"
            "span_1" -> "1"
            "up" -> "Up"
            "down" -> "Down"
            "done" -> "Done"
            else -> id
        }
}
