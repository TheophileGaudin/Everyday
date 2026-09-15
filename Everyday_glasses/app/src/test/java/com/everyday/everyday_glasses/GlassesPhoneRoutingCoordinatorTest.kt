package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.controllers.GlassesPhoneRoutingCoordinator
import com.everyday.everyday_glasses.controllers.GlassesRouteFallbackReason
import com.everyday.everyday_glasses.controllers.GlassesRouteTransport
import com.everyday.everyday_glasses.controllers.RouteResolutionListener
import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.RoutePolyline
import com.everyday.shared.routing.RouteResult
import com.everyday.shared.transport.RouteDestinationData
import com.everyday.shared.transport.RouteTransportCancellation
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportErrorCode
import com.everyday.shared.transport.RouteTransportRequest
import com.everyday.shared.transport.RouteTransportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GlassesPhoneRoutingCoordinatorTest {
    @Test
    fun sendFailureAndNotConnectedEachTriggerOneFallback() {
        val transport = FakeTransport(connected = false)
        val fallbacks = mutableListOf<GlassesRouteFallbackReason>()
        val coordinator = coordinator(transport, fallbacks)

        assertFalse(coordinator.request(request("not-connected")))
        assertEquals(listOf(GlassesRouteFallbackReason.NOT_CONNECTED), fallbacks)

        transport.connected = true
        transport.sendResult = false
        assertFalse(coordinator.request(request("send-failure")))
        assertEquals(
            listOf(GlassesRouteFallbackReason.NOT_CONNECTED, GlassesRouteFallbackReason.SEND_FAILURE),
            fallbacks
        )
        coordinator.release()
    }

    @Test
    fun successAcceptsOnlyCurrentResponseAndIgnoresDuplicateOrStaleData() {
        val transport = FakeTransport()
        val results = mutableListOf<RouteTransportResult>()
        val coordinator = coordinator(transport, mutableListOf()).apply {
            listener = object : RouteResolutionListener {
                override fun onRouteResolved(result: RouteTransportResult) {
                    results += result
                }
            }
        }

        assertTrue(coordinator.request(request("one")))
        coordinator.onRouteResult(result("stale"))
        coordinator.onRouteResult(result("one"))
        coordinator.onRouteResult(result("one"))
        assertEquals(listOf("one"), results.map { it.requestId })

        assertTrue(coordinator.request(request("two")))
        coordinator.onRouteResult(result("one"))
        coordinator.onRouteResult(result("two"))
        assertEquals(listOf("one", "two"), results.map { it.requestId })
        coordinator.release()
    }

    @Test
    fun timeoutDisconnectCancellationAndReconnectAreBounded() {
        val transport = FakeTransport()
        val fallbacks = mutableListOf<GlassesRouteFallbackReason>()
        val fallbackLatch = CountDownLatch(1)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val coordinator = GlassesPhoneRoutingCoordinator(
            transportProvider = { transport },
            timeoutMs = 30L,
            scheduler = scheduler
        ).apply {
            listener = object : RouteResolutionListener {
                override fun onRouteFallback(
                    request: RouteTransportRequest,
                    reason: GlassesRouteFallbackReason
                ) {
                    synchronized(fallbacks) { fallbacks += reason }
                    fallbackLatch.countDown()
                }
            }
        }

        assertTrue(coordinator.request(request("timeout")))
        assertTrue(fallbackLatch.await(1, TimeUnit.SECONDS))
        assertEquals(GlassesRouteFallbackReason.TIMEOUT, fallbacks.single())
        assertEquals(listOf("timeout"), transport.cancellations.map { it.requestId })

        val disconnectLatch = CountDownLatch(1)
        coordinator.listener = object : RouteResolutionListener {
            override fun onRouteFallback(request: RouteTransportRequest, reason: GlassesRouteFallbackReason) {
                synchronized(fallbacks) { fallbacks += reason }
                disconnectLatch.countDown()
            }
        }
        assertTrue(coordinator.request(request("disconnect")))
        coordinator.onConnectionChanged(false)
        assertTrue(disconnectLatch.await(1, TimeUnit.SECONDS))
        coordinator.onRouteResult(result("disconnect"))
        assertEquals(GlassesRouteFallbackReason.DISCONNECTED, fallbacks.last())

        transport.connected = true
        var reconnected = 0
        coordinator.listener = object : RouteResolutionListener {
            override fun onRouteResolved(result: RouteTransportResult) {
                reconnected++
            }
        }
        coordinator.onConnectionChanged(true)
        assertTrue(coordinator.request(request("reconnect")))
        coordinator.cancelActive("user_stop")
        assertEquals(1, transport.cancellations.count { it.requestId == "reconnect" })
        coordinator.onRouteResult(result("reconnect"))
        assertEquals(0, reconnected)
        coordinator.release()
    }

    @Test
    fun phoneErrorFallsBackButAmbiguousErrorStaysForChoice() {
        val transport = FakeTransport()
        val fallbacks = mutableListOf<GlassesRouteFallbackReason>()
        var ambiguous = 0
        val coordinator = coordinator(transport, fallbacks).apply {
            listener = object : RouteResolutionListener {
                override fun onRouteAmbiguous(request: RouteTransportRequest, error: RouteTransportError) {
                    ambiguous++
                }

                override fun onRouteFallback(request: RouteTransportRequest, reason: GlassesRouteFallbackReason) {
                    synchronized(fallbacks) { fallbacks += reason }
                }
            }
        }

        coordinator.request(request("phone-error"))
        coordinator.onRouteError(
            RouteTransportError(
                requestId = "phone-error",
                code = RouteTransportErrorCode.GEOCODING_NETWORK,
                message = "offline",
                retryable = true,
                timestampMs = 1L
            )
        )
        assertEquals(listOf(GlassesRouteFallbackReason.PHONE_ERROR), fallbacks)

        coordinator.request(request("ambiguous"))
        coordinator.onRouteError(
            RouteTransportError(
                requestId = "ambiguous",
                code = RouteTransportErrorCode.GEOCODING_AMBIGUOUS,
                message = "choose",
                retryable = false,
                timestampMs = 2L,
                choices = listOf(RouteDestinationData("a", "A", destination))
            )
        )
        assertEquals(1, ambiguous)
        assertEquals(1, fallbacks.size)
        coordinator.release()
    }

    @Test
    fun staleRerouteResponseCannotCompleteReplacementRequest() {
        val transport = FakeTransport()
        val fallbacks = mutableListOf<GlassesRouteFallbackReason>()
        val results = mutableListOf<String>()
        val coordinator = coordinator(transport, fallbacks).apply {
            listener = object : RouteResolutionListener {
                override fun onRouteResolved(result: RouteTransportResult) {
                    results += result.requestId
                }

                override fun onRouteFallback(request: RouteTransportRequest, reason: GlassesRouteFallbackReason) {
                    synchronized(fallbacks) { fallbacks += reason }
                }
            }
        }

        assertTrue(coordinator.request(request("reroute-old")))
        assertTrue(coordinator.request(request("reroute-new")))
        coordinator.onRouteResult(result("reroute-old"))
        assertTrue(results.isEmpty())
        coordinator.onRouteError(
            RouteTransportError(
                requestId = "reroute-new",
                code = RouteTransportErrorCode.ROUTE_FAILURE,
                message = "recalculation failed",
                retryable = true,
                timestampMs = 3L
            )
        )

        assertEquals(listOf(GlassesRouteFallbackReason.PHONE_ERROR), fallbacks)
        assertFalse(coordinator.hasActiveRequest())
        coordinator.release()
    }

    private fun coordinator(
        transport: FakeTransport,
        fallbacks: MutableList<GlassesRouteFallbackReason>
    ) = GlassesPhoneRoutingCoordinator(
        transportProvider = { transport },
        timeoutMs = 1_000L,
        scheduler = Executors.newSingleThreadScheduledExecutor()
    ).apply {
        listener = object : RouteResolutionListener {
            override fun onRouteFallback(request: RouteTransportRequest, reason: GlassesRouteFallbackReason) {
                synchronized(fallbacks) { fallbacks += reason }
            }
        }
    }

    private fun request(id: String) = RouteTransportRequest(
        requestId = id,
        origin = origin,
        destinationAddress = "Destination",
        requestedAtMs = 1L
    )

    private fun result(id: String) = RouteTransportResult(
        requestId = id,
        destination = RouteDestinationData("Destination", "Destination", destination),
        route = RouteResult(
            origin = origin,
            destination = destination,
            snappedOriginNodeId = 1L,
            snappedDestinationNodeId = 2L,
            polyline = RoutePolyline(listOf(origin, destination)),
            totalDistanceMeters = 100.0,
            estimatedWalkingTimeSeconds = 72L,
            graphNodeIds = listOf(1L, 2L),
            graphEdges = emptyList(),
            maneuvers = emptyList()
        ),
        calculatedAtMs = 3L
    )

    private class FakeTransport(var connected: Boolean = true) : GlassesRouteTransport {
        var sendResult = true
        val requests = mutableListOf<RouteTransportRequest>()
        val cancellations = mutableListOf<RouteTransportCancellation>()

        override fun isConnected(): Boolean = connected

        override fun sendRouteRequest(request: RouteTransportRequest): Boolean {
            requests += request
            return sendResult
        }

        override fun sendRouteCancellation(cancellation: RouteTransportCancellation): Boolean {
            cancellations += cancellation
            return true
        }
    }

    companion object {
        private val origin = GeoPoint(51.5, -0.125)
        private val destination = GeoPoint(51.501, -0.124)
    }
}
