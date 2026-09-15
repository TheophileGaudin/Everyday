package com.everyday.everyday_glasses.agent_tools

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Stable Page Agent policy. Keep this separate from the main assistant model policy. */
object PageAgentPolicy {
    /**
     * Google-maintained alias for the latest Gemini Flash model variation.
     *
     * The Computer Use documentation currently recommends the latest Flash
     * family for this workload, while Google's model aliases are hot-swapped
     * as that variation is updated. Keeping the alias here avoids pinning the
     * Page Agent to a numbered release such as gemini-3.6-flash.
     */
    const val DEFAULT_MODEL_ID = "gemini-flash-latest"
    const val MAX_ACTIONS = 20
    const val MAX_RUNTIME_MS = 90_000L
    const val MAX_CONSECUTIVE_INVALID_ACTIONS = 3
    const val MAX_WAIT_MS = 10_000L
    const val APPROVAL_TIMEOUT_MS = 30_000L
}

enum class PageAgentTerminalState {
    SUCCESS,
    PARTIAL,
    FAILURE,
    TIMEOUT,
    CANCELLED,
    TARGET_UNAVAILABLE,
    CONFIRMATION_REQUIRED,
    PROMPT_INJECTION_WARNING,
    HANDOFF_REQUESTED
}

enum class PageAgentActionType {
    TAP,
    TYPE,
    SCROLL,
    BACK,
    NAVIGATE,
    WAIT,
    FULLSCREEN,
    FINISH,
    HANDOFF
}

enum class PageAgentFullscreenMode { PAGE, VIDEO }

enum class PageAgentActionCode {
    SUCCESS,
    INVALID_ACTION,
    TARGET_UNAVAILABLE,
    FAILED,
    CANCELLED
}

enum class PageAgentUpdateState {
    RUNNING,
    CONFIRMATION_REQUIRED,
    FINISHED
}

/** Identifies the exact browser widget, active tab, and WebView captured for a run. */
data class PageAgentTargetBinding(
    val widgetId: String,
    val tabId: String,
    val webViewId: String
) {
    init {
        require(widgetId.isNotBlank()) { "Page Agent widget ID must not be blank" }
        require(tabId.isNotBlank()) { "Page Agent tab ID must not be blank" }
        require(webViewId.isNotBlank()) { "Page Agent WebView ID must not be blank" }
    }
}

data class PageAgentScreenshot(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val mimeType: String = "image/png"
) {
    init {
        require(width > 0) { "Page Agent screenshot width must be positive" }
        require(height > 0) { "Page Agent screenshot height must be positive" }
        require(mimeType.isNotBlank()) { "Page Agent screenshot MIME type must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is PageAgentScreenshot &&
        bytes.contentEquals(other.bytes) &&
        width == other.width &&
        height == other.height &&
        mimeType == other.mimeType

    override fun hashCode(): Int = 31 * (31 * (31 * bytes.contentHashCode() + width) + height) + mimeType.hashCode()
}

/** The original voice recording, kept byte-for-byte when a voice request is routed to a page. */
data class PageAgentAudio(
    val bytes: ByteArray,
    val mimeType: String = "audio/aac"
) {
    init {
        require(bytes.isNotEmpty()) { "Page Agent audio data must not be empty" }
        require(mimeType.isNotBlank()) { "Page Agent audio MIME type must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is PageAgentAudio &&
        bytes.contentEquals(other.bytes) && mimeType == other.mimeType

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

typealias PageAgentAudioInput = PageAgentAudio

data class PageAgentPageSnapshot(
    val screenshot: PageAgentScreenshot,
    val url: String,
    val title: String,
    val viewportWidth: Int,
    val viewportHeight: Int
) {
    init {
        require(viewportWidth > 0) { "Page Agent viewport width must be positive" }
        require(viewportHeight > 0) { "Page Agent viewport height must be positive" }
    }
}

data class PageAgentActionResult(
    val action: PageAgentActionType?,
    val code: PageAgentActionCode,
    val message: String
) {
    val success: Boolean get() = code == PageAgentActionCode.SUCCESS

    fun toPromptText(): String = buildString {
        append("action=")
        append(action?.name?.lowercase() ?: "none")
        append("; code=")
        append(code.name.lowercase())
        append("; message=")
        append(message.take(500))
    }
}

data class PageAgentComputerUseState(
    val actionCount: Int,
    val maxActions: Int,
    val elapsedMs: Long,
    val remainingMs: Long,
    val consecutiveInvalidActions: Int
)

data class PageAgentTurnRequest(
    val originalTask: String,
    val screenshot: PageAgentScreenshot,
    val url: String,
    val title: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val priorActionResult: PageAgentActionResult?,
    val computerUseState: PageAgentComputerUseState,
    val audio: PageAgentAudio? = null,
    val providerState: String? = null
)

data class PageAgentConfirmationRequest(
    val action: PageAgentActionType,
    val description: String,
    val riskDescription: String
)

data class PageAgentAction(
    val type: PageAgentActionType,
    val x: Float? = null,
    val y: Float? = null,
    val text: String? = null,
    val pressEnter: Boolean = false,
    val deltaX: Float? = null,
    val deltaY: Float? = null,
    val url: String? = null,
    val waitMs: Long? = null,
    val fullscreenMode: PageAgentFullscreenMode? = null,
    val resultState: PageAgentTerminalState? = null,
    val message: String? = null,
    val reason: String? = null,
    val requiresConfirmation: Boolean = false,
    val confirmation: PageAgentConfirmationRequest? = null
)

data class PageAgentModelTurn(
    val action: PageAgentAction,
    val promptInjectionWarning: String? = null,
    /** Lets an audio-first Page Agent make the video completion contract explicit. */
    val requiresVideoPlaybackCompletion: Boolean? = null,
    val providerState: String? = null
)

data class PageAgentResult(
    val state: PageAgentTerminalState,
    val message: String,
    val target: PageAgentTargetBinding,
    val actionCount: Int,
    val actionResults: List<PageAgentActionResult> = emptyList(),
    val promptInjectionWarning: String? = null,
    val confirmation: PageAgentConfirmationRequest? = null
)

data class PageAgentUpdate(
    val runId: String,
    val state: PageAgentUpdateState,
    val terminalState: PageAgentTerminalState? = null,
    val approval: PageAgentConfirmationRequest? = null,
    val result: PageAgentResult? = null
)

/** Provider boundary used by the loop; unit tests can supply deterministic model turns. */
interface PageAgentProvider {
    fun nextTurn(
        apiKey: String,
        modelId: String,
        request: PageAgentTurnRequest
    ): Result<PageAgentModelTurn>
}

/** Browser boundary used by the loop; implementations must remain bound to one target. */
interface PageAgentBrowserAdapter {
    val binding: PageAgentTargetBinding

    fun isAvailable(): Boolean
    fun snapshot(): Result<PageAgentPageSnapshot>
    fun tap(viewportX: Float, viewportY: Float): PageAgentActionResult
    fun replaceFocusedText(text: String): PageAgentActionResult
    fun pressEnter(): PageAgentActionResult = PageAgentActionResult(
        PageAgentActionType.TYPE,
        PageAgentActionCode.FAILED,
        "The browser adapter cannot submit the focused field"
    )
    fun scroll(deltaX: Float, deltaY: Float): PageAgentActionResult
    fun goBack(): PageAgentActionResult
    fun navigate(url: String): PageAgentActionResult
    fun waitForPageChange(timeoutMs: Long, isCancelled: () -> Boolean): PageAgentActionResult
    fun requestFullscreen(mode: PageAgentFullscreenMode): PageAgentActionResult
}

class PageAgentTargetUnavailableException(message: String) : IllegalStateException(message)

interface PageAgentClock {
    fun nanoTime(): Long
}

object SystemPageAgentClock : PageAgentClock {
    override fun nanoTime(): Long = System.nanoTime()
}

/** Maps model screenshot coordinates into the active WebView viewport. */
object PageAgentCoordinateMapper {
    data class ViewportPoint(val x: Float, val y: Float)

    fun mapToViewport(
        screenshotX: Float,
        screenshotY: Float,
        screenshotWidth: Int,
        screenshotHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int
    ): Result<ViewportPoint> = runCatching {
        require(screenshotWidth > 0 && screenshotHeight > 0) { "Screenshot dimensions are invalid" }
        require(viewportWidth > 0 && viewportHeight > 0) { "Viewport dimensions are invalid" }
        require(screenshotX.isFinite() && screenshotY.isFinite()) { "Tap coordinates must be finite" }
        require(screenshotX >= 0f && screenshotX < screenshotWidth) {
            "Tap x=$screenshotX is outside screenshot width $screenshotWidth"
        }
        require(screenshotY >= 0f && screenshotY < screenshotHeight) {
            "Tap y=$screenshotY is outside screenshot height $screenshotHeight"
        }

        val mappedX = screenshotX * viewportWidth.toFloat() / screenshotWidth.toFloat()
        val mappedY = screenshotY * viewportHeight.toFloat() / screenshotHeight.toFloat()
        require(mappedX >= 0f && mappedX < viewportWidth && mappedY >= 0f && mappedY < viewportHeight) {
            "Mapped tap is outside viewport"
        }
        ViewportPoint(mappedX, mappedY)
    }

    /** Converts a WebView-local point to the containing widget's screen coordinates. */
    fun addWidgetChromeOffset(
        viewportPoint: ViewportPoint,
        chromeLeft: Float,
        chromeTop: Float
    ): ViewportPoint = ViewportPoint(
        viewportPoint.x + chromeLeft,
        viewportPoint.y + chromeTop
    )
}

data class PageAgentRunRequest(
    val task: String,
    val browser: PageAgentBrowserAdapter,
    val audio: PageAgentAudio? = null,
    val onUpdate: (PageAgentUpdate) -> Unit = {},
    val requestApproval: PageAgentApprovalRequester? = null
) {
    /** Source-compatible constructor for callers that supplied the update callback third. */
    constructor(
        task: String,
        browser: PageAgentBrowserAdapter,
        onUpdate: (PageAgentUpdate) -> Unit
    ) : this(task, browser, null, onUpdate)
}

typealias PageAgentApprovalRequester = (
    runId: String,
    request: PageAgentConfirmationRequest,
    respond: (Boolean) -> Unit
) -> Unit

internal class PageAgentRunControl(val runId: String) {
    val cancelled = AtomicBoolean(false)
    private val approvalMonitor = Object()
    @Volatile var workerThread: Thread? = null
    @Volatile private var approvalPending = false
    @Volatile private var approvalDecision: Boolean? = null

    fun cancel() {
        cancelled.set(true)
        synchronized(approvalMonitor) { approvalMonitor.notifyAll() }
        workerThread?.interrupt()
    }

    fun decide(approved: Boolean): Boolean = synchronized(approvalMonitor) {
        if (!approvalPending || approvalDecision != null) return@synchronized false
        approvalDecision = approved
        approvalMonitor.notifyAll()
        true
    }

    fun awaitApproval(
        request: PageAgentConfirmationRequest,
        emit: (PageAgentUpdate) -> Unit,
        approvalDecider: ((PageAgentConfirmationRequest) -> Boolean)?,
        approvalRequester: PageAgentApprovalRequester?,
        timeoutMs: Long
    ): Boolean {
        if (approvalDecider != null) return approvalDecider(request)

        synchronized(approvalMonitor) {
            approvalPending = true
            approvalDecision = null
        }
        runCatching {
            emit(
                PageAgentUpdate(
                    runId = runId,
                    state = PageAgentUpdateState.CONFIRMATION_REQUIRED,
                    terminalState = PageAgentTerminalState.CONFIRMATION_REQUIRED,
                    approval = request
                )
            )
        }

        if (approvalRequester != null) {
            runCatching {
                approvalRequester(runId, request, ::decide)
            }.onFailure {
                decide(false)
            }
        }

        val deadlineNanos = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        val decision = synchronized(approvalMonitor) {
            while (!cancelled.get() && approvalDecision == null && System.nanoTime() < deadlineNanos) {
                try {
                    val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                    approvalMonitor.wait(minOf(250L, remainingMs))
                } catch (_: InterruptedException) {
                    if (cancelled.get()) break
                }
            }
            val resolved = approvalDecision
            approvalPending = false
            approvalDecision = null
            resolved
        }
        return decision == true
    }
}

class PageAgentRunHandle internal constructor(
    private val control: PageAgentRunControl,
    val result: CompletableFuture<PageAgentResult>
) {
    val runId: String get() = control.runId

    fun cancel() = control.cancel()

    fun approve(): Boolean = control.decide(true)

    fun reject(): Boolean = control.decide(false)
}

/** Owns the stateless screenshot/action loop for one bound browser target. */
class PageAgentCoordinator(
    private val apiKeyProvider: () -> String?,
    private val provider: PageAgentProvider,
    private val clock: PageAgentClock = SystemPageAgentClock,
    private val modelId: String = PageAgentPolicy.DEFAULT_MODEL_ID,
    private val maxActions: Int = PageAgentPolicy.MAX_ACTIONS,
    private val maxRuntimeMs: Long = PageAgentPolicy.MAX_RUNTIME_MS,
    private val maxConsecutiveInvalidActions: Int = PageAgentPolicy.MAX_CONSECUTIVE_INVALID_ACTIONS,
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "PageAgent").apply { isDaemon = true }
    }
) : AutoCloseable {
    companion object {
        private const val TAG = "PageAgent"
        /** Uses the same encrypted Gemini key source as VoiceAssistantCoordinator. */
        fun usingPreferences(
            preferences: AssistantPreferencesStore,
            provider: PageAgentProvider,
            clock: PageAgentClock = SystemPageAgentClock,
            modelId: String = PageAgentPolicy.DEFAULT_MODEL_ID
        ): PageAgentCoordinator = PageAgentCoordinator(
            apiKeyProvider = { preferences.load()?.apiKey },
            provider = provider,
            clock = clock,
            modelId = modelId
        )
    }

    init {
        require(maxActions > 0) { "Page Agent action limit must be positive" }
        require(maxRuntimeMs > 0L) { "Page Agent runtime limit must be positive" }
        require(maxConsecutiveInvalidActions > 0) { "Page Agent invalid-action limit must be positive" }
        require(modelId.isNotBlank()) { "Page Agent model ID must not be blank" }
    }

    fun start(request: PageAgentRunRequest): PageAgentRunHandle {
        val control = PageAgentRunControl(UUID.randomUUID().toString())
        val future = CompletableFuture<PageAgentResult>()
        val handle = PageAgentRunHandle(control, future)
        executor.submit {
            control.workerThread = Thread.currentThread()
            val result = executeSafely(
                control = control,
                task = request.task,
                browser = request.browser,
                emit = request.onUpdate,
                approvalDecider = null,
                approvalRequester = request.requestApproval,
                audio = request.audio
            )
            if (!future.isDone) future.complete(result)
        }
        return handle
    }

    /** Synchronous entry point useful for deterministic tests and non-UI callers. */
    fun run(
        task: String,
        browser: PageAgentBrowserAdapter,
        approvalDecider: ((PageAgentConfirmationRequest) -> Boolean)? = null,
        onUpdate: (PageAgentUpdate) -> Unit = {},
        audio: PageAgentAudio? = null
    ): PageAgentResult {
        val control = PageAgentRunControl(UUID.randomUUID().toString())
        return executeSafely(
            control = control,
            task = task,
            browser = browser,
            emit = onUpdate,
            approvalDecider = approvalDecider ?: { false },
            approvalRequester = null,
            audio = audio
        )
    }

    private fun executeSafely(
        control: PageAgentRunControl,
        task: String,
        browser: PageAgentBrowserAdapter,
        emit: (PageAgentUpdate) -> Unit,
        approvalDecider: ((PageAgentConfirmationRequest) -> Boolean)?,
        approvalRequester: PageAgentApprovalRequester?,
        audio: PageAgentAudio?
    ): PageAgentResult = runCatching {
        execute(control, task, browser, emit, approvalDecider, approvalRequester, audio)
    }.getOrElse { failure ->
        val state = if (control.cancelled.get()) {
            PageAgentTerminalState.CANCELLED
        } else {
            PageAgentTerminalState.FAILURE
        }
        finish(
            control,
            emit,
            result(
                state,
                failure.message ?: "Page Agent run failed",
                browser,
                0,
                emptyList()
            )
        )
    }

    override fun close() {
        executor.shutdownNow()
    }

    fun release() = close()

    private fun execute(
        control: PageAgentRunControl,
        task: String,
        browser: PageAgentBrowserAdapter,
        emit: (PageAgentUpdate) -> Unit,
        approvalDecider: ((PageAgentConfirmationRequest) -> Boolean)?,
        approvalRequester: PageAgentApprovalRequester?,
        audio: PageAgentAudio?
    ): PageAgentResult {
        val normalizedTask = task.trim()
        if (normalizedTask.isBlank() && audio == null) {
            return finish(
                control,
                emit,
                PageAgentResult(
                    PageAgentTerminalState.FAILURE,
                    "Page task is empty",
                    browser.binding,
                    0
                )
            )
        }

        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            return finish(
                control,
                emit,
                PageAgentResult(
                    PageAgentTerminalState.FAILURE,
                    "Gemini is not configured",
                    browser.binding,
                    0
                )
            )
        }

        val startedAt = clock.nanoTime()
        var actionCount = 0
        var invalidActions = 0
        var priorActionResult: PageAgentActionResult? = null
        val actionResults = mutableListOf<PageAgentActionResult>()
        var requiresVideoCompletion = BrowserTaskPolicy.requiresVideoPlaybackCompletion(normalizedTask)
        var videoFullscreenAchieved = false
        var providerState: String? = null
        runCatching { emit(PageAgentUpdate(control.runId, PageAgentUpdateState.RUNNING)) }

        while (true) {
            if (control.cancelled.get() || Thread.currentThread().isInterrupted) {
                return finish(control, emit, result(
                    PageAgentTerminalState.CANCELLED,
                    "Page Agent run cancelled",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            val elapsed = elapsedMs(startedAt)
            if (elapsed >= maxRuntimeMs) {
                return finish(control, emit, result(
                    PageAgentTerminalState.TIMEOUT,
                    "Page Agent time limit reached",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            if (!browser.isAvailable()) {
                return finish(control, emit, result(
                    PageAgentTerminalState.TARGET_UNAVAILABLE,
                    "The bound browser page is no longer available",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            if (actionCount >= maxActions) {
                return finish(control, emit, result(
                    PageAgentTerminalState.TIMEOUT,
                    "Page Agent action limit reached ($maxActions)",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            val snapshot = runCatching { browser.snapshot() }
                .getOrElse { failure -> Result.failure<PageAgentPageSnapshot>(failure) }
                .getOrElse { failure ->
                val state = if (failure is PageAgentTargetUnavailableException) {
                    PageAgentTerminalState.TARGET_UNAVAILABLE
                } else {
                    PageAgentTerminalState.FAILURE
                }
                return finish(control, emit, result(
                    state,
                    failure.message ?: "Could not capture the bound browser page",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            val request = PageAgentTurnRequest(
                originalTask = normalizedTask,
                screenshot = snapshot.screenshot,
                url = snapshot.url,
                title = snapshot.title,
                viewportWidth = snapshot.viewportWidth,
                viewportHeight = snapshot.viewportHeight,
                priorActionResult = priorActionResult,
                computerUseState = PageAgentComputerUseState(
                    actionCount = actionCount,
                    maxActions = maxActions,
                    elapsedMs = elapsed,
                    remainingMs = (maxRuntimeMs - elapsed).coerceAtLeast(0L),
                    consecutiveInvalidActions = invalidActions
                ),
                audio = audio,
                providerState = providerState
            )

            val turn = runCatching { provider.nextTurn(apiKey, modelId, request) }
                .getOrElse { failure -> Result.failure<PageAgentModelTurn>(failure) }
                .getOrElse { failure ->
                if (control.cancelled.get()) {
                    return finish(control, emit, result(
                        PageAgentTerminalState.CANCELLED,
                        "Page Agent run cancelled",
                        browser,
                        actionCount,
                        actionResults
                    ))
                }
                return finish(control, emit, result(
                    PageAgentTerminalState.FAILURE,
                    failure.message ?: "Gemini Page Agent request failed",
                    browser,
                    actionCount,
                    actionResults
                ))
            }
            if (turn.requiresVideoPlaybackCompletion == true) {
                requiresVideoCompletion = true
            }
            providerState = turn.providerState ?: providerState
            AssistantDiagnostics.debug(
                TAG,
                "run=${control.runId} turn=${actionCount + 1} action=${turn.action.type} " +
                    "url=${snapshot.url.take(300)}"
            )

            if (!browser.isAvailable()) {
                return finish(control, emit, result(
                    PageAgentTerminalState.TARGET_UNAVAILABLE,
                    "The bound browser page is no longer available",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            if (elapsedMs(startedAt) >= maxRuntimeMs) {
                return finish(control, emit, result(
                    PageAgentTerminalState.TIMEOUT,
                    "Page Agent time limit reached",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            turn.promptInjectionWarning?.trim()?.takeIf { it.isNotBlank() }?.let { warning ->
                return finish(control, emit, result(
                    PageAgentTerminalState.PROMPT_INJECTION_WARNING,
                    warning,
                    browser,
                    actionCount,
                    actionResults,
                    promptInjectionWarning = warning
                ))
            }

            actionCount++
            val action = turn.action
            if (action.requiresConfirmation) {
                val confirmation = action.confirmation ?: defaultConfirmation(action)
                val approved = control.awaitApproval(
                    request = confirmation,
                    emit = emit,
                    approvalDecider = approvalDecider,
                    approvalRequester = approvalRequester,
                    timeoutMs = minOf(
                        PageAgentPolicy.APPROVAL_TIMEOUT_MS,
                        (maxRuntimeMs - elapsedMs(startedAt)).coerceAtLeast(1L)
                    )
                )
                if (!approved) {
                    val state = if (control.cancelled.get()) {
                        PageAgentTerminalState.CANCELLED
                    } else {
                        PageAgentTerminalState.FAILURE
                    }
                    return finish(control, emit, result(
                        state,
                        if (state == PageAgentTerminalState.CANCELLED) {
                            "Page Agent run cancelled while awaiting approval"
                        } else {
                            "Pending page action was rejected"
                        },
                        browser,
                        actionCount,
                        actionResults,
                        confirmation = confirmation
                    ))
                }
                if (elapsedMs(startedAt) >= maxRuntimeMs) {
                    return finish(control, emit, result(
                        PageAgentTerminalState.TIMEOUT,
                        "Page Agent time limit reached while awaiting approval",
                        browser,
                        actionCount,
                        actionResults,
                        confirmation = confirmation
                    ))
                }
                if (!browser.isAvailable()) {
                    return finish(control, emit, result(
                        PageAgentTerminalState.TARGET_UNAVAILABLE,
                        "The bound browser page disappeared before approval",
                        browser,
                        actionCount,
                        actionResults
                    ))
                }
            }

            if (elapsedMs(startedAt) >= maxRuntimeMs) {
                return finish(control, emit, result(
                    PageAgentTerminalState.TIMEOUT,
                    "Page Agent time limit reached before the pending action",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            val execution = runCatching {
                executeAction(
                    action = action,
                    snapshot = snapshot,
                    browser = browser,
                    control = control,
                    remainingRuntimeMs = (maxRuntimeMs - elapsedMs(startedAt)).coerceAtLeast(0L)
                )
            }.getOrElse { failure ->
                PageAgentActionResult(
                    action = action.type,
                    code = PageAgentActionCode.FAILED,
                    message = failure.message ?: "Browser action failed"
                )
            }
            actionResults += execution
            priorActionResult = execution
            AssistantDiagnostics.debug(
                TAG,
                "run=${control.runId} action=${action.type} code=${execution.code} " +
                    "message=${execution.message.take(300)}"
            )

            if (execution.code == PageAgentActionCode.TARGET_UNAVAILABLE) {
                return finish(control, emit, result(
                    PageAgentTerminalState.TARGET_UNAVAILABLE,
                    execution.message,
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            if (execution.code == PageAgentActionCode.CANCELLED || control.cancelled.get()) {
                return finish(control, emit, result(
                    PageAgentTerminalState.CANCELLED,
                    execution.message,
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            if (elapsedMs(startedAt) >= maxRuntimeMs) {
                return finish(control, emit, result(
                    PageAgentTerminalState.TIMEOUT,
                    "Page Agent time limit reached",
                    browser,
                    actionCount,
                    actionResults
                ))
            }

            if (execution.code == PageAgentActionCode.INVALID_ACTION) {
                invalidActions++
                if (invalidActions >= maxConsecutiveInvalidActions) {
                    return finish(control, emit, result(
                        PageAgentTerminalState.FAILURE,
                        "Page Agent returned repeated invalid actions",
                        browser,
                        actionCount,
                        actionResults
                    ))
                }
                continue
            }

            if (execution.code == PageAgentActionCode.FAILED) {
                // A page-level failure is returned to Gemini so it can correct the action.
                invalidActions = 0
                continue
            }
            if (action.type == PageAgentActionType.FULLSCREEN &&
                action.fullscreenMode == PageAgentFullscreenMode.VIDEO
            ) {
                videoFullscreenAchieved = true
            }
            if (action.type != PageAgentActionType.FINISH) invalidActions = 0

            when (action.type) {
                PageAgentActionType.FINISH -> {
                    val terminal = when (action.resultState) {
                        PageAgentTerminalState.PARTIAL -> PageAgentTerminalState.PARTIAL
                        PageAgentTerminalState.FAILURE -> PageAgentTerminalState.FAILURE
                        else -> PageAgentTerminalState.SUCCESS
                    }
                    if (terminal == PageAgentTerminalState.SUCCESS &&
                        requiresVideoCompletion &&
                        !videoFullscreenAchieved
                    ) {
                        val policyResult = PageAgentActionResult(
                            action = PageAgentActionType.FINISH,
                            code = PageAgentActionCode.INVALID_ACTION,
                            message = "For an online video task, search alone is not completion; open, play, and request video fullscreen."
                        )
                        actionResults[actionResults.lastIndex] = policyResult
                        priorActionResult = policyResult
                        invalidActions++
                        if (invalidActions >= maxConsecutiveInvalidActions) {
                            return finish(control, emit, result(
                                PageAgentTerminalState.FAILURE,
                                "Page Agent stopped before completing online video playback",
                                browser,
                                actionCount,
                                actionResults
                            ))
                        }
                        continue
                    }
                    return finish(control, emit, result(
                        terminal,
                        action.message?.trim().takeUnless { it.isNullOrBlank() } ?: "Page task completed",
                        browser,
                        actionCount,
                        actionResults
                    ))
                }
                PageAgentActionType.HANDOFF -> {
                    return finish(control, emit, result(
                        PageAgentTerminalState.HANDOFF_REQUESTED,
                        action.reason?.trim().takeUnless { it.isNullOrBlank() }
                            ?: action.message?.trim().takeUnless { it.isNullOrBlank() }
                            ?: "Page Agent requested the main assistant",
                        browser,
                        actionCount,
                        actionResults
                    ))
                }
                else -> Unit
            }
        }
    }

    private fun executeAction(
        action: PageAgentAction,
        snapshot: PageAgentPageSnapshot,
        browser: PageAgentBrowserAdapter,
        control: PageAgentRunControl,
        remainingRuntimeMs: Long
    ): PageAgentActionResult {
        if (control.cancelled.get()) {
            return PageAgentActionResult(action.type, PageAgentActionCode.CANCELLED, "Page Agent run cancelled")
        }

        return when (action.type) {
            PageAgentActionType.TAP -> {
                val x = action.x
                val y = action.y
                if (x == null || y == null) {
                    invalid(action, "Tap requires x and y screenshot coordinates")
                } else {
                    PageAgentCoordinateMapper.mapToViewport(
                        x,
                        y,
                        snapshot.screenshot.width,
                        snapshot.screenshot.height,
                        snapshot.viewportWidth,
                        snapshot.viewportHeight
                    ).fold(
                        onSuccess = { point -> browser.tap(point.x, point.y) },
                        onFailure = { failure -> invalid(action, failure.message ?: "Tap coordinates are invalid") }
                    )
                }
            }
            PageAgentActionType.TYPE -> {
                val text = action.text
                if (text == null) invalid(action, "Type requires replacement text")
                else {
                    val focusResult = if (action.x != null || action.y != null) {
                        val x = action.x
                        val y = action.y
                        if (x == null || y == null) {
                            invalid(action, "Coordinate-aware type requires both x and y")
                        } else {
                            PageAgentCoordinateMapper.mapToViewport(
                                x,
                                y,
                                snapshot.screenshot.width,
                                snapshot.screenshot.height,
                                snapshot.viewportWidth,
                                snapshot.viewportHeight
                            ).fold(
                                onSuccess = { point -> browser.tap(point.x, point.y) },
                                onFailure = { failure ->
                                    invalid(action, failure.message ?: "Type coordinates are invalid")
                                }
                            )
                        }
                    } else null
                    if (focusResult != null && !focusResult.success) {
                        PageAgentActionResult(
                            PageAgentActionType.TYPE,
                            focusResult.code,
                            "Could not focus the requested text field: ${focusResult.message}"
                        )
                    } else {
                        val typed = browser.replaceFocusedText(text)
                        if (typed.success && action.pressEnter) browser.pressEnter() else typed
                    }
                }
            }
            PageAgentActionType.SCROLL -> {
                val dx = action.deltaX ?: 0f
                val dy = action.deltaY
                if (!dx.isFinite() || dy == null || !dy.isFinite()) {
                    invalid(action, "Scroll requires finite delta_x and delta_y")
                } else {
                    browser.scroll(dx, dy)
                }
            }
            PageAgentActionType.BACK -> browser.goBack()
            PageAgentActionType.NAVIGATE -> {
                val url = action.url?.trim()
                if (url.isNullOrBlank()) invalid(action, "Navigate requires a URL")
                else browser.navigate(url)
            }
            PageAgentActionType.WAIT -> {
                val waitMs = (action.waitMs ?: 1_000L).coerceIn(
                    0L,
                    minOf(PageAgentPolicy.MAX_WAIT_MS, remainingRuntimeMs)
                )
                browser.waitForPageChange(waitMs) { control.cancelled.get() || Thread.currentThread().isInterrupted }
            }
            PageAgentActionType.FULLSCREEN -> {
                val mode = action.fullscreenMode
                if (mode == null) invalid(action, "Fullscreen requires page or video mode")
                else browser.requestFullscreen(mode)
            }
            PageAgentActionType.FINISH,
            PageAgentActionType.HANDOFF -> PageAgentActionResult(
                action.type,
                PageAgentActionCode.SUCCESS,
                action.message?.trim().takeUnless { it.isNullOrBlank() } ?: "Terminal action accepted"
            )
        }
    }

    private fun invalid(action: PageAgentAction, message: String) = PageAgentActionResult(
        action.type,
        PageAgentActionCode.INVALID_ACTION,
        message
    )

    private fun defaultConfirmation(action: PageAgentAction): PageAgentConfirmationRequest =
        PageAgentConfirmationRequest(
            action = action.type,
            description = action.message?.trim().takeUnless { it.isNullOrBlank() }
                ?: "Perform ${action.type.name.lowercase()} on the current page",
            riskDescription = "This action may change page state or submit information."
        )

    private fun elapsedMs(startedAt: Long): Long =
        ((clock.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    private fun result(
        state: PageAgentTerminalState,
        message: String,
        browser: PageAgentBrowserAdapter,
        actionCount: Int,
        actionResults: List<PageAgentActionResult>,
        promptInjectionWarning: String? = null,
        confirmation: PageAgentConfirmationRequest? = null
    ) = PageAgentResult(
        state = state,
        message = message,
        target = browser.binding,
        actionCount = actionCount,
        actionResults = actionResults.toList(),
        promptInjectionWarning = promptInjectionWarning,
        confirmation = confirmation
    )

    private fun finish(
        control: PageAgentRunControl,
        emit: (PageAgentUpdate) -> Unit,
        result: PageAgentResult
    ): PageAgentResult {
        AssistantDiagnostics.debug(
            TAG,
            "run=${control.runId} terminal=${result.state} actions=${result.actionCount} " +
                "message=${result.message.take(300)}"
        )
        runCatching {
            emit(
                PageAgentUpdate(
                    runId = control.runId,
                    state = PageAgentUpdateState.FINISHED,
                    terminalState = result.state,
                    result = result
                )
            )
        }
        return result
    }
}
