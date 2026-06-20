package com.everyday.everyday_glasses

import com.everyday.shared.sync.PhoneNotificationItem
import com.everyday.shared.sync.PhoneNotificationsSnapshot
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncProtocol
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
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

        val decoded = SyncProtocol.decodeSnapshot(SyncProtocol.encodeSnapshot(snapshot))

        assertEquals(snapshot.notifications, decoded?.notifications)
        assertFalse(decoded?.isEmpty ?: true)
    }

    @Test
    fun `notification channel decodes in sync requests`() {
        val request = SyncRequest(channels = setOf(SyncChannel.NOTIFICATIONS), reason = "test")

        val decoded = SyncProtocol.decodeRequest(SyncProtocol.encodeRequest(request))

        assertEquals(setOf(SyncChannel.NOTIFICATIONS), decoded?.channels)
        assertTrue(decoded?.channels?.contains(SyncChannel.NOTIFICATIONS) == true)
    }
}
