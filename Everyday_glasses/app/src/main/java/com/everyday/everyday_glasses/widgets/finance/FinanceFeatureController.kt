package com.everyday.everyday_glasses.widgets.finance

import com.everyday.everyday_glasses.connectivity.WidgetDataProvider
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateResult
import com.everyday.everyday_glasses.runtime.FinanceWidgetListener
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceSnapshot
import java.util.concurrent.CompletableFuture

internal class FinanceFeatureController(
    private val ui: UiThreadDispatcher,
    private val currentWidget: () -> FinanceWidget?,
    private val currentProvider: () -> WidgetDataProvider?,
    private val runPersistentUpdate: ((() -> Result<FinanceDashboardTileConfig>) -> Result<FinanceDashboardTileConfig>),
    private val notifyPersistentChange: () -> Unit,
    private val notifyTransientChange: () -> Unit,
    private val stableWidgetId: (BaseWidget) -> String,
) {
    // Pending country code for finance widget
    private var pendingCountryCode: String? = null

    fun updateCountryCode(countryCode: String) {
        pendingCountryCode = countryCode
        currentWidget()?.setCountryCode(countryCode)
    }

    fun applySnapshot(snapshot: FinanceSnapshot, notify: Boolean = true) {
        currentWidget()?.let {
            it.applySnapshot(snapshot)
            if (notify) notifyTransientChange()
        }
    }

    fun applyError(message: String) {
        currentWidget()?.let {
            it.applyError(message)
            notifyTransientChange()
        }
    }

    fun currentVisibleConfigs(): List<FinanceDashboardTileConfig> =
        currentWidget()?.getVisibleTileConfigs().orEmpty()

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

    fun configure(widget: FinanceWidget) {
        widget.listener = object : FinanceWidgetListener {
            override fun onStateChanged() {
                notifyTransientChange()
            }

            override fun onPersistentStateChanged() {
                notifyPersistentChange()
            }

            override fun onDataRequested(
                visibleConfigs: List<FinanceDashboardTileConfig>,
                refreshTileIds: List<String>,
                force: Boolean,
                reason: String
            ) {
                currentProvider()?.requestFinanceData(
                    visibleConfigs,
                    refreshTileIds,
                    force,
                    reason
                )
            }
        }

        pendingCountryCode?.let(widget::setCountryCode)
    }

    fun onMinimizeChanged(
            widget: FinanceWidget,
            minimized: Boolean
        ) {
            currentProvider()?.onFinanceVisibilityChanged(!minimized)

            if (!minimized) {
                widget.requestData(
                    force = false,
                    reason = "finance_unminimized"
                )
            }
        }

    fun onOpened(widget: FinanceWidget, reason: String) {
            if (widget.isMinimized) {
                currentProvider()?.onFinanceVisibilityChanged(false)
                return
            }

            widget.requestData(force = true, reason = reason)
            currentProvider()?.onFinanceVisibilityChanged(true)
        }

    fun onDestroyed() {
            currentProvider()?.onFinanceVisibilityChanged(false)
        }

}