package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecution
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionContext
import com.everyday.everyday_glasses.interaction.assistant.AssistantUiContext
import com.everyday.everyday_glasses.interaction.assistant.StartNavigationSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.StopNavigationSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.assistantCompletedFuture
import com.everyday.everyday_glasses.ui.widgets.common.PresentationExpiryPolicy
import com.everyday.everyday_glasses.ui.widgets.common.PresentationMode
import com.everyday.everyday_glasses.map.MapGeoPoint
import com.everyday.everyday_glasses.map.NavigationDestination
import com.everyday.everyday_glasses.map.NavigationPresentationDefaults
import com.everyday.everyday_glasses.map.NavigationState
import com.everyday.everyday_glasses.map.NavigationStatus
import com.everyday.everyday_glasses.map.StartNavigationRequest
import com.everyday.shared.routing.GeoPoint
import com.everyday.shared.routing.RoutePolyline
import com.everyday.shared.routing.RouteResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class MapNavigationAssistantToolsTest {
    @Test
    fun startNavigationClearlyRejectsUnsupportedModes() {
        val handler = StartNavigationSemanticToolHandler(
            startNavigation = { assistantCompletedFuture(success()) }
        )
        val result = handler.validate(
            JSONObject().put("address", "Central Station").put("mode", "DRIVING")
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Only WALKING"))
    }

    @Test
    fun startNavigationNormalizesRequestAndAcceptsPresentationSpec() {
        var received: StartNavigationRequest? = null
        val handler = StartNavigationSemanticToolHandler(
            startNavigation = { request ->
                received = request
                assistantCompletedFuture(success())
            }
        )
        val arguments = JSONObject()
            .put("address", "  Central\u00a0Station  ")
            .put("mode", "walking")
            .put(
                "presentation",
                JSONObject()
                    .put("mode", "fullscreen")
                    .put("duration_seconds", 10)
                    .put("on_expiry", "leave_windowed")
            )

        val execution = handler.execute(arguments, context()).get()

        assertTrue(execution.success)
        assertEquals("Central Station", received!!.address)
        assertEquals("WALKING", received!!.mode)
        assertEquals(PresentationMode.FULLSCREEN, received!!.presentation!!.mode)
    }

    @Test
    fun startNavigationDefaultsToWalkingWhenVoiceCommandOmitsMode() {
        var received: StartNavigationRequest? = null
        val handler = StartNavigationSemanticToolHandler(
            startNavigation = { request ->
                received = request
                assistantCompletedFuture(success())
            }
        )

        assertTrue(handler.execute(JSONObject().put("address", "Central Station"), context()).get().success)
        assertEquals("WALKING", received!!.mode)
    }

    @Test
    fun stopNavigationHasNoArgumentsAndDelegatesToHost() {
        var calls = 0
        val handler = StopNavigationSemanticToolHandler(
            stopNavigation = {
                calls++
                CompletableFuture.completedFuture(success())
            }
        )

        assertTrue(handler.validate(JSONObject()).isSuccess)
        assertFalse(handler.validate(JSONObject().put("unexpected", true)).isSuccess)
        assertTrue(handler.execute(JSONObject(), context()).get().success)
        assertEquals(1, calls)
    }

    @Test
    fun voiceRouteDefaultPresentationIsTenSecondFullscreenAndLeavesWindowed() {
        val spec = NavigationPresentationDefaults.VOICE_ROUTE

        assertEquals(PresentationMode.FULLSCREEN, spec.mode)
        assertEquals(10, spec.durationSeconds)
        assertEquals(PresentationExpiryPolicy.LEAVE_WINDOWED, spec.onExpiry)
    }

    @Test
    fun activeNavigationStateExposesDestinationStatusAndEtaFacts() {
        val origin = GeoPoint(51.5, -0.125)
        val route = RouteResult(
            origin = origin,
            destination = GeoPoint(51.501, -0.125),
            snappedOriginNodeId = 1L,
            snappedDestinationNodeId = 2L,
            polyline = RoutePolyline(listOf(origin, GeoPoint(51.501, -0.125))),
            totalDistanceMeters = 140.0,
            estimatedWalkingTimeSeconds = 100L,
            graphNodeIds = listOf(1L, 2L),
            graphEdges = emptyList(),
            maneuvers = emptyList()
        )
        val state = NavigationState(
            status = NavigationStatus.ACTIVE,
            destination = NavigationDestination(
                "Museum",
                "Museum, London",
                MapGeoPoint(51.501, -0.125)
            ),
            route = route
        )
        val facts = state.toAssistantSemanticFacts().associate { it.key to it.value }

        assertEquals("ACTIVE", facts["navigation_status"])
        assertEquals("Museum, London", facts["navigation_destination"])
        assertEquals("100", facts["navigation_eta_seconds"])
        assertEquals("ACTIVE", facts["navigation_route_status"])
    }

    private fun context() = AssistantToolExecutionContext(
        uiContext = AssistantUiContext(640, 480, null, null, emptyList())
    )

    private fun success() = AssistantToolExecution(
        true,
        AssistantToolExecutionCodes.SUCCESS,
        "ok"
    )
}
