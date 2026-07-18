package com.everyday.everyday_glasses

import com.everyday.shared.sync.SyncCachePolicy
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncOrchestrator
import com.everyday.shared.sync.SyncProtocol
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SyncSnapshotCache
import com.everyday.shared.sync.LocationWeatherSnapshot
import com.everyday.shared.sync.isStale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOrchestratorTest {
    @Test
    fun `location weather rename preserves weather wire name`() {
        val source = LocationWeatherSnapshot(
            town = "London",
            countryCode = "GB",
            weather = null,
            timestampMs = 100L
        )

        val encoded = SyncProtocol.encodeSnapshot(SyncSnapshot(locationWeather = source))
        val decoded = SyncProtocol.decodeSnapshot(encoded)

        assertTrue(encoded.contains("\"weather\""))
        assertFalse(encoded.contains("locationWeather"))
        assertEquals(source, decoded?.locationWeather)
    }

    @Test
    fun `refresh builds and dispatches sync request`() {
        val dispatched = mutableListOf<SyncRequest>()
        val orchestrator = SyncOrchestrator(
            snapshotCache = FakeSnapshotCache(),
            requestDispatcher = dispatched::add
        )

        val request = orchestrator.refresh(
            channels = setOf(SyncChannel.NEWS),
            force = true,
            reason = "test",
            countryCode = "GB"
        )

        assertSame(request, dispatched.single())
        assertEquals(setOf(SyncChannel.NEWS), request.channels)
        assertTrue(request.force)
        assertEquals("test", request.reason)
        assertEquals("GB", request.countryCode)
    }

    @Test
    fun `cache and apply snapshot saves before notifying applier`() {
        val cache = FakeSnapshotCache()
        val applied = mutableListOf<SyncSnapshot>()
        val weather = LocationWeatherSnapshot(
            town = "London",
            countryCode = "GB",
            weather = null,
            timestampMs = 100L
        )
        val snapshot = SyncSnapshot(locationWeather = weather)
        val orchestrator = SyncOrchestrator(
            snapshotCache = cache,
            requestDispatcher = {},
            snapshotApplier = { appliedSnapshot, _ ->
                assertSame(snapshot, cache.savedSnapshots.single())
                applied += appliedSnapshot
            }
        )

        orchestrator.cacheAndApplySnapshot(snapshot)

        assertSame(snapshot, applied.single())
    }

    @Test
    fun `cached snapshot only loads requested channels`() {
        val weather = LocationWeatherSnapshot(
            town = "London",
            countryCode = "GB",
            weather = null,
            timestampMs = 100L
        )
        val orchestrator = SyncOrchestrator(
            snapshotCache = FakeSnapshotCache(allSnapshot = SyncSnapshot(locationWeather = weather)),
            requestDispatcher = {}
        )

        val snapshot = orchestrator.cachedSnapshot(setOf(SyncChannel.NEWS))

        assertNull(snapshot.locationWeather)
        assertTrue(snapshot.isEmpty)
    }

    @Test
    fun `shared stale policy preserves existing ttl boundary`() {
        val weather = LocationWeatherSnapshot(
            town = "London",
            countryCode = "GB",
            weather = null,
            timestampMs = 1_000L,
            staleAfterMs = SyncCachePolicy.WEATHER_STALE_AFTER_MS
        )

        assertFalse(weather.isStale(nowMs = 1_000L + SyncCachePolicy.WEATHER_STALE_AFTER_MS))
        assertTrue(weather.isStale(nowMs = 1_001L + SyncCachePolicy.WEATHER_STALE_AFTER_MS))
    }

    private class FakeSnapshotCache(
        private val allSnapshot: SyncSnapshot = SyncSnapshot()
    ) : SyncSnapshotCache {
        val savedSnapshots = mutableListOf<SyncSnapshot>()

        override fun save(snapshot: SyncSnapshot) {
            savedSnapshots += snapshot
        }

        override fun loadAll(): SyncSnapshot = allSnapshot

        override fun load(channels: Set<SyncChannel>): SyncSnapshot =
            SyncSnapshot(
                locationWeather = allSnapshot.locationWeather.takeIf { SyncChannel.LOCATION_WEATHER in channels },
                calendar = allSnapshot.calendar.takeIf { SyncChannel.CALENDAR in channels },
                news = allSnapshot.news.takeIf { SyncChannel.NEWS in channels },
                finance = allSnapshot.finance.takeIf { SyncChannel.FINANCE in channels },
                notifications = allSnapshot.notifications.takeIf { SyncChannel.NOTIFICATIONS in channels }
            )
    }
}
