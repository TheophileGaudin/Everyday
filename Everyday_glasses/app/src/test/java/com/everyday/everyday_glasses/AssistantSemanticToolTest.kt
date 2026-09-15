package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.agent_tools.AssistantModel
import com.everyday.everyday_glasses.agent_tools.AssistantProviderTurn
import com.everyday.everyday_glasses.agent_tools.AssistantScreenshot
import com.everyday.everyday_glasses.agent_tools.AssistantSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.AssistantTextPresentationRequest
import com.everyday.everyday_glasses.agent_tools.AssistantToolCall
import com.everyday.everyday_glasses.agent_tools.AssistantToolCatalog
import com.everyday.everyday_glasses.agent_tools.AssistantToolDefinition
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecution
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecutionContext
import com.everyday.everyday_glasses.agent_tools.AssistantToolLoop
import com.everyday.everyday_glasses.agent_tools.AssistantUiContext
import com.everyday.everyday_glasses.agent_tools.InspectUiSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.PresentTextSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.RequestScreenshotSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.VoiceAssistantProvider
import com.everyday.everyday_glasses.agent_tools.WebSearchProvider
import com.everyday.everyday_glasses.agent_tools.WebSearchResult
import com.everyday.everyday_glasses.agent_tools.WebSearchSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.assistantCompletedFuture
import com.everyday.everyday_glasses.controllers.AppCommandResult
import com.everyday.everyday_glasses.managers.AppCommandRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture

class AssistantSemanticToolTest {
    @Test
    fun `synthetic async handler is advertised validated invoked and returned through provider loop`() {
        val handler = RecordingHandler("synthetic_action")
        val catalog = AssistantToolCatalog(listOf(handler))
        val provider = FakeProvider()
        val call =
            AssistantToolCall("call-1", "synthetic_action", JSONObject().put("value", "ready"))
        val initialTurn = AssistantProviderTurn("interaction-1", listOf(call))

        val outcome = AssistantToolLoop(
            provider = provider,
            apiKey = "test-key",
            modelId = "test-model",
            refreshCatalog = { catalog },
            context = executionContext()
        ).run(catalog, initialTurn)

        assertTrue(catalog.declarations().any { it.name == "synthetic_action" })
        assertEquals(1, handler.validationCalls)
        assertEquals(1, handler.executionCalls)
        assertTrue(outcome.anySuccess)
        assertTrue(outcome.recognized)
        assertEquals(1, provider.receivedResults.size)
        assertEquals("SUCCESS", provider.receivedResults.single().second.code)
        assertEquals("synthetic_action completed", provider.receivedResults.single().second.message)
    }

    @Test
    fun `unknown tool fails without executing a handler`() {
        val handler = RecordingHandler("known_action")
        val catalog = AssistantToolCatalog(listOf(handler))

        val result = catalog.execute(
            AssistantToolCall("unknown", "missing_action", JSONObject()),
            executionContext()
        ).get()

        assertFalse(result.success)
        assertEquals(AssistantToolExecutionCodes.UNKNOWN_TOOL, result.code)
        assertEquals(0, handler.executionCalls)
    }

    @Test
    fun `duplicate semantic names fail deterministically before invocation`() {
        val first = RecordingHandler("same_name")
        val second = RecordingHandler("same_name")

        try {
            AssistantToolCatalog(listOf(first, second))
            fail("Expected duplicate handler names to be rejected")
        } catch (failure: IllegalArgumentException) {
            assertEquals("Duplicate assistant tool name: same_name", failure.message)
        }

        assertEquals(0, first.executionCalls)
        assertEquals(0, second.executionCalls)
    }

    @Test
    fun `existing app commands remain advertised and execute through the adapter`() {
        val registry = AppCommandRegistry { listOf("Work") }
        val invocations = mutableListOf<com.everyday.shared.command.AppCommandInvocation>()
        val catalog = AssistantToolCatalog(
            registry = registry,
            isAvailable = { true },
            executeCommand = { invocation ->
                invocations += invocation
                AppCommandResult.Success(invocation.commandId)
            }
        )

        val names = catalog.declarations().map { it.name }.toSet()
        assertTrue("show_news" in names)
        assertTrue("hide_news" in names)
        assertTrue("load_layout" in names)

        val result = catalog.execute(
            AssistantToolCall("show", "show_news", JSONObject()),
            executionContext()
        ).get()

        assertTrue(result.success)
        assertEquals("show:news", invocations.single().commandId)
    }

    @Test
    fun `inspect ui and screenshot are explicit on-demand paths`() {
        var screenshotRequests = 0
        val ui = executionContext().uiContext
        val catalog = AssistantToolCatalog(
            listOf(
                InspectUiSemanticToolHandler(),
                RequestScreenshotSemanticToolHandler()
            )
        )
        val context = AssistantToolExecutionContext(
            uiContext = ui,
            captureUiContext = { assistantCompletedFuture(ui) },
            requestScreenshot = {
                screenshotRequests++
                assistantCompletedFuture(AssistantScreenshot(byteArrayOf(1, 2, 3), 2, 1))
            }
        )

        assertEquals(0, screenshotRequests)
        val inspected = catalog.execute(
            AssistantToolCall("inspect", "inspect_ui", JSONObject()),
            context
        ).get()
        assertTrue(inspected.success)
        assertTrue(inspected.message.contains("\"widgets\""))
        assertEquals(0, screenshotRequests)

        val screenshot = catalog.execute(
            AssistantToolCall("screenshot", "request_screenshot", JSONObject()),
            context
        ).get()
        assertTrue(screenshot.success)
        assertEquals(1, screenshotRequests)
        assertEquals(1, screenshot.screenshots.size)
    }

    @Test
    fun `present text is rejected until a provider turn has used Search`() {
        val presentation = JSONObject()
            .put("mode", "fullscreen")
            .put("duration_seconds", 10)
            .put("on_expiry", "restore_previous")
        val call = AssistantToolCall(
            "present-1",
            "present_text",
            JSONObject().put("content", "Current result").put("presentation", presentation)
        )
        val presented = mutableListOf<AssistantTextPresentationRequest>()
        val catalog = AssistantToolCatalog(listOf(PresentTextSemanticToolHandler()))
        var continuation = 0
        val provider = object : VoiceAssistantProvider {
            override fun listModels(apiKey: String) = Result.success(emptyList<AssistantModel>())
            override fun probeAudioTools(apiKey: String, modelId: String) = Result.success(Unit)
            override fun startInteraction(
                apiKey: String,
                modelId: String,
                audioFile: File,
                tools: List<AssistantToolDefinition>,
                uiContext: AssistantUiContext
            ): Result<AssistantProviderTurn> = error("Prepared turn")

            override fun continueInteraction(
                apiKey: String,
                modelId: String,
                previousInteractionId: String,
                tools: List<AssistantToolDefinition>,
                results: List<Pair<AssistantToolCall, AssistantToolExecution>>
            ): Result<AssistantProviderTurn> {
                continuation++
                return if (continuation == 1) {
                    assertEquals(AssistantToolExecutionCodes.GROUNDING_REQUIRED, results.single().second.code)
                    Result.success(
                        AssistantProviderTurn(
                            previousInteractionId,
                            listOf(call.copy(id = "present-2")),
                            searchAttempted = true
                        )
                    )
                } else {
                    Result.success(AssistantProviderTurn(previousInteractionId, emptyList()))
                }
            }

            override fun deleteUploadedFile(apiKey: String, name: String) = Unit
        }
        val context = executionContext().copy(
            presentText = { request ->
                presented += request
                assistantCompletedFuture(
                    AssistantToolExecution(true, AssistantToolExecutionCodes.SUCCESS, "Presented")
                )
            }
        )

        val outcome = AssistantToolLoop(
            provider = provider,
            apiKey = "test-key",
            modelId = "test-model",
            refreshCatalog = { catalog },
            context = context
        ).run(catalog, AssistantProviderTurn("interaction-1", listOf(call)))

        assertEquals(2, continuation)
        assertEquals(1, presented.size)
        assertTrue(outcome.textPresented)
    }

    @Test
    fun `successful custom web search grounds a later presentation and injects sources`() {
        val search = WebSearchSemanticToolHandler(WebSearchProvider { _, _, _ ->
            Result.success(
                listOf(
                    WebSearchResult(
                        "Evidence",
                        "https://evidence.example/",
                        "Current fact"
                    )
                )
            )
        })
        val catalog = AssistantToolCatalog(listOf(search, PresentTextSemanticToolHandler()))
        val searchCall =
            AssistantToolCall("search-1", "search_web", JSONObject().put("query", "current fact"))
        val presentCall = AssistantToolCall(
            "present-1",
            "present_text",
            JSONObject()
                .put("content", "The current fact is supported.")
                .put(
                    "presentation", JSONObject()
                        .put("mode", "fullscreen")
                        .put("duration_seconds", 10)
                        .put("on_expiry", "close")
                )
        )
        var continuation = 0
        val provider = object : VoiceAssistantProvider {
            override fun listModels(apiKey: String) = Result.success(emptyList<AssistantModel>())
            override fun probeAudioTools(apiKey: String, modelId: String) = Result.success(Unit)
            override fun startInteraction(apiKey: String, modelId: String, audioFile: File,
                                          tools: List<AssistantToolDefinition>, uiContext: AssistantUiContext
            ) = error("Prepared turn")
            override fun continueInteraction(apiKey: String, modelId: String, previousInteractionId: String,
                                             tools: List<AssistantToolDefinition>, results: List<Pair<AssistantToolCall, AssistantToolExecution>>
            ): Result<AssistantProviderTurn> {
                continuation++
                return Result.success(
                    AssistantProviderTurn(
                        previousInteractionId,
                        if (continuation == 1) listOf(presentCall) else emptyList()
                    )
                )
            }
            override fun deleteUploadedFile(apiKey: String, name: String) = Unit
        }
        val presented = mutableListOf<AssistantTextPresentationRequest>()
        val context = executionContext().copy(presentText = { request ->
            presented += request
            assistantCompletedFuture(
                AssistantToolExecution(
                    true,
                    AssistantToolExecutionCodes.SUCCESS,
                    "Presented"
                )
            )
        })

        val outcome = AssistantToolLoop(
            provider, "key", "model", { catalog }, context
        ).run(catalog, AssistantProviderTurn("interaction", listOf(searchCall)))

        assertTrue(outcome.textPresented)
        assertFalse(outcome.searchFailed)
        assertEquals("https://evidence.example/", presented.single().sources.single().url)
    }

    private fun executionContext(): AssistantToolExecutionContext = AssistantToolExecutionContext(
        uiContext = AssistantUiContext(
            screenWidth = 640,
            screenHeight = 480,
            layoutName = null,
            focusedWidgetId = null,
            widgets = emptyList()
        )
    )

    private class RecordingHandler(
        name: String
    ) : AssistantSemanticToolHandler {
        override val definition = AssistantToolDefinition(
            name,
            "Synthetic test action",
            JSONObject().put("type", "object").put(
                "properties", JSONObject().put("value", JSONObject().put("type", "string"))
            ).put("required", JSONArray().put("value"))
        )
        var validationCalls = 0
        var executionCalls = 0

        override fun validate(arguments: JSONObject): Result<Unit> {
            validationCalls++
            return if (arguments.optString("value") == "ready") {
                Result.success(Unit)
            } else {
                Result.failure(IllegalArgumentException("value must be ready"))
            }
        }

        override fun execute(
            arguments: JSONObject,
            context: AssistantToolExecutionContext
        ): CompletableFuture<AssistantToolExecution> = CompletableFuture.supplyAsync {
            executionCalls++
            AssistantToolExecution(
                true,
                AssistantToolExecutionCodes.SUCCESS,
                "synthetic_action completed"
            )
        }
    }

    private class FakeProvider : VoiceAssistantProvider {
        val receivedResults = mutableListOf<Pair<AssistantToolCall, AssistantToolExecution>>()

        override fun listModels(apiKey: String): Result<List<AssistantModel>> = Result.success(emptyList())

        override fun probeAudioTools(apiKey: String, modelId: String): Result<Unit> = Result.success(Unit)

        override fun startInteraction(
            apiKey: String,
            modelId: String,
            audioFile: File,
            tools: List<AssistantToolDefinition>,
            uiContext: AssistantUiContext
        ): Result<AssistantProviderTurn> = error("The loop test starts from a prepared turn")

        override fun continueInteraction(
            apiKey: String,
            modelId: String,
            previousInteractionId: String,
            tools: List<AssistantToolDefinition>,
            results: List<Pair<AssistantToolCall, AssistantToolExecution>>
        ): Result<AssistantProviderTurn> {
            receivedResults += results
            return Result.success(AssistantProviderTurn(previousInteractionId, emptyList()))
        }

        override fun deleteUploadedFile(apiKey: String, name: String) = Unit
    }
}
