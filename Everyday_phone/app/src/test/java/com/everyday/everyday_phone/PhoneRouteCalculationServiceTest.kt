package com.everyday.everyday_phone

import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.RoutePolyline
import com.everyday.shared.routing.RouteResult
import com.everyday.shared.transport.RouteTransportErrorCode
import com.everyday.shared.transport.RouteTransportRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRouteCalculationServiceTest {
    private val origin = GeoPoint(51.5000, -0.1250)
    private val destination = GeoPoint(51.5000, -0.1200)

    @Test
    fun phoneGeocodesAndUsesMapboxDirectionsWithCompleteResult() {
        val service = PhoneRouteCalculationService(
            geocoder = PhoneForwardGeocoder { _, near ->
                assertEquals(origin, near)
                Result.success(listOf(PhoneGeocodingCandidate(destination, "Museum, London")))
            },
            directionsProvider = PhoneDirectionsProvider { routeOrigin, routeDestination, language ->
                assertEquals(origin, routeOrigin)
                assertEquals(destination, routeDestination)
                assertEquals("en", language)
                Result.success(route(routeOrigin, routeDestination))
            },
            languageTag = { "en" },
            nowMs = { 50L }
        )

        val calculation = service.calculate(
            RouteTransportRequest(
                requestId = "phone-route",
                origin = origin,
                destinationAddress = "Museum, London",
                requestedAtMs = 10L
            )
        )

        assertTrue(calculation is PhoneRouteCalculation.Success)
        val result = (calculation as PhoneRouteCalculation.Success).result
        assertEquals("Museum, London", result.destination.displayName)
        assertEquals(destination, result.destination.point)
        assertEquals(result.route.polyline.points.first(), origin)
        assertEquals(result.route.polyline.points.last(), destination)
        assertEquals(50L, result.calculatedAtMs)
        assertTrue(result.route.totalDistanceMeters > 0.0)
    }

    @Test
    fun phoneAddressResolutionPrefersTheClosestFullHouseNumberMatch() {
        val service = PhoneRouteCalculationService(
            geocoder = PhoneForwardGeocoder { _, _ ->
                Result.success(
                    listOf(
                        PhoneGeocodingCandidate(GeoPoint(51.5000, -0.5000), "12 Rue Magellan"),
                        PhoneGeocodingCandidate(destination, "12 Rue Magellan, Le Havre")
                    )
                )
            },
            directionsProvider = PhoneDirectionsProvider { routeOrigin, routeDestination, _ ->
                Result.success(route(routeOrigin, routeDestination))
            }
        )

        val calculation = service.calculate(
            RouteTransportRequest(
                requestId = "local-address",
                origin = origin,
                destinationAddress = "12, Rue Magellan",
                requestedAtMs = 1L
            )
        )

        assertTrue(calculation is PhoneRouteCalculation.Success)
        assertEquals(destination, (calculation as PhoneRouteCalculation.Success).result.destination.point)
    }

    @Test
    fun ambiguousGeocodeIsStructuredAndRoutesBeyondTheOldLimitAreAccepted() {
        val ambiguous = PhoneRouteCalculationService(
            geocoder = PhoneForwardGeocoder { _, _ ->
                Result.success(
                    listOf(
                        PhoneGeocodingCandidate(destination, "Cafe"),
                        PhoneGeocodingCandidate(GeoPoint(51.5001, -0.1201), "Cafe")
                    )
                )
            },
            directionsProvider = PhoneDirectionsProvider { routeOrigin, routeDestination, _ ->
                Result.success(route(routeOrigin, routeDestination))
            }
        ).calculate(
            RouteTransportRequest("ambiguous", origin, destinationAddress = "Cafe", requestedAtMs = 1L)
        )
        assertTrue(ambiguous is PhoneRouteCalculation.Failure)
        val ambiguousFailure = ambiguous as PhoneRouteCalculation.Failure
        assertEquals(RouteTransportErrorCode.GEOCODING_AMBIGUOUS, ambiguousFailure.error.code)
        assertEquals(2, ambiguousFailure.error.choices.size)

        var directionCalls = 0
        val farDestination = GeoPoint(51.5, -0.35)
        val far = PhoneRouteCalculationService(
            geocoder = PhoneForwardGeocoder { _, _ ->
                Result.success(listOf(PhoneGeocodingCandidate(farDestination, "Far")))
            },
            directionsProvider = PhoneDirectionsProvider { routeOrigin, routeDestination, _ ->
                directionCalls++
                Result.success(route(routeOrigin, routeDestination))
            }
        ).calculate(
            RouteTransportRequest("far", origin, destinationAddress = "Far", requestedAtMs = 1L)
        )
        assertTrue(far is PhoneRouteCalculation.Success)
        assertEquals(farDestination, (far as PhoneRouteCalculation.Success).result.destination.point)
        assertEquals(1, directionCalls)
    }

    private fun route(routeOrigin: GeoPoint, routeDestination: GeoPoint) = RouteResult(
        origin = routeOrigin,
        destination = routeDestination,
        snappedOriginNodeId = 0L,
        snappedDestinationNodeId = 1L,
        polyline = RoutePolyline(listOf(routeOrigin, routeDestination)),
        totalDistanceMeters = 100.0,
        estimatedWalkingTimeSeconds = 72L,
        graphNodeIds = emptyList(),
        graphEdges = emptyList(),
        maneuvers = emptyList()
        )
}
