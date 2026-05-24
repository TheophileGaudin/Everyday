package com.everyday.shared.sync

enum class SyncChannel(val wireName: String) {
    WEATHER("weather"),
    CALENDAR("calendar"),
    NEWS("news"),
    FINANCE("finance");

    companion object {
        val ALL: Set<SyncChannel> = values().toSet()

        fun fromWireName(value: String): SyncChannel? =
            values().firstOrNull { it.wireName == value.lowercase() }
    }
}

data class SyncRequest(
    val channels: Set<SyncChannel>,
    val force: Boolean = false,
    val reason: String = "manual",
    val requestedAtMs: Long = System.currentTimeMillis(),
    val countryCode: String? = null,
    val financeSymbol: String? = null,
    val financeRange: String? = null,
    val financeAssetType: FinanceAssetType? = null,
    val financeChartType: FinanceChartType? = null,
    val financeTileId: String? = null,
    val financeLiveEnabled: Boolean? = null
)

data class WeatherData(
    val tempCurrent: Double,
    val weatherCodeCurrent: Int,
    val weatherDescCurrent: String,
    val tempMaxToday: Double,
    val tempMinToday: Double,
    val weatherDescToday: String,
    val sunriseEpochMs: Long? = null,
    val sunsetEpochMs: Long? = null
)

data class LocationName(
    val town: String,
    val countryCode: String
)

data class WeatherSnapshot(
    val town: String,
    val countryCode: String,
    val weather: WeatherData?,
    val timestampMs: Long = System.currentTimeMillis(),
    val speedMps: Float? = null,
    val staleAfterMs: Long = SyncCachePolicy.WEATHER_STALE_AFTER_MS
)

data class SpeedSnapshot(
    val speedMps: Float?,
    val qualityOk: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

data class CalendarAccount(
    val email: String,
    val displayName: String? = null
)

data class CalendarEventSnapshot(
    val id: String,
    val summary: String,
    val startIso: String,
    val htmlLink: String? = null
)

data class CalendarSnapshot(
    val account: CalendarAccount? = null,
    val events: List<CalendarEventSnapshot> = emptyList(),
    val fetchedAtMs: Long = 0L,
    val staleAfterMs: Long = SyncCachePolicy.CALENDAR_STALE_AFTER_MS,
    val sourceMode: String = "PHONE_FALLBACK"
)

data class NewsArticle(
    val title: String,
    val content: String,
    val link: String,
    val source: String,
    val publishedAt: String
)

data class NewsSnapshot(
    val countryCode: String,
    val items: List<NewsArticle>,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val staleAfterMs: Long = SyncCachePolicy.NEWS_STALE_AFTER_MS
)

data class FinanceStockIndex(
    val symbol: String,
    val displayName: String,
    val country: String
)

enum class FinanceAssetType {
    INDEX,
    CRYPTO,
    FX;

    companion object {
        fun fromWireName(value: String?): FinanceAssetType =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INDEX
    }
}

enum class FinanceChartType {
    LINE,
    CANDLE;

    companion object {
        fun fromWireName(value: String?): FinanceChartType =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LINE
    }
}

enum class FinanceTimeRange(val label: String, val range: String, val interval: String) {
    ONE_DAY("1D", "1d", "5m"),
    ONE_WEEK("1W", "5d", "15m"),
    ONE_MONTH("1M", "1mo", "1h"),
    THREE_MONTHS("3M", "3mo", "1d"),
    SIX_MONTHS("6M", "6mo", "1d"),
    ONE_YEAR("1Y", "1y", "1wk"),
    FIVE_YEARS("5Y", "5y", "1mo");

    companion object {
        fun fromRange(value: String?): FinanceTimeRange =
            values().firstOrNull { it.range == value || it.label == value } ?: ONE_DAY
    }
}

data class FinanceInstrument(
    val symbol: String,
    val displayName: String,
    val country: String,
    val assetType: FinanceAssetType
)

data class FinanceDashboardTileConfig(
    val id: String,
    val assetType: FinanceAssetType,
    val symbol: String,
    val range: String = FinanceTimeRange.ONE_DAY.range,
    val chartType: FinanceChartType = FinanceChartType.LINE,
    val slot: Int = -1
)

data class FinanceCandle(
    val openTimeMs: Long,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Float = 0f,
    val isClosed: Boolean = true
)

data class FinanceTileSnapshot(
    val id: String,
    val assetType: FinanceAssetType,
    val symbol: String,
    val displayName: String,
    val country: String,
    val range: String,
    val rangeLabel: String,
    val chartType: FinanceChartType,
    val points: List<Float> = emptyList(),
    val candles: List<FinanceCandle> = emptyList(),
    val latestValue: Float? = points.lastOrNull() ?: candles.lastOrNull()?.close,
    val percentChange: Float,
    val source: String = "UNKNOWN",
    val streamStatus: String? = null,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val staleAfterMs: Long = SyncCachePolicy.FINANCE_STALE_AFTER_MS
)

data class FinanceSnapshot(
    val symbol: String,
    val displayName: String,
    val country: String,
    val range: String,
    val rangeLabel: String,
    val points: List<Float>,
    val candles: List<FinanceCandle> = emptyList(),
    val percentChange: Float,
    val assetType: FinanceAssetType = FinanceAssetType.INDEX,
    val chartType: FinanceChartType = FinanceChartType.LINE,
    val tiles: List<FinanceTileSnapshot> = emptyList(),
    val navigationIndex: Int = 0,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val staleAfterMs: Long = SyncCachePolicy.FINANCE_STALE_AFTER_MS
)

data class SyncSnapshot(
    val weather: WeatherSnapshot? = null,
    val calendar: CalendarSnapshot? = null,
    val news: NewsSnapshot? = null,
    val finance: FinanceSnapshot? = null
) {
    val isEmpty: Boolean
        get() = weather == null && calendar == null && news == null && finance == null
}

data class SyncError(
    val channel: SyncChannel,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)
