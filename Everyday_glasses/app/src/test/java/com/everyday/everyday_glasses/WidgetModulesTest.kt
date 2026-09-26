package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.system_status.StatusBarDisplayState
import com.everyday.everyday_glasses.widgets.system_status.StatusBarModule
import com.everyday.everyday_glasses.widgets.system_status.StatusBarWidget
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetModulesTest {
    private val services = FakeServices()
    private var statusBar: StatusBarWidget? = null
    private val module = StatusBarModule(services) { statusBar }
    private val data = StatusBarDisplayState(
        isPhoneConnected = true,
        phoneBatteryPercent = 80,
        glassesBatteryPercent = 55,
        time = "12:34",
        date = "Sat 26 Sep 2026"
    )

    @Test
    fun `a status bar opened after its data arrived shows it right away`() {
        module.update(data)
        assertEquals(0, services.transientChanges)

        val widget = module.lifecycle.createDefault!!()!!

        assertEquals("12:34", widget.timeString)
        assertEquals(80, widget.phoneBattery)
    }

    @Test
    fun `status data asks for a redraw only when it changes what is shown`() {
        statusBar = module.lifecycle.createDefault!!()

        module.update(data)
        module.update(data)

        assertEquals(1, services.transientChanges)
    }

    @Test
    fun `a module registration takes its descriptor from the widget type`() {
        assertEquals(WidgetTypeId.STATUS, module.lifecycle.descriptor.type)
    }

    private class FakeServices : DashboardServices {
        var transientChanges = 0
        override val viewportWidth = 640
        override val viewportHeight = 480
        override val isDisplayOff = false
        override val captureRestore = WidgetCaptureRestore()
        override fun notifyTransientChange() {
            transientChanges++
        }
        override fun <T> withTransientChanges(action: () -> T): T = action()
        override fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean) = action()
    }
}