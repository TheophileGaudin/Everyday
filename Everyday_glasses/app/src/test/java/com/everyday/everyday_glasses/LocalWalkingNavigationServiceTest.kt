package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.controllers.LocalWalkingNavigationService
import com.everyday.everyday_glasses.map.GeocodingCandidate
import com.everyday.everyday_glasses.map.GeocodingResolution
import com.everyday.everyday_glasses.map.MapGeoPoint
import com.everyday.everyday_glasses.map.navigationFailureMessage
import com.everyday.everyday_glasses.map.ForwardGeocoder
import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.OverpassNode
import com.everyday.shared.routing.OverpassRouteData
import com.everyday.shared.routing.OverpassWay
import com.everyday.shared.routing.RouteCalculationResult
import com.everyday.shared.routing.RouteFailure
import com.everyday.shared.routing.RouteGraphFetchResult
import com.everyday.shared.routing.RouteGraphProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWalkingNavigationServiceTest {
    private val origin = MapGeoPoint(51.5000, -0.1250)
    private val destination = MapGeoPoint(51.5000, -0.1200)

    @Test
    fun resolvesAddressThroughForwardGeocoderAndReturnsRankedResult() {
        var searched: String? = null
        val service = LocalWalkingNavigationService(
            geocoder = ForwardGeocoder { address, near ->
                searched = address
                assertEquals(origin, near)
                Result.success(listOf(GeocodingCandidate(destination, "Museum, London")))
            },
            graphProvider = RouteGraphProvider { _, _ -> RouteGraphFetchResult.Success(routeData()) }
        )

        val resolution = service.resolveAddress("  Museum,   London ", origin)

        assertEquals("Museum, London", searched)
        assertTrue(resolution is GeocodingResolution.Accepted)
        assertEquals(destination, (resolution as GeocodingResolution.Accepted).candidate.point)
    }

    @Test
    fun calculatesWalkingRouteAndRetriesOnlyAfterDisconnectedGraph() {
        val buffers = mutableListOf<Int>()
        val disconnected = OverpassRouteData(
            nodes = routeData().nodes,
            ways = listOf(
                OverpassWay(1L, listOf(1L, 2L), mapOf("highway" to "footway")),
                OverpassWay(2L, listOf(3L), mapOf("highway" to "footway"))
            )
        )
        val service = LocalWalkingNavigationService(
            geocoder = ForwardGeocoder { _, _ -> Result.success(emptyList()) },
            graphProvider = RouteGraphProvider { _, buffer ->
                buffers += buffer
                RouteGraphFetchResult.Success(if (buffer == 400) disconnected else routeData())
            }
        )

        val result = service.calculateWalkingRoute(origin, destination)

        assertTrue(result is RouteCalculationResult.Success)
        assertEquals(listOf(400, 800), buffers)
        assertEquals(2, (result as RouteCalculationResult.Success).attempts)
        assertTrue(result.route.totalDistanceMeters > 0.0)
    }

    @Test
    fun rejectsDestinationBeyondTheProviderSafetyCeilingBeforeNetworkFetch() {
        var calls = 0
        val service = LocalWalkingNavigationService(
            geocoder = ForwardGeocoder { _, _ -> Result.success(emptyList()) },
            graphProvider = RouteGraphProvider { _, _ ->
                calls++
                RouteGraphFetchResult.Success(routeData())
            }
        )

        val result = service.calculateWalkingRoute(origin, MapGeoPoint(51.5000, -20.0000))

        assertTrue(result is RouteCalculationResult.Failure)
        val failure = (result as RouteCalculationResult.Failure).failure
        assertTrue(failure is RouteFailure.OverLimit)
        assertTrue(navigationFailureMessage(failure).contains("provider limit"))
        assertEquals(0, result.attempts)
        assertEquals(0, calls)
    }

    private fun routeData(): OverpassRouteData = OverpassRouteData(
        nodes = listOf(
            OverpassNode(1L, GeoPoint(origin.lat, origin.lon)),
            OverpassNode(2L, GeoPoint(51.5000, -0.1230)),
            OverpassNode(3L, GeoPoint(destination.lat, destination.lon))
        ),
        ways = listOf(
            OverpassWay(1L, listOf(1L, 2L, 3L), mapOf("highway" to "footway", "name" to "Route Lane"))
        )
    )
}
