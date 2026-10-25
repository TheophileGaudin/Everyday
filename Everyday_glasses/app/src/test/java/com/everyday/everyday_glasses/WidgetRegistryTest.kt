package com.everyday.everyday_glasses

import android.graphics.Canvas
import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRegistryTest {
    @Test
    fun `registry enforces singleton membership and canonical identity`() {
        val registry = WidgetRegistry()
        val status = fake(StatusBarPlugin.TYPE)

        assertEquals("status", registry.add(status))
        assertSame(status, registry.singleton(StatusBarPlugin.TYPE))
        assertSame(status, registry.findByStableId("status"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.add(fake(StatusBarPlugin.TYPE))
        }
    }

    @Test
    fun `multiple widget ids remain stable and advance past restored ids`() {
        val registry = WidgetRegistry()
        val first = fake(TextBoxPlugin.TYPE)
        val restored = fake(TextBoxPlugin.TYPE)
        val next = fake(TextBoxPlugin.TYPE)

        assertEquals("text#0", registry.add(first))
        assertEquals("text#7", registry.add(restored, "text#7"))
        assertEquals("text#8", registry.add(next))
        assertSame(restored, registry.findByStableId("text#7"))
        assertEquals("text#7", registry.stableId(restored))
    }

    @Test
    fun `descriptor and insertion ordering are deterministic snapshots`() {
        val registry = WidgetRegistry()
        val secondText = fake(TextBoxPlugin.TYPE)
        val status = fake(StatusBarPlugin.TYPE)
        val firstText = fake(TextBoxPlugin.TYPE)

        registry.add(secondText)
        registry.add(status)
        registry.add(firstText)

        val expected = WidgetDescriptors.all.flatMap { descriptor ->
            when (descriptor.type) {
                TextBoxPlugin.TYPE -> listOf(secondText, firstText)
                StatusBarPlugin.TYPE -> listOf(status)
                else -> emptyList()
            }
        }
        assertEquals(expected, registry.allWidgets())

        val textSnapshot = registry.widgets(TextBoxPlugin.TYPE)
        registry.remove(secondText)
        assertEquals(listOf(secondText, firstText), textSnapshot)
        assertEquals(listOf(firstText), registry.widgets(TextBoxPlugin.TYPE))
    }

    @Test
    fun `membership and z order mutations invalidate sorted and hit test caches`() {
        val registry = WidgetRegistry()
        val first = fake(TextBoxPlugin.TYPE)
        val second = fake(TextBoxPlugin.TYPE)
        registry.add(first)

        val cached = registry.getAllSortedByZOrder()
        assertSame(cached, registry.getAllSortedByZOrder())
        assertSame(first, registry.getWidgetAt(5f, 5f))

        registry.add(second)
        assertNotSame(cached, registry.getAllSortedByZOrder())
        assertSame(second, registry.getWidgetAt(5f, 5f))

        registry.bringToFront(first)
        assertSame(first, registry.getWidgetAt(5f, 5f))

        registry.remove(first)
        assertSame(second, registry.getWidgetAt(5f, 5f))
    }

    @Test
    fun `fullscreen ownership follows registered membership`() {
        val registry = WidgetRegistry()
        val first = fake(TextBoxPlugin.TYPE)
        val second = fake(TextBoxPlugin.TYPE)
        val unregistered = fake(TextBoxPlugin.TYPE)
        registry.add(first)
        registry.add(second)

        registry.setFullscreen(first)
        registry.clearFullscreen(second)
        assertSame(first, registry.fullscreenWidget)

        registry.setFullscreen(second)
        assertSame(second, registry.fullscreenWidget)
        assertThrows(IllegalArgumentException::class.java) {
            registry.setFullscreen(unregistered)
        }

        assertTrue(registry.remove(second))
        assertNull(registry.fullscreenWidget)
        assertFalse(registry.remove(second))
    }

    @Test
    fun `an absent widget is never the fullscreen owner`() {
        val registry = WidgetRegistry()
        val widget = fake(TextBoxPlugin.TYPE)
        registry.add(widget)

        // A missing singleton must not be mistaken for the fullscreen owner just because both
        // it and the owner are null: that made every fullscreen-only input branch fire on a
        // purely windowed dashboard and swallowed the app context menu.
        assertNull(registry.fullscreenWidget)
        assertFalse(registry.isFullscreen(null))
        assertFalse(registry.isFullscreen(widget))

        registry.setFullscreen(widget)
        assertTrue(registry.isFullscreen(widget))
        assertFalse(registry.isFullscreen(null))

        registry.clearFullscreen(widget)
        assertFalse(registry.isFullscreen(widget))
        assertFalse(registry.isFullscreen(null))
    }

    @Test
    fun `clear resets membership fullscreen and id allocation`() {
        val registry = WidgetRegistry()
        val widget = fake(TextBoxPlugin.TYPE)
        registry.add(widget)
        registry.setFullscreen(widget)

        registry.clear()

        assertTrue(registry.allWidgets().isEmpty())
        assertNull(registry.fullscreenWidget)
        assertFalse(registry.isRegistered(widget))
        assertEquals("text#0", registry.add(fake(TextBoxPlugin.TYPE)))
    }

    private fun fake(type: WidgetTypeId) = FakeWidget(type)

    private class FakeWidget(type: WidgetTypeId) : BaseWidget(type, 0f, 0f, 10f, 10f) {
        override fun draw(canvas: Canvas) = Unit
        override fun containsPoint(px: Float, py: Float): Boolean = px in 0f..10f && py in 0f..10f
    }
}
