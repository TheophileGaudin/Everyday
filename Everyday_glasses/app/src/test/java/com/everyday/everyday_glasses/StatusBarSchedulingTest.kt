package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.widgets.system_status.delayUntilNextMinute
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBarSchedulingTest {
    @Test
    fun `minute delay aligns to next boundary`() {
        assertEquals(60_000L, delayUntilNextMinute(120_000L))
        assertEquals(59_999L, delayUntilNextMinute(120_001L))
        assertEquals(1L, delayUntilNextMinute(179_999L))
    }
}
