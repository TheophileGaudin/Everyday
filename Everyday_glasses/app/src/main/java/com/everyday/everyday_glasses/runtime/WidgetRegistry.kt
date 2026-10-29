package com.everyday.everyday_glasses.runtime

import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets._common.WidgetHitTestCache
import java.util.IdentityHashMap

/**
 * The authoritative inventory of live widgets.
 *
 * Membership is grouped by [WidgetTypeId], while callers receive stable descriptor
 * ordering and never mutate the backing collections directly. It names no widget type:
 * callers ask for a type's widgets with [singleton], [widgets], [singletonAs] or [widgetsAs].
 */
class WidgetRegistry {

    companion object {
        private const val TAG = "WidgetRegistry"
    }

    private val widgetsByType: Map<WidgetTypeId, MutableList<BaseWidget>> =
        WidgetDescriptors.all.associate { descriptor -> descriptor.type to ArrayList() }
    private val stableIds = IdentityHashMap<BaseWidget, String>()
    private val widgetsByStableId = mutableMapOf<String, BaseWidget>()
    private val nextInstanceSequence = mutableMapOf<WidgetTypeId, Int>()
    private val hitTestCache = WidgetHitTestCache(::allWidgets) { it.zOrder }

    private var sortedWidgetsCache: List<BaseWidget>? = null
    private var nextZOrder = 0

    /** The sole fullscreen widget, or null when all widgets are windowed. */
    var fullscreenWidget: BaseWidget? = null
        private set

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
        if (rootView != null && containerView != null) widget.onZOrderChanged(rootView, containerView)
    }

    /** Restores a persisted z-order without letting subsequent assignments collide. */
    fun restoreZOrder(widget: BaseWidget, zOrder: Int, rootView: ViewGroup? = null, containerView: View? = null) {
        if (!isRegistered(widget)) return
        widget.zOrder = zOrder
        nextZOrder = maxOf(nextZOrder, zOrder + 1)
        invalidateOrdering()
        if (rootView != null && containerView != null) widget.onZOrderChanged(rootView, containerView)
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

}