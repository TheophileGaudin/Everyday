package com.everyday.everyday_glasses.widgets.map.backend

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everyday.shared.sync.MapDataProvider
import com.everyday.shared.sync.OpenFreeMapSource
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small, dependency-free OpenStreetMap cache used by the glasses map: Overpass first, OpenFreeMap's
 * vector tiles when Overpass fails (it is then skipped for a few minutes).
 */
class MapRoadRepository(
    context: Context,
    private val changed: (List<MapRoad>, List<MapPoi>) -> Unit,
    private val proxy: (MapGeoPoint, PoiDisplayConfig) -> Unit
) {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean()
    private var center: MapGeoPoint? = null
    private var fetchedAt = 0L
    private var roads = emptyList<MapRoad>()
    private var pois = emptyList<MapPoi>()
    @Volatile private var requestedConfig = PoiDisplayConfig.DEFAULT
    private var fetchedConfig = PoiDisplayConfig.DEFAULT
    private var preferPhoneProxyUntilMs = 0L
    @Volatile private var overpassDownUntilMs = 0L
    private var lastPhoneProxyRequestMs = 0L
    @Volatile private var pendingRefresh: PendingRefresh? = null

    private data class PendingRefresh(val point: MapGeoPoint, val config: PoiDisplayConfig)

    fun refresh(
        point: MapGeoPoint,
        config: PoiDisplayConfig = requestedConfig,
        force: Boolean = false
    ) {
        requestedConfig = config
        val now = System.currentTimeMillis()
        val moved = center?.let { distance(it, point) } ?: Float.MAX_VALUE
        if (!force && roads.isNotEmpty() && fetchedConfig == config && moved < 45f && now - fetchedAt < 120_000) return
        if (now < preferPhoneProxyUntilMs) {
            requestPhoneProxy(point, now, config, force)
            return
        }
        // The phone is the preferred internet route: it remains usable through
        // cellular data even when the glasses have a nominal but unusable Wi-Fi
        // connection.  A direct glasses fetch below is only a parallel fallback.
        requestPhoneProxy(point, now, config, force)
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) {
            requestPhoneProxy(point, now, config, force)
            return
        }
        if (!busy.compareAndSet(false, true)) {
            // A category/radius change while Overpass is in flight used to be lost here. Keep
            // the latest request and start it as soon as the older response is discarded.
            pendingRefresh = PendingRefresh(point, config)
            return
        }
        executor.execute {
            try {
                runCatching { fetch(point, config) }.onSuccess { (r, p) ->
                    if (requestedConfig == config) {
                        center = point
                        fetchedAt = System.currentTimeMillis()
                        roads = r
                        pois = p
                        fetchedConfig = config
                        Log.d(TAG, "OSM fetch complete: roads=${r.size} pois=${p.size}")
                        main.post { changed(r, p) }
                    } else {
                        Log.d(TAG, "Discarding stale OSM response for ${config.mode}")
                    }
                }.onFailure {
                    if (requestedConfig == config) {
                        Log.e(TAG, "OSM fetch failed; requesting phone proxy", it)
                        // A glasses network can be marked validated while captive,
                        // restricted, or unable to reach Overpass.  Prefer the phone's
                        // mobile connectivity for a short period after such a failure.
                        preferPhoneProxyUntilMs = System.currentTimeMillis() + 120_000L
                        requestPhoneProxy(point, System.currentTimeMillis(), config, force = true)
                    }
                }
            } finally {
                busy.set(false)
                val pending = pendingRefresh
                pendingRefresh = null
                pending?.let {
                    main.post { refresh(it.point, it.config, force = true) }
                }
            }
        }
    }

    fun refreshNow(point: MapGeoPoint, config: PoiDisplayConfig = requestedConfig) {
        lastPhoneProxyRequestMs = 0L
        refresh(point, config, force = true)
    }

    fun acceptProxy(
        centerPoint: MapGeoPoint,
        newRoads: List<MapRoad>,
        newPois: List<MapPoi>,
        config: PoiDisplayConfig = requestedConfig
    ) {
        requestedConfig = config
        center = centerPoint; fetchedAt = System.currentTimeMillis(); roads = newRoads; pois = newPois; fetchedConfig = config
        changed(newRoads, newPois)
    }

    fun refreshFromPhone(point: MapGeoPoint, config: PoiDisplayConfig = requestedConfig) {
        requestedConfig = config
        // A prior attempt may have happened before RFCOMM connected, so do not
        // let the normal request throttle suppress this connection retry.
        lastPhoneProxyRequestMs = 0L
        requestPhoneProxy(point, System.currentTimeMillis(), config, force = true)
    }

    private fun requestPhoneProxy(point: MapGeoPoint, now: Long, config: PoiDisplayConfig, force: Boolean = false) {
        if (!force && now - lastPhoneProxyRequestMs < 15_000L) return
        lastPhoneProxyRequestMs = now
        proxy(point, config)
    }

    private fun fetch(c: MapGeoPoint, config: PoiDisplayConfig): Pair<List<MapRoad>, List<MapPoi>> {
        val poiQuery = if (config.isDefault) {
            // Preserve the legacy category-specific radii exactly: stations at 8 km,
            // supermarkets/schools at 1.7 km, and ordinary POIs at 400 m.
            """
              nw(around:8000,${c.lat},${c.lon})["railway"="station"];
              nw(around:1700,${c.lat},${c.lon})["shop"="supermarket"];
              nw(around:1700,${c.lat},${c.lon})["amenity"~"^(kindergarten|school|college|university)$"];
              nw(around:400,${c.lat},${c.lon})["amenity"~"^(hospital|police|bus_station|ferry_terminal|place_of_worship|cafe|restaurant|pharmacy|fuel|library|marketplace)$"];
              nw(around:400,${c.lat},${c.lon})["historic"~"^(cathedral|church|monument|castle)$"];
              nw(around:400,${c.lat},${c.lon})["shop"];
              nw(around:400,${c.lat},${c.lon})["tourism"~"^(museum|attraction|viewpoint)$"];
            """.trimIndent()
        } else if (config.isDefaultMode) {
            buildDefaultModePoiQuery(c, config)
        } else {
            config.categories
                .sorted()
                .joinToString("\n") { category ->
                    val definition = MapPoiCategoryCatalog.definition(category)
                    val selector = when {
                        definition.exactValue != null ->
                            "[\"${definition.overpassTag}\"=\"${definition.exactValue}\"]"
                        definition.valuesRegex != null ->
                            "[\"${definition.overpassTag}\"~\"^(${definition.valuesRegex})$\"]"
                        else -> "[\"${definition.overpassTag}\"]"
                    }
                    "nw(around:${config.radiusM},${c.lat},${c.lon})$selector;"
                }
        }
        val q = """
            [out:json][timeout:12];
            (
              way(around:400,${c.lat},${c.lon})["highway"]["highway"!~"construction|proposed|platform|corridor|elevator"];
            );
            out geom;
            (
              $poiQuery
            );
            out center;
        """.trimIndent()
        if (System.currentTimeMillis() < overpassDownUntilMs) return fetchTiles(c, config)
        var failure: Throwable? = null
        for (endpoint in MapDataProvider.OVERPASS_ENDPOINTS) {
            try { return request(endpoint, q) } catch (t: Throwable) { failure = t }
        }
        Log.w(TAG, "Overpass failed (${failure?.message}); using OpenFreeMap tiles for ${OVERPASS_RETRY_AFTER_MS / 60_000} min")
        overpassDownUntilMs = System.currentTimeMillis() + OVERPASS_RETRY_AFTER_MS
        return fetchTiles(c, config)
    }

    /** The same roads and places from OpenFreeMap's vector tiles. */
    private fun fetchTiles(c: MapGeoPoint, config: PoiDisplayConfig): Pair<List<MapRoad>, List<MapPoi>> {
        val requests = when {
            config.isDefault -> LEGACY_DEFAULT_PLACES
            config.isDefaultMode ->
                config.importantCategories.mapNotNull { category -> config.importantRadiiM[category]?.let { OpenFreeMapSource.PoiRequest(category, it) } } +
                    config.normalCategories.map { OpenFreeMapSource.PoiRequest(it, PoiDisplayConfig.DEFAULT_RADIUS_M) }
            else -> config.categories.map { OpenFreeMapSource.PoiRequest(it, config.radiusM) }
        }
        val snapshot = OpenFreeMapSource.fetch(c.lat, c.lon, requests)
        val roads = snapshot.roads.map { road -> MapRoad(road.id, road.highway, road.name, road.points.map { MapGeoPoint(it.lat, it.lon) }) }
        val pois = snapshot.pois.map { poi -> MapPoi(poi.id, poi.name, poi.category, mapPoiTier(poi.category), MapGeoPoint(poi.point.lat, poi.point.lon)) }
        return roads to pois
    }

    private fun buildDefaultModePoiQuery(c: MapGeoPoint, config: PoiDisplayConfig): String {
        val queries = buildList {
            config.importantCategories.sorted().forEach { category ->
                val radius = config.importantRadiiM[category] ?: return@forEach
                add(overpassPoiQuery(c, category, radius))
            }
            config.normalCategories.sorted().forEach { category ->
                add(overpassPoiQuery(c, category, PoiDisplayConfig.DEFAULT_RADIUS_M))
            }
        }
        return queries.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: "nw(around:1,${c.lat},${c.lon})[\"__everyday_no_poi__\"];"
    }

    private fun overpassPoiQuery(c: MapGeoPoint, category: String, radiusM: Int): String {
        val definition = MapPoiCategoryCatalog.definition(category)
        val selector = when {
            definition.exactValue != null ->
                "[\"${definition.overpassTag}\"=\"${definition.exactValue}\"]"
            definition.valuesRegex != null ->
                "[\"${definition.overpassTag}\"~\"^(${definition.valuesRegex})$\"]"
            else -> "[\"${definition.overpassTag}\"]"
        }
        return "nw(around:$radiusM,${c.lat},${c.lon})$selector;"
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
                val points = (0 until geometry.length()).mapNotNull { geometry.optJSONObject(it) }.map {
                    MapGeoPoint(
                        it.optDouble("lat"),
                        it.optDouble("lon")
                    )
                }
                if (points.size > 1) rs += MapRoad(
                    e.optLong("id"),
                    tags.optString("highway"),
                    tags.optString("name").ifBlank { null },
                    points
                )
            } else {
                val pos = e.optJSONObject("center") ?: e
                val category = when { tags.optString("railway") == "station" -> "railway=station"; tags.has("historic") -> "historic=${tags.optString("historic")}"; tags.has("amenity") -> "amenity=${tags.optString("amenity")}"; tags.has("shop") -> "shop=${tags.optString("shop")}"; tags.has("tourism") -> "tourism=${tags.optString("tourism")}"; else -> null } ?: continue
                ps += MapPoi(
                    e.optLong("id"),
                    tags.optString("name").ifBlank { null },
                    category,
                    mapPoiTier(category),
                    MapGeoPoint(pos.optDouble("lat"), pos.optDouble("lon"))
                )
            }
        }
        return rs to ps
    }

    private fun distance(a: MapGeoPoint, b: MapGeoPoint): Float = FloatArray(1).also { Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, it) }[0]

    companion object {
        private const val TAG = "MapRoadRepository"
        private const val OVERPASS_RETRY_AFTER_MS = 5 * 60 * 1000L

        /** The legacy default query's places and radii (stations 8 km, supermarkets and schools 1.7 km, others 400 m). */
        private val LEGACY_DEFAULT_PLACES = listOf(
            OpenFreeMapSource.PoiRequest(MapPoiCategoryCatalog.STATION, 8_000),
            OpenFreeMapSource.PoiRequest(MapPoiCategoryCatalog.SUPERMARKET, 1_700),
            OpenFreeMapSource.PoiRequest(MapPoiCategoryCatalog.SCHOOL, 1_700),
        ) + (MapPoiCategoryCatalog.supportedIds - setOf(MapPoiCategoryCatalog.STATION, MapPoiCategoryCatalog.SUPERMARKET, MapPoiCategoryCatalog.SCHOOL))
            .map { OpenFreeMapSource.PoiRequest(it, 400) }
    }
}