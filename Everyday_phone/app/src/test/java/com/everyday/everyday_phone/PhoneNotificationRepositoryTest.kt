package com.everyday.everyday_phone

import android.app.Notification
import com.everyday.shared.sync.PhoneNotificationItem
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNotificationRepositoryTest {
    @After
    fun tearDown() {
        PhoneNotificationRepository.clearForTests()
    }

    @Test
    fun `filter hides own package system noise and ongoing services`() {
        assertFalse(
            PhoneNotificationRepository.shouldShow(
                item(packageName = "com.everyday.everyday_phone"),
                "com.everyday.everyday_phone"
            )
        )
        assertFalse(
            PhoneNotificationRepository.shouldShow(
                item(packageName = "android"),
                "com.everyday.everyday_phone"
            )
        )
        assertFalse(
            PhoneNotificationRepository.shouldShow(
                item(isOngoing = true, isClearable = false, category = Notification.CATEGORY_SERVICE),
                "com.everyday.everyday_phone"
            )
        )
        assertTrue(
            PhoneNotificationRepository.shouldShow(
                item(),
                "com.everyday.everyday_phone"
            )
        )
    }

    private fun item(
        key: String = "key",
        packageName: String = "com.example.app",
        appLabel: String = "Example",
        title: String = "Title",
        text: String = "Text",
        postTime: Long = 1L,
        isOngoing: Boolean = false,
        isClearable: Boolean = true,
        category: String? = null
    ): PhoneNotificationItem =
        PhoneNotificationItem(
            key = key,
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            subText = "",
            postTime = postTime,
            importance = 3,
            isOngoing = isOngoing,
            isClearable = isClearable,
            category = category
        )
}
