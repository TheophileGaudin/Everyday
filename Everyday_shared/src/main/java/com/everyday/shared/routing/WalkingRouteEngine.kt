package com.everyday.shared.routing

import java.net.SocketTimeoutException
import java.util.PriorityQueue
import java.util.Locale
import kotlin.math.abs

enum class WayDirection { FORWARD, BACKWARD }

/** OSM access rules used while turning raw ways into directed walking edges. */
object PedestrianAccessRules {
    private val baseHighways = setOf(
        "footway",
        "path",
        "pedestrian",
        "crossing",
        "steps",
        "residential",
        "living_street",
        "service",
        "unclassified"
    )
    private val explicitFootAllow = setOf("yes", "designated", "permissive", "official")
    private val forbidden = setOf(
        "no",
        "private",
        "restricted",
        "prohibited",
        "use_sidepath",
        "military",
        "emergency",
        "agricultural",
        "forestry",
        "delivery",
        "customers",
        "permit",
        "destination"
    )

    fun isWalkable(tags: Map<String, String>): Boolean {
        val highway = tags.value("highway") ?: return false
        val foot = tags.value("foot")
        val access = tags.value("access")
        if (foot in forbidden) return false
        if (access in forbidden && foot !in explicitFootAllow) return false
        if (highway in setOf("construction", "proposed", "platform", "corridor", "elevator", "motorway", "trunk")) {
            return false
        }
        if (highway in baseHighways) return true

        // Explicit pedestrian access can make a non-standard highway usable, but
        // an unqualified major road/cycleway is not assumed safe for walking.
        return foot in explicitFootAllow
    }

    fun directions(tags: Map<String, String>): Set<WayDirection> {
        var result = when (tags.value("oneway:foot") ?: tags.value("oneway:pedestrian")) {
            "yes", "true", "1" -> setOf(WayDirection.FORWARD)
            "-1", "reverse" -> setOf(WayDirection.BACKWARD)
            else -> setOf(WayDirection.FORWARD, WayDirection.BACKWARD)
        }

        // The ordinary `oneway` tag is normally a motor-vehicle restriction.
        // It deliberately does not affect pedestrians here.
        result = applyDirectionalAccess(result, tags.value("foot:forward"), WayDirection.FORWARD)
        result = applyDirectionalAccess(result, tags.value("foot:backward"), WayDirection.BACKWARD)
        return result
    }

    private fun applyDirectionalAccess(
        current: Set<WayDirection>,
        value: String?,
        direction: WayDirection
    ): Set<WayDirection> {
        if (value == null) return current
        return when {
            value in forbidden -> current - direction
            value in explicitFootAllow -> current + direction
            else -> current
        }
    }

    private fun Map<String, String>.value(key: String): String? =
        this[key]?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
}

class PedestrianGraphBuilder {
    fun build(data: OverpassRouteData): PedestrianGraph {
        val pointsById = data.nodes.associate { it.id to it.point }
        val edges = ArrayList<PedestrianGraphEdge>()

        for (way in data.ways.sortedBy { it.id }) {
            if (!PedestrianAccessRules.isWalkable(way.tags)) continue
            val highway = way.tags["highway"]?.trim()?.lowercase(Locale.US) ?: continue
            val name = way.tags["name"]?.trim()?.takeIf { it.isNotEmpty() }
            val directions = PedestrianAccessRules.directions(way.tags)
            val pairs = ArrayList<Pair<Long, Long>>()
            for (index in 0 until way.nodeIds.lastIndex) {
                val from = way.nodeIds[index]
                val to = way.nodeIds[index + 1]
                if (from != to) pairs += from to to
            }
            if (way.tags["area"]?.trim()?.lowercase(Locale.US) == "yes" &&
                way.nodeIds.firstOrNull() != way.nodeIds.lastOrNull()
            ) {
                val first = way.nodeIds.firstOrNull()
                val last = way.nodeIds.lastOrNull()
                if (first != null && last != null && first != last) pairs += last to first
            }

            pairs.forEachIndexed { sequenceIndex, (fromId, toId) ->
                val from = pointsById[fromId] ?: return@forEachIndexed
                val to = pointsById[toId] ?: return@forEachIndexed
                val distance = GeoMath.distanceMeters(from, to)
                if (!distance.isFinite() || distance <= 0.0) return@forEachIndexed
                if (WayDirection.FORWARD in directions) {
                    edges += PedestrianGraphEdge(
                        fromNodeId = fromId,
                        toNodeId = toId,
                        distanceMeters = distance,
                        wayId = way.id,
                        highway = highway,
                        name = name,
                        sequenceIndex = sequenceIndex
                    )
                }
                if (WayDirection.BACKWARD in directions) {
                    edges += PedestrianGraphEdge(
                        fromNodeId = toId,
                        toNodeId = fromId,
                        distanceMeters = distance,
                        wayId = way.id,
                        highway = highway,
                        name = name,
                        sequenceIndex = sequenceIndex
                    )
                }
            }
        }

        val usedNodeIds = edges.flatMapTo(HashSet()) { edge ->
            setOf(edge.fromNodeId, edge.toNodeId)
        }
        val nodes = usedNodeIds
            .mapNotNull { id -> pointsById[id]?.let { PedestrianGraphNode(id, it) } }
            .sortedBy { it.id }
        val sortedEdges = edges.sortedWith(
            compareBy<PedestrianGraphEdge>({ it.fromNodeId }, { it.toNodeId }, { it.wayId }, { it.sequenceIndex })
        )
        return PedestrianGraph(nodes, sortedEdges)
    }
}

class WalkingRouteEngine(
    private val graphBuilder: PedestrianGraphBuilder = PedestrianGraphBuilder()
) {
    fun calculate(request: RouteRequest, data: OverpassRouteData): RouteCalculationResult =
        calculate(request, graphBuilder.build(data))

    fun calculate(request: RouteRequest, graph: PedestrianGraph): RouteCalculationResult {
        validateRequest(request)?.let { return RouteCalculationResult.Failure(it, attempts = 0) }
        val originSnap = nearestNode(request.origin, graph)
        if (originSnap == null || originSnap.distanceMeters > request.maxSnapDistanceMeters + DISTANCE_EPSILON) {
            return RouteCalculationResult.Failure(
                RouteFailure.NoNearbyGraphNode(
                    RouteEndpoint.ORIGIN,
                    originSnap?.distanceMeters,
                    request.maxSnapDistanceMeters
                )
            )
        }
        val destinationSnap = nearestNode(request.destination, graph)
        if (destinationSnap == null || destinationSnap.distanceMeters > request.maxSnapDistanceMeters + DISTANCE_EPSILON) {
            return RouteCalculationResult.Failure(
                RouteFailure.NoNearbyGraphNode(
                    RouteEndpoint.DESTINATION,
                    destinationSnap?.distanceMeters,
                    request.maxSnapDistanceMeters
                )
            )
        }

        val path = aStar(originSnap.node.id, destinationSnap.node.id, graph)
            ?: return RouteCalculationResult.Failure(
                RouteFailure.DisconnectedGraph(originSnap.node.id, destinationSnap.node.id)
            )
        val pointsById = graph.nodes.associateBy { it.id }
        val pathPoints = path.nodeIds.mapNotNull { pointsById[it]?.point }
        if (pathPoints.isEmpty()) {
            return RouteCalculationResult.Failure(
                RouteFailure.MalformedResponse("The graph path did not contain node coordinates")
            )
        }

        val polylinePoints = ArrayList<GeoPoint>(pathPoints.size + 2)
        appendDistinct(polylinePoints, request.origin)
        pathPoints.forEach { appendDistinct(polylinePoints, it) }
        appendDistinct(polylinePoints, request.destination)
        val polyline = RoutePolyline(polylinePoints)
        val maneuvers = buildManeuvers(
            request = request,
            graphPath = path,
            graph = graph,
            destination = request.destination
        )
        val totalDistance = polyline.lengthMeters
        val route = RouteResult(
            origin = request.origin,
            destination = request.destination,
            snappedOriginNodeId = originSnap.node.id,
            snappedDestinationNodeId = destinationSnap.node.id,
            polyline = polyline,
            totalDistanceMeters = totalDistance,
            estimatedWalkingTimeSeconds = kotlin.math.ceil(totalDistance / request.walkingSpeedMps).toLong(),
            graphNodeIds = path.nodeIds,
            graphEdges = path.edges,
            maneuvers = maneuvers
        )
        return RouteCalculationResult.Success(route)
    }

    fun validateRequest(request: RouteRequest): RouteFailure? {
        if (request.mode != RouteMode.WALKING) return RouteFailure.UnsupportedMode(request.mode)
        val distance = request.straightLineDistanceMeters
        return if (distance > request.maxStraightLineDistanceMeters + DISTANCE_EPSILON) {
            RouteFailure.OverLimit(distance, request.maxStraightLineDistanceMeters)
        } else {
            null
        }
    }

    private data class SnapCandidate(
        val node: PedestrianGraphNode,
        val distanceMeters: Double
    )

    private data class SearchEntry(
        val nodeId: Long,
        val gScore: Double,
        val fScore: Double
    )

    private data class GraphPath(
        val nodeIds: List<Long>,
        val edges: List<PedestrianGraphEdge>
    )

    private fun nearestNode(
        point: GeoPoint,
        graph: PedestrianGraph
    ): SnapCandidate? = graph.nodes.asSequence()
            .map { SnapCandidate(it, GeoMath.distanceMeters(point, it.point)) }
            .filter { it.distanceMeters.isFinite() }
            .sortedWith(compareBy<SnapCandidate>({ it.distanceMeters }, { it.node.id }))
            .firstOrNull()

    private fun aStar(startId: Long, goalId: Long, graph: PedestrianGraph): GraphPath? {
        if (startId == goalId) return GraphPath(listOf(startId), emptyList())
        val nodesById = graph.nodes.associateBy { it.id }
        val adjacency = graph.edges.groupBy { it.fromNodeId }.mapValues { (_, edges) ->
            edges.sortedWith(compareBy<PedestrianGraphEdge>({ it.toNodeId }, { it.wayId }, { it.sequenceIndex }))
        }
        val scores = HashMap<Long, Double>()
        val predecessor = HashMap<Long, PedestrianGraphEdge>()
        val open = PriorityQueue(
            compareBy<SearchEntry>({ it.fScore }, { it.gScore }, { it.nodeId })
        )
        scores[startId] = 0.0
        val goalPoint = nodesById[goalId]?.point ?: return null
        val startPoint = nodesById[startId]?.point ?: return null
        open += SearchEntry(startId, 0.0, GeoMath.distanceMeters(startPoint, goalPoint))
        var bestGoalScore: Double? = null

        while (open.isNotEmpty()) {
            val current = open.poll() ?: break
            val knownScore = scores[current.nodeId] ?: continue
            if (current.gScore > knownScore + DISTANCE_EPSILON) continue
            val bestGoal = bestGoalScore
            if (bestGoal != null && current.fScore > bestGoal + DISTANCE_EPSILON) break
            if (current.nodeId == goalId) {
                bestGoalScore = minOf(bestGoalScore ?: Double.POSITIVE_INFINITY, current.gScore)
                continue
            }
            for (edge in adjacency[current.nodeId].orEmpty()) {
                val tentative = current.gScore + edge.distanceMeters
                val previous = scores[edge.toNodeId]
                val equalAndPreferred = previous != null &&
                    abs(tentative - previous) <= DISTANCE_EPSILON &&
                    isPreferredPredecessor(edge, predecessor[edge.toNodeId])
                if (previous == null || tentative < previous - DISTANCE_EPSILON || equalAndPreferred) {
                    scores[edge.toNodeId] = tentative
                    predecessor[edge.toNodeId] = edge
                    val point = nodesById[edge.toNodeId]?.point ?: continue
                    val heuristic = GeoMath.distanceMeters(point, goalPoint)
                    open += SearchEntry(edge.toNodeId, tentative, tentative + heuristic)
                }
            }
        }

        if (goalId !in predecessor) return null
        val reversedEdges = ArrayList<PedestrianGraphEdge>()
        val reversedNodes = ArrayList<Long>()
        var cursor = goalId
        reversedNodes += cursor
        while (cursor != startId) {
            val edge = predecessor[cursor] ?: return null
            reversedEdges += edge
            cursor = edge.fromNodeId
            reversedNodes += cursor
        }
        reversedEdges.reverse()
        reversedNodes.reverse()
        return GraphPath(reversedNodes, reversedEdges)
    }

    private fun isPreferredPredecessor(
        candidate: PedestrianGraphEdge,
        current: PedestrianGraphEdge?
    ): Boolean {
        if (current == null) return true
        return compareValuesBy(
            candidate,
            current,
            { it.fromNodeId },
            { it.wayId },
            { it.sequenceIndex },
            { it.toNodeId }
        ) < 0
    }

    private fun buildManeuvers(
        request: RouteRequest,
        graphPath: GraphPath,
        graph: PedestrianGraph,
        destination: GeoPoint
    ): List<RouteManeuver> {
        if (graphPath.edges.isEmpty()) {
            val graphPoint = graph.node(graphPath.nodeIds.firstOrNull() ?: return emptyList())?.point
            val connectorDistance = graphPoint?.let {
                GeoMath.distanceMeters(request.origin, it) + GeoMath.distanceMeters(it, destination)
            } ?: 0.0
            return listOf(
                RouteManeuver(
                    type = RouteManeuverType.ARRIVE,
                    point = destination,
                    roadName = null,
                    wayId = null,
                    distanceFromPreviousMeters = connectorDistance,
                    headingBeforeDeg = null,
                    headingAfterDeg = null,
                    instruction = "You are at the destination"
                )
            )
        }
        val pointsById = graph.nodes.associateBy { it.id }
        val first = graphPath.edges.first()
        val firstHeading = edgeBearing(first, pointsById)
        val maneuvers = ArrayList<RouteManeuver>()
        maneuvers += RouteManeuver(
            type = RouteManeuverType.START,
            point = request.origin,
            roadName = first.name,
            wayId = first.wayId,
            distanceFromPreviousMeters = pointsById[first.fromNodeId]?.point?.let {
                GeoMath.distanceMeters(request.origin, it)
            } ?: 0.0,
            headingBeforeDeg = null,
            headingAfterDeg = firstHeading,
            instruction = startInstruction(first.name)
        )

        var distanceSincePrevious = pointsById[first.fromNodeId]?.point?.let {
            GeoMath.distanceMeters(request.origin, it)
        } ?: 0.0
        for (index in 1 until graphPath.edges.size) {
            val previous = graphPath.edges[index - 1]
            val current = graphPath.edges[index]
            distanceSincePrevious += previous.distanceMeters
            val previousHeading = edgeBearing(previous, pointsById)
            val currentHeading = edgeBearing(current, pointsById)
            val type = maneuverType(previous, current, previousHeading, currentHeading)
            if (type != null) {
                val point = pointsById[current.fromNodeId]?.point ?: continue
                maneuvers += RouteManeuver(
                    type = type,
                    point = point,
                    roadName = current.name,
                    wayId = current.wayId,
                    distanceFromPreviousMeters = distanceSincePrevious,
                    headingBeforeDeg = previousHeading,
                    headingAfterDeg = currentHeading,
                    instruction = instruction(type, current.name)
                )
                distanceSincePrevious = 0.0
            }
        }
        distanceSincePrevious += graphPath.edges.last().distanceMeters
        pointsById[graphPath.edges.last().toNodeId]?.point?.let {
            distanceSincePrevious += GeoMath.distanceMeters(it, destination)
        }
        maneuvers += RouteManeuver(
            type = RouteManeuverType.ARRIVE,
            point = destination,
            roadName = graphPath.edges.last().name,
            wayId = graphPath.edges.last().wayId,
            distanceFromPreviousMeters = distanceSincePrevious,
            headingBeforeDeg = edgeBearing(graphPath.edges.last(), pointsById),
            headingAfterDeg = null,
            instruction = "Arrive at your destination"
        )
        return maneuvers
    }

    private fun maneuverType(
        previous: PedestrianGraphEdge,
        current: PedestrianGraphEdge,
        previousHeading: Double,
        currentHeading: Double
    ): RouteManeuverType? {
        val previousHighway = previous.highway.lowercase(Locale.US)
        val currentHighway = current.highway.lowercase(Locale.US)
        if (currentHighway == "crossing" || previousHighway == "crossing") return RouteManeuverType.CROSSING
        if (currentHighway == "steps" || previousHighway == "steps") return RouteManeuverType.STAIRS

        val turn = GeoMath.signedTurnDegrees(previousHeading, currentHeading)
        val nameChanged = previous.name.orEmpty().trim() != current.name.orEmpty().trim()
        if (abs(turn) < SIGNIFICANT_TURN_DEGREES) {
            return if (nameChanged) RouteManeuverType.CONTINUE else null
        }
        if (abs(turn) >= U_TURN_DEGREES) return RouteManeuverType.U_TURN
        return when {
            turn < 0.0 && abs(turn) < SHARP_TURN_DEGREES -> RouteManeuverType.LEFT
            turn < 0.0 -> RouteManeuverType.SHARP_LEFT
            turn > 0.0 && abs(turn) < SHARP_TURN_DEGREES -> RouteManeuverType.RIGHT
            else -> RouteManeuverType.SHARP_RIGHT
        }.let { turnType ->
            if (abs(turn) < REGULAR_TURN_DEGREES) {
                when (turnType) {
                    RouteManeuverType.LEFT, RouteManeuverType.SHARP_LEFT -> RouteManeuverType.SLIGHT_LEFT
                    RouteManeuverType.RIGHT, RouteManeuverType.SHARP_RIGHT -> RouteManeuverType.SLIGHT_RIGHT
                    else -> turnType
                }
            } else turnType
        }
    }

    private fun edgeBearing(edge: PedestrianGraphEdge, pointsById: Map<Long, PedestrianGraphNode>): Double {
        val from = pointsById[edge.fromNodeId]?.point ?: return 0.0
        val to = pointsById[edge.toNodeId]?.point ?: return 0.0
        return GeoMath.bearingDegrees(from, to)
    }

    private fun startInstruction(name: String?): String =
        name?.let { "Start walking on $it" } ?: "Start walking"

    private fun instruction(type: RouteManeuverType, name: String?): String {
        val suffix = name?.let { " onto $it" }.orEmpty()
        return when (type) {
            RouteManeuverType.CONTINUE -> "Continue walking$suffix"
            RouteManeuverType.SLIGHT_LEFT -> "Bear left$suffix"
            RouteManeuverType.LEFT -> "Turn left$suffix"
            RouteManeuverType.SHARP_LEFT -> "Turn sharply left$suffix"
            RouteManeuverType.U_TURN -> "Make a U-turn$suffix"
            RouteManeuverType.SLIGHT_RIGHT -> "Bear right$suffix"
            RouteManeuverType.RIGHT -> "Turn right$suffix"
            RouteManeuverType.SHARP_RIGHT -> "Turn sharply right$suffix"
            RouteManeuverType.CROSSING -> "Use the crossing"
            RouteManeuverType.STAIRS -> "Take the stairs"
            else -> "Continue walking$suffix"
        }
    }

    private fun appendDistinct(points: MutableList<GeoPoint>, point: GeoPoint) {
        if (points.lastOrNull() != point) points += point
    }

    companion object {
        private const val DISTANCE_EPSILON = 1e-6
        private const val SIGNIFICANT_TURN_DEGREES = 25.0
        private const val REGULAR_TURN_DEGREES = 45.0
        private const val SHARP_TURN_DEGREES = 135.0
        private const val U_TURN_DEGREES = 160.0
    }
}

/** Owns the bounded 400 m -> 800 m graph-area retry policy. */
class WalkingRouteRepository(
    private val graphProvider: RouteGraphProvider,
    private val engine: WalkingRouteEngine = WalkingRouteEngine()
) {
    fun calculate(request: RouteRequest): RouteCalculationResult {
        engine.validateRequest(request)?.let { failure ->
            return RouteCalculationResult.Failure(failure, attempts = 0, queryBufferMeters = null)
        }

        val buffers = intArrayOf(
            WalkingRouteDefaults.INITIAL_QUERY_BUFFER_METERS,
            WalkingRouteDefaults.RETRY_QUERY_BUFFER_METERS
        )
        for (index in buffers.indices) {
            val buffer = buffers[index]
            val fetched = try {
                graphProvider.fetch(request, buffer)
            } catch (t: Throwable) {
                RouteGraphFetchResult.Failure(routeFailureFromThrowable(t))
            }
            when (fetched) {
                is RouteGraphFetchResult.Failure -> {
                    return RouteCalculationResult.Failure(
                        failure = fetched.failure,
                        attempts = index + 1,
                        queryBufferMeters = buffer
                    )
                }

                is RouteGraphFetchResult.Success -> {
                    when (val calculated = engine.calculate(request, fetched.data)) {
                        is RouteCalculationResult.Success -> {
                            return calculated.copy(attempts = index + 1, queryBufferMeters = buffer)
                        }

                        is RouteCalculationResult.Failure -> {
                            val retryable = calculated.failure is RouteFailure.NoNearbyGraphNode ||
                                calculated.failure is RouteFailure.DisconnectedGraph
                            if (!retryable || index == buffers.lastIndex) {
                                return calculated.copy(attempts = index + 1, queryBufferMeters = buffer)
                            }
                        }
                    }
                }
            }
        }
        return RouteCalculationResult.Failure(
            RouteFailure.NetworkFailure("Route calculation did not produce a result"),
            attempts = buffers.size,
            queryBufferMeters = buffers.last()
        )
    }

    private fun routeFailureFromThrowable(t: Throwable): RouteFailure = when (t) {
        is SocketTimeoutException -> RouteFailure.Timeout(t.message ?: "Route graph request timed out")
        else -> RouteFailure.NetworkFailure(t.message ?: t::class.java.simpleName)
    }
}
