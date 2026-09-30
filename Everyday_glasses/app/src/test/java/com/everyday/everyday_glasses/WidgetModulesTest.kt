package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.AppData
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.Place
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.runtime.StatusSnapshot
import com.everyday.everyday_glasses.widgets.system_status.StatusBarModule
import com.everyday.everyday_glasses.widgets.system_status.StatusBarWidget
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxModule
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetModulesTest {
    private val services = FakeServices()
    private var statusBar: StatusBarWidget? = null
    private val module = StatusBarModule(services) { statusBar }
    private val data = StatusSnapshot(
        isPhoneConnected = true,
        phoneBatteryPercent = 80,
        glassesBatteryPercent = 55,
        time = "12:34",
        date = "Sat 26 Sep 2026"
    )

    @Test
    fun `a status bar opened after its data arrived shows it right away`() {
        services.appData.publish(AppChannels.status, data)
        assertEquals(0, services.transientChanges)

        val widget = module.lifecycle.createDefault!!()!!

        assertEquals("12:34", widget.timeString)
        assertEquals(80, widget.phoneBattery)
    }

    @Test
    fun `status data asks for a redraw only when it changes what is shown`() {
        statusBar = module.lifecycle.createDefault!!()

        services.appData.publish(AppChannels.status, data)
        services.appData.publish(AppChannels.status, data)

        assertEquals(1, services.transientChanges)
    }

    @Test
    fun `app data keeps the latest value and tells subscribers in the order they subscribed`() {
        val appData = AppData()
        val heard = mutableListOf<String>()
        appData.subscribe(AppChannels.place) { heard += "first ${it.town}" }
        appData.subscribe(AppChannels.place) { heard += "second ${it.town}" }

        appData.publish(AppChannels.place, Place("Leeds", "GB"))

        assertEquals(listOf("first Leeds", "second Leeds"), heard)
        assertEquals(Place("Leeds", "GB"), appData.latest(AppChannels.place))
        assertNull(appData.latest(AppChannels.weather))
    }

    @Test
    fun `a module registration takes its descriptor from the widget type`() {
        assertEquals(WidgetTypeId.STATUS, module.lifecycle.descriptor.type)
    }

    @Test
    fun `a text box opened from the menu is added and saved`() {
        TextBoxModule(services).open(100f, 100f)

        assertEquals(1, services.opened.size)
        assertEquals(1, services.persistentMutations)
    }

    @Test
    fun `the last closed text box is reopened instead of a new one`() {
        services.closedTemplate = TextBoxWidget(10f, 10f, 200f, 100f)

        TextBoxModule(services).open(100f, 100f)

        assertEquals(0, services.opened.size)
        assertNull(services.closedTemplate)
        assertEquals(1, services.persistentMutations)
    }

    private class FakeServices : DashboardServices {
        var transientChanges = 0
        var persistentMutations = 0
        var closedTemplate: BaseWidget? = null
        val opened = mutableListOf<BaseWidget>()
        override val viewportWidth = 640
        override val viewportHeight = 480
        override val isDisplayOff = false
        override val captureRestore = WidgetCaptureRestore()
        override val appData = AppData()
        override fun notifyTransientChange() {
            transientChanges++
        }
        override fun <T> withTransientChanges(action: () -> T): T = action()
        override fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean) = action()
        override fun <T> runPersistentMutation(changed: (T) -> Boolean, action: () -> T): T =
            action().also { if (changed(it)) persistentMutations++ }
        override fun <W : BaseWidget> open(type: WidgetTypeId, create: () -> W?): W? =
            create()?.also { opened += it }
        override fun openClosedTemplate(type: WidgetTypeId): BaseWidget? =
            closedTemplate.also { closedTemplate = null }
        override fun close(widget: BaseWidget) = Unit
    }
}