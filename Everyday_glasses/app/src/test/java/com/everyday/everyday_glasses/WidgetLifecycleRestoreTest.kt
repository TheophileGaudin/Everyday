package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.system_status.StatusBarPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetLifecycleRestoreTest {
    @Test
    fun `restore delegates to registration exactly once`() {
        val restored = mutableListOf<String>()
        val registration = WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[StatusBarPlugin.TYPE],
            widgetType = BaseWidget::class,
            stateType = String::class,
            restore = { state -> restored.add(state); null }
        )

        registration.restoreCandidate("saved state")

        assertEquals(listOf("saved state"), restored)
    }

    @Test
    fun `restore rejects a state of the wrong type`() {
        val registration = WidgetLifecycleRegistration(
            descriptor = WidgetDescriptors[StatusBarPlugin.TYPE],
            widgetType = BaseWidget::class,
            stateType = String::class,
            restore = { null }
        )

        assertThrows(IllegalArgumentException::class.java) {
            registration.restoreCandidate(42)
        }
    }
}
