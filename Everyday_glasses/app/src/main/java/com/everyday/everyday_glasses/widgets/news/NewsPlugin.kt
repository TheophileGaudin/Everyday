package com.everyday.everyday_glasses.widgets.news

import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.NewsSourceUpdateResult
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseSizedWidgetObject
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.AppQueries
import com.everyday.everyday_glasses.runtime.NewsWidgetListener
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.shared.sync.NewsSnapshot
import com.everyday.shared.sync.NewsSourceSpec
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** Puts the news widget on the dashboard and connects it to the phone's news feeds. */
internal class NewsPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val ui: UiThreadDispatcher get() = services.ui
    private val currentWidget: () -> NewsWidget? = { services.singleton(TYPE) as? NewsWidget }
    override val lifecycle = WidgetLifecycleRegistration.singleton(
        NewsWidget::class, NewsWidgetState.Codec,
        current = currentWidget,
        create = ::create,
        restore = ::restore,
        capture = ::capture,
    )

    private fun create(): NewsWidget {
        val x = (services.viewportWidth - NewsWidget.DEFAULT_WIDTH - 20f).coerceAtLeast(20f)
        val widget = NewsWidget(x, 20f, NewsWidget.DEFAULT_WIDTH, NewsWidget.DEFAULT_HEIGHT)
        configure(widget)
        onOpened(widget, reason = "widget_toggled_on")
        return widget
    }

    private fun restore(state: NewsWidgetState): NewsWidget {
        val widget = NewsWidget(state.x, state.y, state.width, state.height)
        configure(widget)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight) {
            widget.setSourceSpec(state.sourceSpec)
            widget.setCountryCode(state.countryCode)
            widget.setSelectedIndex(state.selectedIndex)
            widget.setFontScale(state.fontScale)
        }
        onOpened(widget, reason = "widget_restored")
        return widget
    }

    private fun capture(widget: NewsWidget): NewsWidgetState {
        val c = services.captureRestore.extractCommonFields(widget)
        return NewsWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            countryCode = widget.getCountryCode(),
            sourceSpec = widget.getSourceSpec(),
            selectedIndex = widget.getSelectedIndex(),
            fontScale = widget.getFontScale(),
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
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

    /** The news widget as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("news")
        override val descriptor = WidgetDescriptor(
            TYPE,
            "News",
            WidgetInstancePolicy.SINGLETON,
            order = 5,
            category = "information",
            storesClosedTemplate = true,
            menuOrder = 6
        )
        override val codec get() = NewsWidgetState.Codec
        override fun create(context: WidgetContext) = NewsPlugin(context)
    }

}

/** What a saved dashboard keeps of the news widget: its source, country, selection and font scale. */
data class NewsWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val countryCode: String = "US",
    val sourceSpec: NewsSourceSpec = NewsSourceSpec.DEFAULT_LOCATION,
    val selectedIndex: Int = 0,
    val fontScale: Float = 1f,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<NewsWidgetState>(NewsPlugin.TYPE, NewsWidgetState::class) {
        override fun toJson(state: NewsWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("countryCode", state.countryCode)
            put("sourceKind", state.sourceSpec.kind.name)
            state.sourceSpec.value?.let { put("sourceValue", it) }
            put("selectedIndex", state.selectedIndex)
            put("fontScale", state.fontScale)
        }

        override fun fromJson(payload: JSONObject): NewsWidgetState? =
            parseSizedWidgetObject(payload) { obj, c, w, h ->
                NewsWidgetState(
                    x = c.x, y = c.y, width = w, height = h,
                    countryCode = obj.optString("countryCode", "US"),
                    sourceSpec = parseNewsSource(obj),
                    selectedIndex = obj.optInt("selectedIndex", 0),
                    fontScale = obj.optDouble("fontScale", 1.0).toFloat(),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }

        private fun parseNewsSource(obj: JSONObject): NewsSourceSpec =
            NewsSourceSpec.fromWire(
                obj.optString("sourceKind").takeIf { it.isNotBlank() },
                obj.optString("sourceValue").takeIf { it.isNotBlank() }
            ) ?: NewsSourceSpec.DEFAULT_LOCATION
    }
}

