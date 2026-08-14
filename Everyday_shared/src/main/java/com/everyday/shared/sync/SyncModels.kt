package com.everyday.shared.sync

enum class SyncChannel(val wireName: String) {
    LOCATION("location"),
    WEATHER("weather"),
    CALENDAR("calendar"),
    NEWS("news"),
    FINANCE("finance"),
    NOTIFICATIONS("notifications"),
    MAP("map");

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
    val financeTiles: List<FinanceDashboardTileConfig> = emptyList(),
    val financeRefreshTileIds: List<String> = emptyList(),
    val financeSymbol: String? = null,
    val financeRange: String? = null,
    val financeAssetType: FinanceAssetType? = null,
    val financeChartType: FinanceChartType? = null,
    val financeTileId: String? = null,
    val financeLiveEnabled: Boolean? = null,
    val mapLat: Double? = null,
    val mapLon: Double? = null,
    /** Active map algorithm name; kept as a string so the shared wire model stays app-agnostic. */
    val mapPoiMode: String? = null,
    val mapPoiCategories: List<String> = emptyList(),
    val mapPoiRadiusM: Int? = null,
    val mapPoiMaxResults: Int? = null,
    val mapPoiImportantCategories: List<String> = emptyList(),
    val mapPoiImportantRadiiM: Map<String, Int> = emptyMap(),
    val mapPoiNormalCategories: List<String> = emptyList(),
    val mapPoiNormalMaxResults: Int? = null,
    val mapPoiShowClosest: Boolean? = null,
    val mapPoiClosestRadiusM: Int? = null,
    val newsSource: NewsSourceSpec? = null
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

enum class LocationFixQuality {
    VALID,
    MISSING,
    STALE,
    INACCURATE,
    IMPLAUSIBLE
}

/** Phone/location-provider owned fast stream; source time drives its short TTL. */
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val bearingDeg: Float?,
    val speedMps: Float?,
    val quality: LocationFixQuality,
    val timestampMs: Long = System.currentTimeMillis()
)

/** Returns speed only while this physical fix is both valid and fresh. */
fun LocationFix.usableSpeedMps(nowMs: Long = System.currentTimeMillis()): Float? =
    speedMps?.takeIf {
        quality == LocationFixQuality.VALID &&
            !SyncCachePolicy.isStale(timestampMs, SyncCachePolicy.LOCATION_STALE_AFTER_MS, nowMs) &&
            it.isFinite() && it >= 0f
    }

/** Forecast/place-provider owned slow stream; fetchedAtMs drives weather cache expiry. */
data class WeatherSnapshot(
    val town: String,
    val countryCode: String,
    val weather: WeatherData?,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val forecastLat: Double? = null,
    val forecastLon: Double? = null,
    val staleAfterMs: Long = SyncCachePolicy.WEATHER_STALE_AFTER_MS
)

data class MapPointSnapshot(val lat: Double, val lon: Double)
data class MapRoadSnapshot(val id: Long, val highway: String, val name: String?, val points: List<MapPointSnapshot>)
data class MapPoiSnapshot(val id: Long, val name: String?, val category: String, val important: Boolean, val point: MapPointSnapshot)
data class MapSnapshot(
    val center: MapPointSnapshot,
    val roads: List<MapRoadSnapshot>,
    val pois: List<MapPoiSnapshot>,
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

enum class NewsSourceKind {
    DEFAULT_LOCATION,
    QUERY,
    CUSTOM_RSS;

    companion object {
        fun fromWireName(value: String?): NewsSourceKind? =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * The user-selected source for the news widget.
 *
 * The uppercase factories intentionally mirror the wire-facing names used by the assistant
 * tool and persistence format while the lower-case helpers keep normal Kotlin call sites
 * readable.
 */
data class NewsSourceSpec(
    val kind: NewsSourceKind,
    val value: String? = null
) {
    init {
        when (kind) {
            NewsSourceKind.DEFAULT_LOCATION -> require(value.isNullOrBlank()) {
                "Default news source must not have a value"
            }
            NewsSourceKind.QUERY,
            NewsSourceKind.CUSTOM_RSS -> require(!value.isNullOrBlank()) {
                "News source value must not be blank"
            }
        }
    }

    val intent: String?
        get() = value.takeIf { kind == NewsSourceKind.QUERY }

    val url: String?
        get() = value.takeIf { kind == NewsSourceKind.CUSTOM_RSS }

    val description: String
        get() = when (kind) {
            NewsSourceKind.DEFAULT_LOCATION -> "Default location headlines"
            NewsSourceKind.QUERY -> "Query: ${intent.orEmpty()}"
            NewsSourceKind.CUSTOM_RSS -> "Custom RSS: ${url.orEmpty()}"
        }

    val sourceKind: NewsSourceKind
        get() = kind

    companion object {
        val DEFAULT_LOCATION: NewsSourceSpec = NewsSourceSpec(NewsSourceKind.DEFAULT_LOCATION)

        fun QUERY(intent: String): NewsSourceSpec =
            NewsSourceSpec(NewsSourceKind.QUERY, intent.trim())

        fun CUSTOM_RSS(url: String): NewsSourceSpec =
            NewsSourceSpec(NewsSourceKind.CUSTOM_RSS, url.trim())

        fun query(intent: String): NewsSourceSpec = QUERY(intent)

        fun customRss(url: String): NewsSourceSpec = CUSTOM_RSS(url)

        fun fromWire(kind: String?, value: String?): NewsSourceSpec? = runCatching {
            when (NewsSourceKind.fromWireName(kind)) {
                NewsSourceKind.DEFAULT_LOCATION -> DEFAULT_LOCATION
                NewsSourceKind.QUERY -> QUERY(value.orEmpty())
                NewsSourceKind.CUSTOM_RSS -> CUSTOM_RSS(value.orEmpty())
                null -> null
            }
        }.getOrNull()
    }
}

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
    val staleAfterMs: Long = SyncCachePolicy.NEWS_STALE_AFTER_MS,
    val sourceSpec: NewsSourceSpec = NewsSourceSpec.DEFAULT_LOCATION
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
    val tiles: List<FinanceTileSnapshot>
) {
    companion object {
        fun fromTiles(tiles: List<FinanceTileSnapshot>): FinanceSnapshot? {
            if (tiles.isEmpty()) return null
            return FinanceSnapshot(tiles)
        }
    }
}

data class PhoneNotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val subText: String,
    val postTime: Long,
    val importance: Int,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val category: String?
) {
    val previewText: String
        get() = listOf(title, text, subText)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" - ")
}

data class PhoneNotificationsSnapshot(
    val items: List<PhoneNotificationItem>,
    val listenerEnabled: Boolean,
    val capturedAtMs: Long = System.currentTimeMillis()
)

data class SyncSnapshot(
    val location: LocationFix? = null,
    val weather: WeatherSnapshot? = null,
    val calendar: CalendarSnapshot? = null,
    val news: NewsSnapshot? = null,
    val finance: FinanceSnapshot? = null,
    val notifications: PhoneNotificationsSnapshot? = null,
    val map: MapSnapshot? = null
) {
    val isEmpty: Boolean
        get() = location == null &&
            weather == null &&
            calendar == null &&
            news == null &&
            finance == null &&
            notifications == null &&
            map == null
}

data class SyncError(
    val channel: SyncChannel,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)
