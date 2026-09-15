package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.managers.WakeSleepManager
import com.everyday.everyday_glasses.managers.resolveDisplayState
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeSleepPolicyTest {
    @Test
    fun `sleep without pinned widgets resolves to off`() {
        assertEquals(
            WakeSleepManager.DisplayState.OFF,
            resolveDisplayState(WakeSleepManager.DisplayState.SLEEP, hasPinnedWidgets = false)
        )
    }

    @Test
    fun `sleep with pinned widgets remains sleep`() {
        assertEquals(
            WakeSleepManager.DisplayState.SLEEP,
            resolveDisplayState(WakeSleepManager.DisplayState.SLEEP, hasPinnedWidgets = true)
        )
    }
}
