package com.everyday.shared.routing

import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.abs

/** Tunable safety bounds for the live walking-guidance state machine. */
object LiveGuidanceDefaults {
    const val WALKING_SPEED_MPS = 1.4
    const val LOCATION_STALE_AFTER_MS = 10_000L
    const val MAX_CLOCK_SKEW_MS = 2_000L
    const val MAX_USABLE_ACCURACY_METERS = 100.0
    const val UNKNOWN_ACCURACY_METERS = 50.0
    const val DEGRADED_ACCURACY_METERS = 30.0
    const val OFF_ROUTE_MIN_METERS = 30.0
    const val DESTINATION_MIN_METERS = 20.0
    const val REROUTE_FIX_COUNT = 2
    const val ARRIVAL_FIX_COUNT = 2
    const val REROUTE_THROTTLE_MS = 30_000L
    const val REVERSE_FIX_COUNT = 2
    const val REVERSE_NOISE_TOLERANCE_METERS = 8.0
    const val REVERSE_CONFIRMATION_METERS = 10.0
    const val MANEUVER_CROSSING_TOLERANCE_METERS = 2.0
    const val START_MANEUVER_HOLD_METERS = 5.0
    const val MAX_WALKING_SPEED_MPS = 6.0
    const val TELEPORT_GRACE_METERS = 250.0
}

/** A location sample supplied by either the glasses provider or the phone sync stream. */
data class LiveLocationFix(
    val point: GeoPoint,
    val accuracyMeters: Double?,
    val timestampMs: Long,
    val monotonicTimeMs: Long
) {
    init {
        require(timestampMs >= 0L) { "Location timestamp must not be negative" }
        require(monotonicTimeMs >= 0L) { "Location monotonic timestamp must not be negative" }
        require(accuracyMeters == null || (accuracyMeters.isFinite() && accuracyMeters >= 0.0)) {
            "Location accuracy must be finite and non-negative"
        }
    }
}

/** Why a location sample did or did not advance live guidance. */
enum class GuidanceFixStatus {
    NO_FIX,
    ACCEPTED,
    STALE,
    INACCURATE,
    IMPOSSIBLE,
    OFF_ROUTE,
    ARRIVED
}

/** Geometry and display data for the current position on a route. */
data class LiveGuidanceSnapshot(
    val progressMeters: Double,
    val routeDistanceMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingWalkingTimeSeconds: Long,
    val matchedPoint: GeoPoint?,
    val crossTrackDistanceMeters: Double?,
    val nextManeuverIndex: Int?,
    val nextManeuver: RouteManeuver?,
    val distanceToNextManeuverMeters: Double?,
    val completedPolyline: List<GeoPoint>,
    val remainingPolyline: List<GeoPoint>,
    val lastFixStatus: GuidanceFixStatus = GuidanceFixStatus.NO_FIX,
    val reportedAccuracyMeters: Double? = null,
    val offRouteThresholdMeters: Double? = null,
    val consecutiveOffRouteFixes: Int = 0,
    val consecutiveArrivalFixes: Int = 0,
    val reroutePending: Boolean = false,
    val isDegraded: Boolean = false
)

data class LiveGuidanceUpdate(
    val snapshot: LiveGuidanceSnapshot,
    val fixStatus: GuidanceFixStatus,
    val shouldRequestReroute: Boolean = false,
    val arrivedNow: Boolean = false
)

/**
 * Deterministic route-following state.  It has no Android or clock dependency: callers provide
 * source and monotonic times with each sample, making real traces and unit tests share the same
 * behavior.
 */
class LiveWalkingGuidanceEngine(
    private val walkingSpeedMps: Double = LiveGuidanceDefaults.WALKING_SPEED_MPS,
    private val staleAfterMs: Long = LiveGuidanceDefaults.LOCATION_STALE_AFTER_MS,
    private val maxUsableAccuracyMeters: Double = LiveGuidanceDefaults.MAX_USABLE_ACCURACY_METERS
) {
    private var route: RouteResult? = null
    private var routeLengthMeters = 0.0
    private var cumulativePolylineMeters = emptyList<Double>()
    private var maneuverBoundariesMeters = emptyList<Double>()
    private var progressMeters = 0.0
    private var matchedPoint: GeoPoint? = null
    private var matchedSegmentIndex: Int? = null
    private var matchedSegmentFraction = 0.0
    private var crossTrackDistanceMeters: Double? = null
    private var lastFixStatus = GuidanceFixStatus.NO_FIX
    private var reportedAccuracyMeters: Double? = null
    private var offRouteThresholdMeters: Double? = null
    private var consecutiveOffRouteFixes = 0
    private var consecutiveArrivalFixes = 0
    private var reroutePending = false
    private var arrived = false
    private var lastAcceptedFix: LiveLocationFix? = null
    private var lastRerouteRequestMonotonicMs: Long? = null
    private var reverseEvidenceProgress: Double? = null
    private var reverseEvidenceCount = 0
    private var snapshot = emptySnapshot()

    init {
        require(walkingSpeedMps.isFinite() && walkingSpeedMps > 0.0) {
            "Walking speed must be positive"
        }
        require(staleAfterMs > 0L) { "Stale location window must be positive" }
        require(maxUsableAccuracyMeters > 0.0 && maxUsableAccuracyMeters.isFinite()) {
            "Maximum usable accuracy must be positive"
        }
    }

    fun startRoute(newRoute: RouteResult): LiveGuidanceSnapshot {
        lastRerouteRequestMonotonicMs = null
        installRoute(newRoute)
        return snapshot
    }

    /** Atomically installs a valid replacement while retaining reroute throttle history. */
    fun replaceRoute(newRoute: RouteResult): LiveGuidanceSnapshot {
        installRoute(newRoute)
        return snapshot
    }

    fun currentSnapshot(): LiveGuidanceSnapshot? = route?.let { snapshot }

    fun markRerouteRequested(monotonicTimeMs: Long) {
        require(monotonicTimeMs >= 0L) { "Reroute time must not be negative" }
        if (route == null || arrived) return
        lastRerouteRequestMonotonicMs = monotonicTimeMs
        reroutePending = true
        snapshot = buildSnapshot(lastFixStatus, isDegraded = snapshot.isDegraded)
    }

    fun finishReroute() {
        reroutePending = false
        snapshot = buildSnapshot(lastFixStatus, isDegraded = snapshot.isDegraded)
    }

    /** Clears progress, counters, throttle history, and any pending request state. */
    fun reset() {
        route = null
        routeLengthMeters = 0.0
        cumulativePolylineMeters = emptyList()
        maneuverBoundariesMeters = emptyList()
        progressMeters = 0.0
        matchedPoint = null
        matchedSegmentIndex = null
        matchedSegmentFraction = 0.0
        crossTrackDistanceMeters = null
        lastFixStatus = GuidanceFixStatus.NO_FIX
        reportedAccuracyMeters = null
        offRouteThresholdMeters = null
        consecutiveOffRouteFixes = 0
        consecutiveArrivalFixes = 0
        reroutePending = false
        arrived = false
        lastAcceptedFix = null
        lastRerouteRequestMonotonicMs = null
        reverseEvidenceProgress = null
        reverseEvidenceCount = 0
        snapshot = emptySnapshot()
    }

    fun update(
        fix: LiveLocationFix,
        nowTimestampMs: Long,
        nowMonotonicTimeMs: Long
    ): LiveGuidanceUpdate {
        require(nowTimestampMs >= 0L) { "Current timestamp must not be negative" }
        require(nowMonotonicTimeMs >= 0L) { "Current monotonic timestamp must not be negative" }
        check(route != null) { "A route must be started before location updates" }

        if (arrived) {
            lastFixStatus = GuidanceFixStatus.ARRIVED
            snapshot = buildSnapshot(GuidanceFixStatus.ARRIVED, isDegraded = false)
            return LiveGuidanceUpdate(snapshot, GuidanceFixStatus.ARRIVED)
        }

        val reportedAccuracy = fix.accuracyMeters?.takeIf { it > 0.0 }
        val decisionAccuracy = reportedAccuracy ?: LiveGuidanceDefaults.UNKNOWN_ACCURACY_METERS
        reportedAccuracyMeters = reportedAccuracy
        offRouteThresholdMeters = max(
            LiveGuidanceDefaults.OFF_ROUTE_MIN_METERS,
            decisionAccuracy * 2.0
        )

        val stale = fix.timestampMs == 0L ||
            nowTimestampMs > fix.timestampMs && nowTimestampMs - fix.timestampMs > staleAfterMs
        val future = fix.timestampMs > nowTimestampMs + LiveGuidanceDefaults.MAX_CLOCK_SKEW_MS
        val nonMonotonic = lastAcceptedFix?.let {
            fix.monotonicTimeMs <= it.monotonicTimeMs || fix.timestampMs <= it.timestampMs
        } == true
        if (stale || nonMonotonic) return ignored(GuidanceFixStatus.STALE, decisionAccuracy)
        if (future) return ignored(GuidanceFixStatus.IMPOSSIBLE, decisionAccuracy)
        if (reportedAccuracy != null && reportedAccuracy > maxUsableAccuracyMeters) {
            return ignored(GuidanceFixStatus.INACCURATE, decisionAccuracy)
        }

        val previousFix = lastAcceptedFix
        if (previousFix != null) {
            val elapsedMs = fix.monotonicTimeMs - previousFix.monotonicTimeMs
            if (elapsedMs <= 0L) return ignored(GuidanceFixStatus.STALE, decisionAccuracy)
            val elapsedSeconds = elapsedMs / 1_000.0
            val maximumTravel = max(
                LiveGuidanceDefaults.TELEPORT_GRACE_METERS,
                elapsedSeconds * LiveGuidanceDefaults.MAX_WALKING_SPEED_MPS + decisionAccuracy * 2.0
            )
            if (GeoMath.distanceMeters(previousFix.point, fix.point) > maximumTravel) {
                return ignored(GuidanceFixStatus.IMPOSSIBLE, decisionAccuracy)
            }
        }

        lastAcceptedFix = fix
        val routeValue = requireNotNull(route)
        val projections = projectOntoRoute(routeValue.polyline.points, fix.point)
        val chosen = chooseProjection(projections, decisionAccuracy)
        crossTrackDistanceMeters = chosen.distanceMeters

        val destinationDistance = GeoMath.distanceMeters(fix.point, routeValue.destination)
        val arrivalThreshold = max(LiveGuidanceDefaults.DESTINATION_MIN_METERS, decisionAccuracy)
        val isNearDestination = destinationDistance <= arrivalThreshold
        if (isNearDestination) {
            consecutiveArrivalFixes++
        } else {
            consecutiveArrivalFixes = 0
        }

        if (isNearDestination && consecutiveArrivalFixes >= LiveGuidanceDefaults.ARRIVAL_FIX_COUNT) {
            arrived = true
            reroutePending = false
            consecutiveOffRouteFixes = 0
            reverseEvidenceProgress = null
            reverseEvidenceCount = 0
            acceptProjection(chosen, decisionAccuracy)
            lastFixStatus = GuidanceFixStatus.ARRIVED
            snapshot = buildSnapshot(GuidanceFixStatus.ARRIVED, isDegraded = false)
            return LiveGuidanceUpdate(snapshot, GuidanceFixStatus.ARRIVED, arrivedNow = true)
        }

        val offRoute = chosen.distanceMeters > offRouteThresholdMeters!!
        if (offRoute) {
            consecutiveOffRouteFixes++
            consecutiveArrivalFixes = 0
            reverseEvidenceProgress = null
            reverseEvidenceCount = 0
            lastFixStatus = GuidanceFixStatus.OFF_ROUTE
            val shouldRequest = consecutiveOffRouteFixes >= LiveGuidanceDefaults.REROUTE_FIX_COUNT &&
                !reroutePending &&
                rerouteAllowed(nowMonotonicTimeMs)
            snapshot = buildSnapshot(GuidanceFixStatus.OFF_ROUTE, isDegraded = true)
            return LiveGuidanceUpdate(snapshot, GuidanceFixStatus.OFF_ROUTE, shouldRequestReroute = shouldRequest)
        }

        consecutiveOffRouteFixes = 0
        acceptProjection(chosen, decisionAccuracy)
        val degraded = reportedAccuracy == null || decisionAccuracy >= LiveGuidanceDefaults.DEGRADED_ACCURACY_METERS
        lastFixStatus = GuidanceFixStatus.ACCEPTED
        snapshot = buildSnapshot(GuidanceFixStatus.ACCEPTED, isDegraded = degraded)
        return LiveGuidanceUpdate(snapshot, GuidanceFixStatus.ACCEPTED)
    }

    private fun installRoute(newRoute: RouteResult) {
        route = newRoute
        val points = newRoute.polyline.points
        routeLengthMeters = GeoMath.polylineDistanceMeters(points)
        cumulativePolylineMeters = cumulativeDistances(points)
        maneuverBoundariesMeters = maneuverBoundaries(newRoute)
        progressMeters = 0.0
        matchedPoint = points.firstOrNull()
        matchedSegmentIndex = if (points.size > 1) 0 else null
        matchedSegmentFraction = 0.0
        crossTrackDistanceMeters = null
        lastFixStatus = GuidanceFixStatus.NO_FIX
        reportedAccuracyMeters = null
        offRouteThresholdMeters = null
        consecutiveOffRouteFixes = 0
        consecutiveArrivalFixes = 0
        reroutePending = false
        arrived = false
        lastAcceptedFix = null
        reverseEvidenceProgress = null
        reverseEvidenceCount = 0
        snapshot = buildSnapshot(GuidanceFixStatus.NO_FIX, isDegraded = false)
    }

    private fun ignored(status: GuidanceFixStatus, decisionAccuracy: Double): LiveGuidanceUpdate {
        consecutiveOffRouteFixes = 0
        consecutiveArrivalFixes = 0
        reverseEvidenceProgress = null
        reverseEvidenceCount = 0
        lastFixStatus = status
        snapshot = buildSnapshot(status, isDegraded = true).copy(
            reportedAccuracyMeters = reportedAccuracyMeters,
            offRouteThresholdMeters = max(
                LiveGuidanceDefaults.OFF_ROUTE_MIN_METERS,
                decisionAccuracy * 2.0
            )
        )
        return LiveGuidanceUpdate(snapshot, status)
    }

    private fun acceptProjection(projection: Projection, accuracyMeters: Double) {
        val previousProgress = progressMeters
        val backwardTolerance = max(
            LiveGuidanceDefaults.REVERSE_NOISE_TOLERANCE_METERS,
            accuracyMeters * 1.5
        )
        val candidateProgress = projection.alongRouteMeters.coerceIn(0.0, routeLengthMeters)
        if (candidateProgress < previousProgress - backwardTolerance) {
            val previousEvidence = reverseEvidenceProgress
            val movedFurtherBack = previousEvidence != null &&
                candidateProgress <= previousEvidence - max(3.0, accuracyMeters * 0.5)
            reverseEvidenceCount = if (movedFurtherBack) reverseEvidenceCount + 1 else 1
            reverseEvidenceProgress = candidateProgress
            val confirmed = reverseEvidenceCount >= LiveGuidanceDefaults.REVERSE_FIX_COUNT &&
                previousProgress - candidateProgress >= max(
                    LiveGuidanceDefaults.REVERSE_CONFIRMATION_METERS,
                    accuracyMeters * 0.75
                )
            if (!confirmed) {
                matchedPoint = matchedPoint ?: projection.point
                return
            }
            progressMeters = candidateProgress
            reverseEvidenceProgress = null
            reverseEvidenceCount = 0
        } else {
            progressMeters = max(previousProgress, candidateProgress)
            reverseEvidenceProgress = null
            reverseEvidenceCount = 0
        }
        matchedPoint = projection.point
        matchedSegmentIndex = projection.segmentIndex
        matchedSegmentFraction = projection.segmentFraction
    }

    private fun rerouteAllowed(nowMonotonicTimeMs: Long): Boolean {
        val previous = lastRerouteRequestMonotonicMs ?: return true
        return nowMonotonicTimeMs >= previous + LiveGuidanceDefaults.REROUTE_THROTTLE_MS
    }

    private fun buildSnapshot(status: GuidanceFixStatus, isDegraded: Boolean): LiveGuidanceSnapshot {
        val routeValue = route ?: return emptySnapshot()
        val safeProgress = progressMeters.coerceIn(0.0, routeLengthMeters)
        val remaining = (routeLengthMeters - safeProgress).coerceAtLeast(0.0)
        val next = nextManeuver(safeProgress, routeValue.maneuvers)
        val geometry = progressGeometry(routeValue.polyline.points)
        return LiveGuidanceSnapshot(
            progressMeters = safeProgress,
            routeDistanceMeters = routeLengthMeters,
            remainingDistanceMeters = remaining,
            remainingWalkingTimeSeconds = (remaining / walkingSpeedMps).let { seconds ->
                if (seconds <= 0.0) 0L else ceil(seconds).toLong().coerceAtLeast(1L)
            },
            matchedPoint = matchedPoint,
            crossTrackDistanceMeters = crossTrackDistanceMeters,
            nextManeuverIndex = next?.first,
            nextManeuver = next?.second,
            distanceToNextManeuverMeters = next?.let { (index, _) ->
                (maneuverBoundariesMeters.getOrNull(index) ?: safeProgress) - safeProgress
            }?.coerceAtLeast(0.0),
            completedPolyline = geometry.first,
            remainingPolyline = geometry.second,
            lastFixStatus = status,
            reportedAccuracyMeters = reportedAccuracyMeters,
            offRouteThresholdMeters = offRouteThresholdMeters,
            consecutiveOffRouteFixes = consecutiveOffRouteFixes,
            consecutiveArrivalFixes = consecutiveArrivalFixes,
            reroutePending = reroutePending,
            isDegraded = isDegraded
        )
    }

    private fun nextManeuver(
        progress: Double,
        maneuvers: List<RouteManeuver>
    ): Pair<Int, RouteManeuver>? {
        maneuvers.forEachIndexed { index, maneuver ->
            val boundary = maneuverBoundariesMeters.getOrNull(index) ?: return@forEachIndexed
            val passed = if (maneuver.type == RouteManeuverType.START) {
                progress >= max(boundary + LiveGuidanceDefaults.START_MANEUVER_HOLD_METERS,
                    LiveGuidanceDefaults.START_MANEUVER_HOLD_METERS)
            } else {
                progress >= boundary - LiveGuidanceDefaults.MANEUVER_CROSSING_TOLERANCE_METERS
            }
            if (!passed) return index to maneuver
        }
        return null
    }

    private fun progressGeometry(points: List<GeoPoint>): Pair<List<GeoPoint>, List<GeoPoint>> {
        if (points.isEmpty()) return emptyList<GeoPoint>() to emptyList()
        val segment = matchedSegmentIndex
        if (segment == null || points.size == 1 || matchedPoint == null) {
            return listOf(points.first()) to points
        }
        val completed = ArrayList<GeoPoint>(segment + 2)
        for (index in 0..segment) appendDistinct(completed, points[index])
        appendDistinct(completed, requireNotNull(matchedPoint))
        val remaining = ArrayList<GeoPoint>(points.size - segment + 1)
        appendDistinct(remaining, requireNotNull(matchedPoint))
        for (index in (segment + 1) until points.size) appendDistinct(remaining, points[index])
        return completed to remaining
    }

    private fun chooseProjection(projections: List<Projection>, accuracyMeters: Double): Projection {
        require(projections.isNotEmpty()) { "Route polyline must contain a projection" }
        val nearest = projections.minWithOrNull(
            compareBy<Projection>({ it.distanceMeters }, { abs(it.alongRouteMeters - progressMeters) }, { it.segmentIndex })
        ) ?: projections.first()
        if (matchedSegmentIndex == null) return nearest
        val backwardTolerance = max(
            LiveGuidanceDefaults.REVERSE_NOISE_TOLERANCE_METERS,
            accuracyMeters * 1.5
        )
        val plausible = projections.filter { it.alongRouteMeters >= progressMeters - backwardTolerance }
        if (nearest.alongRouteMeters < progressMeters - backwardTolerance && plausible.isNotEmpty()) {
            return plausible.minWithOrNull(
                compareBy<Projection>({ it.distanceMeters }, { abs(it.alongRouteMeters - progressMeters) }, { it.segmentIndex })
            ) ?: nearest
        }
        return nearest
    }

    private fun projectOntoRoute(points: List<GeoPoint>, point: GeoPoint): List<Projection> {
        if (points.size == 1) {
            return listOf(Projection(0, 0.0, 0.0, GeoMath.distanceMeters(points.first(), point), points.first()))
        }
        val projections = ArrayList<Projection>(points.lastIndex)
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            val local = LocalCoordinateFrame(point.lat, start)
            val startX = local.x(start)
            val startY = local.y(start)
            val endX = local.x(end)
            val endY = local.y(end)
            val pointX = local.x(point)
            val pointY = local.y(point)
            val dx = endX - startX
            val dy = endY - startY
            val denominator = dx * dx + dy * dy
            val fraction = if (denominator <= 0.0) 0.0 else {
                ((pointX - startX) * dx + (pointY - startY) * dy) / denominator
            }.coerceIn(0.0, 1.0)
            val projectedX = startX + dx * fraction
            val projectedY = startY + dy * fraction
            val projectedPoint = local.point(projectedX, projectedY)
            val along = cumulativePolylineMeters.getOrElse(index) { 0.0 } +
                GeoMath.distanceMeters(start, end) * fraction
            projections += Projection(
                segmentIndex = index,
                segmentFraction = fraction,
                alongRouteMeters = along,
                distanceMeters = hypot(pointX - projectedX, pointY - projectedY),
                point = projectedPoint
            )
        }
        return projections
    }

    private fun cumulativeDistances(points: List<GeoPoint>): List<Double> {
        if (points.isEmpty()) return emptyList()
        val cumulative = ArrayList<Double>(points.size)
        var distance = 0.0
        cumulative += distance
        for (index in 1 until points.size) {
            distance += GeoMath.distanceMeters(points[index - 1], points[index])
            cumulative += distance
        }
        return cumulative
    }

    private fun maneuverBoundaries(route: RouteResult): List<Double> {
        var distance = 0.0
        return route.maneuvers.map { maneuver ->
            distance += maneuver.distanceFromPreviousMeters.coerceAtLeast(0.0)
            distance.coerceIn(0.0, GeoMath.polylineDistanceMeters(route.polyline.points))
        }
    }

    private fun appendDistinct(points: MutableList<GeoPoint>, point: GeoPoint) {
        if (points.lastOrNull() != point) points += point
    }

    private data class Projection(
        val segmentIndex: Int,
        val segmentFraction: Double,
        val alongRouteMeters: Double,
        val distanceMeters: Double,
        val point: GeoPoint
    )

    private class LocalCoordinateFrame(referenceLat: Double, private val origin: GeoPoint) {
        private val latitudeScale = 6_371_008.8 * Math.PI / 180.0
        private val longitudeScale = latitudeScale * cos(Math.toRadians(referenceLat))

        fun x(point: GeoPoint): Double = (point.lon - origin.lon) * longitudeScale

        fun y(point: GeoPoint): Double = (point.lat - origin.lat) * latitudeScale

        fun point(x: Double, y: Double): GeoPoint = GeoPoint(
            origin.lat + y / latitudeScale,
            origin.lon + x / longitudeScale.coerceAtLeast(1e-9)
        )
    }

    private fun emptySnapshot() = LiveGuidanceSnapshot(
        progressMeters = 0.0,
        routeDistanceMeters = 0.0,
        remainingDistanceMeters = 0.0,
        remainingWalkingTimeSeconds = 0L,
        matchedPoint = null,
        crossTrackDistanceMeters = null,
        nextManeuverIndex = null,
        nextManeuver = null,
        distanceToNextManeuverMeters = null,
        completedPolyline = emptyList(),
        remainingPolyline = emptyList()
    )
}
