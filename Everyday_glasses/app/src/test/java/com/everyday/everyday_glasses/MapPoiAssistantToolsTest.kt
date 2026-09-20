package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.interaction.assistant.AssistantToolCall
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecution
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionContext
import com.everyday.everyday_glasses.interaction.assistant.AssistantUiContext
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.ConfigureMapPoisRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.ConfigureMapPoisSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.assistantCompletedFuture
import com.everyday.everyday_glasses.widgets._common.PresentationExpiryPolicy
import com.everyday.everyday_glasses.widgets._common.PresentationMode
import com.everyday.everyday_glasses.widgets.map.backend.MapPoiCategoryCatalog
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPoiAssistantToolsTest {
    private val requests = mutableListOf<ConfigureMapPoisRequest>()
    private val handler = ConfigureMapPoisSemanticToolHandler(
        applyConfiguration = { request ->
            requests += request
            assistantCompletedFuture(
                AssistantToolExecution(true, AssistantToolExecutionCodes.SUCCESS, "applied")
            )
        }
    )

    @Test
    fun `display hospitals close by infers emergency radius and default count`() {
        val result = handler.execute(
            callArguments(JSONObject().put("categories", JSONArray().put("hospital"))),
            context()
        ).get()

        assertTrue(result.success)
        assertEquals(2_000, requests.single().config.radiusM)
        assertEquals(8, requests.single().config.maxResults)
        assertEquals(setOf(MapPoiCategoryCatalog.HOSPITAL), requests.single().config.categories)
    }

    @Test
    fun `explicit restaurant radius is used literally`() {
        handler.execute(
            callArguments(
                JSONObject()
                    .put("categories", JSONArray().put("restaurant"))
                    .put("radius_m", 300)
            ),
            context()
        ).get()

        assertEquals(300, requests.single().config.radiusM)
        assertEquals(8, requests.single().config.maxResults)
    }

    @Test
    fun `default reset restores the original default rather than inferred values`() {
        handler.execute(
            callArguments(
                JSONObject()
                    .put("categories", JSONArray().put("restaurant"))
                    .put("radius_m", 300)
                    .put("max_results", 2)
            ),
            context()
        ).get()
        requests.clear()

        val result = handler.execute(
            callArguments(JSONObject().put("reset_default", true)),
            context()
        ).get()

        assertTrue(result.success)
        assertEquals(PoiDisplayConfig.DEFAULT, requests.single().config)
        assertEquals(PoiDisplayConfig.DEFAULT_RADIUS_M, requests.single().config.radiusM)
        assertEquals(PoiDisplayConfig.DEFAULT_MAX_RESULTS, requests.single().config.maxResults)
    }

    @Test
    fun `temporary map presentation accepts fullscreen leave windowed`() {
        handler.execute(
            callArguments(
                JSONObject()
                    .put("categories", JSONArray().put("hospital"))
                    .put(
                        "presentation",
                        JSONObject()
                            .put("mode", "fullscreen")
                            .put("duration_seconds", 10)
                            .put("on_expiry", "leave_windowed")
                    )
            ),
            context()
        ).get()

        assertNotNull(requests.single().presentation)
        assertEquals(PresentationMode.FULLSCREEN, requests.single().presentation?.mode)
        assertEquals(PresentationExpiryPolicy.LEAVE_WINDOWED, requests.single().presentation?.onExpiry)
    }

    @Test
    fun `tool validation rejects invalid ranges and unsafe expiry`() {
        val invalidRadius = handler.invoke(
            AssistantToolCall(
                "1", "configure_map_pois", JSONObject()
                    .put("categories", JSONArray().put("restaurant"))
                    .put("radius_m", 49)
            ),
            context()
        ).get()
        val invalidExpiry = handler.invoke(
            AssistantToolCall(
                "2", "configure_map_pois", JSONObject()
                    .put("categories", JSONArray().put("restaurant"))
                    .put(
                        "presentation", JSONObject()
                            .put("mode", "fullscreen")
                            .put("duration_seconds", 10)
                            .put("on_expiry", "restore_previous")
                    )
            ),
            context()
        ).get()

        assertFalse(invalidRadius.success)
        assertEquals(AssistantToolExecutionCodes.INVALID_ARGUMENTS, invalidRadius.code)
        assertFalse(invalidExpiry.success)
        assertEquals(AssistantToolExecutionCodes.INVALID_ARGUMENTS, invalidExpiry.code)
        assertTrue(requests.isEmpty())
    }

    private fun callArguments(arguments: JSONObject): JSONObject = arguments

    private fun context() = AssistantToolExecutionContext(
        uiContext = AssistantUiContext(640, 480, null, null, emptyList())
    )
}
