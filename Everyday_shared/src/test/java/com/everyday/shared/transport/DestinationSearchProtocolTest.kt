package com.everyday.shared.transport

import com.everyday.shared.routing.GeoPoint
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationSearchProtocolTest {
    @Test fun `search message family round trips with ten candidates`() {
        val request = DestinationSearchRequest("search-1", "115 Allée des Commerces", GeoPoint(48.8, 2.3), "fr-FR", 1L)
        val candidates = (1..10).map {
            DestinationSearchCandidate("Gare $it", "Rue $it", GeoPoint(48.8 + it / 10_000.0, 2.3), DestinationSearchCandidateKind.POI)
        }
        val messages = listOf(
            TransportMessage.DestinationSearchRequestMessage(request),
            TransportMessage.DestinationSearchResultMessage(DestinationSearchResult("search-1", request.query, candidates, "© Mapbox", 2L)),
            TransportMessage.DestinationSearchErrorMessage(DestinationSearchError("search-1", DestinationSearchErrorCode.RATE_LIMITED, "slow down", true, 3L)),
            TransportMessage.DestinationSearchCancellationMessage(DestinationSearchCancellation("search-1", 4L, "replaced"))
        )
        messages.forEach { message ->
            val decoded = TransportProtocol.decode(TransportProtocol.encode(message))
            assertTrue(decoded is TransportDecodeResult.Success)
            assertEquals(message, (decoded as TransportDecodeResult.Success).message)
        }
    }

    @Test fun `result rejects more than ten candidates`() {
        val json = DestinationSearchProtocol.resultToJson(
            DestinationSearchResult("id", "q", emptyList(), "", 1L)
        ).put("candidates", JSONArray().apply {
            repeat(11) { put(org.json.JSONObject().put("displayName", "x").put("secondaryText", "")
                .put("point", org.json.JSONObject().put("lat", 0).put("lon", 0)).put("kind", "poi")) }
        })
        assertTrue(runCatching { DestinationSearchProtocol.resultFromJson(json) }.isFailure)
    }
}
