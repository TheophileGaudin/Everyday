package com.everyday.everyday_glasses

import kotlin.math.*
import kotlin.random.Random

data class MapPoiSelection(
    val important: List<MapPoi> = emptyList(),
    val immediate: MapPoi? = null,
    val midRange: List<MapPoi> = emptyList()
) {
    val visibleInMap: List<MapPoi>
        get() = buildList {
            addAll(important)
            immediate?.let(::add)
            addAll(midRange)
        }.distinctBy(MapPoi::stableKey)
}

/** Keeps the quiet, evenly-spaced POI set stable while the user moves. */
class MapPoiSelector(private val random: Random = Random.Default) {
    private val selectedMidRangeKeys = linkedSetOf<String>()

    fun select(origin: MapGeoPoint, pois: List<MapPoi>, mapRadiusM: Float): MapPoiSelection {
        val important = listOfNotNull(
            nearest(origin, pois.filter { it.tier == MapPoiTier.STATION }),
            nearest(origin, pois.filter { it.tier == MapPoiTier.SUPERMARKET && distanceM(origin, it.point) < ONE_MILE_M }),
            nearest(origin, pois.filter { it.tier == MapPoiTier.SCHOOL && distanceM(origin, it.point) < ONE_MILE_M })
        ).distinctBy(MapPoi::stableKey)

        val ordinaryInMap = pois.filter {
            it.tier == MapPoiTier.NORMAL && distanceM(origin, it.point) <= mapRadiusM
        }
        val immediate = nearest(origin, ordinaryInMap)
        val candidates = ordinaryInMap.filter { it.stableKey != immediate?.stableKey }
        val byKey = candidates.associateBy(MapPoi::stableKey)

        selectedMidRangeKeys.retainAll(byKey.keys)
        val chosen = selectedMidRangeKeys.mapNotNull(byKey::get).toMutableList()
        if (chosen.isEmpty() && candidates.isNotEmpty()) {
            val closePool = candidates.sortedBy { distanceM(origin, it.point) }.take(6)
            val weighted = closePool.flatMapIndexed { index, poi -> List(closePool.size - index) { poi } }
            chosen += weighted[random.nextInt(weighted.size)]
        }
        while (chosen.size < MID_RANGE_LIMIT) {
            val remaining = candidates.filter { candidate -> chosen.none { it.stableKey == candidate.stableKey } }
            if (remaining.isEmpty()) break
            chosen += remaining.maxWithOrNull(
                compareBy<MapPoi> { candidate -> angularSeparation(origin, candidate, chosen) }
                    .thenBy { -distanceM(origin, it.point) }
            ) ?: break
        }
        selectedMidRangeKeys.clear()
        selectedMidRangeKeys.addAll(chosen.take(MID_RANGE_LIMIT).map(MapPoi::stableKey))
        return MapPoiSelection(important, immediate, chosen.take(MID_RANGE_LIMIT))
    }

    private fun nearest(origin: MapGeoPoint, candidates: List<MapPoi>): MapPoi? =
        candidates.minByOrNull { distanceM(origin, it.point) }

    private fun angularSeparation(origin: MapGeoPoint, candidate: MapPoi, chosen: List<MapPoi>): Double {
        if (chosen.isEmpty()) return 180.0
        val bearing = bearingDeg(origin, candidate.point)
        return chosen.minOf { other ->
            val delta = abs(bearing - bearingDeg(origin, other.point)) % 360.0
            min(delta, 360.0 - delta)
        }
    }

    companion object {
        const val MID_RANGE_LIMIT = 4
        const val ONE_MILE_M = 1609.344

        fun distanceM(a: MapGeoPoint, b: MapGeoPoint): Double {
            val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
            val dLat = lat2 - lat1; val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
            return 6_371_000.0 * 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
        }

        fun bearingDeg(a: MapGeoPoint, b: MapGeoPoint): Double {
            val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            return (Math.toDegrees(atan2(sin(dLon) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon))) + 360.0) % 360.0
        }
    }
}

private val MapPoi.stableKey: String
    get() = "$id:$category:${point.lat}:${point.lon}"
