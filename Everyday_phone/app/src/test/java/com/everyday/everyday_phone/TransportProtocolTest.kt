package com.everyday.everyday_phone

import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleControlAction
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.transport.TransportDecodeResult
import com.everyday.shared.transport.TransportMessage
import com.everyday.shared.transport.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportProtocolTest {
    @Test
    fun `glasses to phone messages round trip with escaped text`() {
        val messages = listOf(
            TransportMessage.TextFieldFocus(true, "field-\"one\""),
            TransportMessage.DeviceStatus(glassesBatteryPercent = 77),
            TransportMessage.MirrorRequest(true),
            TransportMessage.ClipboardRequest,
            TransportMessage.GoogleAuthRequest,
            TransportMessage.GoogleAuthDisconnect,
            TransportMessage.SyncRequestMessage(
                SyncRequest(setOf(SyncChannel.FINANCE), true, "phone test", requestedAtMs = 12L)
            ),
            TransportMessage.SubtitleControlMessage(
                SubtitleControl(SubtitleControlAction.STOP, requestedAtMs = 13L)
            )
        )

        messages.forEach { message ->
            val decoded = TransportProtocol.decode(TransportProtocol.encode(message))
            assertTrue(decoded is TransportDecodeResult.Success)
            assertEquals(message, (decoded as TransportDecodeResult.Success).message)
        }
    }

    @Test
    fun `legacy and malformed glasses messages are rejected`() {
        val invalid = listOf(
            """{"action":"requestClipboard"}""",
            """{"textFieldFocused":true,"battery":50}""",
            """{"kind":"mirror.request","payload":{}}"""
        )
        invalid.forEach { assertTrue(TransportProtocol.decode(it) is TransportDecodeResult.Failure) }
    }
}
