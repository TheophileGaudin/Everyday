package com.everyday.shared.sync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Phone-side OSM proxy for glasses that have Bluetooth but no direct internet. */
object MapDataProvider {
    fun fetch(lat: Double, lon: Double): MapSnapshot {
        val q = """
            [out:json][timeout:12];
            (way(around:400,$lat,$lon)["highway"]["highway"!~"construction|proposed|platform|corridor|elevator"];);out geom;
            (nw(around:8000,$lat,$lon)["railway"="station"];
             nw(around:1700,$lat,$lon)["shop"="supermarket"];
             nw(around:1700,$lat,$lon)["amenity"~"^(kindergarten|school|college|university)${'$'}"];
             nw(around:400,$lat,$lon)["amenity"~"^(hospital|police|bus_station|ferry_terminal|place_of_worship|cafe|restaurant|pharmacy|fuel|library|marketplace)${'$'}"];
             nw(around:400,$lat,$lon)["historic"~"^(cathedral|church|monument|castle)${'$'}"];
             nw(around:400,$lat,$lon)["shop"];
             nw(around:400,$lat,$lon)["tourism"~"^(museum|attraction|viewpoint)${'$'}"];
            );out center;
        """.trimIndent()
        var failure: Throwable? = null
        for (endpoint in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            try { return parse(post(endpoint, q), lat, lon) } catch (t: Throwable) { failure = t }
        }
        throw failure ?: IllegalStateException("No Overpass endpoint")
    }

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
