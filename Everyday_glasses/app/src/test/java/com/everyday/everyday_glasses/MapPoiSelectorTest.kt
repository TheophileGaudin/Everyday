package com.everyday.everyday_glasses

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MapPoiSelectorTest {
    private val origin = MapGeoPoint(52.0, 0.0)

    @Test
    fun selectsAtMostEightPoisWithOneImportantPoiPerRole() {
        val pois = listOf(
            poi(1, "Station A", "railway=station", 3000.0, 0.0),
            poi(2, "Station B", "railway=station", 5000.0, 90.0),
            poi(3, "Market", "shop=supermarket", 900.0, 30.0),
            poi(4, "School", "amenity=school", 1000.0, 60.0)
        ) + (10L..20L).mapIndexed { index, id -> poi(id, "POI $id", "amenity=cafe", 30.0 + index, index * 31.0) }

        val selected = MapPoiSelector(Random(2)).select(origin, pois, 160f)

        assertEquals(3, selected.important.size)
        assertEquals(4, selected.midRange.count { it.tier == MapPoiTier.NORMAL })
        assertTrue(selected.midRange.size <= 4)
        assertNotNull(selected.immediate)
        assertTrue(selected.visibleInMap.size <= 8)
        assertEquals("Station A", selected.important.first { it.tier == MapPoiTier.STATION }.name)
    }

    @Test
    fun churchIsOrdinaryAndCanBeImmediateOrMidRange() {
        val church = poi(1, "Parish Church", "amenity=place_of_worship", 20.0, 0.0)
        val selected = MapPoiSelector(Random(1)).select(origin, listOf(church), 160f)

        assertEquals(MapPoiTier.NORMAL, church.tier)
        assertSame(church, selected.immediate)
        assertTrue(selected.important.isEmpty())
    }

    @Test
    fun preservesMidRangePoisStillInsideMapAndRefillsMissingOnes() {
        val initial = (1L..8L).mapIndexed { index, id -> poi(id, "POI $id", "amenity=cafe", 50.0, index * 45.0) }
        val selector = MapPoiSelector(Random(3))
        val first = selector.select(origin, initial, 160f)
        val kept = first.midRange.dropLast(1)
        val removedKey = first.midRange.last().id
        val moved = initial.filterNot { it.id == removedKey } + poi(99, "Replacement", "tourism=museum", 60.0, 200.0)

        val second = selector.select(origin, moved, 160f)

        assertTrue(second.midRange.containsAll(kept))
        assertEquals(4, second.midRange.size)
    }

    @Test
    fun supermarketAndSchoolStopBeingImportantAtOneMile() {
        val selected = MapPoiSelector(Random(1)).select(origin, listOf(
            poi(1, "Far Market", "shop=supermarket", 1700.0, 0.0),
            poi(2, "Far School", "amenity=university", 1700.0, 90.0),
            poi(3, "Far Station", "railway=station", 7000.0, 180.0)
        ), 320f)

        assertEquals(listOf(MapPoiTier.STATION), selected.important.map { it.tier })
    }

    private fun poi(id: Long, name: String, category: String, distanceM: Double, bearingDeg: Double): MapPoi {
        val angular = distanceM / 6_371_000.0
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(origin.lat)
        val lon1 = Math.toRadians(origin.lon)
        val lat2 = kotlin.math.asin(kotlin.math.sin(lat1) * kotlin.math.cos(angular) + kotlin.math.cos(lat1) * kotlin.math.sin(angular) * kotlin.math.cos(bearing))
        val lon2 = lon1 + kotlin.math.atan2(kotlin.math.sin(bearing) * kotlin.math.sin(angular) * kotlin.math.cos(lat1), kotlin.math.cos(angular) - kotlin.math.sin(lat1) * kotlin.math.sin(lat2))
        return MapPoi(id, name, category, mapPoiTier(category), MapGeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2)))
    }
}
