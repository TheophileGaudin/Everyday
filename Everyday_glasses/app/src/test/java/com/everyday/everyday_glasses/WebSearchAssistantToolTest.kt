package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.agent_tools.AssistantToolExecutionContext
import com.everyday.everyday_glasses.agent_tools.AssistantUiContext
import com.everyday.everyday_glasses.agent_tools.WebSearchProvider
import com.everyday.everyday_glasses.agent_tools.WebSearchResult
import com.everyday.everyday_glasses.agent_tools.WebSearchSemanticToolHandler
import com.everyday.everyday_glasses.agent_tools.parseDuckDuckGoResults
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchAssistantToolTest {
    @Test
    fun `duckduckgo html is converted to compact deduplicated evidence`() {
        val html = """
            <div class="result">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fstory">Example &amp; Story</a>
              <a class="result__snippet">A <b>current</b> factual snippet.</a>
            </div>
            <div class="result">
              <a class="result__a" href="https://other.example/report">Other report</a>
              <div class="result__snippet">Second result.</div>
            </div>
        """.trimIndent()

        val results = parseDuckDuckGoResults(html, 5)

        assertEquals(2, results.size)
        assertEquals("Example & Story", results[0].title)
        assertEquals("https://example.com/story", results[0].url)
        assertEquals("A current factual snippet.", results[0].snippet)
    }

    @Test
    fun `search tool returns typed grounding evidence and sources`() {
        val handler = WebSearchSemanticToolHandler(WebSearchProvider { _, _, _ ->
            Result.success(listOf(WebSearchResult("Source", "https://source.example/", "Evidence")))
        })

        val result = handler.execute(
            JSONObject().put("query", "current fact"),
            AssistantToolExecutionContext(AssistantUiContext(640, 480, null, null, emptyList()))
        ).get()

        assertTrue(result.success)
        assertTrue(result.groundingEvidence)
        assertEquals("https://source.example/", result.sources.single().url)
        assertTrue(JSONObject(result.message).getJSONArray("results").length() == 1)
    }

    @Test
    fun `empty search result cannot establish grounding`() {
        val handler =
            WebSearchSemanticToolHandler(WebSearchProvider { _, _, _ -> Result.success(emptyList()) })

        val result = handler.execute(
            JSONObject().put("query", "missing"),
            AssistantToolExecutionContext(AssistantUiContext(640, 480, null, null, emptyList()))
        ).get()

        assertFalse(result.success)
        assertFalse(result.groundingEvidence)
        assertEquals("NO_RESULTS", result.code)
    }
}
