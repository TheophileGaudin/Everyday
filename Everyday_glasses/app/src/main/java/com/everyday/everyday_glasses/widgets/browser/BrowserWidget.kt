package com.everyday.everyday_glasses.widgets.browser

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import com.everyday.everyday_glasses.interaction.user_input.TextEditAction
import com.everyday.everyday_glasses.interaction.user_input.TextZone
import com.everyday.everyday_glasses.interaction.user_input.TextZoneKind
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentFullscreenMode
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentPageSnapshot
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentScreenshot
import com.everyday.everyday_glasses.runtime.BrowserWidgetListener
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.ui.components.GlassesKeyboardView
import com.everyday.everyday_glasses.ui.components.KeyboardOverlayController
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets._common.WidgetInteractionState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * A widget that displays a web browser.
 * Inherits from BaseWidget for common window management.
 * Manages a WebView instance that overlays the widget area.
 */
class BrowserWidget(
    private val context: Context,
    private val rootLayout: ViewGroup,
    x: Float,
    y: Float,
    widgetWidth: Float,
    widgetHeight: Float
) : BaseWidget(WidgetTypeId.BROWSER, x, y, widgetWidth, widgetHeight) {

    companion object {
        private const val TAG = "BrowserWidget"
        private const val HOME_URL = "https://www.google.com"
        private const val BLANK_URL = "about:blank"

        // Navigation Bar Constants
        private const val NAV_BAR_HEIGHT = 44f
        private const val NAV_BUTTON_SIZE = 32f
        private const val NAV_BUTTON_SPACING = 4f

        // Tab Bar Constants
        private const val TAB_BAR_HEIGHT = 32f
        private const val TAB_WIDTH = 150f
        private const val TAB_MIN_WIDTH = 80f
        private const val TAB_SPACING = 2f
        private const val TAB_CLOSE_SIZE = 16f
        private const val NEW_TAB_BUTTON_WIDTH = 32f
        private const val MAX_TABS = 10
        private const val URL_EDITOR_MAX_ROWS = 5
        private const val URL_EDITOR_PADDING = 8f
        private const val AUTO_FULLSCREEN_VIDEO_WINDOW_MS = 45_000L
        private const val AUTO_FULLSCREEN_GESTURE_MAX_ATTEMPTS = 10
        private const val AUTO_FULLSCREEN_GESTURE_RETRY_MS = 900L
        private const val CONTENT_INTERACTION_QUERY_INTERVAL_MS = 80L
        private const val CONTENT_INTERACTION_CACHE_RADIUS_PX = 36f
    }

    /**
     * Represents a single browser tab.
     */
    data class Tab(
        val id: Int,
        var title: String = "New Tab",
        var url: String = HOME_URL,
        var savedState: Bundle? = null,
        var isLoading: Boolean = false,
        var loadProgress: Int = 100
    )

    // Extended state
    enum class State {
        IDLE, HOVER_CONTENT, HOVER_BORDER, MOVING, RESIZING, HOVER_NAV, HOVER_TAB
    }

    var state = State.IDLE
    var shouldPersist: Boolean = true

    // Hit areas (extends BaseHitArea with navigation-specific areas)
    enum class HitArea {
        NONE, CONTENT, BORDER, FULLSCREEN_BUTTON, PIN_BUTTON, MINIMIZE_BUTTON, RESIZE_HANDLE,
        NAV_BACK, NAV_FORWARD, NAV_REFRESH, NAV_HOME, NAV_MODE, NAV_URL,
        TAB, TAB_CLOSE, NEW_TAB,
        KEYBOARD_BUTTON, KEYBOARD_KEY
    }

    override val minimizeLabel: String = "W"
    override val minWidth = MIN_WIDGET_SIZE
    override val minHeight = MIN_WIDGET_SIZE

    // Tab management
    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex = 0
    private var nextTabId = 0

    // Normally only the active tab owns a live WebView. Popup auth flows may park
    // their opener briefly so window.opener/postMessage based sign-ins can finish.
    private var webView: MyWebView? = null
    private var lastAttachedWebViewIndex: Int? = null
    private val parkedWebViewsByTabId = mutableMapOf<Int, MyWebView>()

    private var lastDownTime: Long = 0
    private var isDispatchingSyntheticTouch = false
    private var isHostPaused = false
    private var isDisplayVisible = true

    /**
     * Inner class to access protected scroll methods and prevent system keyboard.
     *
     * Keyboard prevention strategy (optimized for performance):
     * 1. onCheckIsTextEditor() = false -> Primary: tells Android this isn't a text editor
     * 2. onCreateInputConnection() = null + single hide -> Prevents IME connection
     * 3. Debounced hide on focus changes -> Catches edge cases on some OEMs
     *
     * This replaces the previous 100ms polling loop which was causing lag.
     */
    inner class MyWebView(context: Context) : WebView(context) {
        private val handler = Handler(Looper.getMainLooper())
        private var pendingKeyboardHide: Runnable? = null

        var currentPageScale: Float = 1f
            private set

        fun recordPageScale(scale: Float) {
            currentPageScale = scale.coerceAtLeast(0.1f)
        }

        public override fun computeVerticalScrollRange(): Int = super.computeVerticalScrollRange()
        public override fun computeVerticalScrollOffset(): Int = super.computeVerticalScrollOffset()
        public override fun computeVerticalScrollExtent(): Int = super.computeVerticalScrollExtent()

        // === PRIMARY keyboard prevention: these two overrides do most of the work ===
        override fun onCheckIsTextEditor(): Boolean = false

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            // Additional safety: hide keyboard when IME tries to connect
            // This catches aggressive OEM keyboards that ignore onCheckIsTextEditor
            scheduleKeyboardHide()
            return null
        }

        // Override to intercept focus and prevent keyboard
        override fun requestFocus(direction: Int, previouslyFocusedRect: Rect?): Boolean {
            val result = super.requestFocus(direction, previouslyFocusedRect)
            scheduleKeyboardHide()
            return result
        }

        // Prevent default keyboard behavior on window focus
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) {
                scheduleKeyboardHide()
            }
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!isDispatchingSyntheticTouch) {return true}
            if (event.actionMasked == MotionEvent.ACTION_UP) {scheduleKeyboardHide()}
            return super.dispatchTouchEvent(event)
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (!isDispatchingSyntheticTouch) {
                return true
            }
            return super.onGenericMotionEvent(event)
        }

        override fun onDetachedFromWindow() {
            // Clean up any pending callbacks
            pendingKeyboardHide?.let { handler.removeCallbacks(it) }
            pendingKeyboardHide = null
            super.onDetachedFromWindow()
        }

        /**
         * Debounced keyboard hide - ensures only one hide operation is pending at a time.
         * This replaces the previous approach of scheduling 4-5 delayed calls per event.
         */
        fun scheduleKeyboardHide() {
            pendingKeyboardHide?.let { handler.removeCallbacks(it) }
            pendingKeyboardHide = Runnable {
                hideSystemIme(this)
                pendingKeyboardHide = null
            }
            // Small delay to let the system settle before we hide
            handler.postDelayed(pendingKeyboardHide!!, 50)
        }
    }

    /**
     * Expose the underlying WebView so WidgetContainer can:
     *  - control visibility for WAKE/SLEEP/OFF
     *  - re-insert it in the root layout to manage Z-order
     *  - forward touch events (proxy) into the WebView
     */
    fun getWebView(): WebView? = webView

    fun getUrl(): String? = webView?.url ?: currentTab()?.url

    fun getTitle(): String? = webView?.title ?: currentTab()?.title

    /** Active tab identity used when binding a Page Agent run. */
    fun pageAgentActiveTabId(): Int? = currentTab()?.id

    /** True only while this exact tab and WebView are still owned by this widget. */
    fun pageAgentIsBoundTo(tabId: Int, webView: WebView): Boolean =
        currentTab()?.id == tabId && this.webView === webView && !isMinimized

    fun pageAgentPageRevision(): Long = pageAgentRevision

    /** Capture only the bound page viewport, excluding browser chrome. */
    fun capturePageAgentSnapshot(): PageAgentPageSnapshot? {
        val source = customView ?: webView ?: return null
        val captureWidth = source.width
        val captureHeight = source.height
        if (captureWidth <= 0 || captureHeight <= 0) return null

        val bitmap = runCatching {
            createBitmap(captureWidth, captureHeight)
        }.getOrNull() ?: return null
        val output = ByteArrayOutputStream()
        return try {
            source.draw(Canvas(bitmap))
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
            PageAgentPageSnapshot(
                screenshot = PageAgentScreenshot(
                    bytes = output.toByteArray(),
                    width = captureWidth,
                    height = captureHeight
                ),
                url = getUrl().orEmpty(),
                title = getTitle().orEmpty(),
                viewportWidth = captureWidth,
                viewportHeight = captureHeight
            )
        } finally {
            bitmap.recycle()
            output.close()
        }
    }

    /** Deliver a model tap expressed in WebView-local viewport coordinates. */
    fun dispatchPageAgentTap(viewportX: Float, viewportY: Float): Boolean {
        if (!viewportX.isFinite() || !viewportY.isFinite()) return false
        val activeVideoView = customView
        if (isVideoFullscreen && activeVideoView != null) {
            if (viewportX < 0f || viewportY < 0f ||
                viewportX >= activeVideoView.width || viewportY >= activeVideoView.height
            ) return false
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                viewportX,
                viewportY,
                0
            )
            val up = MotionEvent.obtain(
                downTime,
                downTime + 40L,
                MotionEvent.ACTION_UP,
                viewportX,
                viewportY,
                0
            )
            try {
                activeVideoView.dispatchTouchEvent(down)
                activeVideoView.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
            return true
        }

        val activeWebView = webView ?: return false
        if (viewportX < 0f || viewportY < 0f ||
            viewportX >= activeWebView.width || viewportY >= activeWebView.height
        ) return false

        // dispatchTouchEvent expects widget coordinates. The Page Agent adapter has
        // already scaled screenshot coordinates to this WebView viewport; add the
        // navigation/tab chrome offset exactly once here.
        val widgetX = contentBounds.left + viewportX
        val widgetY = contentBounds.top + NAV_BAR_HEIGHT + TAB_BAR_HEIGHT + viewportY
        dispatchTouchEvent(widgetX, widgetY, MotionEvent.ACTION_DOWN)
        dispatchTouchEvent(widgetX, widgetY, MotionEvent.ACTION_UP)
        return true
    }

    /** Replace the active page element without using the app URL editor. */
    fun replacePageAgentFocusedText(text: String): Boolean {
        val activeWebView = webView ?: return false
        setFocused(true)
        val quoted = JSONObject.quote(text)
        activeWebView.evaluateJavascript(
            """
            (function() {
                var el = document.activeElement;
                if (!el) return false;
                var tag = (el.tagName || '').toUpperCase();
                if (el.isContentEditable || tag === 'INPUT' || tag === 'TEXTAREA') {
                    try { el.focus(); } catch (e) {}
                    if (el.isContentEditable) {
                        try {
                            var range = document.createRange();
                            range.selectNodeContents(el);
                            var selection = window.getSelection();
                            selection.removeAllRanges();
                            selection.addRange(range);
                        } catch (e) {}
                        if (document.execCommand) {
                            document.execCommand('insertText', false, $quoted);
                        }
                    } else {
                        try { el.setSelectionRange(0, (el.value || '').length); } catch (e) {}
                        if (typeof el.setRangeText === 'function') {
                            el.setRangeText($quoted, 0, (el.value || '').length, 'end');
                        } else {
                            el.value = $quoted;
                        }
                        el.dispatchEvent(new Event('input', {bubbles:true}));
                        el.dispatchEvent(new Event('change', {bubbles:true}));
                    }
                    return true;
                }
                return false;
            })()
            """.trimIndent(),
            null
        )
        // evaluateJavascript is asynchronous; dispatching it successfully is the
        // actionable result. The next screenshot reports whether the page accepted it.
        return true
    }

    /** Submit the focused page field after a Computer Use `type(..., press_enter=true)`. */
    fun submitPageAgentFocusedElement(): Boolean {
        val activeWebView = webView ?: return false
        activeWebView.evaluateJavascript(
            """
            (function() {
                var el = document.activeElement;
                if (!el) return false;
                try {
                    ['keydown', 'keypress', 'keyup'].forEach(function(type) {
                        el.dispatchEvent(new KeyboardEvent(type, {
                            key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true
                        }));
                    });
                    if (el.form) {
                        if (typeof el.form.requestSubmit === 'function') el.form.requestSubmit();
                        else el.form.submit();
                    }
                    return true;
                } catch (e) { return false; }
            })()
            """.trimIndent(),
            null
        )
        return true
    }

    fun scrollPageAgent(deltaX: Float, deltaY: Float): Boolean {
        val activeWebView = webView ?: return false
        if (!deltaX.isFinite() || !deltaY.isFinite()) return false
        activeWebView.scrollBy(deltaX.toInt(), deltaY.toInt())
        return true
    }

    fun goBackForPageAgent(): Boolean {
        val activeWebView = webView ?: return false
        if (!activeWebView.canGoBack()) return false
        activeWebView.goBack()
        return true
    }

    fun navigateForPageAgent(address: String): Boolean {
        if (address.trim().isBlank()) return false
        val activeWebView = ensureActiveWebView() ?: return false
        navigateToAddress(address, autoFullscreenVideo = false)
        return activeWebView.isAttachedToWindow || activeWebView.parent != null
    }

    fun requestPageAgentFullscreen(mode: PageAgentFullscreenMode): Boolean {
        return when (mode) {
            PageAgentFullscreenMode.PAGE -> {
                if (isVideoFullscreen) true
                else {
                    if (!isFullscreen) toggleFullscreen()
                    isFullscreen
                }
            }
            PageAgentFullscreenMode.VIDEO -> requestPageAgentVideoFullscreen()
        }
    }

    private fun requestPageAgentVideoFullscreen(): Boolean {
        if (isVideoFullscreen) return true
        val activeWebView = webView ?: return false
        autoFullscreenGestureAttempts = 0
        cancelAutoFullscreenGestureAttempts()
        autoFullscreenVideoUntilMs = SystemClock.uptimeMillis() + AUTO_FULLSCREEN_VIDEO_WINDOW_MS
        injectMediaPlaybackDefaults(activeWebView)
        dispatchAutoFullscreenGestureIfCandidate()
        scheduleAutoFullscreenGestureAttempt(0L)
        return true
    }

    fun isEditingAddress(): Boolean = isEditingUrl

    fun isAddressHit(px: Float, py: Float): Boolean {
        return urlBarBounds.contains(px, py) || (isEditingUrl && urlEditorBounds.contains(px, py))
    }

    /** Opens the address editor and hands it to the text editing controller. */
    fun beginAddressEditing(selectAll: Boolean = true) {
        setFocused(true)
        if (!isEditingUrl) {
            isEditingUrl = true
            editingUrlText.clear()
            editingUrlText.append(getUrl() ?: "")
            updateUrlEditorBounds()
        }
        textZoneHost?.focus(addressZone, selectAll)
        listener?.onKeyboardRequested(true)
    }

    private val addressTextBounds = RectF()

    /** The address bar field; typing, selection and clipboard are handled by the text editing controller. */
    val addressZone: TextZone = object : TextZone {
        override val kind = TextZoneKind.FIELD
        override val bounds: RectF
            get() = addressTextBounds.apply {
                set(
                    urlEditorBounds.left + URL_EDITOR_PADDING,
                    urlEditorBounds.top + URL_EDITOR_PADDING,
                    urlEditorBounds.right - URL_EDITOR_PADDING,
                    urlEditorBounds.bottom - URL_EDITOR_PADDING
                )
            }
        override val fieldTextSize: Float get() = urlTextPaint.textSize
        override val fieldWraps = true
        override fun content(): CharSequence = editingUrlText
        override fun persistentState(): Any? = getUrl()
        override fun onTextEditAction(action: TextEditAction) {
            when (action) {
                is TextEditAction.Changed -> {
                    editingUrlText.clear()
                    editingUrlText.append(action.content)
                    updateUrlEditorBounds()
                }
                is TextEditAction.Submitted -> navigateToAddress(action.text)
                TextEditAction.Released -> exitAddressEditing()
                TextEditAction.LayoutChanged -> Unit
            }
            listener?.onStateChanged(state)
        }
    }

    private fun exitAddressEditing() {
        isEditingUrl = false
        editingUrlText.clear()
        urlEditorBounds.setEmpty()
    }

    // Bounds (navigation bar - close button inherited from BaseWidget)
    private val navBarBounds = RectF()
    private val backButtonBounds = RectF()
    private val forwardButtonBounds = RectF()
    private val refreshButtonBounds = RectF()
    private val homeButtonBounds = RectF()
    private val modeButtonBounds = RectF()
    private val urlBarBounds = RectF()
    private val urlEditorBounds = RectF()

    // Tab bar bounds
    private val tabBarBounds = RectF()
    private val tabBounds = mutableListOf<RectF>()
    private val tabCloseBounds = mutableListOf<RectF>()
    private val newTabButtonBounds = RectF()

    // Keyboard overlay shared logic (layout, hit-test, drawing, visibility)
    private val keyboardOverlay = KeyboardOverlayController().apply {
        onKeyPressed = { key -> if (textZoneHost?.isFocused(addressZone) == true) textZoneHost?.onKey(key) else onKeyPress(key) }
    }
    private val keyboardButtonBounds: RectF
        get() = keyboardOverlay.buttonBounds
    val isKeyboardVisible: Boolean
        get() = keyboardOverlay.isVisible

    // Paints (close button paints inherited from BaseWidget)
    private val navBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#E6202025".toColorInt() // Dark semi-transparent background
    }

    private val navButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
    }

    private val navButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#40FFFFFF".toColorInt()
    }

    private val navButtonDisabledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
    }

    private val navIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val navIconDisabledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#55FFFFFF".toColorInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val urlBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = "#33000000".toColorInt()
    }

    private val urlEditorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F0202028".toColorInt()
    }

    private val urlEditorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CCFFFFFF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val urlTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.LEFT
    }

    // Tab bar paints
    private val tabBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#CC181820".toColorInt()
    }

    private val tabActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#44FFFFFF".toColorInt()
    }

    private val tabInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#22FFFFFF".toColorInt()
    }

    private val tabHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#33FFFFFF".toColorInt()
    }

    private val tabTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f
        textAlign = Paint.Align.LEFT
    }

    private val tabCloseIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#AAFFFFFF".toColorInt()
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val newTabButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#22FFFFFF".toColorInt()
    }

    private val newTabIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val loadingScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#66000000".toColorInt()
    }

    private val loadingSpinnerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#44FFFFFF".toColorInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val loadingSpinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val loadingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }

    // Hover states for nav bar
    private var isHoveringBack = false
    private var isHoveringForward = false
    private var isHoveringRefresh = false
    private var isHoveringHome = false
    private var isHoveringMode = false
    private var isHoveringUrlBar = false

    // Hover states for tab bar
    private var hoveringTabIndex = -1
    private var hoveringTabClose = -1
    private var isHoveringNewTab = false
    private var cursorSafeX = Float.NEGATIVE_INFINITY
    private var cursorSafeY = Float.NEGATIVE_INFINITY
    private var isContentInteractionTarget = false
    private var contentInteractionTargetX = Float.NEGATIVE_INFINITY
    private var contentInteractionTargetY = Float.NEGATIVE_INFINITY
    private var lastContentInteractionQueryMs = 0L
    private var contentInteractionQueryGeneration = 0

    // URL editing state
    private var isEditingUrl = false
    private var editingUrlText = StringBuilder()

    // User agent toggle state
    private var isDesktopMode = false
    private var defaultUserAgent: String? = null

    // A fairly standard desktop UA (works well for many sites)
    private val desktopUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Video fullscreen state
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isVideoFullscreen = false
    private var pageAgentRevision = 0L
    private val loadingAnimationHandler = Handler(Looper.getMainLooper())
    private var loadingAnimationRunnable: Runnable? = null
    private var loadingSpinnerStartMs: Long = 0L
    private var autoFullscreenVideoUntilMs: Long = 0L
    private var autoFullscreenGestureAttempts: Int = 0
    private var autoFullscreenGestureRunnable: Runnable? = null

    // Callbacks (onCloseRequested is inherited from BaseWidget)
    var listener: BrowserWidgetListener? = null
    private var isFocused = false

    init {
        // Create initial tab with WebView
        createNewTab(HOME_URL)
        updateBaseBounds()
    }

    private fun currentTab(): Tab? = tabs.getOrNull(activeTabIndex)

    private fun shouldKeepLiveWebView(): Boolean {
        return !isHostPaused && isDisplayVisible && !isMinimized && !isVideoFullscreen
    }

    private fun captureActiveTabState() {
        val activeWebView = webView ?: return
        val tab = currentTab() ?: return
        val savedState = Bundle()
        activeWebView.saveState(savedState)
        tab.savedState = savedState.takeUnless { it.isEmpty }
        tab.url = activeWebView.url ?: tab.url
        tab.title = activeWebView.title?.takeIf { it.isNotBlank() } ?: tab.title
    }

    private fun destroyWebViewInstance(instance: MyWebView) {
        try {
            instance.stopLoading()
        } catch (_: Throwable) {
        }
        instance.onPause()
        instance.webChromeClient = null
        instance.onFocusChangeListener = null
        instance.removeAllViews()
        instance.destroy()
    }

    private fun destroyParkedWebView(tabId: Int) {
        val parked = parkedWebViewsByTabId.remove(tabId) ?: return
        (parked.parent as? ViewGroup)?.removeView(parked)
        destroyWebViewInstance(parked)
    }

    private fun destroyAllParkedWebViews() {
        val parkedViews = parkedWebViewsByTabId.values.toList()
        parkedWebViewsByTabId.clear()
        parkedViews.forEach { parked ->
            (parked.parent as? ViewGroup)?.removeView(parked)
            destroyWebViewInstance(parked)
        }
    }

    private fun configureCookiePolicy(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun injectBrowserInputDefaults(view: WebView?) {
        view ?: return
        val script = """
            (function() {
              if (!window.__everydayBrowserInputDefaultsInstalled) {
                window.__everydayBrowserInputDefaultsInstalled = true;

                var style = document.createElement('style');
                style.id = 'everyday-browser-input-defaults';
                style.textContent =
                  'html, body, * {' +
                  '  touch-action: none !important;' +
                  '  overscroll-behavior: none !important;' +
                  '}';
                (document.head || document.documentElement || document.body).appendChild(style);
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun injectUnmutedMediaDefaults(view: WebView?) {
        view ?: return
        val script = """
            (function() {
              function unmute(media) {
                try {
                  media.defaultMuted = false;
                  media.muted = false;
                  media.volume = 1.0;
                } catch (e) {}
              }
              function apply() {
                document.querySelectorAll('video,audio').forEach(unmute);
              }
              if (!window.__everydayUnmutedMediaDefaults) {
                window.__everydayUnmutedMediaDefaults = true;
                document.addEventListener('play', function(event) {
                  var target = event.target;
                  if (target && (target.tagName === 'VIDEO' || target.tagName === 'AUDIO')) {
                    unmute(target);
                  }
                }, true);
                var root = document.documentElement || document.body;
                if (root) {
                  new MutationObserver(apply).observe(root, { childList: true, subtree: true });
                }
                var attempts = 0;
                var nudge = setInterval(function() {
                  apply();
                  attempts += 1;
                  if (attempts >= 40) {
                    clearInterval(nudge);
                  }
                }, 250);
              }
              apply();
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun injectAutoFullscreenForPlayingVideo(view: WebView?) {
        view ?: return
        val script = """
            (function() {
              function candidateVideo() {
                var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                var best = null;
                var bestArea = 0;
                videos.forEach(function(video) {
                  try {
                    var rect = video.getBoundingClientRect();
                    var area = Math.max(0, rect.width) * Math.max(0, rect.height);
                    var hasFrames = video.videoWidth > 0 && video.videoHeight > 0;
                    var isPlaying = !video.paused && !video.ended && video.readyState >= 2;
                    if (hasFrames && isPlaying && area > bestArea) {
                      best = video;
                      bestArea = area;
                    }
                  } catch (e) {}
                });
                return bestArea >= 12000 ? best : null;
              }

              function requestFullscreenFor(video) {
                if (!video ||
                    window.__everydayAutoFullscreenDone ||
                    window.__everydayAutoFullscreenInFlight ||
                    document.fullscreenElement ||
                    document.webkitFullscreenElement) {
                  return;
                }

                var request =
                  video.requestFullscreen ||
                  video.webkitRequestFullscreen ||
                  video.webkitEnterFullscreen ||
                  video.msRequestFullscreen;
                if (!request) return;

                window.__everydayAutoFullscreenInFlight = true;
                try {
                  var result = request.call(video);
                  if (result && result.then) {
                    result.then(function() {
                      window.__everydayAutoFullscreenDone = true;
                      window.__everydayAutoFullscreenInFlight = false;
                    }).catch(function() {
                      window.__everydayAutoFullscreenInFlight = false;
                    });
                  } else {
                    window.__everydayAutoFullscreenDone = true;
                    window.__everydayAutoFullscreenInFlight = false;
                  }
                } catch (e) {
                  window.__everydayAutoFullscreenInFlight = false;
                }
              }

              function tryFullscreen() {
                requestFullscreenFor(candidateVideo());
              }

              function tryFullscreenFromGesture(event) {
                var shouldConsume = window.__everydayConsumeNextAutoFullscreenGesture === true;
                tryFullscreen();
                if (shouldConsume && event) {
                  try {
                    event.preventDefault();
                    event.stopPropagation();
                    if (event.stopImmediatePropagation) event.stopImmediatePropagation();
                  } catch (e) {}
                }
                window.__everydayConsumeNextAutoFullscreenGesture = false;
              }

              window.__everydayAutoFullscreenHasCandidate = function() {
                return !!candidateVideo();
              };
              window.__everydayPrimeAutoFullscreenGesture = function() {
                var hasCandidate = !!candidateVideo();
                if (hasCandidate) {
                  window.__everydayConsumeNextAutoFullscreenGesture = true;
                }
                return hasCandidate;
              };

              if (!window.__everydayAutoFullscreenInstalled) {
                window.__everydayAutoFullscreenInstalled = true;
                document.addEventListener('play', tryFullscreen, true);
                document.addEventListener('playing', tryFullscreen, true);
                document.addEventListener('touchend', tryFullscreenFromGesture, true);
                document.addEventListener('mouseup', tryFullscreenFromGesture, true);
                document.addEventListener('click', tryFullscreenFromGesture, true);
                var root = document.documentElement || document.body;
                if (root) {
                  new MutationObserver(tryFullscreen).observe(root, { childList: true, subtree: true });
                }
                var attempts = 0;
                var retry = setInterval(function() {
                  tryFullscreen();
                  attempts += 1;
                  if (window.__everydayAutoFullscreenDone || attempts >= 60) {
                    clearInterval(retry);
                  }
                }, 500);
              }
              tryFullscreen();
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun shouldAutoFullscreenVideo(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (autoFullscreenVideoUntilMs <= now) {
            autoFullscreenVideoUntilMs = 0L
            cancelAutoFullscreenGestureAttempts()
            return false
        }
        return true
    }

    private fun injectMediaPlaybackDefaults(view: WebView?) {
        injectBrowserInputDefaults(view)
        injectUnmutedMediaDefaults(view)
        if (shouldAutoFullscreenVideo()) {
            injectAutoFullscreenForPlayingVideo(view)
            scheduleAutoFullscreenGestureAttempt()
        }
    }

    private fun cancelAutoFullscreenGestureAttempts() {
        autoFullscreenGestureRunnable?.let { loadingAnimationHandler.removeCallbacks(it) }
        autoFullscreenGestureRunnable = null
    }

    private fun resetAutoFullscreenVideoAutomation() {
        autoFullscreenVideoUntilMs = 0L
        autoFullscreenGestureAttempts = 0
        cancelAutoFullscreenGestureAttempts()
    }

    private fun scheduleAutoFullscreenGestureAttempt(delayMs: Long = AUTO_FULLSCREEN_GESTURE_RETRY_MS) {
        if (!shouldAutoFullscreenVideo() ||
            isVideoFullscreen ||
            autoFullscreenGestureAttempts >= AUTO_FULLSCREEN_GESTURE_MAX_ATTEMPTS ||
            autoFullscreenGestureRunnable != null
        ) {
            return
        }

        autoFullscreenGestureRunnable = Runnable {
            autoFullscreenGestureRunnable = null
            dispatchAutoFullscreenGestureIfCandidate()
        }
        loadingAnimationHandler.postDelayed(autoFullscreenGestureRunnable!!, delayMs)
    }

    private fun dispatchAutoFullscreenGestureIfCandidate() {
        val activeWebView = webView ?: return
        if (!shouldAutoFullscreenVideo() ||
            isVideoFullscreen ||
            autoFullscreenGestureAttempts >= AUTO_FULLSCREEN_GESTURE_MAX_ATTEMPTS
        ) {
            return
        }

        activeWebView.evaluateJavascript(
            "(window.__everydayPrimeAutoFullscreenGesture && window.__everydayPrimeAutoFullscreenGesture()) === true"
        ) { result ->
            if (!shouldAutoFullscreenVideo() || isVideoFullscreen) return@evaluateJavascript

            if (result == "true") {
                autoFullscreenGestureAttempts++
                val webViewTop = contentBounds.top + NAV_BAR_HEIGHT + TAB_BAR_HEIGHT
                val tapX = contentBounds.left + (activeWebView.width.coerceAtLeast(1) * 0.5f)
                val tapY = webViewTop + (activeWebView.height.coerceAtLeast(1) * 0.5f)
                val downTime = SystemClock.uptimeMillis()
                dispatchTouchEventInternal(
                    activeWebView,
                    tapX,
                    tapY,
                    MotionEvent.ACTION_DOWN,
                    downTime,
                    downTime
                )
                dispatchTouchEventInternal(
                    activeWebView,
                    tapX,
                    tapY,
                    MotionEvent.ACTION_UP,
                    downTime,
                    downTime + 40L
                )
            }

            if (shouldAutoFullscreenVideo() &&
                !isVideoFullscreen &&
                autoFullscreenGestureAttempts < AUTO_FULLSCREEN_GESTURE_MAX_ATTEMPTS
            ) {
                scheduleAutoFullscreenGestureAttempt()
            }
        }
    }

    private fun setTabLoading(
        tabId: Int,
        isLoading: Boolean,
        progress: Int,
        notifyState: Boolean = true
    ) {
        val tab = tabs.find { it.id == tabId } ?: return
        val coercedProgress = progress.coerceIn(0, 100)
        val nextIsLoading = isLoading && coercedProgress < 100
        val changed = tab.isLoading != nextIsLoading || tab.loadProgress != coercedProgress

        tab.isLoading = nextIsLoading
        tab.loadProgress = if (nextIsLoading) coercedProgress else 100

        if (changed && tab.id == currentTab()?.id) {
            syncLoadingAnimation()
            if (notifyState) notifyStateChanged()
        }
    }

    private fun shouldDrawLoadingIndicator(): Boolean {
        val tab = currentTab() ?: return false
        return tab.isLoading &&
            !isMinimized &&
            !isVideoFullscreen &&
            isDisplayVisible &&
            !isHostPaused
    }

    private fun syncLoadingAnimation() {
        if (shouldDrawLoadingIndicator()) {
            startLoadingAnimation()
        } else {
            stopLoadingAnimation()
        }
    }

    private fun startLoadingAnimation() {
        if (loadingAnimationRunnable != null) return
        loadingSpinnerStartMs = SystemClock.uptimeMillis()
        loadingAnimationRunnable = object : Runnable {
            override fun run() {
                if (!shouldDrawLoadingIndicator()) {
                    loadingAnimationRunnable = null
                    loadingSpinnerStartMs = 0L
                    return
                }
                notifyStateChanged()
                loadingAnimationHandler.postDelayed(this, 50L)
            }
        }
        loadingAnimationHandler.post(loadingAnimationRunnable!!)
    }

    private fun stopLoadingAnimation() {
        loadingAnimationRunnable?.let { loadingAnimationHandler.removeCallbacks(it) }
        loadingAnimationRunnable = null
        loadingSpinnerStartMs = 0L
    }

    private fun releaseActiveWebView(persistState: Boolean): Int? {
        val activeWebView = webView ?: return null
        if (persistState) {
            captureActiveTabState()
        }
        val viewIndex = rootLayout.indexOfChild(activeWebView).takeIf { it >= 0 }
        if (viewIndex != null) {
            rootLayout.removeView(activeWebView)
            lastAttachedWebViewIndex = viewIndex
        }
        destroyWebViewInstance(activeWebView)
        if (webView === activeWebView) {
            webView = null
        }
        return viewIndex
    }

    private fun ensureActiveWebView(forceRecreate: Boolean = false, preferredIndex: Int? = null): MyWebView? {
        val tab = currentTab() ?: return null
        if (!shouldKeepLiveWebView()) {
            return null
        }
        if (forceRecreate) {
            releaseActiveWebView(persistState = false)
            destroyParkedWebView(tab.id)
        }
        webView?.let { existing ->
            existing.visibility = View.VISIBLE
            return existing
        }

        parkedWebViewsByTabId.remove(tab.id)?.let { parked ->
            val targetIndex = preferredIndex ?: lastAttachedWebViewIndex
            if (parked.parent != rootLayout) {
                (parked.parent as? ViewGroup)?.removeView(parked)
                rootLayout.addView(parked, (targetIndex ?: 0).coerceIn(0, rootLayout.childCount))
            }
            rootLayout.indexOfChild(parked).takeIf { it >= 0 }?.let { lastAttachedWebViewIndex = it }
            webView = parked
            parked.visibility = View.VISIBLE
            return parked
        }

        val targetIndex = preferredIndex ?: lastAttachedWebViewIndex
        val newWebView = createWebViewForTab(tab.id, targetIndex)
        webView = newWebView

        val restored = tab.savedState?.let { savedState ->
            newWebView.restoreState(Bundle(savedState)) != null
        } == true

        if (!restored) {
            newWebView.loadUrl(tab.url)
        }

        newWebView.visibility = View.VISIBLE
        return newWebView
    }

    private fun syncLiveWebViewState() {
        if (shouldKeepLiveWebView()) {
            ensureActiveWebView()
        } else if (!isVideoFullscreen) {
            releaseActiveWebView(persistState = true)
        }
    }

    private fun syncTabLifecycle() {
        webView?.let { activeWebView ->
            if (isHostPaused) {
                activeWebView.onPause()
                activeWebView.pauseTimers()
            } else {
                activeWebView.onResume()
                activeWebView.resumeTimers()
            }
        }
    }

    private fun recreateTabWebView(tabId: Int, recoverUrl: String?) {
        val tabIndex = tabs.indexOfFirst { it.id == tabId }
        if (tabIndex == -1) return

        val tab = tabs[tabIndex]
        tab.savedState = null
        tab.url = recoverUrl ?: tab.url

        if (tabIndex != activeTabIndex) return

        val oldIndex = releaseActiveWebView(persistState = false)
        ensureActiveWebView(forceRecreate = true, preferredIndex = oldIndex)
        syncTabLifecycle()
        updateWebViewLayout()
    }

    private fun hideSystemIme(target: View?) {
        if (target == null) return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return

        // Hide with multiple approaches for maximum effectiveness
        imm.hideSoftInputFromWindow(target.windowToken, 0)
        imm.hideSoftInputFromWindow(target.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)

        // Also try to deactivate IME for this view
        try {
            imm.restartInput(target)
        } catch (_: Exception) {
            // Ignore if this fails
        }
    }

    /**
     * Create a new tab with a WebView and optionally load a URL.
     * @return The index of the newly created tab
     */
    private fun createNewTab(url: String = HOME_URL): Int {
        if (tabs.size >= MAX_TABS) {
            Log.w(TAG, "Maximum tabs reached ($MAX_TABS)")
            return activeTabIndex
        }

        Log.d(TAG, "Creating new tab with URL: $url")
        val tabId = nextTabId++
        val tab = Tab(
            id = tabId,
            title = "New Tab",
            url = url
        )
        tabs.add(tab)

        // Switch to the new tab
        val newIndex = tabs.size - 1
        switchToTab(newIndex)

        updateTabBounds()
        return newIndex
    }

    private fun createPopupTabForWindow(opener: MyWebView?): MyWebView? {
        if (tabs.size >= MAX_TABS) {
            Log.w(TAG, "Cannot create popup window; maximum tabs reached ($MAX_TABS)")
            return null
        }

        val openerTab = currentTab()
        val openerIndex = opener?.let { rootLayout.indexOfChild(it).takeIf { index -> index >= 0 } }
        if (opener != null && openerTab != null) {
            captureActiveTabState()
            opener.visibility = View.GONE
            parkedWebViewsByTabId[openerTab.id] = opener
            if (webView === opener) {
                webView = null
            }
        }

        val tabId = nextTabId++
        tabs.add(
            Tab(
                id = tabId,
                title = "New Window",
                url = BLANK_URL
            )
        )
        activeTabIndex = tabs.lastIndex

        val popupWebView = createWebViewForTab(tabId, openerIndex?.plus(1))
        webView = popupWebView
        popupWebView.visibility = View.VISIBLE

        syncTabLifecycle()
        updateTabBounds()
        updateWebViewLayout()
        notifyStateChanged()
        return popupWebView
    }

    /**
     * Create and configure a WebView for a tab.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewForTab(tabId: Int, preferredIndex: Int? = null): MyWebView {
        val webView = MyWebView(context)

        // Critical: Set focusable but prevent IME
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        // Disable IME at the view level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        // Single debounced hide on focus change
        webView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                webView.scheduleKeyboardHide()
            }
        }

        // Configure WebView
        webView.settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            offscreenPreRaster = false
            setSupportMultipleWindows(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_ON
            }
        }

        configureCookiePolicy(webView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        }

        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        // Capture the tab's WebView in a local variable for the callbacks
        val tabWebView = webView

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                var activeUrlChanged = false
                tabs.find { it.id == tabId }?.let { tab ->
                    val nextUrl = url ?: ""
                    val urlChanged = tab.url != nextUrl
                    tab.url = nextUrl
                    activeUrlChanged = urlChanged && tab.id == currentTab()?.id
                }
                pageAgentRevision++
                // URL and loading state change together.  Suppress the loading-only callback
                // when the active persisted URL changed so the listener receives one cumulative
                // persistent signal for the navigation.
                setTabLoading(
                    tabId,
                    isLoading = true,
                    progress = 0,
                    notifyState = !activeUrlChanged
                )
                if (activeUrlChanged) listener?.onPersistentStateChanged()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
                // Update tab URL
                tabs.find { it.id == tabId }?.url = url ?: ""
                pageAgentRevision++
                injectMediaPlaybackDefaults(view)
                setTabLoading(tabId, isLoading = false, progress = 100)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val didCrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && detail?.didCrash() == true
                Log.w(
                    TAG,
                    "WebView render process gone for tab=$tabId, didCrash=$didCrash"
                )
                setTabLoading(tabId, isLoading = false, progress = 100)
                recreateTabWebView(tabId, tabs.find { it.id == tabId }?.url)
                return true
            }

            override fun onScaleChanged(
                view: WebView?,
                oldScale: Float,
                newScale: Float
            ) {
                super.onScaleChanged(view, oldScale, newScale)
                tabWebView.recordPageScale(newScale)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                // Update tab title
                tabs.find { it.id == tabId }?.title = title ?: "Untitled"
                pageAgentRevision++
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }

                Log.d(TAG, "onShowCustomView - entering video fullscreen")

                customView = view
                customViewCallback = callback
                isVideoFullscreen = true
                resetAutoFullscreenVideoAutomation()
                injectMediaPlaybackDefaults(tabWebView)

                tabWebView.visibility = View.GONE

                view?.let { videoView ->
                    videoView.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    videoView.visibility = if (isDisplayVisible) View.VISIBLE else View.GONE
                    rootLayout.addView(videoView, 1)
                }

                notifyStateChanged()
            }

            override fun onHideCustomView() {
                Log.d(TAG, "onHideCustomView - exiting video fullscreen")

                customView?.let { view ->
                    rootLayout.removeView(view)
                }
                customView = null

                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                isVideoFullscreen = false

                tabWebView.visibility = if (isMinimized || !isDisplayVisible) View.GONE else View.VISIBLE

                notifyStateChanged()
            }

            override fun getDefaultVideoPoster(): Bitmap {
                return createBitmap(1, 1)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                val progress = newProgress.coerceIn(0, 100)
                setTabLoading(tabId, isLoading = progress < 100, progress = progress)
                if (progress >= 40) {
                    injectMediaPlaybackDefaults(view)
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popupWebView = createPopupTabForWindow((view as? MyWebView) ?: webView) ?: return false
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                Log.d(TAG, "Created popup WebView tab, isDialog=$isDialog, userGesture=$isUserGesture")
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                if (window === webView) {
                    closeTab(activeTabIndex)
                }
            }
        }

        if (defaultUserAgent == null) {
            defaultUserAgent = webView.settings.userAgentString
        }

        if (isDesktopMode) {
            webView.settings.userAgentString = desktopUserAgent
        }

        // Initially hidden - will be shown when tab is active
        webView.visibility = View.GONE

        val insertIndex = (preferredIndex ?: lastAttachedWebViewIndex ?: 0).coerceIn(0, rootLayout.childCount)
        rootLayout.addView(webView, insertIndex)
        lastAttachedWebViewIndex = insertIndex

        return webView
    }

    /**
     * Switch to a different tab.
     */
    private fun switchToTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        if (index == activeTabIndex && webView != null) {
            updateWebViewLayout()
            syncLoadingAnimation()
            return
        }

        Log.d(TAG, "Switching to tab $index")

        val oldIndex = releaseActiveWebView(persistState = true)

        activeTabIndex = index
        ensureActiveWebView(preferredIndex = oldIndex)

        syncTabLifecycle()
        updateWebViewLayout()
        syncLoadingAnimation()
        notifyStateChanged()
    }

    /**
     * Close a tab at the given index.
     */
    private fun closeTab(index: Int) {
        if (tabs.size <= 1) {
            // Don't close the last tab - just navigate home
            currentTab()?.apply {
                savedState = null
                title = "New Tab"
                url = HOME_URL
            }
            ensureActiveWebView()
            webView?.loadUrl(HOME_URL)
            return
        }

        if (index < 0 || index >= tabs.size) return

        Log.d(TAG, "Closing tab $index")

        val tab = tabs[index]
        tab.savedState = null
        if (index != activeTabIndex) {
            destroyParkedWebView(tab.id)
        }

        val closingActiveTab = index == activeTabIndex
        val oldIndex = if (closingActiveTab) releaseActiveWebView(persistState = false) else null

        tabs.removeAt(index)

        // Adjust activeTabIndex
        if (activeTabIndex >= tabs.size) {
            activeTabIndex = tabs.size - 1
        } else if (index < activeTabIndex) {
            activeTabIndex--
        } else if (index == activeTabIndex) {
            // We just closed the active tab, need to switch
            activeTabIndex = activeTabIndex.coerceIn(0, tabs.size - 1)
        }

        if (closingActiveTab) {
            ensureActiveWebView(preferredIndex = oldIndex)
        }
        syncTabLifecycle()
        updateWebViewLayout()
        updateTabBounds()
        syncLoadingAnimation()
        notifyStateChanged()
    }

    /**
     * Update tab bounds based on current number of tabs.
     */
    private fun updateTabBounds() {
        tabBounds.clear()
        tabCloseBounds.clear()

        if (tabs.isEmpty()) return

        val availableWidth = tabBarBounds.width() - NEW_TAB_BUTTON_WIDTH - TAB_SPACING * 2
        val tabWidth = ((availableWidth - TAB_SPACING * tabs.size) / tabs.size)
            .coerceIn(TAB_MIN_WIDTH, TAB_WIDTH)

        var currentX = tabBarBounds.left + TAB_SPACING

        repeat(tabs.size) {
            val tabRect = RectF(
                currentX,
                tabBarBounds.top + 2f,
                currentX + tabWidth,
                tabBarBounds.bottom - 2f
            )
            tabBounds.add(tabRect)

            // Close button on the right side of tab
            val closeRect = RectF(
                tabRect.right - TAB_CLOSE_SIZE - 4f,
                tabRect.centerY() - TAB_CLOSE_SIZE / 2,
                tabRect.right - 4f,
                tabRect.centerY() + TAB_CLOSE_SIZE / 2
            )
            tabCloseBounds.add(closeRect)

            currentX += tabWidth + TAB_SPACING
        }

        // New tab button
        newTabButtonBounds.set(
            currentX,
            tabBarBounds.top + 2f,
            currentX + NEW_TAB_BUTTON_WIDTH,
            tabBarBounds.bottom - 2f
        )
    }

    private fun updateWebViewLayout() {
        syncLiveWebViewState()
        val activeWebView = webView ?: return

        if (!shouldKeepLiveWebView()) {
            activeWebView.visibility = View.GONE
            return
        }

        activeWebView.visibility = View.VISIBLE

        // Push content down by NAV_BAR_HEIGHT + TAB_BAR_HEIGHT
        val topOffset = NAV_BAR_HEIGHT + TAB_BAR_HEIGHT
        val topMargin = contentBounds.top + topOffset
        // Reduce height if keyboard is visible
        val keyboardHeight = keyboardOverlay.getHeight()
        val height = contentBounds.height() - topOffset - keyboardHeight

        val params = FrameLayout.LayoutParams(
            contentBounds.width().toInt(),
            height.toInt().coerceAtLeast(1)
        )
        params.leftMargin = contentBounds.left.toInt()
        params.topMargin = topMargin.toInt()

        activeWebView.layoutParams = params
    }

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        // Button positions (close, fullscreen, minimize, pin) are now handled by BaseWidget

        // Navigation Bar - positioned at the top of contentBounds
        navBarBounds.set(
            contentBounds.left,
            contentBounds.top,
            contentBounds.right,
            contentBounds.top + NAV_BAR_HEIGHT
        )

        // Nav Buttons
        var currentX = navBarBounds.left + NAV_BUTTON_SPACING
        val centerY = navBarBounds.centerY()
        val halfSize = NAV_BUTTON_SIZE / 2

        // Back
        backButtonBounds.set(currentX, centerY - halfSize, currentX + NAV_BUTTON_SIZE, centerY + halfSize)
        currentX += NAV_BUTTON_SIZE + NAV_BUTTON_SPACING

        // Forward
        forwardButtonBounds.set(currentX, centerY - halfSize, currentX + NAV_BUTTON_SIZE, centerY + halfSize)
        currentX += NAV_BUTTON_SIZE + NAV_BUTTON_SPACING

        // Refresh
        refreshButtonBounds.set(currentX, centerY - halfSize, currentX + NAV_BUTTON_SIZE, centerY + halfSize)
        currentX += NAV_BUTTON_SIZE + NAV_BUTTON_SPACING

        // Home
        homeButtonBounds.set(currentX, centerY - halfSize, currentX + NAV_BUTTON_SIZE, centerY + halfSize)
        currentX += NAV_BUTTON_SIZE + NAV_BUTTON_SPACING

        // Mode toggle (Glasses <-> Desktop)
        modeButtonBounds.set(currentX, centerY - halfSize, currentX + NAV_BUTTON_SIZE, centerY + halfSize)
        currentX += NAV_BUTTON_SIZE + NAV_BUTTON_SPACING

        // URL Bar (Takes remaining space)
        val urlBarRight = navBarBounds.right - NAV_BUTTON_SPACING
        urlBarBounds.set(currentX, centerY - halfSize, urlBarRight, centerY + halfSize)

        // Tab Bar - positioned below navigation bar
        tabBarBounds.set(
            contentBounds.left,
            navBarBounds.bottom,
            contentBounds.right,
            navBarBounds.bottom + TAB_BAR_HEIGHT
        )

        // Keyboard button at the bottom center edge
        keyboardOverlay.updateButtonPosition(widgetBounds.centerX(), widgetBounds.bottom)

        updateTabBounds()
        updateKeyboardLayout()
        updateUrlEditorBounds()
        updateWebViewLayout()
    }

    /**
     * Update keyboard layout when widget size changes or keyboard is toggled.
     */
    private fun updateKeyboardLayout() {
        val webViewArea = getWebViewBounds()
        keyboardOverlay.updateLayout(
            areaWidth = webViewArea.width(),
            areaHeight = webViewArea.height(),
            areaLeft = webViewArea.left,
            areaTop = webViewArea.top
        )
    }

    /**
     * Get the bounds of the WebView content area.
     */
    private fun getWebViewBounds(): RectF {
        val webViewTop = tabBarBounds.bottom
        return RectF(contentBounds.left, webViewTop, contentBounds.right, contentBounds.bottom)
    }

    /**
     * Toggle keyboard visibility.
     */
    fun toggleKeyboard() {
        val nowVisible = keyboardOverlay.toggle()
        if (nowVisible) {
            updateKeyboardLayout()
        }
        updateWebViewLayout()
        listener?.onStateChanged(state)
    }

     /**
     * Get the keyboard for external hover/tap handling.
     */
    fun getKeyboard(): GlassesKeyboardView? = keyboardOverlay.getKeyboard()

    override fun toggleMinimize() {
        super.toggleMinimize()
        updateWebViewLayout()
        syncLoadingAnimation()
        notifyStateChanged()
    }

    override fun exitFullscreen() {
        super.exitFullscreen()
        updateWebViewLayout()
        notifyStateChanged()
    }

    override fun setFullscreenBounds(screenWidth: Float, screenHeight: Float) {
        super.setFullscreenBounds(screenWidth, screenHeight)
        updateWebViewLayout()
        notifyStateChanged()
    }

    override fun startDrag(isResize: Boolean) {
        super.startDrag(isResize)
        state = if (isResize) State.RESIZING else State.MOVING
        listener?.onStateChanged(state)
    }

    override fun onDrag(dx: Float, dy: Float, screenWidth: Float, screenHeight: Float) {
        super.onDrag(dx, dy, screenWidth, screenHeight)
        if (state == State.MOVING || baseState == BaseState.RESIZING) {
            updateWebViewLayout()
        }
    }

    override fun onPause() {
        if (isHostPaused) return
        isHostPaused = true
        syncLiveWebViewState()
        syncTabLifecycle()
        syncLoadingAnimation()
    }

    override fun onResume() {
        if (!isHostPaused) return
        isHostPaused = false
        syncLiveWebViewState()
        syncTabLifecycle()
        updateWebViewLayout()
        syncLoadingAnimation()
    }

    override fun onDisplayVisibilityChanged(visible: Boolean) {
        if (isDisplayVisible == visible) return
        isDisplayVisible = visible
        customView?.visibility = if (visible) View.VISIBLE else View.GONE
        syncLiveWebViewState()
        updateWebViewLayout()
        syncLoadingAnimation()
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (!shouldKeepLiveWebView()) {
                releaseActiveWebView(persistState = true)
            } else {
                captureActiveTabState()
                webView?.clearCache(false)
            }
        }
    }

    fun onLowMemory() {
        if (!shouldKeepLiveWebView()) {
            releaseActiveWebView(persistState = true)
        } else {
            captureActiveTabState()
            webView?.clearCache(false)
        }
    }

    fun clearDebugWebViewData(profileSummary: String) {
        Log.d(TAG, "Clearing WebView debug data, binocularProfile=$profileSummary")
        captureActiveTabState()
        tabs.forEach { tab ->
            tab.savedState = null
        }
        webView?.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            clearFormData()
            clearSslPreferences()
        }
        destroyAllParkedWebViews()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        // Exit video fullscreen if active
        exitVideoFullscreen()
        resetAutoFullscreenVideoAutomation()
        stopLoadingAnimation()

        releaseActiveWebView(persistState = false)
        destroyAllParkedWebViews()
        for (tab in tabs) {
            tab.savedState = null
        }
        tabs.clear()
    }

    /**
     * Helper to notify state changed callback.
     */
    private fun notifyStateChanged() {
        listener?.onStateChanged(state)
    }

    /**
     * Check if a video is currently playing in fullscreen mode.
     */
    fun isInVideoFullscreen(): Boolean = isVideoFullscreen

    /**
     * Exit video fullscreen mode if active.
     * Call this when the user presses back or wants to exit fullscreen.
     * @return true if was in video fullscreen and exited, false otherwise
     */
    fun exitVideoFullscreen(): Boolean {
        if (!isVideoFullscreen) return false
        resetAutoFullscreenVideoAutomation()

        Log.d(TAG, "exitVideoFullscreen - manually exiting video fullscreen")

        customView?.let { view ->
            rootLayout.removeView(view)
        }
        customView = null

        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        isVideoFullscreen = false

        // Restore WebView visibility
        syncLiveWebViewState()
        webView?.visibility = if (shouldKeepLiveWebView()) View.VISIBLE else View.GONE

        notifyStateChanged()
        return true
    }

    // Input handling
    fun setFocused(focused: Boolean) {
        if (isFocused == focused) return
        isFocused = focused
        listener?.onFocusChanged(focused)

        if (focused) {
            ensureActiveWebView()
            webView?.requestFocus()
            hideSystemIme(webView)
        } else {
            webView?.clearFocus()
            // Exit URL editing mode when losing focus
            if (isEditingUrl) {
                exitAddressEditing()
                textZoneHost?.release(addressZone)
            }
        }
    }


    /**
     * Dispatch touch event to WebView, translating coordinates from screen to WebView local.
     */
    fun dispatchTouchEvent(x: Float, y: Float, action: Int) {
        val activeWebView = webView ?: ensureActiveWebView() ?: return
        val now = SystemClock.uptimeMillis()
        val downTime = if (action == MotionEvent.ACTION_DOWN) {
            lastDownTime = now
            now
        } else {
            if (lastDownTime > 0) lastDownTime else now
        }

        injectBrowserInputDefaults(activeWebView)
        dispatchTouchEventInternal(activeWebView, x, y, action, downTime, now)

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            lastDownTime = 0L
        }
    }

    fun shouldStartContentTouch(px: Float, py: Float): Boolean {
        updateContentInteractionTarget(px, py)
        if (!isContentInteractionTarget) return false

        val dx = px - contentInteractionTargetX
        val dy = py - contentInteractionTargetY
        return dx * dx + dy * dy <= CONTENT_INTERACTION_CACHE_RADIUS_PX * CONTENT_INTERACTION_CACHE_RADIUS_PX
    }

    private fun updateContentInteractionTarget(px: Float, py: Float) {
        val activeWebView = webView ?: return
        val contentArea = getWebViewBounds()
        if (!contentArea.contains(px, py)) {
            isContentInteractionTarget = false
            return
        }

        val now = SystemClock.uptimeMillis()
        val dx = px - contentInteractionTargetX
        val dy = py - contentInteractionTargetY
        val nearCachedPoint = dx * dx + dy * dy <= CONTENT_INTERACTION_CACHE_RADIUS_PX * CONTENT_INTERACTION_CACHE_RADIUS_PX
        if (nearCachedPoint && now - lastContentInteractionQueryMs < CONTENT_INTERACTION_QUERY_INTERVAL_MS) {
            return
        }

        lastContentInteractionQueryMs = now
        val generation = ++contentInteractionQueryGeneration
        val localX = (px - contentBounds.left).coerceIn(0f, activeWebView.width.toFloat().coerceAtLeast(1f) - 1f)
        val localY = (py - (contentBounds.top + NAV_BAR_HEIGHT + TAB_BAR_HEIGHT))
            .coerceIn(0f, activeWebView.height.toFloat().coerceAtLeast(1f) - 1f)
        val cssScale = activeWebView.currentPageScale
        val cssX = localX / cssScale
        val cssY = localY / cssScale

        activeWebView.evaluateJavascript("""
            (function() {
                var el = document.elementFromPoint($cssX, $cssY);
                var depth = 0;
                while (el && depth++ < 8) {
                    var tag = (el.tagName || '').toLowerCase();
                    if (tag === 'html' || tag === 'body') return false;

                    var role = (el.getAttribute('role') || '').toLowerCase();
                    var cursor = '';
                    try { cursor = (window.getComputedStyle(el).cursor || '').toLowerCase(); } catch (e) {}

                    if (/^(a|button|input|textarea|select|option|label|summary|video|audio)$/.test(tag)) return true;
                    if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') return true;
                    if (el.hasAttribute('href') || el.hasAttribute('onclick') || typeof el.onclick === 'function') return true;
                    if (el.hasAttribute('draggable') || el.getAttribute('aria-valuenow') != null || el.getAttribute('aria-valuemax') != null) return true;
                    if (el.tabIndex >= 0) return true;
                    if (/(button|link|checkbox|radio|menuitem|option|slider|spinbutton|switch|tab|textbox|searchbox|combobox)/.test(role)) return true;
                    if (/(pointer|grab|grabbing|move|resize|col-resize|row-resize|ew-resize|ns-resize)/.test(cursor)) return true;

                    el = el.parentElement;
                }
                return false;
            })()
        """.trimIndent()) { result ->
            if (generation != contentInteractionQueryGeneration) return@evaluateJavascript
            isContentInteractionTarget = result == "true"
            contentInteractionTargetX = px
            contentInteractionTargetY = py
        }
    }

    private fun performContentTap(x: Float, y: Float) {
        val activeWebView = webView ?: ensureActiveWebView() ?: return
        val downTime = SystemClock.uptimeMillis()
        dispatchTouchEventInternal(activeWebView, x, y, MotionEvent.ACTION_DOWN, downTime, downTime)
        dispatchTouchEventInternal(activeWebView, x, y, MotionEvent.ACTION_UP, downTime, downTime + 40L)
        lastDownTime = 0L
    }

    private fun dispatchTouchEventInternal(
        webView: MyWebView,
        x: Float,
        y: Float,
        action: Int,
        downTime: Long,
        eventTime: Long
    ) {
        val localX = (x - contentBounds.left).coerceIn(0f, webView.width.toFloat().coerceAtLeast(1f) - 1f)
        val localY = (y - (contentBounds.top + NAV_BAR_HEIGHT + TAB_BAR_HEIGHT))
            .coerceIn(0f, webView.height.toFloat().coerceAtLeast(1f) - 1f)

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            localX,
            localY,
            0
        )
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        isDispatchingSyntheticTouch = true
        try {
            webView.dispatchTouchEvent(event)
        } finally {
            isDispatchingSyntheticTouch = false
            event.recycle()
        }
    }

    private fun normalizeAddressInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return HOME_URL

        val hasHttpScheme = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        if (hasHttpScheme && trimmed.substringAfter("://", "").isNotBlank() && !trimmed.contains(Regex("\\s"))) {
            return trimmed
        }

        val looksLikeAddress =
            !trimmed.contains(Regex("\\s")) &&
            (trimmed.contains(".") ||
                trimmed.startsWith("localhost", ignoreCase = true) ||
                trimmed.matches(Regex("""\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?""")))

        return if (looksLikeAddress) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=${URLEncoder.encode(trimmed, "UTF-8")}"
        }
    }

    fun navigateToAddress(address: String, autoFullscreenVideo: Boolean = false) {
        val url = normalizeAddressInput(address)
        if (autoFullscreenVideo) {
            autoFullscreenGestureAttempts = 0
            cancelAutoFullscreenGestureAttempts()
            autoFullscreenVideoUntilMs = SystemClock.uptimeMillis() + AUTO_FULLSCREEN_VIDEO_WINDOW_MS
        } else {
            resetAutoFullscreenVideoAutomation()
        }
        currentTab()?.apply {
            savedState = null
            this.url = url
        }
        ensureActiveWebView()
        webView?.loadUrl(url)
        exitAddressEditing()
        textZoneHost?.release(addressZone)
        listener?.onKeyboardRequested(false)
        listener?.onStateChanged(state)
    }

    private fun wrapAddressText(text: String, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val count = urlTextPaint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            lines.add(remaining.take(count))
            remaining = remaining.drop(count)
        }
        return lines
    }

    private fun updateUrlEditorBounds() {
        if (!isEditingUrl) {
            urlEditorBounds.setEmpty()
            return
        }
        val maxTextWidth = (urlBarBounds.width() - URL_EDITOR_PADDING * 2).coerceAtLeast(1f)
        val lines = wrapAddressText(editingUrlText.toString(), maxTextWidth)
        val rows = lines.size.coerceIn(1, URL_EDITOR_MAX_ROWS)
        val lineHeight = urlTextPaint.fontSpacing
        val height = URL_EDITOR_PADDING * 2 + lineHeight * rows
        val top = urlBarBounds.top
        urlEditorBounds.set(
            urlBarBounds.left,
            top,
            urlBarBounds.right,
            (top + height).coerceAtMost(contentBounds.bottom)
        )
    }

    /** Sends a key to the web page; address bar keys are handled by the text editing controller. */
    fun onKeyPress(key: String) {
        if (!isFocused) return

        val wv = webView ?: ensureActiveWebView() ?: return

        fun js(scriptBody: String) {
            // Wrap in IIFE to avoid leaking vars
            wv.evaluateJavascript("(function(){ $scriptBody })();", null)
        }

        fun insertText(text: String) {
            val q = JSONObject.quote(text)
            js("""
            var el = document.activeElement;
            if (!el) return;

            // Prefer execCommand when available (works for many inputs/contenteditable)
            if (document.execCommand) {
                document.execCommand('insertText', false, $q);
                return;
            }

            // Fallback for input/textarea
            var tag = (el.tagName || '').toUpperCase();
            if (tag === 'INPUT' || tag === 'TEXTAREA') {
                var start = el.selectionStart;
                var end = el.selectionEnd;
                if (start == null || end == null) {
                    el.value = (el.value || '') + $q;
                } else {
                    el.setRangeText($q, start, end, 'end');
                }
                el.dispatchEvent(new Event('input', {bubbles:true}));
            }
        """.trimIndent())
        }

        fun backspace() {
            js("""
            var el = document.activeElement;
            if (!el) return;

            if (document.execCommand) {
                document.execCommand('delete', false, null);
                return;
            }

            var tag = (el.tagName || '').toUpperCase();
            if (tag === 'INPUT' || tag === 'TEXTAREA') {
                var start = el.selectionStart;
                var end = el.selectionEnd;

                if (start == null || end == null) return;

                if (start !== end) {
                    el.setRangeText('', start, end, 'end');
                } else if (start > 0) {
                    el.setRangeText('', start - 1, start, 'end');
                }
                el.dispatchEvent(new Event('input', {bubbles:true}));
            }
        """.trimIndent())
        }

        fun enter() {
            js("""
            var el = document.activeElement;
            if (!el) return;

            var tag = (el.tagName || '').toUpperCase();
            var isContentEditable = !!el.isContentEditable;

            // If it's multiline editing, insert newline
            if (tag === 'TEXTAREA' || isContentEditable) {
                if (document.execCommand) {
                    document.execCommand('insertText', false, '\n');
                } else {
                    // Minimal fallback: append newline
                    if (tag === 'TEXTAREA') {
                        var start = el.selectionStart, end = el.selectionEnd;
                        if (start != null && end != null) {
                            el.setRangeText('\n', start, end, 'end');
                            el.dispatchEvent(new Event('input', {bubbles:true}));
                        }
                    }
                }
                return;
            }

            // Otherwise: behave like desktop enter in single-line inputs => submit/search
            if (tag === 'INPUT') {
                var form = el.form;
                if (form) {
                    if (form.requestSubmit) form.requestSubmit();
                    else form.submit();
                    return;
                }
            }

            // Last resort: fire Enter key events (some sites listen for this)
            var down = new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true});
            var up   = new KeyboardEvent('keyup',   {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true});
            el.dispatchEvent(down);
            el.dispatchEvent(up);
        """.trimIndent())
        }

        when (key) {
            "BACKSPACE" -> backspace()

            // IMPORTANT: phone may send a real newline, not "ENTER"
            "ENTER", "\n" -> enter()

            // IMPORTANT: phone may send a real space, not "SPACE"
            "SPACE", " " -> insertText(" ")

            else -> {
                // If phone sends actual characters (including punctuation), insert them as-is.
                // Also supports multi-character paste-like events.
                if (key.isNotEmpty()) insertText(key)
            }
        }
    }

    // Navigation helpers
    fun canGoBack(): Boolean = webView?.canGoBack() == true
    fun canGoForward(): Boolean = webView?.canGoForward() == true

    // State management
    override fun isHovering(): Boolean {
        return isBorderHovered ||
               state == State.HOVER_CONTENT ||
               state == State.MOVING ||
               baseState == BaseState.HOVER_RESIZE ||
               baseState == BaseState.RESIZING ||
               state == State.HOVER_NAV ||
               state == State.HOVER_TAB
    }

    /**
     * Override to include nav bar and tab bar as part of the border zone.
     */
    override fun isPointOverBorderButton(px: Float, py: Float): Boolean {
        // Check base buttons first
        if (super.isPointOverBorderButton(px, py)) return true

        // Nav bar is part of border
        if (navBarBounds.contains(px, py)) return true

        // Tab bar is part of border
        if (tabBarBounds.contains(px, py)) return true

        return false
    }

    // Scrolling support (for future external scroll control)
    override fun onScroll(dy: Float) {
        if (isEditingUrl && urlEditorBounds.contains(cursorSafeX, cursorSafeY)) return
        (webView ?: ensureActiveWebView())?.scrollBy(0, dy.toInt())
    }

    // Store the hit tab index for tap handling
    private var hitTabIndex = -1

    fun hitTest(px: Float, py: Float): HitArea {
        hitTabIndex = -1

        // Check base widget buttons first (close button is handled by baseHitTest)
        val baseResult = baseHitTest(px, py)
        when (baseResult) {
            BaseHitArea.CLOSE_BUTTON -> return HitArea.NONE  // Handled via onCloseRequested callback
            BaseHitArea.FULLSCREEN_BUTTON -> return HitArea.FULLSCREEN_BUTTON
            BaseHitArea.MINIMIZE_BUTTON -> return HitArea.MINIMIZE_BUTTON
            BaseHitArea.PIN_BUTTON -> return HitArea.PIN_BUTTON
            BaseHitArea.RESIZE_HANDLE -> return HitArea.RESIZE_HANDLE
            else -> {}
        }

        // Check keyboard key (if keyboard is visible)
        if (keyboardOverlay.containsPoint(px, py)) {
            return HitArea.KEYBOARD_KEY
        }

        // Check keyboard button (at bottom edge)
        if (keyboardButtonBounds.contains(px, py)) {
            return HitArea.KEYBOARD_BUTTON
        }

        // Navigation bar hit testing
        if (navBarBounds.contains(px, py)) {
            if (backButtonBounds.contains(px, py)) return HitArea.NAV_BACK
            if (forwardButtonBounds.contains(px, py)) return HitArea.NAV_FORWARD
            if (refreshButtonBounds.contains(px, py)) return HitArea.NAV_REFRESH
            if (homeButtonBounds.contains(px, py)) return HitArea.NAV_HOME
            if (modeButtonBounds.contains(px, py)) return HitArea.NAV_MODE
            if (urlBarBounds.contains(px, py)) return HitArea.NAV_URL
            return HitArea.NONE
        }

        if (isEditingUrl && urlEditorBounds.contains(px, py)) return HitArea.NAV_URL

        // Tab bar hit testing
        if (tabBarBounds.contains(px, py)) {
            // Check new tab button first
            if (newTabButtonBounds.contains(px, py)) return HitArea.NEW_TAB

            // Check tab close buttons
            for (i in tabCloseBounds.indices) {
                if (tabCloseBounds[i].contains(px, py)) {
                    hitTabIndex = i
                    return HitArea.TAB_CLOSE
                }
            }

            // Check tabs
            for (i in tabBounds.indices) {
                if (tabBounds[i].contains(px, py)) {
                    hitTabIndex = i
                    return HitArea.TAB
                }
            }
            return HitArea.NONE
        }

        if (contentBounds.contains(px, py)) return HitArea.CONTENT

        val expandedBounds = RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) return HitArea.BORDER

        return HitArea.NONE
    }

    override fun containsPoint(px: Float, py: Float): Boolean {
        if (isEditingUrl && urlEditorBounds.contains(px, py)) return true
        // Check keyboard button (extends below widget)
        if (keyboardButtonBounds.contains(px, py)) return true
        // All other button bounds are now handled by BaseWidget
        return super.containsPoint(px, py)
    }

    override fun updateHover(px: Float, py: Float) {
        if (state == State.MOVING) return
        cursorSafeX = px
        cursorSafeY = py

        // Reset nav hover states
        isHoveringBack = false
        isHoveringForward = false
        isHoveringRefresh = false
        isHoveringHome = false
        isHoveringMode = false
        isHoveringUrlBar = false

        // Reset tab hover states
        hoveringTabIndex = -1
        hoveringTabClose = -1
        isHoveringNewTab = false

        // Update keyboard hover state
        keyboardOverlay.updateHover(px, py)

        // Use unified hover state from BaseWidget
        val baseResult = updateHoverState(px, py)

        // Update nav button specific hovers (for visual feedback)
        if (navBarBounds.contains(px, py)) {
            if (backButtonBounds.contains(px, py)) isHoveringBack = true
            else if (forwardButtonBounds.contains(px, py)) isHoveringForward = true
            else if (refreshButtonBounds.contains(px, py)) isHoveringRefresh = true
            else if (homeButtonBounds.contains(px, py)) isHoveringHome = true
            else if (modeButtonBounds.contains(px, py)) isHoveringMode = true
            else if (urlBarBounds.contains(px, py)) isHoveringUrlBar = true
        }
        if (isEditingUrl && urlEditorBounds.contains(px, py)) {
            isHoveringUrlBar = true
        }

        // Update tab specific hovers (for visual feedback)
        if (tabBarBounds.contains(px, py)) {
            if (newTabButtonBounds.contains(px, py)) {
                isHoveringNewTab = true
            } else {
                for (i in tabCloseBounds.indices) {
                    if (tabCloseBounds[i].contains(px, py)) {
                        hoveringTabClose = i
                        break
                    }
                }
                if (hoveringTabClose == -1) {
                    for (i in tabBounds.indices) {
                        if (tabBounds[i].contains(px, py)) {
                            hoveringTabIndex = i
                            break
                        }
                    }
                }
            }
        }

        val chromeState = WidgetInteractionState(baseResult).chromeState
        val newState = when {
            navBarBounds.contains(px, py) -> State.HOVER_NAV
            isEditingUrl && urlEditorBounds.contains(px, py) -> State.HOVER_NAV
            tabBarBounds.contains(px, py) -> State.HOVER_TAB
            chromeState == BaseState.HOVER_BORDER -> State.HOVER_BORDER
            baseResult == BaseState.HOVER_CONTENT -> State.HOVER_CONTENT
            baseResult == BaseState.MOVING -> State.MOVING
            else -> State.IDLE
        }

        if (newState != state) {
            state = newState
        }
    }

    fun onTap(px: Float, py: Float): Boolean {
        if (handleChromeTap(px, py)) return true

        val hitArea = hitTest(px, py)

        return when (hitArea) {
            HitArea.FULLSCREEN_BUTTON -> {
                toggleFullscreen()
                true
            }
            HitArea.MINIMIZE_BUTTON -> {
                toggleMinimize()
                true
            }
            HitArea.PIN_BUTTON -> {
                isPinned = !isPinned
                true
            }
            HitArea.KEYBOARD_BUTTON -> {
                toggleKeyboard()
                true
            }
            HitArea.KEYBOARD_KEY -> {
                // Handle keyboard key tap
                keyboardOverlay.onTap(px, py)
                true
            }
            HitArea.NAV_BACK -> {
                val activeWebView = webView ?: ensureActiveWebView()
                if (activeWebView?.canGoBack() == true) activeWebView.goBack()
                true
            }
            HitArea.NAV_FORWARD -> {
                val activeWebView = webView ?: ensureActiveWebView()
                if (activeWebView?.canGoForward() == true) activeWebView.goForward()
                true
            }
            HitArea.NAV_REFRESH -> {
                ensureActiveWebView()?.reload()
                true
            }
            HitArea.NAV_HOME -> {
                currentTab()?.apply {
                    savedState = null
                    url = HOME_URL
                }
                ensureActiveWebView()
                webView?.loadUrl(HOME_URL)
                true
            }
            HitArea.NAV_MODE -> {
                toggleUserAgentMode()
                true
            }
            HitArea.NAV_URL -> {
                if (isEditingUrl && textZoneHost?.isFocused(addressZone) == true) {
                    textZoneHost?.tap(addressZone, px, py)
                } else {
                    beginAddressEditing(selectAll = true)
                }
                true
            }
            HitArea.NEW_TAB -> {
                createNewTab()
                true
            }
            HitArea.TAB_CLOSE -> {
                if (hitTabIndex >= 0) {
                    closeTab(hitTabIndex)
                }
                true
            }
            HitArea.TAB -> {
                if (hitTabIndex >= 0 && hitTabIndex != activeTabIndex) {
                    switchToTab(hitTabIndex)
                }
                true
            }
            HitArea.CONTENT -> {
                setFocused(true)

                // Exit URL editing mode when tapping content (but don't hide keyboard - WebView may need it)
                if (isEditingUrl) {
                    exitAddressEditing()
                    textZoneHost?.release(addressZone)
                }

                performContentTap(px, py)

                true
            }
            HitArea.BORDER -> {
                if (!isFullscreen) {
                    state = State.MOVING
                    baseState = BaseState.MOVING
                    listener?.onStateChanged(state)
                }
                true
            }
            else -> false
        }
    }

    private fun toggleUserAgentMode() {
        val wv = webView ?: ensureActiveWebView() ?: return
        val settings = wv.settings

        if (defaultUserAgent == null) {
            defaultUserAgent = settings.userAgentString
        }

        isDesktopMode = !isDesktopMode
        settings.userAgentString = if (isDesktopMode) desktopUserAgent else (defaultUserAgent ?: settings.userAgentString)
        tabs.forEach { tab ->
            tab.savedState = null
        }

        // Reload current page so the site receives the new UA
        wv.reload()

        Log.d(TAG, "User-Agent mode: " + (if (isDesktopMode) "DESKTOP" else "MOBILE"))
    }


    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        if (shouldShowBorder() && !isVideoFullscreen) {
            canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        }
        if (shouldShowBorderButtons() && !isVideoFullscreen) {
            drawBorderButtons(canvas)
        }

        // Draw Navigation Bar
        if (!isVideoFullscreen) {
            drawNavigationBar(canvas)
        }

        // Draw Tab Bar
        if (!isVideoFullscreen) {
            drawTabBar(canvas)
        }

        if (!isVideoFullscreen) {
            drawFocusedUrlEditor(canvas)
        }

        if (shouldDrawLoadingIndicator()) {
            drawLoadingIndicator(canvas)
        }

        // Draw keyboard if visible
        if (!isVideoFullscreen) {
            keyboardOverlay.draw(canvas)
        }

        // Keyboard button at bottom edge (show when border is hovered or keyboard is visible)
        if ((isBorderHovered || isKeyboardVisible) && !isVideoFullscreen) {
            drawKeyboardButton(canvas)
        }

        // Resize handle only when not fullscreen and hovering border
        if (!isFullscreen && !isVideoFullscreen) {
            drawResizeHandle(canvas)
        }
    }

    private fun drawLoadingIndicator(canvas: Canvas) {
        val area = RectF(
            contentBounds.left,
            tabBarBounds.bottom,
            contentBounds.right,
            contentBounds.bottom - keyboardOverlay.getHeight()
        )
        if (area.width() <= 0f || area.height() <= 0f) return

        canvas.drawRect(area, loadingScrimPaint)

        val radius = 18f
        val centerX = area.centerX()
        val centerY = area.centerY() - 10f
        val spinnerBounds = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )
        val elapsedMs = (SystemClock.uptimeMillis() - loadingSpinnerStartMs).coerceAtLeast(0L)
        val startAngle = (elapsedMs % 1000L) / 1000f * 360f

        canvas.drawArc(spinnerBounds, 0f, 360f, false, loadingSpinnerTrackPaint)
        canvas.drawArc(spinnerBounds, startAngle, 270f, false, loadingSpinnerPaint)
        canvas.drawText("Loading...", centerX, centerY + radius + 28f, loadingTextPaint)
    }

    /**
     * Draw the keyboard toggle button at the bottom edge.
     */
    private fun drawKeyboardButton(canvas: Canvas) {
        keyboardOverlay.drawButton(canvas)
    }

    /**
     * Draw the navigation bar with back, forward, refresh, home buttons and URL bar.
     */
    private fun drawNavigationBar(canvas: Canvas) {
        // Background
        canvas.drawRect(navBarBounds, navBarPaint)

        // Back button
        val backPaint = when {
            !canGoBack() -> navButtonDisabledPaint
            isHoveringBack -> navButtonHoverPaint
            else -> navButtonPaint
        }
        canvas.drawRoundRect(backButtonBounds, 4f, 4f, backPaint)
        drawBackIcon(canvas, backButtonBounds, if (canGoBack()) navIconPaint else navIconDisabledPaint)

        // Forward button
        val forwardPaint = when {
            !canGoForward() -> navButtonDisabledPaint
            isHoveringForward -> navButtonHoverPaint
            else -> navButtonPaint
        }
        canvas.drawRoundRect(forwardButtonBounds, 4f, 4f, forwardPaint)
        drawForwardIcon(canvas, forwardButtonBounds, if (canGoForward()) navIconPaint else navIconDisabledPaint)

        // Refresh button
        val refreshPaint = if (isHoveringRefresh) navButtonHoverPaint else navButtonPaint
        canvas.drawRoundRect(refreshButtonBounds, 4f, 4f, refreshPaint)
        drawRefreshIcon(canvas, refreshButtonBounds, navIconPaint)

        // Home button
        val homePaint = if (isHoveringHome) navButtonHoverPaint else navButtonPaint
        canvas.drawRoundRect(homeButtonBounds, 4f, 4f, homePaint)
        drawHomeIcon(canvas, homeButtonBounds, navIconPaint)

        // Mode button (mobile/desktop UA)
        val modePaint = if (isHoveringMode) navButtonHoverPaint else navButtonPaint
        canvas.drawRoundRect(modeButtonBounds, 4f, 4f, modePaint)
        if (isDesktopMode) {
            drawDesktopIcon(canvas, modeButtonBounds, navIconPaint)
        } else {
            drawGlassesIcon(canvas, modeButtonBounds, navIconPaint)
        }


        // URL bar - highlight when editing
        val urlPaint = when {
            isEditingUrl -> navButtonHoverPaint
            isHoveringUrlBar -> navButtonHoverPaint
            else -> urlBarPaint
        }
        canvas.drawRoundRect(urlBarBounds, 4f, 4f, urlPaint)

        // Draw URL text (show editing text or current URL)
        val displayText = if (isEditingUrl) {
            editingUrlText.toString()
        } else {
            getUrl() ?: ""
        }
        val maxChars = 40
        val displayUrl = if (displayText.length > maxChars) displayText.take(maxChars) + "..." else displayText
        val textY = urlBarBounds.centerY() - (urlTextPaint.descent() + urlTextPaint.ascent()) / 2
        canvas.drawText(displayUrl, urlBarBounds.left + 8f, textY, urlTextPaint)

    }

    private fun drawFocusedUrlEditor(canvas: Canvas) {
        if (!isEditingUrl || textZoneHost?.isFocused(addressZone) != true) return

        updateUrlEditorBounds()
        canvas.drawRoundRect(urlEditorBounds, 4f, 4f, urlEditorPaint)
        canvas.drawRoundRect(urlEditorBounds, 4f, 4f, urlEditorBorderPaint)
        canvas.withClip(addressZone.bounds) {
            textZoneHost?.drawZone(this, addressZone)
        }
    }

    /**
     * Draw the tab bar with tabs, close buttons, and new tab button.
     */
    private fun drawTabBar(canvas: Canvas) {
        // Background
        canvas.drawRect(tabBarBounds, tabBarPaint)

        // Draw tabs
        for (i in tabs.indices) {
            val tabRect = tabBounds.getOrNull(i) ?: continue
            val tab = tabs[i]

            // Determine tab paint
            val paint = when (i) {
                activeTabIndex -> tabActivePaint
                hoveringTabIndex, hoveringTabClose -> tabHoverPaint
                else -> tabInactivePaint
            }

            // Draw tab background
            canvas.drawRoundRect(tabRect, 4f, 4f, paint)

            // Draw tab title (truncated)
            val title = tab.title.take(15).let { if (tab.title.length > 15) "$it…" else it }
            val titleY = tabRect.centerY() - (tabTextPaint.descent() + tabTextPaint.ascent()) / 2
            canvas.withClip(
                tabRect.left + 6f,
                tabRect.top,
                tabRect.right - TAB_CLOSE_SIZE - 4f,
                tabRect.bottom
            ) {
                drawText(title, tabRect.left + 6f, titleY, tabTextPaint)
            }

            // Draw close button (X)
            val closeRect = tabCloseBounds.getOrNull(i)
            if (closeRect != null) {
                val closePaint = if (i == hoveringTabClose) tabHoverPaint else tabInactivePaint
                canvas.drawRoundRect(closeRect, 2f, 2f, closePaint)

                val cx = closeRect.centerX()
                val cy = closeRect.centerY()
                val offset = TAB_CLOSE_SIZE * 0.25f
                canvas.drawLine(cx - offset, cy - offset, cx + offset, cy + offset, tabCloseIconPaint)
                canvas.drawLine(cx + offset, cy - offset, cx - offset, cy + offset, tabCloseIconPaint)
            }
        }

        // Draw new tab button (+)
        val newTabPaint = if (isHoveringNewTab) tabHoverPaint else newTabButtonPaint
        canvas.drawRoundRect(newTabButtonBounds, 4f, 4f, newTabPaint)

        val cx = newTabButtonBounds.centerX()
        val cy = newTabButtonBounds.centerY()
        val plusSize = NEW_TAB_BUTTON_WIDTH * 0.25f
        canvas.drawLine(cx - plusSize, cy, cx + plusSize, cy, newTabIconPaint)
        canvas.drawLine(cx, cy - plusSize, cx, cy + plusSize, newTabIconPaint)
    }

    private fun drawBackIcon(canvas: Canvas, bounds: RectF, paint: Paint) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = NAV_BUTTON_SIZE * 0.3f

        // Draw left arrow
        canvas.drawLine(cx + size * 0.5f, cy - size, cx - size * 0.5f, cy, paint)
        canvas.drawLine(cx - size * 0.5f, cy, cx + size * 0.5f, cy + size, paint)
    }

    private fun drawGlassesIcon(canvas: Canvas, bounds: RectF, paint: Paint) {
        // Simple "glasses" icon drawn with strokes (vector-like)
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val w = NAV_BUTTON_SIZE * 0.55f
        val h = NAV_BUTTON_SIZE * 0.22f
        val gap = NAV_BUTTON_SIZE * 0.10f

        val left = RectF(cx - w / 2, cy - h / 2, cx - gap / 2, cy + h / 2)
        val right = RectF(cx + gap / 2, cy - h / 2, cx + w / 2, cy + h / 2)

        canvas.drawRoundRect(left, h / 2, h / 2, paint)
        canvas.drawRoundRect(right, h / 2, h / 2, paint)

        // Bridge
        canvas.drawLine(cx - gap / 2, cy, cx + gap / 2, cy, paint)

        // Small "arms"
        canvas.drawLine(left.left, cy, left.left - NAV_BUTTON_SIZE * 0.10f, cy - NAV_BUTTON_SIZE * 0.05f, paint)
        canvas.drawLine(right.right, cy, right.right + NAV_BUTTON_SIZE * 0.10f, cy - NAV_BUTTON_SIZE * 0.05f, paint)

        // (If you want true SVG rendering later, you can replace this with a Path-based SVG parser.)
    }

    private fun drawDesktopIcon(canvas: Canvas, bounds: RectF, paint: Paint) {
        // Simple "monitor" icon drawn with strokes
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        val screenW = NAV_BUTTON_SIZE * 0.60f
        val screenH = NAV_BUTTON_SIZE * 0.38f

        val screen = RectF(
            cx - screenW / 2,
            cy - screenH / 2,
            cx + screenW / 2,
            cy + screenH / 2
        )

        // Screen
        canvas.drawRoundRect(screen, 4f, 4f, paint)

        // Stand
        val standTopY = screen.bottom + NAV_BUTTON_SIZE * 0.04f
        canvas.drawLine(cx, screen.bottom, cx, standTopY, paint)

        val baseW = NAV_BUTTON_SIZE * 0.25f
        canvas.drawLine(cx - baseW / 2, standTopY, cx + baseW / 2, standTopY, paint)
    }


    private fun drawForwardIcon(canvas: Canvas, bounds: RectF, paint: Paint) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = NAV_BUTTON_SIZE * 0.3f

        // Draw right arrow
        canvas.drawLine(cx - size * 0.5f, cy - size, cx + size * 0.5f, cy, paint)
        canvas.drawLine(cx + size * 0.5f, cy, cx - size * 0.5f, cy + size, paint)
    }

    private fun drawRefreshIcon(canvas: Canvas, bounds: RectF, paint: Paint) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val radius = NAV_BUTTON_SIZE * 0.3f

        // Draw circular arrow (simplified)
        val tempBounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(tempBounds, -90f, 270f, false, paint)

        // Arrow head
        val arrowSize = radius * 0.4f
        canvas.drawLine(cx, cy - radius, cx + arrowSize, cy - radius + arrowSize, paint)
        canvas.drawLine(cx, cy - radius, cx - arrowSize, cy - radius + arrowSize, paint)
    }

    private fun drawHomeIcon(canvas: Canvas, bounds: RectF, paint: Paint) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = NAV_BUTTON_SIZE * 0.35f

        // Draw house shape
        // Roof (triangle)
        canvas.drawLine(cx - size, cy, cx, cy - size * 0.8f, paint)
        canvas.drawLine(cx, cy - size * 0.8f, cx + size, cy, paint)

        // Walls
        canvas.drawLine(cx - size * 0.8f, cy, cx - size * 0.8f, cy + size * 0.6f, paint)
        canvas.drawLine(cx + size * 0.8f, cy, cx + size * 0.8f, cy + size * 0.6f, paint)

        // Base
        canvas.drawLine(cx - size * 0.8f, cy + size * 0.6f, cx + size * 0.8f, cy + size * 0.6f, paint)
    }
}