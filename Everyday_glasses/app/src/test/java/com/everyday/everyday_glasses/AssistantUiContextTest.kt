package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.assistant.AssistantSemanticFact
import com.everyday.everyday_glasses.assistant.AssistantUiContext
import com.everyday.everyday_glasses.assistant.AssistantWidgetContext
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantUiContextTest {
    @Test
    fun `prompt contains compact layout metadata without widget content`() {
        val context = AssistantUiContext(
            screenWidth = 640,
            screenHeight = 480,
            layoutName = "Standard",
            focusedWidgetId = "text#0",
            widgets = listOf(
                AssistantWidgetContext(
                    id = "news",
                    left = 0,
                    top = 300,
                    right = 352,
                    bottom = 436,
                    zOrder = 4,
                    minimized = false,
                    fullscreen = false,
                    pinned = true,
                    stableId = "news#stable",
                    facts = listOf(
                        AssistantSemanticFact("kind", "news"),
                        AssistantSemanticFact("kind", "ticker"),
                        AssistantSemanticFact("state", "ordered")
                    )
                )
            )
        )

        val prompt = context.toPromptText()
        assertTrue(prompt.contains("\"screen\":[640,480]"))
        assertTrue(prompt.contains("\"layout\":\"Standard\""))
        assertTrue(prompt.contains("\"focused\":\"text#0\""))
        assertTrue(prompt.contains("\"rect\":[0,300,352,436]"))
        assertTrue(prompt.contains("\"stable_id\":\"news#stable\""))
        assertTrue(prompt.indexOf("\"value\":\"news\"") < prompt.indexOf("\"value\":\"ticker\""))
        assertTrue(prompt.contains("\"value\":\"ordered\""))
        assertTrue(prompt.contains("show_* and hide_*"))
        assertTrue(!prompt.contains("clipboard"))
        assertTrue(!prompt.contains("browser-page-secret"))
        assertTrue(!prompt.contains("https://private.example"))
        assertTrue(!prompt.contains("screenshot-bytes"))
    }
}
