package com.everyday.everyday_phone

import com.everyday.shared.routing.GeoMath
import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.RouteFailure
import com.everyday.shared.routing.RouteMode
import com.everyday.shared.transport.RouteDestinationData
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportErrorCode
import com.everyday.shared.transport.RouteTransportRequest
import com.everyday.shared.transport.RouteTransportResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

interface PhoneRouteTransport {
    fun isConnected(): Boolean
    fun sendRouteResult(result: RouteTransportResult): Boolean
    fun sendRouteError(error: RouteTransportError): Boolean
}

fun interface PhoneRouteCalculator {
    fun calculate(request: RouteTransportRequest): PhoneRouteCalculation
}

interface CancellablePhoneRouteCalculator : PhoneRouteCalculator {
    fun cancelActive()
}

enum class PhoneGeocodingFailureKind { NETWORK, MALFORMED, INVALID_QUERY }

class PhoneGeocodingException(
    val kind: PhoneGeocodingFailureKind,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

data class PhoneGeocodingCandidate(
    val point: GeoPoint,
    val displayName: String,
    val importance: Double? = null,
    val addressText: String? = null,
    val sourceIndex: Int = 0
)

fun interface PhoneForwardGeocoder {
    fun search(address: String, near: GeoPoint): Result<List<PhoneGeocodingCandidate>>
}

interface CancellablePhoneForwardGeocoder : PhoneForwardGeocoder {
    fun cancelActive()
}

/** Phone-side maps.co geocoder.  It is intentionally UI-free; only data crosses RFCOMM. */
class MapsCoPhoneForwardGeocoder(
    private val apiKey: String = DEFAULT_API_KEY,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val fetchResponse: ((String) -> String)? = null
) : CancellablePhoneForwardGeocoder {
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)

    init {
        require(endpoint.isNotBlank()) { "maps.co endpoint must not be blank" }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "maps.co timeouts must be positive" }
    }

    override fun search(address: String, near: GeoPoint): Result<List<PhoneGeocodingCandidate>> {
        val normalized = normalizeAddress(address)
        if (normalized.isBlank()) {
            return Result.failure(
                PhoneGeocodingException(PhoneGeocodingFailureKind.INVALID_QUERY, "Address must not be blank")
            )
        }
        return runCatching {
            val url = buildUrl(normalized, near)
            parseResponse(fetchResponse?.invoke(url) ?: request(url))
        }.recoverCatching { failure ->
            if (failure is PhoneGeocodingException) throw failure
            val kind = when (failure) {
                is SocketTimeoutException, is IOException -> PhoneGeocodingFailureKind.NETWORK
                else -> PhoneGeocodingFailureKind.MALFORMED
            }
            throw PhoneGeocodingException(kind, failure.message ?: "maps.co search failed", failure)
        }
    }

    fun buildUrl(address: String, near: GeoPoint): String {
        val query = URLEncoder.encode(normalizeAddress(address), Charsets.UTF_8.name())
        val key = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        // Ask the geocoder for a broad candidate set; the ranker below applies the user's
        // current location locally, which is more reliable than depending on provider ordering.
        return "$endpoint?q=$query&limit=$MAX_RESULTS&lat=${formatCoordinate(near.lat)}&lon=${formatCoordinate(near.lon)}&api_key=$key"
    }

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Everyday-RayNeo-Navigation/1.0")
        }
        activeConnection.set(connection)
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw PhoneGeocodingException(
                    PhoneGeocodingFailureKind.NETWORK,
                    "maps.co search returned HTTP $responseCode"
                )
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

    companion object {
        const val DEFAULT_ENDPOINT = "https://geocode.maps.co/search"
        const val DEFAULT_API_KEY = "6974a768992ec642181119eby0e50e7"
        private const val MAX_RESULTS = 50

        fun parseResponse(payload: String): List<PhoneGeocodingCandidate> {
            val array = try {
                JSONArray(payload)
            } catch (failure: Throwable) {
                throw PhoneGeocodingException(
                    PhoneGeocodingFailureKind.MALFORMED,
                    "maps.co response is not a JSON array",
                    failure
                )
            }
            if (array.length() > MAX_RESULTS) {
                throw PhoneGeocodingException(
                    PhoneGeocodingFailureKind.MALFORMED,
                    "maps.co response contains too many results"
                )
            }
            val candidates = mutableListOf<PhoneGeocodingCandidate>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val lat = number(item, "lat")
                val lon = number(item, "lon")
                val displayName = item.optString("display_name").trim()
                    .ifBlank { item.optString("name").trim() }
                if (lat == null || lon == null || displayName.isBlank() ||
                    lat !in -90.0..90.0 || lon !in -180.0..180.0
                ) continue
                candidates += PhoneGeocodingCandidate(
                    point = GeoPoint(lat, lon),
                    displayName = displayName.take(256),
                    importance = item.optDouble("importance").takeIf { it.isFinite() },
                    addressText = item.optJSONObject("address")?.let(::addressText),
                    sourceIndex = index
                )
            }
            if (array.length() > 0 && candidates.isEmpty()) {
                throw PhoneGeocodingException(
                    PhoneGeocodingFailureKind.MALFORMED,
                    "maps.co response contained no valid coordinates"
                )
            }
            return candidates
        }

        private fun number(item: JSONObject, key: String): Double? = when (val raw = item.opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }

        private fun addressText(address: JSONObject): String = listOf(
            "house_number", "road", "neighbourhood", "suburb", "city", "town",
            "village", "state", "postcode", "country"
        ).mapNotNull { key -> address.optString(key).trim().takeIf { it.isNotBlank() } }
            .joinToString(", ")

        private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
    }
}

sealed class PhoneRouteCalculation {
    data class Success(val result: RouteTransportResult) : PhoneRouteCalculation()
    data class Failure(val error: RouteTransportError) : PhoneRouteCalculation()
}

/** Resolves legacy address-only requests, then asks Mapbox directly for a walking route. */
class PhoneRouteCalculationService(
    private val geocoder: PhoneForwardGeocoder = MapsCoPhoneForwardGeocoder(),
    private val directionsProvider: PhoneDirectionsProvider = MapboxDirectionsProvider(),
    private val languageTag: () -> String = { Locale.getDefault().toLanguageTag() },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : CancellablePhoneRouteCalculator {
    override fun calculate(request: RouteTransportRequest): PhoneRouteCalculation {
        if (request.mode != RouteMode.WALKING) {
            return failure(
                request,
                RouteTransportErrorCode.UNSUPPORTED_MODE,
                "Only WALKING navigation is supported",
                retryable = false
            )
        }

        var resolvedDisplayName = request.destinationDisplayName
        val destination = request.destination ?: run {
            val address = normalizeAddress(request.destinationAddress.orEmpty())
            if (address.isBlank()) {
                return failure(
                    request,
                    RouteTransportErrorCode.INVALID_REQUEST,
                    "A destination address is required",
                    retryable = false
                )
            }
            val candidates = try {
                geocoder.search(address, request.origin).getOrElse { throw it }
            } catch (error: Throwable) {
                val code = when ((error as? PhoneGeocodingException)?.kind) {
                    PhoneGeocodingFailureKind.NETWORK -> RouteTransportErrorCode.GEOCODING_NETWORK
                    PhoneGeocodingFailureKind.MALFORMED -> RouteTransportErrorCode.GEOCODING_MALFORMED
                    PhoneGeocodingFailureKind.INVALID_QUERY -> RouteTransportErrorCode.INVALID_REQUEST
                    null -> RouteTransportErrorCode.GEOCODING_NETWORK
                }
                return failure(request, code, error.message ?: "Destination search failed", retryable = true)
            }
            val resolution = PhoneGeocodingRanker.resolve(address, candidates, request.origin)
            when (resolution) {
                PhoneGeocodingResolution.NoResults -> return failure(
                    request,
                    RouteTransportErrorCode.GEOCODING_NO_RESULTS,
                    "No destination was found",
                    retryable = true
                )
                is PhoneGeocodingResolution.Ambiguous -> return PhoneRouteCalculation.Failure(
                    RouteTransportError(
                        requestId = request.requestId,
                        code = RouteTransportErrorCode.GEOCODING_AMBIGUOUS,
                        message = "Several destinations matched the address",
                        retryable = false,
                        timestampMs = nowMs(),
                        choices = resolution.choices.map {
                            RouteDestinationData(address, it.displayName, it.point)
                        }
                    )
                )
                is PhoneGeocodingResolution.Accepted -> {
                    resolvedDisplayName = resolution.candidate.displayName
                    resolution.candidate.point
                }
            }
        }

        val query = normalizeAddress(request.destinationAddress.orEmpty())
            .ifBlank { request.destinationDisplayName ?: "Resolved destination" }
        val displayName = resolvedDisplayName?.takeIf { it.isNotBlank() }
            ?: PhoneGeocodingRanker.displayNameFor(request, destination)
        return directionsProvider.route(request.origin, destination, languageTag()).fold(
            onSuccess = { route -> PhoneRouteCalculation.Success(
                RouteTransportResult(
                    requestId = request.requestId,
                    destination = RouteDestinationData(query, displayName, destination),
                    route = route,
                    calculatedAtMs = nowMs()
                )
            ) },
            onFailure = { error ->
                val routeFailure = (error as? MapboxDirectionsException)?.failure
                    ?: RouteFailure.NetworkFailure(error.message ?: "Mapbox Directions failed")
                PhoneRouteCalculation.Failure(
                RouteTransportError(
                    requestId = request.requestId,
                    code = RouteTransportErrorCode.ROUTE_FAILURE,
                    message = routeFailureMessage(routeFailure),
                    retryable = routeFailure is RouteFailure.NetworkFailure ||
                        routeFailure is RouteFailure.Timeout,
                    timestampMs = nowMs(),
                    routeFailureCode = routeFailure.code
                )
            ) }
        )
    }

    override fun cancelActive() {
        (geocoder as? CancellablePhoneForwardGeocoder)?.cancelActive()
        (directionsProvider as? CancellablePhoneDirectionsProvider)?.cancelActive()
    }

    private fun failure(
        request: RouteTransportRequest,
        code: RouteTransportErrorCode,
        message: String,
        retryable: Boolean
    ): PhoneRouteCalculation.Failure = PhoneRouteCalculation.Failure(
        RouteTransportError(
            requestId = request.requestId,
            code = code,
            message = message.take(512),
            retryable = retryable,
            timestampMs = nowMs()
        )
    )

    private fun routeFailureMessage(failure: RouteFailure): String = when (failure) {
        is RouteFailure.OverLimit -> "The destination exceeds the routing provider limit"
        is RouteFailure.NoNearbyGraphNode -> "No walkable road was found near the ${failure.endpoint.name.lowercase(Locale.ROOT)}"
        is RouteFailure.DisconnectedGraph -> "Walking roads near the destination are not connected"
        is RouteFailure.MalformedResponse -> "Walking route data was malformed"
        is RouteFailure.NetworkFailure -> "The Mapbox walking route could not be downloaded"
        is RouteFailure.Timeout -> "The Mapbox walking route request timed out"
        is RouteFailure.AuthorizationFailure -> "Mapbox rejected the route request"
        is RouteFailure.UnsupportedMode -> "Only WALKING navigation is supported"
        is RouteFailure.InvalidRequest -> "The walking route request was invalid"
    }
}

private sealed class PhoneGeocodingResolution {
    data class Accepted(val candidate: RankedPhoneCandidate) : PhoneGeocodingResolution()
    data class Ambiguous(val choices: List<RankedPhoneCandidate>) : PhoneGeocodingResolution()
    data object NoResults : PhoneGeocodingResolution()
}

private data class RankedPhoneCandidate(
    val candidate: PhoneGeocodingCandidate,
    val textualScore: Double,
    val proximityMeters: Double,
    val score: Double
) {
    val point: GeoPoint get() = candidate.point
    val displayName: String get() = candidate.displayName
}

private object PhoneGeocodingRanker {
    private const val MAX_CHOICES = 3
    private const val DOMINANT_MARGIN = 13.0
    private const val LOCATION_BIAS_WEIGHT = 48.0
    private const val STRONG_TEXT_SCORE = 0.92

    fun resolve(
        query: String,
        candidates: List<PhoneGeocodingCandidate>,
        currentLocation: GeoPoint
    ): PhoneGeocodingResolution {
        val ranked = candidates.map { candidate ->
            val normalizedQuery = normalizeForMatch(query)
            val searchable = normalizeForMatch(listOfNotNull(candidate.displayName, candidate.addressText).joinToString(" "))
            val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank).toSet()
            val isAddressQuery = queryTokens.size >= 2 && queryTokens.any { token ->
                token.any(Char::isDigit)
            }
            val candidateTokens = searchable.split(' ').filter(String::isNotBlank).toSet()
            val matchingTokens = queryTokens.count { token ->
                candidateTokens.any { it == token || it.contains(token) || token.contains(it) }
            }
            val tokenCoverage = if (queryTokens.isEmpty()) 0.0 else matchingTokens.toDouble() / queryTokens.size
            val exact = if (searchable == normalizedQuery && normalizedQuery.isNotBlank()) 1.0 else 0.0
            val prefix = if (searchable.startsWith(normalizedQuery) && normalizedQuery.isNotBlank()) 1.0 else 0.0
            val contains = if (searchable.contains(normalizedQuery) && normalizedQuery.isNotBlank()) 1.0 else 0.0
            val textual = (exact * 0.42 + prefix * 0.18 + contains * 0.16 + tokenCoverage * 0.24).coerceIn(0.0, 1.0)
            val distance = GeoMath.distanceMeters(currentLocation, candidate.point)
            val proximity = 1.0 / (1.0 + distance / 1_000.0)
            val importance = candidate.importance?.coerceIn(0.0, 1.0) ?: 0.0
            val fullAddressMatch = isAddressQuery && tokenCoverage >= 1.0
            val score = if (fullAddressMatch) {
                1_000.0 + proximity * 100.0 + textual * 10.0 + importance * 3.0
            } else {
                textual * 100.0 + proximity * LOCATION_BIAS_WEIGHT + importance * 3.0
            }
            RankedPhoneCandidate(candidate, textual, distance, score)
        }.sortedWith(
            compareByDescending<RankedPhoneCandidate> { it.score }
                .thenBy { it.proximityMeters }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.candidate.sourceIndex }
        )
        if (ranked.isEmpty()) return PhoneGeocodingResolution.NoResults
        val top = ranked.first()
        val second = ranked.getOrNull(1)
        val clearlyDominant = second == null ||
            top.score - second.score >= DOMINANT_MARGIN ||
            (top.textualScore >= STRONG_TEXT_SCORE && top.textualScore - second.textualScore >= 0.20)
        return if (clearlyDominant) PhoneGeocodingResolution.Accepted(top)
        else PhoneGeocodingResolution.Ambiguous(ranked.take(MAX_CHOICES))
    }

    fun displayNameFor(request: RouteTransportRequest, destination: GeoPoint): String =
        request.destinationAddress?.takeIf { it.isNotBlank() } ?: "${destination.lat}, ${destination.lon}"

    private fun normalizeForMatch(value: String): String = normalizeAddress(value)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalizeAddress(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
    .replace(Regex("\\s+"), " ")
    .trim()
