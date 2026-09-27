package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.system_status.StatusBarDisplayState
import com.everyday.everyday_glasses.widgets.system_status.StatusBarModule
import com.everyday.everyday_glasses.widgets.system_status.StatusBarWidget
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxModule
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import org.junit.Assert.assertSame
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

    @Test
    fun `a text box opened from the menu is focused and saved`() {
        val focused = mutableListOf<TextBoxWidget>()
        val texts = TextBoxModule(services, focus = { focused += it }, releaseFocus = {})

        texts.open(100f, 100f)

        assertEquals(1, services.opened.size)
        assertSame(services.opened.single(), focused.single())
        assertEquals(1, services.persistentMutations)
    }

    @Test
    fun `the last closed text box is reopened instead of a new one`() {
        val closed = TextBoxWidget(10f, 10f, 200f, 100f)
        services.closedTemplate = closed
        val focused = mutableListOf<TextBoxWidget>()
        val texts = TextBoxModule(services, focus = { focused += it }, releaseFocus = {})

        texts.open(100f, 100f)

        assertEquals(0, services.opened.size)
        assertSame(closed, focused.single())
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