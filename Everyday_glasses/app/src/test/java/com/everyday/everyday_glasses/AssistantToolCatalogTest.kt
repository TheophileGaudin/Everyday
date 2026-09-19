package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.assistant.AssistantToolCall
import com.everyday.everyday_glasses.assistant.AssistantToolCatalog
import com.everyday.everyday_glasses.managers.AppCommandRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantToolCatalogTest {
    private val registry = AppCommandRegistry { listOf("Work") }
    private val catalog = AssistantToolCatalog(registry) { true }

    @Test
    fun `assistant exposes idempotent singleton visibility instead of toggles`() {
        val names = catalog.declarations().map { it.name }.toSet()

        assertTrue("show_news" in names)
        assertTrue("hide_news" in names)
        assertFalse("toggle_news" in names)
    }

    @Test
    fun `assistant visibility tools decode to stable app commands`() {
        val show = catalog.decode(AssistantToolCall("1", "show_news", JSONObject()))
            .getOrThrow()
        val hide = catalog.decode(AssistantToolCall("2", "hide_news", JSONObject()))
            .getOrThrow()

        assertEquals("show:news", show.commandId)
        assertEquals("hide:news", hide.commandId)
    }
}
