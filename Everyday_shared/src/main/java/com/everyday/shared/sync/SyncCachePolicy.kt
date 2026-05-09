package com.everyday.shared.sync

object SyncCachePolicy {
    const val WEATHER_STALE_AFTER_MS = 10 * 60 * 1000L
    const val CALENDAR_STALE_AFTER_MS = 3 * 60 * 1000L
    const val NEWS_STALE_AFTER_MS = 10 * 60 * 1000L
    const val FINANCE_STALE_AFTER_MS = 60 * 1000L

    fun isStale(
        timestampMs: Long,
        staleAfterMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return timestampMs <= 0L || (staleAfterMs > 0L && nowMs > timestampMs + staleAfterMs)
    }
}

fun WeatherSnapshot?.isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
    this == null || SyncCachePolicy.isStale(timestampMs, staleAfterMs, nowMs)

fun CalendarSnapshot?.isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
    this == null || SyncCachePolicy.isStale(fetchedAtMs, staleAfterMs, nowMs)

fun NewsSnapshot?.isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
    this == null || SyncCachePolicy.isStale(fetchedAtMs, staleAfterMs, nowMs)

fun FinanceSnapshot?.isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
    this == null || SyncCachePolicy.isStale(fetchedAtMs, staleAfterMs, nowMs)
