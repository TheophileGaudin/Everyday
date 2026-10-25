package com.everyday.everyday_glasses

import android.graphics.Canvas
import com.everyday.everyday_glasses.widgets.speedometer.SpeedometerWidgetState
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.speedometer.SpeedometerPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetCaptureRestoreTest {
    private val captureRestore = WidgetCaptureRestore()

    @Test
    fun `saved bounds are clamped to the screen`() {
        val widget = FakeWidget()

        captureRestore.restoreCommonFields(
            widget,
            SpeedometerWidgetState(x = 600f, y = -20f, width = 200f, height = 100f),
            screenWidth = 640,
            screenHeight = 480
        )

        assertEquals(440f, widget.x)
        assertEquals(0f, widget.y)
        assertEquals(200f, widget.widgetWidth)
        assertEquals(100f, widget.widgetHeight)
    }

    @Test
    fun `own fields are restored before pinned and fullscreen state`() {
        val widget = FakeWidget()
        var wasFullscreenWhileRestoringOwnFields: Boolean? = null

        captureRestore.restoreCommonFields(
            widget,
            SpeedometerWidgetState(
                x = 10f, y = 10f, width = 100f, height = 50f,
                isFullscreen = true, isPinned = false
            ),
            screenWidth = 640,
            screenHeight = 480
        ) { wasFullscreenWhileRestoringOwnFields = widget.isFullscreen }

        assertEquals(false, wasFullscreenWhileRestoringOwnFields)
        assertTrue(widget.isFullscreen)
        assertFalse(widget.isPinned)
    }

    @Test
    fun `a minimized widget is not restored fullscreen`() {
        val widget = FakeWidget()

        captureRestore.restoreCommonFields(
            widget,
            SpeedometerWidgetState(
                x = 0f, y = 416f, width = 100f, height = 50f,
                isMinimized = true, isFullscreen = true,
                savedMinX = 50f, savedMinY = 60f, savedMinWidth = 200f, savedMinHeight = 120f
            ),
            screenWidth = 640,
            screenHeight = 480
        )

        assertTrue(widget.isMinimized)
        assertFalse(widget.isFullscreen)
        assertEquals(200f, widget.savedMinWidth)
    }

    private class FakeWidget : BaseWidget(SpeedometerPlugin.TYPE, 0f, 0f, 10f, 10f) {
        override fun draw(canvas: Canvas) = Unit
    }
}