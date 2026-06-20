package com.everyday.shared.sync

import org.json.JSONArray
import org.json.JSONObject

object SyncProtocol {
    private const val EVENT_SYNC_REQUEST = "sync_request"
    private const val EVENT_SYNC_SNAPSHOT = "sync_snapshot"
    private const val EVENT_SYNC_ERROR = "sync_error"
    private const val EVENT_SPEED_SNAPSHOT = "speed_snapshot"

    // ==================== Channel codecs ====================
    // Each codec is the single place that knows a channel's snapshot shape.
    // encodeSnapshot/decodeSnapshot and SyncSnapshotStore iterate CHANNEL_CODECS
    // instead of repeating one branch per channel.

    val WEATHER_CODEC: SyncChannelCodec<WeatherSnapshot> =
        object : SyncChannelCodec<WeatherSnapshot>(SyncChannel.WEATHER) {
            override fun extract(snapshot: SyncSnapshot) = snapshot.weather
            override fun place(into: SyncSnapshot, value: WeatherSnapshot?) = into.copy(weather = value)
            override fun toJson(value: WeatherSnapshot) = value.toJson()
            override fun fromJson(json: JSONObject) = json.toWeatherSnapshot()
        }

    val CALENDAR_CODEC: SyncChannelCodec<CalendarSnapshot> =
        object : SyncChannelCodec<CalendarSnapshot>(SyncChannel.CALENDAR) {
            override fun extract(snapshot: SyncSnapshot) = snapshot.calendar
            override fun place(into: SyncSnapshot, value: CalendarSnapshot?) = into.copy(calendar = value)
            override fun toJson(value: CalendarSnapshot) = value.toJson()
            override fun fromJson(json: JSONObject) = json.toCalendarSnapshot()
        }

    val NEWS_CODEC: SyncChannelCodec<NewsSnapshot> =
        object : SyncChannelCodec<NewsSnapshot>(SyncChannel.NEWS) {
            override fun extract(snapshot: SyncSnapshot) = snapshot.news
            override fun place(into: SyncSnapshot, value: NewsSnapshot?) = into.copy(news = value)
            override fun toJson(value: NewsSnapshot) = value.toJson()
            override fun fromJson(json: JSONObject) = json.toNewsSnapshot()
        }

    val FINANCE_CODEC: SyncChannelCodec<FinanceSnapshot> =
        object : SyncChannelCodec<FinanceSnapshot>(SyncChannel.FINANCE) {
            override fun extract(snapshot: SyncSnapshot) = snapshot.finance
            override fun place(into: SyncSnapshot, value: FinanceSnapshot?) = into.copy(finance = value)
            override fun toJson(value: FinanceSnapshot) = value.toJson()
            override fun fromJson(json: JSONObject) = json.toFinanceSnapshot()

            // Live (streaming) finance updates are display-only and must not
            // overwrite the persisted snapshot.
            override fun shouldPersist(value: FinanceSnapshot): Boolean =
                value.tiles.none { it.source == "BINANCE_WS" || it.streamStatus == "Live" }
        }

    val NOTIFICATIONS_CODEC: SyncChannelCodec<PhoneNotificationsSnapshot> =
        object : SyncChannelCodec<PhoneNotificationsSnapshot>(SyncChannel.NOTIFICATIONS) {
            override fun extract(snapshot: SyncSnapshot) = snapshot.notifications
            override fun place(into: SyncSnapshot, value: PhoneNotificationsSnapshot?) =
                into.copy(notifications = value)
            override fun toJson(value: PhoneNotificationsSnapshot) = value.toJson()
            override fun fromJson(json: JSONObject) = json.toPhoneNotificationsSnapshot()

            override fun shouldPersist(value: PhoneNotificationsSnapshot): Boolean = false
        }

    /** All channel codecs in a stable order. */
    val CHANNEL_CODECS: List<SyncChannelCodec<*>> =
        listOf(WEATHER_CODEC, CALENDAR_CODEC, NEWS_CODEC, FINANCE_CODEC, NOTIFICATIONS_CODEC)

    // ==================== Request ====================

    fun encodeRequest(request: SyncRequest): String {
        return JSONObject()
            .put("event", EVENT_SYNC_REQUEST)
            .put("channels", request.channels.toJsonArray { it.wireName })
            .put("force", request.force)
            .put("reason", request.reason)
            .put("requestedAtMs", request.requestedAtMs)
            .putNullable("countryCode", request.countryCode)
            .put("financeTiles", request.financeTiles.toJsonArray { it.toJson() })
            .put("financeRefreshTileIds", request.financeRefreshTileIds.toJsonArray { it })
            .putNullable("financeSymbol", request.financeSymbol)
            .putNullable("financeRange", request.financeRange)
            .putNullable("financeAssetType", request.financeAssetType?.name)
            .putNullable("financeChartType", request.financeChartType?.name)
            .putNullable("financeTileId", request.financeTileId)
            .putNullable("financeLiveEnabled", request.financeLiveEnabled)
            .toString()
    }

    fun decodeRequest(raw: String): SyncRequest? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optString("event") != EVENT_SYNC_REQUEST) return null
        val channels = mutableSetOf<SyncChannel>()
        val items = root.optJSONArray("channels") ?: JSONArray()
        for (index in 0 until items.length()) {
            SyncChannel.fromWireName(items.optString(index))?.let(channels::add)
        }
        if (channels.isEmpty()) {
            channels += SyncChannel.ALL
        }
        return SyncRequest(
            channels = channels,
            force = root.optBoolean("force", false),
            reason = root.optString("reason", "manual"),
            requestedAtMs = root.optLong("requestedAtMs", System.currentTimeMillis()),
            countryCode = root.optStringOrNull("countryCode"),
            financeTiles = root.optJSONArray("financeTiles").mapObjects { it.toFinanceDashboardTileConfig() },
            financeRefreshTileIds = root.optJSONArray("financeRefreshTileIds").toStringList(),
            financeSymbol = root.optStringOrNull("financeSymbol"),
            financeRange = root.optStringOrNull("financeRange"),
            financeAssetType = root.optStringOrNull("financeAssetType")?.let(FinanceAssetType::fromWireName),
            financeChartType = root.optStringOrNull("financeChartType")?.let(FinanceChartType::fromWireName),
            financeTileId = root.optStringOrNull("financeTileId"),
            financeLiveEnabled = if (root.has("financeLiveEnabled") && !root.isNull("financeLiveEnabled")) {
                root.optBoolean("financeLiveEnabled")
            } else {
                null
            }
        )
    }

    // ==================== Snapshot ====================

    fun encodeSnapshot(snapshot: SyncSnapshot): String {
        val json = JSONObject().put("event", EVENT_SYNC_SNAPSHOT)
        for (codec in CHANNEL_CODECS) {
            json.putNullable(codec.channel.wireName, codec.extractJson(snapshot))
        }
        return json.toString()
    }

    fun decodeSnapshot(raw: String): SyncSnapshot? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optString("event") != EVENT_SYNC_SNAPSHOT) return null
        var snapshot = SyncSnapshot()
        for (codec in CHANNEL_CODECS) {
            root.optJSONObject(codec.channel.wireName)?.let { snapshot = codec.placeFromJson(snapshot, it) }
        }
        return snapshot
    }

    // ==================== Error ====================

    fun encodeError(error: SyncError): String {
        return JSONObject()
            .put("event", EVENT_SYNC_ERROR)
            .put("channel", error.channel.wireName)
            .put("message", error.message)
            .put("timestampMs", error.timestampMs)
            .toString()
    }

    fun decodeError(raw: String): SyncError? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optString("event") != EVENT_SYNC_ERROR) return null
        val channel = SyncChannel.fromWireName(root.optString("channel")) ?: return null
        return SyncError(
            channel = channel,
            message = root.optString("message", "Sync error"),
            timestampMs = root.optLong("timestampMs", System.currentTimeMillis())
        )
    }

    // ==================== Speed ====================

    fun encodeSpeedSnapshot(snapshot: SpeedSnapshot): String {
        return JSONObject()
            .put("event", EVENT_SPEED_SNAPSHOT)
            .putNullable("speedMps", snapshot.speedMps)
            .put("qualityOk", snapshot.qualityOk)
            .put("timestampMs", snapshot.timestampMs)
            .toString()
    }

    fun decodeSpeedSnapshot(raw: String): SpeedSnapshot? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optString("event") != EVENT_SPEED_SNAPSHOT) return null
        val speedMps = if (root.has("speedMps") && !root.isNull("speedMps")) {
            root.optDouble("speedMps").toFloat()
        } else {
            null
        }
        return SpeedSnapshot(
            speedMps = speedMps,
            qualityOk = root.optBoolean("qualityOk", false),
            timestampMs = root.optLong("timestampMs", System.currentTimeMillis())
        )
    }

    // ==================== Per-type (de)serialization ====================

    private fun WeatherSnapshot.toJson(): JSONObject {
        return JSONObject()
            .put("town", town)
            .put("countryCode", countryCode)
            .put("timestampMs", timestampMs)
            .put("staleAfterMs", staleAfterMs)
            .putNullable("speedMps", speedMps)
            .putNullable("weather", weather?.toJson())
    }

    private fun JSONObject.toWeatherSnapshot(): WeatherSnapshot {
        return WeatherSnapshot(
            town = optString("town", "Unknown"),
            countryCode = optString("countryCode", "Unknown"),
            weather = optJSONObject("weather")?.toWeatherData(),
            timestampMs = optLong("timestampMs", 0L),
            speedMps = if (has("speedMps") && !isNull("speedMps")) optDouble("speedMps").toFloat() else null,
            staleAfterMs = optLong("staleAfterMs", SyncCachePolicy.WEATHER_STALE_AFTER_MS)
        )
    }

    private fun WeatherData.toJson(): JSONObject {
        return JSONObject()
            .put("tempCurrent", tempCurrent)
            .put("weatherCodeCurrent", weatherCodeCurrent)
            .put("weatherDescCurrent", weatherDescCurrent)
            .put("tempMaxToday", tempMaxToday)
            .put("tempMinToday", tempMinToday)
            .put("weatherDescToday", weatherDescToday)
            .putNullable("sunriseEpochMs", sunriseEpochMs)
            .putNullable("sunsetEpochMs", sunsetEpochMs)
    }

    private fun JSONObject.toWeatherData(): WeatherData {
        return WeatherData(
            tempCurrent = optDouble("tempCurrent"),
            weatherCodeCurrent = optInt("weatherCodeCurrent"),
            weatherDescCurrent = optString("weatherDescCurrent"),
            tempMaxToday = optDouble("tempMaxToday"),
            tempMinToday = optDouble("tempMinToday"),
            weatherDescToday = optString("weatherDescToday"),
            sunriseEpochMs = optLongOrNull("sunriseEpochMs"),
            sunsetEpochMs = optLongOrNull("sunsetEpochMs")
        )
    }

    private fun CalendarSnapshot.toJson(): JSONObject {
        return JSONObject()
            .putNullable("account", account?.toJson())
            .put("events", events.toJsonArray { it.toJson() })
            .put("fetchedAtMs", fetchedAtMs)
            .put("staleAfterMs", staleAfterMs)
            .put("sourceMode", sourceMode)
    }

    private fun JSONObject.toCalendarSnapshot(): CalendarSnapshot {
        return CalendarSnapshot(
            account = optJSONObject("account")?.toCalendarAccount(),
            events = optJSONArray("events").mapObjects { it.toCalendarEvent() },
            fetchedAtMs = optLong("fetchedAtMs", 0L),
            staleAfterMs = optLong("staleAfterMs", SyncCachePolicy.CALENDAR_STALE_AFTER_MS),
            sourceMode = optString("sourceMode", "PHONE_FALLBACK")
        )
    }

    private fun CalendarAccount.toJson(): JSONObject {
        return JSONObject()
            .put("email", email)
            .putNullable("displayName", displayName)
    }

    private fun JSONObject.toCalendarAccount(): CalendarAccount? {
        val email = optString("email")
        if (email.isBlank()) return null
        return CalendarAccount(
            email = email,
            displayName = optStringOrNull("displayName")
        )
    }

    private fun CalendarEventSnapshot.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("summary", summary)
            .put("startIso", startIso)
            .putNullable("htmlLink", htmlLink)
    }

    private fun JSONObject.toCalendarEvent(): CalendarEventSnapshot {
        return CalendarEventSnapshot(
            id = optString("id"),
            summary = optString("summary"),
            startIso = optString("startIso"),
            htmlLink = optStringOrNull("htmlLink")
        )
    }

    private fun NewsSnapshot.toJson(): JSONObject {
        return JSONObject()
            .put("countryCode", countryCode)
            .put("items", items.toJsonArray { it.toJson() })
            .put("fetchedAtMs", fetchedAtMs)
            .put("staleAfterMs", staleAfterMs)
    }

    private fun JSONObject.toNewsSnapshot(): NewsSnapshot {
        return NewsSnapshot(
            countryCode = optString("countryCode", "US"),
            items = optJSONArray("items").mapObjects { it.toNewsArticle() },
            fetchedAtMs = optLong("fetchedAtMs", 0L),
            staleAfterMs = optLong("staleAfterMs", SyncCachePolicy.NEWS_STALE_AFTER_MS)
        )
    }

    private fun NewsArticle.toJson(): JSONObject {
        return JSONObject()
            .put("title", title)
            .put("content", content)
            .put("link", link)
            .put("source", source)
            .put("publishedAt", publishedAt)
    }

    private fun JSONObject.toNewsArticle(): NewsArticle {
        return NewsArticle(
            title = optString("title"),
            content = optString("content"),
            link = optString("link"),
            source = optString("source"),
            publishedAt = optString("publishedAt")
        )
    }

    private fun FinanceSnapshot.toJson(): JSONObject {
        return JSONObject()
            .put("symbol", symbol)
            .put("displayName", displayName)
            .put("country", country)
            .put("range", range)
            .put("rangeLabel", rangeLabel)
            .put("points", points.toJsonArray { it.toDouble() })
            .put("candles", candles.toJsonArray { it.toJson() })
            .put("percentChange", percentChange.toDouble())
            .put("assetType", assetType.name)
            .put("chartType", chartType.name)
            .put("navigationIndex", navigationIndex)
            .put("tiles", tiles.toJsonArray { it.toJson() })
            .put("fetchedAtMs", fetchedAtMs)
            .put("staleAfterMs", staleAfterMs)
    }

    private fun JSONObject.toFinanceSnapshot(): FinanceSnapshot {
        return FinanceSnapshot(
            symbol = optString("symbol"),
            displayName = optString("displayName"),
            country = optString("country"),
            range = optString("range"),
            rangeLabel = optString("rangeLabel"),
            points = optJSONArray("points").toFloatList(),
            candles = optJSONArray("candles").mapObjects { it.toFinanceCandle() },
            percentChange = optDouble("percentChange").toFloat(),
            assetType = FinanceAssetType.fromWireName(optStringOrNull("assetType")),
            chartType = FinanceChartType.fromWireName(optStringOrNull("chartType")),
            tiles = optJSONArray("tiles").mapObjects { it.toFinanceTileSnapshot() },
            navigationIndex = optInt("navigationIndex", 0),
            fetchedAtMs = optLong("fetchedAtMs", 0L),
            staleAfterMs = optLong("staleAfterMs", SyncCachePolicy.FINANCE_STALE_AFTER_MS)
        )
    }

    private fun FinanceTileSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("assetType", assetType.name)
            .put("symbol", symbol)
            .put("displayName", displayName)
            .put("country", country)
            .put("range", range)
            .put("rangeLabel", rangeLabel)
            .put("chartType", chartType.name)
            .put("points", points.toJsonArray { it.toDouble() })
            .put("candles", candles.toJsonArray { it.toJson() })
            .putNullable("latestValue", latestValue?.toDouble())
            .put("percentChange", percentChange.toDouble())
            .put("source", source)
            .putNullable("streamStatus", streamStatus)
            .put("fetchedAtMs", fetchedAtMs)
            .put("staleAfterMs", staleAfterMs)

    private fun JSONObject.toFinanceTileSnapshot(): FinanceTileSnapshot {
        return FinanceTileSnapshot(
            id = optString("id"),
            assetType = FinanceAssetType.fromWireName(optStringOrNull("assetType")),
            symbol = optString("symbol"),
            displayName = optString("displayName"),
            country = optString("country"),
            range = optString("range"),
            rangeLabel = optString("rangeLabel"),
            chartType = FinanceChartType.fromWireName(optStringOrNull("chartType")),
            points = optJSONArray("points").toFloatList(),
            candles = optJSONArray("candles").mapObjects { it.toFinanceCandle() },
            latestValue = if (has("latestValue") && !isNull("latestValue")) optDouble("latestValue").toFloat() else null,
            percentChange = optDouble("percentChange").toFloat(),
            source = optString("source", "UNKNOWN"),
            streamStatus = optStringOrNull("streamStatus"),
            fetchedAtMs = optLong("fetchedAtMs", 0L),
            staleAfterMs = optLong("staleAfterMs", SyncCachePolicy.FINANCE_STALE_AFTER_MS)
        )
    }

    private fun FinanceDashboardTileConfig.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("assetType", assetType.name)
            .put("symbol", symbol)
            .put("range", range)
            .put("chartType", chartType.name)
            .put("slot", slot)

    private fun JSONObject.toFinanceDashboardTileConfig(): FinanceDashboardTileConfig? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val symbol = optString("symbol").takeIf { it.isNotBlank() } ?: return null
        return FinanceDashboardTileConfig(
            id = id,
            assetType = FinanceAssetType.fromWireName(optStringOrNull("assetType")),
            symbol = symbol,
            range = FinanceTimeRange.fromRange(optStringOrNull("range")).range,
            chartType = FinanceChartType.fromWireName(optStringOrNull("chartType")),
            slot = optInt("slot", -1)
        )
    }

    private fun PhoneNotificationsSnapshot.toJson(): JSONObject =
        JSONObject()
            .put("items", items.toJsonArray { it.toJson() })
            .put("listenerEnabled", listenerEnabled)
            .put("capturedAtMs", capturedAtMs)

    private fun JSONObject.toPhoneNotificationsSnapshot(): PhoneNotificationsSnapshot =
        PhoneNotificationsSnapshot(
            items = optJSONArray("items").mapObjects { it.toPhoneNotificationItem() },
            listenerEnabled = optBoolean("listenerEnabled", false),
            capturedAtMs = optLong("capturedAtMs", 0L)
        )

    private fun PhoneNotificationItem.toJson(): JSONObject =
        JSONObject()
            .put("key", key)
            .put("packageName", packageName)
            .put("appLabel", appLabel)
            .put("title", title)
            .put("text", text)
            .put("subText", subText)
            .put("postTime", postTime)
            .put("importance", importance)
            .put("isOngoing", isOngoing)
            .put("isClearable", isClearable)
            .putNullable("category", category)

    private fun JSONObject.toPhoneNotificationItem(): PhoneNotificationItem? {
        val key = optString("key").takeIf { it.isNotBlank() } ?: return null
        return PhoneNotificationItem(
            key = key,
            packageName = optString("packageName"),
            appLabel = optString("appLabel"),
            title = optString("title"),
            text = optString("text"),
            subText = optString("subText"),
            postTime = optLong("postTime", 0L),
            importance = optInt("importance", 0),
            isOngoing = optBoolean("isOngoing", false),
            isClearable = optBoolean("isClearable", true),
            category = optStringOrNull("category")
        )
    }

    private fun FinanceCandle.toJson(): JSONObject =
        JSONObject()
            .put("openTimeMs", openTimeMs)
            .put("open", open.toDouble())
            .put("high", high.toDouble())
            .put("low", low.toDouble())
            .put("close", close.toDouble())
            .put("volume", volume.toDouble())
            .put("isClosed", isClosed)

    private fun JSONObject.toFinanceCandle(): FinanceCandle =
        FinanceCandle(
            openTimeMs = optLong("openTimeMs"),
            open = optDouble("open").toFloat(),
            high = optDouble("high").toFloat(),
            low = optDouble("low").toFloat(),
            close = optDouble("close").toFloat(),
            volume = optDouble("volume", 0.0).toFloat(),
            isClosed = optBoolean("isClosed", true)
        )

    // ==================== JSON helpers ====================

    /** Build a [JSONArray] from any iterable, mapping each element via [transform]. */
    private inline fun <T> Iterable<T>.toJsonArray(transform: (T) -> Any): JSONArray =
        JSONArray().apply { this@toJsonArray.forEach { put(transform(it)) } }

    /** Map each JSON object element via [transform], dropping nulls. Null array -> empty. */
    private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(transform)?.let(::add)
            }
        }
    }

    private fun JSONArray?.toFloatList(): List<Float> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                add(optDouble(index).toFloat())
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject {
        if (value == null) {
            put(name, JSONObject.NULL)
        } else {
            put(name, value)
        }
        return this
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null
}
