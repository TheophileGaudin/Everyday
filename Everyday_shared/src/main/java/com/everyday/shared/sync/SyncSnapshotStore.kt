package com.everyday.shared.sync

import android.content.Context

class SyncSnapshotStore(context: Context) : SyncSnapshotCache {
    companion object {
        private const val PREFS_NAME = "sync_snapshots"
        private const val KEY_WEATHER = "weather"
        private const val KEY_CALENDAR = "calendar"
        private const val KEY_NEWS = "news"
        private const val KEY_FINANCE = "finance"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheLock = Any()
    private var weatherLoaded = false
    private var cachedWeather: WeatherSnapshot? = null
    private var calendarLoaded = false
    private var cachedCalendar: CalendarSnapshot? = null
    private var newsLoaded = false
    private var cachedNews: NewsSnapshot? = null
    private var financeLoaded = false
    private var cachedFinance: FinanceSnapshot? = null

    override fun save(snapshot: SyncSnapshot) {
        snapshot.weather?.let(::saveWeather)
        snapshot.calendar?.let(::saveCalendar)
        snapshot.news?.let(::saveNews)
        snapshot.finance?.let(::saveFinance)
    }

    fun saveWeather(snapshot: WeatherSnapshot) {
        synchronized(cacheLock) {
            cachedWeather = snapshot
            weatherLoaded = true
        }
        prefs.edit().putString(KEY_WEATHER, SyncProtocol.weatherToJson(snapshot).toString()).apply()
    }

    fun loadWeather(): WeatherSnapshot? = synchronized(cacheLock) {
        if (!weatherLoaded) {
            cachedWeather = prefs.getString(KEY_WEATHER, null)?.let(SyncProtocol::weatherFromJson)
            weatherLoaded = true
        }
        cachedWeather
    }

    fun clearWeather() {
        synchronized(cacheLock) {
            cachedWeather = null
            weatherLoaded = true
        }
        prefs.edit().remove(KEY_WEATHER).apply()
    }

    fun saveCalendar(snapshot: CalendarSnapshot) {
        synchronized(cacheLock) {
            cachedCalendar = snapshot
            calendarLoaded = true
        }
        prefs.edit().putString(KEY_CALENDAR, SyncProtocol.calendarToJson(snapshot).toString()).apply()
    }

    fun loadCalendar(): CalendarSnapshot? = synchronized(cacheLock) {
        if (!calendarLoaded) {
            cachedCalendar = prefs.getString(KEY_CALENDAR, null)?.let(SyncProtocol::calendarFromJson)
            calendarLoaded = true
        }
        cachedCalendar
    }

    fun clearCalendar() {
        synchronized(cacheLock) {
            cachedCalendar = null
            calendarLoaded = true
        }
        prefs.edit().remove(KEY_CALENDAR).apply()
    }

    fun saveNews(snapshot: NewsSnapshot) {
        synchronized(cacheLock) {
            cachedNews = snapshot
            newsLoaded = true
        }
        prefs.edit().putString(KEY_NEWS, SyncProtocol.newsToJson(snapshot).toString()).apply()
    }

    fun loadNews(): NewsSnapshot? = synchronized(cacheLock) {
        if (!newsLoaded) {
            cachedNews = prefs.getString(KEY_NEWS, null)?.let(SyncProtocol::newsFromJson)
            newsLoaded = true
        }
        cachedNews
    }

    fun saveFinance(snapshot: FinanceSnapshot) {
        synchronized(cacheLock) {
            cachedFinance = snapshot
            financeLoaded = true
        }
        prefs.edit().putString(KEY_FINANCE, SyncProtocol.financeToJson(snapshot).toString()).apply()
    }

    fun loadFinance(): FinanceSnapshot? = synchronized(cacheLock) {
        if (!financeLoaded) {
            cachedFinance = prefs.getString(KEY_FINANCE, null)?.let(SyncProtocol::financeFromJson)
            financeLoaded = true
        }
        cachedFinance
    }

    override fun loadAll(): SyncSnapshot =
        SyncSnapshot(
            weather = loadWeather(),
            calendar = loadCalendar(),
            news = loadNews(),
            finance = loadFinance()
        )

    override fun load(channels: Set<SyncChannel>): SyncSnapshot =
        SyncSnapshot(
            weather = if (SyncChannel.WEATHER in channels) loadWeather() else null,
            calendar = if (SyncChannel.CALENDAR in channels) loadCalendar() else null,
            news = if (SyncChannel.NEWS in channels) loadNews() else null,
            finance = if (SyncChannel.FINANCE in channels) loadFinance() else null
        )
}
