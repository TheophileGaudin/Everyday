package com.everyday.everyday_phone

import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.RouteFailure
import com.everyday.shared.routing.RouteManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapboxDirectionsTest {
    private val origin = GeoPoint(49.5042, 0.0973)
    private val destination = GeoPoint(49.5100, 0.1050)

    @Test
    fun urlUsesWalkingStepsGeoJsonAndDeviceLanguage() {
        val url = MapboxDirectionsProvider("pk.test").buildUrl(origin, destination, "fr-FR")

        assertTrue(url.contains("/directions/v5/mapbox/walking/"))
        assertTrue(url.contains("steps=true"))
        assertTrue(url.contains("overview=full"))
        assertTrue(url.contains("geometries=geojson"))
        assertTrue(url.contains("language=fr"))
        assertTrue(url.contains("access_token=pk.test"))
    }

    @Test
    fun parsesGeometryDurationAndTurnByTurnSteps() {
        val result = MapboxDirectionsProvider("pk.test").parseResponse(SUCCESS, origin, destination)

        assertEquals(3, result.polyline.points.size)
        assertEquals(820.5, result.totalDistanceMeters, 0.001)
        assertEquals(601L, result.estimatedWalkingTimeSeconds)
        assertEquals(listOf(RouteManeuverType.START, RouteManeuverType.RIGHT, RouteManeuverType.ARRIVE),
            result.maneuvers.map { it.type })
        assertEquals(0.0, result.maneuvers[0].distanceFromPreviousMeters, 0.001)
        assertEquals(300.0, result.maneuvers[1].distanceFromPreviousMeters, 0.001)
        assertEquals(520.5, result.maneuvers[2].distanceFromPreviousMeters, 0.001)
        assertEquals("Turn right onto Rue de Paris", result.maneuvers[1].instruction)
        assertTrue(result.graphNodeIds.isEmpty())
        assertTrue(result.graphEdges.isEmpty())
    }

    @Test
    fun mapsAuthorizationNoRouteAndMalformedResponsesToStructuredFailures() {
        val unauthorized = MapboxDirectionsProvider(
            accessToken = "pk.test",
            fetchResponse = { 401 to "{}" }
        ).route(origin, destination, "en").exceptionOrNull() as MapboxDirectionsException
        assertTrue(unauthorized.failure is RouteFailure.AuthorizationFailure)

        val noRoute = MapboxDirectionsProvider("pk.test").routeFromFixture(
            """{"code":"NoRoute","message":"No route found"}"""
        )
        assertTrue(noRoute.failure is RouteFailure.DisconnectedGraph)

        val malformed = MapboxDirectionsProvider("pk.test").routeFromFixture("not-json")
        assertTrue(malformed.failure is RouteFailure.MalformedResponse)
    }

    private fun MapboxDirectionsProvider.routeFromFixture(payload: String): MapboxDirectionsException =
        runCatching { parseResponse(payload, origin, destination) }.exceptionOrNull() as MapboxDirectionsException

    companion object {
        private val SUCCESS = """
            {
              "code":"Ok",
              "routes":[{
                "distance":820.5,
                "duration":600.2,
                "geometry":{"type":"LineString","coordinates":[[0.0973,49.5042],[0.101,49.507],[0.105,49.51]]},
                "legs":[{"steps":[
                  {"distance":300.0,"duration":210.0,"name":"Rue A","maneuver":{"type":"depart","instruction":"Head east on Rue A","location":[0.0973,49.5042],"bearing_after":90}},
                  {"distance":520.5,"duration":370.0,"name":"Rue de Paris","maneuver":{"type":"turn","modifier":"right","instruction":"Turn right onto Rue de Paris","location":[0.101,49.507],"bearing_before":90,"bearing_after":180}},
                  {"distance":0.0,"duration":0.0,"name":"","maneuver":{"type":"arrive","instruction":"You have arrived","location":[0.105,49.51],"bearing_before":180}}
                ]}]
              }]
            }
        """.trimIndent()
    }
}
