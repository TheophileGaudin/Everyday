package com.everyday.everyday_glasses.managers

import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.ui.widgets.BaseWidget
import com.everyday.everyday_glasses.ui.widgets.BrowserWidget
import com.everyday.everyday_glasses.ui.widgets.CalendarWidget
import com.everyday.everyday_glasses.ui.widgets.FileBrowserWidget
import com.everyday.everyday_glasses.ui.widgets.FinanceWidget
import com.everyday.everyday_glasses.ui.widgets.ImageWidget
import com.everyday.everyday_glasses.ui.widgets.LocationWidget
import com.everyday.everyday_glasses.ui.widgets.MapWidget
import com.everyday.everyday_glasses.ui.widgets.NewsWidget
import com.everyday.everyday_glasses.ui.widgets.NotificationsWidget
import com.everyday.everyday_glasses.ui.widgets.PdfWidget
import com.everyday.everyday_glasses.ui.widgets.ScreenMirrorWidget
import com.everyday.everyday_glasses.ui.widgets.SpeedometerWidget
import com.everyday.everyday_glasses.ui.widgets.StatusBarWidget
import com.everyday.everyday_glasses.ui.widgets.SubtitleWidget
import com.everyday.everyday_glasses.ui.widgets.TextBoxWidget
import com.everyday.everyday_glasses.ui.widgets.VideoWidget
import com.everyday.everyday_glasses.data_models.WidgetDescriptor
import com.everyday.everyday_glasses.data_models.WidgetDescriptors
import com.everyday.everyday_glasses.ui.widgets.common.WidgetHitTestCache
import com.everyday.everyday_glasses.data_models.WidgetInstancePolicy
import com.everyday.everyday_glasses.data_models.WidgetTypeId
import java.util.IdentityHashMap

/**
 * The authoritative inventory of live widgets.
 *
 * Membership is grouped by [WidgetTypeId], while callers receive stable descriptor
 * ordering and never mutate the backing collections directly. The typed properties and
 * lists remain temporarily for WidgetContainer compatibility; they delegate to the same
 * registry operations and do not hold duplicate state.
 */
class WidgetRegistry {

    companion object {
        private const val TAG = "WidgetRegistry"
    }

    private val widgetsByType: Map<WidgetTypeId, MutableList<BaseWidget>> =
        WidgetTypeId.entries.associateWith { ArrayList<BaseWidget>() }
    private val stableIds = IdentityHashMap<BaseWidget, String>()
    private val widgetsByStableId = mutableMapOf<String, BaseWidget>()
    private val nextInstanceSequence = mutableMapOf<WidgetTypeId, Int>()
    private val hitTestCache = WidgetHitTestCache<BaseWidget>(::allWidgets) { it.zOrder }

    private var sortedWidgetsCache: List<BaseWidget>? = null
    private var nextZOrder = 0

    /** The sole fullscreen widget, or null when all widgets are windowed. */
    var fullscreenWidget: BaseWidget? = null
        private set

    val textWidgets: List<TextBoxWidget> get() = widgetsAs(WidgetTypeId.TEXT)
    val browserWidgets: List<BrowserWidget> get() = widgetsAs(WidgetTypeId.BROWSER)
    val imageWidgets: List<ImageWidget> get() = widgetsAs(WidgetTypeId.IMAGE)
    val videoWidgets: List<VideoWidget> get() = widgetsAs(WidgetTypeId.VIDEO)
    val pdfWidgets: List<PdfWidget> get() = widgetsAs(WidgetTypeId.PDF)
    val fileBrowserWidgets: List<FileBrowserWidget> get() = widgetsAs(WidgetTypeId.FILES)

    val statusBarWidget: StatusBarWidget? get() = singletonAs(WidgetTypeId.STATUS)
    val locationWidget: LocationWidget? get() = singletonAs(WidgetTypeId.LOCATION)
    val mapWidget: MapWidget? get() = singletonAs(WidgetTypeId.MAP)
    val calendarWidget: CalendarWidget? get() = singletonAs(WidgetTypeId.CALENDAR)
    val financeWidget: FinanceWidget? get() = singletonAs(WidgetTypeId.FINANCE)
    val newsWidget: NewsWidget? get() = singletonAs(WidgetTypeId.NEWS)
    val notificationsWidget: NotificationsWidget? get() = singletonAs(WidgetTypeId.NOTIFICATIONS)
    val speedometerWidget: SpeedometerWidget? get() = singletonAs(WidgetTypeId.SPEEDOMETER)
    val subtitleWidget: SubtitleWidget? get() = singletonAs(WidgetTypeId.SUBTITLE)
    val screenMirrorWidget: ScreenMirrorWidget? get() = singletonAs(WidgetTypeId.MIRROR)

    /** Registers [widget], assigns its z-order and returns its durable runtime ID. */
    fun add(widget: BaseWidget, requestedStableId: String? = null): String =
        addAt(widget, requestedStableId, widgetsFor(widget.type).size)

    /** Removes [widget] if it is registered, returning whether membership changed. */
    fun remove(widget: BaseWidget): Boolean {
        val values = widgetsFor(widget.type)
        if (!values.remove(widget)) return false
        stableIds.remove(widget)?.let(widgetsByStableId::remove)
        if (fullscreenWidget === widget) fullscreenWidget = null
        invalidateOrdering()
        return true
    }

    fun isRegistered(widget: BaseWidget): Boolean = stableIds.containsKey(widget)

    fun stableId(widget: BaseWidget): String = requireNotNull(stableIds[widget]) {
        "Widget ${widget::class.simpleName} is not registered"
    }

    fun findByStableId(stableId: String): BaseWidget? = widgetsByStableId[stableId]

    /**
     * Replaces an already-registered widget's generated ID with a persisted or presentation ID.
     * This is intentionally separate from [add] for creation paths that only learn their target
     * ID after the instance has been registered.
     */
    fun rebindStableId(widget: BaseWidget, requestedStableId: String): String {
        val currentId = stableId(widget)
        if (currentId == requestedStableId) return currentId
        val descriptor = WidgetDescriptors[widget.type]
        validateStableId(descriptor, requestedStableId)
        require(widgetsByStableId[requestedStableId] == null) {
            "Stable widget ID '$requestedStableId' is already registered"
        }
        widgetsByStableId.remove(currentId)
        stableIds[widget] = requestedStableId
        widgetsByStableId[requestedStableId] = widget
        advanceSequence(descriptor, requestedStableId)
        return requestedStableId
    }

    /** The registered singleton for [type], or null. Throws for multiple-instance types. */
    fun singleton(type: WidgetTypeId): BaseWidget? {
        require(WidgetDescriptors[type].policy == WidgetInstancePolicy.SINGLETON) {
            "$type is not a singleton widget type"
        }
        return widgetsFor(type).singleOrNull()
    }

    /** Immutable snapshot of all live instances for [type]. */
    fun widgets(type: WidgetTypeId): List<BaseWidget> = widgetsFor(type).toList()

    inline fun <reified T : BaseWidget> singletonAs(type: WidgetTypeId): T? = singleton(type) as? T

    inline fun <reified T : BaseWidget> widgetsAs(type: WidgetTypeId): List<T> =
        widgets(type).map { widget ->
            widget as? T ?: error("Registered ${widget::class.simpleName} does not match $type")
        }

    /** Stable descriptor order, then insertion order within a type. */
    fun allWidgets(): List<BaseWidget> = buildList {
        WidgetDescriptors.all.forEach { descriptor -> addAll(widgetsFor(descriptor.type)) }
    }

    fun pinnedWidgets(): List<BaseWidget> = allWidgets().filter(BaseWidget::isPinned)

    fun forEachWidget(action: (BaseWidget) -> Unit) = allWidgets().forEach(action)

    /** Cached until registry membership or z-order changes through this registry. */
    fun getAllSortedByZOrder(): List<BaseWidget> = sortedWidgetsCache ?: allWidgets()
        .sortedBy(BaseWidget::zOrder)
        .also { sortedWidgetsCache = it }

    fun getPinnedWidgetsSortedByZOrder(): List<BaseWidget> =
        getAllSortedByZOrder().filter(BaseWidget::isPinned)

    fun hasPinnedWidgets(): Boolean = allWidgets().any(BaseWidget::isPinned)

    fun getWidgetAt(x: Float, y: Float): BaseWidget? =
        hitTestCache.findFirstEligible { widget -> widget.containsPoint(x, y) }

    /** Marks [widget] as fullscreen. It must be registered; null clears fullscreen state. */
    fun setFullscreen(widget: BaseWidget?) {
        require(widget == null || isRegistered(widget)) { "Fullscreen widget must be registered" }
        fullscreenWidget = widget
    }

    /** Clears fullscreen only when [widget] is currently fullscreen. */
    fun clearFullscreen(widget: BaseWidget? = null) {
        if (widget == null || fullscreenWidget === widget) fullscreenWidget = null
    }

    /**
     * Whether [widget] is the current fullscreen owner.
     *
     * An absent widget is never fullscreen. The null guard is load-bearing: a bare
     * `fullscreenWidget === widget` identity check is `true` when both sides are null, so an
     * optional widget that is simply not on the dashboard would report itself as fullscreen on
     * every windowed dashboard.
     */
    fun isFullscreen(widget: BaseWidget?): Boolean = widget != null && fullscreenWidget === widget

    fun bringToFront(widget: BaseWidget, rootView: ViewGroup? = null, containerView: View? = null) {
        require(isRegistered(widget)) { "Cannot bring an unregistered widget to front" }
        val oldZOrder = widget.zOrder
        widget.zOrder = nextZOrder++
        invalidateOrdering()
        Log.d(TAG, "Brought ${widget::class.simpleName} to front: zOrder $oldZOrder -> ${widget.zOrder}")
        if (widget is BrowserWidget && rootView != null && containerView != null) {
            updateBrowserWebViewZOrder(widget, rootView, containerView)
        }
    }

    /** Restores a persisted z-order without letting subsequent assignments collide. */
    fun restoreZOrder(widget: BaseWidget, zOrder: Int, rootView: ViewGroup? = null, containerView: View? = null) {
        if (!isRegistered(widget)) return
        widget.zOrder = zOrder
        nextZOrder = maxOf(nextZOrder, zOrder + 1)
        invalidateOrdering()
        if (widget is BrowserWidget && rootView != null && containerView != null) {
            updateBrowserWebViewZOrder(widget, rootView, containerView)
        }
    }

    fun clear() {
        widgetsByType.values.forEach { widgets -> widgets.clear() }
        stableIds.clear()
        widgetsByStableId.clear()
        nextInstanceSequence.clear()
        fullscreenWidget = null
        nextZOrder = 0
        invalidateOrdering()
    }

    fun getMinimizedWidgets(): List<BaseWidget> = allWidgets().filter(BaseWidget::isMinimized)

    private fun addAt(widget: BaseWidget, requestedStableId: String?, index: Int): String {
        val descriptor = WidgetDescriptors[widget.type]
        val values = widgetsFor(widget.type)
        require(!isRegistered(widget)) { "Widget ${widget::class.simpleName} is already registered" }
        require(descriptor.policy != WidgetInstancePolicy.SINGLETON || values.isEmpty()) {
            "Only one ${descriptor.label} widget may be registered"
        }
        require(index in 0..values.size) { "Invalid insertion index $index for ${widget.type}" }

        val stableId = requestedStableId ?: allocateStableId(descriptor)
        validateStableId(descriptor, stableId)
        require(widgetsByStableId[stableId] == null) { "Stable widget ID '$stableId' is already registered" }

        values.add(index, widget)
        stableIds[widget] = stableId
        widgetsByStableId[stableId] = widget
        advanceSequence(descriptor, stableId)
        widget.zOrder = nextZOrder++
        invalidateOrdering()
        return stableId
    }

    private fun widgetsFor(type: WidgetTypeId): MutableList<BaseWidget> =
        requireNotNull(widgetsByType[type]) { "No collection registered for $type" }

    private fun allocateStableId(descriptor: WidgetDescriptor): String {
        if (descriptor.policy == WidgetInstancePolicy.SINGLETON) return descriptor.type.persistedId
        var sequence = nextInstanceSequence[descriptor.type] ?: 0
        var candidate: String
        do {
            candidate = "${descriptor.type.persistedId}#$sequence"
            sequence += 1
        } while (widgetsByStableId.containsKey(candidate))
        nextInstanceSequence[descriptor.type] = sequence
        return candidate
    }

    private fun validateStableId(descriptor: WidgetDescriptor, stableId: String) {
        val prefix = descriptor.type.persistedId
        if (descriptor.policy == WidgetInstancePolicy.SINGLETON) {
            require(stableId == prefix) { "Singleton ${descriptor.type} must use stable ID '$prefix'" }
        } else {
            require(stableId.startsWith("$prefix#") && stableId.removePrefix("$prefix#").toIntOrNull() != null) {
                "Multiple ${descriptor.type} widgets must use '$prefix#<number>' stable IDs"
            }
        }
    }

    private fun advanceSequence(descriptor: WidgetDescriptor, stableId: String) {
        if (descriptor.policy == WidgetInstancePolicy.SINGLETON) return
        val sequence = stableId.substringAfter('#').toInt()
        nextInstanceSequence[descriptor.type] = maxOf(nextInstanceSequence[descriptor.type] ?: 0, sequence + 1)
    }

    private fun invalidateOrdering() {
        sortedWidgetsCache = null
        hitTestCache.invalidate()
    }

    private fun updateBrowserWebViewZOrder(widget: BrowserWidget, rootView: ViewGroup, containerView: View) {
        val webView = widget.getWebView() ?: return
        val containerIndex = rootView.indexOfChild(containerView)
        if (containerIndex < 0) return
        val widgetIndex = browserWidgets.indexOf(widget)
        if (widgetIndex < 0) return
        if (webView.parent == rootView) rootView.removeView(webView)
        val insertAt = containerIndex.coerceIn(0, rootView.childCount)
        rootView.addView(webView, insertAt)
        Log.d(TAG, "Updated WebView z-order for browser at index $widgetIndex, inserted at $insertAt")
    }

}
