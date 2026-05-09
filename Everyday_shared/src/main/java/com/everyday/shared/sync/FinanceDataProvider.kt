package com.everyday.shared.sync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

object FinanceDataProvider {
    private const val ONE_DAY_LOOKBACK_RANGE = "5d"

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
                            timedPrices.add(TimedPrice(timestamps.getLong(i), price))
                        }
                    }
                }

                val chart = buildChartSnapshot(
                    requestRange = range,
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

                FinanceSnapshot(
                    symbol = index.symbol,
                    displayName = index.displayName,
                    country = index.country,
                    range = range.range,
                    rangeLabel = range.label,
                    points = chart.points,
                    percentChange = chart.percentChange
                )
            } finally {
                connection.disconnect()
            }
        }
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

    private data class ChartSnapshot(
        val points: List<Float>,
        val percentChange: Float
    )

    private data class TradingSessionSnapshot(
        val prices: List<Float>,
        val baselinePrice: Float?
    )

    private fun buildChartSnapshot(
        requestRange: FinanceTimeRange,
        rawPrices: List<Float>,
        timedPrices: List<TimedPrice>,
        exchangeTimeZoneId: String?,
        fallbackPreviousClose: Float?
    ): ChartSnapshot {
        val oneDaySnapshot = if (requestRange == FinanceTimeRange.ONE_DAY) {
            extractLatestTradingSession(timedPrices, exchangeTimeZoneId, fallbackPreviousClose)
        } else {
            null
        }

        val sessionPrices = oneDaySnapshot?.prices ?: rawPrices
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
            percentChange = ((renderablePrices.last() - baseline) / baseline) * 100f
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

        return TradingSessionSnapshot(sessionPoints.map { it.price }, previousSessionClose)
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
