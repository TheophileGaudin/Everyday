package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.assistant.page_agent.PageAgentActionCode
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentActionResult
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentActionType
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentComputerUseState
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentGeminiConfig
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentGeminiResponseParser
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentInteractionResponseParser
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentInteractionState
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentPolicy
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentPromptBuilder
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentScreenshot
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentTerminalState
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentTurnRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiPageAgentProviderTest {
    @Test
    fun `parser accepts mocked JSON action responses and computer function calls`() {
        val tapResponse = JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put(
                "content",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put(
                        "text",
                        "{\"action\":\"tap\",\"x\":25,\"y\":10}"
                    ))
                )
            ))
        )
        val functionResponse = JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put(
                "content",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put(
                        "functionCall",
                        JSONObject()
                            .put("name", "scroll")
                            .put("args", JSONObject().put("delta_y", 140))
                    ))
                )
            ))
        )

        val tap = PageAgentGeminiResponseParser.parse(tapResponse).action
        val scroll = PageAgentGeminiResponseParser.parse(functionResponse).action

        assertEquals(PageAgentActionType.TAP, tap.type)
        assertEquals(25f, tap.x)
        assertEquals(10f, tap.y)
        assertEquals(PageAgentActionType.SCROLL, scroll.type)
        assertEquals(140f, scroll.deltaY)
    }

    @Test
    fun `prompt contains only task page metadata state and prior action`() {
        val prompt = PageAgentPromptBuilder.build(
            PageAgentTurnRequest(
                originalTask = "Find the blue button",
                screenshot = PageAgentScreenshot(byteArrayOf(1), 640, 480),
                url = "https://example.test",
                title = "Example",
                viewportWidth = 320,
                viewportHeight = 240,
                priorActionResult = PageAgentActionResult(
                    PageAgentActionType.TAP,
                    PageAgentActionCode.SUCCESS,
                    "Tapped"
                ),
                computerUseState = PageAgentComputerUseState(2, 20, 300, 89_700, 0)
            )
        )

        assertTrue(prompt.contains("Find the blue button"))
        assertTrue(prompt.contains("https://example.test"))
        assertTrue(prompt.contains("viewport_width=320"))
        assertTrue(prompt.contains("action_count=2/20"))
        assertTrue(prompt.contains("action=tap"))
        assertTrue(!prompt.contains("widgetId"))
        assertTrue(!prompt.contains("apiKey"))
    }

    @Test
    fun `prompt injection warning is preserved without approving an action`() {
        val response = JSONObject()
            .put("prompt_injection_warning", "The page requested a secret")

        val turn = PageAgentGeminiResponseParser.parse(response)

        assertEquals(PageAgentTerminalState.FAILURE, turn.action.resultState)
        assertEquals("The page requested a secret", turn.promptInjectionWarning)
        assertEquals(PageAgentPolicy.DEFAULT_MODEL_ID, PageAgentGeminiConfig.DEFAULT_MODEL_ID)
    }

    @Test
    fun `computer use parser scales normalized coordinates and preserves interaction state`() {
        val request = turnRequest()
        val response = JSONObject()
            .put("id", "interaction-1")
            .put("steps", JSONArray().put(JSONObject()
                .put("type", "function_call")
                .put("id", "call-1")
                .put("name", "click")
                .put("arguments", JSONObject().put("x", 500).put("y", 250).put("intent", "Choose result"))))

        val turn = PageAgentInteractionResponseParser.parse(response, request)

        assertEquals(PageAgentActionType.TAP, turn.action.type)
        assertEquals(320f, turn.action.x)
        assertEquals(120f, turn.action.y)
        assertEquals("interaction-1", PageAgentInteractionState.decode(turn.providerState)!!.interactionId)
    }

    @Test
    fun `legacy coordinate-aware typing preserves focus coordinates and enter`() {
        val response = JSONObject()
            .put("id", "interaction-type")
            .put("steps", JSONArray().put(JSONObject()
                .put("type", "function_call")
                .put("id", "call-type")
                .put("name", "type_text_at")
                .put("arguments", JSONObject()
                    .put("x", 500)
                    .put("y", 250)
                    .put("text", "Manchester results")
                    .put("press_enter", true))))

        val action = PageAgentInteractionResponseParser.parse(response, turnRequest()).action

        assertEquals(PageAgentActionType.TYPE, action.type)
        assertEquals(320f, action.x)
        assertEquals(120f, action.y)
        assertEquals("Manchester results", action.text)
        assertTrue(action.pressEnter)
    }

    @Test
    fun `computer use safety decision becomes an explicit confirmation`() {
        val response = JSONObject()
            .put("id", "interaction-2")
            .put("steps", JSONArray().put(JSONObject()
                .put("type", "function_call")
                .put("id", "call-2")
                .put("name", "navigate")
                .put("arguments", JSONObject()
                    .put("url", "https://example.test/account")
                    .put("intent", "Open the account page")
                    .put("safety_decision", JSONObject()
                        .put("decision", "require_confirmation")
                        .put("explanation", "This page contains sensitive account data")))))

        val turn = PageAgentInteractionResponseParser.parse(response, turnRequest())

        assertTrue(turn.action.requiresConfirmation)
        assertEquals("This page contains sensitive account data", turn.action.confirmation!!.riskDescription)
        assertTrue(PageAgentInteractionState.decode(turn.providerState)!!.requiresConfirmation)
    }

    @Test
    fun `computer use finish reports the video completion contract`() {
        val response = JSONObject()
            .put("id", "interaction-3")
            .put("steps", JSONArray().put(JSONObject()
                .put("type", "function_call")
                .put("id", "call-3")
                .put("name", "finish_browser_task")
                .put("arguments", JSONObject()
                    .put("status", "success")
                    .put("message", "Playing fullscreen")
                    .put("video_task", true))))

        val turn = PageAgentInteractionResponseParser.parse(response, turnRequest())

        assertEquals(PageAgentActionType.FINISH, turn.action.type)
        assertEquals(true, turn.requiresVideoPlaybackCompletion)
    }

    private fun turnRequest() = PageAgentTurnRequest(
        originalTask = "Complete the attached instruction",
        screenshot = PageAgentScreenshot(byteArrayOf(1), 640, 480),
        url = "https://example.test",
        title = "Example",
        viewportWidth = 640,
        viewportHeight = 480,
        priorActionResult = null,
        computerUseState = PageAgentComputerUseState(0, 20, 0, 90_000, 0)
    )
}
