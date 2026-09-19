package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.contracts.WidgetDescriptors
import com.everyday.everyday_glasses.contracts.WidgetInstancePolicy
import com.everyday.everyday_glasses.contracts.WidgetTypeId
import com.everyday.everyday_glasses.runtime.WidgetLifecycleController
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.ui.widgets.BaseWidget
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
        val descriptor = WidgetDescriptors[WidgetTypeId.STATUS]
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
                descriptor = WidgetDescriptors[WidgetTypeId.STATUS],
                widgetType = BaseWidget::class,
                stateType = Unit::class
            )
        }
    }
}
