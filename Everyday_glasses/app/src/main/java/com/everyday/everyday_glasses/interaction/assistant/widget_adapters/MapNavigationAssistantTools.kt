package com.everyday.everyday_glasses.interaction.assistant.widget_adapters

import com.everyday.everyday_glasses.interaction.assistant.AssistantSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolDefinition
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecution
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionContext
import com.everyday.everyday_glasses.interaction.assistant.assistantCompletedFuture
import com.everyday.everyday_glasses.widgets.map.backend.NavigationAddressText
import com.everyday.everyday_glasses.widgets.map.backend.StartNavigationRequest
import com.everyday.everyday_glasses.widgets._common.PresentationExpiryPolicy
import com.everyday.everyday_glasses.widgets._common.PresentationMode
import com.everyday.everyday_glasses.widgets._common.PresentationSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** Semantic request for the glasses-local walking navigation flow. */
class StartNavigationSemanticToolHandler(
    private val startNavigation: (StartNavigationRequest) -> CompletableFuture<AssistantToolExecution>,
    private val isAvailable: () -> Boolean = { true }
) : AssistantSemanticToolHandler {
    override val isAdvertised: Boolean
        get() = isAvailable()

    override val definition = AssistantToolDefinition(
        name = "start_navigation",
        description = "Search for the best matching destination and start turn-by-turn walking navigation on the Everyday glasses map. Use for commands such as search, find, directions to, go to, or navigate to a place or address.",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("address", JSONObject().put("type", "string"))
                    .put(
                        "mode",
                        JSONObject()
                            .put("type", "string")
                            .put(
                                "enum",
                                JSONArray().put("WALKING")
                            )
                    )
                    .put("presentation", presentationSchema())
                    .put("presentation_spec", presentationSchema())
            )
            .put("required", JSONArray().put("address"))
    )

    override fun validate(arguments: JSONObject): Result<Unit> = parse(arguments).map {}

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = runCatching {
        parse(arguments).getOrThrow()
    }.fold(
        onSuccess = startNavigation,
        onFailure = { failure ->
            assistantCompletedFuture(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.INVALID_ARGUMENTS,
                    failure.message ?: "Invalid navigation arguments"
                )
            )
        }
    )

    private fun parse(arguments: JSONObject): Result<StartNavigationRequest> = runCatching {
        val address = NavigationAddressText.normalize(arguments.getString("address"))
        require(address.isNotBlank()) { "address must not be blank" }
        val mode = arguments.optString("mode", RouteModeName.WALKING).trim().uppercase(Locale.ROOT)
        require(mode in SUPPORTED_MODES) { "Unknown navigation mode: $mode" }
        require(mode == RouteModeName.WALKING) {
            "Only WALKING navigation is supported; requested mode $mode"
        }
        StartNavigationRequest(
            address = address,
            mode = mode,
            presentation = parsePresentation(arguments)
        )
    }

    private fun parsePresentation(arguments: JSONObject): PresentationSpec? {
        require(!(arguments.has("presentation") && arguments.has("presentation_spec"))) {
            "Use only one of presentation or presentation_spec"
        }
        val raw = arguments.optJSONObject("presentation")
            ?: arguments.optJSONObject("presentation_spec")
            ?: return null
        val mode = when (raw.getString("mode").trim().lowercase(Locale.ROOT)) {
            "fullscreen" -> PresentationMode.FULLSCREEN
            "windowed" -> PresentationMode.WINDOWED
            else -> error("Unknown presentation mode")
        }
        val expiry = when (raw.getString("on_expiry").trim().lowercase(Locale.ROOT)) {
            "restore_previous" -> PresentationExpiryPolicy.RESTORE_PREVIOUS
            "leave_windowed" -> PresentationExpiryPolicy.LEAVE_WINDOWED
            "close" -> PresentationExpiryPolicy.CLOSE
            else -> error("Unknown presentation expiry policy")
        }
        return PresentationSpec(
            mode = mode,
            durationSeconds = raw.getInt("duration_seconds"),
            onExpiry = expiry
        )
    }

    private fun presentationSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties", JSONObject()
                .put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("fullscreen").put("windowed")))
                .put(
                    "duration_seconds",
                    JSONObject()
                        .put("type", "integer")
                        .put("minimum", PresentationSpec.MIN_DURATION_SECONDS)
                        .put("maximum", PresentationSpec.MAX_DURATION_SECONDS)
                )
                .put(
                    "on_expiry",
                    JSONObject().put(
                        "type",
                        "string"
                    ).put("enum", JSONArray().put("restore_previous").put("leave_windowed").put("close"))
                )
        )
        .put("required", JSONArray().put("mode").put("duration_seconds").put("on_expiry"))

    private object RouteModeName {
        const val WALKING = "WALKING"
    }

    private companion object {
        val SUPPORTED_MODES = setOf("WALKING", "CYCLING", "DRIVING", "TRANSIT")
    }
}

/** Semantic assistant action that removes only the active navigation guidance. */
class StopNavigationSemanticToolHandler(
    private val stopNavigation: () -> CompletableFuture<AssistantToolExecution>,
    private val isAvailable: () -> Boolean = { true }
) : AssistantSemanticToolHandler {
    override val isAdvertised: Boolean
        get() = isAvailable()

    override val definition = AssistantToolDefinition(
        name = "stop_navigation",
        description = "Cancel or stop the active turn-by-turn navigation and remove its route guidance from the map",
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
    )

    override fun validate(arguments: JSONObject): Result<Unit> =
        if (arguments.length() == 0) Result.success(Unit)
        else Result.failure(IllegalArgumentException("stop_navigation does not accept arguments"))

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = stopNavigation()
}
