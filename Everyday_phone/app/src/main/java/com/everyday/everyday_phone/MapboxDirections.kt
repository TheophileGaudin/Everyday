package com.everyday.everyday_phone

import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.RouteFailure
import com.everyday.shared.routing.RouteManeuver
import com.everyday.shared.routing.RouteManeuverType
import com.everyday.shared.routing.RoutePolyline
import com.everyday.shared.routing.RouteResult
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

class MapboxDirectionsException(
    val failure: RouteFailure,
    cause: Throwable? = null
) : RuntimeException(failureMessage(failure), cause) {
    companion object {
        private fun failureMessage(failure: RouteFailure): String = when (failure) {
            is RouteFailure.AuthorizationFailure -> failure.message
            is RouteFailure.DisconnectedGraph -> "Mapbox could not find a walking route"
            is RouteFailure.InvalidRequest -> failure.reason
            is RouteFailure.MalformedResponse -> failure.reason
            is RouteFailure.NetworkFailure -> failure.message
            is RouteFailure.Timeout -> failure.message
            is RouteFailure.NoNearbyGraphNode -> "Mapbox could not snap the route endpoint"
            is RouteFailure.OverLimit -> "The route exceeds the provider limit"
            is RouteFailure.UnsupportedMode -> "The route mode is unsupported"
        }
    }
}

fun interface PhoneDirectionsProvider {
    fun route(origin: GeoPoint, destination: GeoPoint, languageTag: String): Result<RouteResult>
}

interface CancellablePhoneDirectionsProvider : PhoneDirectionsProvider {
    fun cancelActive()
}

/** Direct Mapbox Directions v5 client. Search has already resolved the destination coordinates. */
class MapboxDirectionsProvider(
    private val accessToken: String = BuildConfig.MAPBOX_ACCESS_TOKEN,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val fetchResponse: ((String) -> Pair<Int, String>)? = null
) : CancellablePhoneDirectionsProvider {
    private val activeCall = AtomicReference<Call?>(null)

    override fun route(
        origin: GeoPoint,
        destination: GeoPoint,
        languageTag: String
    ): Result<RouteResult> = runCatching {
        if (!accessToken.startsWith("pk.")) {
            throw MapboxDirectionsException(
                RouteFailure.AuthorizationFailure(401, "Mapbox Directions is not configured")
            )
        }
        val url = buildUrl(origin, destination, languageTag)
        val (status, body) = fetchResponse?.invoke(url) ?: execute(url)
        if (status !in 200..299) throw statusFailure(status)
        parseResponse(body, origin, destination)
    }.recoverCatching { error ->
        if (error is MapboxDirectionsException) throw error
        val failure = when (error) {
            is SocketTimeoutException -> RouteFailure.Timeout("Mapbox Directions timed out")
            is IOException -> RouteFailure.NetworkFailure("Mapbox Directions network request failed")
            else -> RouteFailure.MalformedResponse("Mapbox Directions response was malformed")
        }
        throw MapboxDirectionsException(failure, error)
    }

    internal fun buildUrl(origin: GeoPoint, destination: GeoPoint, languageTag: String): String {
        val coordinates = "${format(origin.lon)},${format(origin.lat)};${format(destination.lon)},${format(destination.lat)}"
        return "https://api.mapbox.com/directions/v5/mapbox/walking/$coordinates"
            .toHttpUrl().newBuilder()
            .addQueryParameter("steps", "true")
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "geojson")
            .addQueryParameter("language", languageTag.substringBefore('-').takeIf { it.length in 2..3 } ?: "en")
            .addQueryParameter("access_token", accessToken)
            .build().toString()
    }

    private fun execute(url: String): Pair<Int, String> {
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        activeCall.set(call)
        return try {
            call.execute().use { response -> response.code to response.body?.string().orEmpty() }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    override fun cancelActive() {
        activeCall.getAndSet(null)?.cancel()
    }

    internal fun parseResponse(payload: String, origin: GeoPoint, destination: GeoPoint): RouteResult {
        val root = try {
            JSONObject(payload)
        } catch (error: Throwable) {
            throw MapboxDirectionsException(
                RouteFailure.MalformedResponse("Mapbox Directions response is not valid JSON"),
                error
            )
        }
        val code = root.optString("code")
        if (code != "Ok") {
            val message = root.optString("message").take(256).ifBlank { "Mapbox returned $code" }
            throw MapboxDirectionsException(
                if (code == "NoRoute" || code == "NoSegment") {
                    RouteFailure.DisconnectedGraph(0L, 0L)
                } else {
                    RouteFailure.InvalidRequest(message)
                }
            )
        }
        val route = root.optJSONArray("routes")?.optJSONObject(0)
            ?: throw malformed("Mapbox Directions response contains no route")
        val distance = route.finiteNonNegative("distance")
        val duration = route.finiteNonNegative("duration")
        val coordinates = route.optJSONObject("geometry")?.optJSONArray("coordinates")
            ?: throw malformed("Mapbox Directions route is missing geometry")
        if (coordinates.length() !in 2..MAX_RAW_POLYLINE_POINTS) {
            throw malformed("Mapbox Directions route geometry has an invalid size")
        }
        val rawPoints = (0 until coordinates.length()).map { index ->
            coordinates.optJSONArray(index)?.toPoint()
                ?: throw malformed("Mapbox Directions route contains an invalid coordinate")
        }
        val points = downsample(rawPoints, MAX_TRANSPORT_POLYLINE_POINTS)
        val steps = parseSteps(route.optJSONArray("legs"))
        return RouteResult(
            origin = origin,
            destination = destination,
            snappedOriginNodeId = 0L,
            snappedDestinationNodeId = (points.lastIndex).toLong(),
            polyline = RoutePolyline(points),
            totalDistanceMeters = distance,
            estimatedWalkingTimeSeconds = ceil(duration).toLong(),
            graphNodeIds = emptyList(),
            graphEdges = emptyList(),
            maneuvers = steps
        )
    }

    private data class RawStep(
        val distanceMeters: Double,
        val maneuver: JSONObject,
        val roadName: String?
    )

    private fun parseSteps(legs: JSONArray?): List<RouteManeuver> {
        if (legs == null || legs.length() !in 1..MAX_LEGS) {
            throw malformed("Mapbox Directions route is missing legs")
        }
        val raw = mutableListOf<RawStep>()
        for (legIndex in 0 until legs.length()) {
            val steps = legs.optJSONObject(legIndex)?.optJSONArray("steps")
                ?: throw malformed("Mapbox Directions route leg is missing steps")
            if (raw.size + steps.length() > MAX_MANEUVERS) {
                throw malformed("Mapbox Directions route contains too many maneuvers")
            }
            for (stepIndex in 0 until steps.length()) {
                val step = steps.optJSONObject(stepIndex)
                    ?: throw malformed("Mapbox Directions route contains an invalid step")
                val maneuver = step.optJSONObject("maneuver")
                    ?: throw malformed("Mapbox Directions step is missing a maneuver")
                raw += RawStep(
                    distanceMeters = step.finiteNonNegative("distance"),
                    maneuver = maneuver,
                    roadName = step.optString("name").trim().take(256).takeIf(String::isNotBlank)
                )
            }
        }
        return raw.mapIndexed { index, step ->
            val type = maneuverType(step.maneuver.optString("type"), step.maneuver.optString("modifier"))
            RouteManeuver(
                type = type,
                point = step.maneuver.optJSONArray("location")?.toPoint()
                    ?: throw malformed("Mapbox Directions maneuver is missing its location"),
                roadName = step.roadName,
                wayId = null,
                distanceFromPreviousMeters = if (index == 0) 0.0 else raw[index - 1].distanceMeters,
                headingBeforeDeg = step.maneuver.optionalBearing("bearing_before"),
                headingAfterDeg = step.maneuver.optionalBearing("bearing_after"),
                instruction = step.maneuver.optString("instruction").trim().take(256).ifBlank {
                    fallbackInstruction(type, step.roadName)
                }
            )
        }
    }

    private fun statusFailure(status: Int): MapboxDirectionsException = MapboxDirectionsException(
        when (status) {
            401, 403 -> RouteFailure.AuthorizationFailure(status, "Mapbox rejected the access token")
            408, 504 -> RouteFailure.Timeout("Mapbox Directions returned HTTP $status")
            422 -> RouteFailure.InvalidRequest("Mapbox rejected the route request")
            else -> RouteFailure.NetworkFailure("Mapbox Directions returned HTTP $status")
        }
    )

    private fun malformed(reason: String) = MapboxDirectionsException(RouteFailure.MalformedResponse(reason))

    private fun JSONObject.finiteNonNegative(name: String): Double {
        val value = optDouble(name, Double.NaN)
        if (!value.isFinite() || value < 0.0) throw malformed("Mapbox Directions has an invalid $name")
        return value
    }

    private fun JSONArray.toPoint(): GeoPoint {
        if (length() < 2) throw malformed("Mapbox Directions coordinate is incomplete")
        val lon = optDouble(0, Double.NaN)
        val lat = optDouble(1, Double.NaN)
        return try {
            GeoPoint(lat, lon)
        } catch (error: Throwable) {
            throw malformed("Mapbox Directions coordinate is out of range")
        }
    }

    private fun JSONObject.optionalBearing(name: String): Double? =
        optDouble(name, Double.NaN).takeIf { it.isFinite() && it in 0.0..360.0 }

    private fun maneuverType(type: String, modifier: String): RouteManeuverType = when (type) {
        "depart" -> RouteManeuverType.START
        "arrive" -> RouteManeuverType.ARRIVE
        else -> when (modifier) {
            "slight left" -> RouteManeuverType.SLIGHT_LEFT
            "left" -> RouteManeuverType.LEFT
            "sharp left" -> RouteManeuverType.SHARP_LEFT
            "uturn" -> RouteManeuverType.U_TURN
            "slight right" -> RouteManeuverType.SLIGHT_RIGHT
            "right" -> RouteManeuverType.RIGHT
            "sharp right" -> RouteManeuverType.SHARP_RIGHT
            else -> RouteManeuverType.CONTINUE
        }
    }

    private fun fallbackInstruction(type: RouteManeuverType, roadName: String?): String {
        val suffix = roadName?.let { " onto $it" }.orEmpty()
        return when (type) {
            RouteManeuverType.START -> roadName?.let { "Start walking on $it" } ?: "Start walking"
            RouteManeuverType.SLIGHT_LEFT -> "Bear left$suffix"
            RouteManeuverType.LEFT -> "Turn left$suffix"
            RouteManeuverType.SHARP_LEFT -> "Turn sharply left$suffix"
            RouteManeuverType.U_TURN -> "Make a U-turn$suffix"
            RouteManeuverType.SLIGHT_RIGHT -> "Bear right$suffix"
            RouteManeuverType.RIGHT -> "Turn right$suffix"
            RouteManeuverType.SHARP_RIGHT -> "Turn sharply right$suffix"
            RouteManeuverType.ARRIVE -> "Arrive at your destination"
            else -> "Continue walking$suffix"
        }
    }

    private fun downsample(points: List<GeoPoint>, limit: Int): List<GeoPoint> {
        if (points.size <= limit) return points
        val last = points.lastIndex
        return (0 until limit).map { index -> points[(index.toLong() * last / (limit - 1)).toInt()] }
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    companion object {
        private const val MAX_LEGS = 25
        private const val MAX_MANEUVERS = 4_000
        private const val MAX_RAW_POLYLINE_POINTS = 100_000
        private const val MAX_TRANSPORT_POLYLINE_POINTS = 12_000
    }
}
