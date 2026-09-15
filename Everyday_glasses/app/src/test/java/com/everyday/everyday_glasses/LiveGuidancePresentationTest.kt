package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.data_models.MapGeoPoint
import com.everyday.everyday_glasses.data_models.NavigationDestination
import com.everyday.everyday_glasses.data_models.NavigationState
import com.everyday.everyday_glasses.data_models.NavigationStatus
import com.everyday.everyday_glasses.data_models.toCompactBannerText
import com.everyday.shared.routing.GuidanceFixStatus
import com.everyday.shared.routing.LiveGuidanceSnapshot
import com.everyday.shared.routing.RouteManeuver
import com.everyday.shared.routing.RouteManeuverType
import com.everyday.shared.routing.RoutePolyline
import com.everyday.shared.routing.RouteResult
import com.everyday.shared.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGuidancePresentationTest {
    @Test
    fun activeBannerUsesNextManeuverDistanceRemainingDistanceAndEta() {
        val origin = GeoPoint(51.5, -0.125)
        val destination = GeoPoint(51.501, -0.125)
        val maneuver = RouteManeuver(
            type = RouteManeuverType.LEFT,
            point = GeoPoint(51.5005, -0.125),
            roadName = "Turn Lane",
            wayId = 2L,
            distanceFromPreviousMeters = 70.0,
            headingBeforeDeg = 90.0,
            headingAfterDeg = 0.0,
            instruction = "Turn left onto Turn Lane"
        )
        val route = route(origin, destination)
        val state = NavigationState(
            status = NavigationStatus.ACTIVE,
            destination = NavigationDestination(
                "Museum",
                "Museum, London",
                MapGeoPoint(destination.lat, destination.lon)
            ),
            route = route,
            guidance = LiveGuidanceSnapshot(
                progressMeters = 40.0,
                routeDistanceMeters = 140.0,
                remainingDistanceMeters = 100.0,
                remainingWalkingTimeSeconds = 72L,
                matchedPoint = origin,
                crossTrackDistanceMeters = 2.0,
                nextManeuverIndex = 0,
                nextManeuver = maneuver,
                distanceToNextManeuverMeters = 30.0,
                completedPolyline = listOf(origin),
                remainingPolyline = listOf(origin, destination),
                lastFixStatus = GuidanceFixStatus.ACCEPTED
            )
        )

        val banner = state.toCompactBannerText()
        val facts = state.toAssistantSemanticFacts().associate { it.key to it.value }

        assertEquals("Museum, London", banner.title)
        assertTrue(banner.maneuverLine.contains("Turn left"))
        assertTrue(banner.maneuverLine.contains("30 m"))
        assertEquals("100 m left • 2 min walk", banner.progressLine)
        assertEquals(72L, state.walkingEtaSeconds)
        assertEquals("Turn left onto Turn Lane", facts["navigation_next_maneuver"])
        assertEquals("100.0", facts["navigation_remaining_distance_meters"])
    }

    @Test
    fun degradedAndArrivedBannerStatesRemainCompactAndDistinct() {
        val origin = GeoPoint(51.5, -0.125)
        val destination = GeoPoint(51.501, -0.125)
        val route = route(origin, destination)
        val degraded = NavigationState(
            status = NavigationStatus.DEGRADED,
            destination = NavigationDestination(
                "Museum",
                "Museum, London",
                MapGeoPoint(destination.lat, destination.lon)
            ),
            route = route,
            errorMessage = "GPS stale; route held",
            guidance = snapshot(GuidanceFixStatus.STALE)
        )
        val arrived = degraded.copy(
            status = NavigationStatus.ARRIVED,
            errorMessage = null,
            guidance = snapshot(GuidanceFixStatus.ARRIVED)
        )

        assertTrue(degraded.toCompactBannerText().maneuverLine.startsWith("GPS stale"))
        assertEquals("Destination reached", arrived.toCompactBannerText().progressLine)
        assertEquals("Arrived", arrived.toCompactBannerText().maneuverLine)
    }

    private fun snapshot(status: GuidanceFixStatus) = LiveGuidanceSnapshot(
        progressMeters = 100.0,
        routeDistanceMeters = 140.0,
        remainingDistanceMeters = 40.0,
        remainingWalkingTimeSeconds = 29L,
        matchedPoint = null,
        crossTrackDistanceMeters = null,
        nextManeuverIndex = null,
        nextManeuver = null,
        distanceToNextManeuverMeters = null,
        completedPolyline = emptyList(),
        remainingPolyline = emptyList(),
        lastFixStatus = status
    )

    private fun route(origin: GeoPoint, destination: GeoPoint) = RouteResult(
        origin = origin,
        destination = destination,
        snappedOriginNodeId = 1L,
        snappedDestinationNodeId = 2L,
        polyline = RoutePolyline(listOf(origin, destination)),
        totalDistanceMeters = 140.0,
        estimatedWalkingTimeSeconds = 100L,
        graphNodeIds = listOf(1L, 2L),
        graphEdges = emptyList(),
        maneuvers = emptyList()
    )
}
