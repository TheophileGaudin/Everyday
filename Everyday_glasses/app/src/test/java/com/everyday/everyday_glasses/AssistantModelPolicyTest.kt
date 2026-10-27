package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.interaction.assistant.AssistantModel
import com.everyday.everyday_glasses.interaction.assistant.AssistantModelPolicy
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

    @Test
    fun `a retired model is replaced by the newest of its own family`() {
        val models = listOf(
            "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-3.5-flash",
            "gemini-3.5-flash-lite", "gemini-3.10-flash", "gemini-3.8-flash", "gemini-4-argon"
        ).map { AssistantModel(it, it) }

        assertEquals("flash", AssistantModelPolicy.family("models/gemini-2.5-flash"))
        assertEquals("flash-lite", AssistantModelPolicy.family("gemini-3.5-flash-lite"))
        assertEquals(null, AssistantModelPolicy.family("gemini-omni"))
        assertEquals(
            listOf("gemini-3.10-flash", "gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash"),
            AssistantModelPolicy.sameFamilyNewestFirst(models, "gemini-2.5-flash").map { it.id }
        )
        assertEquals(listOf("gemini-2.5-pro"), AssistantModelPolicy.sameFamilyNewestFirst(models, "gemini-2.5-pro").map { it.id })
    }
}
