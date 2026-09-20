package com.everyday.everyday_glasses.widgets.news

import com.everyday.everyday_glasses.contracts.WidgetDataProvider
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.NewsSourceUpdateResult
import com.everyday.everyday_glasses.runtime.NewsWidgetListener
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import java.util.concurrent.CompletableFuture

internal class NewsFeatureController(
    private val ui: UiThreadDispatcher,
    private val currentWidget: () -> NewsWidget?,
    private val currentProvider: () -> WidgetDataProvider?,
    private val withTransientChanges: (() -> Unit) -> Unit,
    private val notifyTransientChange: () -> Unit,
    private val notifyPersistentChange: () -> Unit,
    private val notifyFocusChange: (Boolean) -> Unit,
) {
    // Pending country code for news widget
    private var pendingCountryCode: String? = null

    fun updateCountryCode(countryCode: String) {
        pendingCountryCode = countryCode
        currentWidget()?.setCountryCode(countryCode)
    }

    fun applySnapshot(snapshot: NewsSnapshot) {
        val widget = currentWidget() ?: return

        if (
            widget.getSourceSpec() != snapshot.sourceSpec ||
            widget.getCountryCode() != snapshot.countryCode
        ) {
            return
        }

        withTransientChanges {
            widget.applySnapshot(snapshot)
            notifyTransientChange()
        }
    }

    fun applyError(message: String) {
        withTransientChanges {
            currentWidget()?.applyError(message)
            notifyTransientChange()
        }
    }

    fun currentCountryCode(): String? = currentWidget()?.getCountryCode() ?: pendingCountryCode
    fun currentSourceSpec(): NewsSourceSpec? = currentWidget()?.getSourceSpec()

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
    fun configure(widget: NewsWidget) {
        widget.listener = object : NewsWidgetListener {
            override fun onStateChanged() {
                notifyTransientChange()
            }

            override fun onPersistentStateChanged() {
                notifyPersistentChange()
            }

            override fun onDataRequested(
                countryCode: String,
                force: Boolean,
                reason: String
            ) {
                currentProvider()?.requestNewsData(
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
                currentProvider()?.requestNewsSourceData(
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

            override fun onSourceInputFocusChanged(focused: Boolean) {
                notifyFocusChange(focused)
            }
        }

        pendingCountryCode?.let(widget::setCountryCode)
    }

    fun onOpened(widget: NewsWidget, reason: String) {
        widget.requestData(force = true, reason = reason)
    }

}

