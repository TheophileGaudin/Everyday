package com.everyday.everyday_glasses.widgets.news

import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.NewsSourceUpdateResult
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.AppQueries
import com.everyday.everyday_glasses.runtime.NewsWidgetListener
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import java.util.concurrent.CompletableFuture

/** Puts the news widget on the dashboard and connects it to the phone's news feeds. */
internal class NewsModule(
    private val services: DashboardServices,
    private val ui: UiThreadDispatcher,
    private val currentWidget: () -> NewsWidget?,
) {
    val lifecycle = WidgetLifecycleRegistration.singleton(
        WidgetTypeId.NEWS, NewsWidget::class, DashboardPersistence.NewsWidgetState::class,
        current = currentWidget,
        create = ::create,
        restore = ::restore,
        capture = { services.captureRestore.getNewsWidgetState(it) },
    )

    private fun create(): NewsWidget {
        val x = (services.viewportWidth - NewsWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
        val widget = NewsWidget(x, 20f, NewsWidget.DEFAULT_WIDTH, NewsWidget.DEFAULT_HEIGHT)
        configure(widget)
        onOpened(widget, reason = "widget_toggled_on")
        return widget
    }

    private fun restore(state: DashboardPersistence.NewsWidgetState): NewsWidget {
        val widget = NewsWidget(state.x, state.y, state.width, state.height)
        configure(widget)
        services.captureRestore.restoreNewsWidgetPosition(widget, state, services.viewportWidth, services.viewportHeight)
        onOpened(widget, reason = "widget_restored")
        return widget
    }

    /** Lifting the head to wake the display shows the next news item; saved when the selection changed. */
    fun onHeadUpWake() {
        val widget = currentWidget() ?: return
        val before = widget.getSelectedIndex()
        services.runClassifiedInput(
            action = { widget.showNextItemOnHeadUpWake(); true },
            changed = { widget.getSelectedIndex() != before },
        )
    }

    init {
        services.appData.subscribe(AppChannels.place) { place -> currentWidget()?.setCountryCode(place.countryCode) }
        services.appData.subscribe(AppChannels.news, ::applySnapshot)
        services.appData.subscribe(AppChannels.newsError) { message ->
            services.withTransientChanges {
                currentWidget()?.applyError(message)
                services.notifyTransientChange()
            }
        }
        services.appData.answer(AppQueries.newsCountry) {
            currentWidget()?.getCountryCode() ?: services.appData.latest(AppChannels.place)?.countryCode
        }
        services.appData.answer(AppQueries.newsSource) { currentWidget()?.getSourceSpec() }
    }

    /** Only the snapshot for the source and country the widget shows is applied. */
    private fun applySnapshot(snapshot: NewsSnapshot) {
        val widget = currentWidget() ?: return

        if (
            widget.getSourceSpec() != snapshot.sourceSpec ||
            widget.getCountryCode() != snapshot.countryCode
        ) {
            return
        }

        services.withTransientChanges {
            widget.applySnapshot(snapshot)
            services.notifyTransientChange()
        }
    }

    /** Update the existing singleton News widget from an assistant request. */
    fun setSource(sourceSpec: NewsSourceSpec): CompletableFuture<NewsSourceUpdateResult> {
        val result = CompletableFuture<NewsSourceUpdateResult>()
        val accepted = ui.dispatch {
            val widget = currentWidget()
            if (widget == null) {
                result.complete(
                    NewsSourceUpdateResult(
                        applied = false,
                        message = "News widget is not visible"
                    )
                )
                return@dispatch
            }
            widget.requestSource(sourceSpec) { fetchResult ->
                fetchResult.fold(
                    onSuccess = {
                        result.complete(
                            NewsSourceUpdateResult(
                                applied = true,
                                message = "News source updated",
                                sourceSpec = sourceSpec
                            )
                        )
                    },
                    onFailure = { failure ->
                        result.complete(
                            NewsSourceUpdateResult(
                                applied = false,
                                message = failure.message ?: "News source could not be loaded"
                            )
                        )
                    }
                )
            }
        }
        if (!accepted) {
            result.complete(
                NewsSourceUpdateResult(
                    applied = false,
                    message = "Glasses display is unavailable"
                )
            )
        }
        return result
    }
    private fun configure(widget: NewsWidget) {
        widget.listener = object : NewsWidgetListener {
            override fun onDataRequested(
                countryCode: String,
                force: Boolean,
                reason: String
            ) {
                services.appData.requests?.requestNewsData(
                    countryCode,
                    force,
                    reason
                )
            }

            override fun onSourceDataRequested(
                sourceSpec: NewsSourceSpec,
                countryCode: String,
                force: Boolean,
                reason: String,
                completion: (Result<NewsSnapshot>) -> Unit
            ) {
                services.appData.requests?.requestNewsSourceData(
                    sourceSpec,
                    countryCode,
                    force,
                    reason,
                    completion
                ) ?: completion(
                    Result.failure(
                        IllegalStateException(
                            "News data service is unavailable"
                        )
                    )
                )
            }
        }

        services.appData.latest(AppChannels.place)?.let { widget.setCountryCode(it.countryCode) }
    }

    private fun onOpened(widget: NewsWidget, reason: String) {
        widget.requestData(force = true, reason = reason)
    }

}

