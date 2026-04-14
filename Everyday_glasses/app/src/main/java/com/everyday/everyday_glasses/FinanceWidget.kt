package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

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
        private const val FETCH_COOLDOWN_MS = 60_000L
        private const val DROPDOWN_ITEM_HEIGHT = 34f
        private const val ONE_DAY_LOOKBACK_RANGE = "5d"
    }

    // ==================== Data Model ====================

    data class StockIndex(
        val symbol: String,
        val displayName: String,
        val country: String
    )

    enum class TimeRange(val label: String, val range: String, val interval: String) {
        ONE_DAY("1D", "1d", "5m"),
        ONE_WEEK("1W", "5d", "15m"),
        ONE_MONTH("1M", "1mo", "1h"),
        THREE_MONTHS("3M", "3mo", "1d"),
        SIX_MONTHS("6M", "6mo", "1d"),
        ONE_YEAR("1Y", "1y", "1wk"),
        FIVE_YEARS("5Y", "5y", "1mo")
    }

    data class ChartSnapshot(
        val points: List<Float>,
        val percentChange: Float
    )

    private data class TimedPrice(
        val timestampSeconds: Long,
        val price: Float
    )

    private data class TradingSessionSnapshot(
        val prices: List<Float>,
        val baselinePrice: Float?
    )

    // All available indices - popular ones first
    private val allIndices = listOf(
        StockIndex("^GSPC", "S&P 500", "US"),
        StockIndex("^DJI", "Dow Jones", "US"),
        StockIndex("^IXIC", "Nasdaq", "US"),
        StockIndex("^FTSE", "FTSE 100", "GB"),
        StockIndex("^GDAXI", "DAX", "DE"),
        StockIndex("^FCHI", "CAC 40", "FR"),
        StockIndex("^N225", "Nikkei 225", "JP"),
        StockIndex("^HSI", "Hang Seng", "HK"),
        StockIndex("^STOXX50E", "Euro Stoxx 50", "EU"),
        StockIndex("^GSPTSE", "S&P/TSX", "CA"),
        StockIndex("^AXJO", "ASX 200", "AU"),
        StockIndex("^BSESN", "BSE Sensex", "IN"),
        StockIndex("^KS11", "KOSPI", "KR"),
        StockIndex("^IBEX", "IBEX 35", "ES"),
        StockIndex("^FTSEMIB.MI", "FTSE MIB", "IT"),
        StockIndex("^SSMI", "SMI", "CH"),
        StockIndex("^BVSP", "Bovespa", "BR"),
        StockIndex("^MXX", "IPC Mexico", "MX")
    )

    private val countryToDefaultSymbol = mapOf(
        "US" to "^GSPC", "GB" to "^FTSE", "DE" to "^GDAXI",
        "FR" to "^FCHI", "JP" to "^N225", "HK" to "^HSI",
        "CA" to "^GSPTSE", "AU" to "^AXJO", "IN" to "^BSESN",
        "KR" to "^KS11", "ES" to "^IBEX", "IT" to "^FTSEMIB.MI",
        "CH" to "^SSMI", "BR" to "^BVSP", "MX" to "^MXX",
        // Map some EU countries without their own major index to Euro Stoxx
        "NL" to "^STOXX50E", "BE" to "^STOXX50E", "AT" to "^STOXX50E",
        "FI" to "^STOXX50E", "IE" to "^STOXX50E", "PT" to "^STOXX50E",
        "GR" to "^STOXX50E", "LU" to "^STOXX50E"
    )

    // ==================== State ====================

    override val minimizeLabel: String = "$"

    var currentSymbol: String = "^GSPC"
        private set
    var currentRange: TimeRange = TimeRange.ONE_DAY
        private set

    private var currentIndex: StockIndex = allIndices[0]
    private var dataPoints: List<Float> = emptyList()
    private var percentChange: Float = 0f
    private var isLoading = false
    private var lastError: String? = null
    private var countryCodeSet = false  // Track if country was already auto-set

    // Fetch tracking keyed by "symbol:range"
    private val lastFetchTimes = mutableMapOf<String, Long>()
    private val chartCache = mutableMapOf<String, ChartSnapshot>()
    private val activeFetchKeys = mutableSetOf<String>()
    private val lastErrorByKey = mutableMapOf<String, String>()

    private val handler = Handler(Looper.getMainLooper())
    private var autoRefreshStarted = false
    var onStateChanged: (() -> Unit)? = null

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            fetchData(force = true, reason = "periodic_60s")
            if (autoRefreshStarted) {
                handler.postDelayed(this, FETCH_COOLDOWN_MS)
            }
        }
    }

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
    private val timeRangeButtonBounds = Array(TimeRange.values().size) { RectF() }
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
        val buttonCount = TimeRange.values().size
        val totalBarWidth = timeRangeBarBounds.width()
        val buttonWidth = totalBarWidth / buttonCount
        for (i in TimeRange.values().indices) {
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
        startAutoRefresh()
    }

    private fun buildSeriesKey(
        symbol: String = currentSymbol,
        range: TimeRange = currentRange
    ): String = "$symbol:${range.range}"

    private fun buildChartUrl(symbol: String, range: TimeRange): String {
        val encodedSymbol = URLEncoder.encode(symbol, "UTF-8")
        val query = if (range == TimeRange.ONE_DAY) {
            "?range=$ONE_DAY_LOOKBACK_RANGE&interval=${range.interval}&includePrePost=false"
        } else {
            "?range=${range.range}&interval=${range.interval}"
        }
        return "https://query1.finance.yahoo.com/v8/finance/chart/$encodedSymbol$query"
    }

    private fun buildChartSnapshot(
        requestRange: TimeRange,
        rawPrices: List<Float>,
        timedPrices: List<TimedPrice>,
        exchangeTimeZoneId: String?,
        fallbackPreviousClose: Float?
    ): ChartSnapshot {
        val oneDaySnapshot = if (requestRange == TimeRange.ONE_DAY) {
            // 1D should represent the latest market session, not a rolling 24h window.
            extractLatestTradingSession(timedPrices, exchangeTimeZoneId, fallbackPreviousClose)
        } else {
            null
        }

        val sessionPrices = oneDaySnapshot?.prices ?: rawPrices
        if (sessionPrices.isEmpty()) {
            throw Exception("Insufficient chart data")
        }

        val renderablePrices = if (sessionPrices.size >= 2) {
            sessionPrices
        } else if (requestRange == TimeRange.ONE_DAY) {
            listOf(sessionPrices.first(), sessionPrices.first())
        } else {
            throw Exception("Insufficient chart data")
        }

        val baseline = when (requestRange) {
            TimeRange.ONE_DAY -> oneDaySnapshot?.baselinePrice ?: renderablePrices.first()
            else -> renderablePrices.first()
        }
        if (baseline == 0f) {
            throw Exception("Invalid chart baseline")
        }

        val pct = ((renderablePrices.last() - baseline) / baseline) * 100f
        return ChartSnapshot(
            points = renderablePrices,
            percentChange = pct
        )
    }

    private fun extractLatestTradingSession(
        points: List<TimedPrice>,
        exchangeTimeZoneId: String?,
        fallbackPreviousClose: Float?
    ): TradingSessionSnapshot {
        if (points.isEmpty()) {
            return TradingSessionSnapshot(emptyList(), fallbackPreviousClose)
        }

        val timeZone = resolveExchangeTimeZone(exchangeTimeZoneId)
        val targetDayKey = tradingDayKey(points.last().timestampSeconds, timeZone)
        val sessionPoints = points.filter { tradingDayKey(it.timestampSeconds, timeZone) == targetDayKey }
        val previousSessionClose = points
            .asReversed()
            .firstOrNull { tradingDayKey(it.timestampSeconds, timeZone) < targetDayKey }
            ?.price
            ?: fallbackPreviousClose

        return TradingSessionSnapshot(
            prices = sessionPoints.map { it.price },
            baselinePrice = previousSessionClose
        )
    }

    private fun resolveExchangeTimeZone(exchangeTimeZoneId: String?): TimeZone {
        if (exchangeTimeZoneId.isNullOrBlank()) {
            return TimeZone.getTimeZone("UTC")
        }

        val timeZone = TimeZone.getTimeZone(exchangeTimeZoneId)
        return if (
            timeZone.id == "GMT" &&
            !exchangeTimeZoneId.equals("GMT", ignoreCase = true) &&
            !exchangeTimeZoneId.equals("UTC", ignoreCase = true) &&
            !exchangeTimeZoneId.startsWith("GMT", ignoreCase = true) &&
            !exchangeTimeZoneId.startsWith("UTC", ignoreCase = true)
        ) {
            TimeZone.getTimeZone("UTC")
        } else {
            timeZone
        }
    }

    private fun tradingDayKey(timestampSeconds: Long, timeZone: TimeZone): Int {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = timestampSeconds * 1000L
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    private fun hasRenderableCache(key: String): Boolean {
        return (chartCache[key]?.points?.size ?: 0) >= 2
    }

    private fun applyCurrentSeriesFromCache() {
        val key = buildSeriesKey()
        val cached = chartCache[key]
        if (cached != null && cached.points.size >= 2) {
            dataPoints = cached.points
            percentChange = cached.percentChange
            lastError = null
        } else {
            dataPoints = emptyList()
            percentChange = 0f
            lastError = lastErrorByKey[key]
        }
        isLoading = activeFetchKeys.contains(key)
        updateInternalBounds()
        onStateChanged?.invoke()
    }

    private fun startAutoRefresh() {
        if (autoRefreshStarted) return
        autoRefreshStarted = true
        handler.postDelayed(autoRefreshRunnable, FETCH_COOLDOWN_MS)
        Log.d(TAG, "Auto-refresh timer started (${FETCH_COOLDOWN_MS}ms)")
    }

    private fun stopAutoRefresh() {
        if (!autoRefreshStarted) return
        autoRefreshStarted = false
        handler.removeCallbacks(autoRefreshRunnable)
        Log.d(TAG, "Auto-refresh timer stopped")
    }

    fun setRefreshActive(active: Boolean) {
        if (active) {
            startAutoRefresh()
        } else {
            stopAutoRefresh()
        }
    }

    private fun restartAutoRefreshTimer() {
        if (!autoRefreshStarted) return
        handler.removeCallbacks(autoRefreshRunnable)
        handler.postDelayed(autoRefreshRunnable, FETCH_COOLDOWN_MS)
    }

    fun release() {
        stopAutoRefresh()
        Log.d(TAG, "Finance widget released (auto-refresh stopped)")
    }

    // ==================== Public API ====================

    /**
     * Set the country code to auto-select the default stock index.
     * Only changes the index if the user hasn't manually selected one.
     */
    fun setCountryCode(code: String) {
        if (countryCodeSet) {
            Log.d(TAG, "Auto-country update ignored (manual/restored selection already set): country=$code")
            return  // Don't override user's manual selection
        }
        val defaultSymbol = countryToDefaultSymbol[code.uppercase()] ?: return
        val index = allIndices.find { it.symbol == defaultSymbol } ?: return
        if (currentIndex.symbol != index.symbol) {
            currentIndex = index
            currentSymbol = index.symbol
            countryCodeSet = true
            Log.d(TAG, "Auto-selected finance index from country: country=${code.uppercase()} symbol=$currentSymbol range=${currentRange.label}")
            applyCurrentSeriesFromCache()
            fetchData(force = true, reason = "country_auto")
        }
    }

    /**
     * Restore a specific symbol and range from persisted state.
     */
    fun setSymbolAndRange(symbol: String, range: String) {
        val index = allIndices.find { it.symbol == symbol }
        if (index != null) {
            currentIndex = index
            currentSymbol = symbol
            countryCodeSet = true  // Treat restored state like a manual selection
        }
        val timeRange = TimeRange.values().find { it.range == range }
        if (timeRange != null) {
            currentRange = timeRange
        }
        Log.d(TAG, "Restored finance widget selection: symbol=$currentSymbol range=${currentRange.label}")
        applyCurrentSeriesFromCache()
    }

    /**
     * Fetch chart data from Yahoo Finance.
     */
    fun fetchData(force: Boolean = false, reason: String = "manual") {
        val requestSymbol = currentSymbol
        val requestRange = currentRange
        val key = buildSeriesKey(requestSymbol, requestRange)

        restartAutoRefreshTimer()

        val now = System.currentTimeMillis()
        val lastFetch = lastFetchTimes[key] ?: 0L
        val hasCachedData = hasRenderableCache(key)

        if (!force && now - lastFetch < FETCH_COOLDOWN_MS && hasCachedData) {
            val elapsed = now - lastFetch
            Log.d(
                TAG,
                "Fetch skipped (cooldown): symbol=$requestSymbol range=${requestRange.label} reason=$reason elapsedMs=$elapsed cooldownMs=$FETCH_COOLDOWN_MS"
            )
            if (key == buildSeriesKey()) {
                applyCurrentSeriesFromCache()
            }
            return
        }

        if (activeFetchKeys.contains(key)) {
            Log.d(
                TAG,
                "Fetch skipped (request already in flight): symbol=$requestSymbol range=${requestRange.label} reason=$reason"
            )
            if (key == buildSeriesKey()) {
                isLoading = true
                if (!hasCachedData) {
                    lastError = null
                }
                onStateChanged?.invoke()
            }
            return
        }

        activeFetchKeys.add(key)
        if (key == buildSeriesKey()) {
            isLoading = true
            if (!hasCachedData) {
                lastError = null
            }
            onStateChanged?.invoke()
        }

        Log.d(
            TAG,
            "Fetch started: symbol=$requestSymbol range=${requestRange.label} interval=${requestRange.interval} reason=$reason force=$force hasCachedData=$hasCachedData"
        )

        Thread {
            try {
                val urlStr = buildChartUrl(requestSymbol, requestRange)

                val connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    throw Exception("HTTP $responseCode")
                }

                val json = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val root = JSONObject(json)
                val result = root.getJSONObject("chart")
                    .getJSONArray("result").getJSONObject(0)
                val meta = result.optJSONObject("meta")
                val timestamps = result.optJSONArray("timestamp")

                val closePrices = result.getJSONObject("indicators")
                    .getJSONArray("quote").getJSONObject(0)
                    .getJSONArray("close")

                val rawPrices = mutableListOf<Float>()
                val timedPrices = mutableListOf<TimedPrice>()
                for (i in 0 until closePrices.length()) {
                    if (!closePrices.isNull(i)) {
                        val price = closePrices.getDouble(i).toFloat()
                        rawPrices.add(price)
                        if (timestamps != null && i < timestamps.length() && !timestamps.isNull(i)) {
                            timedPrices.add(
                                TimedPrice(
                                    timestampSeconds = timestamps.getLong(i),
                                    price = price
                                )
                            )
                        }
                    }
                }

                val snapshot = buildChartSnapshot(
                    requestRange = requestRange,
                    rawPrices = rawPrices,
                    timedPrices = timedPrices,
                    exchangeTimeZoneId = meta?.optString("exchangeTimezoneName")
                        ?.takeIf { it.isNotBlank() }
                        ?: meta?.optString("timezone")?.takeIf { it.isNotBlank() },
                    fallbackPreviousClose = meta?.let {
                        listOf("previousClose", "regularMarketPreviousClose", "chartPreviousClose")
                            .asSequence()
                            .mapNotNull { keyName ->
                                if (it.has(keyName) && !it.isNull(keyName)) {
                                    it.optDouble(keyName, Double.NaN)
                                        .takeIf { value -> value.isFinite() && value > 0.0 }
                                        ?.toFloat()
                                } else {
                                    null
                                }
                            }
                            .firstOrNull()
                    }
                )
                val fetchedAt = System.currentTimeMillis()

                handler.post {
                    activeFetchKeys.remove(key)
                    chartCache[key] = snapshot
                    lastFetchTimes[key] = fetchedAt
                    lastErrorByKey.remove(key)

                    if (key == buildSeriesKey()) {
                        dataPoints = snapshot.points
                        percentChange = snapshot.percentChange
                        lastError = null
                        isLoading = activeFetchKeys.contains(key)
                        updateInternalBounds()
                        onStateChanged?.invoke()
                    }

                    Log.d(
                        TAG,
                        "Fetch success: symbol=$requestSymbol range=${requestRange.label} reason=$reason points=${snapshot.points.size} pct=${String.format("%.2f", snapshot.percentChange)}%"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch chart data for $requestSymbol", e)
                handler.post {
                    activeFetchKeys.remove(key)
                    lastErrorByKey[key] = "No data"

                    if (key == buildSeriesKey()) {
                        val cached = chartCache[key]
                        if (cached != null && cached.points.size >= 2) {
                            dataPoints = cached.points
                            percentChange = cached.percentChange
                            lastError = null
                        } else {
                            dataPoints = emptyList()
                            percentChange = 0f
                            lastError = "No data"
                        }
                        isLoading = activeFetchKeys.contains(key)
                        updateInternalBounds()
                        onStateChanged?.invoke()
                    }

                    Log.d(
                        TAG,
                        "Fetch failed: symbol=$requestSymbol range=${requestRange.label} reason=$reason keepCachedData=${hasRenderableCache(key)}"
                    )
                }
            }
        }.start()
    }

    /**
     * Trigger a wake refresh.
     */
    fun refreshOnWake() {
        Log.d(TAG, "Wake refresh requested: symbol=$currentSymbol range=${currentRange.label}")
        fetchData(force = true, reason = "wake")
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
            for (i in TimeRange.values().indices) {
                if (timeRangeButtonBounds[i].contains(px, py)) {
                    currentRange = TimeRange.values()[i]
                    Log.d(TAG, "Time range selected: symbol=$currentSymbol range=${currentRange.label}")
                    applyCurrentSeriesFromCache()
                    fetchData(force = true, reason = "range_changed")
                    return true
                }
            }
        }

        return false
    }

    private fun selectIndex(index: StockIndex) {
        currentIndex = index
        currentSymbol = index.symbol
        countryCodeSet = true  // Manual selection overrides auto-detection
        Log.d(TAG, "Index selected: symbol=$currentSymbol name=${currentIndex.displayName} range=${currentRange.label}")
        applyCurrentSeriesFromCache()
        fetchData(force = true, reason = "index_changed")
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
            for (i in TimeRange.values().indices) {
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
        } else if (isLoading) {
            drawCenteredText(canvas, "Loading...")
        } else if (lastError != null) {
            drawCenteredText(canvas, lastError!!)
        } else {
            drawCenteredText(canvas, "No data")
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
        val values = TimeRange.values()
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
