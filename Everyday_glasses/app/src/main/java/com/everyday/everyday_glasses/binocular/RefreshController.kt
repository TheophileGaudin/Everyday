package com.everyday.everyday_glasses.binocular

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Controls the refresh cycle for binocular mirroring.
 * 
 * Features:
 * - Dirty region tracking for efficient partial updates
 * - Choreographer synchronization for vsync-aligned rendering
 * - Adaptive refresh rate based on RefreshMode
 * - Efficient state management with atomic operations
 * 
 * This class is thread-safe and designed for minimal overhead on the hot path.
 */
class RefreshController(
    private val onRefresh: (dirtyRect: Rect?) -> Unit
) {
    
    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    
    // Atomic state for thread safety without locks
    private val isRunning = AtomicBoolean(false)
    private val currentMode = AtomicReference(RefreshMode.IDLE)
    
    // Dirty region tracking
    private val dirtyRect = Rect()
    private val isDirty = AtomicBoolean(false)
    private val dirtyLock = Object()
    
    // Timing
    private var lastRefreshTime = 0L
    
    // Keep-alive for continuous refresh modes
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            
            val mode = currentMode.get()
            if (mode.needsContinuousRefresh()) {
                // Re-schedule refresh runnable if it stopped
                handler.removeCallbacks(refreshRunnable)
                handler.post(refreshRunnable)
            }
            
            // Schedule next keep-alive check
            handler.postDelayed(this, 1000)
        }
    }
    
    /**
     * Choreographer callback for vsync-synchronized refresh.
     * Only used for HIGH and REALTIME modes.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning.get()) return
            
            val mode = currentMode.get()
            if (mode == RefreshMode.HIGH || mode == RefreshMode.REALTIME) {
                performRefresh()
                // Schedule next frame
                choreographer.postFrameCallback(this)
            }
        }
    }
    
    /**
     * Handler-based runnable for lower refresh rates.
     * More battery-efficient than Choreographer for slow updates.
     */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            
            val mode = currentMode.get()
            if (mode.needsContinuousRefresh() && mode != RefreshMode.HIGH && mode != RefreshMode.REALTIME) {
                performRefresh()
                // Schedule next refresh
                handler.postDelayed(this, mode.getIntervalMs())
            }
        }
    }
    
    /**
     * Set the refresh mode.
     * 
     * @param mode The desired refresh mode
     */
    fun setMode(mode: RefreshMode) {
        val previousMode = currentMode.getAndSet(mode)
        
        if (previousMode == mode) return
        
        Log.d(TAG, "Refresh mode changed: $previousMode -> $mode")
        
        if (isRunning.get()) {
            // Stop previous scheduling
            stopScheduling()
            
            // Start new scheduling if needed
            if (mode.needsContinuousRefresh()) {
                startScheduling(mode)
            }
        }
    }
    
    /**
     * Get the current refresh mode.
     */
    fun getMode(): RefreshMode = currentMode.get()
    
    /**
     * Start the refresh controller.
     */
    fun start() {
        if (isRunning.getAndSet(true)) return  // Already running
        
        Log.d(TAG, "RefreshController started")
        
        val mode = currentMode.get()
        if (mode.needsContinuousRefresh()) {
            startScheduling(mode)
        }
        
        // Start keep-alive to ensure refresh doesn't stop
        handler.postDelayed(keepAliveRunnable, 1000)
    }
    
    /**
     * Stop the refresh controller.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return  // Already stopped
        
        Log.d(TAG, "RefreshController stopped")
        stopScheduling()
        handler.removeCallbacks(keepAliveRunnable)
    }
    
    private fun startScheduling(mode: RefreshMode) {
        when (mode) {
            RefreshMode.HIGH, RefreshMode.REALTIME -> {
                // Use Choreographer for vsync-aligned high-frequency refresh
                choreographer.postFrameCallback(frameCallback)
            }
            RefreshMode.LOW, RefreshMode.NORMAL -> {
                // Use Handler for lower-frequency refresh (more battery efficient)
                handler.post(refreshRunnable)
            }
            RefreshMode.IDLE -> {
                // No continuous refresh
            }
        }
    }
    
    private fun stopScheduling() {
        choreographer.removeFrameCallback(frameCallback)
        handler.removeCallbacks(refreshRunnable)
    }
    
    /**
     * Mark a region as dirty (needs refresh).
     * 
     * This always triggers an immediate refresh to ensure updates are visible.
     * 
     * @param view The view that was modified (optional, for partial updates)
     */
    fun markDirty(view: View? = null) {
        synchronized(dirtyLock) {
            if (view != null) {
                // Calculate view's dirty rectangle
                val viewRect = Rect()
                view.getDrawingRect(viewRect)
                
                // Offset to parent coordinates
                val offsetViewBounds = Rect()
                view.getGlobalVisibleRect(offsetViewBounds)
                
                // Union with existing dirty region
                if (isDirty.get()) {
                    dirtyRect.union(offsetViewBounds)
                } else {
                    dirtyRect.set(offsetViewBounds)
                }
            } else {
                // No specific view = full refresh
                dirtyRect.set(0, 0, DisplayConfig.EYE_WIDTH, DisplayConfig.EYE_HEIGHT)
            }
            isDirty.set(true)
        }
        
        // Always trigger immediate refresh when marked dirty
        if (isRunning.get()) {
            handler.post { performRefresh() }
        }
    }
    
    /**
     * Force an immediate refresh regardless of mode or dirty state.
     * Works even when the controller is not running continuously.
     */
    fun forceRefresh() {
        markDirty()
        // Always post refresh, even if not continuously running
        handler.post { performRefresh() }
    }
    
    private fun performRefresh() {
        val currentTime = System.currentTimeMillis()
        
        // Get and clear dirty region
        val dirty: Rect?
        synchronized(dirtyLock) {
            dirty = if (isDirty.getAndSet(false)) {
                Rect(dirtyRect).also { dirtyRect.setEmpty() }
            } else {
                null
            }
        }
        
        // For continuous modes, always refresh even if not dirty
        val mode = currentMode.get()
        val shouldRefresh = dirty != null || mode.needsContinuousRefresh()
        
        if (shouldRefresh) {
            onRefresh(dirty)
            lastRefreshTime = currentTime
        }
    }
    
    /**
     * Check if there are pending dirty regions.
     */
    fun hasDirtyRegions(): Boolean = isDirty.get()
    
    /**
     * Get time since last refresh in milliseconds.
     */
    fun getTimeSinceLastRefresh(): Long = System.currentTimeMillis() - lastRefreshTime
    
    companion object {
        private const val TAG = "RefreshController"
    }
}

/**
 * Extension to easily mark a View as dirty in the binocular system.
 * 
 * Usage:
 *   myTextView.text = "Updated"
 *   myTextView.markBinocularDirty(refreshController)
 */
fun View.markBinocularDirty(controller: RefreshController) {
    controller.markDirty(this)
}
