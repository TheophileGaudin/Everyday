package com.everyday.everyday_glasses

import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleControlAction
import com.everyday.shared.sync.SubtitleOptions
import com.everyday.shared.sync.SubtitleProtocol
import com.everyday.shared.sync.SubtitleSource
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleStatusState
import com.everyday.shared.sync.SubtitleTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleProtocolTest {

    @Test
    fun controlRoundTripPreservesOptions() {
        val control = SubtitleControl(
            action = SubtitleControlAction.START,
            options = SubtitleOptions(
                source = SubtitleSource.PHONE_PLAYBACK,
                languageTag = "en-US",
                phonePlaybackEnabled = true,
                microphoneEnabled = false,
                translationEnabled = false
            ),
            requestedAtMs = 123L
        )

        val decoded = SubtitleProtocol.decodeControl(SubtitleProtocol.encodeControl(control))

        assertEquals(SubtitleControlAction.START, decoded?.action)
        assertEquals(SubtitleSource.PHONE_PLAYBACK, decoded?.options?.source)
        assertEquals("en-US", decoded?.options?.languageTag)
        assertTrue(decoded?.options?.phonePlaybackEnabled == true)
        assertFalse(decoded?.options?.microphoneEnabled == true)
        assertFalse(decoded?.options?.translationEnabled == true)
        assertEquals(123L, decoded?.requestedAtMs)
    }

    @Test
    fun statusRoundTripPreservesStateAndMessage() {
        val status = SubtitleStatus(
            state = SubtitleStatusState.PERMISSION_NEEDED,
            message = "Playback capture permission needed",
            sessionId = "session",
            timestampMs = 456L
        )

        val decoded = SubtitleProtocol.decodeStatus(SubtitleProtocol.encodeStatus(status))

        assertEquals(SubtitleStatusState.PERMISSION_NEEDED, decoded?.state)
        assertEquals("Playback capture permission needed", decoded?.message)
        assertEquals("session", decoded?.sessionId)
        assertEquals(456L, decoded?.timestampMs)
    }

    @Test
    fun transcriptRoundTripPreservesSegment() {
        val transcript = SubtitleTranscript(
            sessionId = "session",
            segmentId = 7L,
            text = "hello world",
            isFinal = true,
            timestampMs = 789L
        )

        val decoded = SubtitleProtocol.decodeTranscript(SubtitleProtocol.encodeTranscript(transcript))

        assertEquals("session", decoded?.sessionId)
        assertEquals(7L, decoded?.segmentId)
        assertEquals("hello world", decoded?.text)
        assertTrue(decoded?.isFinal == true)
        assertEquals(789L, decoded?.timestampMs)
    }

    @Test
    fun decodersIgnoreOtherEvents() {
        val other = """{"event":"sync_snapshot"}"""

        assertNull(SubtitleProtocol.decodeControl(other))
        assertNull(SubtitleProtocol.decodeStatus(other))
        assertNull(SubtitleProtocol.decodeTranscript(other))
    }
}
