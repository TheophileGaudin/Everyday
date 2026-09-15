package com.everyday.everyday_glasses.helpers

internal enum class NewsDoubleTapAction {
    OPEN_SOURCE_PANEL,
    CLOSE_SOURCE_PANEL,
    EXIT_FULLSCREEN
}

/** Pure interaction decisions kept separate so fullscreen and panel hit targets stay testable. */
internal object NewsInteractionPolicy {
    fun doubleTapAction(isFullscreen: Boolean, sourcePanelOpen: Boolean): NewsDoubleTapAction =
        when {
            isFullscreen -> NewsDoubleTapAction.EXIT_FULLSCREEN
            sourcePanelOpen -> NewsDoubleTapAction.CLOSE_SOURCE_PANEL
            else -> NewsDoubleTapAction.OPEN_SOURCE_PANEL
        }

    fun isFullscreenOptionsHit(
        isFullscreen: Boolean,
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Boolean = isFullscreen && x >= left && x < right && y >= top && y < bottom
}
