package com.everyday.everyday_glasses

import android.graphics.Canvas
import com.everyday.everyday_glasses.data_models.WidgetDescriptors
import com.everyday.everyday_glasses.data_models.WidgetDocument
import com.everyday.everyday_glasses.data_models.WidgetTypeId
import com.everyday.everyday_glasses.managers.WidgetLifecycleController
import com.everyday.everyday_glasses.managers.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.managers.WidgetRegistry
import com.everyday.everyday_glasses.ui.BaseWidget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLifecycleControllerTest {
    @Test
    fun `singleton creation and close each notify and teardown exactly once`() {
        val registry = WidgetRegistry()
        var changes = 0
        var teardowns = 0
        val controller = controller(registry) { changes++ }
        controller.register(
            WidgetLifecycleRegistration(
                descriptor = WidgetDescriptors[WidgetTypeId.STATUS],
                widgetType = FakeWidget::class,
                stateType = String::class,
                current = { registry.singleton(WidgetTypeId.STATUS) as? FakeWidget },
                createDefault = { FakeWidget(WidgetTypeId.STATUS) },
                register = { registry.add(it) },
                unregister = { registry.remove(it) },
                teardown = { teardowns++ }
            )
        )

        val widget = controller.show(WidgetTypeId.STATUS)!!
        assertSame(widget, controller.show(WidgetTypeId.STATUS))
        assertEquals(1, changes)

        controller.close(widget)
        assertNull(registry.singleton(WidgetTypeId.STATUS))
        assertEquals(1, teardowns)
        assertEquals(2, changes)
    }

    @Test
    fun `record restore and capture use codec and preserve stable id`() {
        val registry = WidgetRegistry()
        var changes = 0
        val controller = controller(registry) { changes++ }
        val codec = StringCodec(WidgetTypeId.TEXT)
        controller.register(
            WidgetLifecycleRegistration(
                descriptor = WidgetDescriptors[WidgetTypeId.TEXT],
                widgetType = FakeWidget::class,
                stateType = String::class,
                stateCodec = codec,
                restore = { value -> FakeWidget(WidgetTypeId.TEXT, value) },
                capture = FakeWidget::value,
                register = { registry.add(it) },
                unregister = { registry.remove(it) }
            )
        )
        val record = WidgetDocument.PersistedWidgetRecord(
            type = WidgetTypeId.TEXT,
            instanceId = "text#12",
            state = JSONObject().put("value", "restored")
        )

        val widget = controller.restore(record) as FakeWidget

        assertEquals("restored", widget.value)
        assertEquals(1, changes)
        assertEquals("text#12", registry.stableId(widget))
        assertEquals(record.instanceId, controller.captureRecord(widget, registry.stableId(widget))?.instanceId)
        assertEquals("restored", controller.captureRecord(widget, registry.stableId(widget))?.state?.getString("value"))
    }

    @Test
    fun `close all batches notification and tears down every instance`() {
        val registry = WidgetRegistry()
        var changes = 0
        var teardowns = 0
        val controller = controller(registry) { changes++ }
        controller.register(
            WidgetLifecycleRegistration(
                descriptor = WidgetDescriptors[WidgetTypeId.TEXT],
                widgetType = FakeWidget::class,
                stateType = String::class,
                register = { registry.add(it) },
                unregister = { registry.remove(it) },
                teardown = { teardowns++ }
            )
        )
        controller.open(WidgetTypeId.TEXT) { FakeWidget(WidgetTypeId.TEXT) }
        controller.open(WidgetTypeId.TEXT) { FakeWidget(WidgetTypeId.TEXT) }
        assertEquals(2, changes)

        controller.closeAll()

        assertTrue(registry.widgets(WidgetTypeId.TEXT).isEmpty())
        assertEquals(2, teardowns)
        assertEquals(3, changes)
    }

    @Test
    fun `fullscreen and minimize ownership remain coherent`() {
        val registry = WidgetRegistry()
        var changes = 0
        val controller = controller(registry) { changes++ }
        controller.register(
            WidgetLifecycleRegistration(
                descriptor = WidgetDescriptors[WidgetTypeId.TEXT],
                widgetType = FakeWidget::class,
                stateType = String::class,
                register = { registry.add(it) },
                unregister = { registry.remove(it) }
            )
        )
        val first = controller.open(WidgetTypeId.TEXT) { FakeWidget(WidgetTypeId.TEXT) }!!
        val second = controller.open(WidgetTypeId.TEXT) { FakeWidget(WidgetTypeId.TEXT) }!!
        assertEquals(2, changes)

        controller.enterFullscreen(first)
        assertEquals(3, changes)
        assertSame(first, registry.fullscreenWidget)
        controller.enterFullscreen(second)
        assertEquals(4, changes)
        assertFalse(first.isFullscreen)
        assertSame(second, registry.fullscreenWidget)

        second.minimize()
        assertEquals(5, changes)
        assertTrue(second.isMinimized)
        assertNull(registry.fullscreenWidget)
        second.toggleMinimize()
        assertEquals(6, changes)
        assertFalse(second.isMinimized)
        assertTrue(second.isFullscreen)
        assertSame(second, registry.fullscreenWidget)

        controller.closeAll()
        assertEquals(7, changes)
    }

    private fun controller(registry: WidgetRegistry, onChanged: () -> Unit) =
        WidgetLifecycleController({ action -> action() }, onChanged, registry)

    private class StringCodec(override val type: WidgetTypeId) : WidgetDocument.WidgetStateCodec {
        override val stateClass = String::class
        override fun encode(state: Any): JSONObject = JSONObject().put("value", state as String)
        override fun decode(payload: JSONObject): Any = payload.getString("value")
    }

    private class FakeWidget(type: WidgetTypeId, val value: String = "") :
        BaseWidget(type, 0f, 0f, 10f, 10f) {
        override fun draw(canvas: Canvas) = Unit
    }
}
