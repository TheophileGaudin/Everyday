package com.everyday.everyday_glasses.widgets.browser

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.BrowserWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import org.json.JSONObject

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

/**
 * Puts web browsers on the dashboard: from the menu (reopening the last closed one first),
 * fullscreen for an address or the YouTube history, for an assistant browser task, and from the
 * saved dashboard.
 */
class BrowserPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val context: Context get() = services.androidContext
    private val rootLayout: () -> ViewGroup? = { services.rootLayout }
    private val containerView: View get() = services.dashboardView
    private val all: () -> List<BrowserWidget> = { services.widgets(WidgetTypeId.BROWSER).filterIsInstance<BrowserWidget>() }
    override val lifecycle = WidgetLifecycleRegistration.multiple(
        BrowserWidget::class, BrowserWidgetState.Codec,
        capture = ::capture,
        restore = ::restore,
        configure = ::configure,
        beforeRemove = BrowserWidget::releaseKeyFocus,
    )

    /** From the menu: reopens the last closed browser, or opens a new one that goes fullscreen. */
    fun openFromMenu() {
        if (reopenClosed() != null) return
        services.runPersistentMutation(changed = { it != null }) { openWindowed(fullscreen = true) }
    }

    /** A windowed browser that takes the keyboard, for an assistant browser task. */
    fun openForTask(): BrowserWidget? = openWindowed(fullscreen = false)

    /** A new fullscreen browser; [focus] gives it the keyboard. */
    fun openFullscreen(focus: Boolean, url: String? = null): BrowserWidget? =
        services.runPersistentMutation(changed = { it != null }) {
            open(0f, 0f, services.viewportWidth.toFloat(), services.viewportHeight.toFloat(), fullscreen = true, focus = focus, url = url)
        }

    fun openYouTubeHistory() {
        openFullscreen(focus = true, url = YOUTUBE_HISTORY_URL)
    }

    fun onTrimMemory(level: Int) = all().forEach { it.onTrimMemory(level) }
    fun onLowMemory() = all().forEach { it.onLowMemory() }
    fun clearDebugWebViewData(profileSummary: String) = all().forEach { it.clearDebugWebViewData(profileSummary) }

    /** The default windowed size (45% by 50% of the dashboard), where the user asked (top left otherwise), kept on screen. */
    private fun openWindowed(fullscreen: Boolean): BrowserWidget? {
        val width = services.viewportWidth * 0.45f
        val height = services.viewportHeight * 0.5f
        val x = services.creationPosition?.x ?: 0f
        val y = services.creationPosition?.y ?: 0f
        return open(
            x.coerceIn(0f, services.viewportWidth - width),
            y.coerceIn(0f, services.viewportHeight - height),
            width,
            height,
            fullscreen = fullscreen,
            focus = true,
        )
    }

    /** Null before the dashboard is on screen: the page's native view needs the root layout. */
    private fun open(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        fullscreen: Boolean,
        focus: Boolean,
        url: String? = null,
    ): BrowserWidget? {
        val widget = build(x, y, width, height) ?: return null
        services.open(WidgetTypeId.BROWSER) { widget }
        url?.let { widget.getWebView()?.loadUrl(it) }
        if (fullscreen) widget.toggleFullscreen()
        if (focus) widget.setFocused(true)
        return widget
    }

    private fun reopenClosed(): BrowserWidget? = services.runPersistentMutation(changed = { it != null }) {
        (services.openClosedTemplate(WidgetTypeId.BROWSER) as? BrowserWidget)?.also { browser ->
            if (!browser.isMinimized) browser.setFocused(true)
        }
    }

    private fun restore(state: BrowserWidgetState): BrowserWidget? {
        val widget = build(state.x, state.y, state.width, state.height) ?: return null
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight) {
            widget.getWebView()?.loadUrl(state.url)
        }
        return widget
    }

    /** A browser marked not to be kept ([BrowserWidget.shouldPersist]) is not saved. */
    private fun capture(widget: BrowserWidget): BrowserWidgetState? {
        if (!widget.shouldPersist) return null
        val c = services.captureRestore.extractCommonFields(widget)
        return BrowserWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            url = widget.getUrl() ?: "https://www.google.com",
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    private fun configure(widget: BrowserWidget) {
        widget.listener = object : BrowserWidgetListener {
            override fun onKeyboardRequested(show: Boolean) {
                services.host?.requestBrowserKeyboard(show)
            }
        }
        widget.refreshDisplayVisibility()
    }

    private fun build(x: Float, y: Float, width: Float, height: Float): BrowserWidget? =
        rootLayout()?.let { root -> BrowserWidgetFactory(context, root, containerView).create(x, y, width, height) }

    private companion object {
        const val YOUTUBE_HISTORY_URL = "https://www.youtube.com/feed/history"
    }
}

/** What a saved dashboard keeps of a browser: the address of its page. */
data class BrowserWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val url: String,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<BrowserWidgetState>(WidgetTypeId.BROWSER, BrowserWidgetState::class) {
        override fun toJson(state: BrowserWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("url", state.url)
        }

        override fun fromJson(payload: JSONObject): BrowserWidgetState? {
            return try {
                val c = parseCommonFields(payload)
                BrowserWidgetState(
                    x = c.x, y = c.y,
                    width = payload.getDouble("width").toFloat(),
                    height = payload.getDouble("height").toFloat(),
                    url = payload.optString("url", "https://www.google.com"),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}