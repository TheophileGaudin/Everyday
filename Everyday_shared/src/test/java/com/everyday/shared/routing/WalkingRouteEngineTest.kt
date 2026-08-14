package com.everyday.shared.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkingRouteEngineTest {
    private val engine = WalkingRouteEngine()
    private val builder = PedestrianGraphBuilder()

    @Test
    fun normalRouteUsesWalkingGraphAndPreservesFullPolyline() {
        val origin = GeoPoint(51.0001, -0.1)
        val destination = GeoPoint(51.0001, -0.096)
        val result = engine.calculate(
            RouteRequest(origin, destination, maxSnapDistanceMeters = 30.0),
            fixtureData("normal_route.json")
        )

        val success = assertSuccess(result)
        assertEquals(1L, success.route.snappedOriginNodeId)
        assertEquals(5L, success.route.snappedDestinationNodeId)
        assertEquals(listOf(1L, 2L, 5L), success.route.graphNodeIds)
        assertEquals(origin, success.route.polyline.points.first())
        assertEquals(destination, success.route.polyline.points.last())
        assertEquals(success.route.polyline.lengthMeters, success.route.totalDistanceMeters, 0.000001)
        assertTrue(success.route.totalDistanceMeters > GeoMath.distanceMeters(origin, destination))
        assertTrue(success.route.maneuvers.any { it.type == RouteManeuverType.START })
        assertTrue(success.route.maneuvers.any { it.type == RouteManeuverType.ARRIVE })
        assertEquals(
            kotlin.math.ceil(success.route.totalDistanceMeters / 1.4).toLong(),
            success.route.estimatedWalkingTimeSeconds
        )
        success.route.polyline.points.zipWithNext().forEach { (a, b) ->
            assertTrue(GeoMath.distanceMeters(a, b).isFinite())
        }
    }

    @Test
    fun alternateRouteChoosesShorterTotalWalkingDistance() {
        val data = fixtureData("alternate_shorter_route.json")
        val result = engine.calculate(
            RouteRequest(GeoPoint(51.0, -0.12), GeoPoint(51.0, -0.116)),
            data
        )

        val success = assertSuccess(result)
        assertEquals(listOf(1L, 3L, 4L, 5L), success.route.graphNodeIds)
        assertEquals("Shortcut", success.route.graphEdges.first().name)
    }

    @Test
    fun endpointSnappingReportsNodeAndRejectsUnreasonableDistance() {
        val data = fixtureData("normal_route.json")
        val snapped = engine.calculate(
            RouteRequest(
                origin = GeoPoint(51.00012, -0.1),
                destination = GeoPoint(51.00012, -0.096),
                maxSnapDistanceMeters = 25.0
            ),
            data
        )
        assertEquals(1L, assertSuccess(snapped).route.snappedOriginNodeId)

        val tooFar = engine.calculate(
            RouteRequest(
                origin = GeoPoint(51.005, -0.1),
                destination = GeoPoint(51.005, -0.096),
                maxSnapDistanceMeters = 25.0
            ),
            data
        )
        val failure = assertFailure(tooFar)
        assertTrue(failure.failure is RouteFailure.NoNearbyGraphNode)
        val noNode = failure.failure as RouteFailure.NoNearbyGraphNode
        assertEquals(RouteEndpoint.ORIGIN, noNode.endpoint)
        assertNotNull(noNode.nearestDistanceMeters)
    }

    @Test
    fun stepsAndCrossingsBecomeExplicitManeuvers() {
        val result = engine.calculate(
            RouteRequest(GeoPoint(51.0, -0.11), GeoPoint(51.0005, -0.107)),
            fixtureData("steps_crossings.json")
        )

        val types = assertSuccess(result).route.maneuvers.map { it.type }
        assertTrue(types.contains(RouteManeuverType.STAIRS))
        assertTrue(types.contains(RouteManeuverType.CROSSING))
    }

    @Test
    fun accessRulesExcludePrivateAndFootForbiddenWaysAndRespectPedestrianDirection() {
        val graph = builder.build(fixtureData("access_direction.json"))
        fun has(from: Long, to: Long, way: Long) = graph.edges.any {
            it.fromNodeId == from && it.toNodeId == to && it.wayId == way
        }

        // Ordinary vehicle one-way is bidirectional for walking.
        assertTrue(has(20L, 21L, 600L))
        assertTrue(has(21L, 20L, 600L))
        // Private and foot-forbidden ways are not in the graph.
        assertFalse(graph.edges.any { it.wayId == 601L })
        assertFalse(graph.edges.any { it.wayId == 602L })
        // An explicit foot=yes overrides access=private.
        assertTrue(has(26L, 27L, 603L))
        assertTrue(has(27L, 26L, 603L))
        // Explicit pedestrian one-way is directional; ordinary oneway is not.
        assertTrue(has(28L, 29L, 604L))
        assertFalse(has(29L, 28L, 604L))
        assertTrue(has(29L, 30L, 605L))
        assertTrue(has(30L, 29L, 605L))
    }

    @Test
    fun equalCostCandidatesAreDeterministic() {
        val data = fixtureData("equal_cost.json")
        val request = RouteRequest(GeoPoint(51.04, -0.13), GeoPoint(51.04, -0.128))
        val paths = (1..20).map {
            assertSuccess(engine.calculate(request, data)).route.graphNodeIds
        }

        assertTrue(paths.all { it == listOf(1L, 2L, 4L) })
    }

    @Test
    fun disconnectedGraphIsClassified() {
        val result = engine.calculate(
            RouteRequest(GeoPoint(51.02, -0.11), GeoPoint(51.02, -0.105)),
            fixtureData("disconnected.json")
        )
        val failure = assertFailure(result).failure
        assertEquals(RouteFailureCode.DISCONNECTED_GRAPH, failure.code)
        assertTrue(failure is RouteFailure.DisconnectedGraph)
    }

    @Test
    fun malformedResponseIsTerminalAndDoesNotBecomeAnEmptyGraph() {
        val parsed = OverpassResponseParser.parse(fixture("malformed_response.json"))
        assertTrue(parsed is OverpassParseResult.Failure)
        val failure = (parsed as OverpassParseResult.Failure).failure
        assertEquals(RouteFailureCode.MALFORMED_RESPONSE, failure.code)
        assertTrue(OverpassResponseParser.parse("not json") is OverpassParseResult.Failure)
    }

    @Test
    fun overLimitIsRejectedBeforeTheProviderIsCalled() {
        var calls = 0
        val repository = WalkingRouteRepository(RouteGraphProvider { _, _ ->
            calls++
            RouteGraphFetchResult.Success(fixtureData("normal_route.json"))
        })
        val result = repository.calculate(
            RouteRequest(GeoPoint(51.0, -0.1), GeoPoint(51.0, -0.35))
        )

        val failure = assertFailure(result)
        assertEquals(RouteFailureCode.OVER_LIMIT, failure.failure.code)
        assertEquals(0, failure.attempts)
        assertEquals(0, calls)
    }

    @Test
    fun nonWalkingModeIsRejectedBeforeTheProviderIsCalled() {
        var calls = 0
        val repository = WalkingRouteRepository(RouteGraphProvider { _, _ ->
            calls++
            RouteGraphFetchResult.Success(fixtureData("normal_route.json"))
        })
        val result = repository.calculate(
            RouteRequest(GeoPoint(51.0, -0.1), GeoPoint(51.0, -0.096), mode = RouteMode.DRIVING)
        )

        val failure = assertFailure(result)
        assertEquals(RouteFailureCode.UNSUPPORTED_MODE, failure.failure.code)
        assertEquals(0, failure.attempts)
        assertEquals(0, calls)
    }

    @Test
    fun retryUsesWiderAreaOnlyForGraphFailures() {
        val normal = fixtureData("normal_route.json")
        val disconnected = normal.copy(
            ways = listOf(
                normal.ways.first().copy(nodeIds = listOf(1L, 2L)),
                normal.ways[1].copy(nodeIds = listOf(3L, 4L, 5L))
            )
        )
        val buffers = ArrayList<Int>()
        val repository = WalkingRouteRepository(RouteGraphProvider { _, buffer ->
            buffers += buffer
            RouteGraphFetchResult.Success(if (buffer == 400) disconnected else normal)
        })

        val result = repository.calculate(
            RouteRequest(GeoPoint(51.0, -0.1), GeoPoint(51.0, -0.096))
        )
        val success = assertSuccess(result)
        assertEquals(listOf(400, 800), buffers)
        assertEquals(2, success.attempts)
        assertEquals(800, success.queryBufferMeters)

        val terminalBuffers = ArrayList<Int>()
        val terminal = WalkingRouteRepository(RouteGraphProvider { _, buffer ->
            terminalBuffers += buffer
            RouteGraphFetchResult.Failure(RouteFailure.MalformedResponse("fixture error"))
        }).calculate(RouteRequest(GeoPoint(51.0, -0.1), GeoPoint(51.0, -0.096)))
        assertTrue(assertFailure(terminal).failure is RouteFailure.MalformedResponse)
        assertEquals(listOf(400), terminalBuffers)
    }

    @Test
    fun timeoutAndAuthorizationFailuresAreTerminal() {
        val request = RouteRequest(GeoPoint(51.0, -0.1), GeoPoint(51.0, -0.096))
        listOf<RouteFailure>(
            RouteFailure.Timeout("fixture timeout"),
            RouteFailure.AuthorizationFailure(403, "fixture authorization")
        ).forEach { terminalFailure ->
            val buffers = ArrayList<Int>()
            val result = WalkingRouteRepository(RouteGraphProvider { _, buffer ->
                buffers += buffer
                RouteGraphFetchResult.Failure(terminalFailure)
            }).calculate(request)
            assertEquals(terminalFailure, assertFailure(result).failure)
            assertEquals(listOf(400), buffers)
        }
    }

    @Test
    fun queryIncludesCompleteWaysNodesAndRequestedBuffer() {
        val request = RouteRequest(GeoPoint(51.0, -0.1), GeoPoint(51.0, -0.096))
        val initial = OverpassQueryBuilder.build(request, 400)
        val retry = OverpassQueryBuilder.build(request, 800)
        assertTrue(initial.contains("way("))
        assertTrue(initial.contains("out body"))
        assertTrue(initial.contains(">;"))
        assertTrue(initial.contains("out skel qt"))
        assertTrue(retry != initial)
        assertTrue(
            OverpassQueryBuilder.bounds(request, 800).south < OverpassQueryBuilder.bounds(request, 400).south
        )
    }

    private fun assertSuccess(result: RouteCalculationResult): RouteCalculationResult.Success {
        assertTrue("Expected route success but got $result", result is RouteCalculationResult.Success)
        return result as RouteCalculationResult.Success
    }

    private fun assertFailure(result: RouteCalculationResult): RouteCalculationResult.Failure {
        assertTrue("Expected route failure but got $result", result is RouteCalculationResult.Failure)
        return result as RouteCalculationResult.Failure
    }

    private fun fixtureData(name: String): OverpassRouteData {
        val parsed = OverpassResponseParser.parse(fixture(name))
        assertTrue("Fixture $name should parse: $parsed", parsed is OverpassParseResult.Success)
        return (parsed as OverpassParseResult.Success).data
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/com/everyday/shared/routing/$name")) {
            "Missing fixture $name"
        }.readText()
}
