package com.everyday.everyday_glasses.binocular

/**
 * Refresh modes for the binocular display system.
 * 
 * Different content types require different refresh strategies:
 * - Static content (text labels) needs no continuous refresh
 * - Interactive UI needs moderate refresh for responsiveness
 * - Video/WebView needs high-frequency refresh for smoothness
 * 
 * The BinocularView automatically selects the appropriate mode,
 * but it can also be set manually for specific use cases.
 */
enum class RefreshMode {
    /**
     * No continuous refresh. Content is mirrored only when explicitly
     * marked dirty via invalidate(). Best for static displays like
     * text, icons, and status indicators.
     * 
     * Power usage: Minimal
     * Use case: Dashboard showing time/battery that updates every few seconds
     */
    IDLE,
    
    /**
     * Low-frequency refresh (~5 FPS). Suitable for content that changes
     * occasionally but doesn't require smooth animation.
     * 
     * Power usage: Low
     * Use case: Lists, menus, slow-updating content
     */
    LOW,
    
    /**
     * Normal refresh rate (~30 FPS). Good for interactive UI with
     * animations, transitions, and user input feedback.
     * 
     * Power usage: Moderate
     * Use case: Scrolling lists, button feedback, progress indicators
     */
    NORMAL,
    
    /**
     * High refresh rate (~60 FPS). For smooth animations and
     * content that benefits from fluid motion.
     * 
     * Power usage: Higher
     * Use case: Smooth scrolling, complex animations
     */
    HIGH,
    
    /**
     * Real-time refresh (~120 FPS). Reserved for content that
     * requires frame-perfect mirroring like video playback or
     * WebView with dynamic content.
     * 
     * Power usage: Highest
     * Use case: Video, WebView, games
     */
    REALTIME;
    
    /**
     * Get the refresh interval in milliseconds for this mode.
     */
    fun getIntervalMs(): Long = when (this) {
        IDLE -> DisplayConfig.REFRESH_INTERVAL_IDLE_MS
        LOW -> DisplayConfig.REFRESH_INTERVAL_LOW_MS
        NORMAL -> DisplayConfig.REFRESH_INTERVAL_NORMAL_MS
        HIGH -> DisplayConfig.REFRESH_INTERVAL_HIGH_MS
        REALTIME -> DisplayConfig.REFRESH_INTERVAL_REALTIME_MS
    }
    
    /**
     * Whether this mode requires continuous refresh loop.
     */
    fun needsContinuousRefresh(): Boolean = this != IDLE
}
