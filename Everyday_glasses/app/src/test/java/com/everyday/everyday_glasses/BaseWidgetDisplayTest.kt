package com.everyday.everyday_glasses

import android.graphics.Canvas
import com.everyday.everyday_glasses.runtime.WakeSleepManager.DisplayState
import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.runtime.WidgetLifecycleController
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseWidgetDisplayTest {
    @Test
    fun `widgets are hidden when off, only pinned ones show while asleep, all unminimized ones while awake`() {
        val widget = FakeWidget()

        widget.isPinned = true
        assertFalse(widget.isVisibleIn(DisplayState.OFF))
        assertTrue(widget.isVisibleIn(DisplayState.SLEEP))
        assertTrue(widget.isVisibleIn(DisplayState.WAKE))

        widget.isPinned = false
        assertFalse(widget.isVisibleIn(DisplayState.SLEEP))
        assertTrue(widget.isVisibleIn(DisplayState.WAKE))

        widget.minimize()
        assertFalse(widget.isVisibleIn(DisplayState.WAKE))
    }

    @Test
    fun `refreshing display visibility reports the rule for the current display state`() {
        val widget = FakeWidget()
        widget.isPinned = false
        widget.glassesDisplayState = DisplayState.SLEEP

        widget.refreshDisplayVisibility()

        assertEquals(listOf(false), widget.reportedVisibility)
    }

    @Test
    fun `registration installs the current display state`() {
        val registry = WidgetRegistry()
        val controller = WidgetLifecycleController(
            runOnUiThread = { action -> action() },
            onChanged = {},
            registry = registry,
            displayState = { DisplayState.SLEEP },
        )
        controller.register(
            WidgetLifecycleRegistration(
                descriptor = WidgetDescriptors[TextBoxPlugin.TYPE],
                widgetType = FakeWidget::class,
                stateType = String::class
            )
        )

        val widget = controller.open(TextBoxPlugin.TYPE) { FakeWidget() }!!

        assertEquals(DisplayState.SLEEP, widget.glassesDisplayState)
    }

    private class FakeWidget : BaseWidget(TextBoxPlugin.TYPE, 0f, 0f, 10f, 10f) {
        val reportedVisibility = mutableListOf<Boolean>()
        override fun draw(canvas: Canvas) = Unit
        override fun onDisplayVisibilityChanged(visible: Boolean) {
            reportedVisibility += visible
        }
    }
}