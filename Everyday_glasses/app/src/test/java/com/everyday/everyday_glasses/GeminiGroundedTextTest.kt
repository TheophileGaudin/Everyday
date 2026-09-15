package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.agent_tools.AssistantProviderTurn
import com.everyday.everyday_glasses.agent_tools.AssistantTextContentFormatter
import com.everyday.everyday_glasses.agent_tools.AssistantTextPresentationPolicy
import com.everyday.everyday_glasses.agent_tools.AssistantTextPresentationRequest
import com.everyday.everyday_glasses.agent_tools.AssistantToolCall
import com.everyday.everyday_glasses.agent_tools.AssistantToolCatalog
import com.everyday.everyday_glasses.agent_tools.AssistantToolDefinition
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecution
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecutionContext
import com.everyday.everyday_glasses.agent_tools.AssistantUiContext
import com.everyday.everyday_glasses.agent_tools.GeminiInteractionResponseParser
import com.everyday.everyday_glasses.agent_tools.PresentTextSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.geminiInteractionTools
import com.everyday.everyday_glasses.controllers.AppCommandResult
import com.everyday.everyday_glasses.controllers.PresentationMode
import com.everyday.everyday_glasses.managers.AppCommandRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class GeminiGroundedTextTest {
    @Test
    fun `custom tool fixture remains a custom call`() {
        val turn = parse(
            """
            {"steps":[{"type":"function_call","id":"call-1","name":"show_news","arguments":{}}]}
            """
        )

        assertEquals(listOf("show_news"), turn.calls.map { it.name })
        assertNull(turn.outputText)
        assertFalse(turn.searchAttempted)
    }

    @Test
    fun `grounded text fixture returns the answer and marks Search`() {
        val turn = parse(
            """
            {
              "steps":[
                {"type":"google_search_call","id":"search-1","arguments":{"queries":["latest news"]}},
                {"type":"google_search_result","call_id":"search-1","result":[{"search_suggestions":"ignored html"}]},
                {"type":"model_output","content":[{"type":"text","text":"The latest headlines are available.","annotations":[{"type":"url_citation","url":"https://news.example/headlines","title":"News Example"}]}]}
              ]
            }
            """
        )

        val answer = turn.informationalAnswerOrNull()
        assertTrue(turn.searchAttempted)
        assertFalse(turn.searchFailed)
        assertNotNull(answer)
        assertEquals("The latest headlines are available.", answer!!.text)
        assertEquals("News Example", answer.sources.single().name)
        assertTrue(answer.sources.single().url.contains("news.example"))
    }

    @Test
    fun `readable but ungrounded model output is never presented as factual text`() {
        val turn = parse(
            """{"steps":[{"type":"model_output","content":[{"type":"text","text":"These are the latest results."}]}]}"""
        )

        assertFalse(turn.searchAttempted)
        assertNull(turn.informationalAnswerOrNull())
    }

    @Test
    fun `latest news and Manchester results use Search-backed answer presentation`() {
        assertSearchBackedAnswer(
            query = "latest news",
            answerText = "Top stories: a concise current-news summary."
        )
        assertSearchBackedAnswer(
            query = "latest Manchester results",
            answerText = "Manchester's latest result: a concise current match summary."
        )
    }

    @Test
    fun `Search plus function fixture keeps both the search marker and custom call`() {
        val turn = parse(
            """
            {
              "steps":[
                {"type":"google_search_call","id":"search-1","arguments":{"queries":["Manchester results"]}},
                {"type":"function_call","id":"call-1","name":"show_news","arguments":{}}
              ]
            }
            """
        )

        assertTrue(turn.searchAttempted)
        assertEquals("show_news", turn.calls.single().name)
        assertNull(turn.informationalAnswerOrNull())
    }

    @Test
    fun `source metadata from annotations and result items is compact and deduplicated`() {
        val turn = parse(
            """
            {
              "steps":[
                {"type":"google_search_result","result":[{"url":"https://source.example/story","title":"Source Example"}]},
                {"type":"text","text":"Grounded answer.","annotations":[{"source":"https://source.example/story"},{"source":"https://other.example/story","title":"Other Example"}]}
              ]
            }
            """
        )

        assertEquals(2, turn.sources.size)
        val rendered = AssistantTextContentFormatter.render("Grounded answer.", turn.sources)
        assertTrue(rendered.contains("Sources:"))
        assertTrue(rendered.contains("Source Example"))
        assertTrue(rendered.contains("other.example"))
        assertEquals(1, rendered.split("https://source.example/story").size - 1)
    }

    @Test
    fun `empty and malformed provider output is never an answer`() {
        val empty = parse(
            """{"steps":[{"type":"model_output","content":[]}]}
            """
        )
        val malformed = parse(
            """{"steps":[{"type":"model_output","content":[{"type":"text","text":{"not":"text"}}]}]}"""
        )

        assertNull(empty.outputText)
        assertNull(empty.informationalAnswerOrNull())
        assertNull(malformed.outputText)
        assertNull(malformed.informationalAnswerOrNull())
    }

    @Test
    fun `Search failure returns a non-fabricated failure marker`() {
        val turn = parse(
            """
            {
              "steps":[
                {"type":"google_search_call","id":"search-1","arguments":{"queries":["latest news"]}},
                {"type":"google_search_result","call_id":"search-1","is_error":true}
              ]
            }
            """
        )

        assertTrue(turn.searchAttempted)
        assertTrue(turn.searchFailed)
        assertNull(turn.informationalAnswerOrNull())
    }

    @Test
    fun `provider advertises only custom functions without paid google search`() {
        val function = AssistantToolDefinition(
            name = "show_news",
            description = "Show news",
            parameters = JSONObject().put("type", "object")
        )
        val tools = geminiInteractionTools(listOf(function))

        assertEquals(1, tools.length())
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertEquals("show_news", tools.getJSONObject(0).getString("name"))
    }

    @Test
    fun `present_text forwards content presentation sources and target without typing`() {
        val requests = mutableListOf<AssistantTextPresentationRequest>()
        val handler = PresentTextSemanticToolHandler()
        val args = JSONObject()
            .put("content", "A grounded answer")
            .put(
                "presentation", JSONObject()
                    .put("mode", "fullscreen")
                    .put("duration_seconds", 12)
                    .put("on_expiry", "close")
            )
            .put("target_widget_id", "text#2")
            .put("sources", JSONArray().put(JSONObject().put("name", "Source").put("url", "https://source.example")))
        val context = AssistantToolExecutionContext(
            uiContext = emptyUiContext(),
            presentText = { request ->
                requests += request
                CompletableFuture.completedFuture(
                    AssistantToolExecution(
                        true,
                        AssistantToolExecutionCodes.SUCCESS,
                        "Text presented"
                    )
                )
            }
        )

        val result = handler.invoke(AssistantToolCall("call-1", "present_text", args), context).get()

        assertTrue(result.success)
        assertEquals("A grounded answer", requests.single().content)
        assertEquals(PresentationMode.FULLSCREEN, requests.single().spec.mode)
        assertEquals(12, requests.single().spec.durationSeconds)
        assertEquals("text#2", requests.single().targetWidgetId)
        assertEquals("https://source.example", requests.single().sources.single().url)
    }

    @Test
    fun `text_enter remains the existing user-directed typing command`() {
        val registry = AppCommandRegistry { emptyList() }
        val catalog = AssistantToolCatalog(
            registry = registry,
            isAvailable = { true },
            executeCommand = { AppCommandResult.Success(it.commandId) }
        )
        val call = catalog.declarations().first { it.name == "text_enter" }
        val decoded = catalog.decode(
            AssistantToolCall(
                "call-1",
                call.name,
                JSONObject().put("text", "user-directed text")
            )
        ).getOrThrow()

        assertEquals(registry.enterText.id, decoded.commandId)
    }

    private fun assertSearchBackedAnswer(query: String, answerText: String) {
        val turn = parse(
            """
            {
              "steps":[
                {"type":"google_search_call","id":"search-1","arguments":{"queries":["$query"]}},
                {"type":"model_output","content":[{"type":"text","text":"$answerText"}]}
              ]
            }
            """
        )
        val answer = turn.informationalAnswerOrNull()!!
        val request = AssistantTextPresentationPolicy.forAnswer(answer)

        assertTrue(turn.searchAttempted)
        assertTrue(request.spec.mode == PresentationMode.FULLSCREEN)
        assertTrue(request.spec.durationSeconds in 5..120)
        assertFalse(request.content == query)
        assertTrue(request.content.contains("summary"))
    }

    private fun parse(json: String): AssistantProviderTurn =
        GeminiInteractionResponseParser.parse(JSONObject(json))

    private fun emptyUiContext() = AssistantUiContext(
        screenWidth = 640,
        screenHeight = 480,
        layoutName = null,
        focusedWidgetId = null,
        widgets = emptyList()
    )
}
