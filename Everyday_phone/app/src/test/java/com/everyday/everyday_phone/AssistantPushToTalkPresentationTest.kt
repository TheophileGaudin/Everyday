package com.everyday.everyday_phone

import com.everyday.shared.transport.AssistantSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPushToTalkPresentationTest {
    @Test
    fun `idle presentation clears text and shows voice icon`() {
        val presentation = assistantPushToTalkPresentation(AssistantSessionState.IDLE)

        assertEquals("", presentation.label)
        assertTrue(presentation.showVoiceIcon)
    }

    @Test
    fun `non-idle presentations replace text and clear voice icon`() {
        val expectedLabels = mapOf(
            AssistantSessionState.LISTENING to "LISTEN",
            AssistantSessionState.PROCESSING to "WAIT",
            AssistantSessionState.SUCCESS to "DONE",
            AssistantSessionState.ERROR to "ERROR"
        )

        expectedLabels.forEach { (state, label) ->
            val presentation = assistantPushToTalkPresentation(state)

            assertEquals(label, presentation.label)
            assertFalse(presentation.showVoiceIcon)
        }
    }

    @Test
    fun `missing or unconfigured status uses the idle presentation`() {
        val idle = AssistantPushToTalkPresentation("", showVoiceIcon = true)

        assertEquals(idle, assistantPushToTalkPresentation(null))
        assertEquals(idle, assistantPushToTalkPresentation(AssistantSessionState.UNCONFIGURED))
    }
}
