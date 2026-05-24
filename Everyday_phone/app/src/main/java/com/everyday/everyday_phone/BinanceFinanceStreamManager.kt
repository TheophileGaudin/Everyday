package com.everyday.everyday_phone

import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceCandle
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceTileSnapshot
import com.everyday.shared.sync.FinanceTimeRange
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class BinanceFinanceStreamManager(
    private val onSnapshot: (FinanceSnapshot) -> Unit,
    private val log: (String) -> Unit
) {
    private val client = OkHttpClient()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val lastSentAtMs = ConcurrentHashMap<String, Long>()
    private val latestCandles = ConcurrentHashMap<String, MutableList<FinanceCandle>>()

    fun subscribe(config: FinanceDashboardTileConfig, seed: FinanceTileSnapshot? = null) {
        if (config.assetType != FinanceAssetType.CRYPTO) return
        val key = key(config)
        seed?.candles?.takeIf { it.isNotEmpty() }?.let {
            latestCandles[key] = it.takeLast(120).toMutableList()
        }
        if (sockets.containsKey(key)) return
        val stream = "${config.symbol.lowercase(Locale.US)}@kline_${binanceInterval(FinanceTimeRange.fromRange(config.range))}"
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/ws/$stream")
            .build()
        sockets[key] = client.newWebSocket(request, Listener(config))
    }

    fun unsubscribe(config: FinanceDashboardTileConfig) {
        sockets.remove(key(config))?.close(1000, "tile removed")
    }

    fun stopAll() {
        sockets.values.forEach { it.close(1000, "finance stream stopped") }
        sockets.clear()
    }

    private inner class Listener(private val config: FinanceDashboardTileConfig) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val tile = runCatching { parseKline(config, text) }.getOrElse {
                log("Binance parse failed: ${it.message}")
                return
            }
            val now = System.currentTimeMillis()
            val key = key(config)
            if (now - (lastSentAtMs[key] ?: 0L) < 1000L) return
            lastSentAtMs[key] = now
            onSnapshot(tile.toSnapshot())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets.remove(key(config))
            log("Binance stream failed for ${config.symbol}: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sockets.remove(key(config))
        }
    }

    private fun parseKline(config: FinanceDashboardTileConfig, raw: String): FinanceTileSnapshot {
        val root = JSONObject(raw)
        val kline = root.getJSONObject("k")
        val candle = FinanceCandle(
            openTimeMs = kline.getLong("t"),
            open = kline.getString("o").toFloat(),
            high = kline.getString("h").toFloat(),
            low = kline.getString("l").toFloat(),
            close = kline.getString("c").toFloat(),
            volume = kline.getString("v").toFloatOrNull() ?: 0f,
            isClosed = kline.optBoolean("x", false)
        )
        val key = key(config)
        val candles = latestCandles.getOrPut(key) { mutableListOf() }
        val existingIndex = candles.indexOfLast { it.openTimeMs == candle.openTimeMs }
        if (existingIndex >= 0) {
            candles[existingIndex] = candle
        } else {
            candles.add(candle)
        }
        while (candles.size > 120) candles.removeAt(0)
        val instrument = FinanceDataProvider.instrumentFor(FinanceAssetType.CRYPTO, config.symbol)
        val baseline = candles.firstOrNull()?.open?.takeIf { it != 0f } ?: candle.open.takeIf { it != 0f } ?: candle.close
        return FinanceTileSnapshot(
            id = config.id,
            assetType = FinanceAssetType.CRYPTO,
            symbol = instrument.symbol,
            displayName = instrument.displayName,
            country = instrument.country,
            range = config.range,
            rangeLabel = FinanceTimeRange.fromRange(config.range).label,
            chartType = config.chartType,
            points = candles.map { it.close },
            candles = candles.toList(),
            percentChange = ((candle.close - baseline) / baseline) * 100f,
            source = "BINANCE_WS",
            streamStatus = "Live"
        )
    }

    private fun FinanceTileSnapshot.toSnapshot(): FinanceSnapshot =
        FinanceSnapshot(
            symbol = symbol,
            displayName = displayName,
            country = country,
            range = range,
            rangeLabel = rangeLabel,
            points = points,
            percentChange = percentChange,
            assetType = assetType,
            chartType = chartType,
            tiles = listOf(this),
            fetchedAtMs = fetchedAtMs,
            staleAfterMs = staleAfterMs
        )

    private fun key(config: FinanceDashboardTileConfig): String =
        "${config.id}:${config.symbol}:${config.range}"

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
}
