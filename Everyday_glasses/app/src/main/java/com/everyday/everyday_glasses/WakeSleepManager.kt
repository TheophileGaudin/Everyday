package com.everyday.everyday_glasses

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages the wake/sleep display states for AR glasses.
 *
 * This is the app's internal state machine, completely independent from the
 * glasses' hardware action button which controls display on/off at the OS level.
 *
 * State Machine:
 * - OFF: Screen is effectively off (nothing visible, or only showing black)
 * - WAKE: Everything is visible (all widgets)
 * - SLEEP: Only pinned widgets are visible
 *
 * Transitions:
 * - WAKE → SLEEP: After inactivity timeout (default 10s)
 * - SLEEP → WAKE: Head lift or user activity
 * - OFF → WAKE: Head lift or programmatic call
 * - SLEEP (no pinned widgets) → treated as OFF automatically
 */
class WakeSleepManager(
    private val onStateChanged: (DisplayState) -> Unit
) {
    
    companion object {
        private const val TAG = "WakeSleepManager"
        
        // Default auto-sleep timeout in milliseconds
        const val DEFAULT_SLEEP_TIMEOUT_MS = 10_000L  // 10 seconds
    }
    
    /**
     * Display states for the AR glasses.
     */
    enum class DisplayState {
        OFF,    // Screen is off/black - nothing visible
        WAKE,   // Everything visible
        SLEEP   // Only pinned widgets visible
    }
    
    // Current state
    private var _state: DisplayState = DisplayState.WAKE
    val state: DisplayState get() = _state
    
    // Configurable sleep timeout
    var sleepTimeoutMs: Long = DEFAULT_SLEEP_TIMEOUT_MS
        set(value) {
            field = value.coerceAtLeast(1000L)  // Minimum 1 second
            Log.d(TAG, "Sleep timeout set to ${field}ms")
        }
    
    // Handler for timeout management
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    
    // Callback to check if there are any pinned widgets
    // This is used to determine if SLEEP mode has any visible content
    var hasPinnedWidgets: (() -> Boolean)? = null
    
    init {
        Log.d(TAG, "WakeSleepManager initialized in WAKE state")
        // Start in WAKE mode with timer running
        startSleepTimer()
    }
    
    /**
     * Called when user performs any activity (cursor move, tap, etc.).
     * Resets the sleep timer if in WAKE mode.
     */
    fun onUserActivity() {
        if (_state == DisplayState.WAKE) {
            // Reset the sleep timer
            resetSleepTimer()
        }
    }
    
    /**
     * Called when head lift is detected (from accelerometer).
     * Wakes from SLEEP mode if there are pinned widgets.
     * 
     * @return true if state changed
     */
    fun onHeadLift(): Boolean {
        Log.d(TAG, "Head lift detected in state: $_state")
        
        when (_state) {
            DisplayState.SLEEP -> {
                // SLEEP → WAKE
                transitionTo(DisplayState.WAKE)
                return true
            }
            DisplayState.OFF -> {
                // OFF → WAKE (head lift can also wake from off)
                transitionTo(DisplayState.WAKE)
                return true
            }
            DisplayState.WAKE -> {
                // Already awake, just reset the timer
                resetSleepTimer()
                return false
            }
        }
    }
    
    /**
     * Force transition to a specific state.
     * Used for programmatic control.
     */
    fun transitionTo(newState: DisplayState) {
        if (_state == newState) {
            Log.d(TAG, "Already in state $newState, ignoring transition")
            return
        }
        
        val oldState = _state
        _state = newState
        
        Log.d(TAG, "State transition: $oldState → $newState")
        
        // Handle state-specific logic
        when (newState) {
            DisplayState.WAKE -> {
                // Start the sleep timer
                startSleepTimer()
            }
            DisplayState.SLEEP -> {
                // Cancel any running timer (we're now in sleep)
                cancelSleepTimer()
                
                // Check if we should auto-transition to OFF
                // (if no pinned widgets, SLEEP is effectively OFF)
                val hasPinned = hasPinnedWidgets?.invoke() ?: false
                if (!hasPinned) {
                    Log.d(TAG, "No pinned widgets in SLEEP mode - treating as OFF")
                    // Note: We still stay in SLEEP state, but visually it's the same as OFF
                    // This allows head-lift to wake back to WAKE mode
                }
            }
            DisplayState.OFF -> {
                // Cancel any running timer
                cancelSleepTimer()
            }
        }
        
        // Notify listener
        onStateChanged(newState)
    }
    
    /**
     * Start the sleep timer.
     * Called when entering WAKE mode.
     */
    private fun startSleepTimer() {
        cancelSleepTimer()
        
        sleepRunnable = Runnable {
            Log.d(TAG, "Sleep timer expired after ${sleepTimeoutMs}ms")
            if (_state == DisplayState.WAKE) {
                transitionTo(DisplayState.SLEEP)
            }
        }
        handler.postDelayed(sleepRunnable!!, sleepTimeoutMs)
        
        //Log.d(TAG, "Sleep timer started (${sleepTimeoutMs}ms)")
    }
    
    /**
     * Reset the sleep timer (restart from beginning).
     * Called on user activity.
     */
    private fun resetSleepTimer() {
        if (_state != DisplayState.WAKE) return
        
        cancelSleepTimer()
        startSleepTimer()
    }
    
    /**
     * Cancel the sleep timer.
     */
    private fun cancelSleepTimer() {
        sleepRunnable?.let {
            handler.removeCallbacks(it)
            sleepRunnable = null
        }
    }
    
    /**
     * Check if content should be visible based on current state.
     * 
     * @param isPinned Whether the widget asking is pinned
     * @return true if the widget should be drawn
     */
    fun shouldShowWidget(isPinned: Boolean): Boolean {
        return when (_state) {
            DisplayState.OFF -> false  // Nothing visible when OFF
            DisplayState.WAKE -> true  // Everything visible when WAKE
            DisplayState.SLEEP -> isPinned  // Only pinned widgets in SLEEP
        }
    }
    
    /**
     * Check if we're in a state where the cursor should be visible.
     */
    fun shouldShowCursor(): Boolean {
        return _state == DisplayState.WAKE
    }
    
    /**
     * Check if we're in a state where user interaction is active.
     */
    fun isAwake(): Boolean {
        return _state == DisplayState.WAKE
    }
    
    /**
     * Clean up resources.
     */
    fun release() {
        cancelSleepTimer()
        Log.d(TAG, "WakeSleepManager released")
    }
}
