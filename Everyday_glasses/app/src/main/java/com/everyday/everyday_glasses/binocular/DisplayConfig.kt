package com.everyday.everyday_glasses.binocular

/**
 * Display configuration constants for RayNeo X3 Pro AR glasses.
 * 
 * The glasses have a combined 1280x480 display:
 * - Left eye: 0-639 (640px wide)
 * - Right eye: 640-1279 (640px wide)
 * 
 * This object centralizes all display-related constants to avoid magic numbers.
 */
object DisplayConfig {
    // Total display dimensions
    const val SCREEN_WIDTH = 1280
    const val SCREEN_HEIGHT = 480
    
    // Per-eye dimensions
    const val EYE_WIDTH = 640
    const val EYE_HEIGHT = 480
    
    // UI element dimensions (for future use)
    const val TOGGLE_BAR_WIDTH = 48
    const val NAV_BAR_HEIGHT = 48
    const val STATUS_BAR_HEIGHT = 24
    
    // Content area (excluding UI chrome)
    const val CONTENT_WIDTH = EYE_WIDTH - TOGGLE_BAR_WIDTH
    const val CONTENT_HEIGHT = EYE_HEIGHT - NAV_BAR_HEIGHT
    
    // Refresh timing
    const val REFRESH_INTERVAL_IDLE_MS = 0L          // No refresh when static
    const val REFRESH_INTERVAL_LOW_MS = 200L         // 5 FPS for slow updates
    const val REFRESH_INTERVAL_NORMAL_MS = 33L       // 30 FPS for interactive
    const val REFRESH_INTERVAL_HIGH_MS = 16L         // 60 FPS for smooth animation
    const val REFRESH_INTERVAL_REALTIME_MS = 8L      // 120 FPS for video/WebView
    
    // PixelCopy minimum interval to prevent overload
    const val MIN_PIXELCOPY_INTERVAL_MS = 16L
}
