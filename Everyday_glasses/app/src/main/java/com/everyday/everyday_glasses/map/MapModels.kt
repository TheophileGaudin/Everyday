package com.everyday.everyday_glasses.map

data class MapGeoPoint(val lat: Double, val lon: Double)
data class MapRoad(val id: Long, val highway: String, val name: String?, val points: List<MapGeoPoint>)
enum class MapPoiTier { STATION, SUPERMARKET, SCHOOL, NORMAL }
data class MapPoi(val id: Long, val name: String?, val category: String, val tier: MapPoiTier, val point: MapGeoPoint)

fun mapPoiTier(category: String): MapPoiTier = when {
    category == "railway=station" -> MapPoiTier.STATION
    category == "shop=supermarket" -> MapPoiTier.SUPERMARKET
    category in setOf("amenity=kindergarten", "amenity=school", "amenity=college", "amenity=university") -> MapPoiTier.SCHOOL
    else -> MapPoiTier.NORMAL
}
data class DevicePose(
    val headingDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val anchorXRad: Float = 0f,
    val anchorYRad: Float = 0f,
    val anchorZRad: Float = 0f,
    val source: String = "sensor"
)
enum class MapViewMode { TOP_DOWN, PROJECTED }
enum class MapZoom(val label: String, val radiusM: Float) {
    NEAR("Near", 80f), MEDIUM("Medium", 160f), FAR("Far", 320f);
    fun next() = entries[(ordinal + 1) % entries.size]
}
