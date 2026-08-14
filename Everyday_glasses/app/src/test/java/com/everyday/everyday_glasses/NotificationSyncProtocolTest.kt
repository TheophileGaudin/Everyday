package com.everyday.everyday_glasses

import com.everyday.shared.sync.PhoneNotificationItem
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.transport.TransportDecodeResult
import com.everyday.shared.transport.TransportMessage
import com.everyday.shared.transport.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSyncProtocolTest {
    @Test
    fun `notification snapshot round trips through sync protocol`() {
        val item = PhoneNotificationItem(
            key = "0|com.example|1|null|1000",
            packageName = "com.example",
            appLabel = "Example",
            title = "Title",
            text = "Body",
            subText = "Sub",
            postTime = 123L,
            importance = 3,
            isOngoing = false,
            isClearable = true,
            category = "msg"
        )
        val snapshot = SyncSnapshot(
            notifications = PhoneNotificationsSnapshot(
                items = listOf(item),
                listenerEnabled = true,
                capturedAtMs = 456L
            )
        )

        val decoded = (TransportProtocol.decode(
            TransportProtocol.encode(TransportMessage.SyncSnapshotMessage(snapshot))
        ) as TransportDecodeResult.Success).message as TransportMessage.SyncSnapshotMessage

        assertEquals(snapshot.notifications, decoded.snapshot.notifications)
        assertFalse(decoded.snapshot.isEmpty)
    }

    @Test
    fun `notification channel decodes in sync requests`() {
        val request = SyncRequest(channels = setOf(SyncChannel.NOTIFICATIONS), reason = "test")

        val decoded = (TransportProtocol.decode(
            TransportProtocol.encode(TransportMessage.SyncRequestMessage(request))
        ) as TransportDecodeResult.Success).message as TransportMessage.SyncRequestMessage

        assertEquals(setOf(SyncChannel.NOTIFICATIONS), decoded.request.channels)
        assertTrue(decoded.request.channels.contains(SyncChannel.NOTIFICATIONS))
    }
}
