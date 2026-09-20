package com.everyday.everyday_glasses.interaction.assistant.widget_adapters

import com.everyday.everyday_glasses.interaction.assistant.AssistantSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolDefinition
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecution
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionContext
import com.everyday.everyday_glasses.interaction.assistant.assistantCompletedFuture
import com.everyday.everyday_glasses.widgets.map.backend.MapPoiCategoryCatalog
import com.everyday.everyday_glasses.widgets.map.backend.PoiDisplayConfig
import com.everyday.everyday_glasses.widgets._common.PresentationExpiryPolicy
import com.everyday.everyday_glasses.widgets._common.PresentationMode
import com.everyday.everyday_glasses.widgets._common.PresentationSpec
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

data class ConfigureMapPoisRequest(
    val config: PoiDisplayConfig,
    val presentation: PresentationSpec? = null
)

/** Semantic assistant action for configuring the map's POI display. */
class ConfigureMapPoisSemanticToolHandler(
    private val applyConfiguration: (ConfigureMapPoisRequest) -> CompletableFuture<AssistantToolExecution>,
    private val isAvailable: () -> Boolean = { true }
) : AssistantSemanticToolHandler {
    override val isAdvertised: Boolean
        get() = isAvailable()

    override val definition = AssistantToolDefinition(
        name = "configure_map_pois",
        description = "Configure which map points of interest are displayed and optionally show the map temporarily",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put(
                        "categories", JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put(
                                "items",
                                JSONObject().put("type", "string").put("enum", categoryEnum())
                            )
                    )
                    .put(
                        "radius_m", JSONObject()
                            .put("type", "integer")
                            .put("minimum", PoiDisplayConfig.MIN_RADIUS_M)
                            .put("maximum", PoiDisplayConfig.MAX_RADIUS_M)
                    )
                    .put(
                        "max_results", JSONObject()
                            .put("type", "integer")
                            .put("minimum", PoiDisplayConfig.MIN_RESULTS)
                            .put("maximum", PoiDisplayConfig.MAX_RESULTS)
                    )
                    .put("reset_default", JSONObject().put("type", "boolean"))
                    .put("presentation", presentationSchema())
            )
    )

    override fun validate(arguments: JSONObject): Result<Unit> = parse(arguments).map { }

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = runCatching {
        parse(arguments).getOrThrow()
    }.fold(
        onSuccess = applyConfiguration,
        onFailure = { failure ->
            assistantCompletedFuture(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.INVALID_ARGUMENTS,
                    failure.message ?: "Invalid POI configuration"
                )
            )
        }
    )

    private fun parse(arguments: JSONObject): Result<ConfigureMapPoisRequest> = runCatching {
        val presentation = parsePresentation(arguments)
        val resetDefault = arguments.optBoolean("reset_default", false)
        if (resetDefault) {
            require(!arguments.has("categories")) { "reset_default cannot include categories" }
            require(!arguments.has("radius_m")) { "reset_default cannot include radius_m" }
            require(!arguments.has("max_results")) { "reset_default cannot include max_results" }
            return@runCatching ConfigureMapPoisRequest(PoiDisplayConfig.DEFAULT, presentation)
        }

        val rawCategories = arguments.optJSONArray("categories")
            ?: error("categories is required unless reset_default is true")
        require(rawCategories.length() > 0) { "At least one POI category is required" }
        val requestedCategories = buildSet {
            for (index in 0 until rawCategories.length()) {
                val category = rawCategories.optString(index).trim()
                require(category.isNotBlank()) { "POI category must not be blank" }
                addAll(MapPoiCategoryCatalog.normalize(category))
            }
        }
        require(requestedCategories.isNotEmpty()) { "At least one POI category is required" }

        val radius = when {
            arguments.has("radius_m") -> arguments.getInt("radius_m")
            arguments.has("radius_metres") -> arguments.getInt("radius_metres")
            else -> MapPoiCategoryCatalog.inferredRadiusM(requestedCategories)
        }
        val maxResults = when {
            arguments.has("max_results") -> arguments.getInt("max_results")
            arguments.has("result_count") -> arguments.getInt("result_count")
            else -> PoiDisplayConfig.DEFAULT_MAX_RESULTS
        }
        ConfigureMapPoisRequest(
            config = PoiDisplayConfig.custom(requestedCategories, radius, maxResults),
            presentation = presentation
        )
    }

    private fun categoryEnum(): JSONArray = JSONArray().apply {
        MapPoiCategoryCatalog.definitions.forEach { put(it.id) }
        put("emergency")
        put("transport")
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
                .put("on_expiry", JSONObject().put("type", "string").put("enum", JSONArray().put("leave_windowed")))
        )
        .put("required", JSONArray().put("mode").put("duration_seconds").put("on_expiry"))

    private fun parsePresentation(arguments: JSONObject): PresentationSpec? {
        val raw = arguments.optJSONObject("presentation")
            ?: arguments.optJSONObject("presentation_spec")
            ?: return null
        val mode = when (raw.getString("mode").lowercase()) {
            "fullscreen" -> PresentationMode.FULLSCREEN
            "windowed" -> PresentationMode.WINDOWED
            else -> error("Unknown presentation mode")
        }
        val expiry = raw.getString("on_expiry").lowercase()
        require(expiry == "leave_windowed") {
            "Map POI presentations must use leave_windowed expiry"
        }
        return PresentationSpec(
            mode = mode,
            durationSeconds = raw.getInt("duration_seconds"),
            onExpiry = PresentationExpiryPolicy.LEAVE_WINDOWED
        )
    }
}
