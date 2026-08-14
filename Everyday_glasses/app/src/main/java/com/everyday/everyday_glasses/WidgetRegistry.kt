package com.everyday.everyday_glasses

import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * Manages the collection of widgets and their z-ordering.
 * 
 * This class provides a unified interface for:
 * - Storing and accessing all widget types
 * - Z-order management (bringing widgets to front)
 * - Hit-testing (finding widget at coordinates)
 * - Drawing order (sorted by z-order)
 * 
 * The registry owns membership and ordering; [WidgetLifecycleController] owns creation
 * and teardown.
 */
class WidgetRegistry {

    companion object {
        private const val TAG = "WidgetRegistry"
    }

    private val hitTestCache = WidgetHitTestCache<BaseWidget>(::allWidgets) { it.zOrder }

    // Widget collections invalidate the ordering cache on every membership change,
    // including direct bulk changes made while applying a persisted layout.
    val textWidgets: MutableList<TextBoxWidget> = InvalidatingMutableList(::invalidateHitTestOrder)
    val browserWidgets: MutableList<BrowserWidget> = InvalidatingMutableList(::invalidateHitTestOrder)
    val imageWidgets: MutableList<ImageWidget> = InvalidatingMutableList(::invalidateHitTestOrder)
    val videoWidgets: MutableList<VideoWidget> = InvalidatingMutableList(::invalidateHitTestOrder)
    val pdfWidgets: MutableList<PdfWidget> = InvalidatingMutableList(::invalidateHitTestOrder)
    val fileBrowserWidgets: MutableList<FileBrowserWidget> = InvalidatingMutableList(::invalidateHitTestOrder)
    
    // Special widgets (managed separately, always present)
    var statusBarWidget: StatusBarWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var locationWidget: LocationWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var mapWidget: MapWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var calendarWidget: CalendarWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var screenMirrorWidget: ScreenMirrorWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var financeWidget: FinanceWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var newsWidget: NewsWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var notificationsWidget: NotificationsWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var speedometerWidget: SpeedometerWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    var subtitleWidget: SubtitleWidget? = null
        set(value) { if (field !== value) { field = value; invalidateHitTestOrder() } }
    
    // Global z-order counter for unified widget layering
    private var nextZOrder = 0

    // ==================== Z-Order Management ====================

    /**
     * Bring a widget to the front (top of z-order).
     * This assigns the next highest z-order value to the widget,
     * making it draw on top of all other widgets regardless of type.
     * 
     * @param widget The widget to bring to front
     * @param rootView Optional root ViewGroup for updating WebView z-order
     * @param containerView Optional container view for WebView z-order calculation
     */
    fun bringToFront(widget: BaseWidget, rootView: ViewGroup? = null, containerView: View? = null) {
        val oldZOrder = widget.zOrder
        widget.zOrder = nextZOrder++
        invalidateHitTestOrder()
        Log.d(TAG, "Brought ${widget::class.simpleName} to front: zOrder $oldZOrder -> ${widget.zOrder}")
        
        // For browser widgets, also update WebView z-order in the View hierarchy
        if (widget is BrowserWidget && rootView != null && containerView != null) {
            updateBrowserWebViewZOrder(widget, rootView, containerView)
        }
    }

    /**
     * Update the z-order of a browser widget's WebView to match its position in the list.
     * WebViews that are later in the list should have higher z-order.
     */
    private fun updateBrowserWebViewZOrder(widget: BrowserWidget, rootView: ViewGroup, containerView: View) {
        val webView = widget.getWebView() ?: return
        
        // Get the index of the container in the parent
        val containerIndex = rootView.indexOfChild(containerView)
        if (containerIndex < 0) return
        
        // Find the position among browser WebViews
        val widgetIndex = browserWidgets.indexOf(widget)
        if (widgetIndex < 0) return
        
        // Calculate target index: container index + widget position offset
        val targetIndex = containerIndex.coerceAtLeast(0)
        
        // Remove and re-add at the correct position
        if (webView.parent == rootView) {
            rootView.removeView(webView)
        }
        
        // Re-add: browsers later in the list go closer to the container (higher index)
        val insertAt = targetIndex.coerceIn(0, rootView.childCount)
        rootView.addView(webView, insertAt)
        
        Log.d(TAG, "Updated WebView z-order for browser at index $widgetIndex, inserted at $insertAt")
    }

    // ==================== Widget Iteration Utilities ====================

    /**
     * Returns every registered widget in a stable, consistent order.
     * The list is unsorted; call getAllSortedByZOrder() when z-order matters.
     *
     * Singleton order matches the minimized-widget tray order so that
     * allWidgets().filter { it.isMinimized } produces the same sequence as
     * the previous hand-rolled getMinimizedWidgets() implementation.
     */
    fun allWidgets(): List<BaseWidget> {
        return WidgetDescriptors.all.flatMap { widgets(it.type) }
    }

    fun widgets(type: WidgetTypeId): List<BaseWidget> = when (type) {
        WidgetTypeId.STATUS -> listOfNotNull(statusBarWidget)
        WidgetTypeId.LOCATION -> listOfNotNull(locationWidget)
        WidgetTypeId.MAP -> listOfNotNull(mapWidget)
        WidgetTypeId.CALENDAR -> listOfNotNull(calendarWidget)
        WidgetTypeId.FINANCE -> listOfNotNull(financeWidget)
        WidgetTypeId.NEWS -> listOfNotNull(newsWidget)
        WidgetTypeId.NOTIFICATIONS -> listOfNotNull(notificationsWidget)
        WidgetTypeId.SPEEDOMETER -> listOfNotNull(speedometerWidget)
        WidgetTypeId.SUBTITLE -> listOfNotNull(subtitleWidget)
        WidgetTypeId.MIRROR -> listOfNotNull(screenMirrorWidget)
        WidgetTypeId.BROWSER -> browserWidgets
        WidgetTypeId.TEXT -> textWidgets
        WidgetTypeId.IMAGE -> imageWidgets
        WidgetTypeId.VIDEO -> videoWidgets
        WidgetTypeId.PDF -> pdfWidgets
        WidgetTypeId.FILES -> fileBrowserWidgets
    }

    /** Returns all pinned widgets in the same stable order as allWidgets(). */
    fun pinnedWidgets(): List<BaseWidget> = allWidgets().filter { it.isPinned }

    /** Invokes [action] on every registered widget. */
    fun forEachWidget(action: (BaseWidget) -> Unit) = allWidgets().forEach(action)

    // ==================== Widget Queries ====================

    /**
     * Get all widgets sorted by z-order for drawing.
     * Lower z-order drawn first, higher drawn on top.
     */
    fun getAllSortedByZOrder(): List<BaseWidget> = allWidgets().sortedBy { it.zOrder }

    /**
     * Get all pinned widgets sorted by z-order.
     * Used for SLEEP mode drawing.
     */
    fun getPinnedWidgetsSortedByZOrder(): List<BaseWidget> = pinnedWidgets().sortedBy { it.zOrder }

    /**
     * Check if any widgets are pinned.
     * Used by WakeSleepManager to determine if there's visible content in SLEEP mode.
     */
    fun hasPinnedWidgets(): Boolean = allWidgets().any { it.isPinned }

    /**
     * Find any widget at the given coordinates.
     * Returns the topmost widget (highest z-order) at the point.
     */
    fun getWidgetAt(x: Float, y: Float): BaseWidget? {
        return hitTestCache.findFirstEligible { it.containsPoint(x, y) }
    }

    // ==================== Widget Management ====================

    /**
     * Add a text widget to the registry.
     */
    fun addTextWidget(widget: TextBoxWidget) {
        textWidgets.add(widget)
        widget.zOrder = nextZOrder++
    }

    /**
     * Remove a text widget from the registry.
     */
    fun removeTextWidget(widget: TextBoxWidget) {
        textWidgets.remove(widget)
    }

    /**
     * Add a browser widget to the registry.
     */
    fun addBrowserWidget(widget: BrowserWidget) {
        browserWidgets.add(widget)
        widget.zOrder = nextZOrder++
    }

    /**
     * Remove a browser widget from the registry.
     */
    fun removeBrowserWidget(widget: BrowserWidget) {
        browserWidgets.remove(widget)
    }

    /**
     * Add an image widget to the registry.
     */
    fun addImageWidget(widget: ImageWidget) {
        imageWidgets.add(widget)
        widget.zOrder = nextZOrder++
    }

    /**
     * Remove an image widget from the registry.
     */
    fun removeImageWidget(widget: ImageWidget) {
        imageWidgets.remove(widget)
    }

    fun addVideoWidget(widget: VideoWidget) {
        videoWidgets.add(widget)
        widget.zOrder = nextZOrder++
    }

    fun removeVideoWidget(widget: VideoWidget) {
        videoWidgets.remove(widget)
    }

    fun addPdfWidget(widget: PdfWidget) {
        pdfWidgets.add(widget)
        widget.zOrder = nextZOrder++
    }

    fun removePdfWidget(widget: PdfWidget) {
        pdfWidgets.remove(widget)
    }

    /**
     * Add a file browser widget to the registry.
     */
    fun addFileBrowserWidget(widget: FileBrowserWidget) {
        fileBrowserWidgets.add(widget)
        widget.zOrder = nextZOrder++
    }

    /**
     * Remove a file browser widget from the registry.
     */
    fun removeFileBrowserWidget(widget: FileBrowserWidget) {
        fileBrowserWidgets.remove(widget)
    }

    /**
     * Clear all widgets from the registry.
     */
    fun clear() {
        textWidgets.clear()
        browserWidgets.clear()
        imageWidgets.clear()
        videoWidgets.clear()
        pdfWidgets.clear()
        fileBrowserWidgets.clear()
        statusBarWidget = null
        locationWidget = null
        mapWidget = null
        calendarWidget = null
        financeWidget = null
        newsWidget = null
        notificationsWidget = null
        speedometerWidget = null
        subtitleWidget = null
        screenMirrorWidget = null
        nextZOrder = 0
    }

    /**
     * Get all minimized widgets for position management.
     */
    fun getMinimizedWidgets(): List<BaseWidget> = allWidgets().filter { it.isMinimized }

    private fun invalidateHitTestOrder() {
        hitTestCache.invalidate()
    }

    private class InvalidatingMutableList<T>(
        private val onChanged: () -> Unit
    ) : AbstractMutableList<T>() {
        private val values = ArrayList<T>()

        override val size: Int
            get() = values.size

        override fun get(index: Int): T = values[index]

        override fun add(index: Int, element: T) {
            values.add(index, element)
            onChanged()
        }

        override fun removeAt(index: Int): T = values.removeAt(index).also { onChanged() }

        override fun set(index: Int, element: T): T = values.set(index, element).also { onChanged() }

        override fun clear() {
            if (values.isEmpty()) return
            values.clear()
            onChanged()
        }
    }

    /** Restore a previously captured z-order without making future assignments collide. */
    fun restoreZOrder(widget: BaseWidget, zOrder: Int, rootView: ViewGroup? = null, containerView: View? = null) {
        if (!allWidgets().any { it === widget }) return
        widget.zOrder = zOrder
        nextZOrder = maxOf(nextZOrder, zOrder + 1)
        invalidateHitTestOrder()
        if (widget is BrowserWidget && rootView != null && containerView != null) {
            updateBrowserWebViewZOrder(widget, rootView, containerView)
        }
    }
}
