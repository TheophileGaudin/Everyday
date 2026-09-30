package com.everyday.everyday_glasses.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import com.everyday.shared.sync.LocationFix
import com.everyday.shared.sync.usableSpeedMps
import java.util.Locale

data class SpeedState(
    val speedKmh: Float,
    val qualityOk: Boolean,
    val isMoving: Boolean
)

class LocationSpeedController(
    private val activity: Activity,
    private val onSpeedChanged: (SpeedState) -> Unit,
    private val onSpeedUnavailable: () -> Unit,
    private val onLocationUpdated: (Location) -> Unit,
    private val onLocationUnavailable: () -> Unit
) {
    private val locationListener = object : LocationListener {

        override fun onLocationChanged(location: Location) {
            processLocationUpdate(location)
        }

        override fun onProviderEnabled(provider: String) {
            speedLog { "Location provider enabled: $provider" }
        }

        override fun onProviderDisabled(provider: String) {
            speedLog { "Location provider disabled: $provider" }
        }
    }
    private var speedLocationManager: LocationManager? = null
    private var speedLocationListener: LocationListener? = null
    var latestLocalLocation: Location? = null
        private set
    private var lastSpeedLocationUpdateElapsedMs = 0L
    private var speedFallbackRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var speedFallbackProviders: List<String> = emptyList()
    private var smoothedSpeedKmh = 0f
    private var lastReliableSpeedSampleElapsedMs = 0L
    private var speedVisibilityThresholdKmh = SPEED_THRESHOLD_KMH
    private var isSpeedMoving = false
    private var lastSpeedQualityOk = false
    private var lastSpeedLogMs = 0L
    private var lastMissingPhoneSpeedLogMs = 0L
    private var started = false
    private var released = false
    private var lifecycleGeneration = 0L
    private var fallbackRequestInFlight = false
    private var fallbackCancellationSignal: CancellationSignal? = null


    companion object {
        private const val TAG = "LocationSpeedController"
        private const val PREFS_NAME = "everyday_settings"
        private const val SPEED_THRESHOLD_KMH = 1f
        private const val SPEED_THRESHOLD_HYSTERESIS_KMH = 0.1f
        private const val SPEED_SMOOTHING_ALPHA = 0.35f
        private const val SPEED_RESUME_BOOTSTRAP_ALPHA = 0.85f
        private const val SPEED_RESUME_BOOTSTRAP_GAP_MS = 4_000L
        private const val SPEED_LOCATION_MIN_TIME_MS = 1000L
        private const val SPEED_LOCATION_MIN_DISTANCE_M = 0f
        private const val MAX_ACCEPTABLE_SPEED_AGE_MS = 10_000L
        private const val MAX_ACCEPTABLE_ACCURACY_M = 60f
        private const val MAX_ACCEPTABLE_COARSE_ACCURACY_M = 250f
        private const val MIN_SPEED_ESTIMATE_INTERVAL_MS = 500L
        private const val MAX_SPEED_ESTIMATE_INTERVAL_MS = 12_000L
        private const val MAX_REASONABLE_SPEED_KMH = 180f
        private const val SPEED_FALLBACK_POLL_INTERVAL_MS = 3_000L
        private const val SPEED_STALE_UPDATE_WINDOW_MS = 6_000L
        private const val SPEEDO_LOG = true
        private const val SPEEDO_LOG_TAG = "Speedometer"
        private const val SPEED_LOG_INTERVAL_MS = 5000L
        private const val KEY_SPEED_VISIBILITY_THRESHOLD = "speed_visibility_threshold_kmh"
    }

    private inline fun speedLog(msg: () -> String) {
        if (SPEEDO_LOG) {
            Log.d(SPEEDO_LOG_TAG, msg())
        }
    }

    fun applyPhoneLocationFix(snapshot: LocationFix) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val localFresh = lastSpeedLocationUpdateElapsedMs != 0L &&
                (nowElapsed - lastSpeedLocationUpdateElapsedMs) <SPEED_STALE_UPDATE_WINDOW_MS &&
                lastSpeedQualityOk
        if (localFresh) {
            return
        }
        val safeSpeedMps = snapshot.usableSpeedMps() ?: run {
            val now = System.currentTimeMillis()
            if (now - lastMissingPhoneSpeedLogMs >=SPEED_LOG_INTERVAL_MS) {
                lastMissingPhoneSpeedLogMs = now
                speedLog { "Phone speed unavailable or low quality and local speed is stale" }
            }
            smoothSpeedReading(0f, qualityOk = false, source = "phone-unavailable")
            lastSpeedQualityOk = false
            isSpeedMoving = false
            @Suppress("KotlinConstantConditions")
            onSpeedChanged(SpeedState(speedKmh = smoothedSpeedKmh, qualityOk = lastSpeedQualityOk, isMoving = isSpeedMoving))
            return
        }

        val phoneKmh = (safeSpeedMps * 3.6f)
            .coerceAtLeast(0f)
            .coerceAtMost(MAX_REASONABLE_SPEED_KMH)
        smoothSpeedReading(phoneKmh, qualityOk = true, source = "phone")

        val previousMoving = isSpeedMoving
        isSpeedMoving = computeSpeedMovingState(smoothedSpeedKmh, qualityOk = true, wasMoving = previousMoving)
        lastSpeedQualityOk = true
        onSpeedChanged(SpeedState(speedKmh = smoothedSpeedKmh, qualityOk = true, isMoving = isSpeedMoving))

        speedLog {
            "Applied phone speed: " +
                    "${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)} km/h "
        }
    }


    private fun computeSpeedMovingState(
        speedKmh: Float,
        qualityOk: Boolean,
        wasMoving: Boolean
    ): Boolean {
        if (!qualityOk) return false
        if (speedVisibilityThresholdKmh <= 0f) return true

        val showThreshold =
            (speedVisibilityThresholdKmh + SPEED_THRESHOLD_HYSTERESIS_KMH).coerceIn(
                0f,
                1f
            )
        val hideThreshold =
            (speedVisibilityThresholdKmh - SPEED_THRESHOLD_HYSTERESIS_KMH).coerceAtLeast(
                0f
            )
        return if (wasMoving) speedKmh >= hideThreshold else speedKmh >= showThreshold
    }

    private fun formatSpeedThresholdForLog(): String {
        if (speedVisibilityThresholdKmh <= 0f) return "always-visible (0.00 km/h)"

        val showThreshold =
            (speedVisibilityThresholdKmh + SPEED_THRESHOLD_HYSTERESIS_KMH).coerceIn(
                0f,
                1f
            )
        val hideThreshold =
            (speedVisibilityThresholdKmh - SPEED_THRESHOLD_HYSTERESIS_KMH).coerceAtLeast(
                0f
            )
        return String.format(
            Locale.ENGLISH,
            "%.2f km/h (show>=%.2f, hide<%.2f)",
            speedVisibilityThresholdKmh,
            showThreshold,
            hideThreshold
        )
    }

    fun hasAnyLocationPermission(): Boolean {
        val fine =
            ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarse =
            ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun saveSpeedVisibilityThreshold(thresholdKmh: Float) {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putFloat(KEY_SPEED_VISIBILITY_THRESHOLD, thresholdKmh.coerceIn(0f, 1f))
            }
    }

    private fun smoothSpeedReading(rawKmh: Float, qualityOk: Boolean, source: String): Float {
        val nowElapsed = SystemClock.elapsedRealtime()
        val safeRawKmh =
            rawKmh.coerceIn(0f, MAX_REASONABLE_SPEED_KMH)
        val previousSmoothedKmh = smoothedSpeedKmh
        val elapsedSinceReliableSampleMs = if (lastReliableSpeedSampleElapsedMs > 0L) {
            nowElapsed - lastReliableSpeedSampleElapsedMs
        } else {
            Long.MAX_VALUE
        }
        val shouldBootstrapResume = qualityOk && (
                !lastSpeedQualityOk ||
                        lastReliableSpeedSampleElapsedMs == 0L ||
                        elapsedSinceReliableSampleMs >= SPEED_RESUME_BOOTSTRAP_GAP_MS
                )
        val alpha = if (shouldBootstrapResume) {
            SPEED_RESUME_BOOTSTRAP_ALPHA
        } else {
            SPEED_SMOOTHING_ALPHA
        }

        smoothedSpeedKmh = (alpha * safeRawKmh) + ((1f - alpha) * previousSmoothedKmh)
        if (smoothedSpeedKmh < 0.05f) smoothedSpeedKmh = 0f

        if (qualityOk) {
            lastReliableSpeedSampleElapsedMs = nowElapsed
        }

        if (shouldBootstrapResume) {
            speedLog {
                "Speed resume bootstrap: source=$source " +
                        "raw=${String.format(Locale.ENGLISH, "%.2f", safeRawKmh)}km/h " +
                        "prev=${String.format(Locale.ENGLISH, "%.2f", previousSmoothedKmh)}km/h " +
                        "next=${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)}km/h " +
                        "gapMs=${if (elapsedSinceReliableSampleMs == Long.MAX_VALUE) "first" else elapsedSinceReliableSampleMs}"
            }
        }

        return smoothedSpeedKmh
    }


    /**
     * Called when local location updates are received.
     *
     * NOTE: Weather data prefers the phone app when connected. Local updates are used for:
     * - validating location permission/provider availability
     * - driving the local speedometer widget from sensor speed
     * - triggering standalone weather refreshes when no phone is connected
     */
    private fun processLocationUpdate(location: Location) {
        lastSpeedLocationUpdateElapsedMs = SystemClock.elapsedRealtime()

        val now = System.currentTimeMillis()
        val ageMs = now - location.time
        val hasSensorSpeed = location.hasSpeed()
        val fineLocationGranted = ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val maxAllowedAccuracyM = if (fineLocationGranted) MAX_ACCEPTABLE_ACCURACY_M else MAX_ACCEPTABLE_COARSE_ACCURACY_M
        val accuracyOk = !location.hasAccuracy() || location.accuracy <= maxAllowedAccuracyM
        val ageOk = ageMs in 0..MAX_ACCEPTABLE_SPEED_AGE_MS
        val sensorKmh = if (hasSensorSpeed) location.speed.coerceAtLeast(0f) * 3.6f else 0f
        val hasReliableSensorSpeed = hasSensorSpeed && accuracyOk && ageOk

        var estimatedKmh = 0f
        var hasReliableEstimatedSpeed = false
        latestLocalLocation?.let { previous ->
            val deltaTimeMs = location.time - previous.time
            if (deltaTimeMs in MIN_SPEED_ESTIMATE_INTERVAL_MS..MAX_SPEED_ESTIMATE_INTERVAL_MS) {
                val rawDistanceM = previous.distanceTo(location).coerceAtLeast(0f)
                val prevAccuracy = if (previous.hasAccuracy()) previous.accuracy else MAX_ACCEPTABLE_ACCURACY_M
                val currAccuracy = if (location.hasAccuracy()) location.accuracy else MAX_ACCEPTABLE_ACCURACY_M
                val noiseFloorM = ((prevAccuracy + currAccuracy) * 0.25f).coerceIn(0.8f, 8f)
                val adjustedDistanceM = (rawDistanceM - noiseFloorM).coerceAtLeast(0f)
                val estimated = (adjustedDistanceM / (deltaTimeMs / 1000f)) * 3.6f
                if (estimated.isFinite() && estimated in 0f..MAX_REASONABLE_SPEED_KMH && accuracyOk && ageOk) {
                    estimatedKmh = estimated
                    hasReliableEstimatedSpeed = true
                }
            }
        }
        latestLocalLocation = Location(location)
        onLocationUpdated(location)

        val qualityOk = hasReliableSensorSpeed || hasReliableEstimatedSpeed

        val rawKmh = when {
            hasReliableSensorSpeed -> sensorKmh
            hasReliableEstimatedSpeed -> estimatedKmh
            else -> 0f
        }
        smoothSpeedReading(
            rawKmh = rawKmh,
            qualityOk = qualityOk,
            source = if (hasReliableSensorSpeed) "sensor" else if (hasReliableEstimatedSpeed) "estimated" else "none"
        )

        val previousMoving = isSpeedMoving
        isSpeedMoving = computeSpeedMovingState(smoothedSpeedKmh, qualityOk, previousMoving)
        lastSpeedQualityOk = qualityOk

        if (previousMoving != isSpeedMoving) {
            val transition = if (isSpeedMoving) "idle -> moving" else "moving -> idle"
            speedLog {
                "Movement transition: $transition at " +
                        "${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)} km/h " +
                        "(threshold=${formatSpeedThresholdForLog()})"
            }
        }

        onSpeedChanged(
            SpeedState(
                speedKmh = smoothedSpeedKmh,
                qualityOk = qualityOk,
                isMoving = isSpeedMoving
            )
        )

        if (now - lastSpeedLogMs >= SPEED_LOG_INTERVAL_MS) {
            lastSpeedLogMs = now
            speedLog {
                "speed=${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)} km/h " +
                    "moving=$isSpeedMoving quality=$qualityOk " +
                    "source=${if (hasReliableSensorSpeed) "sensor" else if (hasReliableEstimatedSpeed) "estimated" else "none"} " +
                    "acc=${if (location.hasAccuracy()) String.format(Locale.ENGLISH, "%.1f", location.accuracy) else "na"}m " +
                    "accLimit=${String.format(Locale.ENGLISH, "%.1f", maxAllowedAccuracyM)}m " +
                    "sensor=${String.format(Locale.ENGLISH, "%.2f", sensorKmh)}km/h " +
                    "estimate=${String.format(Locale.ENGLISH, "%.2f", estimatedKmh)}km/h ageMs=$ageMs " +
                    "threshold=${formatSpeedThresholdForLog()}"
            }
        }
    }

    fun onLocationPermissionDenied() {
        stopLocationUpdates()
        smoothedSpeedKmh = 0f
        lastReliableSpeedSampleElapsedMs = 0L
        isSpeedMoving = false
        lastSpeedQualityOk = false

        onLocationUnavailable()
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (released || started) return
        val fine = ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "startLocationUpdates(): fine=$fine coarse=$coarse")
        speedLog { "startLocationUpdates fine=$fine coarse=$coarse" }

        if (!fine && !coarse) {
            onLocationUnavailable()
            latestLocalLocation = null
            smoothedSpeedKmh = 0f
            lastReliableSpeedSampleElapsedMs = 0L
            isSpeedMoving = false
            lastSpeedQualityOk = false
            speedLog { "Location permission denied -> speed pipeline disabled" }
            return
        }

        started = true
        lifecycleGeneration += 1

        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        speedLocationManager = locationManager

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val netEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val enabledProviders = locationManager.getProviders(true)
        val locationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
        Log.d(TAG, "Providers enabled: GPS=$gpsEnabled NETWORK=$netEnabled")
        Log.d(TAG, "Enabled providers: $enabledProviders")
        Log.d(TAG, "isLocationEnabled=$locationEnabled")
        speedLog { "Providers status: GPS=$gpsEnabled NETWORK=$netEnabled locationEnabled=$locationEnabled enabled=$enabledProviders" }
        if (!locationEnabled || (!gpsEnabled && !netEnabled)) {
            speedLog { "Location services unavailable (locationEnabled=$locationEnabled, gps=$gpsEnabled, network=$netEnabled)." }
        }

        val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        Log.d(TAG, "Last known gps=$lastGps net=$lastNet")

        try {
            speedLocationListener = locationListener

            val requestedProviders = linkedSetOf<String>()

            fun requestProvider(provider: String) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        SPEED_LOCATION_MIN_TIME_MS,
                        SPEED_LOCATION_MIN_DISTANCE_M,
                        locationListener
                    )
                    requestedProviders.add(provider)
                    speedLog { "Requested location updates from provider=$provider" }
                } catch (e: Exception) {
                    speedLog { "Failed to request updates from provider=$provider: ${e.message}" }
                }
            }

            if (enabledProviders.isNotEmpty()) {
                for (provider in enabledProviders) {
                    requestProvider(provider)
                }
            } else {
                // Keep a passive listener alive as fallback. Useful on devices where
                // GPS/NETWORK are off but another provider still emits updates.
                requestProvider(LocationManager.PASSIVE_PROVIDER)
            }

            val locationCandidates = linkedSetOf<String>().apply {
                addAll(requestedProviders)
                addAll(enabledProviders)
            }
            val fallbackProviders = locationCandidates
                .ifEmpty { listOf("fused", LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER) }
            startSpeedFallbackPolling( fallbackProviders.toList())

            var bestLocation: Location? = null
            for (provider in locationCandidates) {
                val candidate = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
                if (bestLocation == null || candidate.time > (bestLocation.time)) {
                    bestLocation = candidate
                }
            }

            if (bestLocation != null) {
                processLocationUpdate(bestLocation)
            } else {
                latestLocalLocation = null
                lastSpeedQualityOk = false
                onSpeedChanged(
                    SpeedState(
                        speedKmh = 0f,
                        qualityOk = false,
                        isMoving = false
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location updates", e)
            speedLog { "Failed to start location updates: ${e.message}" }
            stopSpeedFallbackPolling()
            latestLocalLocation = null
            smoothedSpeedKmh = 0f
            lastReliableSpeedSampleElapsedMs = 0L
            isSpeedMoving = false
            lastSpeedQualityOk = false
            onSpeedUnavailable()
            started = false
        }
    }

    fun stopLocationUpdates() {
        started = false
        lifecycleGeneration += 1
        fallbackCancellationSignal?.cancel()
        fallbackCancellationSignal = null
        fallbackRequestInFlight = false
        speedLocationManager?.let { manager ->
            try {
                manager.removeUpdates(locationListener)
                speedLog { "Location updates stopped" }
            } catch (e: Exception) {
                speedLog { "Error while stopping location updates: ${e.message}" }
            }
        }
        stopSpeedFallbackPolling()
        speedLocationListener = null
        speedLocationManager = null
        latestLocalLocation = null
        lastSpeedLocationUpdateElapsedMs = 0L
        lastReliableSpeedSampleElapsedMs = 0L
        lastSpeedQualityOk = false
        isSpeedMoving = false
    }

    fun release() {
        if (released) return
        released = true
        stopLocationUpdates()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startSpeedFallbackPolling(providers: List<String>) {
        stopSpeedFallbackPolling()
        speedFallbackProviders = providers.distinct()

        val runnable = object : Runnable {
            override fun run() {
                if (!started || released) return
                val manager = speedLocationManager ?: return
                if (!hasAnyLocationPermission()) return

                val elapsedSinceLast = SystemClock.elapsedRealtime() - lastSpeedLocationUpdateElapsedMs
                if (lastSpeedLocationUpdateElapsedMs == 0L || elapsedSinceLast >=SPEED_STALE_UPDATE_WINDOW_MS) {
                    val updatedFromCache = tryUpdateSpeedFromLastKnown(manager, speedFallbackProviders)
                    if (!updatedFromCache && !fallbackRequestInFlight) {
                        requestSingleSpeedLocation(manager, speedFallbackProviders)
                    }
                }

                handler.postDelayed(this,SPEED_FALLBACK_POLL_INTERVAL_MS)
            }
        }

        speedFallbackRunnable = runnable
        handler.postDelayed(runnable,SPEED_FALLBACK_POLL_INTERVAL_MS)
        speedLog { "Started speed fallback polling providers=$speedFallbackProviders" }
    }

    private fun stopSpeedFallbackPolling() {
        speedFallbackRunnable?.let { handler.removeCallbacks(it) }
        speedFallbackRunnable = null
        speedFallbackProviders = emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun tryUpdateSpeedFromLastKnown(locationManager: LocationManager, providers: List<String>): Boolean {
        if (!hasAnyLocationPermission()) return false
        var bestLocation: Location? = null
        var bestProvider: String? = null

        for (provider in providers.distinct()) {
            val candidate = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (bestLocation == null || candidate.time > (bestLocation.time)) {
                bestLocation = candidate
                bestProvider = provider
            }
        }

        val location = bestLocation ?: return false
        val previousTime = latestLocalLocation?.time ?: Long.MIN_VALUE
        if (location.time <= previousTime) {
            return false
        }

        speedLog {
            "Fallback lastKnown location: provider=${bestProvider ?: "unknown"} hasSpeed=${location.hasSpeed()} " +
                    "acc=${if (location.hasAccuracy()) String.format(Locale.ENGLISH, "%.1f", location.accuracy) else "na"} " +
                    "time=${location.time}"
        }
        processLocationUpdate(location)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleSpeedLocation(
        locationManager: LocationManager,
        providers: List<String>
    ) {
        if (!started || released || fallbackRequestInFlight || !hasAnyLocationPermission()) return

        val requestGeneration = lifecycleGeneration

        for (provider in providers) {
            val enabled = runCatching {
                locationManager.isProviderEnabled(provider)
            }.getOrNull()

            speedLog {
                "Fallback probe provider=$provider enabled=${enabled ?: "unknown"}"
            }

            try {
                val cancellationSignal = CancellationSignal()
                fallbackCancellationSignal = cancellationSignal
                fallbackRequestInFlight = true
                LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    provider,
                    cancellationSignal,
                    ContextCompat.getMainExecutor(activity)
                ) { location: Location? ->
                    fallbackRequestInFlight = false
                    fallbackCancellationSignal = null

                    if (!started || released || requestGeneration != lifecycleGeneration) {
                        return@getCurrentLocation
                    }

                    if (location == null) {
                        speedLog {
                            "Fallback current location returned null for provider=$provider"
                        }
                        return@getCurrentLocation
                    }

                    speedLog {
                        "Fallback location: provider=$provider hasSpeed=${location.hasSpeed()} " +
                                "acc=${if (location.hasAccuracy()) String.format(Locale.ENGLISH, "%.1f", location.accuracy) else "na"} " +
                                "time=${location.time}"
                    }

                    lastSpeedLocationUpdateElapsedMs =
                        SystemClock.elapsedRealtime()

                    processLocationUpdate(location)
                }

                speedLog {
                    "Requested fallback current location from provider=$provider"
                }

                return
            } catch (e: Exception) {
                fallbackRequestInFlight = false
                fallbackCancellationSignal = null
                speedLog {
                    "Failed fallback current location provider=$provider: ${e.message}"
                }
            }
        }
    }

    fun restoreVisibilityThreshold(): Float {
        speedVisibilityThresholdKmh =
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_SPEED_VISIBILITY_THRESHOLD, SPEED_THRESHOLD_KMH)
                .coerceIn(0f, 1f)

        return speedVisibilityThresholdKmh
    }
    fun setVisibilityThreshold(thresholdKmh: Float) {
        speedVisibilityThresholdKmh = thresholdKmh.coerceIn(0f, 1f)
        saveSpeedVisibilityThreshold(speedVisibilityThresholdKmh)

        val previousMoving = isSpeedMoving
        isSpeedMoving = computeSpeedMovingState(
            speedKmh = smoothedSpeedKmh,
            qualityOk = lastSpeedQualityOk,
            wasMoving = previousMoving
        )

        onSpeedChanged(
            SpeedState(
                speedKmh = smoothedSpeedKmh,
                qualityOk = lastSpeedQualityOk,
                isMoving = isSpeedMoving
            )
        )

        if (previousMoving != isSpeedMoving) {
            val transition = if (isSpeedMoving) "idle -> moving" else "moving -> idle"
            speedLog {
                "Movement transition: $transition at " +
                        "${String.format(Locale.ENGLISH, "%.2f", smoothedSpeedKmh)} km/h " +
                        "(threshold=${formatSpeedThresholdForLog()})"
            }
        }
    }
}