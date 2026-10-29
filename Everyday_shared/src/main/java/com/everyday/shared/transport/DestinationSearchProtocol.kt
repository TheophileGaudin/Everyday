package com.everyday.shared.transport

import com.everyday.shared.routing.GeoPoint
import org.json.JSONArray
import org.json.JSONObject

enum class DestinationSearchCandidateKind(val wireName: String) {
    ADDRESS("address"), POI("poi");

    companion object {
        fun fromWireName(value: String): DestinationSearchCandidateKind? =
            values().firstOrNull { it.wireName == value }
    }
}

data class DestinationSearchCandidate(
    val displayName: String,
    val secondaryText: String,
    val point: GeoPoint,
    val kind: DestinationSearchCandidateKind
)

data class DestinationSearchRequest(
    val requestId: String,
    val query: String,
    val origin: GeoPoint,
    val languageTag: String,
    val requestedAtMs: Long,
    val limit: Int = DestinationSearchProtocol.MAX_RESULTS
)

data class DestinationSearchResult(
    val requestId: String,
    val query: String,
    val candidates: List<DestinationSearchCandidate>,
    val attribution: String,
    val completedAtMs: Long
)

enum class DestinationSearchErrorCode(val wireName: String) {
    NO_RESULTS("no_results"), OUTSIDE_WALKING_RANGE("outside_walking_range"), NETWORK("network"),
    NOT_CONFIGURED("not_configured"), RATE_LIMITED("rate_limited"), MALFORMED("malformed"),
    CANCELLED("cancelled"), INTERNAL("internal");

    companion object {
        fun fromWireName(value: String): DestinationSearchErrorCode? =
            values().firstOrNull { it.wireName == value }
    }
}

data class DestinationSearchError(
    val requestId: String,
    val code: DestinationSearchErrorCode,
    val message: String,
    val retryable: Boolean,
    val timestampMs: Long
)

data class DestinationSearchCancellation(
    val requestId: String,
    val cancelledAtMs: Long,
    val reason: String? = null
)

object DestinationSearchProtocol {
    const val VERSION = 1
    const val MAX_RESULTS = 10
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_QUERY_LENGTH = 512
    const val MAX_LABEL_LENGTH = 256
    const val MAX_SECONDARY_LENGTH = 384
    const val MAX_LANGUAGE_TAG_LENGTH = 35
    const val MAX_ATTRIBUTION_LENGTH = 512
    const val MAX_ERROR_LENGTH = 512
    const val MAX_REASON_LENGTH = 128

    fun requestToJson(value: DestinationSearchRequest): JSONObject {
        require(value.requestId.length in 1..MAX_REQUEST_ID_LENGTH)
        require(value.query.isNotBlank() && value.query.length <= MAX_QUERY_LENGTH)
        require(value.languageTag.length <= MAX_LANGUAGE_TAG_LENGTH)
        require(value.limit in 1..MAX_RESULTS)
        return JSONObject().put("version", VERSION).put("requestId", value.requestId)
            .put("query", value.query).put("origin", pointToJson(value.origin))
            .put("languageTag", value.languageTag).put("requestedAtMs", value.requestedAtMs)
            .put("limit", value.limit)
    }

    fun requestFromJson(json: JSONObject): DestinationSearchRequest {
        version(json)
        return DestinationSearchRequest(
            string(json, "requestId", MAX_REQUEST_ID_LENGTH),
            string(json, "query", MAX_QUERY_LENGTH), point(json, "origin"),
            stringAllowEmpty(json, "languageTag", MAX_LANGUAGE_TAG_LENGTH),
            long(json, "requestedAtMs"), int(json, "limit").also { require(it in 1..MAX_RESULTS) }
        )
    }

    fun resultToJson(value: DestinationSearchResult): JSONObject {
        require(value.requestId.length in 1..MAX_REQUEST_ID_LENGTH)
        require(value.query.isNotBlank() && value.query.length <= MAX_QUERY_LENGTH)
        require(value.candidates.size <= MAX_RESULTS)
        require(value.attribution.length <= MAX_ATTRIBUTION_LENGTH)
        return JSONObject().put("version", VERSION).put("requestId", value.requestId)
            .put("query", value.query).put("candidates", JSONArray().apply {
                value.candidates.forEach { put(candidateToJson(it)) }
            }).put("attribution", value.attribution).put("completedAtMs", value.completedAtMs)
    }

    fun resultFromJson(json: JSONObject): DestinationSearchResult {
        version(json)
        val array = json.optJSONArray("candidates") ?: error("missing candidates")
        require(array.length() <= MAX_RESULTS) { "too many candidates" }
        return DestinationSearchResult(
            string(json, "requestId", MAX_REQUEST_ID_LENGTH), string(json, "query", MAX_QUERY_LENGTH),
            (0 until array.length()).map { candidate(array.optJSONObject(it) ?: error("invalid candidate")) },
            stringAllowEmpty(json, "attribution", MAX_ATTRIBUTION_LENGTH), long(json, "completedAtMs")
        )
    }

    fun errorToJson(value: DestinationSearchError): JSONObject {
        require(value.requestId.length in 1..MAX_REQUEST_ID_LENGTH)
        require(value.message.length <= MAX_ERROR_LENGTH)
        return JSONObject()
        .put("version", VERSION).put("requestId", value.requestId).put("code", value.code.wireName)
        .put("message", value.message).put("retryable", value.retryable).put("timestampMs", value.timestampMs)
    }

    fun errorFromJson(json: JSONObject): DestinationSearchError {
        version(json)
        return DestinationSearchError(
            string(json, "requestId", MAX_REQUEST_ID_LENGTH),
            DestinationSearchErrorCode.fromWireName(string(json, "code", 64)) ?: error("invalid code"),
            stringAllowEmpty(json, "message", MAX_ERROR_LENGTH), boolean(json, "retryable"), long(json, "timestampMs")
        )
    }

    fun cancellationToJson(value: DestinationSearchCancellation): JSONObject {
        require(value.requestId.length in 1..MAX_REQUEST_ID_LENGTH)
        require(value.reason == null || value.reason.length <= MAX_REASON_LENGTH)
        return JSONObject()
        .put("version", VERSION).put("requestId", value.requestId).put("cancelledAtMs", value.cancelledAtMs)
        .put("reason", value.reason ?: JSONObject.NULL)
    }

    fun cancellationFromJson(json: JSONObject): DestinationSearchCancellation {
        version(json)
        val reason = if (json.isNull("reason")) null else stringAllowEmpty(json, "reason", MAX_REASON_LENGTH)
        return DestinationSearchCancellation(string(json, "requestId", MAX_REQUEST_ID_LENGTH), long(json, "cancelledAtMs"), reason)
    }

    private fun candidateToJson(value: DestinationSearchCandidate): JSONObject {
        require(value.displayName.isNotBlank() && value.displayName.length <= MAX_LABEL_LENGTH)
        require(value.secondaryText.length <= MAX_SECONDARY_LENGTH)
        return JSONObject().put("displayName", value.displayName).put("secondaryText", value.secondaryText)
            .put("point", pointToJson(value.point)).put("kind", value.kind.wireName)
    }

    private fun candidate(json: JSONObject) = DestinationSearchCandidate(
        string(json, "displayName", MAX_LABEL_LENGTH), stringAllowEmpty(json, "secondaryText", MAX_SECONDARY_LENGTH),
        point(json, "point"), DestinationSearchCandidateKind.fromWireName(string(json, "kind", 32)) ?: error("invalid kind")
    )

    private fun version(json: JSONObject) = require(int(json, "version") == VERSION) { "unsupported version" }
    private fun pointToJson(p: GeoPoint) = JSONObject().put("lat", p.lat).put("lon", p.lon)
    private fun point(json: JSONObject, key: String): GeoPoint {
        val p = json.optJSONObject(key) ?: error("missing $key")
        return GeoPoint(double(p, "lat"), double(p, "lon"))
    }
    private fun string(json: JSONObject, key: String, max: Int) =
        stringAllowEmpty(json, key, max).also { require(it.isNotBlank()) { "missing $key" } }
    private fun stringAllowEmpty(json: JSONObject, key: String, max: Int): String {
        val value = json.opt(key) as? String ?: error("missing $key")
        require(value.length <= max) { "$key too long" }; return value
    }
    private fun long(json: JSONObject, key: String): Long {
        val n = json.opt(key) as? Number ?: error("missing $key"); val v = n.toLong()
        require(n.toDouble() == v.toDouble()) { "invalid $key" }; return v
    }
    private fun int(json: JSONObject, key: String) = long(json, key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun double(json: JSONObject, key: String): Double = (json.opt(key) as? Number)?.toDouble()
        ?.takeIf { it.isFinite() } ?: error("invalid $key")
    private fun boolean(json: JSONObject, key: String) = json.opt(key) as? Boolean ?: error("invalid $key")
}
