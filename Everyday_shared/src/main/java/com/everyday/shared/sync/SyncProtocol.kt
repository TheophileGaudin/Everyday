package com.everyday.shared.sync

import org.json.JSONArray
import org.json.JSONObject

object SyncProtocol {

    // ==================== Channel codecs ====================
    // Each codec is the single place that knows a channel's snapshot shape.
    // encodeSnapshot/decodeSnapshot and SyncSnapshotStore iterate CHANNEL_CODECS
    // instead of repeating one branch per channel.

    val LOCATION_CODEC: SyncChannelCodec<LocationFix> =
        object : SyncChannelCodec<LocationFix>(SyncChannel.LOCATION) {
            override fun extract(snapshot: SyncSnapshot) = snapshot.location
            override fun place(into: SyncSnapshot, value: LocationFix?) = into.copy(location = value)
            override fun toJson(value: LocationFix) = value.toJson()
            override fun fromJson(json: JSONObject) = json.toLocationFix()
            override fun shouldPersist(value: LocationFix): Boolean = false
        }

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

    val MAP_CODEC: SyncChannelCodec<MapSnapshot> =
        object : SyncChannelCodec<MapSnapshot>(SyncChannel.MAP) {
            override fun extract(snapshot: SyncSnapshot) = snapshot.map
            override fun place(into: SyncSnapshot, value: MapSnapshot?) = into.copy(map = value)
            override fun toJson(value: MapSnapshot) = value.toJson()
            override fun fromJson(json: JSONObject) = json.toMapSnapshot()
            override fun shouldPersist(value: MapSnapshot): Boolean = false
        }

    /** All channel codecs in a stable order. */
    val CHANNEL_CODECS: List<SyncChannelCodec<*>> =
        listOf(LOCATION_CODEC, WEATHER_CODEC, CALENDAR_CODEC, NEWS_CODEC, FINANCE_CODEC, NOTIFICATIONS_CODEC, MAP_CODEC)

    // ==================== Request ====================

    internal fun requestToJson(request: SyncRequest): JSONObject {
        return JSONObject()
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
            .putNullable("mapLat", request.mapLat)
            .putNullable("mapLon", request.mapLon)
            .putNullable("mapPoiMode", request.mapPoiMode)
            .put("mapPoiCategories", request.mapPoiCategories.toJsonArray { it })
            .putNullable("mapPoiRadiusM", request.mapPoiRadiusM)
            .putNullable("mapPoiMaxResults", request.mapPoiMaxResults)
            .put("mapPoiImportantCategories", request.mapPoiImportantCategories.toJsonArray { it })
            .put("mapPoiImportantRadiiM", JSONObject().apply {
                request.mapPoiImportantRadiiM.toSortedMap().forEach { (category, radius) -> put(category, radius) }
            })
            .put("mapPoiNormalCategories", request.mapPoiNormalCategories.toJsonArray { it })
            .putNullable("mapPoiNormalMaxResults", request.mapPoiNormalMaxResults)
            .putNullable("mapPoiShowClosest", request.mapPoiShowClosest)
            .putNullable("mapPoiClosestRadiusM", request.mapPoiClosestRadiusM)
            .putNullable("newsSource", request.newsSource?.toJson())
    }

    internal fun requestFromJson(root: JSONObject): SyncRequest? {
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
            },
            mapLat = root.optDoubleOrNull("mapLat"),
            mapLon = root.optDoubleOrNull("mapLon"),
            mapPoiMode = root.optStringOrNull("mapPoiMode"),
            mapPoiCategories = root.optJSONArray("mapPoiCategories").toStringList(),
            mapPoiRadiusM = root.optIntOrNull("mapPoiRadiusM"),
            mapPoiMaxResults = root.optIntOrNull("mapPoiMaxResults"),
            mapPoiImportantCategories = root.optJSONArray("mapPoiImportantCategories").toStringList(),
            mapPoiImportantRadiiM = root.optJSONObject("mapPoiImportantRadiiM")?.let { json ->
                buildMap {
                    json.keys().forEach { category -> put(category, json.optInt(category)) }
                }
            }.orEmpty(),
            mapPoiNormalCategories = root.optJSONArray("mapPoiNormalCategories").toStringList(),
            mapPoiNormalMaxResults = root.optIntOrNull("mapPoiNormalMaxResults"),
            mapPoiShowClosest = if (root.has("mapPoiShowClosest") && !root.isNull("mapPoiShowClosest")) {
                root.optBoolean("mapPoiShowClosest")
            } else {
                null
            },
            mapPoiClosestRadiusM = root.optIntOrNull("mapPoiClosestRadiusM"),
            newsSource = root.optJSONObject("newsSource")?.toNewsSourceSpec()
        )
    }

    // ==================== Snapshot ====================

    internal fun snapshotToJson(snapshot: SyncSnapshot): JSONObject {
        val json = JSONObject()
        for (codec in CHANNEL_CODECS) {
            json.putNullable(codec.channel.wireName, codec.extractJson(snapshot))
        }
        return json
    }

    internal fun snapshotFromJson(root: JSONObject): SyncSnapshot {
        var snapshot = SyncSnapshot()
        for (codec in CHANNEL_CODECS) {
            root.optJSONObject(codec.channel.wireName)?.let { snapshot = codec.placeFromJson(snapshot, it) }
        }
        return snapshot
    }

    // ==================== Error ====================

    internal fun errorToJson(error: SyncError): JSONObject {
        return JSONObject()
            .put("channel", error.channel.wireName)
            .put("message", error.message)
            .put("timestampMs", error.timestampMs)
    }

    internal fun errorFromJson(root: JSONObject): SyncError? {
        val channel = SyncChannel.fromWireName(root.optString("channel")) ?: return null
        return SyncError(
            channel = channel,
            message = root.optString("message", "Sync error"),
            timestampMs = root.optLong("timestampMs", System.currentTimeMillis())
        )
    }

    /**
     * A fix's [LocationFix.timestampMs] is on the sender's clock, and the phone's and the glasses'
     * clocks can be seconds apart (the glasses without Wi-Fi never correct theirs). So the fix also
     * says when it was sent, on that same clock, and the reader turns its age into a timestamp on
     * its own clock: freshness never compares the two clocks.
     */
    private fun LocationFix.toJson(nowMs: Long = System.currentTimeMillis()): JSONObject {
        return JSONObject()
            .put("lat", lat)
            .put("lon", lon)
            .putNullable("accuracyM", accuracyM)
            .putNullable("bearingDeg", bearingDeg)
            .putNullable("speedMps", speedMps)
            .put("quality", quality.name)
            .put("timestampMs", timestampMs)
            .put("sentAtMs", nowMs)
    }

    private fun JSONObject.toLocationFix(nowMs: Long = System.currentTimeMillis()): LocationFix {
        val sentTimestampMs = getLong("timestampMs")
        // Without sentAtMs (an older sender) the timestamp is taken as it is, as before.
        val timestampMs = optLongOrNull("sentAtMs")
            ?.let { sentAtMs -> nowMs - (sentAtMs - sentTimestampMs).coerceAtLeast(0L) }
            ?: sentTimestampMs
        return LocationFix(
            lat = getDouble("lat"),
            lon = getDouble("lon"),
            accuracyM = optDoubleOrNull("accuracyM")?.toFloat(),
            bearingDeg = optDoubleOrNull("bearingDeg")?.toFloat(),
            speedMps = optDoubleOrNull("speedMps")?.toFloat(),
            quality = LocationFixQuality.valueOf(getString("quality")),
            timestampMs = timestampMs
        )
    }

    // ==================== Per-type (de)serialization ====================

    private fun WeatherSnapshot.toJson(): JSONObject {
        return JSONObject()
            .put("town", town)
            .put("countryCode", countryCode)
            .put("fetchedAtMs", fetchedAtMs)
            .put("staleAfterMs", staleAfterMs)
            .putNullable("forecastLat", forecastLat)
            .putNullable("forecastLon", forecastLon)
            .putNullable("weather", weather?.toJson())
    }

    private fun JSONObject.toWeatherSnapshot(): WeatherSnapshot {
        return WeatherSnapshot(
            town = optString("town", "Unknown"),
            countryCode = optString("countryCode", "Unknown"),
            weather = optJSONObject("weather")?.toWeatherData(),
            fetchedAtMs = optLong("fetchedAtMs", 0L),
            forecastLat = optDoubleOrNull("forecastLat"),
            forecastLon = optDoubleOrNull("forecastLon"),
            staleAfterMs = optLong("staleAfterMs", SyncCachePolicy.WEATHER_STALE_AFTER_MS)
        )
    }

    private fun MapSnapshot.toJson(): JSONObject = JSONObject()
        .put("center", center.toJson())
        .put("timestampMs", timestampMs)
        .put("roads", roads.toJsonArray { road -> JSONObject()
            .put("id", road.id).put("highway", road.highway).putNullable("name", road.name)
            .put("points", road.points.toJsonArray { it.toJson() }) })
        .put("pois", pois.toJsonArray { poi -> JSONObject()
            .put("id", poi.id).putNullable("name", poi.name).put("category", poi.category)
            .put("important", poi.important).put("point", poi.point.toJson()) })

    private fun MapPointSnapshot.toJson() = JSONObject().put("lat", lat).put("lon", lon)

    private fun JSONObject.toMapSnapshot(): MapSnapshot = MapSnapshot(
        center = optJSONObject("center").toMapPoint(),
        roads = optJSONArray("roads").mapObjects { road -> MapRoadSnapshot(
            id = road.optLong("id"), highway = road.optString("highway"), name = road.optStringOrNull("name"),
            points = road.optJSONArray("points").mapObjects { it.toMapPoint() }
        ) },
        pois = optJSONArray("pois").mapObjects { poi -> MapPoiSnapshot(
            id = poi.optLong("id"), name = poi.optStringOrNull("name"), category = poi.optString("category"),
            important = poi.optBoolean("important"), point = poi.optJSONObject("point").toMapPoint()
        ) },
        timestampMs = optLong("timestampMs", System.currentTimeMillis())
    )

    private fun JSONObject?.toMapPoint() = MapPointSnapshot(this?.optDouble("lat") ?: 0.0, this?.optDouble("lon") ?: 0.0)

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
            .put("sourceSpec", sourceSpec.toJson())
    }

    private fun JSONObject.toNewsSnapshot(): NewsSnapshot {
        return NewsSnapshot(
            countryCode = optString("countryCode", "US"),
            items = optJSONArray("items").mapObjects { it.toNewsArticle() },
            fetchedAtMs = optLong("fetchedAtMs", 0L),
            staleAfterMs = optLong("staleAfterMs", SyncCachePolicy.NEWS_STALE_AFTER_MS),
            sourceSpec = optJSONObject("sourceSpec")?.toNewsSourceSpec()
                ?: NewsSourceSpec.DEFAULT_LOCATION
        )
    }

    private fun NewsSourceSpec.toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .putNullable("value", value)

    private fun JSONObject.toNewsSourceSpec(): NewsSourceSpec? =
        NewsSourceSpec.fromWire(optStringOrNull("kind"), optStringOrNull("value"))

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
        return JSONObject().put("tiles", tiles.toJsonArray { it.toJson() })
    }

    private fun JSONObject.toFinanceSnapshot(): FinanceSnapshot {
        return FinanceSnapshot(tiles = getJSONArray("tiles").mapObjects { it.toFinanceTileSnapshot() })
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

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null
}
