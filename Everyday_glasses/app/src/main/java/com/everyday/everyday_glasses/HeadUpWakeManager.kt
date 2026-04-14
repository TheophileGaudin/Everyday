package com.everyday.everyday_glasses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * HeadUpWakeManager - Detects head-up motion to temporarily wake the display.
 *
 * This manager monitors the device's game rotation vector sensor to detect when the user
 * tilts their head up (like looking at the sky). When the screen is OFF, this motion
 * will temporarily wake the display for a configurable duration.
 *
 * Key behaviors:
 * - Only active when the display is in OFF state
 * - Requires the head-up position to be held for a configurable duration (default 1s)
 * - Wakes the display for a configurable duration (default 10s)
 * - Automatically turns the display back off after the wake duration
 *
 * Detection method:
 * - Uses game rotation vector to detect pitch angle (head tilt up/down)
 * - When pitch exceeds threshold for the required hold time, triggers wake
 */
class HeadUpWakeManager(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "HeadUpWakeManager"

        // Default durations
        const val DEFAULT_HEAD_UP_HOLD_TIME_MS = 1000L  // 1 second hold required
        const val DEFAULT_WAKE_DURATION_MS = 10000L     // 10 seconds screen on

        // Minimum/maximum values for settings
        const val MIN_HEAD_UP_HOLD_TIME_MS = 300L    // 0.3 seconds minimum
        const val MAX_HEAD_UP_HOLD_TIME_MS = 3000L   // 3 seconds maximum
        const val MIN_WAKE_DURATION_MS = 3000L       // 3 seconds minimum
        const val MAX_WAKE_DURATION_MS = 30000L      // 30 seconds maximum

        // Default angle threshold
        const val DEFAULT_PITCH_CHANGE_THRESHOLD = 25f // degrees

        // Conversion factor
        private const val RAD_TO_DEG = 57.2957795f
    }

    // Sensor management
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gameRotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Handler for timing
    private val handler = Handler(Looper.getMainLooper())

    // Configuration
    var headUpHoldTimeMs: Long = DEFAULT_HEAD_UP_HOLD_TIME_MS
        set(value) {
            field = value.coerceIn(MIN_HEAD_UP_HOLD_TIME_MS, MAX_HEAD_UP_HOLD_TIME_MS)
            Log.d(TAG, "Head-up hold time set to ${field}ms")
        }

    var wakeDurationMs: Long = DEFAULT_WAKE_DURATION_MS
        set(value) {
            field = value.coerceIn(MIN_WAKE_DURATION_MS, MAX_WAKE_DURATION_MS)
            Log.d(TAG, "Wake duration set to ${field}ms")
        }

    // Configurable pitch threshold in degrees
    var pitchChangeThreshold: Float = DEFAULT_PITCH_CHANGE_THRESHOLD
        set(value) {
            field = value.coerceIn(DashboardSettings.MIN_ANGLE_THRESHOLD, DashboardSettings.MAX_ANGLE_THRESHOLD)
            Log.d(TAG, "Pitch threshold set to ${field}°")
        }

    // Whether the heads-up gesture feature is enabled by the user
    var isHeadsUpGestureEnabled: Boolean = true
        set(value) {
            field = value
            Log.d(TAG, "Heads-up gesture ${if (value) "enabled" else "disabled"}")
            if (!value) {
                // If disabled, reset any pending head-up detection
                resetHeadUpState()
            }
        }

    // State tracking
    private var isEnabled = false
    private var isScreenOff = false
    private var isWakeActive = false  // True when we've woken the screen and are waiting for timeout

    // True only when the screen was woken by this manager (head-up gesture).
    // This prevents us from requesting sleep for screens turned on by other means.
    private var wokeScreenByHeadUp = false

    // Head-up detection state
    private var headUpStartTime: Long = 0L
    private var isHeadUp = false

    // Runnables
    private var wakeTimeoutRunnable: Runnable? = null

    // Callbacks
    var onWakeRequested: (() -> Unit)? = null
    var onSleepRequested: (() -> Unit)? = null

    // For game rotation vector processing
    private val rotationMatrix = FloatArray(9)

    init {
        Log.d(TAG, "HeadUpWakeManager initialized")
        Log.d(TAG, "  Accelerometer: ${accelerometer?.name ?: "NOT AVAILABLE"}")
        Log.d(TAG, "  Game Rotation Vector: ${gameRotationVector?.name ?: "NOT AVAILABLE"}")
    }

    /**
     * Enable head-up wake detection.
     * Call this when the app is active and ready to detect head-up gestures.
     */
    fun enable() {
        if (isEnabled) return
        isEnabled = true
        startSensorListening()
        Log.d(TAG, "Head-up wake detection enabled")
    }

    /**
     * Disable head-up wake detection.
     * Call this when the app is paused or destroyed.
     */
    fun disable() {
        if (!isEnabled) return
        isEnabled = false
        stopSensorListening()
        cancelWakeTimeout()
        resetState()
        Log.d(TAG, "Head-up wake detection disabled")
    }

    /**
     * Notify the manager that the screen state has changed.
     * Head-up detection only triggers wake when screen is OFF.
     *
     * @param isOff true if the screen is now OFF, false otherwise
     */
    fun onScreenStateChanged(isOff: Boolean) {
        val wasOff = isScreenOff
        isScreenOff = isOff

        if (wasOff && !isOff) {
            // Screen turned on (possibly by us or by other means)
            resetHeadUpState()

            // If the screen was turned on by OTHER means while a previous head-up
            // wake was not active, ensure we never request sleep for it.
            if (!isWakeActive) {
                wokeScreenByHeadUp = false
            }
        } else if (!wasOff && isOff) {
            // Screen turned off
            // Cancel any active wake timeout since we're already off
            if (isWakeActive) {
                cancelWakeTimeout()
                isWakeActive = false
                wokeScreenByHeadUp = false
            }
            // Reset state for fresh detection
            resetHeadUpState()
        }

        Log.d(TAG, "Screen state changed: isOff=$isOff, wasOff=$wasOff, isWakeActive=$isWakeActive")
    }

    /**
     * Check if head-up wake is currently active (screen was woken by head-up and timer is running).
     */
    fun isWakeActive(): Boolean = isWakeActive

    /**
     * Release resources.
     */
    fun release() {
        disable()
        Log.d(TAG, "HeadUpWakeManager released")
    }

    // ==================== Sensor Management ====================

    private fun startSensorListening() {
        // Prefer game rotation vector for more accurate orientation
        if (gameRotationVector != null) {
            sensorManager.registerListener(
                this,
                gameRotationVector,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d(TAG, "Registered game rotation vector sensor")
        } else if (accelerometer != null) {
            // Fallback to accelerometer
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d(TAG, "Registered accelerometer sensor (fallback)")
        } else {
            Log.e(TAG, "No suitable sensors available for head-up detection!")
        }
    }

    private fun stopSensorListening() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Sensor listening stopped")
    }

    // ==================== SensorEventListener ====================

    override fun onSensorChanged(event: SensorEvent) {
        if (!isEnabled) return

        // Skip all processing if the heads-up gesture feature is disabled by the user
        if (!isHeadsUpGestureEnabled) return

        // Only process if screen is off and we haven't already triggered a wake
        if (!isScreenOff || isWakeActive) return

        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> handleGameRotationVector(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Not used
    }

    // ==================== Sensor Processing ====================

    private fun handleGameRotationVector(event: SensorEvent) {
        // Get rotation matrix from quaternion
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // MANUAL PITCH CALCULATION
        // Instead of getOrientation (which clamps to -90/+90), we look at the
        // Z-axis projection directly from the rotation matrix.
        // Rotation Matrix indices:
        // [0, 1, 2] -> X axis
        // [3, 4, 5] -> Y axis
        // [6, 7, 8] -> Z axis

        // The element at index 7 (row 2, col 1) represents the Y-component of the Z-axis.
        // The element at index 8 (row 2, col 2) represents the Z-component of the Z-axis.
        // We use atan2 to get the full 360 degree angle.

        // This calculates the angle of the Z-vector projected on the Y-Z plane
        val pitchRad = kotlin.math.atan2(rotationMatrix[7].toDouble(), rotationMatrix[8].toDouble())

        // Convert to degrees and adjust offset if necessary
        // You might need to adjust the -90 offset depending on your specific mounting
        val pitchDegrees = (pitchRad * RAD_TO_DEG).toFloat() - 90f

        processHeadPitch(pitchDegrees)
    }

    private fun handleAccelerometer(event: SensorEvent) {
        // Simple accelerometer-based pitch detection (fallback)
        val ay = event.values[1]
        val az = event.values[2]

        val pitchRad = kotlin.math.atan2(az.toDouble(), ay.toDouble())
        val pitchDegrees = (pitchRad * RAD_TO_DEG).toFloat() - 90f

        processHeadPitch(pitchDegrees)
    }

    private fun processHeadPitch(pitchDegrees: Float) {
        val currentTime = System.currentTimeMillis()

        // Check if pitch exceeds threshold (looking up)
        val isLookingUp = (pitchDegrees > pitchChangeThreshold)

        if (isLookingUp) {
            if (!isHeadUp) {
                // Just started looking up
                isHeadUp = true
                headUpStartTime = currentTime
                Log.d(TAG, "Head-up detected! pitch=${"%.1f".format(pitchDegrees)}° (threshold: ${pitchChangeThreshold}°)")
            } else {
                // Check if held long enough
                val holdDuration = currentTime - headUpStartTime
                if (holdDuration >= headUpHoldTimeMs) {
                    // Trigger wake!
                    triggerWake()
                }
            }
        } else {
            if (isHeadUp) {
                // Stopped looking up before hold time reached
                val holdDuration = currentTime - headUpStartTime
                Log.d(TAG, "Head-up cancelled after ${holdDuration}ms (pitch: ${"%.1f".format(pitchDegrees)}°)")
                resetHeadUpState()
            }
        }
    }

    // ==================== Wake Logic ====================

    private fun triggerWake() {
        Log.d(TAG, "Head-up wake triggered! Waking screen for ${wakeDurationMs}ms")

        isWakeActive = true
        wokeScreenByHeadUp = true
        resetHeadUpState()

        // Notify listener to wake the screen
        onWakeRequested?.invoke()

        // Schedule automatic sleep after wake duration
        scheduleWakeTimeout()
    }

    private fun scheduleWakeTimeout() {
        cancelWakeTimeout()

        wakeTimeoutRunnable = Runnable {
            // Important: Only request sleep if *we* turned it on.
            // If the user/app turned it on by other means, we must not interfere.
            if (wokeScreenByHeadUp) {
                Log.d(TAG, "Wake timeout expired, requesting sleep")
                onSleepRequested?.invoke()
            } else {
                Log.d(TAG, "Wake timeout expired, but screen not woken by head-up; skipping sleep")
            }

            // Either way, the wake window is over.
            isWakeActive = false
            wokeScreenByHeadUp = false
        }
        handler.postDelayed(wakeTimeoutRunnable!!, wakeDurationMs)
    }

    private fun cancelWakeTimeout() {
        wakeTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            wakeTimeoutRunnable = null
        }
    }

    // ==================== State Management ====================

    private fun resetHeadUpState() {
        isHeadUp = false
        headUpStartTime = 0L
    }

    private fun resetState() {
        resetHeadUpState()
        isWakeActive = false
        wokeScreenByHeadUp = false
    }
}
