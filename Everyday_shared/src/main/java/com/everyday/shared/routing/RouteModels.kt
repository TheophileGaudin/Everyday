package com.everyday.shared.routing

/** A latitude/longitude point used by the routing core. */
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { "Latitude must be finite and between -90 and 90" }
        require(lon.isFinite() && lon in -180.0..180.0) { "Longitude must be finite and between -180 and 180" }
    }
}

/** Route modes understood by the shared request model; only WALKING is implemented here. */
enum class RouteMode { WALKING, CYCLING, DRIVING, TRANSIT }

object WalkingRouteDefaults {
    /** Mapbox walking supports routes up to 1,000 km; this is a protocol safety ceiling, not a nearby-search filter. */
    const val MAX_STRAIGHT_LINE_DISTANCE_METERS = 1_000_000.0
    const val DEFAULT_MAX_SNAP_DISTANCE_METERS = 120.0
    const val DEFAULT_WALKING_SPEED_MPS = 1.4
    const val INITIAL_QUERY_BUFFER_METERS = 400
    const val RETRY_QUERY_BUFFER_METERS = 800
}

data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val mode: RouteMode = RouteMode.WALKING,
    val maxStraightLineDistanceMeters: Double = WalkingRouteDefaults.MAX_STRAIGHT_LINE_DISTANCE_METERS,
    val maxSnapDistanceMeters: Double = WalkingRouteDefaults.DEFAULT_MAX_SNAP_DISTANCE_METERS,
    val walkingSpeedMps: Double = WalkingRouteDefaults.DEFAULT_WALKING_SPEED_MPS
) {
    init {
        require(maxStraightLineDistanceMeters.isFinite() &&
            maxStraightLineDistanceMeters > 0.0 &&
            maxStraightLineDistanceMeters <= WalkingRouteDefaults.MAX_STRAIGHT_LINE_DISTANCE_METERS
        ) { "The straight-line route limit must be between 0 and 1,000,000 metres" }
        require(maxSnapDistanceMeters.isFinite() && maxSnapDistanceMeters > 0.0) {
            "The graph snap distance must be positive"
        }
        require(walkingSpeedMps.isFinite() && walkingSpeedMps > 0.0) {
            "Walking speed must be positive"
        }
    }

    val straightLineDistanceMeters: Double
        get() = GeoMath.distanceMeters(origin, destination)
}

data class OverpassNode(val id: Long, val point: GeoPoint)

data class OverpassWay(
    val id: Long,
    val nodeIds: List<Long>,
    val tags: Map<String, String>
)

/** Raw, node-referenced Overpass data used to build a walkable graph. */
data class OverpassRouteData(
    val nodes: List<OverpassNode>,
    val ways: List<OverpassWay>
)

data class PedestrianGraphNode(val id: Long, val point: GeoPoint)

data class PedestrianGraphEdge(
    val fromNodeId: Long,
    val toNodeId: Long,
    val distanceMeters: Double,
    val wayId: Long,
    val highway: String,
    val name: String?,
    val sequenceIndex: Int
)

/** Directed graph containing only public, pedestrian-usable way segments. */
data class PedestrianGraph(
    val nodes: List<PedestrianGraphNode>,
    val edges: List<PedestrianGraphEdge>
) {
    private val nodesById: Map<Long, PedestrianGraphNode> by lazy { nodes.associateBy { it.id } }

    fun node(id: Long): PedestrianGraphNode? = nodesById[id]

    fun outgoing(nodeId: Long): List<PedestrianGraphEdge> =
        edges.filter { it.fromNodeId == nodeId }
}

data class RoutePolyline(val points: List<GeoPoint>) {
    init {
        require(points.isNotEmpty()) { "A route polyline must contain at least one point" }
    }

    val lengthMeters: Double
        get() = GeoMath.polylineDistanceMeters(points)
}

enum class RouteManeuverType {
    START,
    CONTINUE,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    U_TURN,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    CROSSING,
    STAIRS,
    ARRIVE
}

/** A compact instruction model; the full route geometry remains in [RoutePolyline]. */
data class RouteManeuver(
    val type: RouteManeuverType,
    val point: GeoPoint,
    val roadName: String?,
    val wayId: Long?,
    val distanceFromPreviousMeters: Double,
    val headingBeforeDeg: Double?,
    val headingAfterDeg: Double?,
    val instruction: String
)

/** Alias kept for callers that refer to maneuvers as turn-by-turn steps. */
typealias RouteStep = RouteManeuver

data class RouteResult(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val snappedOriginNodeId: Long,
    val snappedDestinationNodeId: Long,
    val polyline: RoutePolyline,
    val totalDistanceMeters: Double,
    val estimatedWalkingTimeSeconds: Long,
    val graphNodeIds: List<Long>,
    val graphEdges: List<PedestrianGraphEdge>,
    val maneuvers: List<RouteManeuver>
) {
    val distanceMeters: Double
        get() = totalDistanceMeters

    val walkingTimeSeconds: Long
        get() = estimatedWalkingTimeSeconds

    val steps: List<RouteStep>
        get() = maneuvers
}

enum class RouteEndpoint { ORIGIN, DESTINATION }

enum class RouteFailureCode {
    OVER_LIMIT,
    NO_NEARBY_GRAPH_NODE,
    DISCONNECTED_GRAPH,
    MALFORMED_RESPONSE,
    NETWORK_FAILURE,
    TIMEOUT,
    AUTHORIZATION_FAILURE,
    UNSUPPORTED_MODE,
    INVALID_REQUEST
}

sealed class RouteFailure(open val code: RouteFailureCode) {
    data class OverLimit(
        val straightLineDistanceMeters: Double,
        val maximumDistanceMeters: Double = WalkingRouteDefaults.MAX_STRAIGHT_LINE_DISTANCE_METERS
    ) : RouteFailure(RouteFailureCode.OVER_LIMIT)

    data class NoNearbyGraphNode(
        val endpoint: RouteEndpoint,
        val nearestDistanceMeters: Double?,
        val maxSnapDistanceMeters: Double
    ) : RouteFailure(RouteFailureCode.NO_NEARBY_GRAPH_NODE)

    data class DisconnectedGraph(
        val originNodeId: Long,
        val destinationNodeId: Long
    ) : RouteFailure(RouteFailureCode.DISCONNECTED_GRAPH)

    data class MalformedResponse(val reason: String) : RouteFailure(RouteFailureCode.MALFORMED_RESPONSE)

    data class NetworkFailure(val message: String) : RouteFailure(RouteFailureCode.NETWORK_FAILURE)

    data class Timeout(val message: String) : RouteFailure(RouteFailureCode.TIMEOUT)

    data class AuthorizationFailure(
        val statusCode: Int,
        val message: String
    ) : RouteFailure(RouteFailureCode.AUTHORIZATION_FAILURE)

    data class UnsupportedMode(val mode: RouteMode) : RouteFailure(RouteFailureCode.UNSUPPORTED_MODE)

    data class InvalidRequest(val reason: String) : RouteFailure(RouteFailureCode.INVALID_REQUEST)
}

sealed class RouteCalculationResult {
    data class Success(
        val route: RouteResult,
        val attempts: Int = 1,
        val queryBufferMeters: Int? = null
    ) : RouteCalculationResult()

    data class Failure(
        val failure: RouteFailure,
        val attempts: Int = 1,
        val queryBufferMeters: Int? = null
    ) : RouteCalculationResult()
}
