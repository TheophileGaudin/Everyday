package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.map.destinationIndicatorIsOffscreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapDestinationIndicatorTest {
    @Test
    fun destinationUsesAnEdgeArrowOnlyOutsideTheSelectedZoomRadius() {
        assertFalse(destinationIndicatorIsOffscreen(399.9f, 400f))
        assertFalse(destinationIndicatorIsOffscreen(400f, 400f))
        assertTrue(destinationIndicatorIsOffscreen(400.1f, 400f))
        assertTrue(destinationIndicatorIsOffscreen(8_000f, 400f))
    }
}
