package com.everyday.everyday_glasses.video

import com.everyday.everyday_glasses.widgets.file_browser.video_backend.PlaybackProgress
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.resolveResumePosition
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.resolveSeekTarget
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.shouldAutoHidePlayerControls
import com.everyday.everyday_glasses.widgets.file_browser.video_backend.shouldAutoSelectEmbeddedSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerPolicyTest {

    @Test
    fun resumePolicySkipsShortAndNearlyFinishedProgress() {
        assertEquals(
            0L,
            resolveResumePosition(
                PlaybackProgress(1L, "video://one", 29_999L, 120_000L, false, 0L),
                autoResumeEnabled = true,
            ),
        )
        assertEquals(
            0L,
            resolveResumePosition(
                PlaybackProgress(1L, "video://one", 114_000L, 120_000L, false, 0L),
                autoResumeEnabled = true,
            ),
        )
    }

    @Test
    fun resumePolicyKeepsMeaningfulIncompleteProgress() {
        assertEquals(
            60_000L,
            resolveResumePosition(
                PlaybackProgress(1L, "video://one", 60_000L, 120_000L, false, 0L),
                autoResumeEnabled = true,
            ),
        )
    }

    @Test
    fun seekAndControlPoliciesClampAndRespectOverlays() {
        assertEquals(0L, resolveSeekTarget(2_000L, 10_000L, -10_000L))
        assertEquals(10_000L, resolveSeekTarget(9_000L, 10_000L, 10_000L))
        assertTrue(shouldAutoHidePlayerControls(true, true, false, false))
        assertFalse(shouldAutoHidePlayerControls(true, true, true, false))
        assertTrue(shouldAutoSelectEmbeddedSubtitle(true, true, false, true))
        assertFalse(shouldAutoSelectEmbeddedSubtitle(false, true, true, true))
    }
}
