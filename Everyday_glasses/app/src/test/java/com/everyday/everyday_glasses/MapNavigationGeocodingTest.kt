package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.widgets.map.backend.GeocodingCandidate
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingException
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingFailureKind
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingRanker
import com.everyday.everyday_glasses.widgets.map.backend.GeocodingResolution
import com.everyday.everyday_glasses.widgets.map.backend.MapGeoPoint
import com.everyday.everyday_glasses.widgets.map.backend.MapsCoForwardGeocoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MapNavigationGeocodingTest {
    private val origin = MapGeoPoint(51.5000, -0.1250)

    @Test
    fun mapsCoResponseParsesValidCoordinatesAndAddressText() {
        val candidates = MapsCoForwardGeocoder.parseResponse(
            """
            [{
              "lat": "51.5014",
              "lon": "-0.1419",
              "display_name": "Westminster Abbey, London",
              "importance": 0.82,
              "type": "place_of_worship",
              "address": {"road": "Deans Yard", "city": "London", "country": "United Kingdom"}
            }]
            """.trimIndent()
        )

        assertEquals(1, candidates.size)
        assertEquals(51.5014, candidates.single().point.lat, 0.000001)
        assertEquals("Deans Yard, London, United Kingdom", candidates.single().addressText)
        assertEquals("place_of_worship", candidates.single().type)
    }

    @Test
    fun emptyResponseIsNoResultsAndMalformedResponsesAreRejected() {
        assertTrue(MapsCoForwardGeocoder.parseResponse("[]").isEmpty())

        val malformed = runCatching {
            MapsCoForwardGeocoder.parseResponse("[{\"lat\":\"not-a-coordinate\"}]")
        }.exceptionOrNull()
        assertTrue(malformed is GeocodingException)
        assertEquals(GeocodingFailureKind.MALFORMED_RESPONSE, (malformed as GeocodingException).kind)
    }

    @Test
    fun networkFailureIsClassifiedWithoutTouchingTheMap() {
        val geocoder = MapsCoForwardGeocoder(fetchResponse = { throw IOException("offline") })
        val failure = geocoder.search("10 Downing Street", origin).exceptionOrNull()

        assertTrue(failure is GeocodingException)
        assertEquals(GeocodingFailureKind.NETWORK, (failure as GeocodingException).kind)
    }

    @Test
    fun addressIsNormalizedAndSafelyEncodedWithCurrentLocation() {
        val geocoder = MapsCoForwardGeocoder(apiKey = "key with/slash")
        val url = geocoder.buildUrl("  10\u00a0Downing   Street, London ", origin)

        assertTrue(url.contains("q=10+Downing+Street%2C+London"))
        assertTrue(url.contains("limit=50"))
        assertTrue(url.contains("lat=51.500000"))
        assertTrue(url.contains("lon=-0.125000"))
        assertTrue(url.contains("api_key=key+with%2Fslash"))
    }

    @Test
    fun houseNumberMatchesPreferTheClosestLocalResult() {
        val ranked = GeocodingRanker.rank(
            "12, Rue Magellan",
            listOf(
                candidate("12 Rue Magellan", 51.5000, -0.5000),
                candidate("12 Rue Magellan, Le Havre", 51.5005, -0.1250)
            ),
            origin
        )

        assertEquals("12 Rue Magellan, Le Havre", ranked.first().candidate.displayName)
    }

    @Test
    fun unaccentedLowercaseAddressMatchesAccentedDisplayAddress() {
        val ranked = GeocodingRanker.rank(
            "115 allee des commerces",
            listOf(
                candidate("115 Allée des Commerces", 51.5002, -0.1250),
                candidate("115 Avenue du Commerce", 51.5001, -0.1250)
            ), origin
        )
        assertEquals("115 Allée des Commerces", ranked.first().candidate.displayName)
    }

    @Test
    fun dominantTextMatchIsAcceptedButNearbySimilarResultsStayAmbiguous() {
        val dominant = GeocodingRanker.resolve(
            "Central Library",
            listOf(
                candidate("Central Library", 51.501, -0.125),
                candidate("Central Library Annex", 51.501, -0.1251)
            ),
            origin
        )
        assertTrue(dominant is GeocodingResolution.Accepted)
        assertEquals("Central Library", (dominant as GeocodingResolution.Accepted).candidate.label)

        val ambiguous = GeocodingRanker.resolve(
            "Central Library",
            listOf(
                candidate("Central Library North", 51.5008, -0.125),
                candidate("Central Library South", 51.4992, -0.125),
                candidate("Central Library East", 51.5000, -0.123)
            ),
            origin
        )
        assertTrue(ambiguous is GeocodingResolution.Ambiguous)
        assertEquals(3, (ambiguous as GeocodingResolution.Ambiguous).choices.size)
    }

    @Test
    fun ambiguityExposesUpToTenChoicesAndUsesProximityAsTieBreaker() {
        val candidates = (0 until 12).map { index ->
            candidate("Cafe ${index + 1}", 51.5000 + index * 0.0001, -0.1250)
        }
        val ranked = GeocodingRanker.rank("Cafe", candidates, origin)

        assertEquals(12, ranked.size)
        assertEquals("Cafe 1", ranked.first().label)
        val resolution = GeocodingRanker.resolve("Cafe", candidates, origin)
        assertTrue(resolution is GeocodingResolution.Ambiguous)
        assertEquals(GeocodingRanker.MAX_CHOICES, (resolution as GeocodingResolution.Ambiguous).choices.size)
    }

    @Test
    fun nearbyRegionalSpellingMatchBeatsDistantGenericMatches() {
        val ely = MapGeoPoint(52.399, 0.266)
        val resolution = GeocodingRanker.resolve(
            "Hive leisure center",
            listOf(
                candidate("The Hive Leisure Centre, Leisure Park, Ely", 52.401, 0.270),
                candidate("The Hive", 51.507, -0.128),
                candidate("Hive Leisure Center", 53.480, -2.242)
            ),
            ely
        )

        assertTrue(resolution is GeocodingResolution.Accepted)
        assertEquals(
            "The Hive Leisure Centre, Leisure Park, Ely",
            (resolution as GeocodingResolution.Accepted).candidate.candidate.displayName
        )
    }

    private fun candidate(name: String, lat: Double, lon: Double) =
        GeocodingCandidate(MapGeoPoint(lat, lon), name)
}
