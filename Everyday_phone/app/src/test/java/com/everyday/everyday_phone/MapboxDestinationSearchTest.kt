package com.everyday.everyday_phone

import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.transport.DestinationSearchCandidateKind
import com.everyday.shared.transport.DestinationSearchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapboxDestinationSearchTest {
    private val origin = GeoPoint(48.8000, 2.3000)

    @Test fun `classifier folds accents and recognizes French and English stations`() {
        assertEquals(DestinationQueryKind.CATEGORY, DestinationQueryClassifier.classify("TRAIN STATION").kind)
        assertEquals("train_station", DestinationQueryClassifier.classify("Gare ferroviaire").categoryId)
        assertEquals(DestinationQueryKind.ADDRESS, DestinationQueryClassifier.classify("115 allée des commerces").kind)
        assertEquals("115 allee des commerces", DestinationSearchText.matchKey("115 Allée des Commerces"))
    }

    @Test fun `URLs use the correct endpoint and proximity`() {
        val provider = MapboxDestinationSearchProvider("pk.test")
        val address = DestinationSearchRequest("1", "115 Allée des Commerces", origin, "fr-FR", 1L)
        val category = address.copy(query = "train station")
        assertTrue(provider.buildUrl(address, DestinationQueryClassifier.classify(address.query)).contains("geocode/v6/forward"))
        val categoryUrl = provider.buildUrl(category, DestinationQueryClassifier.classify(category.query))
        assertTrue(categoryUrl.contains("category/train_station"))
        assertTrue(categoryUrl.contains("proximity=2.3%2C48.8"))
    }

    @Test fun `parser prioritizes the nearest full accent-insensitive address and caps results`() {
        val features = (0 until 12).joinToString(",") { index ->
            val lat = if (index == 11) 48.8001 else 48.81 + index * .001
            """{"type":"Feature","id":"id-$index","geometry":{"coordinates":[2.3,$lat]},"properties":{"mapbox_id":"id-$index","name":"115 Allée des Commerces","full_address":"115 Allée des Commerces, France","feature_type":"address"}}"""
        }
        val provider = MapboxDestinationSearchProvider("pk.test")
        val request = DestinationSearchRequest("1", "115 allee des commerces", origin, "fr", 1L)
        val response = provider.parseAndRank("""{"type":"FeatureCollection","features":[$features],"attribution":"Mapbox"}""", request,
            DestinationQueryClassifier.classify(request.query))
        assertEquals(10, response.candidates.size)
        assertEquals(48.8001, response.candidates.first().point.lat, .000001)
        assertEquals(DestinationSearchCandidateKind.ADDRESS, response.candidates.first().kind)
    }

    @Test fun `far matches remain eligible without the former walking range filter`() {
        val provider = MapboxDestinationSearchProvider("pk.test")
        val request = DestinationSearchRequest("1", "station", origin, "en", 1L)
        val response = provider.parseAndRank(
            """{"features":[{"geometry":{"coordinates":[4.0,50.0]},"properties":{"name":"Far Station","mapbox_id":"far"}}]}""",
            request, DestinationQueryClassifier.classify(request.query))
        assertEquals(1, response.candidates.size)
        assertEquals("Far Station", response.candidates.single().displayName)
        assertTrue(!response.outsideWalkingRange)
    }
}
