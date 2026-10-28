package com.everyday.everyday_glasses.interaction.assistant

import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserRoutingCoordinator
import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserRoutingHost
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentApprovalOverlay
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentBrowserAdapter
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentConfirmationRequest
import com.everyday.everyday_glasses.interaction.assistant.page_agent.RunBrowserTaskSemanticToolHandler
import com.everyday.everyday_glasses.interaction.commands.AppCommandRegistry
import com.everyday.everyday_glasses.interaction.commands.AppCommandResult
import com.everyday.everyday_glasses.interaction.commands.CommandAvailability
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.shared.command.AppCommandInvocation
import java.util.concurrent.CompletableFuture

/**
 * What a widget plugin adds to the voice assistant: the tools it can call on the plugin's widgets,
 * and what it reads about them. A plugin implements it when it has something to add.
 */
interface AssistantContribution {
    /** The tools the assistant can call on this type's widgets. */
    fun assistantTools(): List<AssistantSemanticToolHandler> = emptyList()

    /** What the dashboard snapshot says about [widget], beyond its type and label; [stableId] is its id there. */
    fun assistantFacts(widget: BaseWidget, stableId: String): List<AssistantSemanticFact> = emptyList()
}

/** The plugin that shows the assistant's text answers (the text box's). */
interface AssistantTextPresenter {
    fun presentAssistantText(request: AssistantTextPresentationRequest): CompletableFuture<AssistantToolExecution>
}

/**
 * What the voice assistant can read and do on the dashboard: the snapshot of the dashboard it reads,
 * the tools it can call, the text it presents, and the browsers the Page Agent drives, with the popup
 * that asks you before a sensitive action.
 *
 * It names no widget: what is particular to a widget type comes from its plugin, as an
 * [AssistantContribution], an [AssistantTextPresenter] or a [BrowserRoutingHost]. Widgets have
 * their own access to the dashboard in [WidgetContext]. The dashboard holds one of these; the
 * activity hands it to the assistant's coordinators.
 */
internal class AssistantDashboardAccess(
    private val services: WidgetContext,
    plugins: List<WidgetPlugin>,
    private val registry: WidgetRegistry,
    private val commands: AppCommandRegistry,
    private val isCommandAvailable: (CommandAvailability) -> Boolean,
    private val executeCommand: (AppCommandInvocation) -> AppCommandResult,
    private val activeLayoutName: () -> String?,
    private val drawAboveNativeViews: () -> Unit,
    notifyPersistentChange: () -> Unit,
) : BrowserRoutingHost {
    /** The Page Agent's "allow this action?" popup; the dashboard draws it and gives it taps. */
    val approvalOverlay = PageAgentApprovalOverlay(notifyPersistentChange)

    private var browserRouting: BrowserRoutingCoordinator? = null
    /** How web search reaches the internet when the glasses have none; the activity sets it before the first search. */
    var internet: okhttp3.Interceptor? = null
    /** The glasses' camera, for looks, photos and videos; the activity sets it. */
    var camera: AssistantCamera? = null
    private val webSearch by lazy { WebSearchSemanticToolHandler(DuckDuckGoWebSearchProvider(internet)) }
    private val contributions: Map<WidgetTypeId, AssistantContribution> = plugins
        .filterIsInstance<AssistantContribution>()
        .associateBy { (it as WidgetPlugin).lifecycle.descriptor.type }
    private val textPresenter: AssistantTextPresenter? = plugins.filterIsInstance<AssistantTextPresenter>().singleOrNull()
    private val browsers: BrowserRoutingHost? = plugins.filterIsInstance<BrowserRoutingHost>().singleOrNull()
    private val uiContextCapture by lazy {
        AssistantUiContextCapture(
            registry = registry,
            screenSize = { services.viewportWidth to services.viewportHeight },
            activeLayoutName = activeLayoutName,
            focusedWidget = { registry.allWidgets().firstOrNull { it.hasKeyFocus } },
            factsOf = { widget, stableId -> contributions[widget.type]?.assistantFacts(widget, stableId).orEmpty() },
        )
    }

    /** The Page Agent's coordinator, created by the activity; it adds the "run a browser task" tool. */
    fun configureBrowserRouting(routing: BrowserRoutingCoordinator?) {
        browserRouting = routing
    }

    /** The tools the assistant can call: the app's commands, the general tools, and the plugins' tools. */
    fun toolCatalog(): AssistantToolCatalog = AssistantToolCatalog(
        registry = commands,
        isAvailable = isCommandAvailable,
        executeCommand = executeCommand,
        extraHandlers = listOf(
            PresentTextSemanticToolHandler(),
            webSearch,
            InspectUiSemanticToolHandler(),
            RequestScreenshotSemanticToolHandler(),
        ) + camera?.tools().orEmpty() + contributions.values.flatMap { it.assistantTools() } +
                listOfNotNull(browserRouting?.let(::RunBrowserTaskSemanticToolHandler))
    )

    /** Capture only runtime layout metadata for the next assistant request. */
    fun uiContext(): AssistantUiContext = uiContextCapture.capture()

    /**
     * Present assistant-authored text on the UI thread. The callback is asynchronous because
     * semantic handlers run inside the provider worker and widget mutations are main-thread-only.
     */
    fun presentText(request: AssistantTextPresentationRequest): CompletableFuture<AssistantToolExecution> =
        textPresenter?.presentAssistantText(request)
            ?: CompletableFuture.completedFuture(
                AssistantToolExecution(false, AssistantToolExecutionCodes.EXECUTION_FAILED, "Text presentation is unavailable")
            )

    fun showApproval(runId: String, request: PageAgentConfirmationRequest, respond: (Boolean) -> Unit) {
        approvalOverlay.show(runId, request, respond)
        drawAboveNativeViews()
    }

    fun dismissApproval(runId: String) = approvalOverlay.dismiss(runId)

    // ==================== The browsers the Page Agent drives: the browser plugin's ====================

    override fun bindFullscreenBrowser(): PageAgentBrowserAdapter? = browsers?.bindFullscreenBrowser()

    override fun bindBrowser(widgetId: String?): PageAgentBrowserAdapter? = browsers?.bindBrowser(widgetId)

    override fun createBrowser(): PageAgentBrowserAdapter? = browsers?.createBrowser()
}