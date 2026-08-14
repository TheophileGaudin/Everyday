package com.everyday.everyday_glasses

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

fun interface ForwardGeocoder {
    fun search(address: String, near: MapGeoPoint): Result<List<GeocodingCandidate>>
}
interface CancellableForwardGeocoder : ForwardGeocoder { fun cancelActive() }

/** maps.co forward geocoding using the same API-key and HTTP conventions as reverse geocoding. */
class MapsCoForwardGeocoder(
    private val apiKey: String = DEFAULT_API_KEY,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val connectTimeoutMs: Int = 2_500,
    private val readTimeoutMs: Int = 2_500,
    private val fetchResponse: ((String) -> String)? = null
) : CancellableForwardGeocoder {
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    init {
        require(endpoint.isNotBlank()) { "maps.co endpoint must not be blank" }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) { "maps.co timeouts must be positive" }
    }

    override fun search(address: String, near: MapGeoPoint): Result<List<GeocodingCandidate>> {
        val normalized = NavigationAddressText.normalize(address)
        if (normalized.isBlank()) {
            return Result.failure(GeocodingException(GeocodingFailureKind.INVALID_QUERY, "Address must not be blank"))
        }
        return runCatching {
            val url = buildUrl(normalized, near)
            val payload = fetchResponse?.invoke(url) ?: request(url)
            parseResponse(payload)
        }.recoverCatching { failure ->
            if (failure is GeocodingException) throw failure
            val kind = when (failure) {
                is SocketTimeoutException -> GeocodingFailureKind.NETWORK
                is IOException -> GeocodingFailureKind.NETWORK
                else -> GeocodingFailureKind.MALFORMED_RESPONSE
            }
            throw GeocodingException(kind, failure.message ?: "maps.co search failed", failure)
        }
    }

    fun buildUrl(address: String, near: MapGeoPoint): String {
        val query = URLEncoder.encode(NavigationAddressText.normalize(address), Charsets.UTF_8.name())
        val key = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        return "$endpoint?q=$query&limit=50&lat=${formatCoordinate(near.lat)}&lon=${formatCoordinate(near.lon)}&api_key=$key"
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
                throw GeocodingException(
                    GeocodingFailureKind.NETWORK,
                    "maps.co search returned HTTP $responseCode"
                )
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    override fun cancelActive() { activeConnection.getAndSet(null)?.disconnect() }

    companion object {
        const val DEFAULT_ENDPOINT = "https://geocode.maps.co/search"
        /** Existing Everyday maps.co configuration used by reverse geocoding. */
        const val DEFAULT_API_KEY = "6974a768992ec642181119eby0e50e7"

        fun parseResponse(payload: String): List<GeocodingCandidate> {
            val array = try {
                JSONArray(payload)
            } catch (failure: Throwable) {
                throw GeocodingException(
                    GeocodingFailureKind.MALFORMED_RESPONSE,
                    "maps.co response is not a JSON array",
                    failure
                )
            }
            val candidates = mutableListOf<GeocodingCandidate>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val lat = number(item, "lat")
                val lon = number(item, "lon")
                val displayName = item.optString("display_name").trim()
                    .ifBlank { item.optString("name").trim() }
                if (lat == null || lon == null || displayName.isBlank() ||
                    lat !in -90.0..90.0 || lon !in -180.0..180.0
                ) continue
                candidates += GeocodingCandidate(
                    point = MapGeoPoint(lat, lon),
                    displayName = displayName,
                    importance = item.optDouble("importance").takeIf { it.isFinite() },
                    type = item.optString("type").takeIf { it.isNotBlank() },
                    addressText = item.optJSONObject("address")?.let(::addressText),
                    sourceIndex = index
                )
            }
            if (array.length() > 0 && candidates.isEmpty()) {
                throw GeocodingException(
                    GeocodingFailureKind.MALFORMED_RESPONSE,
                    "maps.co response contained no valid coordinates"
                )
            }
            return candidates
        }

        private fun number(item: JSONObject, key: String): Double? {
            val raw = item.opt(key)
            return when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            }?.takeIf { it.isFinite() }
        }

        private fun addressText(address: JSONObject): String = buildList {
            val keys = listOf(
                "house_number", "road", "neighbourhood", "suburb", "city", "town",
                "village", "state", "postcode", "country"
            )
            keys.forEach { key -> address.optString(key).trim().takeIf { it.isNotBlank() }?.let(::add) }
        }.joinToString(", ")

        private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
    }
}
