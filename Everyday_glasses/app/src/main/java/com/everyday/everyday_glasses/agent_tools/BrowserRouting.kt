package com.everyday.everyday_glasses.agent_tools

import com.everyday.everyday_glasses.WidgetContainer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Browser-specific intent rules shared by voice routing and the Page Agent prompt. */
object BrowserTaskPolicy {
    private val localPlaybackCue = Regex(
        "\\b(local|file|downloaded|device|storage|library)\\b|(?:^|[\\\\/])(?:[a-zA-Z]:)?[\\\\/]|\\.(?:mp4|mkv|mov|avi|webm|m4v)\\b",
        RegexOption.IGNORE_CASE
    )
    private val videoCue = Regex("\\b(video|watch|play|youtube|movie|clip|episode|song)\\b", RegexOption.IGNORE_CASE)

    fun isLocalPlaybackRequest(task: String): Boolean = localPlaybackCue.containsMatchIn(task)

    fun defaultsToInternetVideo(task: String): Boolean =
        videoCue.containsMatchIn(task) && !isLocalPlaybackRequest(task)

    fun requiresVideoPlaybackCompletion(task: String): Boolean = defaultsToInternetVideo(task)

    fun defaultVideoSite(task: String): String? =
        if (defaultsToInternetVideo(task)) "YouTube" else null

    /** Deterministic policy text; the user's task itself is never rewritten. */
    fun guidance(task: String): String = buildString {
        append("Browser policy: ")
        if (defaultsToInternetVideo(task)) {
            append("this is an online/unspecified video request, so use YouTube by default. ")
        } else if (isLocalPlaybackRequest(task)) {
            append("local playback cues are present; do not replace them with an online search. ")
        }
        append("For browser video, search iteratively, inspect results, correct the query when needed, " +
            "open the requested result, start playback, and request video fullscreen. " +
            "Entering a search query alone is not completion.")
    }
}

/** UI-owned browser lookup boundary. Implementations must never substitute another bound target. */
interface BrowserRoutingHost {
    fun bindFullscreenBrowser(): PageAgentBrowserAdapter?
    fun bindBrowser(widgetId: String?): PageAgentBrowserAdapter?
    fun createBrowser(): PageAgentBrowserAdapter?
}

data class BrowserRouteStart(
    val handle: PageAgentRunHandle,
    val target: PageAgentTargetBinding
)

/**
 * Routes browser work to the Work Package 06 Page Agent.
 *
 * A recorded voice route carries the original audio bytes to every Page Agent turn. The
 * coordinator never invokes the main assistant itself; the voice coordinator decides whether
 * a terminal handoff/empty target should be submitted to that assistant exactly once.
 */
class BrowserRoutingCoordinator(
    private val pageAgent: PageAgentCoordinator,
    private val host: BrowserRoutingHost,
    private val ui: PageAgentUiDispatcher = AndroidPageAgentUiDispatcher(),
    private val showApproval: PageAgentApprovalRequester? = null,
    private val dismissApproval: (String) -> Unit = {}
) : AutoCloseable {
    companion object {
        private const val TAG = "BrowserRouting"
        const val RECORDED_VOICE_TASK = "Complete the browser task described by the attached recorded voice instruction."

        fun shouldFallbackToMain(result: PageAgentResult): Boolean =
            result.state == PageAgentTerminalState.HANDOFF_REQUESTED ||
                (result.state == PageAgentTerminalState.TARGET_UNAVAILABLE && result.actionCount == 0)
    }

    private val activeRuns = ConcurrentHashMap<String, PageAgentRunHandle>()

    fun routeRecordedAudio(
        audioFile: File,
        onUpdate: (PageAgentUpdate) -> Unit = {},
        onComplete: (PageAgentResult) -> Unit
    ): BrowserRouteStart? {
        val browser = runCatching { ui.call { host.bindFullscreenBrowser() } }.getOrNull()
            ?: return null
        if (!browser.isAvailable()) return null

        val audio = runCatching { PageAgentAudio(audioFile.readBytes()) }.getOrNull()
            ?: return null

        val completionDelivered = AtomicBoolean(false)
        val handle = runCatching {
            pageAgent.start(
                PageAgentRunRequest(
                    task = RECORDED_VOICE_TASK,
                    browser = browser,
                    onUpdate = onUpdate,
                    audio = audio,
                    requestApproval = approvalRequester()
                )
            )
        }.getOrNull() ?: return null

        activeRuns[handle.runId] = handle
        handle.result.whenComplete { result, failure ->
            activeRuns.remove(handle.runId)
            runCatching { ui.call { dismissApproval(handle.runId) } }
            if (!completionDelivered.compareAndSet(false, true)) return@whenComplete
            val completed = result ?: PageAgentResult(
                state = if (failure is InterruptedException) {
                    PageAgentTerminalState.CANCELLED
                } else {
                    PageAgentTerminalState.FAILURE
                },
                message = failure?.message ?: "Page Agent run failed",
                target = browser.binding,
                actionCount = 0
            )
            onComplete(completed)
        }

        return BrowserRouteStart(handle, browser.binding)
    }

    /** Runs a text browser task requested by the main assistant's `run_browser_task` tool. */
    fun runBrowserTask(
        task: String,
        targetWidgetId: String? = null,
        onUpdate: (PageAgentUpdate) -> Unit = {}
    ): CompletableFuture<AssistantToolExecution> {
        val result = CompletableFuture<AssistantToolExecution>()
        val normalizedTask = task.trim()
        AssistantDiagnostics.debug(TAG, "run requested target=${targetWidgetId ?: "auto"}")
        if (normalizedTask.isBlank()) {
            result.complete(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.INVALID_ARGUMENTS,
                    "Browser task must not be blank"
                )
            )
            return result
        }

        var browser = runCatching {
            ui.call { host.bindBrowser(targetWidgetId) }
        }.getOrNull()
        if ((browser == null || !browser.isAvailable()) && targetWidgetId == null) {
            // Choosing a new browser is allowed only before a Page Agent run is bound. Once a
            // task has started, its adapter is never replaced by another browser.
            browser = runCatching { ui.call { host.createBrowser() } }.getOrNull()
            AssistantDiagnostics.debug(TAG, "created browser available=${browser?.isAvailable() == true}")
        }
        if (browser == null || !browser.isAvailable()) {
            result.complete(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.EXECUTION_FAILED,
                    "No browser page is available for the requested task"
                )
            )
            return result
        }

        val handle = runCatching {
            pageAgent.start(
                PageAgentRunRequest(
                    task = normalizedTask,
                    browser = browser,
                    onUpdate = onUpdate,
                    requestApproval = approvalRequester()
                )
            )
        }.getOrElse { failure ->
            result.complete(
                AssistantToolExecution(
                    false,
                    AssistantToolExecutionCodes.EXECUTION_FAILED,
                    failure.message ?: "Could not start the Page Agent"
                )
            )
            return result
        }
        activeRuns[handle.runId] = handle
        AssistantDiagnostics.debug(TAG, "Page Agent started run=${handle.runId} target=${browser.binding.widgetId}")
        handle.result.whenComplete { pageResult, failure ->
            activeRuns.remove(handle.runId)
            runCatching { ui.call { dismissApproval(handle.runId) } }
            if (failure != null) {
                AssistantDiagnostics.error(TAG, "Page Agent future failed run=${handle.runId}", failure)
                result.complete(
                    AssistantToolExecution(
                        false,
                        AssistantToolExecutionCodes.EXECUTION_FAILED,
                        failure.message ?: "Page Agent failed"
                    )
                )
                return@whenComplete
            }
            AssistantDiagnostics.debug(
                TAG,
                "Page Agent finished run=${handle.runId} state=${pageResult.state} actions=${pageResult.actionCount}"
            )
            result.complete(pageResult.toAssistantExecution())
        }
        return result
    }

    fun cancelActiveRuns() {
        activeRuns.values.toList().forEach { handle ->
            handle.cancel()
            runCatching { ui.call { dismissApproval(handle.runId) } }
        }
    }

    override fun close() {
        cancelActiveRuns()
        activeRuns.clear()
        pageAgent.close()
    }

    fun release() = close()

    private fun approvalRequester(): PageAgentApprovalRequester = { runId, request, respond ->
        val presenter = showApproval
        if (presenter == null) {
            respond(false)
        } else {
            runCatching {
                ui.call { presenter(runId, request, respond) }
            }.onFailure {
                respond(false)
            }
        }
    }

    private fun PageAgentResult.toAssistantExecution(): AssistantToolExecution {
        val success = state == PageAgentTerminalState.SUCCESS
        val code = when (state) {
            PageAgentTerminalState.SUCCESS -> AssistantToolExecutionCodes.SUCCESS
            PageAgentTerminalState.TARGET_UNAVAILABLE -> "TARGET_UNAVAILABLE"
            PageAgentTerminalState.TIMEOUT -> "TIMEOUT"
            PageAgentTerminalState.CANCELLED -> "CANCELLED"
            PageAgentTerminalState.PROMPT_INJECTION_WARNING -> "PROMPT_INJECTION_WARNING"
            PageAgentTerminalState.HANDOFF_REQUESTED -> "HANDOFF_REQUESTED"
            PageAgentTerminalState.PARTIAL -> "PARTIAL"
            else -> "BROWSER_TASK_FAILED"
        }
        return AssistantToolExecution(success, code, message.take(500))
    }
}

/** Provider-facing semantic tool used by the main assistant for ordinary browser requests. */
class RunBrowserTaskSemanticToolHandler(
    private val routing: BrowserRoutingCoordinator
) : AssistantSemanticToolHandler {
    override val definition = AssistantToolDefinition(
        name = "run_browser_task",
        description = "Operate one browser page to complete a browser or online-video task",
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties", JSONObject()
                    .put("task", JSONObject().put("type", "string").put("maxLength", 10_000))
                    .put("target_widget_id", JSONObject().put("type", "string"))
            )
            .put("required", JSONArray().put("task"))
    )

    override fun validate(arguments: JSONObject): Result<Unit> = runCatching {
        require(arguments.has("task")) { "task is required" }
        require(arguments.getString("task").trim().isNotBlank()) { "task must not be blank" }
        require(arguments.getString("task").length <= 10_000) { "task is too long" }
        arguments.optString("target_widget_id").takeIf { it.isNotBlank() }
    }.map { Unit }

    override fun execute(
        arguments: JSONObject,
        context: AssistantToolExecutionContext
    ): CompletableFuture<AssistantToolExecution> = routing.runBrowserTask(
        task = arguments.getString("task"),
        targetWidgetId = arguments.optString("target_widget_id").trim().takeIf { it.isNotBlank() }
    )
}

/** Concrete host adapter for the Android widget container. */
class WidgetContainerBrowserRoutingHost(
    private val widgetContainer: WidgetContainer
) : BrowserRoutingHost {
    override fun bindFullscreenBrowser(): PageAgentBrowserAdapter? =
        widgetContainer.bindFullscreenPageAgentBrowser()

    override fun bindBrowser(widgetId: String?): PageAgentBrowserAdapter? =
        widgetContainer.bindPageAgentBrowserForTask(widgetId)

    override fun createBrowser(): PageAgentBrowserAdapter? =
        widgetContainer.createPageAgentBrowser()
}
