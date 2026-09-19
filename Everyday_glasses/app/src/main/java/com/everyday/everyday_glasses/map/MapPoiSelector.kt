package com.everyday.everyday_glasses.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class MapPoiSelection(
    val important: List<MapPoi> = emptyList(),
    val immediate: MapPoi? = null,
    val midRange: List<MapPoi> = emptyList(),
    /** Custom selections retain their deterministic distance order for rendering and tests. */
    val displayed: List<MapPoi> = emptyList()
) {
    val visibleInMap: List<MapPoi>
        get() = if (displayed.isNotEmpty()) {
            displayed
        } else {
            buildList {
                addAll(important)
                immediate?.let(::add)
                addAll(midRange)
            }.distinctBy(MapPoi::stableKey)
        }
}

/** Keeps the quiet, evenly-spaced POI set stable while the user moves. */
class MapPoiSelector(private val random: Random = Random.Default) {
    private val selectedMidRangeKeys = linkedSetOf<String>()

    /** Legacy selector retained verbatim for [PoiDisplayConfig.Companion.DEFAULT] parity. */
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

    /**
     * Selects a user-configured POI set. One nearest representative is reserved per qualifying
     * category when there is room, then the remaining slots are filled by distance. The final
     * list is sorted by distance and stable identity so equal-distance results never flicker.
     */
    fun select(
        origin: MapGeoPoint,
        pois: List<MapPoi>,
        config: PoiDisplayConfig,
        legacyMapRadiusM: Float = MapZoom.MEDIUM.radiusM
    ): MapPoiSelection {
        if (config.isDefaultMode) {
            // Keep the untouched Default profile byte-for-byte equivalent to the selector that
            // shipped before the settings menu grew configurable algorithm parameters.
            if (config.isDefault) return select(origin, pois, legacyMapRadiusM)
            return selectDefaultMode(origin, pois, config, legacyMapRadiusM)
        }

        return selectNearestMode(origin, pois, config)
    }

    /** Configurable form of the quiet Default algorithm. */
    private fun selectDefaultMode(
        origin: MapGeoPoint,
        pois: List<MapPoi>,
        config: PoiDisplayConfig,
        mapRadiusM: Float
    ): MapPoiSelection {
        val important = config.importantCategories
            .mapNotNull { category ->
                val radiusM = config.importantRadiiM[category] ?: return@mapNotNull null
                val effectiveRadiusM = when {
                    // Preserve the legacy station behavior when a user changes another Default
                    // variable: the legacy data request already bounds stations at 8 km.
                    category == MapPoiCategoryCatalog.STATION && radiusM == PoiDisplayConfig.Companion.DEFAULT_STATION_RADIUS_M -> Double.POSITIVE_INFINITY
                    category in setOf(MapPoiCategoryCatalog.SUPERMARKET, MapPoiCategoryCatalog.SCHOOL) &&
                        radiusM == PoiDisplayConfig.Companion.DEFAULT_LOCAL_IMPORTANT_RADIUS_M -> ONE_MILE_M
                    else -> radiusM.toDouble()
                }
                nearest(
                    origin,
                    pois.filter {
                        MapPoiCategoryCatalog.categoryForPoi(it.category) == category &&
                            distanceM(origin, it.point) < effectiveRadiusM
                    }
                )
            }
            .distinctBy(MapPoi::stableKey)

        val ordinaryInMap = pois.filter {
            val category = MapPoiCategoryCatalog.categoryForPoi(it.category)
            category != null && category in config.normalCategories &&
                distanceM(origin, it.point) <= mapRadiusM
        }
        val immediate = if (config.showClosest) {
            nearest(
                origin,
                ordinaryInMap.filter { distanceM(origin, it.point) <= config.closestRadiusM }
            )
        } else {
            null
        }
        val candidates = ordinaryInMap.filter { it.stableKey != immediate?.stableKey }
        val byKey = candidates.associateBy(MapPoi::stableKey)

        selectedMidRangeKeys.retainAll(byKey.keys)
        val chosen = selectedMidRangeKeys.mapNotNull(byKey::get).toMutableList()
        if (chosen.isEmpty() && candidates.isNotEmpty() && config.normalMaxResults > 0) {
            val closePool = candidates.sortedBy { distanceM(origin, it.point) }.take(6)
            val weighted = closePool.flatMapIndexed { index, poi -> List(closePool.size - index) { poi } }
            chosen += weighted[random.nextInt(weighted.size)]
        }
        while (chosen.size < config.normalMaxResults) {
            val remaining = candidates.filter { candidate -> chosen.none { it.stableKey == candidate.stableKey } }
            if (remaining.isEmpty()) break
            chosen += remaining.maxWithOrNull(
                compareBy<MapPoi> { candidate -> angularSeparation(origin, candidate, chosen) }
                    .thenBy { -distanceM(origin, it.point) }
            ) ?: break
        }
        selectedMidRangeKeys.clear()
        selectedMidRangeKeys.addAll(chosen.take(config.normalMaxResults).map(MapPoi::stableKey))
        return MapPoiSelection(important, immediate, chosen.take(config.normalMaxResults))
    }

    private fun selectNearestMode(
        origin: MapGeoPoint,
        pois: List<MapPoi>,
        config: PoiDisplayConfig
    ): MapPoiSelection {
        val candidates = pois
            .asSequence()
            .mapNotNull { poi ->
                val category = MapPoiCategoryCatalog.categoryForPoi(poi.category)
                if (category == null || category !in config.categories) return@mapNotNull null
                val distance = distanceM(origin, poi.point)
                if (distance > config.radiusM) return@mapNotNull null
                Candidate(poi, category, distance)
            }
            .distinctBy { it.poi.stableKey }
            .toList()

        if (candidates.isEmpty()) return MapPoiSelection()

        val orderedCandidates = candidates.sortedWith(candidateComparator())
        val selected = if (!config.balanceCategories) {
            orderedCandidates.take(config.maxResults)
        } else {
            val byCategory = candidates.groupBy(Candidate::category)
            val selectedKeys = linkedSetOf<String>()

            // Preserve the previous voice configuration's category representation behavior.
            // The settings menu uses [nearest], which leaves this compatibility path disabled.
            if (config.maxResults >= byCategory.size) {
                byCategory.values
                    .map { it.minWithOrNull(candidateComparator()) }
                    .filterNotNull()
                    .sortedWith(candidateComparator())
                    .forEach { selectedKeys += it.poi.stableKey }
            }
            orderedCandidates.forEach { candidate ->
                if (selectedKeys.size >= config.maxResults) return@forEach
                selectedKeys += candidate.poi.stableKey
            }
            orderedCandidates
                .filter { it.poi.stableKey in selectedKeys }
                .take(config.maxResults)
        }

        val selectedPois = selected.map(Candidate::poi)
        return MapPoiSelection(
            important = if (config.balanceCategories) selectedPois.filter { it.tier != MapPoiTier.NORMAL } else emptyList(),
            immediate = selectedPois.firstOrNull(),
            midRange = selectedPois.drop(1),
            displayed = selectedPois
        )
    }

    private data class Candidate(
        val poi: MapPoi,
        val category: String,
        val distanceM: Double
    )

    private fun candidateComparator(): Comparator<Candidate> = compareBy<Candidate> { it.distanceM }
        .thenBy { it.category }
        .thenBy { it.poi.stableKey }

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
            return (Math.toDegrees(
                atan2(
                    sin(dLon) * cos(lat2),
                    cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
                )
            ) + 360.0) % 360.0
        }
    }
}

private val MapPoi.stableKey: String
    get() = "$id:$category:${point.lat}:${point.lon}"