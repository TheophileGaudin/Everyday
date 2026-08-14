package com.everyday.everyday_phone

import com.everyday.shared.routing.GeoMath
import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.transport.DestinationSearchCandidate
import com.everyday.shared.transport.DestinationSearchCandidateKind
import com.everyday.shared.transport.DestinationSearchErrorCode
import com.everyday.shared.transport.DestinationSearchProtocol
import com.everyday.shared.transport.DestinationSearchRequest
import org.json.JSONObject
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class DestinationQueryKind { ADDRESS, CATEGORY, PLACE }

data class DestinationQueryClassification(val kind: DestinationQueryKind, val categoryId: String? = null)

object DestinationSearchText {
    fun normalizeInput(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ").trim()

    fun matchKey(value: String): String = Normalizer.normalize(normalizeInput(value), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
}

object DestinationQueryClassifier {
    private val categories = mapOf(
        "train station" to "train_station", "railway station" to "train_station",
        "rail station" to "train_station", "gare" to "train_station",
        "gare ferroviaire" to "train_station", "supermarket" to "supermarket",
        "supermarche" to "supermarket", "school" to "school", "ecole" to "school",
        "hospital" to "hospital", "hopital" to "hospital", "police station" to "police_station",
        "bus station" to "bus_station", "gare routiere" to "bus_station",
        "ferry terminal" to "ferry_terminal", "cafe" to "cafe", "restaurant" to "restaurant",
        "pharmacy" to "pharmacy", "pharmacie" to "pharmacy", "gas station" to "gas_station",
        "station service" to "gas_station", "library" to "library", "bibliotheque" to "library"
    )

    fun classify(query: String): DestinationQueryClassification {
        val key = DestinationSearchText.matchKey(query)
        categories[key]?.let { return DestinationQueryClassification(DestinationQueryKind.CATEGORY, it) }
        return if (key.any(Char::isDigit)) DestinationQueryClassification(DestinationQueryKind.ADDRESS)
        else DestinationQueryClassification(DestinationQueryKind.PLACE)
    }
}

class DestinationSearchException(
    val code: DestinationSearchErrorCode,
    message: String,
    val retryable: Boolean = true,
    cause: Throwable? = null
) : RuntimeException(message, cause)

data class DestinationSearchResponse(
    val candidates: List<DestinationSearchCandidate>,
    val attribution: String,
    val outsideWalkingRange: Boolean = false
)

fun interface DestinationSearchProvider {
    fun search(request: DestinationSearchRequest): Result<DestinationSearchResponse>
}

interface CancellableDestinationSearchProvider : DestinationSearchProvider { fun cancelActive() }

class MapboxDestinationSearchProvider(
    private val accessToken: String = BuildConfig.MAPBOX_ACCESS_TOKEN,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS).build(),
    private val fetchResponse: ((String) -> Pair<Int, String>)? = null
) : CancellableDestinationSearchProvider {
    private val activeCall = AtomicReference<Call?>(null)

    override fun search(request: DestinationSearchRequest): Result<DestinationSearchResponse> = runCatching {
        if (!accessToken.startsWith("pk.")) throw DestinationSearchException(
            DestinationSearchErrorCode.NOT_CONFIGURED, "Mapbox destination search is not configured", false
        )
        val classification = DestinationQueryClassifier.classify(request.query)
        val url = buildUrl(request, classification)
        val (status, body) = fetchResponse?.invoke(url) ?: execute(url)
        if (status !in 200..299) throw when (status) {
            429 -> DestinationSearchException(DestinationSearchErrorCode.RATE_LIMITED, "Destination search rate limit reached")
            401, 403 -> DestinationSearchException(DestinationSearchErrorCode.NOT_CONFIGURED, "Mapbox rejected the access token", false)
            else -> DestinationSearchException(DestinationSearchErrorCode.NETWORK, "Destination search returned HTTP $status")
        }
        parseAndRank(body, request, classification)
    }.recoverCatching { failure ->
        if (failure is DestinationSearchException) throw failure
        val code = if (failure is IOException) DestinationSearchErrorCode.NETWORK else DestinationSearchErrorCode.MALFORMED
        throw DestinationSearchException(
            code,
            if (code == DestinationSearchErrorCode.NETWORK) "Destination search network failure" else "Destination search response was malformed",
            code == DestinationSearchErrorCode.NETWORK,
            failure
        )
    }

    internal fun buildUrl(request: DestinationSearchRequest, classification: DestinationQueryClassification): String {
        val base = when (classification.kind) {
            DestinationQueryKind.ADDRESS -> "https://api.mapbox.com/search/geocode/v6/forward"
            DestinationQueryKind.CATEGORY -> "https://api.mapbox.com/search/searchbox/v1/category/${classification.categoryId}"
            DestinationQueryKind.PLACE -> "https://api.mapbox.com/search/searchbox/v1/forward"
        }.toHttpUrl().newBuilder()
        if (classification.kind != DestinationQueryKind.CATEGORY) base.addQueryParameter("q", request.query)
        return base.addQueryParameter("limit", request.limit.coerceAtMost(DestinationSearchProtocol.MAX_RESULTS).toString())
            .addQueryParameter("proximity", "${request.origin.lon},${request.origin.lat}")
            .apply { request.languageTag.substringBefore('-').takeIf { it.length in 2..3 }?.let { addQueryParameter("language", it) } }
            .addQueryParameter("access_token", accessToken).build().toString()
    }

    private fun execute(url: String): Pair<Int, String> {
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        activeCall.set(call)
        return try { call.execute().use { it.code to (it.body?.string().orEmpty()) } }
        finally { activeCall.compareAndSet(call, null) }
    }

    override fun cancelActive() { activeCall.getAndSet(null)?.cancel() }

    private data class Raw(val id: String, val candidate: DestinationSearchCandidate, val sourceIndex: Int)

    internal fun parseAndRank(
        payload: String,
        request: DestinationSearchRequest,
        classification: DestinationQueryClassification
    ): DestinationSearchResponse {
        val root = JSONObject(payload)
        val features = root.optJSONArray("features") ?: throw DestinationSearchException(
            DestinationSearchErrorCode.MALFORMED, "Mapbox response is missing features", false
        )
        val raw = (0 until features.length()).mapNotNull { index ->
            val feature = features.optJSONObject(index) ?: return@mapNotNull null
            val props = feature.optJSONObject("properties") ?: JSONObject()
            val geometry = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
            val coords = props.optJSONObject("coordinates")
            val lon = coords?.optDouble("longitude")?.takeIf(Double::isFinite)
                ?: geometry?.optDouble(0)?.takeIf(Double::isFinite) ?: return@mapNotNull null
            val lat = coords?.optDouble("latitude")?.takeIf(Double::isFinite)
                ?: geometry?.optDouble(1)?.takeIf(Double::isFinite) ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val name = props.optString("name_preferred").ifBlank { props.optString("name") }
                .ifBlank { props.optString("full_address") }.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val secondary = props.optString("full_address").ifBlank {
                listOf(props.optString("address"), props.optString("place_formatted"))
                    .filter(String::isNotBlank).joinToString(", ")
            }.trim().take(384)
            val type = props.optString("feature_type")
            Raw(
                props.optString("mapbox_id").ifBlank { feature.optString("id") }.ifBlank { "$lat,$lon" },
                DestinationSearchCandidate(name.take(256), secondary, GeoPoint(lat, lon),
                    if (classification.kind == DestinationQueryKind.ADDRESS || type == "address") DestinationSearchCandidateKind.ADDRESS else DestinationSearchCandidateKind.POI),
                index
            )
        }
        val queryKey = DestinationSearchText.matchKey(request.query)
        val deduped = raw.distinctBy { rawItem ->
            rawItem.id.ifBlank { DestinationSearchText.matchKey(rawItem.candidate.displayName + rawItem.candidate.secondaryText) }
        }
        val sorted = deduped.sortedWith(compareBy<Raw> { item ->
            if (classification.kind == DestinationQueryKind.CATEGORY) 0 else {
                val searchable = DestinationSearchText.matchKey(item.candidate.displayName + " " + item.candidate.secondaryText)
                if (searchable.contains(queryKey)) 0 else queryKey.split(' ').count { !searchable.contains(it) }
            }
        }.thenBy { GeoMath.distanceMeters(request.origin, it.candidate.point) }.thenBy { it.sourceIndex })
        return DestinationSearchResponse(
            sorted.take(request.limit).map(Raw::candidate),
            root.optString("attribution").take(512),
            outsideWalkingRange = false
        )
    }
}
