package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationsWidgetTest {
    @Test
    fun `display count follows one third screen rule`() {
        assertEquals(3, NotificationsWidget.computeDisplayCount(widgetHeight = 480f, screenHeight = 480f))
        assertEquals(2, NotificationsWidget.computeDisplayCount(widgetHeight = 240f, screenHeight = 480f))
        assertEquals(1, NotificationsWidget.computeDisplayCount(widgetHeight = 120f, screenHeight = 480f))
        assertEquals(3, NotificationsWidget.computeDisplayCount(widgetHeight = 900f, screenHeight = 480f))
    }
}
