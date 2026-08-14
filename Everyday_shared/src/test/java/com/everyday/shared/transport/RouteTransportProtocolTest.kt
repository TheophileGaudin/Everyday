package com.everyday.shared.transport

import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.PedestrianGraphEdge
import com.everyday.shared.routing.RouteFailureCode
import com.everyday.shared.routing.RouteManeuver
import com.everyday.shared.routing.RouteManeuverType
import com.everyday.shared.routing.RouteMode
import com.everyday.shared.routing.RoutePolyline
import com.everyday.shared.routing.RouteResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTransportProtocolTest {
    private val origin = GeoPoint(51.5000, -0.1250)
    private val destination = GeoPoint(51.5010, -0.1240)

    @Test
    fun requestResultErrorAndCancellationRoundTrip() {
        val request = RouteTransportRequest(
            requestId = "route-1",
            origin = origin,
            destinationAddress = "Central Station",
            requestedAtMs = 10L,
            mode = RouteMode.WALKING
        )
        val result = RouteTransportResult(
            requestId = request.requestId,
            destination = RouteDestinationData("Central Station", "Central Station, London", destination),
            route = route(),
            calculatedAtMs = 20L
        )
        val error = RouteTransportError(
            requestId = request.requestId,
            code = RouteTransportErrorCode.ROUTE_FAILURE,
            message = "route failed",
            retryable = true,
            timestampMs = 21L,
            routeFailureCode = RouteFailureCode.TIMEOUT,
            choices = listOf(RouteDestinationData("Station", "Station, London", destination))
        )
        val cancellation = RouteTransportCancellation(request.requestId, 22L, "obsolete")

        listOf(
            TransportMessage.RouteRequestMessage(request),
            TransportMessage.RouteResultMessage(result),
            TransportMessage.RouteErrorMessage(error),
            TransportMessage.RouteCancellationMessage(cancellation)
        ).forEach { message ->
            val decoded = TransportProtocol.decode(TransportProtocol.encode(message))
            assertTrue("failed ${message.kind}: $decoded", decoded is TransportDecodeResult.Success)
            assertEquals(message, (decoded as TransportDecodeResult.Success).message)
        }
    }

    @Test
    fun routeBoundsMalformedPayloadAndUnknownKindAreRejectedSafely() {
        val missingVersion = JSONObject()
            .put("kind", "route.cancel")
            .put("payload", JSONObject().put("requestId", "route-1").put("cancelledAtMs", 1L))
        assertTrue(TransportProtocol.decode(missingVersion.toString()) is TransportDecodeResult.Failure)

        val tooManyPoints = JSONArray().apply {
            repeat(RouteTransportProtocol.MAX_POLYLINE_POINTS + 1) {
                put(JSONObject().put("lat", origin.lat).put("lon", origin.lon))
            }
        }
        val malformedResult = JSONObject()
            .put("kind", "route.result")
            .put(
                "payload",
                JSONObject()
                    .put("version", RouteTransportProtocol.VERSION)
                    .put("requestId", "route-1")
                    .put("destination", JSONObject()
                        .put("query", "x")
                        .put("displayName", "x")
                        .put("point", JSONObject().put("lat", destination.lat).put("lon", destination.lon)))
                    .put("route", JSONObject().put("polyline", tooManyPoints))
                    .put("calculatedAtMs", 1L)
            )
        assertTrue(TransportProtocol.decode(malformedResult.toString()) is TransportDecodeResult.Failure)

        val unknown = TransportProtocol.decode("{\"kind\":\"route.future\",\"payload\":{}}")
        assertTrue(unknown is TransportDecodeResult.Failure)
        assertEquals("route.future", (unknown as TransportDecodeResult.Failure).kind)

        val pointer = TransportMessage.Pointer(PointerAction.MOVE, 2, -1)
        assertEquals(
            pointer,
            (TransportProtocol.decode(TransportProtocol.encode(pointer)) as TransportDecodeResult.Success).message
        )
    }

    private fun route(): RouteResult = RouteResult(
        origin = origin,
        destination = destination,
        snappedOriginNodeId = 1L,
        snappedDestinationNodeId = 2L,
        polyline = RoutePolyline(listOf(origin, GeoPoint(51.5005, -0.1245), destination)),
        totalDistanceMeters = 160.0,
        estimatedWalkingTimeSeconds = 115L,
        graphNodeIds = listOf(1L, 2L),
        graphEdges = listOf(
            PedestrianGraphEdge(1L, 2L, 160.0, 3L, "footway", "Route Lane", 0)
        ),
        maneuvers = listOf(
            RouteManeuver(
                RouteManeuverType.START,
                origin,
                "Route Lane",
                3L,
                0.0,
                null,
                90.0,
                "Start walking on Route Lane"
            ),
            RouteManeuver(
                RouteManeuverType.ARRIVE,
                destination,
                "Route Lane",
                3L,
                160.0,
                90.0,
                null,
                "Arrive at your destination"
            )
        )
    )
}
