package com.everyday.shared.sync

import android.content.Context
import org.json.JSONObject

class SyncSnapshotStore(context: Context) : SyncSnapshotCache {
    companion object {
        private const val PREFS_NAME = "sync_snapshots"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheLock = Any()

    // In-memory cache keyed by channel. A channel present in [loaded] has been
    // read from prefs (its value may legitimately be null), so we never re-read.
    private val cache = mutableMapOf<SyncChannel, Any?>()
    private val loaded = mutableSetOf<SyncChannel>()

    override fun save(snapshot: SyncSnapshot) {
        for (codec in SyncProtocol.CHANNEL_CODECS) {
            codec.extractAny(snapshot)?.let { storeValue(codec, it) }
        }
    }

    override fun loadAll(): SyncSnapshot = load(SyncChannel.ALL)

    override fun load(channels: Set<SyncChannel>): SyncSnapshot {
        var snapshot = SyncSnapshot()
        for (codec in SyncProtocol.CHANNEL_CODECS) {
            if (codec.channel in channels) {
                snapshot = codec.placeAny(snapshot, loadValue(codec))
            }
        }
        return snapshot
    }

    // ==================== Typed accessors ====================

    fun saveWeather(snapshot: WeatherSnapshot) = storeValue(SyncProtocol.WEATHER_CODEC, snapshot)
    fun loadWeather(): WeatherSnapshot? = loadValue(SyncProtocol.WEATHER_CODEC) as WeatherSnapshot?

    fun saveCalendar(snapshot: CalendarSnapshot) = storeValue(SyncProtocol.CALENDAR_CODEC, snapshot)
    fun loadCalendar(): CalendarSnapshot? = loadValue(SyncProtocol.CALENDAR_CODEC) as CalendarSnapshot?
    fun clearCalendar() = clearValue(SyncProtocol.CALENDAR_CODEC)

    fun saveNews(snapshot: NewsSnapshot) = storeValue(SyncProtocol.NEWS_CODEC, snapshot)
    fun loadNews(): NewsSnapshot? = loadValue(SyncProtocol.NEWS_CODEC) as NewsSnapshot?

    fun saveFinance(snapshot: FinanceSnapshot) = storeValue(SyncProtocol.FINANCE_CODEC, snapshot)
    fun loadFinance(): FinanceSnapshot? = loadValue(SyncProtocol.FINANCE_CODEC) as FinanceSnapshot?

    fun saveNotifications(snapshot: PhoneNotificationsSnapshot) =
        storeValue(SyncProtocol.NOTIFICATIONS_CODEC, snapshot)
    fun loadNotifications(): PhoneNotificationsSnapshot? =
        loadValue(SyncProtocol.NOTIFICATIONS_CODEC) as PhoneNotificationsSnapshot?

    // ==================== Generic channel storage ====================

    private fun storeValue(codec: SyncChannelCodec<*>, value: Any) {
        synchronized(cacheLock) {
            cache[codec.channel] = value
            loaded += codec.channel
        }
        if (codec.shouldPersistAny(value)) {
            prefs.edit().putString(codec.channel.wireName, codec.toJsonAny(value).toString()).apply()
        }
    }

    private fun loadValue(codec: SyncChannelCodec<*>): Any? = synchronized(cacheLock) {
        if (codec.channel !in loaded) {
            cache[codec.channel] = prefs.getString(codec.channel.wireName, null)?.let { raw ->
                runCatching { codec.fromJson(JSONObject(raw)) }.getOrNull()
            }
            loaded += codec.channel
        }
        cache[codec.channel]
    }

    private fun clearValue(codec: SyncChannelCodec<*>) {
        synchronized(cacheLock) {
            cache[codec.channel] = null
            loaded += codec.channel
        }
        prefs.edit().remove(codec.channel.wireName).apply()
    }
}
