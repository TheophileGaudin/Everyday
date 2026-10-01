package com.everyday.everyday_glasses.widgets.finance

import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateResult
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.AppQueries
import com.everyday.everyday_glasses.runtime.FinanceWidgetListener
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.shared.sync.FinanceDashboardTileConfig
import java.util.concurrent.CompletableFuture

/** Puts the finance widget on the dashboard and connects it to the phone's market data. */
internal class FinanceModule(
    private val services: DashboardServices,
    private val ui: UiThreadDispatcher,
    private val currentWidget: () -> FinanceWidget?,
    private val runPersistentUpdate: ((() -> Result<FinanceDashboardTileConfig>) -> Result<FinanceDashboardTileConfig>),
    private val stableWidgetId: (BaseWidget) -> String,
) {
    val lifecycle = WidgetLifecycleRegistration.singleton(
        WidgetTypeId.FINANCE, FinanceWidget::class, DashboardPersistence.FinanceWidgetState::class,
        current = currentWidget,
        create = ::create,
        restore = ::restore,
        capture = { services.captureRestore.getFinanceWidgetState(it) },
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

    private fun restore(state: DashboardPersistence.FinanceWidgetState): FinanceWidget {
        val widget = FinanceWidget(state.x, state.y, state.width, state.height)
        configure(widget)
        services.captureRestore.restoreFinanceWidgetPosition(widget, state, services.viewportWidth, services.viewportHeight)
        onOpened(widget, reason = "widget_restored")
        return widget
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