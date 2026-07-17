package com.everyday.everyday_glasses

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal fun headingFromRayNeoOpticalForward(
    matrix: FloatArray,
    fallback: Float?,
    minHorizontalNorm: Float = 0.50f
): Float? {
    // With the glasses worn normally, local Y is vertical and optical forward
    // is local -Z (confirmed from the RayNeo accelerometer/magnetometer axes).
    val east = -matrix[2]
    val north = -matrix[5]
    if (sqrt(east * east + north * north) < minHorizontalNorm) return fallback
    var heading = Math.toDegrees(atan2(east, north).toDouble()).toFloat() % 360f
    if (heading < 0f) heading += 360f
    return heading
}

/**
 * Supplies relative head pose plus an absolute, tilt-compensated compass heading.
 *
 * The top-down map uses the geomagnetic rotation vector: a direct gravity + magnetic
 * north solution with no gyroscope yaw anchor to change between screen sessions.
 * The projected view separately uses the game vector for relative head tracking.
 */
class DevicePoseController(
    context: Context,
    private val onPoseChanged: (DevicePose) -> Unit,
    private val onStatusChanged: (String) -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val compassRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val relativeRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: compassRotationVector
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val currentQuaternion = FloatArray(4)
    private val inverseInitialQuaternion = FloatArray(4)
    private val relativeQuaternion = FloatArray(4)
    private val relativeMatrix = FloatArray(9)
    private val currentMatrix = FloatArray(9)
    private val deviceAngles = FloatArray(3)
    private val latestGravity = FloatArray(3)
    private val latestMagnetic = FloatArray(3)
    private val compassRotationMatrix = FloatArray(9)
    private val compassInclinationMatrix = FloatArray(9)

    private var initialQuaternion: FloatArray? = null
    private var inertialHeadingDeg = Float.NaN
    private var compassHeadingDeg = Float.NaN
    private var gameToTrueNorthOffsetDeg = Float.NaN
    private var hasNewTrustedCompassSample = false
    private var smoothedHeading = Float.NaN
    private var smoothedAnchorX = 0f
    private var smoothedAnchorY = 0f
    private var smoothedAnchorZ = 0f
    private var haveGravity = false
    private var haveMagnetic = false
    private var magneticDeclinationDeg = 0f
    private var running = false
    private var activeDelay = -1
    private var activeMode: PoseMode? = null
    private var lastPoseAccuracy = Int.MIN_VALUE
    private var lastMagneticAccuracy = Int.MIN_VALUE
    private var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var loggedInitialHeading = false
    private var loggedNorthCorrection = false
    private var lastHeadingTraceMs = 0L

    fun startTopDown() = start(PoseMode.TOP_DOWN, SensorManager.SENSOR_DELAY_UI)

    fun startProjected() = start(PoseMode.PROJECTED, SensorManager.SENSOR_DELAY_GAME)

    fun updateLocation(location: Location) {
        magneticDeclinationDeg = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            if (location.hasAltitude()) location.altitude.toFloat() else 0f,
            System.currentTimeMillis()
        ).declination
    }

    private fun start(mode: PoseMode, sensorDelay: Int) {
        if (running && activeMode == mode && activeDelay == sensorDelay) return
        val poseSensor = if (mode == PoseMode.TOP_DOWN) compassRotationVector else relativeRotationVector
        if (poseSensor == null) {
            onStatusChanged("Pose: required orientation sensors unavailable")
            return
        }
        if (running) sensorManager.unregisterListener(this)
        resetRelativePoseState()
        resetAbsoluteHeadingState()
        sensorManager.registerListener(this, poseSensor, sensorDelay)
        if (mode == PoseMode.PROJECTED) {
            accelerometer?.let { sensorManager.registerListener(this, it, sensorDelay) }
            magnetometer?.let { sensorManager.registerListener(this, it, sensorDelay) }
        }
        running = true
        activeDelay = sensorDelay
        activeMode = mode
        onStatusChanged("Pose: ${poseSensor.name}; direct tilt-compensated compass")
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
        activeDelay = -1
        activeMode = null
        onStatusChanged("Pose: paused")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                filterSensorValues(event.values, latestGravity, haveGravity, GRAVITY_FILTER_ALPHA)
                haveGravity = true
                updateCompassHeading()
                return
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                filterSensorValues(event.values, latestMagnetic, haveMagnetic, MAGNETIC_FILTER_ALPHA)
                haveMagnetic = true
                updateCompassHeading()
                return
            }
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR -> Unit
            else -> return
        }

        SensorManager.getQuaternionFromVector(currentQuaternion, event.values)
        if (initialQuaternion == null) {
            initialQuaternion = currentQuaternion.clone()
            smoothedAnchorX = 0f
            smoothedAnchorY = 0f
            smoothedAnchorZ = 0f
        }
        quaternionInverse(initialQuaternion!!, inverseInitialQuaternion)
        quaternionMultiply(inverseInitialQuaternion, currentQuaternion, relativeQuaternion)
        quaternionToMatrix(relativeQuaternion, relativeMatrix)
        extractDeviceYawPitchRoll(relativeMatrix, deviceAngles)

        smoothedAnchorX = smoothScalar(smoothedAnchorX, deviceAngles[1], 0.18f)
        smoothedAnchorY = smoothScalar(smoothedAnchorY, deviceAngles[0], 0.18f)
        smoothedAnchorZ = smoothScalar(smoothedAnchorZ, deviceAngles[2], 0.18f)

        val yawDeg = Math.toDegrees(smoothedAnchorY.toDouble()).toFloat() * DISPLAY_YAW_FROM_RAW_Y_SIGN
        val pitchDeg = Math.toDegrees(smoothedAnchorX.toDouble()).toFloat() * DISPLAY_PITCH_FROM_RAW_X_SIGN
        val rollDeg = Math.toDegrees(smoothedAnchorZ.toDouble()).toFloat() * DISPLAY_ROLL_FROM_RAW_Z_SIGN

        quaternionToMatrix(currentQuaternion, currentMatrix)
        headingFromRayNeoOpticalForward(
            currentMatrix,
            fallback = inertialHeadingDeg.takeUnless { it.isNaN() }
        )?.let { inertialHeadingDeg = it }

        if (hasNewTrustedCompassSample && !compassHeadingDeg.isNaN() && !inertialHeadingDeg.isNaN()) {
            hasNewTrustedCompassSample = false
            val offsetSample = normalizeDeg(compassHeadingDeg - inertialHeadingDeg)
            gameToTrueNorthOffsetDeg = smoothAngle(
                gameToTrueNorthOffsetDeg,
                offsetSample,
                NORTH_CORRECTION_ALPHA
            )
            if (!loggedNorthCorrection) {
                loggedNorthCorrection = true
                onStatusChanged("Pose: trusted north correction acquired")
            }
        }
        val heading = when (activeMode) {
            PoseMode.TOP_DOWN -> {
                // The geomagnetic vector is referenced to magnetic north;
                // GPS-derived declination aligns it with the map's true north.
                if (inertialHeadingDeg.isNaN()) return
                normalizeDeg(inertialHeadingDeg + magneticDeclinationDeg)
            }
            PoseMode.PROJECTED -> when {
                !gameToTrueNorthOffsetDeg.isNaN() && !inertialHeadingDeg.isNaN() ->
                    normalizeDeg(inertialHeadingDeg + gameToTrueNorthOffsetDeg)
                !compassHeadingDeg.isNaN() -> compassHeadingDeg
                !inertialHeadingDeg.isNaN() -> inertialHeadingDeg
                !smoothedHeading.isNaN() -> smoothedHeading
                else -> yawDeg
            }
            null -> return
        }
        if (!loggedInitialHeading && !compassHeadingDeg.isNaN()) {
            loggedInitialHeading = true
            onStatusChanged("Pose: initial direct-compass heading ${heading.toInt()}deg")
        }
        smoothedHeading = smoothAngle(smoothedHeading, heading, HEADING_OUTPUT_ALPHA)

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastHeadingTraceMs >= HEADING_TRACE_INTERVAL_MS) {
            lastHeadingTraceMs = nowMs
            onStatusChanged(
                "Pose sample: heading=${smoothedHeading.toInt()}deg " +
                    "inertial=${inertialHeadingDeg.toInt()}deg " +
                    "yaw=${yawDeg.toInt()}deg pitch=${pitchDeg.toInt()}deg"
            )
        }

        onPoseChanged(
            DevicePose(
                headingDeg = smoothedHeading,
                yawDeg = yawDeg,
                pitchDeg = pitchDeg,
                rollDeg = rollDeg,
                anchorXRad = smoothedAnchorX,
                anchorYRad = smoothedAnchorY,
                anchorZRad = smoothedAnchorZ,
                source = "${event.sensor.name}; direct compass"
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        val changed = when (sensor?.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (accuracy == lastPoseAccuracy) false else true.also { lastPoseAccuracy = accuracy }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticAccuracy = accuracy
                if (accuracy == lastMagneticAccuracy) false else true.also { lastMagneticAccuracy = accuracy }
            }
            else -> false
        }
        if (!changed) return
        val label = when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "unreliable"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
            else -> accuracy.toString()
        }
        onStatusChanged("Pose: ${sensor?.name}; accuracy $label")
    }

    private fun updateCompassHeading() {
        if (!haveGravity || !haveMagnetic) return
        // RayNeo's mmc56x3x driver commonly remains at ACCURACY_LOW even after
        // normal calibration. Reject only UNRELIABLE here and use the physical
        // field-strength and projection checks below to screen bad samples.
        if (magneticAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val fieldStrength = sqrt(
            latestMagnetic[0] * latestMagnetic[0] +
                latestMagnetic[1] * latestMagnetic[1] +
                latestMagnetic[2] * latestMagnetic[2]
        )
        if (fieldStrength !in MIN_MAGNETIC_FIELD_UT..MAX_MAGNETIC_FIELD_UT) return
        if (!SensorManager.getRotationMatrix(
                compassRotationMatrix,
                compassInclinationMatrix,
                latestGravity,
                latestMagnetic
            )
        ) return

        val east = -compassRotationMatrix[2]
        val north = -compassRotationMatrix[5]
        if (sqrt(east * east + north * north) < MIN_COMPASS_HORIZONTAL_NORM) return

        val magneticHeading = headingFromRayNeoOpticalForward(
            compassRotationMatrix,
            fallback = compassHeadingDeg.takeUnless { it.isNaN() }
        ) ?: return
        val trueHeading = normalizeDeg(magneticHeading + magneticDeclinationDeg)
        compassHeadingDeg = smoothAngle(compassHeadingDeg, trueHeading, COMPASS_HEADING_ALPHA)
        hasNewTrustedCompassSample = true
    }

    private fun filterSensorValues(
        input: FloatArray,
        output: FloatArray,
        initialized: Boolean,
        alpha: Float
    ) {
        for (index in output.indices) {
            val next = input.getOrElse(index) { 0f }
            output[index] = if (initialized) output[index] + (next - output[index]) * alpha else next
        }
    }

    private fun smoothScalar(previous: Float, next: Float, alpha: Float): Float =
        if (previous.isNaN()) next else previous + (next - previous) * alpha

    private fun smoothAngle(previous: Float, next: Float, alpha: Float): Float {
        if (previous.isNaN()) return next
        val delta = ((next - previous + 540f) % 360f) - 180f
        return normalizeDeg(previous + delta * alpha)
    }

    private fun normalizeDeg(value: Float): Float {
        var out = value % 360f
        if (out < 0f) out += 360f
        return out
    }

    private fun resetRelativePoseState() {
        initialQuaternion = null
        inertialHeadingDeg = Float.NaN
        smoothedAnchorX = 0f
        smoothedAnchorY = 0f
        smoothedAnchorZ = 0f
        lastPoseAccuracy = Int.MIN_VALUE
        lastMagneticAccuracy = Int.MIN_VALUE
        loggedInitialHeading = false
    }

    private fun resetAbsoluteHeadingState() {
        // The device can move while its display is asleep. Reusing the previous
        // magnetic vector or a game-to-north offset would mix two sensor sessions
        // and make the wake direction appear to redefine north.
        compassHeadingDeg = Float.NaN
        gameToTrueNorthOffsetDeg = Float.NaN
        smoothedHeading = Float.NaN
        hasNewTrustedCompassSample = false
        haveGravity = false
        haveMagnetic = false
        latestGravity.fill(0f)
        latestMagnetic.fill(0f)
        loggedNorthCorrection = false
    }

    private fun quaternionMultiply(q1: FloatArray, q2: FloatArray, out: FloatArray) {
        out[0] = q1[0] * q2[0] - q1[1] * q2[1] - q1[2] * q2[2] - q1[3] * q2[3]
        out[1] = q1[0] * q2[1] + q1[1] * q2[0] + q1[2] * q2[3] - q1[3] * q2[2]
        out[2] = q1[0] * q2[2] - q1[1] * q2[3] + q1[2] * q2[0] + q1[3] * q2[1]
        out[3] = q1[0] * q2[3] + q1[1] * q2[2] - q1[2] * q2[1] + q1[3] * q2[0]
    }

    private fun quaternionInverse(q: FloatArray, out: FloatArray) {
        val magnitudeSquared = q.sumOf { (it * it).toDouble() }.toFloat()
        if (magnitudeSquared == 0f) {
            out.fill(0f)
            return
        }
        val inverseMagnitude = 1f / magnitudeSquared
        out[0] = q[0] * inverseMagnitude
        out[1] = -q[1] * inverseMagnitude
        out[2] = -q[2] * inverseMagnitude
        out[3] = -q[3] * inverseMagnitude
    }

    private fun quaternionToMatrix(q: FloatArray, out: FloatArray) {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        out[0] = 1f - 2f * (y * y + z * z)
        out[1] = 2f * (x * y - z * w)
        out[2] = 2f * (x * z + y * w)
        out[3] = 2f * (x * y + z * w)
        out[4] = 1f - 2f * (x * x + z * z)
        out[5] = 2f * (y * z - x * w)
        out[6] = 2f * (x * z - y * w)
        out[7] = 2f * (y * z + x * w)
        out[8] = 1f - 2f * (x * x + y * y)
    }

    private fun extractDeviceYawPitchRoll(matrix: FloatArray, out: FloatArray) {
        out[0] = atan2(matrix[2], matrix[8])
        out[1] = asin(clamp(-matrix[5], -1f, 1f))
        out[2] = atan2(matrix[3], matrix[4])
    }

    private fun clamp(value: Float, minValue: Float, maxValue: Float): Float =
        max(minValue, min(maxValue, value))

    companion object {
        const val DEG_TO_RAD = PI.toFloat() / 180f
        private const val DISPLAY_YAW_FROM_RAW_Y_SIGN = -1f
        private const val DISPLAY_PITCH_FROM_RAW_X_SIGN = 1f
        private const val DISPLAY_ROLL_FROM_RAW_Z_SIGN = 1f
        private const val GRAVITY_FILTER_ALPHA = 0.18f
        private const val MAGNETIC_FILTER_ALPHA = 0.12f
        private const val COMPASS_HEADING_ALPHA = 0.16f
        private const val NORTH_CORRECTION_ALPHA = 0.12f
        private const val HEADING_OUTPUT_ALPHA = 0.22f
        private const val HEADING_TRACE_INTERVAL_MS = 1_000L
        private const val MIN_COMPASS_HORIZONTAL_NORM = 0.50f
        private const val MIN_MAGNETIC_FIELD_UT = 20f
        private const val MAX_MAGNETIC_FIELD_UT = 80f
    }

    private enum class PoseMode { TOP_DOWN, PROJECTED }
}
