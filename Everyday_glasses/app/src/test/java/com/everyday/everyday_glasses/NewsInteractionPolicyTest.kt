package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.ui.widgets.helpers.NewsDoubleTapAction
import com.everyday.everyday_glasses.ui.widgets.helpers.NewsInteractionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsInteractionPolicyTest {
    @Test
    fun `windowed and fullscreen double taps have distinct behavior`() {
        assertEquals(
            NewsDoubleTapAction.OPEN_SOURCE_PANEL,
            NewsInteractionPolicy.doubleTapAction(isFullscreen = false, sourcePanelOpen = false)
        )
        assertEquals(
            NewsDoubleTapAction.CLOSE_SOURCE_PANEL,
            NewsInteractionPolicy.doubleTapAction(isFullscreen = false, sourcePanelOpen = true)
        )
        assertEquals(
            NewsDoubleTapAction.EXIT_FULLSCREEN,
            NewsInteractionPolicy.doubleTapAction(isFullscreen = true, sourcePanelOpen = false)
        )
        assertEquals(
            NewsDoubleTapAction.EXIT_FULLSCREEN,
            NewsInteractionPolicy.doubleTapAction(isFullscreen = true, sourcePanelOpen = true)
        )
    }

    @Test
    fun `fullscreen options hit target is gated to fullscreen and does not leak outside`() {
        assertTrue(NewsInteractionPolicy.isFullscreenOptionsHit(true, 110f, 120f, 100f, 100f, 140f, 140f))
        assertFalse(NewsInteractionPolicy.isFullscreenOptionsHit(true, 140f, 120f, 100f, 100f, 140f, 140f))
        assertFalse(NewsInteractionPolicy.isFullscreenOptionsHit(false, 110f, 120f, 100f, 100f, 140f, 140f))
    }
}
