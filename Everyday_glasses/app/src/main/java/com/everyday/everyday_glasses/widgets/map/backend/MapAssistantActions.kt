package com.everyday.everyday_glasses.widgets.map.backend

import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecution
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.widgets._common.PresentationSpec
import com.everyday.everyday_glasses.widgets.map.MapWidget
import java.util.concurrent.CompletableFuture

internal class MapAssistantActions(
    private val ui: UiThreadDispatcher,
    private val showMap: () -> MapWidget?,
    private val currentMap: () -> MapWidget?,
    private val present: (MapWidget, PresentationSpec) -> Boolean,
    private val dismissPresentation: () -> Unit,
    private val withTransientChanges: (() -> Unit) -> Unit,
    private val notifyTransientChange: () -> Unit,
    private val notifyPersistentChange: () -> Unit,
) {
    fun startNavigation(request: StartNavigationRequest): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val accepted = ui.dispatch {
            try {
                val map = showMap() ?: error("Map widget could not be created")
                val presentation = request.presentation ?: NavigationPresentationDefaults.VOICE_ROUTE
                if (!present(map, presentation)) {
                    result.complete(failure("Map presentation could not be started"))
                    return@dispatch
                }
                withTransientChanges {
                    map.startNavigation(request.address, request.mode, presentation) { outcome ->
                        result.complete(outcome.toAssistantExecution())
                    }
                    notifyTransientChange()
                }
            } catch (error: Throwable) {
                result.complete(failure(error.message ?: "Navigation could not be started"))
            }
        }
        if (!accepted) result.complete(failure("Glasses display is unavailable"))
        return result
    }

    fun stopNavigation(): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val accepted = ui.dispatch {
            withTransientChanges {
                dismissPresentation()
                currentMap()?.stopNavigation()
                notifyTransientChange()
            }
            result.complete(success("Navigation stopped"))
        }
        if (!accepted) result.complete(failure("Glasses display is unavailable"))
        return result
    }

    fun configurePois(request: ConfigureMapPoisRequest): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val accepted = ui.dispatch {
            runCatching {
                val mapWasVisible = currentMap() != null
                val map = showMap() ?: error("Map widget could not be created")
                val before = map.poiDisplayConfig
                map.applyPoiDisplayConfig(request.config)
                val changed = map.poiDisplayConfig != before
                val presented = request.presentation?.let { present(map, it) } ?: true
                if (!presented) {
                    failure("Map presentation could not be started")
                } else {
                    if (changed && mapWasVisible) notifyPersistentChange()
                    success(
                        "Map POIs configured: state=${request.config.state.name}, " +
                                "categories=${request.config.categories.sorted().joinToString(",")}, " +
                                "radius_m=${request.config.radiusM}, max_results=${request.config.maxResults}"
                    )
                }
            }.onSuccess(result::complete)
                .onFailure { result.complete(failure(it.message ?: "Map POI configuration failed")) }
        }
        if (!accepted) result.complete(failure("Glasses display is unavailable"))
        return result
    }

    private fun NavigationOperationResult.toAssistantExecution(): AssistantToolExecution = when (this) {
        is NavigationOperationResult.Active -> success(
            "Walking route active: destination=${state.destination?.label.orEmpty()}, " +
                    "eta=${formatWalkingEta(state.walkingEtaSeconds)}"
        )
        is NavigationOperationResult.Ambiguous -> failure(
            "Destination is ambiguous; choose one: ${state.choices.joinToString(" | ") { it.label }}"
        )
        is NavigationOperationResult.Failure -> failure(message)
    }

    private fun success(message: String) = AssistantToolExecution(
        true, AssistantToolExecutionCodes.SUCCESS, message
    )

    private fun failure(message: String) = AssistantToolExecution(
        false, AssistantToolExecutionCodes.EXECUTION_FAILED, message
    )
}