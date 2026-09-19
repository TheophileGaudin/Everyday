package com.everyday.everyday_glasses.interaction.assistant

import com.everyday.everyday_glasses.ui.widgets.FinanceWidget
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceInstrument
import com.everyday.shared.sync.FinanceTimeRange
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CompletableFuture

enum class FinanceTileTargetMode {
    EXPLICIT,
    SELECTED,
    FIRST_VISIBLE,
    TOP_LEFT_VISIBLE
}

data class FinanceTileTarget(
    val mode: FinanceTileTargetMode,
    val tileId: String? = null
) {
    init {
        require(mode != FinanceTileTargetMode.EXPLICIT || !tileId.isNullOrBlank()) {
            "An explicit finance tile target requires tileId"
        }
    }

    companion object {
        fun explicit(tileId: String): FinanceTileTarget =
            FinanceTileTarget(FinanceTileTargetMode.EXPLICIT, tileId.trim())

        fun selected(): FinanceTileTarget = FinanceTileTarget(FinanceTileTargetMode.SELECTED)

        fun firstVisible(): FinanceTileTarget = FinanceTileTarget(FinanceTileTargetMode.FIRST_VISIBLE)

        fun topLeftVisible(): FinanceTileTarget =
            FinanceTileTarget(FinanceTileTargetMode.TOP_LEFT_VISIBLE)
    }
}

data class FinanceTileSemanticState(
    val id: String,
    val visible: Boolean,
    val visibleOrder: Int?,
    val slot: Int,
    val selected: Boolean,
    val assetType: FinanceAssetType,
    val symbol: String,
    val displayName: String,
    val range: FinanceTimeRange,
    val chartStyle: FinanceChartType
) {
    val tileId: String get() = id
    val configurationId: String get() = id
    val chartType: FinanceChartType get() = chartStyle

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("visible", visible)
        .put("visible_order", visibleOrder ?: JSONObject.NULL)
        .put("slot", slot)
        .put("selected", selected)
        .put("asset_type", assetType.name)
        .put("symbol", symbol)
        .put("display_name", displayName)
        .put("range", range.range)
        .put("range_label", range.label)
        .put("chart_style", chartStyle.name)
}

data class FinanceWidgetSemanticState(
    val widgetId: String,
    val minimized: Boolean,
    val fullscreen: Boolean,
    val tiles: List<FinanceTileSemanticState>,
    val visibleTileIds: List<String>,
    val selectedTileId: String?,
    val topLeftVisibleTileId: String?
) {
    val visible: Boolean get() = !minimized
    val windowed: Boolean get() = !fullscreen
    val selectedTile: FinanceTileSemanticState?
        get() = tiles.firstOrNull { it.id == selectedTileId }

    fun toJson(): JSONObject = JSONObject()
        .put("widget_id", widgetId)
        .put("visible", visible)
        .put("minimized", minimized)
        .put("fullscreen", fullscreen)
        .put("windowed", windowed)
        .put("visible_tile_ids", JSONArray().apply { visibleTileIds.forEach(::put) })
        .put("top_left_visible_tile_id", topLeftVisibleTileId ?: JSONObject.NULL)
        .put("selected_tile_id", selectedTileId ?: JSONObject.NULL)
        .put("tiles", JSONArray().apply { tiles.forEach { put(it.toJson()) } })
}

/** Pure target resolution keeps navigation/reordering semantics independently testable. */
object FinanceTileTargetResolver {
    fun resolve(
        target: FinanceTileTarget,
        tiles: List<FinanceTileSemanticState>,
        visibleTileIds: List<String>,
        selectedTileId: String?
    ): String? {
        val byId = tiles.associateBy { it.id }
        return when (target.mode) {
            FinanceTileTargetMode.EXPLICIT ->
                target.tileId?.takeIf(byId::containsKey)

            FinanceTileTargetMode.SELECTED ->
                selectedTileId?.takeIf(byId::containsKey)

            FinanceTileTargetMode.FIRST_VISIBLE ->
                visibleTileIds.firstOrNull(byId::containsKey)

            FinanceTileTargetMode.TOP_LEFT_VISIBLE ->
                tiles.asSequence()
                    .filter { it.visible && it.id in visibleTileIds }
                    .sortedWith(compareBy<FinanceTileSemanticState> { it.slot }
                        .thenBy { it.visibleOrder ?: Int.MAX_VALUE }
                        .thenBy { it.id })
                    .map { it.id }
                    .firstOrNull()
        }
    }
}

data class FinanceTileUpdateRequest(
    val target: FinanceTileTarget,
    val instrument: FinanceInstrument,
    val range: FinanceTimeRange? = null,
    val chartType: FinanceChartType? = null,
    val slot: Int? = null,
    val fullscreen: Boolean? = null
)

data class FinanceTileUpdateResult(
    val applied: Boolean,
    val message: String,
    val tileId: String? = null,
    val instrument: FinanceInstrument? = null,
    val state: FinanceWidgetSemanticState? = null
)

object FinanceAssistantPolicy {
    const val DEFAULT_VISIBLE_TARGET = "TOP_LEFT_VISIBLE"

    fun defaultTarget(): FinanceTileTarget = FinanceTileTarget.topLeftVisible()

    const val PROMPT_INSTRUCTION: String =
        "For finance requests, use update_finance_tile. When the user says the visible finance " +
            "widget or chart without a more precise target, set target to TOP_LEFT_VISIBLE. " +
            "Use tile_id only for a named stable tile and preserve omitted tile settings."
}

/** Exposes finance state through the Work Package 01 extensible semantic-fact channel. */
fun FinanceWidgetSemanticState.toAssistantSemanticFacts(): List<AssistantSemanticFact> = buildList {
    add(AssistantSemanticFact("finance_widget_id", widgetId))
    add(AssistantSemanticFact("finance_visible", visible.toString()))
    add(AssistantSemanticFact("finance_minimized", minimized.toString()))
    add(AssistantSemanticFact("finance_fullscreen", fullscreen.toString()))
    add(AssistantSemanticFact("finance_windowed", windowed.toString()))
    add(AssistantSemanticFact("finance_visible_tile_order", JSONArray().apply {
        visibleTileIds.forEach(::put)
    }.toString()))
    add(AssistantSemanticFact("finance_top_left_visible_tile_id", topLeftVisibleTileId.orEmpty()))
    add(AssistantSemanticFact("finance_selected_tile_id", selectedTileId.orEmpty()))
    tiles.forEach { tile ->
        add(AssistantSemanticFact("finance_tile", tile.toJson().toString()))
    }
    add(AssistantSemanticFact("finance_state", toJson().toString()))
}

/** Semantic tool that updates an existing finance tile without creating a widget. */
class UpdateFinanceTileSemanticToolHandler(
    private val updateTile: (FinanceTileUpdateRequest) -> CompletableFuture<FinanceTileUpdateResult>,
    private val isAvailable: () -> Boolean = { true }
) : AssistantSemanticToolHandler {

    override val isAdvertised: Boolean
        get() = isAvailable()

    override val definition = AssistantToolDefinition(
        name = "update_finance_tile",
        description =
            "Update the instrument on an existing Finance dashboard tile and refresh its data; " +
                "target defaults to TOP_LEFT_VISIBLE when no target is specified",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put(
                        "instrument", JSONObject()
                            .put("type", "string")
                            .put("maxLength", 128)
                            .put("description", "A catalogue name, alias, or symbol such as FTSE100")
                    )
                    .put(
                        "target", JSONObject()
                            .put("type", "string")
                            .put(
                                "enum",
                                JSONArray()
                                    .put(FinanceTileTargetMode.SELECTED.name)
                                    .put(FinanceTileTargetMode.FIRST_VISIBLE.name)
                                    .put(FinanceTileTargetMode.TOP_LEFT_VISIBLE.name)
                            )
                            .put("default", FinanceAssistantPolicy.DEFAULT_VISIBLE_TARGET)
                    )
                    .put(
                        "tile_id", JSONObject()
                            .put("type", "string")
                            .put("description", "Explicit stable finance tile configuration ID")
                    )
                    .put("range", rangeSchema())
                    .put(
                        "chart_style", JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray().put("LINE").put("CANDLE"))
                    )
                    .put(
                        "slot", JSONObject()
                            .put("type", "integer")
                            .put("minimum", 0)
                            .put("maximum", FinanceWidget.Companion.MAX_TILES - 1)
                            .put("description", "Optional dashboard placement slot")
                    )
                    .put(
                        "fullscreen", JSONObject()
                            .put("type", "boolean")
                            .put("description", "Optional existing finance widget fullscreen state")
                    )
            )
            .put("required", JSONArray().put("instrument"))
    )

    override fun validate(arguments: JSONObject): Result<Unit> = parse(arguments).map { Unit }

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> {
        val request = parse(arguments).getOrElse { failure ->
            return assistantCompletedFuture(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.INVALID_ARGUMENTS,
                    failure.message ?: "Invalid finance tile arguments"
                )
            )
        }
        return updateTile(request).thenApply { result ->
            AssistantToolExecution(
                success = result.applied,
                code = if (result.applied) {
                    AssistantToolExecutionCodes.SUCCESS
                } else {
                    AssistantToolExecutionCodes.EXECUTION_FAILED
                },
                message = result.message
            )
        }
    }

    private fun parse(arguments: JSONObject): Result<FinanceTileUpdateRequest> = runCatching {
        val rawInstrument = arguments.optString("instrument").trim()
            .ifBlank { arguments.optString("instrument_name").trim() }
        require(rawInstrument.isNotBlank()) { "instrument is required" }
        val instrument = FinanceDataProvider.resolveInstrument(rawInstrument)
            ?: error("Unknown finance instrument: $rawInstrument")

        val tileId = arguments.optString("tile_id").trim().takeIf { it.isNotBlank() }
        val rawTarget = arguments.optString("target").trim()
        val target = when {
            tileId != null -> FinanceTileTarget.explicit(tileId)
            rawTarget.isBlank() -> FinanceAssistantPolicy.defaultTarget()
            rawTarget.equals(FinanceTileTargetMode.SELECTED.name, ignoreCase = true) ->
                FinanceTileTarget.selected()
            rawTarget.equals(FinanceTileTargetMode.FIRST_VISIBLE.name, ignoreCase = true) ->
                FinanceTileTarget.firstVisible()
            rawTarget.equals(FinanceTileTargetMode.TOP_LEFT_VISIBLE.name, ignoreCase = true) ->
                FinanceTileTarget.topLeftVisible()
            rawTarget.equals(FinanceTileTargetMode.EXPLICIT.name, ignoreCase = true) ->
                error("tile_id is required for an explicit finance target")
            else -> FinanceTileTarget.explicit(rawTarget)
        }

        FinanceTileUpdateRequest(
            target = target,
            instrument = instrument,
            range = parseRange(arguments.optString("range").ifBlank {
                arguments.optString("date_range")
            }),
            chartType = parseChartType(
                arguments.optString("chart_style").ifBlank {
                    arguments.optString("chart_type")
                }
            ),
            slot = parseSlot(arguments),
            fullscreen = if (arguments.has("fullscreen") && !arguments.isNull("fullscreen")) {
                arguments.getBoolean("fullscreen")
            } else {
                null
            }
        )
    }

    private fun rangeSchema(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray().apply {
            FinanceTimeRange.values().forEach {
                put(it.range)
                put(it.label)
            }
        })

    private fun parseRange(raw: String): FinanceTimeRange? {
        if (raw.isBlank()) return null
        val normalized = raw.trim().uppercase(Locale.US).replace(" ", "")
        return FinanceTimeRange.values().firstOrNull {
            it.range.equals(raw.trim(), ignoreCase = true) ||
                it.label.equals(raw.trim(), ignoreCase = true) ||
                it.range.replace(" ", "").uppercase(Locale.US) == normalized
        } ?: error("Unknown finance range: $raw")
    }

    private fun parseChartType(raw: String): FinanceChartType? {
        if (raw.isBlank()) return null
        return when (raw.trim().uppercase(Locale.US)) {
            "LINE", "LINE_CHART" -> FinanceChartType.LINE
            "CANDLE", "CANDLESTICK", "CANDLE_CHART" -> FinanceChartType.CANDLE
            else -> error("Unknown finance chart style: $raw")
        }
    }

    private fun parseSlot(arguments: JSONObject): Int? {
        val raw = when {
            arguments.has("slot") && !arguments.isNull("slot") -> arguments.getInt("slot")
            arguments.has("placement") && !arguments.isNull("placement") -> arguments.getInt("placement")
            else -> return null
        }
        require(raw in 0 until FinanceWidget.Companion.MAX_TILES) {
            "Finance tile slot must be between 0 and ${FinanceWidget.Companion.MAX_TILES - 1}"
        }
        return raw
    }
}
