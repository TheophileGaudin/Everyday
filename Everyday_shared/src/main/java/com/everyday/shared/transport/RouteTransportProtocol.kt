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

/** The versioned request payload used for phone-first walking-route calculation. */
data class RouteTransportRequest(
    val requestId: String,
    val origin: GeoPoint,
    val destinationAddress: String? = null,
    val destination: GeoPoint? = null,
    val destinationDisplayName: String? = null,
    val mode: RouteMode = RouteMode.WALKING,
    val requestedAtMs: Long,
    val maxStraightLineDistanceMeters: Double = com.everyday.shared.routing.WalkingRouteDefaults.MAX_STRAIGHT_LINE_DISTANCE_METERS,
    val maxSnapDistanceMeters: Double = com.everyday.shared.routing.WalkingRouteDefaults.DEFAULT_MAX_SNAP_DISTANCE_METERS,
    val walkingSpeedMps: Double = com.everyday.shared.routing.WalkingRouteDefaults.DEFAULT_WALKING_SPEED_MPS
) {
    init {
        require(requestId.length in 1..RouteTransportProtocol.MAX_REQUEST_ID_LENGTH)
        require(destinationAddress?.isNotBlank() == true || destination != null) {
            "A route request needs an address or resolved destination"
        }
        require(destinationAddress == null || destinationAddress.length <= RouteTransportProtocol.MAX_ADDRESS_LENGTH)
        require(destinationDisplayName == null || destinationDisplayName.length <= RouteTransportProtocol.MAX_DISPLAY_NAME_LENGTH)
    }
}

/** Display data is returned separately because the routing core only knows coordinates. */
data class RouteDestinationData(
    val query: String,
    val displayName: String,
    val point: GeoPoint
)

data class RouteTransportResult(
    val requestId: String,
    val destination: RouteDestinationData,
    val route: RouteResult,
    val calculatedAtMs: Long
) {
    init {
        require(requestId.length in 1..RouteTransportProtocol.MAX_REQUEST_ID_LENGTH)
        require(destination.query.length <= RouteTransportProtocol.MAX_ADDRESS_LENGTH)
        require(destination.displayName.length in 1..RouteTransportProtocol.MAX_DISPLAY_NAME_LENGTH)
    }
}

enum class RouteTransportErrorCode(val wireName: String) {
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED_MODE("unsupported_mode"),
    UNSUPPORTED_PEER("unsupported_peer"),
    GEOCODING_NO_RESULTS("geocoding_no_results"),
    GEOCODING_AMBIGUOUS("geocoding_ambiguous"),
    GEOCODING_NETWORK("geocoding_network"),
    GEOCODING_MALFORMED("geocoding_malformed"),
    ROUTE_FAILURE("route_failure"),
    CANCELLED("cancelled"),
    INTERNAL("internal");

    companion object {
        fun fromWireName(value: String): RouteTransportErrorCode? =
            values().firstOrNull { it.wireName == value }
    }
}

/** Structured errors are deliberately data-only so the phone never updates glasses UI. */
data class RouteTransportError(
    val requestId: String,
    val code: RouteTransportErrorCode,
    val message: String,
    val retryable: Boolean,
    val timestampMs: Long,
    val routeFailureCode: RouteFailureCode? = null,
    val choices: List<RouteDestinationData> = emptyList()
) {
    init {
        require(requestId.length in 1..RouteTransportProtocol.MAX_REQUEST_ID_LENGTH)
        require(message.length <= RouteTransportProtocol.MAX_ERROR_LENGTH)
        require(choices.size <= RouteTransportProtocol.MAX_GEOCODING_CHOICES)
    }
}

data class RouteTransportCancellation(
    val requestId: String,
    val cancelledAtMs: Long,
    val reason: String? = null
) {
    init {
        require(requestId.length in 1..RouteTransportProtocol.MAX_REQUEST_ID_LENGTH)
        require(reason == null || reason.length <= RouteTransportProtocol.MAX_CANCEL_REASON_LENGTH)
    }
}

/**
 * JSON codec for the route family.  It is separate from the envelope codec so the bounds and
 * nested route validation remain easy to audit.  Unknown route kinds are still handled by
 * [TransportProtocol] as unknown messages, allowing older peers to continue other traffic.
 */
object RouteTransportProtocol {
    const val VERSION = 1
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_ADDRESS_LENGTH = 512
    const val MAX_DISPLAY_NAME_LENGTH = 256
    const val MAX_ERROR_LENGTH = 512
    const val MAX_CANCEL_REASON_LENGTH = 128
    const val MAX_GEOCODING_CHOICES = 3
    const val MAX_POLYLINE_POINTS = 12_000
    const val MAX_GRAPH_NODES = 20_000
    const val MAX_GRAPH_EDGES = 20_000
    const val MAX_MANEUVER_COUNT = 4_000
    const val MAX_INSTRUCTION_LENGTH = 256

    fun requestToJson(request: RouteTransportRequest): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("requestId", request.requestId)
        .put("origin", request.origin.toJson())
        .putNullable("destinationAddress", request.destinationAddress)
        .putNullable("destination", request.destination?.toJson())
        .putNullable("destinationDisplayName", request.destinationDisplayName)
        .put("mode", request.mode.name)
        .put("requestedAtMs", request.requestedAtMs)
        .put("maxStraightLineDistanceMeters", request.maxStraightLineDistanceMeters)
        .put("maxSnapDistanceMeters", request.maxSnapDistanceMeters)
        .put("walkingSpeedMps", request.walkingSpeedMps)

    fun requestFromJson(payload: JSONObject): RouteTransportRequest {
        requireVersion(payload)
        return RouteTransportRequest(
            requestId = payload.requiredString("requestId", MAX_REQUEST_ID_LENGTH),
            origin = payload.requiredPoint("origin"),
            destinationAddress = payload.optionalString("destinationAddress", MAX_ADDRESS_LENGTH),
            destination = payload.optionalPoint("destination"),
            destinationDisplayName = payload.optionalString("destinationDisplayName", MAX_DISPLAY_NAME_LENGTH),
            mode = payload.requiredMode("mode"),
            requestedAtMs = payload.requiredLong("requestedAtMs"),
            maxStraightLineDistanceMeters = payload.requiredRouteParameter(
                "maxStraightLineDistanceMeters", 0.0, com.everyday.shared.routing.WalkingRouteDefaults.MAX_STRAIGHT_LINE_DISTANCE_METERS
            ),
            maxSnapDistanceMeters = payload.requiredRouteParameter("maxSnapDistanceMeters", 0.0, MAX_SNAP_DISTANCE_METERS),
            walkingSpeedMps = payload.requiredRouteParameter("walkingSpeedMps", 0.01, MAX_WALKING_SPEED_MPS)
        )
    }

    fun resultToJson(result: RouteTransportResult): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("requestId", result.requestId)
        .put("destination", result.destination.toJson())
        .put("route", result.route.toJson())
        .put("calculatedAtMs", result.calculatedAtMs)

    fun resultFromJson(payload: JSONObject): RouteTransportResult {
        requireVersion(payload)
        return RouteTransportResult(
            requestId = payload.requiredString("requestId", MAX_REQUEST_ID_LENGTH),
            destination = payload.requiredDestination("destination"),
            route = payload.requiredRoute("route"),
            calculatedAtMs = payload.requiredLong("calculatedAtMs")
        )
    }

    fun errorToJson(error: RouteTransportError): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("requestId", error.requestId)
        .put("code", error.code.wireName)
        .put("message", error.message)
        .put("retryable", error.retryable)
        .put("timestampMs", error.timestampMs)
        .putNullable("routeFailureCode", error.routeFailureCode?.name)
        .put("choices", JSONArray().apply { error.choices.forEach { put(it.toJson()) } })

    fun errorFromJson(payload: JSONObject): RouteTransportError {
        requireVersion(payload)
        val choicesArray = payload.optJSONArray("choices")
            ?: throw InvalidPayload("missing choices")
        if (choicesArray.length() > MAX_GEOCODING_CHOICES) {
            throw InvalidPayload("too many route choices")
        }
        val choices = (0 until choicesArray.length()).map { index ->
            val choice = choicesArray.optJSONObject(index)
                ?: throw InvalidPayload("invalid route choice $index")
            choice.toDestinationData()
        }
        val routeFailureCode = payload.optionalString("routeFailureCode", 64)?.let {
            runCatching { RouteFailureCode.valueOf(it) }.getOrNull()
                ?: throw InvalidPayload("invalid route failure code")
        }
        return RouteTransportError(
            requestId = payload.requiredString("requestId", MAX_REQUEST_ID_LENGTH),
            code = RouteTransportErrorCode.fromWireName(payload.requiredString("code", 64))
                ?: throw InvalidPayload("invalid route error code"),
            message = payload.requiredStringAllowEmpty("message", MAX_ERROR_LENGTH),
            retryable = payload.requiredBoolean("retryable"),
            timestampMs = payload.requiredLong("timestampMs"),
            routeFailureCode = routeFailureCode,
            choices = choices
        )
    }

    fun cancellationToJson(cancellation: RouteTransportCancellation): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("requestId", cancellation.requestId)
        .put("cancelledAtMs", cancellation.cancelledAtMs)
        .putNullable("reason", cancellation.reason)

    fun cancellationFromJson(payload: JSONObject): RouteTransportCancellation {
        requireVersion(payload)
        return RouteTransportCancellation(
            requestId = payload.requiredString("requestId", MAX_REQUEST_ID_LENGTH),
            cancelledAtMs = payload.requiredLong("cancelledAtMs"),
            reason = payload.optionalStringAllowEmpty("reason", MAX_CANCEL_REASON_LENGTH)
        )
    }

    private fun requireVersion(payload: JSONObject) {
        val version = payload.opt("version") as? Number
            ?: throw InvalidPayload("missing route protocol version")
        if (version.toInt().toDouble() != version.toDouble() || version.toInt() != VERSION) {
            throw InvalidPayload("unsupported route protocol version")
        }
    }

    private fun RouteDestinationData.toJson(): JSONObject = JSONObject()
        .put("query", query)
        .put("displayName", displayName)
        .put("point", point.toJson())

    private fun JSONObject.requiredDestination(name: String): RouteDestinationData {
        val value = optJSONObject(name) ?: throw InvalidPayload("missing $name")
        return value.toDestinationData()
    }

    private fun JSONObject.toDestinationData(): RouteDestinationData {
        return RouteDestinationData(
            query = requiredStringAllowEmpty("query", MAX_ADDRESS_LENGTH),
            displayName = requiredString("displayName", MAX_DISPLAY_NAME_LENGTH),
            point = requiredPoint("point")
        )
    }

    private fun JSONObject.requiredPoint(name: String): GeoPoint {
        val value = optJSONObject(name) ?: throw InvalidPayload("missing $name")
        return value.toPointOrInvalid(name)
    }

    private fun JSONObject.toPoint(): GeoPoint = toPointOrInvalid("point")

    private fun JSONObject.toPointOrInvalid(name: String): GeoPoint {
        val point = runCatching {
            GeoPoint(
                requiredFiniteDouble("lat"),
                requiredFiniteDouble("lon")
            )
        }.getOrElse { throw InvalidPayload("invalid $name") }
        return point
    }

    private fun GeoPoint.toJson(): JSONObject = JSONObject().put("lat", lat).put("lon", lon)

    private fun RouteResult.toJson(): JSONObject = JSONObject()
        .put("origin", origin.toJson())
        .put("destination", destination.toJson())
        .put("snappedOriginNodeId", snappedOriginNodeId)
        .put("snappedDestinationNodeId", snappedDestinationNodeId)
        .put("polyline", JSONArray().apply { polyline.points.forEach { put(it.toJson()) } })
        .put("totalDistanceMeters", totalDistanceMeters)
        .put("estimatedWalkingTimeSeconds", estimatedWalkingTimeSeconds)
        .put("graphNodeIds", JSONArray().apply { graphNodeIds.forEach { put(it) } })
        .put("graphEdges", JSONArray().apply { graphEdges.forEach { put(it.toJson()) } })
        .put("maneuvers", JSONArray().apply { maneuvers.forEach { put(it.toJson()) } })

    private fun JSONObject.requiredRoute(name: String): RouteResult {
        val value = optJSONObject(name) ?: throw InvalidPayload("missing $name")
        val polylineArray = value.optJSONArray("polyline")
            ?: throw InvalidPayload("missing route polyline")
        if (polylineArray.length() !in 1..MAX_POLYLINE_POINTS) {
            throw InvalidPayload("invalid route polyline size")
        }
        val polyline = (0 until polylineArray.length()).map { index ->
            val pointObject = polylineArray.optJSONObject(index)
                ?: throw InvalidPayload("invalid route polyline point $index")
            pointObject.toPoint()
        }
        val graphNodeIds = value.requiredLongArray("graphNodeIds", MAX_GRAPH_NODES)
        val edgeArray = value.optJSONArray("graphEdges")
            ?: throw InvalidPayload("missing route graph edges")
        if (edgeArray.length() > MAX_GRAPH_EDGES) throw InvalidPayload("too many route graph edges")
        val edges = (0 until edgeArray.length()).map { index ->
            val edge = edgeArray.optJSONObject(index) ?: throw InvalidPayload("invalid graph edge $index")
            edge.toGraphEdge()
        }
        val maneuverArray = value.optJSONArray("maneuvers")
            ?: throw InvalidPayload("missing route maneuvers")
        if (maneuverArray.length() > MAX_MANEUVER_COUNT) throw InvalidPayload("too many route maneuvers")
        val maneuvers = (0 until maneuverArray.length()).map { index ->
            val maneuver = maneuverArray.optJSONObject(index)
                ?: throw InvalidPayload("invalid route maneuver $index")
            maneuver.toManeuver()
        }
        val totalDistance = value.requiredFiniteDouble("totalDistanceMeters")
        if (totalDistance < 0.0) throw InvalidPayload("invalid route distance")
        val eta = value.requiredLong("estimatedWalkingTimeSeconds")
        if (eta < 0L) throw InvalidPayload("invalid route eta")
        return try {
            RouteResult(
                origin = value.requiredPoint("origin"),
                destination = value.requiredPoint("destination"),
                snappedOriginNodeId = value.requiredLong("snappedOriginNodeId"),
                snappedDestinationNodeId = value.requiredLong("snappedDestinationNodeId"),
                polyline = RoutePolyline(polyline),
                totalDistanceMeters = totalDistance,
                estimatedWalkingTimeSeconds = eta,
                graphNodeIds = graphNodeIds,
                graphEdges = edges,
                maneuvers = maneuvers
            )
        } catch (failure: InvalidPayload) {
            throw failure
        } catch (failure: Throwable) {
            throw InvalidPayload(failure.message ?: "invalid route")
        }
    }

    private fun PedestrianGraphEdge.toJson(): JSONObject = JSONObject()
        .put("fromNodeId", fromNodeId)
        .put("toNodeId", toNodeId)
        .put("distanceMeters", distanceMeters)
        .put("wayId", wayId)
        .put("highway", highway)
        .putNullable("name", name)
        .put("sequenceIndex", sequenceIndex)

    private fun JSONObject.toGraphEdge(): PedestrianGraphEdge {
        val highway = requiredString("highway", MAX_DISPLAY_NAME_LENGTH)
        val distance = requiredFiniteDouble("distanceMeters")
        if (distance < 0.0) throw InvalidPayload("invalid graph edge distance")
        return PedestrianGraphEdge(
            fromNodeId = requiredLong("fromNodeId"),
            toNodeId = requiredLong("toNodeId"),
            distanceMeters = distance,
            wayId = requiredLong("wayId"),
            highway = highway,
            name = optionalString("name", MAX_DISPLAY_NAME_LENGTH),
            sequenceIndex = requiredInt("sequenceIndex")
        )
    }

    private fun RouteManeuver.toJson(): JSONObject = JSONObject()
        .put("type", type.name)
        .put("point", point.toJson())
        .putNullable("roadName", roadName)
        .putNullable("wayId", wayId)
        .put("distanceFromPreviousMeters", distanceFromPreviousMeters)
        .putNullable("headingBeforeDeg", headingBeforeDeg)
        .putNullable("headingAfterDeg", headingAfterDeg)
        .put("instruction", instruction)

    private fun JSONObject.toManeuver(): RouteManeuver {
        val type = runCatching { RouteManeuverType.valueOf(requiredString("type", 64)) }
            .getOrElse { throw InvalidPayload("invalid maneuver type") }
        val distance = requiredFiniteDouble("distanceFromPreviousMeters")
        if (distance < 0.0) throw InvalidPayload("invalid maneuver distance")
        val headingBefore = optionalFiniteDouble("headingBeforeDeg")
        val headingAfter = optionalFiniteDouble("headingAfterDeg")
        return RouteManeuver(
            type = type,
            point = requiredPoint("point"),
            roadName = optionalString("roadName", MAX_DISPLAY_NAME_LENGTH),
            wayId = optionalLong("wayId"),
            distanceFromPreviousMeters = distance,
            headingBeforeDeg = headingBefore,
            headingAfterDeg = headingAfter,
            instruction = requiredString("instruction", MAX_INSTRUCTION_LENGTH)
        )
    }

    private fun JSONObject.requiredLongArray(name: String, maxSize: Int): List<Long> {
        val array = optJSONArray(name) ?: throw InvalidPayload("missing $name")
        if (array.length() > maxSize) throw InvalidPayload("too many $name")
        return (0 until array.length()).map { index ->
            val value = array.opt(index) as? Number ?: throw InvalidPayload("invalid $name[$index]")
            val long = value.toLong()
            if (value.toDouble() != long.toDouble()) throw InvalidPayload("invalid $name[$index]")
            long
        }
    }

    private fun JSONObject.requiredString(name: String, maxLength: Int): String =
        requiredStringAllowEmpty(name, maxLength).takeIf { it.isNotBlank() }
            ?: throw InvalidPayload("missing $name")

    private fun JSONObject.requiredStringAllowEmpty(name: String, maxLength: Int): String {
        val value = opt(name) as? String ?: throw InvalidPayload("missing $name")
        if (value.length > maxLength) throw InvalidPayload("$name is too long")
        return value
    }

    private fun JSONObject.optionalString(name: String, maxLength: Int): String? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name) as? String ?: throw InvalidPayload("invalid $name")
        if (value.length > maxLength || value.isBlank()) throw InvalidPayload("invalid $name")
        return value
    }

    private fun JSONObject.optionalStringAllowEmpty(name: String, maxLength: Int): String? {
        if (!has(name) || isNull(name)) return null
        val value = opt(name) as? String ?: throw InvalidPayload("invalid $name")
        if (value.length > maxLength) throw InvalidPayload("invalid $name")
        return value
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean =
        (opt(name) as? Boolean) ?: throw InvalidPayload("missing $name")

    private fun JSONObject.requiredInt(name: String): Int {
        val value = opt(name) as? Number ?: throw InvalidPayload("missing $name")
        val long = value.toLong()
        if (value.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) {
            throw InvalidPayload("invalid $name")
        }
        return long.toInt()
    }

    private fun JSONObject.requiredLong(name: String): Long {
        val value = opt(name) as? Number ?: throw InvalidPayload("missing $name")
        val long = value.toLong()
        if (!value.toDouble().isFinite() || value.toDouble() != long.toDouble()) {
            throw InvalidPayload("invalid $name")
        }
        return long
    }

    private fun JSONObject.optionalLong(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return requiredLong(name)
    }

    private fun JSONObject.requiredFiniteDouble(name: String): Double {
        val value = opt(name) as? Number ?: throw InvalidPayload("missing $name")
        return value.toDouble().takeIf { it.isFinite() } ?: throw InvalidPayload("invalid $name")
    }

    private fun JSONObject.requiredRouteParameter(name: String, minimumExclusive: Double, maximum: Double): Double {
        val value = requiredFiniteDouble(name)
        if (value <= minimumExclusive || value > maximum) throw InvalidPayload("invalid $name")
        return value
    }

    private fun JSONObject.optionalFiniteDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return requiredFiniteDouble(name)
    }

    private fun JSONObject.requiredMode(name: String): RouteMode =
        runCatching { RouteMode.valueOf(requiredString(name, 32).uppercase()) }
            .getOrElse { throw InvalidPayload("invalid route mode") }

    private fun JSONObject.optionalPoint(name: String): GeoPoint? {
        if (!has(name) || isNull(name)) return null
        return requiredPoint(name)
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private class InvalidPayload(message: String) : IllegalArgumentException(message)

    private const val MAX_SNAP_DISTANCE_METERS = 5_000.0
    private const val MAX_WALKING_SPEED_MPS = 10.0
}
