package com.everyday.shared.sync

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * The map's fallback when Overpass doesn't answer: roads and points of interest read from
 * OpenFreeMap's vector tiles (OpenStreetMap data in the OpenMapTiles schema, free, no key).
 *
 * Detailed tiles (zoom 14) give the roads and the nearby places; coarser tiles (zoom 12) keep only
 * important places, enough for far ones such as stations. Each tile's useful content is cached,
 * so moving around reuses the tiles already read. Tile road classes become OpenStreetMap highway
 * values, and tile places become the app's categories ("railway=station", "amenity=cafe", …),
 * so the map draws them as it draws Overpass data.
 */
object OpenFreeMapSource {
    /** One kind of place to show, within [radiusM] metres. [category] is a POI category id ("cafe", "station", …). */
    data class PoiRequest(val category: String, val radiusM: Int)

    private const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"
    private const val USER_AGENT = "Everyday-Map/1.0"
    private const val DETAIL_ZOOM = 14
    private const val OVERVIEW_ZOOM = 12
    /** Places farther than this come from overview tiles, which keep only the important ones. */
    private const val DETAIL_RADIUS_LIMIT_M = 2_000
    private const val ROAD_RADIUS_M = 400
    private const val MAX_TILES_PER_FETCH = 16
    private const val TEMPLATE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val TILE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val TILE_CACHE_SIZE = 48
    private const val MAX_ROAD_POINTS = 40
    private const val EARTH_RADIUS_M = 6_371_000.0

    private val pool = Executors.newFixedThreadPool(4) { runnable -> Thread(runnable, "OpenFreeMapTiles").apply { isDaemon = true } }

    @Volatile private var template: String? = null
    @Volatile private var templateFetchedAt = 0L

    /** A tile's useful content, already in map coordinates. */
    private class TileContent(val roads: List<MapRoadSnapshot>, val pois: List<MapPoiSnapshot>, val fetchedAt: Long)

    private val cache = object : LinkedHashMap<String, TileContent>(TILE_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TileContent>?): Boolean = size > TILE_CACHE_SIZE
    }

    /** Roads within 400 m of ([lat], [lon]), and the places of [pois], as a map snapshot. */
    fun fetch(lat: Double, lon: Double, pois: List<PoiRequest>): MapSnapshot {
        val center = MapPointSnapshot(lat, lon)
        val detailRadius = (listOf(ROAD_RADIUS_M) + pois.filter { it.radiusM <= DETAIL_RADIUS_LIMIT_M }.map { it.radiusM }).max()
        val farRadius = pois.filter { it.radiusM > DETAIL_RADIUS_LIMIT_M }.maxOfOrNull { it.radiusM }
        val tiles = tilesAround(lat, lon, detailRadius, DETAIL_ZOOM).take(MAX_TILES_PER_FETCH) +
            (farRadius?.let { tilesAround(lat, lon, it, OVERVIEW_ZOOM).take(MAX_TILES_PER_FETCH) } ?: emptyList())
        val contents = tiles.map { tile -> pool.submit(Callable { tile to content(tile) }) }.map { it.get() }

        val roads = contents.filter { it.first.z == DETAIL_ZOOM }.flatMap { it.second.roads }
            .filter { road -> road.points.any { distanceM(center, it) <= ROAD_RADIUS_M * 1.5 } }
        val places = contents.flatMap { (tile, content) ->
            content.pois.filter { poi ->
                val category = categoryId(poi.category) ?: return@filter false
                val distance = distanceM(center, poi.point)
                pois.any { request ->
                    request.category == category && distance <= request.radiusM &&
                        (tile.z == DETAIL_ZOOM) == (request.radiusM <= DETAIL_RADIUS_LIMIT_M)
                }
            }
        }.distinctBy { Triple(it.name, it.category, (it.point.lat * 1e4).toLong() to (it.point.lon * 1e4).toLong()) }
        return MapSnapshot(center, roads, places)
    }

    // ==================== Tiles ====================

    private data class Tile(val z: Int, val x: Int, val y: Int) {
        val key get() = "$z/$x/$y"
    }

    private fun tilesAround(lat: Double, lon: Double, radiusM: Int, z: Int): List<Tile> {
        val dLat = Math.toDegrees(radiusM / EARTH_RADIUS_M)
        val dLon = Math.toDegrees(radiusM / (EARTH_RADIUS_M * cos(Math.toRadians(lat))))
        val (x0, y0) = tileXY(lat + dLat, lon - dLon, z)
        val (x1, y1) = tileXY(lat - dLat, lon + dLon, z)
        // Nearest tiles first, so a capped list keeps the middle of the area.
        val (cx, cy) = tileXY(lat, lon, z)
        return (x0..x1).flatMap { x -> (y0..y1).map { y -> Tile(z, x, y) } }
            .sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }
    }

    private fun tileXY(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val y = floor((1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return x to y
    }

    private fun content(tile: Tile): TileContent {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[tile.key]?.takeIf { now - it.fetchedAt < TILE_TTL_MS }?.let { return it } }
        val url = tileTemplate().replace("{z}", "${tile.z}").replace("{x}", "${tile.x}").replace("{y}", "${tile.y}")
        val layers = VectorTileDecoder.decode(get(url), setOf("transportation", "poi"))
        val content = TileContent(
            roads = if (tile.z == DETAIL_ZOOM) layers["transportation"]?.let { roadsOf(tile, it) }.orEmpty() else emptyList(),
            pois = layers["poi"]?.let { placesOf(tile, it) }.orEmpty(),
            fetchedAt = now
        )
        synchronized(cache) { cache[tile.key] = content }
        return content
    }

    /** The tile address pattern; it changes with each data release, so it's read from OpenFreeMap's TileJSON. */
    private fun tileTemplate(): String {
        val now = System.currentTimeMillis()
        template?.takeIf { now - templateFetchedAt < TEMPLATE_TTL_MS }?.let { return it }
        val tiles = JSONObject(String(get(TILEJSON_URL), Charsets.UTF_8)).getJSONArray("tiles")
        return tiles.getString(0).also { template = it; templateFetchedAt = now }
    }

    private fun get(url: String): ByteArray {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8_000; con.readTimeout = 15_000
        con.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (con.responseCode !in 200..299) throw IllegalStateException("OpenFreeMap HTTP ${con.responseCode}")
            val bytes = con.inputStream.use { it.readBytes() }
            // Tiles may come gzip-compressed without a Content-Encoding header.
            return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } else bytes
        } finally {
            con.disconnect()
        }
    }

    // ==================== Roads ====================

    private fun roadsOf(tile: Tile, layer: VectorTileLayer): List<MapRoadSnapshot> = layer.features.flatMapIndexed { index, feature ->
        if (feature.type != 2) return@flatMapIndexed emptyList()
        val highway = highwayOf(feature.string("class"), feature.string("subclass")) ?: return@flatMapIndexed emptyList()
        feature.parts.mapIndexedNotNull { part, line ->
            if (line.size < 2) return@mapIndexedNotNull null
            val stride = (line.size / MAX_ROAD_POINTS).coerceAtLeast(1)
            val points = line.filterIndexed { i, _ -> i % stride == 0 || i == line.lastIndex }.map { toLatLon(tile, layer.extent, it) }
            MapRoadSnapshot(syntheticId(tile, feature.id, index, part), highway, null, points)
        }
    }

    /**
     * An OpenMapTiles road class as an OpenStreetMap highway value; null for what the map doesn't
     * draw (railways, ferries, construction, areas).
     */
    internal fun highwayOf(roadClass: String?, subclass: String?): String? = when (roadClass) {
        "motorway", "trunk", "primary", "secondary", "tertiary" -> roadClass
        "minor" -> "residential"
        "service" -> "service"
        "track" -> "track"
        "path" -> subclass?.takeIf { it !in setOf("corridor", "platform", "elevator") } ?: if (subclass == null) "path" else null
        "busway" -> "busway"
        else -> null
    }

    // ==================== Places ====================

    private fun placesOf(tile: Tile, layer: VectorTileLayer): List<MapPoiSnapshot> = layer.features.mapIndexedNotNull { index, feature ->
        val point = feature.parts.firstOrNull()?.firstOrNull() ?: return@mapIndexedNotNull null
        val category = osmCategory(feature.string("class"), feature.string("subclass")) ?: return@mapIndexedNotNull null
        MapPoiSnapshot(
            id = syntheticId(tile, feature.id, index, 0),
            name = feature.string("name:latin") ?: feature.string("name"),
            category = category,
            important = category in IMPORTANT,
            point = toLatLon(tile, layer.extent, point)
        )
    }

    private val IMPORTANT = setOf(
        "railway=station", "shop=supermarket",
        "amenity=kindergarten", "amenity=school", "amenity=college", "amenity=university"
    )
    private val AMENITIES = setOf(
        "kindergarten", "school", "college", "university", "hospital", "police", "bus_station", "ferry_terminal",
        "cafe", "restaurant", "pharmacy", "fuel", "library", "marketplace"
    )
    private val TOURISM = setOf(
        "museum", "attraction", "viewpoint", "artwork", "gallery", "zoo", "aquarium", "theme_park",
        "hotel", "guest_house", "hostel", "motel"
    )
    private val HISTORIC = setOf("castle", "monument", "memorial", "ruins", "archaeological_site", "fort", "city_gate")

    /** OpenMapTiles classes for shops; their subclass is the shop's OpenStreetMap value. */
    private val SHOP_CLASSES = setOf(
        "shop", "grocery", "clothing_store", "alcohol_shop", "bakery", "butcher", "jewelry", "furniture",
        "hardware", "florist", "ice_cream", "laundry", "music", "car", "bicycle", "mobile_phone", "shoes", "books"
    )

    /**
     * A tile place as the app's category ("railway=station", "amenity=cafe", "shop=bakery", …);
     * null for what the map doesn't show (parking, benches, bus stops…). The OpenMapTiles subclass is
     * the place's OpenStreetMap value, but its key has to be inferred, and a few are special:
     * a place of worship's subclass is its religion, and a bookshop is in the "library" class.
     */
    internal fun osmCategory(placeClass: String?, subclass: String?): String? {
        val value = subclass ?: placeClass ?: return null
        return when {
            placeClass == "railway" -> if (value == "station") "railway=station" else null
            value == "supermarket" -> "shop=supermarket"
            placeClass == "place_of_worship" -> "amenity=place_of_worship"
            placeClass == "library" && value == "books" -> "shop=books"
            value in AMENITIES -> "amenity=$value"
            value in TOURISM -> "tourism=$value"
            value in HISTORIC -> "historic=$value"
            placeClass in SHOP_CLASSES -> "shop=$value"
            else -> null
        }
    }

    /** The app's POI category id for a category string, as the map's catalog reads it. */
    internal fun categoryId(category: String): String? {
        val (key, value) = category.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
        return when (key) {
            "railway" -> "station"
            "shop" -> if (value == "supermarket") "supermarket" else "shop"
            "amenity" -> if (value in setOf("kindergarten", "school", "college", "university")) "school" else value
            "tourism" -> "tourism"
            "historic" -> "historic"
            else -> null
        }
    }

    // ==================== Geometry ====================

    private fun toLatLon(tile: Tile, extent: Int, point: VectorTilePoint): MapPointSnapshot {
        val n = (1 shl tile.z).toDouble()
        val x = (tile.x + point.x.toDouble() / extent) / n
        val y = (tile.y + point.y.toDouble() / extent) / n
        return MapPointSnapshot(Math.toDegrees(atan(sinh(PI * (1 - 2 * y)))), x * 360.0 - 180.0)
    }

    private fun distanceM(a: MapPointSnapshot, b: MapPointSnapshot): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        return EARTH_RADIUS_M * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    /** Tile features have no OpenStreetMap id: a stable id from the tile and the feature's place in it. */
    private fun syntheticId(tile: Tile, featureId: Long, index: Int, part: Int): Long =
        (((tile.z.toLong() * 31 + tile.x) * 1_000_003 + tile.y) * 1_000_003 + (if (featureId != 0L) featureId else index.toLong())) * 31 + part
}
