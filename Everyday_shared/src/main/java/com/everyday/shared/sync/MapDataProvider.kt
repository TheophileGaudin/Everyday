package com.everyday.shared.sync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Phone-side OSM proxy for glasses that have Bluetooth but no direct internet. Overpass first; when
 * it fails, OpenFreeMap's vector tiles ([OpenFreeMapSource]), and Overpass is skipped for a few
 * minutes so each refresh doesn't wait for its timeouts.
 */
object MapDataProvider {
    private const val OVERPASS_RETRY_AFTER_MS = 5 * 60 * 1000L
    @Volatile private var overpassDownUntilMs = 0L

    fun fetch(
        lat: Double,
        lon: Double,
        poiMode: String? = null,
        poiCategories: Set<String> = emptySet(),
        poiRadiusM: Int? = null,
        importantCategories: Set<String> = emptySet(),
        importantRadiiM: Map<String, Int> = emptyMap(),
        normalCategories: Set<String> = emptySet()
    ): MapSnapshot {
        val poiQuery = if (poiMode.equals("DEFAULT", ignoreCase = true) &&
            (importantCategories.isNotEmpty() || normalCategories.isNotEmpty())
        ) {
            buildDefaultPoiQuery(lat, lon, importantCategories, importantRadiiM, normalCategories)
        } else if (poiCategories.isNotEmpty() && poiRadiusM != null) {
            poiCategories.sorted().mapNotNull { category ->
                val selector = selectorFor(category)
                selector?.let { "nw(around:$poiRadiusM,$lat,$lon)$it;" }
            }.joinToString("\n")
        } else {
            // Keep the phone proxy's default request aligned with the legacy glasses query.
            """
             nw(around:8000,$lat,$lon)["railway"="station"];
             nw(around:1700,$lat,$lon)["shop"="supermarket"];
             nw(around:1700,$lat,$lon)["amenity"~"^(kindergarten|school|college|university)${'$'}"];
             nw(around:400,$lat,$lon)["amenity"~"^(hospital|police|bus_station|ferry_terminal|place_of_worship|cafe|restaurant|pharmacy|fuel|library|marketplace)${'$'}"];
             nw(around:400,$lat,$lon)["historic"~"^(cathedral|church|monument|castle)${'$'}"];
             nw(around:400,$lat,$lon)["shop"];
             nw(around:400,$lat,$lon)["tourism"~"^(museum|attraction|viewpoint)${'$'}"];
            """.trimIndent()
        }
        val q = """
            [out:json][timeout:12];
            (way(around:400,$lat,$lon)["highway"]["highway"!~"construction|proposed|platform|corridor|elevator"];);out geom;
            ($poiQuery
            );out center;
        """.trimIndent()
        val requests = tileRequests(poiMode, poiCategories, poiRadiusM, importantCategories, importantRadiiM, normalCategories)
        if (System.currentTimeMillis() < overpassDownUntilMs) return OpenFreeMapSource.fetch(lat, lon, requests)
        var failure: Throwable? = null
        for (endpoint in OVERPASS_ENDPOINTS) {
            try { return parse(post(endpoint, q), lat, lon) } catch (t: Throwable) { failure = t }
        }
        overpassDownUntilMs = System.currentTimeMillis() + OVERPASS_RETRY_AFTER_MS
        return try {
            OpenFreeMapSource.fetch(lat, lon, requests)
        } catch (t: Throwable) {
            failure?.let(t::addSuppressed)
            throw t
        }
    }

    /** The public Overpass servers tried in turn. */
    val OVERPASS_ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    /** The same places as the Overpass query, as tile requests: the category ids and their radii. */
    private fun tileRequests(
        poiMode: String?,
        poiCategories: Set<String>,
        poiRadiusM: Int?,
        importantCategories: Set<String>,
        importantRadiiM: Map<String, Int>,
        normalCategories: Set<String>
    ): List<OpenFreeMapSource.PoiRequest> = when {
        poiMode.equals("DEFAULT", ignoreCase = true) && (importantCategories.isNotEmpty() || normalCategories.isNotEmpty()) ->
            importantCategories.map { OpenFreeMapSource.PoiRequest(it, importantRadiiM[it] ?: DEFAULT_IMPORTANT_RADIUS_M) } +
                normalCategories.map { OpenFreeMapSource.PoiRequest(it, DEFAULT_NORMAL_RADIUS_M) }
        poiCategories.isNotEmpty() && poiRadiusM != null -> poiCategories.map { OpenFreeMapSource.PoiRequest(it, poiRadiusM) }
        else -> LEGACY_DEFAULT_PLACES
    }

    /** The legacy default query's places and radii. */
    private val LEGACY_DEFAULT_PLACES = listOf(
        OpenFreeMapSource.PoiRequest("station", 8_000),
        OpenFreeMapSource.PoiRequest("supermarket", 1_700),
        OpenFreeMapSource.PoiRequest("school", 1_700),
    ) + listOf(
        "hospital", "police", "bus_station", "ferry_terminal", "place_of_worship", "cafe", "restaurant",
        "pharmacy", "fuel", "library", "marketplace", "historic", "shop", "tourism"
    ).map { OpenFreeMapSource.PoiRequest(it, DEFAULT_NORMAL_RADIUS_M) }

    private fun buildDefaultPoiQuery(
        lat: Double,
        lon: Double,
        importantCategories: Set<String>,
        importantRadiiM: Map<String, Int>,
        normalCategories: Set<String>
    ): String {
        val queries = buildList {
            importantCategories.sorted().forEach { category ->
                selectorFor(category)?.let { selector ->
                    val radius = importantRadiiM[category] ?: DEFAULT_IMPORTANT_RADIUS_M
                    add("nw(around:$radius,$lat,$lon)$selector;")
                }
            }
            normalCategories.sorted().forEach { category ->
                selectorFor(category)?.let { selector ->
                    add("nw(around:$DEFAULT_NORMAL_RADIUS_M,$lat,$lon)$selector;")
                }
            }
        }
        return queries.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: "nw(around:1,$lat,$lon)[\"__everyday_no_poi__\"];"
    }

    private fun selectorFor(category: String): String? = when (category) {
        "station" -> "[\"railway\"=\"station\"]"
        "supermarket" -> "[\"shop\"=\"supermarket\"]"
        "school" -> "[\"amenity\"~\"^(kindergarten|school|college|university)${'$'}\"]"
        "hospital", "police", "bus_station", "ferry_terminal", "place_of_worship",
        "cafe", "restaurant", "pharmacy", "fuel", "library", "marketplace" ->
            "[\"amenity\"=\"$category\"]"
        "shop" -> "[\"shop\"]"
        "tourism" -> "[\"tourism\"]"
        "historic" -> "[\"historic\"]"
        else -> null
    }

    private const val DEFAULT_NORMAL_RADIUS_M = 400
    private const val DEFAULT_IMPORTANT_RADIUS_M = 1_609

    private fun post(endpoint: String, query: String): JSONObject {
        val con = URL(endpoint).openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.connectTimeout = 10_000; con.readTimeout = 15_000; con.doOutput = true
        con.setRequestProperty("User-Agent", "Everyday-Phone-MapProxy/1.0")
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        con.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        if (con.responseCode !in 200..299) throw IllegalStateException("Overpass HTTP ${con.responseCode}")
        return JSONObject(con.inputStream.bufferedReader().readText())
    }

    private fun parse(root: JSONObject, lat: Double, lon: Double): MapSnapshot {
        val roads = mutableListOf<MapRoadSnapshot>(); val pois = mutableListOf<MapPoiSnapshot>()
        val elements = root.optJSONArray("elements")
        if (elements != null) for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue; val tags = e.optJSONObject("tags") ?: continue
            val geometry = e.optJSONArray("geometry")
            if (geometry != null && tags.has("highway")) {
                val raw = (0 until geometry.length()).mapNotNull { geometry.optJSONObject(it) }
                // Compact long ways for RFCOMM while retaining endpoints and bends.
                val stride = (raw.size / 40).coerceAtLeast(1)
                val points = raw.filterIndexed { index, _ -> index % stride == 0 || index == raw.lastIndex }
                    .map { MapPointSnapshot(it.optDouble("lat"), it.optDouble("lon")) }
                if (points.size > 1) roads += MapRoadSnapshot(e.optLong("id"), tags.optString("highway"), tags.optString("name").ifBlank { null }, points)
            } else {
                val pos = e.optJSONObject("center") ?: e
                val category = when { tags.optString("railway") == "station" -> "railway=station"; tags.has("historic") -> "historic=${tags.optString("historic")}"; tags.has("amenity") -> "amenity=${tags.optString("amenity")}"; tags.has("shop") -> "shop=${tags.optString("shop")}"; tags.has("tourism") -> "tourism=${tags.optString("tourism")}"; else -> null } ?: continue
                val important = category == "railway=station" || category == "shop=supermarket" || category in setOf("amenity=kindergarten", "amenity=school", "amenity=college", "amenity=university")
                pois += MapPoiSnapshot(e.optLong("id"), tags.optString("name").ifBlank { null }, category, important, MapPointSnapshot(pos.optDouble("lat"), pos.optDouble("lon")))
            }
        }
        return MapSnapshot(MapPointSnapshot(lat, lon), roads, pois)
    }
}
