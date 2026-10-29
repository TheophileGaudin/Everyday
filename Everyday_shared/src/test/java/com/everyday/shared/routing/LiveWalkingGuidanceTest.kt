package com.everyday.shared.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWalkingGuidanceTest {
    @Test
    fun forwardTraceAdvancesMonotonicallyAndReducesEta() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }

        val first = engine.update(fix(0.0, timestamp = 1_000L), 1_000L, 1_000L).snapshot
        val middle = engine.update(fix(30.0, timestamp = 11_000L), 11_000L, 11_000L).snapshot
        val later = engine.update(fix(70.0, timestamp = 21_000L), 21_000L, 21_000L).snapshot

        assertEquals(0.0, first.progressMeters, 0.5)
        assertTrue(middle.progressMeters > first.progressMeters)
        assertTrue(later.progressMeters >= middle.progressMeters)
        assertTrue(later.remainingDistanceMeters < middle.remainingDistanceMeters)
        assertTrue(later.remainingWalkingTimeSeconds < middle.remainingWalkingTimeSeconds)
        assertEquals(GuidanceFixStatus.ACCEPTED, later.lastFixStatus)
        assertEquals(later.matchedPoint, later.remainingPolyline.first())
        assertTrue(later.completedPolyline.size >= 2)
    }

    @Test
    fun stationaryJitterDoesNotMoveProgressBackward() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }
        engine.update(fix(60.0, timestamp = 1_000L), 1_000L, 1_000L)

        val jitter = listOf(54.0, 58.0, 52.0).mapIndexed { index, metres ->
            engine.update(fix(metres, timestamp = 2_000L + index * 1_000L), 2_000L + index * 1_000L, 2_000L + index * 1_000L)
                .snapshot.progressMeters
        }

        assertTrue(jitter.all { it >= 60.0 - 0.5 })
    }

    @Test
    fun reversedTraceNeedsTwoEvidenceFixesBeforeControlledBackwardCorrection() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }
        engine.update(fix(80.0, timestamp = 1_000L), 1_000L, 1_000L)

        val firstReverse = engine.update(fix(60.0, timestamp = 11_000L), 11_000L, 11_000L).snapshot
        val confirmedReverse = engine.update(fix(40.0, timestamp = 21_000L), 21_000L, 21_000L).snapshot

        assertTrue(firstReverse.progressMeters >= 79.0)
        assertTrue(confirmedReverse.progressMeters < firstReverse.progressMeters - 10.0)
    }

    @Test
    fun staleAndExtremelyInaccurateFixesDegradeWithoutChangingProgress() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }
        val accepted = engine.update(fix(40.0, timestamp = 10_000L), 10_000L, 10_000L).snapshot

        val stale = engine.update(fix(90.0, accuracy = 5.0, timestamp = 10_000L), 20_001L, 20_001L).snapshot
        val inaccurate = engine.update(fix(90.0, accuracy = 150.0, timestamp = 21_000L), 21_000L, 21_000L).snapshot

        assertEquals(GuidanceFixStatus.STALE, stale.lastFixStatus)
        assertEquals(GuidanceFixStatus.INACCURATE, inaccurate.lastFixStatus)
        assertEquals(accepted.progressMeters, inaccurate.progressMeters, 0.5)
        assertTrue(inaccurate.isDegraded)
        assertEquals(0, inaccurate.consecutiveOffRouteFixes)
    }

    @Test
    fun impossibleTeleportDoesNotTriggerGuidanceProgress() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }
        val start = engine.update(fix(0.0, timestamp = 1_000L), 1_000L, 1_000L).snapshot
        val impossible = engine.update(fix(900.0, timestamp = 2_000L), 2_000L, 2_000L).snapshot

        assertEquals(GuidanceFixStatus.IMPOSSIBLE, impossible.lastFixStatus)
        assertEquals(start.progressMeters, impossible.progressMeters, 0.5)
    }

    @Test
    fun nextManeuverAdvancesAtStepBoundaryAndDistanceIsReported() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }

        val start = engine.update(fix(0.0, timestamp = 1_000L), 1_000L, 1_000L).snapshot
        val beforeTurn = engine.update(fix(30.0, timestamp = 11_000L), 11_000L, 11_000L).snapshot
        val afterTurn = engine.update(fix(55.0, timestamp = 21_000L), 21_000L, 21_000L).snapshot

        assertEquals(RouteManeuverType.START, start.nextManeuver?.type)
        assertEquals(RouteManeuverType.LEFT, beforeTurn.nextManeuver?.type)
        assertEquals(RouteManeuverType.ARRIVE, afterTurn.nextManeuver?.type)
        assertTrue((beforeTurn.distanceToNextManeuverMeters ?: 0.0) > 10.0)
        assertTrue((afterTurn.distanceToNextManeuverMeters ?: 0.0) > 30.0)
    }

    @Test
    fun exactOffRouteThresholdNeedsTwoFixesAndAccuracyExpandsIt() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }
        val exact = engine.update(fix(40.0, lateral = 30.0, accuracy = 15.0, timestamp = 1_000L), 1_000L, 1_000L)
        val qualifying = engine.update(fix(40.0, lateral = 31.0, accuracy = 15.0, timestamp = 2_000L), 2_000L, 2_000L)

        assertFalse(exact.shouldRequestReroute)
        assertEquals(GuidanceFixStatus.OFF_ROUTE, qualifying.fixStatus)
        assertTrue(qualifying.shouldRequestReroute)
        assertEquals(2, qualifying.snapshot.consecutiveOffRouteFixes)

        val accuracyEngine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }
        val belowAccuracyThreshold = accuracyEngine.update(
            fix(40.0, lateral = 39.0, accuracy = 20.0, timestamp = 1_000L),
            1_000L,
            1_000L
        )
        val aboveAccuracyThreshold = accuracyEngine.update(
            fix(40.0, lateral = 41.0, accuracy = 20.0, timestamp = 2_000L),
            2_000L,
            2_000L
        )
        assertFalse(belowAccuracyThreshold.shouldRequestReroute)
        assertEquals(GuidanceFixStatus.OFF_ROUTE, aboveAccuracyThreshold.fixStatus)
    }

    @Test
    fun rerouteIsThrottledForThirtySecondsAndPendingRouteKeepsProgressState() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }
        engine.update(fix(40.0, lateral = 40.0, timestamp = 1_000L), 1_000L, 1_000L)
        val request = engine.update(fix(40.0, lateral = 40.0, timestamp = 2_000L), 2_000L, 2_000L)
        assertTrue(request.shouldRequestReroute)

        engine.markRerouteRequested(2_000L)
        assertTrue(requireNotNull(engine.currentSnapshot()).reroutePending)
        engine.finishReroute()
        val throttled = engine.update(fix(40.0, lateral = 40.0, timestamp = 3_000L), 3_000L, 3_000L)
        assertFalse(throttled.shouldRequestReroute)
        val allowed = engine.update(fix(40.0, lateral = 40.0, timestamp = 32_000L), 32_000L, 32_000L)
        assertTrue(allowed.shouldRequestReroute)

        val beforeReplace = requireNotNull(engine.currentSnapshot()).routeDistanceMeters
        engine.markRerouteRequested(32_000L)
        engine.replaceRoute(route(offsetMeters = 10.0))
        val afterReplace = requireNotNull(engine.currentSnapshot())
        assertFalse(afterReplace.reroutePending)
        assertEquals(beforeReplace, afterReplace.routeDistanceMeters, 0.5)
        assertEquals(0.0, afterReplace.progressMeters, 0.5)
    }

    @Test
    fun arrivalNeedsTwoQualifyingFixesAndStopsFurtherRerouting() {
        val engine = LiveWalkingGuidanceEngine().also { it.startRoute(route()) }
        val firstNear = engine.update(fix(100.0, accuracy = 5.0, timestamp = 1_000L), 1_000L, 1_000L)
        val falseNear = engine.update(fix(80.0, accuracy = 5.0, timestamp = 2_000L), 2_000L, 2_000L)
        val nearAgain = engine.update(fix(100.0, accuracy = 5.0, timestamp = 3_000L), 3_000L, 3_000L)
        val arrived = engine.update(fix(100.0, accuracy = 5.0, timestamp = 4_000L), 4_000L, 4_000L)
        val afterArrival = engine.update(fix(40.0, lateral = 100.0, accuracy = 5.0, timestamp = 5_000L), 5_000L, 5_000L)

        assertFalse(firstNear.arrivedNow)
        assertEquals(0, falseNear.snapshot.consecutiveArrivalFixes)
        assertFalse(nearAgain.arrivedNow)
        assertTrue(arrived.arrivedNow)
        assertEquals(GuidanceFixStatus.ARRIVED, arrived.fixStatus)
        assertEquals(GuidanceFixStatus.ARRIVED, afterArrival.fixStatus)
        assertFalse(afterArrival.shouldRequestReroute)
    }

    private fun fix(
        along: Double,
        lateral: Double = 0.0,
        accuracy: Double = 5.0,
        timestamp: Long
    ): LiveLocationFix = LiveLocationFix(
        point = point(along, lateral),
        accuracyMeters = accuracy,
        timestampMs = timestamp,
        monotonicTimeMs = timestamp
    )

    private fun route(offsetMeters: Double = 0.0): RouteResult {
        val origin = point(offsetMeters)
        val turn = point(offsetMeters + 50.0)
        val destination = point(offsetMeters + 100.0)
        val polyline = RoutePolyline(listOf(origin, turn, destination))
        return RouteResult(
            origin = origin,
            destination = destination,
            snappedOriginNodeId = 1L,
            snappedDestinationNodeId = 3L,
            polyline = polyline,
            totalDistanceMeters = polyline.lengthMeters,
            estimatedWalkingTimeSeconds = kotlin.math.ceil(polyline.lengthMeters / 1.4).toLong(),
            graphNodeIds = listOf(1L, 2L, 3L),
            graphEdges = emptyList(),
            maneuvers = listOf(
                RouteManeuver(
                    type = RouteManeuverType.START,
                    point = origin,
                    roadName = "Route",
                    wayId = 1L,
                    distanceFromPreviousMeters = 0.0,
                    headingBeforeDeg = null,
                    headingAfterDeg = 90.0,
                    instruction = "Start walking"
                ),
                RouteManeuver(
                    type = RouteManeuverType.LEFT,
                    point = turn,
                    roadName = "Turn Lane",
                    wayId = 2L,
                    distanceFromPreviousMeters = 50.0,
                    headingBeforeDeg = 90.0,
                    headingAfterDeg = 0.0,
                    instruction = "Turn left"
                ),
                RouteManeuver(
                    type = RouteManeuverType.ARRIVE,
                    point = destination,
                    roadName = "Turn Lane",
                    wayId = 2L,
                    distanceFromPreviousMeters = 50.0,
                    headingBeforeDeg = 0.0,
                    headingAfterDeg = null,
                    instruction = "Arrive at your destination"
                )
            )
        )
    }

    private fun point(alongMeters: Double, lateralMeters: Double = 0.0): GeoPoint = GeoPoint(
        lat = lateralMeters / 111_195.0802,
        lon = alongMeters / 111_195.0802
    )
}
