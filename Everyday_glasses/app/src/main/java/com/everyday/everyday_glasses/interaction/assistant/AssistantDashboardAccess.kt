package com.everyday.everyday_glasses.interaction.assistant

import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserRoutingCoordinator
import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserRoutingHost
import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserWidgetPageAgentAdapter
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentApprovalOverlay
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentBrowserAdapter
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentConfirmationRequest
import com.everyday.everyday_glasses.interaction.assistant.page_agent.RunBrowserTaskSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.AssistantTextPresentationRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.ConfigureMapPoisSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateResult
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.MapAssistantActions
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.NewsSourceUpdateResult
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.SetNewsSourceSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.StartNavigationSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.StopNavigationSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.TextAssistantActions
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.UpdateFinanceTileSemanticToolHandler
import com.everyday.everyday_glasses.interaction.commands.AppCommandRegistry
import com.everyday.everyday_glasses.interaction.commands.AppCommandResult
import com.everyday.everyday_glasses.interaction.commands.CommandAvailability
import com.everyday.everyday_glasses.interaction.user_input.TextEditingController
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.runtime.WidgetLifecycleController
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets._common.WidgetPresenter
import com.everyday.everyday_glasses.widgets.browser.BrowserWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import com.everyday.shared.command.AppCommandInvocation
import com.everyday.shared.sync.NewsSourceSpec
import java.util.concurrent.CompletableFuture

/**
 * What the voice assistant can read and do on the dashboard: the snapshot of the dashboard it reads,
 * the tools it can call, the text and map it presents, and the browsers the Page Agent drives, with
 * the popup that asks you before a sensitive action.
 *
 * Widgets have their own access to the dashboard in [DashboardServices]. The dashboard holds one of
 * these; the activity hands it to the assistant's coordinators.
 */
internal class AssistantDashboardAccess(
    private val services: DashboardServices,
    private val registry: WidgetRegistry,
    private val lifecycle: WidgetLifecycleController,
    private val presenter: WidgetPresenter,
    private val textEditing: TextEditingController,
    private val ui: UiThreadDispatcher,
    private val commands: AppCommandRegistry,
    private val isCommandAvailable: (CommandAvailability) -> Boolean,
    private val executeCommand: (AppCommandInvocation) -> AppCommandResult,
    private val activeLayoutName: () -> String?,
    private val openTextForAssistant: () -> TextBoxWidget?,
    private val openBrowserForTask: () -> BrowserWidget?,
    private val updateFinanceTile: (FinanceTileUpdateRequest) -> CompletableFuture<FinanceTileUpdateResult>,
    private val setNewsSource: (NewsSourceSpec) -> CompletableFuture<NewsSourceUpdateResult>,
    private val drawAboveNativeViews: () -> Unit,
    private val hideIme: () -> Unit,
    notifyPersistentChange: () -> Unit,
) : BrowserRoutingHost {
    /** The Page Agent's "allow this action?" popup; the dashboard draws it and gives it taps. */
    val approvalOverlay = PageAgentApprovalOverlay(notifyPersistentChange)

    private var browserRouting: BrowserRoutingCoordinator? = null
    private val webSearch = WebSearchSemanticToolHandler(DuckDuckGoWebSearchProvider())
    private val uiContextCapture by lazy {
        AssistantUiContextCapture(
            registry = registry,
            screenSize = { services.viewportWidth to services.viewportHeight },
            activeLayoutName = activeLayoutName,
            focusedWidget = { registry.allWidgets().firstOrNull { it.hasKeyFocus } },
        )
    }
    private val textActions by lazy {
        TextAssistantActions(
            ui = ui,
            findTextWidget = { id -> registry.findByStableId(id) as? TextBoxWidget },
            createTextWidget = openTextForAssistant,
            clearEditing = { widget -> widget.hideKeyboard(); textEditing.clearAllFocus(); hideIme() },
            presentWidget = { widget, spec, created -> presenter.present(widget, spec, created) },
            removePresentationTarget = presenter::remove,
        )
    }
    private val mapActions by lazy {
        MapAssistantActions(
            ui = ui,
            showMap = { lifecycle.show(WidgetTypeId.MAP); registry.mapWidget },
            currentMap = { registry.mapWidget },
            present = { widget, spec -> presenter.present(widget, spec) },
            dismissPresentation = { registry.mapWidget?.let(services::dismissPresentationOf) },
            withTransientChanges = { action -> services.withTransientChanges(action) },
            notifyTransientChange = services::notifyTransientChange,
            notifyPersistentChange = notifyPersistentChange,
        )
    }

    /** The Page Agent's coordinator, created by the activity; it adds the "run a browser task" tool. */
    fun configureBrowserRouting(routing: BrowserRoutingCoordinator?) {
        browserRouting = routing
    }

    /** The tools the assistant can call: the app's commands, plus the tools below. */
    fun toolCatalog(): AssistantToolCatalog = AssistantToolCatalog(
        registry = commands,
        isAvailable = isCommandAvailable,
        executeCommand = executeCommand,
        extraHandlers = listOfNotNull(
            PresentTextSemanticToolHandler(),
            webSearch,
            InspectUiSemanticToolHandler(),
            RequestScreenshotSemanticToolHandler(),
            ConfigureMapPoisSemanticToolHandler(applyConfiguration = mapActions::configurePois, isAvailable = ::isReady),
            StartNavigationSemanticToolHandler(startNavigation = mapActions::startNavigation, isAvailable = ::isReady),
            StopNavigationSemanticToolHandler(
                stopNavigation = mapActions::stopNavigation,
                isAvailable = { isReady() && registry.mapWidget?.hasActiveNavigation() == true },
            ),
            SetNewsSourceSemanticToolHandler(setNewsSource),
            UpdateFinanceTileSemanticToolHandler(updateTile = updateFinanceTile, isAvailable = { registry.financeWidget != null }),
            browserRouting?.let(::RunBrowserTaskSemanticToolHandler),
        )
    )

    /** Capture only runtime layout metadata for the next assistant request. */
    fun uiContext(): AssistantUiContext = uiContextCapture.capture()

    /**
     * Present assistant-authored text on the UI thread. The callback is asynchronous because
     * semantic handlers run inside the provider worker and widget mutations are main-thread-only.
     */
    fun presentText(request: AssistantTextPresentationRequest) = textActions.present(request)

    fun showApproval(runId: String, request: PageAgentConfirmationRequest, respond: (Boolean) -> Unit) {
        approvalOverlay.show(runId, request, respond)
        drawAboveNativeViews()
    }

    fun dismissApproval(runId: String) = approvalOverlay.dismiss(runId)

    // ==================== The browsers the Page Agent drives ====================

    /**
     * Atomically capture the browser occupying page/video fullscreen and bind its current tab
     * and WebView. This method is called on the UI thread at voice-audio submission time.
     */
    override fun bindFullscreenBrowser(): PageAgentBrowserAdapter? {
        val widget = registry.fullscreenWidget as? BrowserWidget
            ?: registry.browserWidgets.firstOrNull { it.isInVideoFullscreen() }
            ?: return null
        return BrowserWidgetPageAgentAdapter.bind(registry.stableId(widget), widget)
    }

    /** Bind an existing browser for a normal assistant browser task, without falling back to another one when [widgetId] is given. */
    override fun bindBrowser(widgetId: String?): PageAgentBrowserAdapter? {
        val normalizedId = widgetId?.trim()?.takeIf { it.isNotBlank() }
        val widget = if (normalizedId != null) {
            registry.browserWidgets.firstOrNull { registry.stableId(it) == normalizedId }
        } else {
            (textEditing.focusedPage as? BrowserWidget)?.takeIf { !it.isMinimized }
                ?: registry.browserWidgets.firstOrNull { !it.isMinimized }
        } ?: return null
        return BrowserWidgetPageAgentAdapter.bind(registry.stableId(widget), widget)
    }

    /** A windowed browser for a normal assistant browser task, bound to the Page Agent. */
    override fun createBrowser(): PageAgentBrowserAdapter? {
        if (!isReady()) return null
        return services.runPersistentMutation(changed = { true }) {
            openBrowserForTask()?.let { opened ->
                BrowserWidgetPageAgentAdapter.bind(registry.stableId(opened), opened)
                    ?: run {
                        services.close(opened)
                        null
                    }
            }
        }
    }

    private fun isReady(): Boolean = services.viewportWidth > 0 && services.viewportHeight > 0
}