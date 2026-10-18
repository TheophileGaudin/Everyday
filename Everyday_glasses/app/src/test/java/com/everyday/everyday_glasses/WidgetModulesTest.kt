package com.everyday.everyday_glasses

import android.graphics.PointF
import com.everyday.everyday_glasses.runtime.WidgetHost
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.AppData
import com.everyday.everyday_glasses.runtime.AppQueries
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.UiThreadDispatcher
import com.everyday.everyday_glasses.settings.SettingsController
import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.everyday.everyday_glasses.settings.UnitSettings
import com.everyday.everyday_glasses.runtime.Place
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.runtime.StatusSnapshot
import com.everyday.everyday_glasses.widgets.system_status.StatusBarPlugin
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxPlugin
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetModulesTest {
    private val services = FakeServices()
    private val module = StatusBarPlugin(services)
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
        services.singletonWidget = module.lifecycle.createDefault!!()

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
    fun `values published together go through the dashboard's grouping once`() {
        val appData = AppData()
        val events = mutableListOf<String>()
        appData.grouping = { publishes -> events += "start"; publishes(); events += "end" }
        appData.subscribe(AppChannels.place) { events += it.town }

        appData.publishTogether {
            appData.publish(AppChannels.place, Place("Leeds", "GB"))
            appData.publish(AppChannels.place, Place("York", "GB"))
        }

        assertEquals(listOf("start", "Leeds", "York", "end"), events)
    }

    @Test
    fun `a module registration takes its descriptor from the widget type`() {
        assertEquals(WidgetTypeId.STATUS, module.lifecycle.descriptor.type)
    }

    @Test
    fun `a question gets the current answer of whoever answers it`() {
        val appData = AppData()
        assertNull(appData.ask(AppQueries.newsCountry))

        var country = "GB"
        appData.answer(AppQueries.newsCountry) { country }
        country = "FR"

        assertEquals("FR", appData.ask(AppQueries.newsCountry))
    }

    @Test
    fun `a text box opened from the menu is added and saved`() {
        TextBoxPlugin(services).open()

        assertEquals(1, services.opened.size)
        assertEquals(1, services.persistentMutations)
    }

    @Test
    fun `the last closed text box is reopened instead of a new one`() {
        services.closedTemplate = TextBoxWidget(10f, 10f, 200f, 100f)

        TextBoxPlugin(services).open()

        assertEquals(0, services.opened.size)
        assertNull(services.closedTemplate)
        assertEquals(1, services.persistentMutations)
    }

    private class FakeServices : WidgetContext {
        var transientChanges = 0
        var persistentMutations = 0
        var closedTemplate: BaseWidget? = null
        val opened = mutableListOf<BaseWidget>()
        override val viewportWidth = 640
        override val viewportHeight = 480
        override val isDisplayOff = false
        override val captureRestore = WidgetCaptureRestore()
        override val appData = AppData()
        override val units: UnitSettings get() = error("No unit settings in this test")
        override val host: WidgetHost? = null
        override val creationPosition: PointF? = null
        override fun dismissPresentationOf(widget: BaseWidget) = Unit
        override fun onKeyFocusChanged(widget: BaseWidget, focused: Boolean) = Unit
        override val androidContext: Context get() = error("No Android context in this test")
        override val rootLayout: ViewGroup? = null
        override val dashboardView: View get() = error("No dashboard view in this test")
        override val ui = UiThreadDispatcher { action -> action(); true }
        override val settings: SettingsController get() = error("No settings in this test")
        var singletonWidget: BaseWidget? = null
        override fun singleton(type: WidgetTypeId): BaseWidget? = singletonWidget
        override fun widgets(type: WidgetTypeId): List<BaseWidget> = emptyList()
        override fun stableId(widget: BaseWidget): String = "test"
        override fun requestRepaint() = Unit
        override fun notifyMediaFrame(sourceId: String) = Unit
        override fun setMediaSourceActive(sourceId: String, active: Boolean) = Unit
        override fun refreshBrightnessRules() = Unit
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