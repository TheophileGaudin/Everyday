package com.everyday.everyday_glasses.widgets.finance

import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateResult
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.AppQueries
import com.everyday.everyday_glasses.runtime.FinanceWidgetListener
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseSizedWidgetObject
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceTimeRange
import com.everyday.shared.sync.FinanceDashboardTileConfig
import java.util.concurrent.CompletableFuture
import org.json.JSONArray
import org.json.JSONObject

/** Puts the finance widget on the dashboard and connects it to the phone's market data. */
internal class FinancePlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val ui: UiThreadDispatcher get() = services.ui
    private val currentWidget: () -> FinanceWidget? = { services.singleton(WidgetTypeId.FINANCE) as? FinanceWidget }
    private val runPersistentUpdate: ((() -> Result<FinanceDashboardTileConfig>) -> Result<FinanceDashboardTileConfig>) =
        { action -> services.runPersistentMutation(changed = { result -> result.isSuccess }, action = action) }
    private val stableWidgetId: (BaseWidget) -> String = { widget -> services.stableId(widget) }
    override val lifecycle = WidgetLifecycleRegistration.singleton(
        FinanceWidget::class, FinanceWidgetState.Codec,
        current = currentWidget,
        create = ::create,
        restore = ::restore,
        capture = ::capture,
        teardown = { widget ->
            onDestroyed()
            widget.onDestroy()
        },
        onMinimizeChanged = ::onMinimizeChanged,
    )

    private fun create(): FinanceWidget {
        val widget = FinanceWidget(20f, 20f, FinanceWidget.DEFAULT_WIDTH, FinanceWidget.DEFAULT_HEIGHT)
        configure(widget)
        onOpened(widget, reason = "widget_toggled_on")
        return widget
    }

    private fun restore(state: FinanceWidgetState): FinanceWidget {
        val widget = FinanceWidget(state.x, state.y, state.width, state.height)
        configure(widget)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight) {
            widget.restoreDashboard(state.tiles, state.navigationIndex, state.tilingSpan)
        }
        onOpened(widget, reason = "widget_restored")
        return widget
    }

    private fun capture(widget: FinanceWidget): FinanceWidgetState {
        val c = services.captureRestore.extractCommonFields(widget)
        return FinanceWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            selectedSymbol = widget.currentSymbol,
            selectedRange = widget.currentRange.range,
            tiles = widget.getTileConfigs(),
            navigationIndex = widget.getNavigationIndex(),
            tilingSpan = widget.getTilingSpan(),
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    init {
        services.appData.subscribe(AppChannels.place) { place -> currentWidget()?.setCountryCode(place.countryCode) }
        services.appData.subscribe(AppChannels.finance) { snapshot ->
            currentWidget()?.let {
                it.applySnapshot(snapshot)
                services.notifyTransientChange()
            }
        }
        services.appData.subscribe(AppChannels.financeError) { message ->
            currentWidget()?.let {
                it.applyError(message)
                services.notifyTransientChange()
            }
        }
        services.appData.answer(AppQueries.financeTilesShown) { currentWidget()?.getVisibleTileConfigs().orEmpty() }
    }

    /** Update an existing finance tile on the UI thread and keep the widget instance intact. */
    fun updateTile(
        request: FinanceTileUpdateRequest
    ): CompletableFuture<FinanceTileUpdateResult> {
        val result = CompletableFuture<FinanceTileUpdateResult>()

        val accepted = ui.dispatch {
            val widget = currentWidget()

            if (widget == null) {
                result.complete(
                    FinanceTileUpdateResult(
                        applied = false,
                        message = "Finance widget is not visible"
                    )
                )
                return@dispatch
            }

            if (request.fullscreen == true && widget.isMinimized) {
                result.complete(
                    FinanceTileUpdateResult(
                        applied = false,
                        message = "Finance widget is minimized"
                    )
                )
                return@dispatch
            }

            val update = runPersistentUpdate {
                runCatching {
                    widget.updateTileInstrument(
                        target = request.target,
                        instrument = request.instrument,
                        range = request.range,
                        chartType = request.chartType,
                        slot = request.slot
                    )
                }.getOrElse { failure ->
                    Result.failure(failure)
                }.also { updateResult ->
                    if (
                        updateResult.isSuccess &&
                        request.fullscreen != null &&
                        request.fullscreen != widget.isFullscreen
                    ) {
                        widget.toggleFullscreen()
                    }
                }
            }

            update.fold(
                onSuccess = { config ->
                    val state = widget.semanticState(
                        stableWidgetId(widget)
                    )

                    result.complete(
                        FinanceTileUpdateResult(
                            applied = true,
                            message =
                                "Updated ${request.instrument.displayName} " +
                                        "on finance tile ${config.id}",
                            tileId = config.id,
                            instrument = request.instrument,
                            state = state
                        )
                    )
                },
                onFailure = { failure ->
                    result.complete(
                        FinanceTileUpdateResult(
                            applied = false,
                            message = failure.message
                                ?: "Requested finance tile is not present"
                        )
                    )
                }
            )
        }

        if (!accepted) {
            result.complete(
                FinanceTileUpdateResult(
                    applied = false,
                    message = "Glasses display is unavailable"
                )
            )
        }

        return result
    }

    private fun configure(widget: FinanceWidget) {
        widget.listener = object : FinanceWidgetListener {
            override fun onDataRequested(
                visibleConfigs: List<FinanceDashboardTileConfig>,
                refreshTileIds: List<String>,
                force: Boolean,
                reason: String
            ) {
                services.appData.requests?.requestFinanceData(
                    visibleConfigs,
                    refreshTileIds,
                    force,
                    reason
                )
            }
        }

        services.appData.latest(AppChannels.place)?.let { widget.setCountryCode(it.countryCode) }
    }

    private fun onMinimizeChanged(
            widget: FinanceWidget,
            minimized: Boolean
        ) {
            services.appData.requests?.onFinanceVisibilityChanged(!minimized)

            if (!minimized) {
                widget.requestData(
                    force = false,
                    reason = "finance_unminimized"
                )
            }
        }

    private fun onOpened(widget: FinanceWidget, reason: String) {
            if (widget.isMinimized) {
                services.appData.requests?.onFinanceVisibilityChanged(false)
                return
            }

            widget.requestData(force = true, reason = reason)
            services.appData.requests?.onFinanceVisibilityChanged(true)
        }

    private fun onDestroyed() {
            services.appData.requests?.onFinanceVisibilityChanged(false)
        }

}

/** What a saved dashboard keeps of the finance widget: its tiles and how they are laid out. */
data class FinanceWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val selectedSymbol: String = "^GSPC",
    val selectedRange: String = "1D",
    val tiles: List<FinanceDashboardTileConfig> = emptyList(),
    val navigationIndex: Int = 0,
    val tilingSpan: Int = 3,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<FinanceWidgetState>(WidgetTypeId.FINANCE, FinanceWidgetState::class) {
        override fun toJson(state: FinanceWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("selectedSymbol", state.selectedSymbol)
            put("selectedRange", state.selectedRange)
            put("navigationIndex", state.navigationIndex)
            put("tilingSpan", state.tilingSpan)
            put("tiles", JSONArray().apply {
                state.tiles.forEach { put(financeTileToJson(it)) }
            })
        }

        override fun fromJson(payload: JSONObject): FinanceWidgetState? =
            parseSizedWidgetObject(payload) { obj, c, w, h ->
                FinanceWidgetState(
                    x = c.x, y = c.y, width = w, height = h,
                    selectedSymbol = obj.optString("selectedSymbol", "^GSPC"),
                    selectedRange = obj.optString("selectedRange", "1D"),
                    tiles = parseFinanceTiles(obj, obj.optString("selectedSymbol", "^GSPC"), obj.optString("selectedRange", "1D")),
                    navigationIndex = obj.optInt("navigationIndex", 0),
                    tilingSpan = obj.optInt("tilingSpan", 3).coerceIn(1, 3),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }

        private fun financeTileToJson(tile: FinanceDashboardTileConfig): JSONObject =
            JSONObject().apply {
                put("id", tile.id)
                put("assetType", tile.assetType.name)
                put("symbol", tile.symbol)
                put("range", tile.range)
                put("chartType", tile.chartType.name)
                put("slot", tile.slot)
            }

        private fun parseFinanceTiles(obj: JSONObject, fallbackSymbol: String, fallbackRange: String): List<FinanceDashboardTileConfig> {
            val items = obj.optJSONArray("tiles") ?: JSONArray()
            val parsed = buildList {
                for (index in 0 until items.length()) {
                    val tile = items.optJSONObject(index) ?: continue
                    val assetType = FinanceAssetType.fromWireName(tile.optString("assetType"))
                    val symbol = tile.optString("symbol").takeIf { it.isNotBlank() } ?: continue
                    val range = FinanceTimeRange.fromRange(tile.optString("range", "1d")).range
                    add(
                        FinanceDashboardTileConfig(
                            id = tile.optString("id", "${assetType.name}_${symbol}_$range"),
                            assetType = assetType,
                            symbol = symbol,
                            range = range,
                            chartType = FinanceChartType.fromWireName(tile.optString("chartType")),
                            slot = tile.optInt("slot", index)
                        )
                    )
                }
            }
            if (parsed.isNotEmpty()) return parsed.take(FinanceWidget.MAX_TILES)
            return listOf(
                FinanceDashboardTileConfig(
                    id = "index_${fallbackSymbol}_${fallbackRange}",
                    assetType = FinanceAssetType.INDEX,
                    symbol = fallbackSymbol,
                    range = FinanceTimeRange.fromRange(fallbackRange).range,
                    chartType = FinanceChartType.LINE,
                    slot = 0
                )
            )
        }
    }
}