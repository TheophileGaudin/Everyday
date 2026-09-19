package com.everyday.everyday_glasses.map

import com.everyday.everyday_glasses.interaction.assistant.AssistantSemanticFact
import com.everyday.everyday_glasses.ui.widgets.common.PresentationExpiryPolicy
import com.everyday.everyday_glasses.ui.widgets.common.PresentationMode
import com.everyday.everyday_glasses.ui.widgets.common.PresentationSpec
import com.everyday.shared.routing.RouteFailure
import com.everyday.shared.routing.GuidanceFixStatus
import com.everyday.shared.routing.LiveGuidanceSnapshot
import com.everyday.shared.routing.RouteResult
import org.json.JSONArray
import org.json.JSONObject
import com.everyday.shared.transport.DestinationTextMatcher
import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The explicit lifecycle of a local walking-navigation request. */
enum class NavigationStatus {
    INACTIVE,
    SEARCHING,
    CHOOSING,
    RESOLVING,
    CALCULATING,
    RECALCULATING,
    ACTIVE,
    DEGRADED,
    ERROR,
    ARRIVED
}

internal fun destinationIndicatorIsOffscreen(
    distanceMeters: Float,
    mapRadiusMeters: Float
): Boolean = distanceMeters.isFinite() && mapRadiusMeters.isFinite() &&
    mapRadiusMeters > 0f && distanceMeters > mapRadiusMeters

data class NavigationDestination(
    val address: String,
    val label: String,
    val point: MapGeoPoint
)

data class StartNavigationRequest(
    val address: String,
    val mode: String,
    val presentation: PresentationSpec? = null
)

object NavigationPresentationDefaults {
    val VOICE_ROUTE = PresentationSpec(
        mode = PresentationMode.FULLSCREEN,
        durationSeconds = 10,
        onExpiry = PresentationExpiryPolicy.LEAVE_WINDOWED
    )
}

/**
 * State held by the map rather than inferred from drawing.  The route remains present while
 * guidance is degraded or a replacement route is being calculated so the old geometry stays
 * visible until a valid result can be installed atomically.
 */
data class NavigationState(
    val status: NavigationStatus = NavigationStatus.INACTIVE,
    val query: String? = null,
    val destination: NavigationDestination? = null,
    val route: RouteResult? = null,
    val errorMessage: String? = null,
    val choices: List<RankedGeocodingCandidate> = emptyList(),
    val guidance: LiveGuidanceSnapshot? = null
) {
    val walkingEtaSeconds: Long?
        get() = guidance?.remainingWalkingTimeSeconds?.takeIf { it >= 0L }
            ?: route?.estimatedWalkingTimeSeconds?.takeIf { it >= 0L }
            ?: route?.let { ceil(it.totalDistanceMeters / WALKING_SPEED_MPS).toLong() }

    val remainingDistanceMeters: Double?
        get() = guidance?.remainingDistanceMeters ?: route?.totalDistanceMeters

    val nextManeuverInstruction: String?
        get() = guidance?.nextManeuver?.instruction

    val distanceToNextManeuverMeters: Double?
        get() = guidance?.distanceToNextManeuverMeters

    val firstManeuver: String?
        get() = nextManeuverInstruction ?: route?.maneuvers?.firstOrNull()?.instruction

    fun toAssistantSemanticFacts(): List<AssistantSemanticFact> = listOf(
        AssistantSemanticFact("navigation_status", status.name),
        AssistantSemanticFact("navigation_destination", destination?.label.orEmpty()),
        AssistantSemanticFact("navigation_route_status", status.name),
        AssistantSemanticFact("navigation_eta_seconds", walkingEtaSeconds?.toString().orEmpty()),
        AssistantSemanticFact(
            "navigation_remaining_distance_meters",
            remainingDistanceMeters?.toString().orEmpty()
        ),
        AssistantSemanticFact("navigation_next_maneuver", firstManeuver.orEmpty()),
        AssistantSemanticFact(
            "navigation_distance_to_maneuver_meters",
            distanceToNextManeuverMeters?.toString().orEmpty()
        )
    )

    companion object {
        const val WALKING_SPEED_MPS = 1.4
    }
}

sealed class NavigationOperationResult {
    data class Active(val state: NavigationState) : NavigationOperationResult()
    data class Ambiguous(val state: NavigationState) : NavigationOperationResult()
    data class Failure(val state: NavigationState, val message: String) : NavigationOperationResult()
}

enum class GeocodingFailureKind {
    NETWORK,
    MALFORMED_RESPONSE,
    INVALID_QUERY
}

class GeocodingException(
    val kind: GeocodingFailureKind,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/** A single maps.co forward-search result after its coordinates and label have been validated. */
data class GeocodingCandidate(
    val point: MapGeoPoint,
    val displayName: String,
    val importance: Double? = null,
    val type: String? = null,
    val addressText: String? = null,
    val sourceIndex: Int = 0
) {
    fun conciseLabel(maxLength: Int = 54): String {
        val compact = displayName.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxLength) compact else compact.take(maxLength - 3).trimEnd() + "..."
    }
}

data class RankedGeocodingCandidate(
    val candidate: GeocodingCandidate,
    val textualScore: Double,
    val proximityMeters: Double,
    val score: Double
) {
    val point: MapGeoPoint get() = candidate.point
    val label: String get() = candidate.conciseLabel()
}

sealed class GeocodingResolution {
    data class Accepted(val candidate: RankedGeocodingCandidate) : GeocodingResolution()
    data class Ambiguous(val choices: List<RankedGeocodingCandidate>) : GeocodingResolution()
    data object NoResults : GeocodingResolution()
}

/**
 * Normalizes user-entered addresses without changing their meaning.  This is also used before
 * URL encoding so repeated spaces and non-breaking whitespace do not create different searches.
 */
object NavigationAddressText {
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Textual match remains important while a meaningful location bias keeps local matches useful. */
object GeocodingRanker {
    const val MAX_CHOICES = 10
    private const val DOMINANT_MARGIN = 13.0
    private const val STRONG_TEXT_SCORE = 0.92
    private const val LOCATION_BIAS_WEIGHT = 48.0

    fun rank(
        query: String,
        candidates: List<GeocodingCandidate>,
        currentLocation: MapGeoPoint
    ): List<RankedGeocodingCandidate> {
        val normalizedQuery = DestinationTextMatcher.normalize(query)
        val queryTokens = normalizedQuery.split(' ').filter(String::isNotBlank).toSet()
        val isAddressQuery = queryTokens.size >= 2 && queryTokens.any { token ->
            token.any(Char::isDigit)
        }
        return candidates.map { candidate ->
            val textMatch = DestinationTextMatcher.match(query, candidate.displayName, candidate.addressText)
            val tokenCoverage = textMatch.tokenCoverage
            val textual = textMatch.score
            val distance = distanceMeters(currentLocation, candidate.point)
            val proximity = 1.0 / (1.0 + distance / 1_000.0)
            val importance = candidate.importance?.coerceIn(0.0, 1.0) ?: 0.0
            // For a house-number query, candidates containing every address token are
            // materially equivalent textual matches. Prefer the one nearest the user before
            // letting provider importance or result ordering decide between them.
            val fullAddressMatch = isAddressQuery && tokenCoverage >= 1.0
            val score = if (fullAddressMatch) {
                1_000.0 + proximity * 100.0 + textual * 10.0 + importance * 3.0
            } else {
                textual * 100.0 + proximity * LOCATION_BIAS_WEIGHT + importance * 3.0
            }
            RankedGeocodingCandidate(candidate, textual, distance, score)
        }.sortedWith(
            compareByDescending<RankedGeocodingCandidate> { it.score }
                .thenBy { it.proximityMeters }
                .thenBy { it.candidate.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.candidate.sourceIndex }
        )
    }

    fun resolve(
        query: String,
        candidates: List<GeocodingCandidate>,
        currentLocation: MapGeoPoint,
        maxChoices: Int = MAX_CHOICES
    ): GeocodingResolution {
        require(maxChoices > 0) { "maxChoices must be positive" }
        val ranked = rank(query, candidates, currentLocation)
        if (ranked.isEmpty()) return GeocodingResolution.NoResults
        val top = ranked.first()
        val second = ranked.getOrNull(1)
        val clearlyDominant = second == null ||
            top.score - second.score >= DOMINANT_MARGIN ||
            (top.textualScore >= STRONG_TEXT_SCORE &&
                top.textualScore - second.textualScore >= 0.20)
        return if (clearlyDominant) {
            GeocodingResolution.Accepted(top)
        } else {
            GeocodingResolution.Ambiguous(ranked.take(maxChoices))
        }
    }

    private fun distanceMeters(a: MapGeoPoint, b: MapGeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = (sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon)
            .coerceIn(0.0, 1.0)
        return 2.0 * 6_371_008.8 * asin(sqrt(h))
    }
}

internal fun navigationFailureMessage(failure: RouteFailure): String = when (failure) {
    is RouteFailure.OverLimit ->
        "That destination is ${formatKilometres(failure.straightLineDistanceMeters)} away and exceeds the routing provider limit."
    is RouteFailure.NoNearbyGraphNode ->
        "No walkable road was found near the ${failure.endpoint.name.lowercase(Locale.ROOT)}."
    is RouteFailure.DisconnectedGraph ->
        "The walking roads near the destination are not connected to the current location."
    is RouteFailure.MalformedResponse -> "The walking route data was malformed."
    is RouteFailure.NetworkFailure -> "The walking route could not be downloaded."
    is RouteFailure.Timeout -> "The walking route request timed out."
    is RouteFailure.AuthorizationFailure -> "The walking route service rejected the request."
    is RouteFailure.UnsupportedMode -> "Only WALKING navigation is supported."
    is RouteFailure.InvalidRequest -> "The walking route request was invalid."
}

private fun formatKilometres(metres: Double): String =
    if (metres >= 1_000.0) String.format(Locale.US, "%.1f km", metres / 1_000.0)
    else "${metres.toInt().coerceAtLeast(0)} m"

internal fun formatWalkingEta(seconds: Long?): String {
    if (seconds == null) return "ETA unavailable"
    val minutes = ((seconds + 59L) / 60L).coerceAtLeast(1L)
    return if (minutes < 60L) "${minutes} min walk" else "${minutes / 60L} h ${minutes % 60L} min walk"
}

internal fun formatGuidanceDistance(metres: Double?): String {
    if (metres == null || !metres.isFinite()) return "distance unavailable"
    val safe = metres.coerceAtLeast(0.0)
    return if (safe >= 1_000.0) {
        String.format(Locale.US, "%.1f km", safe / 1_000.0)
    } else {
        "${safe.roundToInt()} m"
    }
}

data class NavigationBannerText(
    val title: String,
    val maneuverLine: String,
    val progressLine: String
)

internal fun NavigationState.toCompactBannerText(): NavigationBannerText {
    val title = destination?.label ?: query ?: "Navigation"
    val guidance = guidance
    val maneuverText = guidance?.nextManeuver?.instruction ?: firstManeuver ?: "Walking route active"
    val maneuverDistance = guidance?.distanceToNextManeuverMeters?.let(::formatGuidanceDistance)
    val remainingText = formatGuidanceDistance(remainingDistanceMeters)
    val etaText = walkingEtaSeconds?.let(::formatWalkingEta) ?: "ETA unavailable"
    val maneuver = listOfNotNull(maneuverText, maneuverDistance).joinToString(" • ")
    val statusLine = when (status) {
        NavigationStatus.SEARCHING -> "Searching nearby…"
        NavigationStatus.CHOOSING -> "Choose a destination"
        NavigationStatus.RESOLVING -> "Finding destination…"
        NavigationStatus.CALCULATING -> "Calculating walking route…"
        NavigationStatus.RECALCULATING -> listOf("Recalculating route", maneuver).joinToString(" • ")
        NavigationStatus.ACTIVE -> maneuver
        NavigationStatus.DEGRADED -> listOfNotNull(
            errorMessage?.takeIf { it.isNotBlank() } ?: when (guidance?.lastFixStatus) {
                    GuidanceFixStatus.STALE -> "GPS stale"
                    GuidanceFixStatus.INACCURATE -> "GPS accuracy poor"
                    GuidanceFixStatus.IMPOSSIBLE -> "GPS jump ignored"
                    GuidanceFixStatus.OFF_ROUTE -> "Off route"
                    else -> "GPS degraded"
                },
            maneuver
        ).joinToString(" • ")
        NavigationStatus.ARRIVED -> "Arrived"
        NavigationStatus.ERROR -> errorMessage ?: "Navigation error"
        NavigationStatus.INACTIVE -> ""
    }
    val progress = when (status) {
        NavigationStatus.ACTIVE,
        NavigationStatus.DEGRADED,
        NavigationStatus.RECALCULATING -> "$remainingText left • $etaText"
        NavigationStatus.ARRIVED -> "Destination reached"
        NavigationStatus.ERROR -> "Route remains visible"
        else -> ""
    }
    return NavigationBannerText(title, statusLine, progress)
}

internal fun NavigationState.toCompactJson(): JSONObject = JSONObject()
    .put("status", status.name)
    .put("destination", destination?.label ?: JSONObject.NULL)
    .put("eta_seconds", walkingEtaSeconds ?: JSONObject.NULL)
    .put("first_maneuver", firstManeuver ?: JSONObject.NULL)
    .put("remaining_distance_meters", remainingDistanceMeters ?: JSONObject.NULL)
    .put("distance_to_maneuver_meters", distanceToNextManeuverMeters ?: JSONObject.NULL)
    .put("choices", JSONArray().apply { choices.forEach { put(it.label) } })
