package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.agent_tools.AssistantSemanticFact
import com.everyday.everyday_glasses.agent_tools.AssistantToolCall
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.agent_tools.AssistantToolExecutionContext
import com.everyday.everyday_glasses.agent_tools.AssistantUiContext
import com.everyday.everyday_glasses.agent_tools.AssistantWidgetContext
import com.everyday.everyday_glasses.agent_tools.NewsSourceUpdateResult
import com.everyday.everyday_glasses.agent_tools.SetNewsSourceSemanticToolHandler
import com.everyday.shared.sync.NewsSourceSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class NewsAssistantToolsTest {

    @Test
    fun `set news source updates the existing source target`() {
        var requested: NewsSourceSpec? = null
        val handler = SetNewsSourceSemanticToolHandler { source ->
            requested = source
            CompletableFuture.completedFuture(
                NewsSourceUpdateResult(applied = true, message = "updated", sourceSpec = source)
            )
        }
        val context = AssistantToolExecutionContext(emptyUiContext())
        val call = AssistantToolCall(
            id = "1",
            name = "set_news_source",
            arguments = JSONObject().put("kind", "query").put("intent", "latest local technology")
        )

        val execution = handler.invoke(call, context).get()
        assertTrue(execution.success)
        assertEquals(NewsSourceSpec.QUERY("latest local technology"), requested)
        assertEquals(AssistantToolExecutionCodes.SUCCESS, execution.code)
    }

    @Test
    fun `invalid custom source is rejected before update`() {
        var updateCalled = false
        val handler = SetNewsSourceSemanticToolHandler {
            updateCalled = true
            CompletableFuture.completedFuture(NewsSourceUpdateResult(true, "unexpected"))
        }
        val arguments = JSONObject().put("kind", "custom_rss").put("url", "file:///tmp/feed.xml")

        assertTrue(handler.validate(arguments).isFailure)
        val execution = handler.invoke(
            AssistantToolCall("2", "set_news_source", arguments),
            AssistantToolExecutionContext(emptyUiContext())
        ).get()
        assertFalse(execution.success)
        assertEquals(AssistantToolExecutionCodes.INVALID_ARGUMENTS, execution.code)
        assertFalse(updateCalled)
    }

    @Test
    fun `source update failure is reported without claiming it was applied`() {
        val previous = NewsSourceSpec.DEFAULT_LOCATION
        val handler = SetNewsSourceSemanticToolHandler {
            CompletableFuture.completedFuture(
                NewsSourceUpdateResult(
                    applied = false,
                    message = "Feed did not return usable news",
                    sourceSpec = previous
                )
            )
        }

        val execution = handler.invoke(
            AssistantToolCall(
                "3",
                "set_news_source",
                JSONObject().put("kind", "custom_rss").put("url", "https://example.com/feed.xml")
            ),
            AssistantToolExecutionContext(emptyUiContext())
        ).get()
        assertFalse(execution.success)
        assertEquals(AssistantToolExecutionCodes.EXECUTION_FAILED, execution.code)
        assertEquals("Feed did not return usable news", execution.message)
    }

    @Test
    fun `news source facts are represented in assistant context`() {
        val source = AssistantWidgetContext(
            id = "news",
            left = 10,
            top = 20,
            right = 400,
            bottom = 220,
            zOrder = 3,
            minimized = false,
            fullscreen = false,
            pinned = true,
            facts = listOf(
                AssistantSemanticFact("widget_type", "news"),
                AssistantSemanticFact("source_kind", "QUERY"),
                AssistantSemanticFact("source_description", "Query: local technology")
            )
        )
        val json = AssistantUiContext(640, 480, null, null, listOf(source)).toJson().toString()
        assertTrue(json.contains("\"key\":\"source_kind\""))
        assertTrue(json.contains("\"value\":\"QUERY\""))
        assertTrue(json.contains("Query: local technology"))
    }

    private fun emptyUiContext() = AssistantUiContext(
        screenWidth = 640,
        screenHeight = 480,
        layoutName = null,
        focusedWidgetId = null,
        widgets = emptyList()
    )
}
