package com.everyday.everyday_glasses

import com.everyday.shared.sync.SyncCachePolicy
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncOrchestrator
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SyncSnapshotCache
import com.everyday.shared.sync.LocationFix
import com.everyday.shared.sync.LocationFixQuality
import com.everyday.shared.sync.NewsDataProvider
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.WeatherSnapshot
import com.everyday.shared.sync.isStale
import com.everyday.shared.sync.usableSpeedMps
import com.everyday.shared.transport.TransportDecodeResult
import com.everyday.shared.transport.TransportMessage
import com.everyday.shared.transport.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOrchestratorTest {
    @Test
    fun `location and weather use independent wire channels`() {
        val weather = WeatherSnapshot(
            town = "London",
            countryCode = "GB",
            weather = null,
            fetchedAtMs = 100L
        )
        val location = LocationFix(51.5, -0.1, 5f, 90f, 2f, LocationFixQuality.VALID, 101L)

        val encoded = TransportProtocol.encode(
            TransportMessage.SyncSnapshotMessage(SyncSnapshot(location = location, weather = weather))
        )
        val decoded = (TransportProtocol.decode(encoded) as TransportDecodeResult.Success).message
            as TransportMessage.SyncSnapshotMessage

        assertTrue(encoded.contains("\"weather\""))
        assertEquals(location, decoded.snapshot.location)
        assertEquals(weather, decoded.snapshot.weather)
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
        val weather = WeatherSnapshot(
            town = "London",
            countryCode = "GB",
            weather = null,
            fetchedAtMs = 100L
        )
        val snapshot = SyncSnapshot(weather = weather)
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
        val weather = WeatherSnapshot(
            town = "London",
            countryCode = "GB",
            weather = null,
            fetchedAtMs = 100L
        )
        val orchestrator = SyncOrchestrator(
            snapshotCache = FakeSnapshotCache(allSnapshot = SyncSnapshot(weather = weather)),
            requestDispatcher = {}
        )

        val snapshot = orchestrator.cachedSnapshot(setOf(SyncChannel.NEWS))

        assertNull(snapshot.weather)
        assertTrue(snapshot.isEmpty)
    }

    @Test
    fun `shared stale policy preserves existing ttl boundary`() {
        val weather = WeatherSnapshot(
            town = "London",
            countryCode = "GB",
            weather = null,
            fetchedAtMs = 1_000L,
            staleAfterMs = SyncCachePolicy.WEATHER_STALE_AFTER_MS
        )

        assertFalse(weather.isStale(nowMs = 1_000L + SyncCachePolicy.WEATHER_STALE_AFTER_MS))
        assertTrue(weather.isStale(nowMs = 1_001L + SyncCachePolicy.WEATHER_STALE_AFTER_MS))
    }

    @Test
    fun `location cadence is independent and speed quality rejects unsafe fixes`() {
        val valid = LocationFix(1.0, 2.0, 5f, null, 3f, LocationFixQuality.VALID, 1_000L)
        val missing = valid.copy(speedMps = null, quality = LocationFixQuality.MISSING)
        val stale = valid.copy(quality = LocationFixQuality.STALE)
        val inaccurate = valid.copy(accuracyM = 100f, quality = LocationFixQuality.INACCURATE)

        assertEquals(3f, valid.usableSpeedMps(nowMs = 1_500L))
        assertNull(missing.usableSpeedMps(nowMs = 1_500L))
        assertNull(stale.usableSpeedMps(nowMs = 1_500L))
        assertNull(inaccurate.usableSpeedMps(nowMs = 1_500L))
        assertNull(valid.usableSpeedMps(nowMs = 1_001L + SyncCachePolicy.LOCATION_STALE_AFTER_MS))
        assertFalse(valid.isStale(nowMs = 1_000L + SyncCachePolicy.LOCATION_STALE_AFTER_MS))
        assertTrue(valid.isStale(nowMs = 1_001L + SyncCachePolicy.LOCATION_STALE_AFTER_MS))
    }

    @Test
    fun `news country follows explicit selection then weather then cached news`() {
        val weather = WeatherSnapshot("London", "GB", null, 1L)
        val news = NewsSnapshot("FR", emptyList(), 1L)

        assertEquals("DE", NewsDataProvider.resolveCountryCode("de", weather, news))
        assertEquals("GB", NewsDataProvider.resolveCountryCode(null, weather, news))
        assertEquals("FR", NewsDataProvider.resolveCountryCode(null, null, news))
    }

    @Test
    fun `empty cache starts with no channels`() {
        assertTrue(FakeSnapshotCache().loadAll().isEmpty)
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
                location = allSnapshot.location.takeIf { SyncChannel.LOCATION in channels },
                weather = allSnapshot.weather.takeIf { SyncChannel.WEATHER in channels },
                calendar = allSnapshot.calendar.takeIf { SyncChannel.CALENDAR in channels },
                news = allSnapshot.news.takeIf { SyncChannel.NEWS in channels },
                finance = allSnapshot.finance.takeIf { SyncChannel.FINANCE in channels },
                notifications = allSnapshot.notifications.takeIf { SyncChannel.NOTIFICATIONS in channels }
            )
    }
}
