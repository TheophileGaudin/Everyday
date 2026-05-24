package com.everyday.shared.sync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

object FinanceDataProvider {
    private const val ONE_DAY_LOOKBACK_RANGE = "5d"
    private const val BINANCE_REST_BASE = "https://api.binance.com/api/v3/klines"
    private const val FX_REST_BASE = "https://api.frankfurter.app"

    val allIndices = listOf(
        FinanceStockIndex("^GSPC", "S&P 500", "US"),
        FinanceStockIndex("^DJI", "Dow Jones", "US"),
        FinanceStockIndex("^IXIC", "Nasdaq", "US"),
        FinanceStockIndex("^FTSE", "FTSE 100", "GB"),
        FinanceStockIndex("^GDAXI", "DAX", "DE"),
        FinanceStockIndex("^FCHI", "CAC 40", "FR"),
        FinanceStockIndex("^N225", "Nikkei 225", "JP"),
        FinanceStockIndex("^HSI", "Hang Seng", "HK"),
        FinanceStockIndex("^STOXX50E", "Euro Stoxx 50", "EU"),
        FinanceStockIndex("^GSPTSE", "S&P/TSX", "CA"),
        FinanceStockIndex("^AXJO", "ASX 200", "AU"),
        FinanceStockIndex("^BSESN", "BSE Sensex", "IN"),
        FinanceStockIndex("^KS11", "KOSPI", "KR"),
        FinanceStockIndex("^IBEX", "IBEX 35", "ES"),
        FinanceStockIndex("^FTSEMIB.MI", "FTSE MIB", "IT"),
        FinanceStockIndex("^SSMI", "SMI", "CH"),
        FinanceStockIndex("^BVSP", "Bovespa", "BR"),
        FinanceStockIndex("^MXX", "IPC Mexico", "MX")
    )

    val allCrypto = listOf(
        FinanceInstrument("BTCUSDT", "BTC/USDT", "GLOBAL", FinanceAssetType.CRYPTO),
        FinanceInstrument("ETHUSDT", "ETH/USDT", "GLOBAL", FinanceAssetType.CRYPTO),
        FinanceInstrument("BNBUSDT", "BNB/USDT", "GLOBAL", FinanceAssetType.CRYPTO),
        FinanceInstrument("SOLUSDT", "SOL/USDT", "GLOBAL", FinanceAssetType.CRYPTO),
        FinanceInstrument("XRPUSDT", "XRP/USDT", "GLOBAL", FinanceAssetType.CRYPTO),
        FinanceInstrument("DOGEUSDT", "DOGE/USDT", "GLOBAL", FinanceAssetType.CRYPTO),
        FinanceInstrument("ADAUSDT", "ADA/USDT", "GLOBAL", FinanceAssetType.CRYPTO),
        FinanceInstrument("AVAXUSDT", "AVAX/USDT", "GLOBAL", FinanceAssetType.CRYPTO),
        FinanceInstrument("LINKUSDT", "LINK/USDT", "GLOBAL", FinanceAssetType.CRYPTO)
    )

    val allFx = listOf(
        FinanceInstrument("EURUSD", "EUR/USD", "GLOBAL", FinanceAssetType.FX),
        FinanceInstrument("GBPUSD", "GBP/USD", "GLOBAL", FinanceAssetType.FX),
        FinanceInstrument("USDJPY", "USD/JPY", "GLOBAL", FinanceAssetType.FX),
        FinanceInstrument("EURGBP", "EUR/GBP", "GLOBAL", FinanceAssetType.FX),
        FinanceInstrument("USDCAD", "USD/CAD", "GLOBAL", FinanceAssetType.FX),
        FinanceInstrument("AUDUSD", "AUD/USD", "GLOBAL", FinanceAssetType.FX),
        FinanceInstrument("USDCHF", "USD/CHF", "GLOBAL", FinanceAssetType.FX)
    )

    val allInstruments: List<FinanceInstrument> =
        allIndices.map { FinanceInstrument(it.symbol, it.displayName, it.country, FinanceAssetType.INDEX) } +
                allCrypto +
                allFx

    private val countryToDefaultSymbol = mapOf(
        "US" to "^GSPC", "GB" to "^FTSE", "DE" to "^GDAXI",
        "FR" to "^FCHI", "JP" to "^N225", "HK" to "^HSI",
        "CA" to "^GSPTSE", "AU" to "^AXJO", "IN" to "^BSESN",
        "KR" to "^KS11", "ES" to "^IBEX", "IT" to "^FTSEMIB.MI",
        "CH" to "^SSMI", "BR" to "^BVSP", "MX" to "^MXX",
        "NL" to "^STOXX50E", "BE" to "^STOXX50E", "AT" to "^STOXX50E",
        "FI" to "^STOXX50E", "IE" to "^STOXX50E", "PT" to "^STOXX50E",
        "GR" to "^STOXX50E", "LU" to "^STOXX50E"
    )

    fun defaultIndexForCountry(countryCode: String?): FinanceStockIndex {
        val symbol = countryCode
            ?.uppercase()
            ?.let(countryToDefaultSymbol::get)
            ?: "^GSPC"
        return allIndices.firstOrNull { it.symbol == symbol } ?: allIndices.first()
    }

    fun indexForSymbol(symbol: String?): FinanceStockIndex {
        return allIndices.firstOrNull { it.symbol == symbol } ?: allIndices.first()
    }

    fun instrumentFor(assetType: FinanceAssetType, symbol: String?): FinanceInstrument {
        val list = when (assetType) {
            FinanceAssetType.INDEX -> allIndices.map { FinanceInstrument(it.symbol, it.displayName, it.country, FinanceAssetType.INDEX) }
            FinanceAssetType.CRYPTO -> allCrypto
            FinanceAssetType.FX -> allFx
        }
        return list.firstOrNull { it.symbol == symbol } ?: list.first()
    }

    fun fetchTile(config: FinanceDashboardTileConfig): Result<FinanceTileSnapshot> {
        return when (config.assetType) {
            FinanceAssetType.INDEX -> fetchChart(config.symbol, FinanceTimeRange.fromRange(config.range)).map {
                it.toTileSnapshot(config)
            }
            FinanceAssetType.CRYPTO -> fetchCryptoChart(config)
            FinanceAssetType.FX -> fetchFxChart(config)
        }
    }

    fun fetchChart(symbol: String, range: FinanceTimeRange): Result<FinanceSnapshot> {
        return runCatching {
            val index = indexForSymbol(symbol)
            val urlStr = buildChartUrl(index.symbol, range)
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Finance HTTP $responseCode")
                }

                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(json)
                val result = root.getJSONObject("chart")
                    .getJSONArray("result").getJSONObject(0)
                val meta = result.optJSONObject("meta")
                val timestamps = result.optJSONArray("timestamp")
                val quote = result.getJSONObject("indicators")
                    .getJSONArray("quote").getJSONObject(0)
                val openPrices = quote.optJSONArray("open")
                val highPrices = quote.optJSONArray("high")
                val lowPrices = quote.optJSONArray("low")
                val closePrices = quote.getJSONArray("close")

                val rawPrices = mutableListOf<Float>()
                val timedPrices = mutableListOf<TimedPrice>()
                val rawCandles = mutableListOf<FinanceCandle>()
                val timedCandles = mutableListOf<TimedCandle>()
                for (i in 0 until closePrices.length()) {
                    if (!closePrices.isNull(i)) {
                        val price = closePrices.getDouble(i).toFloat()
                        rawPrices.add(price)
                        if (timestamps != null && i < timestamps.length() && !timestamps.isNull(i)) {
                            val timestampSeconds = timestamps.getLong(i)
                            timedPrices.add(TimedPrice(timestampSeconds, price))
                            val candle = buildYahooCandle(
                                timestampSeconds = timestampSeconds,
                                openPrices = openPrices,
                                highPrices = highPrices,
                                lowPrices = lowPrices,
                                closePrices = closePrices,
                                index = i
                            )
                            if (candle != null) {
                                rawCandles.add(candle.candle)
                                timedCandles.add(candle)
                            }
                        }
                    }
                }

                val chart = buildChartSnapshot(
                    requestRange = range,
                    rawPrices = rawPrices,
                    rawCandles = rawCandles,
                    timedPrices = timedPrices,
                    timedCandles = timedCandles,
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

                FinanceSnapshot(
                    symbol = index.symbol,
                    displayName = index.displayName,
                    country = index.country,
                    range = range.range,
                    rangeLabel = range.label,
                    points = chart.points,
                    candles = chart.candles,
                    percentChange = chart.percentChange,
                    assetType = FinanceAssetType.INDEX,
                    chartType = FinanceChartType.LINE
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun FinanceSnapshot.toTileSnapshot(config: FinanceDashboardTileConfig): FinanceTileSnapshot =
        FinanceTileSnapshot(
            id = config.id,
            assetType = FinanceAssetType.INDEX,
            symbol = symbol,
            displayName = displayName,
            country = country,
            range = range,
            rangeLabel = rangeLabel,
            chartType = config.chartType,
            points = points,
            candles = candles,
            percentChange = percentChange,
            source = "YAHOO",
            fetchedAtMs = fetchedAtMs,
            staleAfterMs = staleAfterMs
        )

    private fun fetchCryptoChart(config: FinanceDashboardTileConfig): Result<FinanceTileSnapshot> = runCatching {
        val range = FinanceTimeRange.fromRange(config.range)
        val instrument = instrumentFor(FinanceAssetType.CRYPTO, config.symbol)
        val interval = binanceInterval(range)
        val url = "$BINANCE_REST_BASE?symbol=${instrument.symbol}&interval=$interval&limit=120"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Binance HTTP ${connection.responseCode}")
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val root = org.json.JSONArray(json)
            val candles = buildList {
                for (i in 0 until root.length()) {
                    val item = root.getJSONArray(i)
                    add(
                        FinanceCandle(
                            openTimeMs = item.getLong(0),
                            open = item.getString(1).toFloat(),
                            high = item.getString(2).toFloat(),
                            low = item.getString(3).toFloat(),
                            close = item.getString(4).toFloat(),
                            volume = item.getString(5).toFloatOrNull() ?: 0f,
                            isClosed = true
                        )
                    )
                }
            }
            if (candles.isEmpty()) throw IllegalStateException("Insufficient crypto data")
            val baseline = candles.first().open.takeIf { it != 0f } ?: candles.first().close
            FinanceTileSnapshot(
                id = config.id,
                assetType = FinanceAssetType.CRYPTO,
                symbol = instrument.symbol,
                displayName = instrument.displayName,
                country = instrument.country,
                range = range.range,
                rangeLabel = range.label,
                chartType = config.chartType,
                points = candles.map { it.close },
                candles = candles,
                percentChange = ((candles.last().close - baseline) / baseline) * 100f,
                source = "BINANCE_REST",
                streamStatus = "Snapshot"
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchFxChart(config: FinanceDashboardTileConfig): Result<FinanceTileSnapshot> = runCatching {
        val range = FinanceTimeRange.fromRange(config.range)
        val instrument = instrumentFor(FinanceAssetType.FX, config.symbol)
        val base = instrument.symbol.take(3)
        val quote = instrument.symbol.takeLast(3)
        val daysBack = when (range) {
            FinanceTimeRange.ONE_DAY -> 2
            FinanceTimeRange.ONE_WEEK -> 7
            FinanceTimeRange.ONE_MONTH -> 31
            FinanceTimeRange.THREE_MONTHS -> 93
            FinanceTimeRange.SIX_MONTHS -> 186
            FinanceTimeRange.ONE_YEAR -> 366
            FinanceTimeRange.FIVE_YEARS -> 366 * 5
        }
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val end = dateFormat.format(calendar.time)
        calendar.add(Calendar.DAY_OF_YEAR, -daysBack)
        val start = dateFormat.format(calendar.time)
        val url = "$FX_REST_BASE/$start..$end?from=$base&to=$quote"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("FX HTTP ${connection.responseCode}")
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val rates = JSONObject(json).getJSONObject("rates")
            val prices = rates.keys().asSequence()
                .toList()
                .sorted()
                .mapNotNull { key -> rates.optJSONObject(key)?.optDouble(quote)?.takeIf { it.isFinite() }?.toFloat() }
            if (prices.isEmpty()) throw IllegalStateException("Insufficient FX data")
            val renderable = if (prices.size == 1) listOf(prices.first(), prices.first()) else prices
            val baseline = renderable.first().takeIf { it != 0f } ?: renderable.last()
            FinanceTileSnapshot(
                id = config.id,
                assetType = FinanceAssetType.FX,
                symbol = instrument.symbol,
                displayName = instrument.displayName,
                country = instrument.country,
                range = range.range,
                rangeLabel = range.label,
                chartType = FinanceChartType.LINE,
                points = renderable,
                percentChange = ((renderable.last() - baseline) / baseline) * 100f,
                source = "FRANKFURTER"
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun binanceInterval(range: FinanceTimeRange): String =
        when (range) {
            FinanceTimeRange.ONE_DAY -> "5m"
            FinanceTimeRange.ONE_WEEK -> "15m"
            FinanceTimeRange.ONE_MONTH -> "1h"
            FinanceTimeRange.THREE_MONTHS -> "4h"
            FinanceTimeRange.SIX_MONTHS -> "1d"
            FinanceTimeRange.ONE_YEAR -> "1d"
            FinanceTimeRange.FIVE_YEARS -> "1w"
        }

    private fun buildChartUrl(symbol: String, range: FinanceTimeRange): String {
        val encodedSymbol = URLEncoder.encode(symbol, "UTF-8")
        val query = if (range == FinanceTimeRange.ONE_DAY) {
            "?range=$ONE_DAY_LOOKBACK_RANGE&interval=${range.interval}&includePrePost=false"
        } else {
            "?range=${range.range}&interval=${range.interval}"
        }
        return "https://query1.finance.yahoo.com/v8/finance/chart/$encodedSymbol$query"
    }

    private data class TimedPrice(
        val timestampSeconds: Long,
        val price: Float
    )

    private data class TimedCandle(
        val timestampSeconds: Long,
        val candle: FinanceCandle
    )

    private data class ChartSnapshot(
        val points: List<Float>,
        val candles: List<FinanceCandle>,
        val percentChange: Float
    )

    private data class TradingSessionSnapshot(
        val prices: List<Float>,
        val candles: List<FinanceCandle>,
        val baselinePrice: Float?
    )

    private fun buildChartSnapshot(
        requestRange: FinanceTimeRange,
        rawPrices: List<Float>,
        rawCandles: List<FinanceCandle>,
        timedPrices: List<TimedPrice>,
        timedCandles: List<TimedCandle>,
        exchangeTimeZoneId: String?,
        fallbackPreviousClose: Float?
    ): ChartSnapshot {
        val oneDaySnapshot = if (requestRange == FinanceTimeRange.ONE_DAY) {
            extractLatestTradingSession(timedPrices, timedCandles, exchangeTimeZoneId, fallbackPreviousClose)
        } else {
            null
        }

        val sessionPrices = oneDaySnapshot?.prices ?: rawPrices
        val sessionCandles = oneDaySnapshot?.candles ?: rawCandles
        if (sessionPrices.isEmpty()) throw IllegalStateException("Insufficient chart data")
        val renderablePrices = if (sessionPrices.size >= 2) {
            sessionPrices
        } else if (requestRange == FinanceTimeRange.ONE_DAY) {
            listOf(sessionPrices.first(), sessionPrices.first())
        } else {
            throw IllegalStateException("Insufficient chart data")
        }

        val baseline = when (requestRange) {
            FinanceTimeRange.ONE_DAY -> oneDaySnapshot?.baselinePrice ?: renderablePrices.first()
            else -> renderablePrices.first()
        }
        if (baseline == 0f) throw IllegalStateException("Invalid chart baseline")

        return ChartSnapshot(
            points = renderablePrices,
            candles = sessionCandles,
            percentChange = ((renderablePrices.last() - baseline) / baseline) * 100f
        )
    }

    private fun extractLatestTradingSession(
        points: List<TimedPrice>,
        candles: List<TimedCandle>,
        exchangeTimeZoneId: String?,
        fallbackPreviousClose: Float?
    ): TradingSessionSnapshot {
        if (points.isEmpty()) {
            return TradingSessionSnapshot(emptyList(), emptyList(), fallbackPreviousClose)
        }

        val timeZone = resolveExchangeTimeZone(exchangeTimeZoneId)
        val targetDayKey = tradingDayKey(points.last().timestampSeconds, timeZone)
        val sessionPoints = points.filter { tradingDayKey(it.timestampSeconds, timeZone) == targetDayKey }
        val sessionCandles = candles
            .filter { tradingDayKey(it.timestampSeconds, timeZone) == targetDayKey }
            .map { it.candle }
        val previousSessionClose = points
            .asReversed()
            .firstOrNull { tradingDayKey(it.timestampSeconds, timeZone) < targetDayKey }
            ?.price
            ?: fallbackPreviousClose

        return TradingSessionSnapshot(sessionPoints.map { it.price }, sessionCandles, previousSessionClose)
    }

    private fun buildYahooCandle(
        timestampSeconds: Long,
        openPrices: org.json.JSONArray?,
        highPrices: org.json.JSONArray?,
        lowPrices: org.json.JSONArray?,
        closePrices: org.json.JSONArray,
        index: Int
    ): TimedCandle? {
        val open = openPrices.optFiniteFloat(index) ?: return null
        val high = highPrices.optFiniteFloat(index) ?: return null
        val low = lowPrices.optFiniteFloat(index) ?: return null
        val close = closePrices.optFiniteFloat(index) ?: return null
        return TimedCandle(
            timestampSeconds = timestampSeconds,
            candle = FinanceCandle(
                openTimeMs = timestampSeconds * 1000L,
                open = open,
                high = high,
                low = low,
                close = close,
                isClosed = true
            )
        )
    }

    private fun org.json.JSONArray?.optFiniteFloat(index: Int): Float? {
        if (this == null || index !in 0 until length() || isNull(index)) return null
        return optDouble(index, Double.NaN)
            .takeIf { it.isFinite() }
            ?.toFloat()
    }

    private fun resolveExchangeTimeZone(exchangeTimeZoneId: String?): TimeZone {
        if (exchangeTimeZoneId.isNullOrBlank()) return TimeZone.getTimeZone("UTC")
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
}
