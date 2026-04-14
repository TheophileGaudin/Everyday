package com.everyday.everyday_glasses

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * TapGestureDetector - Handles single, double, and triple tap detection with proper timing.
 *
 * The challenge with multi-tap detection is balancing responsiveness with accuracy:
 * - Single taps should feel immediate but wait briefly to check for subsequent taps
 * - Double taps need to wait for potential triple taps
 * - Triple taps should trigger immediately upon detection
 *
 * Timing design:
 * - TAP_WINDOW (200ms): Maximum time between consecutive taps to count as multi-tap
 * - Single tap is dispatched TAP_WINDOW after the tap if no second tap arrives
 * - Double tap is dispatched TAP_WINDOW after second tap if no third tap arrives
 * - Triple tap is dispatched immediately upon third tap detection
 *
 * This ensures:
 * - Single taps feel responsive (~200ms delay, acceptable for UI)
 * - Double taps don't steal intended triple taps
 * - Triple taps are immediate with no delay
 */
class TapGestureDetector {

    companion object {
        private const val TAG = "TapGestureDetector"

        // Time window for counting consecutive taps as multi-tap
        // 200ms feels responsive while allowing natural multi-tap gestures
        private const val TAP_WINDOW_MS = 200L

        // Maximum movement between taps to still count as same gesture
        private const val MAX_TAP_DISTANCE = 60f
    }

    // ==================== State ====================

    private val handler = Handler(Looper.getMainLooper())

    // Tap counting state
    private var tapCount = 0
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // Pending tap dispatch runnable
    private var pendingTapDispatch: Runnable? = null

    // ==================== Callbacks ====================

    /**
     * Called when a single tap is confirmed.
     * Parameters: (x, y) coordinates of the tap
     */
    var onSingleTap: ((Float, Float) -> Unit)? = null

    /**
     * Called when a double tap is confirmed.
     * Parameters: (x, y) coordinates of the tap
     */
    var onDoubleTap: ((Float, Float) -> Unit)? = null

    /**
     * Called when a triple tap is confirmed.
     * Parameters: (x, y) coordinates of the tap
     */
    var onTripleTap: ((Float, Float) -> Unit)? = null

    // ==================== Public API ====================

    /**
     * Register a tap at the given coordinates.
     * This method should be called when a tap is detected by the input system.
     *
     * @param x X coordinate of the tap
     * @param y Y coordinate of the tap
     */
    fun onTap(x: Float, y: Float) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastTap = currentTime - lastTapTime

        // Cancel any pending tap dispatch
        cancelPendingDispatch()

        // Check if this tap is close enough in time and space to count as consecutive
        val isConsecutive = timeSinceLastTap < TAP_WINDOW_MS &&
                isWithinDistance(x, y, lastTapX, lastTapY, MAX_TAP_DISTANCE)

        if (isConsecutive) {
            tapCount++
            Log.d(TAG, "Consecutive tap detected, count: $tapCount")
        } else {
            // Start new tap sequence
            tapCount = 1
            Log.d(TAG, "New tap sequence started")
        }

        // Update tracking state
        lastTapTime = currentTime
        lastTapX = x
        lastTapY = y

        // Handle based on tap count
        when (tapCount) {
            1 -> {
                // Schedule single tap dispatch
                scheduleTapDispatch(1, x, y)
            }
            2 -> {
                // Schedule double tap dispatch
                scheduleTapDispatch(2, x, y)
            }
            3 -> {
                // Triple tap - dispatch immediately
                Log.d(TAG, "Triple tap confirmed at ($x, $y)")
                tapCount = 0  // Reset sequence
                onTripleTap?.invoke(x, y)
            }
            else -> {
                // More than 3 taps - reset and treat as new single tap
                tapCount = 1
                scheduleTapDispatch(1, x, y)
            }
        }
    }

    /**
     * Reset the tap detection state.
     * Call this when the user interaction context changes (e.g., cursor moved significantly).
     */
    fun reset() {
        cancelPendingDispatch()
        tapCount = 0
        lastTapTime = 0L
        Log.d(TAG, "Tap detector reset")
    }

    /**
     * Release resources. Call when no longer needed.
     */
    fun release() {
        cancelPendingDispatch()
        Log.d(TAG, "Tap detector released")
    }

    // ==================== Internal Methods ====================

    /**
     * Schedule a tap dispatch after the TAP_WINDOW.
     * If another tap arrives before dispatch, this is cancelled.
     */
    private fun scheduleTapDispatch(count: Int, x: Float, y: Float) {
        pendingTapDispatch = Runnable {
            when (count) {
                1 -> {
                    Log.d(TAG, "Single tap confirmed at ($x, $y)")
                    onSingleTap?.invoke(x, y)
                }
                2 -> {
                    Log.d(TAG, "Double tap confirmed at ($x, $y)")
                    onDoubleTap?.invoke(x, y)
                }
            }
            tapCount = 0  // Reset after dispatch
            pendingTapDispatch = null
        }
        handler.postDelayed(pendingTapDispatch!!, TAP_WINDOW_MS)
    }

    /**
     * Cancel any pending tap dispatch.
     */
    private fun cancelPendingDispatch() {
        pendingTapDispatch?.let {
            handler.removeCallbacks(it)
            pendingTapDispatch = null
        }
    }

    /**
     * Check if two points are within a given distance.
     */
    private fun isWithinDistance(x1: Float, y1: Float, x2: Float, y2: Float, maxDistance: Float): Boolean {
        val dx = x1 - x2
        val dy = y1 - y2
        return (dx * dx + dy * dy) <= (maxDistance * maxDistance)
    }
}
