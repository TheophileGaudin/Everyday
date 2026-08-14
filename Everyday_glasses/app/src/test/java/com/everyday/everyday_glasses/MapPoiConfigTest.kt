package com.everyday.everyday_glasses

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPoiConfigTest {
    @Test
    fun `custom config round trips through persistence`() {
        val config = PoiDisplayConfig.custom(
            setOf(MapPoiCategoryCatalog.HOSPITAL, MapPoiCategoryCatalog.RESTAURANT),
            radiusM = 300,
            maxResults = 5
        )
        val state = WidgetPersistence.createPersistedState(
            textWidgets = emptyList(),
            browserWidgets = emptyList(),
            imageWidgets = emptyList(),
            statusBarState = null,
            mapWidgetState = WidgetPersistence.MapWidgetState(
                x = 10f,
                y = 20f,
                width = 210f,
                height = 210f,
                poiConfig = config
            )
        )

        val restored = WidgetPersistence.stateFromJson(WidgetPersistence.stateToJson(state))

        assertEquals(config, restored.map?.poiConfig)
    }

    @Test
    fun `missing persisted poi config means legacy Default`() {
        val state = WidgetPersistence.createPersistedState(
            textWidgets = emptyList(),
            browserWidgets = emptyList(),
            imageWidgets = emptyList(),
            statusBarState = null,
            mapWidgetState = WidgetPersistence.MapWidgetState(10f, 20f, 210f, 210f)
        )
        val json = WidgetPersistence.stateToJson(state)
        json.getJSONObject("map_widget").remove("poiConfig")

        val restored = WidgetPersistence.stateFromJson(json)

        assertEquals(PoiDisplayConfig.DEFAULT, restored.map?.poiConfig)
        assertEquals(PoiDisplayConfigState.DEFAULT, restored.map?.poiConfig?.state)
    }

    @Test
    fun `poi configuration facts are deterministic assistant context metadata`() {
        val config = PoiDisplayConfig.custom(
            setOf(MapPoiCategoryCatalog.RESTAURANT, MapPoiCategoryCatalog.HOSPITAL),
            radiusM = 300,
            maxResults = 5
        )
        val facts = config.toAssistantSemanticFacts()
        val context = AssistantUiContext(
            screenWidth = 640,
            screenHeight = 480,
            layoutName = null,
            focusedWidgetId = null,
            widgets = listOf(
                AssistantWidgetContext(
                    id = "map",
                    left = 0,
                    top = 0,
                    right = 640,
                    bottom = 480,
                    zOrder = 1,
                    minimized = false,
                    fullscreen = true,
                    pinned = true,
                    facts = facts
                )
            )
        )

        assertEquals(
            listOf(
                "poi_config_state", "poi_display_mode", "poi_categories", "poi_radius_m", "poi_max_results",
                "poi_important_categories", "poi_important_radii_m", "poi_normal_categories",
                "poi_normal_max_results", "poi_show_closest", "poi_closest_radius_m"
            ),
            facts.map { it.key }
        )
        assertEquals("hospital,restaurant", facts[2].value)
        assertTrue(context.toJson().toString().contains("poi_config_state"))
        assertTrue(context.toJson().toString().contains("radius_m"))
    }

    @Test
    fun `tweaked default profile round trips all algorithm variables`() {
        val config = PoiDisplayConfig.DEFAULT.copy(
            state = PoiDisplayConfigState.CUSTOM,
            mode = PoiDisplayMode.DEFAULT,
            importantCategories = setOf(MapPoiCategoryCatalog.STATION, MapPoiCategoryCatalog.HOSPITAL),
            importantRadiiM = mapOf(
                MapPoiCategoryCatalog.STATION to 7_500,
                MapPoiCategoryCatalog.HOSPITAL to 1_200
            ),
            normalCategories = setOf(MapPoiCategoryCatalog.CAFE, MapPoiCategoryCatalog.RESTAURANT),
            normalMaxResults = 6,
            showClosest = false,
            closestRadiusM = 300
        )
        val state = WidgetPersistence.createPersistedState(
            textWidgets = emptyList(),
            browserWidgets = emptyList(),
            imageWidgets = emptyList(),
            statusBarState = null,
            mapWidgetState = WidgetPersistence.MapWidgetState(10f, 20f, 210f, 210f, poiConfig = config)
        )

        val restored = WidgetPersistence.stateFromJson(WidgetPersistence.stateToJson(state))

        assertEquals(config, restored.map?.poiConfig)
        assertTrue(restored.map?.poiConfig?.isDefaultMode == true)
        assertTrue(restored.map?.poiConfig?.isDefault == false)
    }
}
