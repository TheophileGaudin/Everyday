package com.everyday.everyday_phone

import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.RoutePolyline
import com.everyday.shared.routing.RouteResult
import com.everyday.shared.transport.RouteDestinationData
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportErrorCode
import com.everyday.shared.transport.RouteTransportRequest
import com.everyday.shared.transport.RouteTransportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PhoneRouteCalculationCoordinatorTest {
    @Test
    fun connectedRequestCalculatesOnceAndSendsCompleteResult() {
        val transport = FakeTransport()
        val calls = AtomicInteger()
        val coordinator = PhoneRouteCalculationCoordinator(
            transportProvider = { transport },
            calculator = PhoneRouteCalculator { request ->
                calls.incrementAndGet()
                PhoneRouteCalculation.Success(result(request))
            }
        )
        coordinator.onConnectionChanged(true)
        val request = request("connected")
        coordinator.onRouteRequest(request)
        assertTrue(waitForResult(transport))

        assertEquals(1, calls.get())
        assertEquals(request.requestId, transport.results.single().requestId)
        assertEquals(destination, transport.results.single().destination.point)
        coordinator.release()
    }

    @Test
    fun phoneErrorIsStructuredAndCancellationPreventsLateSend() {
        val transport = FakeTransport()
        val errorLatch = CountDownLatch(1)
        val started = CountDownLatch(1)
        val releaseCalculation = CountDownLatch(1)
        val coordinator = PhoneRouteCalculationCoordinator(
            transportProvider = { transport },
            calculator = PhoneRouteCalculator { request ->
                if (request.requestId == "cancelled") {
                    started.countDown()
                    try {
                        releaseCalculation.await()
                    } catch (_: InterruptedException) {
                        return@PhoneRouteCalculator PhoneRouteCalculation.Success(result(request))
                    }
                    PhoneRouteCalculation.Success(result(request))
                } else {
                    PhoneRouteCalculation.Failure(
                        RouteTransportError(
                            requestId = request.requestId,
                            code = RouteTransportErrorCode.GEOCODING_NETWORK,
                            message = "offline",
                            retryable = true,
                            timestampMs = 1L
                        )
                    )
                }
            }
        )
        coordinator.onConnectionChanged(true)
        coordinator.onRouteRequest(request("error"))
        waitForError(transport, errorLatch)
        assertEquals(RouteTransportErrorCode.GEOCODING_NETWORK, transport.errors.single().code)

        coordinator.onRouteRequest(request("cancelled"))
        assertTrue(started.await(1, TimeUnit.SECONDS))
        coordinator.onRouteCancellation(
            com.everyday.shared.transport.RouteTransportCancellation("cancelled", 2L, "stopped")
        )
        releaseCalculation.countDown()
        Thread.sleep(50L)
        assertTrue(transport.results.isEmpty())
        coordinator.release()
    }

    @Test
    fun disconnectDropsOldResponseAndReconnectAcceptsNewRequest() {
        val transport = FakeTransport()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val coordinator = PhoneRouteCalculationCoordinator(
            transportProvider = { transport },
            calculator = PhoneRouteCalculator { request ->
                if (request.requestId == "old") {
                    oldStarted.countDown()
                    try {
                        releaseOld.await()
                    } catch (_: InterruptedException) {
                        // Return a value deliberately; the coordinator must still drop it.
                    }
                }
                PhoneRouteCalculation.Success(result(request))
            }
        )
        coordinator.onConnectionChanged(true)
        coordinator.onRouteRequest(request("old"))
        assertTrue(oldStarted.await(1, TimeUnit.SECONDS))
        coordinator.onConnectionChanged(false)
        releaseOld.countDown()
        coordinator.onConnectionChanged(true)
        coordinator.onRouteRequest(request("new"))
        waitForResult(transport)

        assertEquals(listOf("new"), transport.results.map { it.requestId })
        coordinator.release()
    }

    @Test
    fun cancellationReachesTheActiveNetworkCalculator() {
        val transport = FakeTransport()
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val calculator = object : CancellablePhoneRouteCalculator {
            override fun calculate(request: RouteTransportRequest): PhoneRouteCalculation {
                started.countDown()
                try {
                    released.await()
                } catch (_: InterruptedException) {
                    // The explicit cancellation hook below is what disconnects blocking I/O.
                }
                return PhoneRouteCalculation.Success(result(request))
            }

            override fun cancelActive() {
                cancellations.incrementAndGet()
                released.countDown()
            }
        }
        val coordinator = PhoneRouteCalculationCoordinator(
            transportProvider = { transport },
            calculator = calculator
        )
        coordinator.onConnectionChanged(true)
        coordinator.onRouteRequest(request("network-cancel"))
        assertTrue(started.await(1, TimeUnit.SECONDS))

        coordinator.onRouteCancellation(
            com.everyday.shared.transport.RouteTransportCancellation("network-cancel", 2L, "stopped")
        )

        assertEquals(1, cancellations.get())
        Thread.sleep(30L)
        assertTrue(transport.results.isEmpty())
        coordinator.release()
    }

    private fun waitForResult(transport: FakeTransport): Boolean {
        repeat(100) {
            if (transport.results.isNotEmpty()) return true
            Thread.sleep(10L)
        }
        return false
    }

    private fun waitForError(transport: FakeTransport, latch: CountDownLatch) {
        repeat(100) {
            if (transport.errors.isNotEmpty()) {
                latch.countDown()
                return
            }
            Thread.sleep(10L)
        }
        assertTrue(latch.await(1, TimeUnit.MILLISECONDS))
    }

    private fun request(id: String) = RouteTransportRequest(
        requestId = id,
        origin = origin,
        destinationAddress = "Destination",
        requestedAtMs = 1L
    )

    private fun result(request: RouteTransportRequest) = RouteTransportResult(
        requestId = request.requestId,
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

    private class FakeTransport : PhoneRouteTransport {
        @Volatile var connected = true
        val results = mutableListOf<RouteTransportResult>()
        val errors = mutableListOf<RouteTransportError>()

        override fun isConnected(): Boolean = connected

        override fun sendRouteResult(result: RouteTransportResult): Boolean = synchronized(results) {
            results += result
            true
        }

        override fun sendRouteError(error: RouteTransportError): Boolean = synchronized(errors) {
            errors += error
            true
        }
    }

    companion object {
        private val origin = GeoPoint(51.5, -0.125)
        private val destination = GeoPoint(51.501, -0.124)
    }
}
