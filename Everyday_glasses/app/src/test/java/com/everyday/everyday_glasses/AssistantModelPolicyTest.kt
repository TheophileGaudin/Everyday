package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.assistant.AssistantModel
import com.everyday.everyday_glasses.assistant.AssistantModelPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantModelPolicyTest {
    @Test
    fun `newest stable Flash Lite wins and unstable variants are excluded`() {
        val models = listOf(
            "gemini-3.5-flash-lite",
            "gemini-4.0-flash-lite-preview",
            "gemini-3.6-flash-lite",
            "gemini-3.6-flash-lite-latest",
            "gemini-3.6-flash-lite-tts",
            "gemini-2.5-flash-lite"
        ).map { AssistantModel(it, it) }

        assertEquals("gemini-3.6-flash-lite", AssistantModelPolicy.chooseRecommended(models)?.id)
        assertEquals(
            listOf("gemini-3.6-flash-lite", "gemini-3.5-flash-lite", "gemini-2.5-flash-lite"),
            AssistantModelPolicy.recommendedCandidates(models).map { it.id }
        )
        assertFalse(AssistantModelPolicy.isGeneralGeminiModel("gemini-3.5-flash-lite-live"))
        assertTrue(AssistantModelPolicy.isGeneralGeminiModel("gemini-3.5-flash-lite"))
    }
}
