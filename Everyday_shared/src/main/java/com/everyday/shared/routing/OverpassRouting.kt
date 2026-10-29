package com.everyday.shared.routing

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

data class RouteAreaBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
)

/** Builds a complete way/node query for the rectangular route area plus a buffer. */
object OverpassQueryBuilder {
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    private const val OVERPASS_TIMEOUT_SECONDS = 25

    fun bounds(request: RouteRequest, bufferMeters: Int): RouteAreaBounds {
        require(bufferMeters > 0) { "The route-area buffer must be positive" }
        val minLat = min(request.origin.lat, request.destination.lat)
        val maxLat = max(request.origin.lat, request.destination.lat)
        val middleLatRadians = Math.toRadians((minLat + maxLat) / 2.0)
        val latBuffer = bufferMeters / METERS_PER_DEGREE_LATITUDE
        val lonScale = max(cos(middleLatRadians), 0.01)
        val lonBuffer = bufferMeters / (METERS_PER_DEGREE_LATITUDE * lonScale)
        return RouteAreaBounds(
            south = (minLat - latBuffer).coerceIn(-90.0, 90.0),
            west = (min(request.origin.lon, request.destination.lon) - lonBuffer).coerceIn(-180.0, 180.0),
            north = (maxLat + latBuffer).coerceIn(-90.0, 90.0),
            east = (max(request.origin.lon, request.destination.lon) + lonBuffer).coerceIn(-180.0, 180.0)
        )
    }

    fun build(request: RouteRequest, bufferMeters: Int = WalkingRouteDefaults.INITIAL_QUERY_BUFFER_METERS): String {
        val area = bounds(request, bufferMeters)
        return """
            [out:json][timeout:$OVERPASS_TIMEOUT_SECONDS];
            (
              way(${format(area.south)},${format(area.west)},${format(area.north)},${format(area.east)})["highway"];
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
}

sealed class OverpassParseResult {
    data class Success(val data: OverpassRouteData) : OverpassParseResult()
    data class Failure(val failure: RouteFailure.MalformedResponse) : OverpassParseResult()
}

/** Strict parser for the node-referenced response produced by [OverpassQueryBuilder]. */
object OverpassResponseParser {
    fun parse(json: String): OverpassParseResult = try {
        parse(JSONObject(json))
    } catch (t: Throwable) {
        OverpassParseResult.Failure(RouteFailure.MalformedResponse(messageFor(t)))
    }

    fun parse(root: JSONObject): OverpassParseResult {
        return try {
            val elements = root.optJSONArray("elements")
                ?: return malformed("Response does not contain an elements array")
            val nodesById = LinkedHashMap<Long, OverpassNode>()
            val ways = ArrayList<OverpassWay>()
            val wayIds = HashSet<Long>()

            for (index in 0 until elements.length()) {
                val element = elements.opt(index) as? JSONObject
                    ?: return malformed("Element $index is not an object")
                val type = element.optString("type").trim().lowercase(Locale.US)
                when (type) {
                    "node" -> {
                        val id = requiredLong(element, "id")
                            ?: return malformed("Node $index has no valid id")
                        val point = requiredPoint(element)
                            ?: return malformed("Node $id has invalid coordinates")
                        val previous = nodesById.put(id, OverpassNode(id, point))
                        if (previous != null && previous.point != point) {
                            return malformed("Node $id is present with conflicting coordinates")
                        }
                    }

                    "way", "" -> {
                        if (type.isEmpty() && !element.has("nodes")) continue
                        val id = requiredLong(element, "id")
                            ?: return malformed("Way $index has no valid id")
                        if (!wayIds.add(id)) return malformed("Way $id appears more than once")
                        val nodeArray = element.optJSONArray("nodes")
                            ?: return malformed("Way $id has no node references")
                        if (nodeArray.length() < 2) return malformed("Way $id has fewer than two nodes")
                        val nodeIds = ArrayList<Long>(nodeArray.length())
                        for (nodeIndex in 0 until nodeArray.length()) {
                            val nodeId = numberAsLong(nodeArray.opt(nodeIndex))
                                ?: return malformed("Way $id has an invalid node reference")
                            nodeIds += nodeId
                        }
                        val tagsObject = element.optJSONObject("tags")
                            ?: return malformed("Way $id has no tags object")
                        val tags = LinkedHashMap<String, String>()
                        val keys = tagsObject.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = tagsObject.opt(key)
                            if (value !is String) return malformed("Way $id has a non-string tag value")
                            tags[key] = value
                        }
                        ways += OverpassWay(id, nodeIds, tags)
                    }

                    // The query can legally return relation metadata; it is not part of the
                    // walk graph and is intentionally ignored.
                    else -> Unit
                }
            }

            for (way in ways) {
                for (nodeId in way.nodeIds) {
                    if (nodeId !in nodesById) {
                        return malformed("Way ${way.id} references missing node $nodeId")
                    }
                }
            }
            OverpassParseResult.Success(OverpassRouteData(nodesById.values.toList(), ways))
        } catch (t: Throwable) {
            OverpassParseResult.Failure(RouteFailure.MalformedResponse(messageFor(t)))
        }
    }

    private fun requiredPoint(element: JSONObject): GeoPoint? {
        val lat = requiredDouble(element, "lat") ?: return null
        val lon = requiredDouble(element, "lon") ?: return null
        return runCatching { GeoPoint(lat, lon) }.getOrNull()
    }

    private fun requiredLong(element: JSONObject, name: String): Long? = numberAsLong(element.opt(name))

    private fun requiredDouble(element: JSONObject, name: String): Double? =
        (element.opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun numberAsLong(value: Any?): Long? {
        val number = value as? Number ?: return null
        val doubleValue = number.toDouble()
        if (!doubleValue.isFinite() || doubleValue != doubleValue.toLong().toDouble()) return null
        return number.toLong()
    }

    private fun malformed(reason: String): OverpassParseResult.Failure =
        OverpassParseResult.Failure(RouteFailure.MalformedResponse(reason))

    private fun messageFor(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
}

sealed class RouteGraphFetchResult {
    data class Success(val data: OverpassRouteData) : RouteGraphFetchResult()
    data class Failure(val failure: RouteFailure) : RouteGraphFetchResult()
}

fun interface RouteGraphProvider {
    fun fetch(request: RouteRequest, bufferMeters: Int): RouteGraphFetchResult
}

interface CancellableRouteGraphProvider : RouteGraphProvider {
    fun cancelActive()
}

/** Live Overpass implementation used by either app; tests can inject a fixture provider. */
class OverpassRouteDataSource(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 25_000
) : CancellableRouteGraphProvider {
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)

    init {
        require(endpoints.isNotEmpty()) { "At least one Overpass endpoint is required" }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "Overpass timeouts must be positive" }
    }

    override fun fetch(request: RouteRequest, bufferMeters: Int): RouteGraphFetchResult {
        val query = OverpassQueryBuilder.build(request, bufferMeters)
        var lastFailure: RouteFailure = RouteFailure.NetworkFailure("No Overpass endpoint succeeded")
        for (endpoint in endpoints) {
            if (Thread.currentThread().isInterrupted) {
                return RouteGraphFetchResult.Failure(RouteFailure.Timeout("Overpass request cancelled"))
            }
            try {
                val response = post(endpoint, query)
                when (val parsed = OverpassResponseParser.parse(response)) {
                    is OverpassParseResult.Success -> return RouteGraphFetchResult.Success(parsed.data)
                    is OverpassParseResult.Failure -> return RouteGraphFetchResult.Failure(parsed.failure)
                }
            } catch (t: Throwable) {
                if (Thread.currentThread().isInterrupted) {
                    return RouteGraphFetchResult.Failure(RouteFailure.Timeout("Overpass request cancelled"))
                }
                val failure = classify(t)
                if (failure is RouteFailure.AuthorizationFailure) {
                    return RouteGraphFetchResult.Failure(failure)
                }
                lastFailure = failure
            }
        }
        return RouteGraphFetchResult.Failure(lastFailure)
    }

    private fun post(endpoint: String, query: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("User-Agent", "Everyday-Walking-Router/1.0")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        }
        activeConnection.set(connection)
        return try {
            connection.outputStream.use { output ->
                output.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw HttpStatusException(status, "Overpass returned HTTP $status")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    override fun cancelActive() {
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun classify(t: Throwable): RouteFailure = when (t) {
        is HttpStatusException -> when (t.statusCode) {
            401, 403 -> RouteFailure.AuthorizationFailure(t.statusCode, t.message ?: "Overpass authorization failed")
            408, 504 -> RouteFailure.Timeout(t.message ?: "Overpass request timed out")
            else -> RouteFailure.NetworkFailure(t.message ?: "Overpass HTTP request failed")
        }
        is SocketTimeoutException -> RouteFailure.Timeout(t.message ?: "Overpass request timed out")
        is IOException -> RouteFailure.NetworkFailure(t.message ?: "Overpass network request failed")
        else -> RouteFailure.NetworkFailure(t.message ?: t::class.java.simpleName)
    }

    private class HttpStatusException(val statusCode: Int, message: String) : RuntimeException(message)

    companion object {
        val DEFAULT_ENDPOINTS: List<String> = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )
    }
}
