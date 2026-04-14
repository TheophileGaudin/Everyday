package com.everyday.everyday_glasses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs

/**
 * ThreeDofManager - Manages 3 Degrees of Freedom head tracking for AR glasses.
 *
 * 3DOF tracks rotational head movements (pitch, yaw, roll) using the device's
 * gyroscope and accelerometer sensors. This enables a "world-locked" content mode
 * where UI content stays fixed in virtual space while the user looks around.
 *
 * Design principles for Snapdragon AR1 Gen1 (4GB RAM):
 * - Uses game-rate sensor updates (50Hz) for smooth tracking with low latency
 * - Employs complementary filter for sensor fusion (lightweight, no heavy matrix ops)
 * - Applies exponential smoothing for jitter reduction
 * - Minimizes allocations in the sensor callback hot path
 *
 * AR Glasses coordinate system (when worn on head, looking forward):
 * - X axis: points right (ear to ear)
 * - Y axis: points up (toward top of head)
 * - Z axis: points forward (out of the display)
 *
 * Gyroscope rotation:
 * - Rotation around X = pitch (nodding head up/down)
 * - Rotation around Y = yaw (turning head left/right)
 * - Rotation around Z = roll (tilting head side to side)
 */
class ThreeDofManager(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ThreeDofManager"


        // Maximum rotation speed (degrees per second) to filter out noise spikes
        private const val MAX_ROTATION_SPEED = 300f

        // Conversion factor: radians to degrees
        private const val RAD_TO_DEG = 57.2957795f

        // --- Per-axis smoothing (alpha in smooth += (target - smooth) * alpha)
// Higher alpha = more responsive (less lag), lower alpha = smoother (more lag).
        private const val SMOOTH_YAW = 0.30f
        private const val SMOOTH_PITCH = 0.30f
        private const val SMOOTH_ROLL = 0.70f   // key: make roll snappier to avoid "boat" lag

        // --- Gyro dead-zone (deg/s) per axis (used only in gyro fallback path)
        private const val DEAD_ZONE_YAW_DPS = 0.0f
        private const val DEAD_ZONE_PITCH_DPS = 0.0f
        private const val DEAD_ZONE_ROLL_DPS = 0.0f

        // --- GRV dead-zone (degrees) per axis (used in GAME_ROTATION_VECTOR path)
        // This is the knob that kills micro wobble without adding lag.
        private const val DEAD_ZONE_YAW_DEG = 0.05f
        private const val DEAD_ZONE_PITCH_DEG = 0.05f
        private const val DEAD_ZONE_ROLL_DEG = 0.15f  // slightly larger; roll jitter is more visible

        // --- FOV limits per axis (degrees)
        private const val FOV_YAW = 50f
        private const val FOV_PITCH = 35f
        private const val FOV_ROLL = 30f

        // --- Sensitivity per axis
        private const val SENSITIVITY_X = 26f   // yaw -> pixels
        private const val SENSITIVITY_Y = 26f   // pitch -> pixels

        // Optional: roll gain for visual rotation (multiplier on degrees)
        private const val ROLL_GAIN = 1.0f



        // Transition animation duration in milliseconds
        private const val TRANSITION_DURATION_MS = 300L

        // Update interval for the UI refresh loop (in ms)
        private const val UPDATE_INTERVAL_MS = 16L  // ~60fps
    }

    // ==================== State ====================
    private var lastRollLogMs = 0L

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gameRotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    // Current 3DOF enabled state
    private var is3DofEnabled = false

    // Accumulated rotation from gyroscope (in degrees)
    private var accumulatedYaw = 0f    // Left/right rotation
    private var accumulatedPitch = 0f  // Up/down rotation
    private var accumulatedRoll = 0f   // Tilt rotation

    // Smoothed values for display
    private var smoothYaw = 0f
    private var smoothPitch = 0f
    private var smoothRoll = 0f

    // Previous gyroscope timestamp for integration
    private var lastGyroTimestamp = 0L

    // For game rotation vector
    // We store reference *orientation angles* after axis remapping (see handleGameRotationVector).
    private var referenceOrientationAngles: FloatArray? = null
    private val currentRotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Transition animation state
    private var isTransitioning = false
    private var transitionStartTime = 0L
    private var transitionStartOffsetX = 0f
    private var transitionStartOffsetY = 0f
    private var transitionStartRoll = 0f

    // Screen dimensions (set by container)
    private var screenWidth = 640f
    private var screenHeight = 480f

    // Handler for UI updates
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    // ==================== Callbacks ====================

    var onModeChanged: ((Boolean) -> Unit)? = null
    var onOrientationChanged: ((Float, Float) -> Unit)? = null
    // Optional: if your UI wants to compensate roll, subscribe here (degrees).
    var onRollChanged: ((Float) -> Unit)? = null
    var onTransitionProgress: ((Float) -> Unit)? = null

    init {
        // Log available sensors for debugging
        Log.d(TAG, "Available sensors:")
        Log.d(TAG, "  Gyroscope: ${gyroscope?.name ?: "NOT AVAILABLE"}")
        Log.d(TAG, "  Game Rotation Vector: ${gameRotationVector?.name ?: "NOT AVAILABLE"}")
    }

    // ==================== Public API ====================

    fun is3DofEnabled(): Boolean = is3DofEnabled
    fun isTransitioning(): Boolean = isTransitioning

    private fun applyDeadZoneDeg(value: Float, zone: Float): Float {
        return if (abs(value) < zone) 0f else value
    }


    fun toggle3Dof() {
        if (is3DofEnabled) {
            disable3Dof()
        } else {
            enable3Dof()
        }
    }

    fun enable3Dof() {
        if (is3DofEnabled) return

        Log.d(TAG, "Enabling 3DOF mode")

        // Reset accumulated rotation
        accumulatedYaw = 0f
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        smoothYaw = 0f
        smoothPitch = 0f
        smoothRoll = 0f
        lastGyroTimestamp = 0L
        referenceOrientationAngles = null

        // Start transition animation
        isTransitioning = true
        transitionStartTime = System.currentTimeMillis()
        transitionStartOffsetX = 0f
        transitionStartOffsetY = 0f
        transitionStartRoll = 0f

        is3DofEnabled = true
        startSensorListening()
        startUpdateLoop()

        onModeChanged?.invoke(true)
    }

    fun disable3Dof() {
        if (!is3DofEnabled) return

        Log.d(TAG, "Disabling 3DOF mode")

        // Store current offset for transition animation back to center (0,0)
        transitionStartOffsetX = degreesToPixelsX(smoothYaw)
        transitionStartOffsetY = degreesToPixelsY(smoothPitch)
        transitionStartRoll = smoothRoll

        // Start transition animation back to center
        isTransitioning = true
        transitionStartTime = System.currentTimeMillis()

        is3DofEnabled = false
        stopSensorListening()

        // Reset accumulated values so next enable starts fresh
        accumulatedYaw = 0f
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        smoothYaw = 0f
        smoothPitch = 0f
        smoothRoll = 0f

        // Keep update loop running during transition (will animate back to 0,0)
        onModeChanged?.invoke(false)
    }

    fun setScreenDimensions(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
        Log.d(TAG, "Screen dimensions set: ${width}x${height}")
    }

    fun getContentOffset(): Pair<Float, Float> {
        if (isTransitioning) {
            val elapsed = System.currentTimeMillis() - transitionStartTime
            val progress = (elapsed.toFloat() / TRANSITION_DURATION_MS).coerceIn(0f, 1f)
            val easedProgress = 1f - (1f - progress) * (1f - progress)

            if (progress >= 1f) {
                isTransitioning = false
                if (!is3DofEnabled) {
                    stopUpdateLoop()
                    return Pair(0f, 0f)
                }
            }

            return if (is3DofEnabled) {
                // Transitioning into 3DOF
                val targetX = degreesToPixelsX(smoothYaw)
                val targetY = degreesToPixelsY(smoothPitch)
                Pair(targetX * easedProgress, targetY * easedProgress)
            } else {
                // Transitioning out of 3DOF
                Pair(
                    transitionStartOffsetX * (1f - easedProgress),
                    transitionStartOffsetY * (1f - easedProgress)
                )
            }
        }

        if (!is3DofEnabled) {
            return Pair(0f, 0f)
        }

        return Pair(
            degreesToPixelsX(smoothYaw),
            degreesToPixelsY(smoothPitch)
        )
    }

    fun recenter() {
        if (!is3DofEnabled) return
        Log.d(TAG, "Recentering 3DOF view")
        accumulatedYaw = 0f
        accumulatedPitch = 0f
        accumulatedRoll = 0f
        smoothYaw = 0f
        smoothPitch = 0f
        smoothRoll = 0f
        referenceOrientationAngles = null
        onOrientationChanged?.invoke(0f, 0f)
        onRollChanged?.invoke(0f)
    }

    fun release() {
        stopSensorListening()
        stopUpdateLoop()
        Log.d(TAG, "ThreeDofManager released")
    }

    // ==================== Update Loop ====================

    private fun startUpdateLoop() {
        stopUpdateLoop()
        updateRunnable = object : Runnable {
            override fun run() {
                if (is3DofEnabled || isTransitioning) {
                    val (offsetX, offsetY) = getContentOffset()
                    onOrientationChanged?.invoke(offsetX, offsetY)

                    // Roll is not part of the (x,y) content offset, but some UIs may want to rotate/level content.
                    // We animate roll in/out alongside the main transition so it doesn't snap.
                    if (onRollChanged != null) {
                        val rollOut = if (isTransitioning) {
                            val elapsed = System.currentTimeMillis() - transitionStartTime
                            val progress = (elapsed.toFloat() / TRANSITION_DURATION_MS).coerceIn(0f, 1f)
                            val easedProgress = 1f - (1f - progress) * (1f - progress)
                            if (is3DofEnabled) {
                                // Transitioning into 3DOF
                                smoothRoll * easedProgress
                            } else {
                                // Transitioning out of 3DOF
                                transitionStartRoll * (1f - easedProgress)
                            }
                        } else {
                            smoothRoll
                        }
                        // Emit the *compensation* roll, i.e. opposite to head tilt, so content stays world-locked.
                        onRollChanged?.invoke(rollOut * ROLL_GAIN)
                    }

                    if (isTransitioning) {
                        val elapsed = System.currentTimeMillis() - transitionStartTime
                        val progress = (elapsed.toFloat() / TRANSITION_DURATION_MS).coerceIn(0f, 1f)
                        onTransitionProgress?.invoke(progress)
                    }

                    handler.postDelayed(this, UPDATE_INTERVAL_MS)
                }
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopUpdateLoop() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    // ==================== Sensor Management ====================

    private fun startSensorListening() {
        // Prefer game rotation vector (no magnetic interference)
        if (gameRotationVector != null) {
            val registered = sensorManager.registerListener(
                this,
                gameRotationVector,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Game rotation vector sensor registered: $registered")
        } else if (gyroscope != null) {
            // Fall back to gyroscope only
            val registered = sensorManager.registerListener(
                this,
                gyroscope,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.d(TAG, "Gyroscope sensor registered: $registered")
        } else {
            Log.e(TAG, "No suitable sensors available for 3DOF!")
        }
    }

    private fun stopSensorListening() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Sensor listening stopped")
    }

    // ==================== SensorEventListener ====================

    override fun onSensorChanged(event: SensorEvent) {

        if (!is3DofEnabled) return

        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> handleGameRotationVector(event)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Log.d(TAG, "Sensor ${sensor.name} accuracy: $accuracy")
    }

    // ==================== Sensor Processing ====================

    private fun handleGameRotationVector(event: SensorEvent) {
        // Get rotation matrix from quaternion
        SensorManager.getRotationMatrixFromVector(currentRotationMatrix, event.values)

        // IMPORTANT:
        // For head-worn glasses we want yaw about the *device Y axis* (up), pitch about X (right), roll about Z (forward).
        // Android's getOrientation() defines:
        //   angles[0] = azimuth about +Z, angles[1] = pitch about +X, angles[2] = roll about +Y
        // So we remap axes such that the returned azimuth corresponds to yaw about device +Y.
        // Choose: X' = device X, Y' = device Z  => Z' = X' x Y' = device Y.
        SensorManager.remapCoordinateSystem(
            currentRotationMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedRotationMatrix
        )

        // Get orientation angles in our remapped head frame
        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)

        // Store reference on first reading (avoid allocating every callback)
        if (referenceOrientationAngles == null) {
            referenceOrientationAngles = orientationAngles.clone()
            Log.d(TAG, "Reference orientation captured (remapped head frame)")
            return
        }

        val refAngles = referenceOrientationAngles!!

        // Calculate relative rotation (in degrees)
        var deltaYaw = wrapDegrees((orientationAngles[0] - refAngles[0]) * RAD_TO_DEG)
        var deltaPitch = (orientationAngles[1] - refAngles[1]) * RAD_TO_DEG
        var deltaRoll = wrapDegrees((orientationAngles[2] - refAngles[2]) * RAD_TO_DEG)

        // Clamp to FOV limits
        deltaYaw = deltaYaw.coerceIn(-FOV_YAW, FOV_YAW)
        deltaPitch = deltaPitch.coerceIn(-FOV_PITCH, FOV_PITCH)
        deltaRoll = deltaRoll.coerceIn(-FOV_ROLL, FOV_ROLL)

        deltaYaw = applyDeadZoneDeg(deltaYaw, DEAD_ZONE_YAW_DEG)
        deltaPitch = applyDeadZoneDeg(deltaPitch, DEAD_ZONE_PITCH_DEG)
        deltaRoll = applyDeadZoneDeg(deltaRoll, DEAD_ZONE_ROLL_DEG)

        // Smooth the values
        smoothYaw += (deltaYaw - smoothYaw) * SMOOTH_YAW
        smoothPitch += (deltaPitch - smoothPitch) * SMOOTH_PITCH
        smoothRoll += (deltaRoll - smoothRoll) * SMOOTH_ROLL

        // val now = android.os.SystemClock.uptimeMillis()
        // if (now - lastRollLogMs > 200) { // 5x/sec
        //     lastRollLogMs = now
        //     Log.d(TAG, "ROLL DEBUG (GRV) smoothRoll=${"%.1f".format(smoothRoll)}°")
        // }
    }

    private fun handleGyroscope(event: SensorEvent) {
        val currentTime = event.timestamp

        if (lastGyroTimestamp == 0L) {
            lastGyroTimestamp = currentTime
            return
        }

        // Calculate delta time in seconds
        val dt = (currentTime - lastGyroTimestamp) / 1_000_000_000f
        lastGyroTimestamp = currentTime

        // Skip if dt is too large (indicates a gap in readings)
        if (dt > 0.1f) {
            Log.w(TAG, "Large dt detected: $dt, skipping")
            return
        }

        // Gyroscope values are in radians/second.
        // For the coordinate system documented at the top of this file:
        //   X = pitch, Y = yaw, Z = roll
        val pitchRate = event.values[0] * RAD_TO_DEG  // X-axis rotation
        val yawRate = event.values[1] * RAD_TO_DEG    // Y-axis rotation
        val rollRate = event.values[2] * RAD_TO_DEG   // Z-axis rotation

        // Apply dead zone
        val effectivePitchRate = if (abs(pitchRate) > DEAD_ZONE_PITCH_DPS) pitchRate else 0f
        val effectiveYawRate = if (abs(yawRate) > DEAD_ZONE_YAW_DPS) yawRate else 0f
        val effectiveRollRate = if (abs(rollRate) > DEAD_ZONE_ROLL_DPS) rollRate else 0f

        // Clamp to prevent noise spikes
        val clampedPitchRate = effectivePitchRate.coerceIn(-MAX_ROTATION_SPEED, MAX_ROTATION_SPEED)
        val clampedYawRate = effectiveYawRate.coerceIn(-MAX_ROTATION_SPEED, MAX_ROTATION_SPEED)
        val clampedRollRate = effectiveRollRate.coerceIn(-MAX_ROTATION_SPEED, MAX_ROTATION_SPEED)

        // Integrate to get accumulated rotation
        accumulatedPitch += clampedPitchRate * dt
        accumulatedYaw += clampedYawRate * dt
        accumulatedRoll += clampedRollRate * dt

        // Clamp accumulated values to FOV limits
        accumulatedPitch = accumulatedPitch.coerceIn(-FOV_PITCH, FOV_PITCH)
        accumulatedYaw = accumulatedYaw.coerceIn(-FOV_YAW, FOV_YAW)
        accumulatedRoll = accumulatedRoll.coerceIn(-FOV_ROLL, FOV_ROLL)

        // Smooth the values
        smoothPitch += (accumulatedPitch - smoothPitch) * SMOOTH_PITCH
        smoothYaw += (accumulatedYaw - smoothYaw) * SMOOTH_YAW
        smoothRoll += (accumulatedRoll - smoothRoll) * SMOOTH_ROLL

        // Log periodically for debugging
        // if (System.currentTimeMillis() % 1000 < 20) {
        //     val offsetX = degreesToPixelsX(smoothYaw)
        //     val offsetY = degreesToPixelsY(smoothPitch)
        //     Log.d(
        //         TAG,
        //         "3DOF: yaw=${smoothYaw.toInt()}° pitch=${smoothPitch.toInt()}° roll=${smoothRoll.toInt()}° -> offset=(${offsetX.toInt()}, ${offsetY.toInt()})"
        //     )
        // }

        //if (abs(smoothRoll) > 2f && (System.currentTimeMillis() % 200L) < 20L) {
        //    Log.d(TAG, "ROLL DEBUG smoothRoll=${"%.1f".format(smoothRoll)}°")
        //}

    }

    private fun wrapDegrees(deg: Float): Float {
        var d = deg
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    // ==================== Coordinate Conversion ====================

    private fun degreesToPixelsX(degrees: Float): Float {
        // Looking right (positive yaw) should move content LEFT (negative offset)
        // so that content appears to stay fixed in world space
        return -degrees * SENSITIVITY_X
    }

    private fun degreesToPixelsY(degrees: Float): Float {
        // Looking up (negative pitch in Android convention) should move content DOWN
        // Note: We may need to flip this depending on the glasses' coordinate system
        return -degrees * SENSITIVITY_Y
    }
}
