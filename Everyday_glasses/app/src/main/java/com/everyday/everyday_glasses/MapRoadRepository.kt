package com.everyday.everyday_glasses

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Small, dependency-free OpenStreetMap/Overpass cache used by the glasses map. */
class MapRoadRepository(context: Context, private val changed: (List<MapRoad>, List<MapPoi>) -> Unit, private val proxy: (MapGeoPoint) -> Unit) {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean()
    private var center: MapGeoPoint? = null
    private var fetchedAt = 0L
    private var roads = emptyList<MapRoad>()
    private var pois = emptyList<MapPoi>()
    private var preferPhoneProxyUntilMs = 0L
    private var lastPhoneProxyRequestMs = 0L

    fun refresh(point: MapGeoPoint) {
        val now = System.currentTimeMillis()
        val moved = center?.let { distance(it, point) } ?: Float.MAX_VALUE
        if (roads.isNotEmpty() && moved < 45f && now - fetchedAt < 120_000) return
        if (now < preferPhoneProxyUntilMs) {
            requestPhoneProxy(point, now)
            return
        }
        // The phone is the preferred internet route: it remains usable through
        // cellular data even when the glasses have a nominal but unusable Wi-Fi
        // connection.  A direct glasses fetch below is only a parallel fallback.
        requestPhoneProxy(point, now)
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) {
            requestPhoneProxy(point, now)
            return
        }
        if (!busy.compareAndSet(false, true)) return
        executor.execute {
            runCatching { fetch(point) }.onSuccess { (r, p) ->
                center = point; fetchedAt = System.currentTimeMillis(); roads = r; pois = p
                Log.d(TAG, "OSM fetch complete: roads=${r.size} pois=${p.size}")
                main.post { changed(r, p) }
            }.onFailure {
                Log.e(TAG, "OSM fetch failed; requesting phone proxy", it)
                // A glasses network can be marked validated while captive,
                // restricted, or unable to reach Overpass.  Prefer the phone's
                // mobile connectivity for a short period after such a failure.
                preferPhoneProxyUntilMs = System.currentTimeMillis() + 120_000L
                requestPhoneProxy(point, System.currentTimeMillis())
            }
            busy.set(false)
        }
    }

    fun acceptProxy(centerPoint: MapGeoPoint, newRoads: List<MapRoad>, newPois: List<MapPoi>) {
        center = centerPoint; fetchedAt = System.currentTimeMillis(); roads = newRoads; pois = newPois
        changed(newRoads, newPois)
    }

    fun refreshFromPhone(point: MapGeoPoint) {
        // A prior attempt may have happened before RFCOMM connected, so do not
        // let the normal request throttle suppress this connection retry.
        lastPhoneProxyRequestMs = 0L
        requestPhoneProxy(point, System.currentTimeMillis())
    }

    private fun requestPhoneProxy(point: MapGeoPoint, now: Long) {
        if (now - lastPhoneProxyRequestMs < 15_000L) return
        lastPhoneProxyRequestMs = now
        proxy(point)
    }

    private fun fetch(c: MapGeoPoint): Pair<List<MapRoad>, List<MapPoi>> {
        // Stations remain useful from several miles away. Supermarkets and all
        // school levels are fetched to the one-mile importance threshold.
        val q = """
            [out:json][timeout:12];
            (
              way(around:400,${c.lat},${c.lon})["highway"]["highway"!~"construction|proposed|platform|corridor|elevator"];
            );
            out geom;
            (
              nw(around:8000,${c.lat},${c.lon})["railway"="station"];
              nw(around:1700,${c.lat},${c.lon})["shop"="supermarket"];
              nw(around:1700,${c.lat},${c.lon})["amenity"~"^(kindergarten|school|college|university)${'$'}"];
              nw(around:400,${c.lat},${c.lon})["amenity"~"^(hospital|police|bus_station|ferry_terminal|place_of_worship|cafe|restaurant|pharmacy|fuel|library|marketplace)${'$'}"];
              nw(around:400,${c.lat},${c.lon})["historic"~"^(cathedral|church|monument|castle)${'$'}"];
              nw(around:400,${c.lat},${c.lon})["shop"];
              nw(around:400,${c.lat},${c.lon})["tourism"~"^(museum|attraction|viewpoint)${'$'}"];
            );
            out center;
        """.trimIndent()
        var failure: Throwable? = null
        for (endpoint in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
            try { return request(endpoint, q) } catch (t: Throwable) { failure = t }
        }
        throw failure ?: IllegalStateException("No Overpass endpoint")
    }

    private fun request(endpoint: String, query: String): Pair<List<MapRoad>, List<MapPoi>> {
        val con = URL(endpoint).openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.connectTimeout = 10_000; con.readTimeout = 15_000; con.doOutput = true
        con.setRequestProperty("User-Agent", "Everyday-RayNeo-Map/1.0")
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
        con.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        if (con.responseCode !in 200..299) throw IllegalStateException("Overpass HTTP ${con.responseCode}")
        val elements = JSONObject(con.inputStream.bufferedReader().readText()).optJSONArray("elements")
        val rs = mutableListOf<MapRoad>(); val ps = mutableListOf<MapPoi>()
        if (elements != null) for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue; val tags = e.optJSONObject("tags") ?: continue
            val geometry = e.optJSONArray("geometry")
            if (geometry != null && tags.has("highway")) {
                val points = (0 until geometry.length()).mapNotNull { geometry.optJSONObject(it) }.map { MapGeoPoint(it.optDouble("lat"), it.optDouble("lon")) }
                if (points.size > 1) rs += MapRoad(e.optLong("id"), tags.optString("highway"), tags.optString("name").ifBlank { null }, points)
            } else {
                val pos = e.optJSONObject("center") ?: e
                val category = when { tags.optString("railway") == "station" -> "railway=station"; tags.has("historic") -> "historic=${tags.optString("historic")}"; tags.has("amenity") -> "amenity=${tags.optString("amenity")}"; tags.has("shop") -> "shop=${tags.optString("shop")}"; tags.has("tourism") -> "tourism=${tags.optString("tourism")}"; else -> null } ?: continue
                ps += MapPoi(e.optLong("id"), tags.optString("name").ifBlank { null }, category, mapPoiTier(category), MapGeoPoint(pos.optDouble("lat"), pos.optDouble("lon")))
            }
        }
        return rs to ps
    }

    private fun distance(a: MapGeoPoint, b: MapGeoPoint): Float = FloatArray(1).also { Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, it) }[0]

    companion object { private const val TAG = "MapRoadRepository" }
}
