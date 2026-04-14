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
 * The WidgetRegistry does NOT own the widgets - it only manages their organization.
 * Widgets are still created and destroyed by WidgetContainer.
 */
class WidgetRegistry {

    companion object {
        private const val TAG = "WidgetRegistry"
    }

    // Widget collections
    val textWidgets = mutableListOf<TextBoxWidget>()
    val browserWidgets = mutableListOf<BrowserWidget>()
    val imageWidgets = mutableListOf<ImageWidget>()
    val fileBrowserWidgets = mutableListOf<FileBrowserWidget>()
    
    // Special widgets (managed separately, always present)
    var statusBarWidget: StatusBarWidget? = null
    var locationWidget: LocationWidget? = null
    var calendarWidget: CalendarWidget? = null
    var screenMirrorWidget: ScreenMirrorWidget? = null
    var financeWidget: FinanceWidget? = null
    var newsWidget: NewsWidget? = null
    var speedometerWidget: SpeedometerWidget? = null
    
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

    /**
     * Reorder all browser WebViews to match their list order.
     * Call this after list modifications or when bringing a widget to front.
     */
    fun reorderAllBrowserWebViews(rootView: ViewGroup, containerView: View) {
        val containerIndex = rootView.indexOfChild(containerView)
        if (containerIndex < 0) return
        
        // Remove all browser WebViews first
        for (browser in browserWidgets) {
            browser.getWebView()?.let { webView ->
                if (webView.parent == rootView) {
                    rootView.removeView(webView)
                }
            }
        }
        
        // Re-add them in list order (first in list = lowest z-index)
        for (browser in browserWidgets) {
            browser.getWebView()?.let { webView ->
                val insertAt = containerIndex.coerceIn(0, rootView.childCount)
                rootView.addView(webView, insertAt)
            }
        }
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
        val result = mutableListOf<BaseWidget>()
        statusBarWidget?.let { result.add(it) }
        locationWidget?.let { result.add(it) }
        calendarWidget?.let { result.add(it) }
        financeWidget?.let { result.add(it) }
        newsWidget?.let { result.add(it) }
        speedometerWidget?.let { result.add(it) }
        screenMirrorWidget?.let { result.add(it) }
        result.addAll(browserWidgets)
        result.addAll(textWidgets)
        result.addAll(imageWidgets)
        result.addAll(fileBrowserWidgets)
        return result
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
     * Find the text widget at the given coordinates.
     * Returns null if no text widget contains the point.
     * Searches in reverse order (topmost first).
     */
    fun getTextWidgetAt(x: Float, y: Float): TextBoxWidget? {
        for (widget in textWidgets.reversed()) {
            if (widget.containsPoint(x, y)) {
                return widget
            }
        }
        return null
    }

    /**
     * Find the browser widget at the given coordinates.
     * Returns null if no browser widget contains the point.
     * Searches in reverse order (topmost first).
     */
    fun getBrowserWidgetAt(x: Float, y: Float): BrowserWidget? {
        for (widget in browserWidgets.reversed()) {
            if (widget.containsPoint(x, y)) {
                return widget
            }
        }
        return null
    }

    /**
     * Find any widget at the given coordinates.
     * Returns the topmost widget (highest z-order) at the point.
     */
    fun getWidgetAt(x: Float, y: Float): BaseWidget? {
        // Get all widgets sorted by z-order descending (topmost first)
        val allWidgets = getAllSortedByZOrder().reversed()
        
        for (widget in allWidgets) {
            if (widget.containsPoint(x, y)) {
                return widget
            }
        }
        return null
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
        fileBrowserWidgets.clear()
        nextZOrder = 0
    }

    /**
     * Get the total count of all widgets (excluding special widgets).
     */
    fun getTotalWidgetCount(): Int {
        return textWidgets.size + browserWidgets.size + imageWidgets.size + fileBrowserWidgets.size
    }

    /**
     * Get all minimized widgets for position management.
     */
    fun getMinimizedWidgets(): List<BaseWidget> = allWidgets().filter { it.isMinimized }
}
