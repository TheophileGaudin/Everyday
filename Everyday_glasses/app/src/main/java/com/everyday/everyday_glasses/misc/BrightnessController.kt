package com.everyday.everyday_glasses.misc

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kotlin.math.ln

data class BrightnessState(
    val requestedBrightness: Float,
    val adaptiveEnabled: Boolean
)

class BrightnessController(
    private val activity: Activity
) {
    // State
    private var requestedBrightness = 1.0f
    private var adaptiveBrightnessEnabled = true
    private var isMediaBrightnessCapActive = false
    private var isBrowserMinimumBrightnessActive = false
    private var isLayoutSwitchMinimumBrightnessActive = false
    private var layoutSwitchBrightnessRestoreRunnable: Runnable? = null
    private var isGlassesCharging = false
    private var smoothedAmbientLux: Float? = null
    private var lastAdaptiveBrightnessTarget: Float? = null
    private var appliedWindowBrightness = -1.0f
    private var ambientLightSensorManager: SensorManager? = null
    private var ambientLightSensor: Sensor? = null
    private var isAmbientLightListenerRegistered = false
    private var resumed = false
    private var released = false
    private val handler = Handler(Looper.getMainLooper())

    // Lifecycle

    fun initialize() {
        ambientLightSensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        ambientLightSensor = ambientLightSensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        if (ambientLightSensor != null) {
            Log.d(TAG, "Ambient light sensor ready: ${ambientLightSensor?.name}")
        } else {
            Log.w(TAG, "No ambient light sensor available; adaptive brightness will fall back to manual control")
        }
    }

    fun onResume() {
        if (released) return
        resumed = true
        updateAmbientLightMonitoring()
    }

    fun onPause() {
        resumed = false
        stopAmbientLightMonitoring()
    }

    fun onDestroy() {
        if (released) return
        released = true
        resumed = false
        stopAmbientLightMonitoring()

        layoutSwitchBrightnessRestoreRunnable?.let {
            handler.removeCallbacks(it)
        }
        layoutSwitchBrightnessRestoreRunnable = null
    }

    // External brightness controls
    fun setBrightness(brightness: Float) {
        requestedBrightness = brightness.coerceIn(0f, 1f)
        saveBrightnessSettings()
        applyEffectiveWindowBrightness()
    }

    fun setAdaptiveBrightness(enabled: Boolean) {
        adaptiveBrightnessEnabled = enabled
        smoothedAmbientLux = null
        lastAdaptiveBrightnessTarget = null
        updateAmbientLightMonitoring()
        saveBrightnessSettings()
        applyEffectiveWindowBrightness()
    }

    fun setMediaBrightnessCap(active: Boolean) {
        isMediaBrightnessCapActive = active
        applyEffectiveWindowBrightness()
    }

    fun setBrowserMinimumBrightness(active: Boolean) {
        isBrowserMinimumBrightnessActive = active
        applyEffectiveWindowBrightness()
    }

    fun setCharging(charging: Boolean): Boolean {
        if (isGlassesCharging == charging) return false

        isGlassesCharging = charging
        applyEffectiveWindowBrightness()
        return true
    }

    fun beginLayoutSwitchBrightness() = beginLayoutSwitchMinimumBrightness()

    // Persistence
    fun restore(): BrightnessState {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        requestedBrightness =
            prefs.getFloat(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS)
                .coerceIn(0f, 1f)

        adaptiveBrightnessEnabled =
            prefs.getBoolean(
                KEY_ADAPTIVE_BRIGHTNESS,
                DEFAULT_ADAPTIVE_BRIGHTNESS
            )

        updateAmbientLightMonitoring()
        applyEffectiveWindowBrightness()

        return BrightnessState(
            requestedBrightness,
            adaptiveBrightnessEnabled
        )
    }

    /**
     * Save brightness settings to SharedPreferences.
     */
    private fun saveBrightnessSettings() {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_BRIGHTNESS, requestedBrightness)
            .putBoolean(KEY_ADAPTIVE_BRIGHTNESS, adaptiveBrightnessEnabled)
            .apply()
    }

    // Ambient light sensor
    private val ambientLightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val rawLux = event.values.firstOrNull()?.coerceAtLeast(0f) ?: return
            val previousLux = smoothedAmbientLux
            val smoothedLux = if (previousLux == null) {
                rawLux
            } else {
                previousLux + ((rawLux - previousLux) * AMBIENT_LUX_SMOOTHING_ALPHA)
            }
            smoothedAmbientLux = smoothedLux

            val adaptiveTarget = mapAmbientLuxToBrightness(smoothedLux)
            if (lastAdaptiveBrightnessTarget == null ||
                abs(adaptiveTarget - (lastAdaptiveBrightnessTarget ?: adaptiveTarget)) >= ADAPTIVE_BRIGHTNESS_EPSILON
            ) {
                lastAdaptiveBrightnessTarget = adaptiveTarget
                applyEffectiveWindowBrightness()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun updateAmbientLightMonitoring() {
        val sensorManager = ambientLightSensorManager
        val sensor = ambientLightSensor
        val shouldListen = resumed && !released && adaptiveBrightnessEnabled && sensorManager != null && sensor != null

        if (shouldListen && !isAmbientLightListenerRegistered) {
            sensorManager!!.registerListener(ambientLightListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            isAmbientLightListenerRegistered = true
            Log.d(TAG, "Ambient light monitoring enabled")
        } else if (!shouldListen && isAmbientLightListenerRegistered) {
            sensorManager?.unregisterListener(ambientLightListener)
            isAmbientLightListenerRegistered = false
            Log.d(TAG, "Ambient light monitoring disabled")
        }
    }

    private fun stopAmbientLightMonitoring() {
        if (!isAmbientLightListenerRegistered) return
        ambientLightSensorManager?.unregisterListener(ambientLightListener)
        isAmbientLightListenerRegistered = false
    }

    // Brightness calculation
    private fun mapAmbientLuxToBrightness(lux: Float): Float {
        val normalizedLux = (ln(1f + lux.coerceAtLeast(0f)) / ln(1f + AMBIENT_LUX_REFERENCE)).coerceIn(0f, 1f)
        return (MIN_ADAPTIVE_BRIGHTNESS + (normalizedLux * (1f - MIN_ADAPTIVE_BRIGHTNESS))).coerceIn(MIN_ADAPTIVE_BRIGHTNESS, 1f)
    }

    private fun computeEffectiveWindowBrightness(): Float {
        val adaptiveTarget = if (adaptiveBrightnessEnabled && ambientLightSensor != null) {
            lastAdaptiveBrightnessTarget ?: requestedBrightness
        } else {
            requestedBrightness
        }
        val mediaWidgetCap = if (isMediaBrightnessCapActive && !isGlassesCharging) {
            MEDIA_WIDGET_BRIGHTNESS_CAP
        } else {
            1f
        }
        val browserWidgetCap = if (isBrowserMinimumBrightnessActive && !isGlassesCharging) {
            MIN_WINDOW_BRIGHTNESS
        } else {
            1f
        }
        val layoutSwitchCap = if (isLayoutSwitchMinimumBrightnessActive && !isGlassesCharging) {
            MIN_WINDOW_BRIGHTNESS
        } else {
            1f
        }
        return minOf(
            requestedBrightness,
            adaptiveTarget,
            mediaWidgetCap,
            browserWidgetCap,
            layoutSwitchCap
        ).coerceIn(MIN_WINDOW_BRIGHTNESS, 1f)
    }

    fun applyEffectiveWindowBrightness() {
        val effectiveBrightness = computeEffectiveWindowBrightness()
        if (abs(effectiveBrightness - appliedWindowBrightness) < 0.01f) {
            return
        }
        applyWindowBrightness(effectiveBrightness)
    }

    /**
     * Apply brightness at window level so the entire app (including WebViews) is dimmed.
     * @param brightness Value from 0.0 (minimum, most dimmed) to 1.0 (maximum, no dimming)
     */
    private fun applyWindowBrightness(brightness: Float) {
        val layoutParams = activity.window.attributes
        layoutParams.screenBrightness = brightness.coerceIn(MIN_WINDOW_BRIGHTNESS, 1.0f)
        activity.window.attributes = layoutParams
        appliedWindowBrightness = layoutParams.screenBrightness
        Log.d(
            TAG,
            "Window brightness set to: $appliedWindowBrightness " +
                    "(requested=$requestedBrightness, adaptive=$adaptiveBrightnessEnabled, mediaCapActive=$isMediaBrightnessCapActive, browserMinActive=$isBrowserMinimumBrightnessActive, layoutSwitchMinActive=$isLayoutSwitchMinimumBrightnessActive, charging=$isGlassesCharging, lux=${smoothedAmbientLux ?: -1f})"
        )
    }

    // Temporary layout switch dimming
    private fun beginLayoutSwitchMinimumBrightness() {
        if (isGlassesCharging) return

        layoutSwitchBrightnessRestoreRunnable?.let(handler::removeCallbacks)
        isLayoutSwitchMinimumBrightnessActive = true
        applyEffectiveWindowBrightness()

        val restoreRunnable = Runnable {
            isLayoutSwitchMinimumBrightnessActive = false
            layoutSwitchBrightnessRestoreRunnable = null
            applyEffectiveWindowBrightness()
        }
        layoutSwitchBrightnessRestoreRunnable = restoreRunnable
        handler.postDelayed(restoreRunnable, LAYOUT_SWITCH_MIN_BRIGHTNESS_DURATION_MS)
    }

    // Class constants
    companion object{
        private const val TAG = "BrightnessController"
        private const val PREFS_NAME = "everyday_settings"
        private const val KEY_BRIGHTNESS = "screen_brightness"
        private const val KEY_ADAPTIVE_BRIGHTNESS = "adaptive_brightness_enabled"
        private const val DEFAULT_BRIGHTNESS = 1.0f  // Maximum brightness (no dimming) by default
        private const val DEFAULT_ADAPTIVE_BRIGHTNESS = true
        private const val MEDIA_WIDGET_BRIGHTNESS_CAP = 0.18f
        private const val MIN_WINDOW_BRIGHTNESS = 0.01f
        private const val LAYOUT_SWITCH_MIN_BRIGHTNESS_DURATION_MS = 1_000L
        private const val MIN_ADAPTIVE_BRIGHTNESS = 0.08f
        private const val AMBIENT_LUX_REFERENCE = 10000f
        private const val AMBIENT_LUX_SMOOTHING_ALPHA = 0.2f
        private const val ADAPTIVE_BRIGHTNESS_EPSILON = 0.03f
    }





}
