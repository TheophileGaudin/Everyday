package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.widgets.map.backend.DestinationSearchFallbackReason
import com.everyday.everyday_glasses.widgets.map.backend.DestinationSearchListener
import com.everyday.everyday_glasses.widgets.map.backend.GlassesDestinationSearchCoordinator
import com.everyday.everyday_glasses.widgets.map.backend.GlassesDestinationSearchTransport
import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.transport.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GlassesDestinationSearchCoordinatorTest {
    private fun request(id: String = "search") = DestinationSearchRequest(id, "train station", GeoPoint(48.8, 2.3), "en", 1L)

    @Test fun `phone result wins before hedge and stale result is ignored`() {
        val transport = FakeTransport()
        val coordinator =
            GlassesDestinationSearchCoordinator({ transport }, hedgeMs = 1_000, deadlineMs = 2_000)
        var delivered = 0
        coordinator.listener = object : DestinationSearchListener {
            override fun onDestinationSearchResult(result: DestinationSearchResult) {
                delivered++
            }
        }
        coordinator.request(request())
        coordinator.onResult(DestinationSearchResult("search", "train station", listOf(
            DestinationSearchCandidate("Station", "Nearby", GeoPoint(48.801, 2.3), DestinationSearchCandidateKind.POI)
        ), "Mapbox", 2L))
        coordinator.onResult(DestinationSearchResult("stale", "x", emptyList(), "", 3L))
        assertEquals(1, delivered)
        assertEquals(1, transport.requests.size)
        coordinator.release()
    }

    @Test fun `slow phone starts one hedge and local winner cancels phone`() {
        val transport = FakeTransport()
        val latch = CountDownLatch(1)
        val coordinator =
            GlassesDestinationSearchCoordinator({ transport }, hedgeMs = 10, deadlineMs = 1_000)
        coordinator.listener = object : DestinationSearchListener {
            override fun onDestinationSearchFallback(
                request: DestinationSearchRequest,
                reason: DestinationSearchFallbackReason
            ) {
                latch.countDown()
            }
        }
        coordinator.request(request())
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertTrue(coordinator.acceptFallback("search"))
        assertFalse(coordinator.acceptFallback("search"))
        assertEquals("fallback_won", transport.cancellations.single().reason)
        coordinator.release()
    }

    private class FakeTransport : GlassesDestinationSearchTransport {
        val requests = mutableListOf<DestinationSearchRequest>()
        val cancellations = mutableListOf<DestinationSearchCancellation>()
        override fun isConnected() = true
        override fun sendDestinationSearchRequest(request: DestinationSearchRequest) = requests.add(request)
        override fun sendDestinationSearchCancellation(cancellation: DestinationSearchCancellation) = cancellations.add(cancellation)
    }
}
