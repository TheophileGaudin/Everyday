package com.everyday.everyday_glasses

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.max

/**
 * Handles cursor physics and stabilization for glasses temple trackpad input.
 * 
 * This class encapsulates all the cursor stabilization logic including:
 * - Y-Axis reversal hysteresis (anti-jump on direction change)
 * - Momentum clamping (anti-teleport)
 * - Horizontal rail locking
 * - Anisotropic smoothing (different smoothing for X and Y)
 *
 * Phone trackpad uses direct 1:1 mapping and bypasses this stabilizer.
 */
class CursorStabilizer {

    companion object {
        // --- STABILIZATION SETTINGS ---

        // 1. REVERSAL HYSTERESIS
        // Increased to 40.0f to catch larger hooks when changing Y direction.
        private const val Y_REVERSAL_HYSTERESIS_THRESHOLD = 40.0f

        // 2. MOMENTUM CLAMP (Anti-Teleport)
        // If moving slowly, we cap the max jump per frame to prevent teleporting.
        // The clamp expands dynamically as you gain speed.
        private const val MOMENTUM_CLAMP_MIN_DELTA = 25.0f // Max jump allowed from a standstill
        private const val MOMENTUM_CLAMP_FACTOR = 3.0f     // How fast we allow acceleration (3x per frame)

        // 3. SMOOTHING & RAILS
        private const val SMOOTH_ALPHA_X = 0.85f
        private const val SMOOTH_ALPHA_Y = 0.45f
        private const val RAIL_LOCK_RATIO = 5.0f
        private const val RAIL_MIN_VELOCITY = 2.0f
    }

    // --- CURSOR STATE ---
    
    // Reversal Tracking for Y-Axis (Anti-Jump)
    // +1.0 for moving Down, -1.0 for moving Up, 0 for stationary
    private var lastTrendY = 0f
    
    // Accumulates "wrong way" movement until it breaks the threshold
    private var reversalAccumulatorY = 0f

    // Momentum Tracking
    // Tracks how fast we were actually moving last frame
    private var lastOutputSpeed = 0f

    /**
     * Reset all stabilization state.
     * Call this when cursor interaction starts (onCursorDown) or when cursor is hidden.
     */
    fun reset() {
        lastTrendY = 0f
        reversalAccumulatorY = 0f
        lastOutputSpeed = 0f
    }

    /**
     * Process raw delta input and return stabilized delta output.
     * 
     * @param rawDx Raw X delta from trackpad
     * @param rawDy Raw Y delta from trackpad
     * @return Stabilized PointF containing (processedDx, processedDy)
     */
    fun process(rawDx: Float, rawDy: Float): PointF {
        var dx = rawDx
        var dy = rawDy

        // 1. MOMENTUM CLAMP (Anti-Teleport)
        val dist = sqrt((dx * dx) + (dy * dy))
        val maxAllowedDist = max(MOMENTUM_CLAMP_MIN_DELTA, lastOutputSpeed * MOMENTUM_CLAMP_FACTOR)

        if (dist > maxAllowedDist) {
            val scale = maxAllowedDist / dist
            dx *= scale
            dy *= scale
        }

        // 2. Y-Axis Reversal Hysteresis
        var effectiveRawDy = dy
        if (dy != 0f) {
            val currentDir = sign(dy)
            if (lastTrendY != 0f && currentDir != lastTrendY) {
                reversalAccumulatorY += dy
                if (abs(reversalAccumulatorY) < Y_REVERSAL_HYSTERESIS_THRESHOLD) {
                    effectiveRawDy = 0f
                } else {
                    lastTrendY = currentDir
                    reversalAccumulatorY = 0f
                }
            } else {
                lastTrendY = currentDir
                reversalAccumulatorY = 0f
                effectiveRawDy = dy
            }
        }

        // 3. Horizontal Rail Lock
        val isSignificantHorizontal = abs(dx) > RAIL_MIN_VELOCITY
        val isMostlyHorizontal = abs(dx) > (abs(effectiveRawDy) * RAIL_LOCK_RATIO)

        if (isSignificantHorizontal && isMostlyHorizontal) {
            effectiveRawDy = 0f
        }

        // Update Momentum State
        lastOutputSpeed = sqrt((dx * dx) + (effectiveRawDy * effectiveRawDy))

        // 4. Anisotropic Smoothing
        val finalDx = dx * SMOOTH_ALPHA_X
        val finalDy = effectiveRawDy * SMOOTH_ALPHA_Y

        return PointF(finalDx, finalDy)
    }

    /**
     * Get the last recorded output speed (useful for debugging).
     */
    fun getLastOutputSpeed(): Float = lastOutputSpeed
}
