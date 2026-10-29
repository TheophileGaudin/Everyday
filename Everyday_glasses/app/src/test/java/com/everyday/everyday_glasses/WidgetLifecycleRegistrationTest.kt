package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetLifecycleController
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.system_status.StatusBarPlugin
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetLifecycleRegistrationTest {
    @Test
    fun `central registrations must cover every descriptor`() {
        val controller = WidgetLifecycleController(
            runOnUiThread = { it() },
            onChanged = {},
            registry = WidgetRegistry()
        )

        WidgetDescriptors.all.forEach { descriptor ->
            controller.register(
                WidgetLifecycleRegistration(
                    descriptor = descriptor,
                    widgetType = BaseWidget::class,
                    stateType = Unit::class,
                    createDefault = if (descriptor.policy == WidgetInstancePolicy.SINGLETON) ({ null }) else null
                )
            )
        }

        controller.verifyComplete()
    }

    @Test
    fun `central registrations reject duplicate widget types`() {
        val controller = WidgetLifecycleController(
            runOnUiThread = { it() },
            onChanged = {},
            registry = WidgetRegistry()
        )
        val descriptor = WidgetDescriptors[StatusBarPlugin.TYPE]
        val registration = WidgetLifecycleRegistration(
            descriptor = descriptor,
            widgetType = BaseWidget::class,
            stateType = Unit::class,
            createDefault = { null }
        )

        controller.register(registration)

        assertThrows(IllegalStateException::class.java) {
            controller.register(registration)
        }
    }

    @Test
    fun `singleton registration needs a creation path`() {
        assertThrows(IllegalArgumentException::class.java) {
            WidgetLifecycleRegistration(
                descriptor = WidgetDescriptors[StatusBarPlugin.TYPE],
                widgetType = BaseWidget::class,
                stateType = Unit::class
            )
        }
    }
}
