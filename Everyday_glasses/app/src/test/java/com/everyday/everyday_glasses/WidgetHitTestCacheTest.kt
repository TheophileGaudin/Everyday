package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetHitTestCacheTest {
    private data class Widget(val id: String, var zOrder: Int, var eligible: Boolean = true)

    @Test
    fun `hit testing returns the topmost eligible widget without caching eligibility`() {
        val widgets = mutableListOf(
            Widget("back", 1),
            Widget("front", 2)
        )
        val cache = WidgetHitTestCache({ widgets }) { it.zOrder }

        assertEquals("front", cache.findFirstEligible { it.eligible }?.id)
        widgets[1].eligible = false
        assertEquals("back", cache.findFirstEligible { it.eligible }?.id)
        widgets[0].eligible = false
        assertNull(cache.findFirstEligible { it.eligible })
    }

    @Test
    fun `membership replacement and bring to front update hit test order after invalidation`() {
        val back = Widget("back", 1)
        val front = Widget("front", 2)
        val widgets = mutableListOf(back, front)
        val cache = WidgetHitTestCache({ widgets }) { it.zOrder }

        assertEquals("front", cache.findFirstEligible { it.eligible }?.id)

        val added = Widget("added", 3)
        widgets += added
        cache.invalidate()
        assertEquals("added", cache.findFirstEligible { it.eligible }?.id)

        widgets.remove(added)
        cache.invalidate()
        assertEquals("front", cache.findFirstEligible { it.eligible }?.id)

        back.zOrder = 4
        cache.invalidate()
        assertEquals("back", cache.findFirstEligible { it.eligible }?.id)

        widgets[0] = Widget("replacement", 5)
        cache.invalidate()
        assertEquals("replacement", cache.findFirstEligible { it.eligible }?.id)

        widgets.clear()
        widgets += Widget("bulk layout", 6)
        cache.invalidate()
        assertEquals("bulk layout", cache.findFirstEligible { it.eligible }?.id)
    }
}
