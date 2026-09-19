package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.map.MapGeoPoint
import com.everyday.everyday_glasses.map.MapPoi
import com.everyday.everyday_glasses.map.MapPoiCategoryCatalog
import com.everyday.everyday_glasses.map.MapPoiTier
import com.everyday.everyday_glasses.map.PoiDisplayConfig
import com.everyday.everyday_glasses.map.PoiDisplayConfigState
import com.everyday.everyday_glasses.map.PoiDisplayMode
import com.everyday.everyday_glasses.map.mapPoiTier
import com.everyday.everyday_glasses.map.MapPoiSelector
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

    @Test
    fun defaultConfigurationMatchesTheLegacySelectorExactly() {
        val pois = listOf(
            poi(1, "Station", "railway=station", 3000.0, 0.0),
            poi(2, "Market", "shop=supermarket", 900.0, 30.0),
            poi(3, "School", "amenity=school", 1000.0, 60.0),
            poi(4, "Restaurant", "amenity=restaurant", 25.0, 90.0),
            poi(5, "Church", "amenity=place_of_worship", 70.0, 180.0)
        )

        val legacy = MapPoiSelector(Random(8)).select(origin, pois, 160f)
        val configured = MapPoiSelector(Random(8)).select(origin, pois, PoiDisplayConfig.DEFAULT, 160f)

        assertEquals(PoiDisplayConfigState.DEFAULT, PoiDisplayConfig.DEFAULT.state)
        assertEquals(PoiDisplayConfig.DEFAULT, PoiDisplayConfig.DEFAULT.copy())
        assertEquals(legacy, configured)
    }

    @Test
    fun customSelectionBalancesCategoriesBeforeFillingByDistance() {
        val selected = MapPoiSelector(Random(1)).select(
            origin,
            listOf(
                poi(1, "Nearby restaurant", "amenity=restaurant", 100.0, 0.0),
                poi(2, "Nearby shop", "shop=clothes", 200.0, 90.0),
                poi(3, "Hospital", "amenity=hospital", 1000.0, 180.0),
                poi(4, "Far hospital", "amenity=hospital", 1100.0, 270.0)
            ),
            PoiDisplayConfig.custom(
                setOf(MapPoiCategoryCatalog.RESTAURANT, MapPoiCategoryCatalog.SHOP, MapPoiCategoryCatalog.HOSPITAL),
                radiusM = 1200,
                maxResults = 4
            )
        )

        assertEquals(4, selected.displayed.size)
        assertEquals(
            listOf("Nearby restaurant", "Nearby shop", "Hospital", "Far hospital"),
            selected.displayed.map { it.name }
        )
        assertEquals(
            setOf(MapPoiCategoryCatalog.RESTAURANT, MapPoiCategoryCatalog.SHOP, MapPoiCategoryCatalog.HOSPITAL),
            selected.displayed.mapNotNull { MapPoiCategoryCatalog.categoryForPoi(it.category) }.toSet()
        )
    }

    @Test
    fun customSelectionAppliesRadiusAndMaximumTogether() {
        val selected = MapPoiSelector(Random(2)).select(
            origin,
            listOf(
                poi(1, "One", "amenity=restaurant", 100.0, 0.0),
                poi(2, "Two", "amenity=restaurant", 200.0, 90.0),
                poi(3, "Outside", "amenity=restaurant", 301.0, 180.0),
                poi(4, "Other category", "amenity=pharmacy", 50.0, 270.0)
            ),
            PoiDisplayConfig.custom(setOf(MapPoiCategoryCatalog.RESTAURANT), radiusM = 300, maxResults = 1)
        )

        assertEquals(listOf("One"), selected.displayed.map { it.name })
        assertTrue(selected.displayed.all { MapPoiSelector.distanceM(origin, it.point) <= 300.0 })
    }

    @Test
    fun customSelectionUsesDeterministicDistanceOrderingForTies() {
        val first = poi(1, "A", "amenity=restaurant", 100.0, 0.0)
        val second = poi(2, "B", "amenity=restaurant", 100.0, 180.0)
        val config = PoiDisplayConfig.custom(setOf(MapPoiCategoryCatalog.RESTAURANT), 500, 2)

        val selectedForward = MapPoiSelector().select(origin, listOf(second, first), config)
        val selectedReverse = MapPoiSelector().select(origin, listOf(first, second), config)

        assertEquals(selectedForward.displayed.map { it.id }, selectedReverse.displayed.map { it.id })
    }

    @Test
    fun customSelectionReturnsEmptyForNoQualifyingResults() {
        val selected = MapPoiSelector().select(
            origin,
            listOf(poi(1, "Far", "amenity=hospital", 2001.0, 0.0)),
            PoiDisplayConfig.custom(setOf(MapPoiCategoryCatalog.HOSPITAL), 2000, 8)
        )

        assertTrue(selected.displayed.isEmpty())
        assertTrue(selected.visibleInMap.isEmpty())
        assertNull(selected.immediate)
    }

    @Test
    fun tweakedDefaultProfileStillSelectsOneNearestImportantMarkerPerRole() {
        val config = PoiDisplayConfig.DEFAULT.copy(
            state = PoiDisplayConfigState.CUSTOM,
            mode = PoiDisplayMode.DEFAULT,
            importantCategories = setOf(MapPoiCategoryCatalog.STATION, MapPoiCategoryCatalog.HOSPITAL),
            importantRadiiM = mapOf(
                MapPoiCategoryCatalog.STATION to 7_000,
                MapPoiCategoryCatalog.HOSPITAL to 1_000
            ),
            normalCategories = setOf(MapPoiCategoryCatalog.CAFE),
            normalMaxResults = 0,
            showClosest = false
        )
        val selected = MapPoiSelector().select(
            origin,
            listOf(
                poi(1, "Station", "railway=station", 6_000.0, 0.0),
                poi(2, "Hospital", "amenity=hospital", 900.0, 90.0),
                poi(3, "Far hospital", "amenity=hospital", 1_100.0, 180.0),
                poi(4, "Cafe", "amenity=cafe", 20.0, 270.0)
            ),
            config,
            160f
        )

        assertEquals(setOf("Station", "Hospital"), selected.important.mapNotNull { it.name }.toSet())
        assertTrue(selected.midRange.isEmpty())
        assertNull(selected.immediate)
    }

    @Test
    fun nearestModeUsesPureDistanceOrder() {
        val selected = MapPoiSelector().select(
            origin,
            listOf(
                poi(1, "Restaurant", "amenity=restaurant", 100.0, 0.0),
                poi(2, "Shop", "shop=clothes", 200.0, 90.0),
                poi(3, "Hospital", "amenity=hospital", 1_000.0, 180.0)
            ),
            PoiDisplayConfig.nearest(
                setOf(MapPoiCategoryCatalog.RESTAURANT, MapPoiCategoryCatalog.SHOP, MapPoiCategoryCatalog.HOSPITAL),
                radiusM = 1_200,
                maxResults = 2
            )
        )

        assertEquals(listOf("Restaurant", "Shop"), selected.displayed.map { it.name })
        assertEquals(PoiDisplayMode.NEAREST, PoiDisplayConfig.nearest(setOf(MapPoiCategoryCatalog.CAFE), 500, 1).mode)
    }

    @Test
    fun nearestModeKeepsASelectedPoiOutsideTheCurrentZoomForDirectionalRendering() {
        val supermarket = poi(1, "Five kilometre supermarket", "shop=supermarket", 4_900.0, 45.0)
        val selected = MapPoiSelector().select(
            origin,
            listOf(supermarket),
            PoiDisplayConfig.nearest(setOf(MapPoiCategoryCatalog.SUPERMARKET), radiusM = 5_000, maxResults = 1)
        )

        assertEquals(listOf(supermarket), selected.displayed)
        assertEquals(supermarket, selected.immediate)
    }

    private fun poi(id: Long, name: String, category: String, distanceM: Double, bearingDeg: Double): MapPoi {
        val angular = distanceM / 6_371_000.0
        val bearing = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(origin.lat)
        val lon1 = Math.toRadians(origin.lon)
        val lat2 = kotlin.math.asin(kotlin.math.sin(lat1) * kotlin.math.cos(angular) + kotlin.math.cos(lat1) * kotlin.math.sin(angular) * kotlin.math.cos(bearing))
        val lon2 = lon1 + kotlin.math.atan2(kotlin.math.sin(bearing) * kotlin.math.sin(angular) * kotlin.math.cos(lat1), kotlin.math.cos(angular) - kotlin.math.sin(lat1) * kotlin.math.sin(lat2))
        return MapPoi(
            id,
            name,
            category,
            mapPoiTier(category),
            MapGeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
        )
    }
}
