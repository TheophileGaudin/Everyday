package com.everyday.shared.sync

import org.json.JSONArray
import org.json.JSONObject

object SyncProtocol {
    private const val EVENT_SYNC_REQUEST = "sync_request"
    private const val EVENT_SYNC_SNAPSHOT = "sync_snapshot"
    private const val EVENT_SYNC_ERROR = "sync_error"

    fun encodeRequest(request: SyncRequest): String {
        return JSONObject()
            .put("event", EVENT_SYNC_REQUEST)
            .put("channels", JSONArray().apply {
                request.channels.forEach { put(it.wireName) }
            })
            .put("force", request.force)
            .put("reason", request.reason)
            .put("requestedAtMs", request.requestedAtMs)
            .putNullable("countryCode", request.countryCode)
            .putNullable("financeSymbol", request.financeSymbol)
            .putNullable("financeRange", request.financeRange)
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
            financeSymbol = root.optStringOrNull("financeSymbol"),
            financeRange = root.optStringOrNull("financeRange")
        )
    }

    fun encodeSnapshot(snapshot: SyncSnapshot): String {
        return JSONObject()
            .put("event", EVENT_SYNC_SNAPSHOT)
            .putNullable("weather", snapshot.weather?.toJson())
            .putNullable("calendar", snapshot.calendar?.toJson())
            .putNullable("news", snapshot.news?.toJson())
            .putNullable("finance", snapshot.finance?.toJson())
            .toString()
    }

    fun decodeSnapshot(raw: String): SyncSnapshot? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (root.optString("event") != EVENT_SYNC_SNAPSHOT) return null
        return SyncSnapshot(
            weather = root.optJSONObject("weather")?.toWeatherSnapshot(),
            calendar = root.optJSONObject("calendar")?.toCalendarSnapshot(),
            news = root.optJSONObject("news")?.toNewsSnapshot(),
            finance = root.optJSONObject("finance")?.toFinanceSnapshot()
        )
    }

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

    fun weatherToJson(snapshot: WeatherSnapshot): JSONObject = snapshot.toJson()
    fun weatherFromJson(raw: String): WeatherSnapshot? =
        runCatching { JSONObject(raw).toWeatherSnapshot() }.getOrNull()

    fun calendarToJson(snapshot: CalendarSnapshot): JSONObject = snapshot.toJson()
    fun calendarFromJson(raw: String): CalendarSnapshot? =
        runCatching { JSONObject(raw).toCalendarSnapshot() }.getOrNull()

    fun newsToJson(snapshot: NewsSnapshot): JSONObject = snapshot.toJson()
    fun newsFromJson(raw: String): NewsSnapshot? =
        runCatching { JSONObject(raw).toNewsSnapshot() }.getOrNull()

    fun financeToJson(snapshot: FinanceSnapshot): JSONObject = snapshot.toJson()
    fun financeFromJson(raw: String): FinanceSnapshot? =
        runCatching { JSONObject(raw).toFinanceSnapshot() }.getOrNull()

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
            .put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
            .put("fetchedAtMs", fetchedAtMs)
            .put("staleAfterMs", staleAfterMs)
            .put("sourceMode", sourceMode)
    }

    private fun JSONObject.toCalendarSnapshot(): CalendarSnapshot {
        val items = optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.toCalendarEvent()?.let(::add)
            }
        }
        return CalendarSnapshot(
            account = optJSONObject("account")?.toCalendarAccount(),
            events = events,
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
            .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
            .put("fetchedAtMs", fetchedAtMs)
            .put("staleAfterMs", staleAfterMs)
    }

    private fun JSONObject.toNewsSnapshot(): NewsSnapshot {
        val items = optJSONArray("items") ?: JSONArray()
        val articles = buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.toNewsArticle()?.let(::add)
            }
        }
        return NewsSnapshot(
            countryCode = optString("countryCode", "US"),
            items = articles,
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
            .put("points", JSONArray().apply { points.forEach { put(it.toDouble()) } })
            .put("percentChange", percentChange.toDouble())
            .put("fetchedAtMs", fetchedAtMs)
            .put("staleAfterMs", staleAfterMs)
    }

    private fun JSONObject.toFinanceSnapshot(): FinanceSnapshot {
        val pointsJson = optJSONArray("points") ?: JSONArray()
        val points = buildList {
            for (index in 0 until pointsJson.length()) {
                add(pointsJson.optDouble(index).toFloat())
            }
        }
        return FinanceSnapshot(
            symbol = optString("symbol"),
            displayName = optString("displayName"),
            country = optString("country"),
            range = optString("range"),
            rangeLabel = optString("rangeLabel"),
            points = points,
            percentChange = optDouble("percentChange").toFloat(),
            fetchedAtMs = optLong("fetchedAtMs", 0L),
            staleAfterMs = optLong("staleAfterMs", SyncCachePolicy.FINANCE_STALE_AFTER_MS)
        )
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
