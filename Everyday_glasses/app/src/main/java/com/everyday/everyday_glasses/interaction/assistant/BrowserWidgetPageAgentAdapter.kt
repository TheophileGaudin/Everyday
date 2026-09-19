package com.everyday.everyday_glasses.interaction.assistant

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentActionCode
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentActionResult
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentActionType
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentBrowserAdapter
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentFullscreenMode
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentPageSnapshot
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentPolicy
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentTargetBinding
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentTargetUnavailableException
import com.everyday.everyday_glasses.ui.widgets.BrowserWidget
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Small UI-thread bridge so the Page Agent worker never directly touches WebView state. */
interface PageAgentUiDispatcher {
    fun <T> call(action: () -> T): T
}

class AndroidPageAgentUiDispatcher(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val timeoutMs: Long = 5_000L
) : PageAgentUiDispatcher {
    override fun <T> call(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val future = CompletableFuture<T>()
        handler.post {
            try {
                future.complete(action())
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        return future.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

/**
 * Adapter for one BrowserWidget and the exact active WebView/tab captured at bind time.
 * It deliberately retains the object reference and never performs a registry lookup.
 */
class BrowserWidgetPageAgentAdapter private constructor(
    private val widget: BrowserWidget,
    private val boundTabId: Int,
    private val boundWebView: WebView,
    private val ui: PageAgentUiDispatcher,
    override val binding: PageAgentTargetBinding
) : PageAgentBrowserAdapter {

    companion object {
        fun bind(
            widgetId: String,
            widget: BrowserWidget,
            ui: PageAgentUiDispatcher = AndroidPageAgentUiDispatcher()
        ): BrowserWidgetPageAgentAdapter? {
            val tabId = ui.call { widget.pageAgentActiveTabId() } ?: return null
            val webView = ui.call { widget.getWebView() } ?: return null
            return BrowserWidgetPageAgentAdapter(
                widget = widget,
                boundTabId = tabId,
                boundWebView = webView,
                ui = ui,
                binding = PageAgentTargetBinding(
                    widgetId = widgetId,
                    tabId = tabId.toString(),
                    webViewId = "webview-${System.identityHashCode(webView)}"
                )
            )
        }
    }

    override fun isAvailable(): Boolean = runCatching {
        ui.call {
            widget.pageAgentIsBoundTo(
                tabId = boundTabId,
                webView = boundWebView
            )
        }
    }.getOrDefault(false)

    override fun snapshot(): Result<PageAgentPageSnapshot> = runCatching {
        ui.call {
            ensureBound()
            widget.capturePageAgentSnapshot()
                ?: throw PageAgentTargetUnavailableException("The bound browser WebView cannot be captured")
        }
    }

    override fun tap(viewportX: Float, viewportY: Float): PageAgentActionResult = runAction(
        PageAgentActionType.TAP) {
        if (!widget.dispatchPageAgentTap(viewportX, viewportY)) {
            PageAgentActionResult(
                PageAgentActionType.TAP,
                PageAgentActionCode.FAILED,
                "Tap could not be delivered"
            )
        } else {
            PageAgentActionResult(
                PageAgentActionType.TAP,
                PageAgentActionCode.SUCCESS,
                "Tapped the page"
            )
        }
    }

    override fun replaceFocusedText(text: String): PageAgentActionResult = runAction(
        PageAgentActionType.TYPE) {
        if (!widget.replacePageAgentFocusedText(text)) {
            PageAgentActionResult(
                PageAgentActionType.TYPE,
                PageAgentActionCode.FAILED,
                "No focused editable page element"
            )
        } else {
            PageAgentActionResult(
                PageAgentActionType.TYPE,
                PageAgentActionCode.SUCCESS,
                "Replaced focused page text"
            )
        }
    }

    override fun pressEnter(): PageAgentActionResult = runAction(PageAgentActionType.TYPE) {
        if (!widget.submitPageAgentFocusedElement()) {
            PageAgentActionResult(
                PageAgentActionType.TYPE,
                PageAgentActionCode.FAILED,
                "Focused field could not be submitted"
            )
        } else {
            PageAgentActionResult(
                PageAgentActionType.TYPE,
                PageAgentActionCode.SUCCESS,
                "Submitted the focused field"
            )
        }
    }

    override fun scroll(deltaX: Float, deltaY: Float): PageAgentActionResult = runAction(
        PageAgentActionType.SCROLL) {
        if (!widget.scrollPageAgent(deltaX, deltaY)) {
            PageAgentActionResult(
                PageAgentActionType.SCROLL,
                PageAgentActionCode.FAILED,
                "Page could not be scrolled"
            )
        } else {
            PageAgentActionResult(
                PageAgentActionType.SCROLL,
                PageAgentActionCode.SUCCESS,
                "Scrolled the page"
            )
        }
    }

    override fun goBack(): PageAgentActionResult = runAction(PageAgentActionType.BACK) {
        if (!widget.goBackForPageAgent()) {
            PageAgentActionResult(
                PageAgentActionType.BACK,
                PageAgentActionCode.FAILED,
                "No browser history entry is available"
            )
        } else {
            PageAgentActionResult(
                PageAgentActionType.BACK,
                PageAgentActionCode.SUCCESS,
                "Went back in the browser"
            )
        }
    }

    override fun navigate(url: String): PageAgentActionResult = runAction(PageAgentActionType.NAVIGATE) {
        if (!widget.navigateForPageAgent(url)) {
            PageAgentActionResult(
                PageAgentActionType.NAVIGATE,
                PageAgentActionCode.FAILED,
                "Navigation could not be started"
            )
        } else {
            PageAgentActionResult(
                PageAgentActionType.NAVIGATE,
                PageAgentActionCode.SUCCESS,
                "Opened $url"
            )
        }
    }

    override fun waitForPageChange(timeoutMs: Long, isCancelled: () -> Boolean): PageAgentActionResult {
        val boundedTimeout = timeoutMs.coerceIn(0L, PageAgentPolicy.MAX_WAIT_MS)
        val startedAt = System.nanoTime()
        val initialRevision = runCatching {
            ui.call {
                ensureBound()
                widget.pageAgentPageRevision()
            }
        }.getOrElse { failure ->
            return if (failure is PageAgentTargetUnavailableException) {
                PageAgentActionResult(
                    PageAgentActionType.WAIT,
                    PageAgentActionCode.TARGET_UNAVAILABLE,
                    failure.message ?: "Target unavailable"
                )
            } else {
                PageAgentActionResult(
                    PageAgentActionType.WAIT,
                    PageAgentActionCode.FAILED,
                    failure.message ?: "Could not wait for page changes"
                )
            }
        }

        while (true) {
            if (isCancelled()) {
                return PageAgentActionResult(
                    PageAgentActionType.WAIT,
                    PageAgentActionCode.CANCELLED,
                    "Wait cancelled"
                )
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            if (elapsedMs >= boundedTimeout) {
                return PageAgentActionResult(
                    PageAgentActionType.WAIT,
                    PageAgentActionCode.SUCCESS,
                    "Waited ${boundedTimeout}ms for page changes"
                )
            }
            val changed = runCatching {
                ui.call {
                    ensureBound()
                    widget.pageAgentPageRevision() != initialRevision
                }
            }.getOrElse { failure ->
                return if (failure is PageAgentTargetUnavailableException) {
                    PageAgentActionResult(
                        PageAgentActionType.WAIT,
                        PageAgentActionCode.TARGET_UNAVAILABLE,
                        failure.message ?: "Target unavailable"
                    )
                } else {
                    PageAgentActionResult(
                        PageAgentActionType.WAIT,
                        PageAgentActionCode.FAILED,
                        failure.message ?: "Could not wait for page changes"
                    )
                }
            }
            if (changed) {
                return PageAgentActionResult(
                    PageAgentActionType.WAIT,
                    PageAgentActionCode.SUCCESS,
                    "Page changed"
                )
            }
            try {
                Thread.sleep(minOf(50L, boundedTimeout - elapsedMs))
            } catch (_: InterruptedException) {
                return PageAgentActionResult(
                    PageAgentActionType.WAIT,
                    PageAgentActionCode.CANCELLED,
                    "Wait interrupted"
                )
            }
        }
    }

    override fun requestFullscreen(mode: PageAgentFullscreenMode): PageAgentActionResult {
        val started = runAction(PageAgentActionType.FULLSCREEN) {
            if (!widget.requestPageAgentFullscreen(mode)) {
                PageAgentActionResult(
                    PageAgentActionType.FULLSCREEN,
                    PageAgentActionCode.FAILED,
                    "Fullscreen request could not be started"
                )
            } else {
                PageAgentActionResult(
                    PageAgentActionType.FULLSCREEN,
                    PageAgentActionCode.SUCCESS,
                    "Fullscreen request started"
                )
            }
        }
        if (!started.success || mode != PageAgentFullscreenMode.VIDEO) return started

        val startedAt = System.nanoTime()
        while ((System.nanoTime() - startedAt) / 1_000_000L < PageAgentPolicy.MAX_WAIT_MS) {
            val state = runCatching {
                ui.call {
                    ensureBound()
                    widget.isInVideoFullscreen()
                }
            }.getOrElse { failure ->
                return if (failure is PageAgentTargetUnavailableException) {
                    PageAgentActionResult(
                        PageAgentActionType.FULLSCREEN,
                        PageAgentActionCode.TARGET_UNAVAILABLE,
                        failure.message ?: "Target unavailable"
                    )
                } else {
                    PageAgentActionResult(
                        PageAgentActionType.FULLSCREEN,
                        PageAgentActionCode.FAILED,
                        failure.message ?: "Could not observe video fullscreen state"
                    )
                }
            }
            if (state) {
                return PageAgentActionResult(
                    PageAgentActionType.FULLSCREEN,
                    PageAgentActionCode.SUCCESS,
                    "Video entered fullscreen playback"
                )
            }
            try {
                Thread.sleep(75L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return PageAgentActionResult(
                    PageAgentActionType.FULLSCREEN,
                    PageAgentActionCode.CANCELLED,
                    "Fullscreen wait interrupted"
                )
            }
        }
        return PageAgentActionResult(
            PageAgentActionType.FULLSCREEN,
            PageAgentActionCode.FAILED,
            "Video did not enter fullscreen within ${PageAgentPolicy.MAX_WAIT_MS}ms"
        )
    }

    private fun runAction(actionType: PageAgentActionType, action: () -> PageAgentActionResult): PageAgentActionResult {
        return try {
            ui.call {
                ensureBound()
                action()
            }
        } catch (failure: PageAgentTargetUnavailableException) {
            PageAgentActionResult(
                action = actionType,
                code = PageAgentActionCode.TARGET_UNAVAILABLE,
                message = failure.message ?: "The bound browser page is unavailable"
            )
        } catch (failure: Throwable) {
            PageAgentActionResult(
                action = actionType,
                code = PageAgentActionCode.FAILED,
                message = failure.message ?: "Browser action failed"
            )
        }
    }

    private fun ensureBound() {
        if (!widget.pageAgentIsBoundTo(boundTabId, boundWebView)) {
            throw PageAgentTargetUnavailableException("The bound browser widget, tab, or WebView is unavailable")
        }
    }
}
