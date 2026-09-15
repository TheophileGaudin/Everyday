package com.everyday.everyday_glasses

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Constructs BrowserWidget instances and places their native WebView below the canvas layer. */
internal class BrowserWidgetFactory(
    private val context: Context,
    private val rootLayout: ViewGroup,
    private val containerView: View,
) {
    fun create(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): BrowserWidget = BrowserWidget(
        context = context,
        rootLayout = rootLayout,
        x = x,
        y = y,
        widgetWidth = width,
        widgetHeight = height,
    ).also(::placeWebViewBehindContainer)

    private fun placeWebViewBehindContainer(widget: BrowserWidget) {
        val containerIndex = rootLayout.indexOfChild(containerView)
        val insertIndex = if (containerIndex >= 0) containerIndex else 0
        widget.getWebView()?.let { webView ->
            if (webView.parent == rootLayout) {
                rootLayout.removeView(webView)
            }
            rootLayout.addView(webView, insertIndex)
        }
    }
}