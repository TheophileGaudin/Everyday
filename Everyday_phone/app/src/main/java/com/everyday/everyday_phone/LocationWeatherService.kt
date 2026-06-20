package com.everyday.everyday_phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.everyday.shared.sync.LocationName
import com.everyday.shared.sync.SpeedSnapshot
import com.everyday.shared.sync.WeatherData
import com.everyday.shared.sync.WeatherDataProvider
import com.everyday.shared.sync.WeatherSnapshot
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Service that provides location and weather data to the glasses.
 *
 * Simple flow:
 * 1. Request location updates from FusedLocationProviderClient
 * 2. When location arrives, geocode it and fetch weather
 * 3. Keep cached payload fresh so the glasses can pull it on their own cadence
 */
class LocationWeatherService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "location_weather_channel"
        private const val EXTRA_FORCE_REFRESH = "extra_force_refresh"
        private const val LOCATION_INTERVAL_MS = 1000L   // Request live speed updates about once per second
        private const val WEATHER_INTERVAL_MS = 600000L  // Fetch weather every 10 minutes
        private const val GEOCODE_INTERVAL_MS = 180000L  // Reverse-geocode every 3 minutes max
        private const val MIN_SPEED_ESTIMATE_INTERVAL_MS = 500L
        private const val MAX_SPEED_ESTIMATE_INTERVAL_MS = 120000L
        private const val MAX_REASONABLE_SPEED_MPS = 60f
        private const val MAX_ACCEPTABLE_SPEED_AGE_MS = 10000L
        private const val MAX_ACCEPTABLE_ACCURACY_M = 60f
        private const val MANUAL_LOCATION_TIMEOUT_MS = 20000L

        //This is my free API Key. If too many people access this service it will saturate.
        private const val GEOCODE_MAPS_CO_API_KEY = "6974a768992ec642181119eby0e50e7"

        var instance: LocationWeatherService? = null
            private set

        var onSnapshotChanged: ((WeatherSnapshot) -> Unit)? = null
        var onSpeedSnapshotChanged: ((SpeedSnapshot) -> Unit)? = null

        fun start(context: Context, forceRefresh: Boolean = false) {
            val intent = Intent(context, LocationWeatherService::class.java).apply {
                putExtra(EXTRA_FORCE_REFRESH, forceRefresh)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationWeatherService::class.java))
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    // Cached data - Single source of truth for location/weather
    private var cachedTown: String? = null
    private var cachedCountryCode: String? = null
    private var cachedWeatherData: WeatherData? = null  // Full weather data including description
    private var cachedLat: Double? = null
    private var cachedLon: Double? = null
    private var cachedSpeedMps: Float? = null
    private var cachedSpeedSnapshot: SpeedSnapshot? = null
    private var previousSpeedLocation: Location? = null
    private var lastWeatherFetch: Long = 0
    private var lastGeocodeFetch: Long = 0

    // Timestamp when the current payload was last updated (used to prevent stale data on glasses)
    private var payloadTimestamp: Long = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        fileLog("LocationWeatherService created")

        acquireWakeLock()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupLocationCallback()
        startForegroundNotification()
        startLocationUpdates()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EverydayPhone::LocationWeatherWakeLock"
        ).apply { acquire() }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                fileLog("LocationCallback.onLocationResult called with ${locationResult.locations.size} locations")
                locationResult.lastLocation?.let { processLocation(it) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                fileLog("Location availability changed: isLocationAvailable=${availability.isLocationAvailable}")
            }
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Everyday Location Service")
            .setContentText("Providing location to glasses")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Location updates for glasses"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            fileLog("Location permission not granted")
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS / 2)
            .build()

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        fileLog("Location updates started")

        // Also try to get last known location immediately
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    fileLog("Got last known location on startup: ${location.latitude}, ${location.longitude}")
                    processLocation(location)
                } else {
                    fileLog("lastLocation returned null - no cached location available")
                }
            }
            .addOnFailureListener { e ->
                fileLog("lastLocation failed: ${e.message}")
            }
    }

    /**
     * Process a new location: geocode it and optionally fetch weather.
     */
    private fun processLocation(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val speedSnapshot = resolveSpeedSnapshot(location)
        cachedSpeedSnapshot = speedSnapshot
        cachedSpeedMps = if (speedSnapshot.qualityOk) speedSnapshot.speedMps else null
        notifySpeedSnapshotChanged(speedSnapshot)
        fileLog(
            "Processing location: $lat, $lon " +
                    "speedMps=${cachedSpeedMps ?: "na"} quality=${speedSnapshot.qualityOk}"
        )

        cachedLat = lat
        cachedLon = lon

        refreshCachedPayload(lat, lon, forceWeatherFetch = false)
    }

    /**
     * Refresh the cached town/country/weather payload for the current coordinates.
     * This is shared by real location updates and explicit force-refreshes so we can
     * refresh weather without mutating the speed estimator with synthetic locations.
     */
    private fun refreshCachedPayload(lat: Double, lon: Double, forceWeatherFetch: Boolean) {
        val now = System.currentTimeMillis()
        val needsGeocode = cachedTown == null ||
                cachedCountryCode == null ||
                (now - lastGeocodeFetch > GEOCODE_INTERVAL_MS)
        val needsWeatherFetch = forceWeatherFetch || (now - lastWeatherFetch > WEATHER_INTERVAL_MS)
        if (!needsGeocode && !needsWeatherFetch) {
            return
        }

        Thread {
            if (needsGeocode) {
                geocodeLocation(lat, lon)
                lastGeocodeFetch = now
            }

            if (needsWeatherFetch) {
                lastWeatherFetch = now
                // Explicit force-refreshes can push immediately when fresh data arrives.
                fetchWeather(lat, lon, sendImmediately = forceWeatherFetch)
            }
        }.start()
    }

    private fun resolveSpeedSnapshot(location: Location): SpeedSnapshot {
        val ageMs = System.currentTimeMillis() - location.time
        val ageOk = ageMs in 0..MAX_ACCEPTABLE_SPEED_AGE_MS
        val accuracyOk = !location.hasAccuracy() || location.accuracy <= MAX_ACCEPTABLE_ACCURACY_M
        val sensorMps = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else Float.NaN
        val validSensorMps = sensorMps.takeIf {
            it.isFinite() && it in 0f..MAX_REASONABLE_SPEED_MPS && ageOk && accuracyOk
        }
        val previous = previousSpeedLocation
        previousSpeedLocation = Location(location)
        if (validSensorMps != null) {
            return SpeedSnapshot(
                speedMps = validSensorMps,
                qualityOk = true,
                timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }
        if (previous == null) {
            return SpeedSnapshot(
                speedMps = null,
                qualityOk = false,
                timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }

        val deltaTimeMs = location.time - previous.time
        if (deltaTimeMs !in MIN_SPEED_ESTIMATE_INTERVAL_MS..MAX_SPEED_ESTIMATE_INTERVAL_MS) {
            return SpeedSnapshot(
                speedMps = null,
                qualityOk = false,
                timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        }

        val rawDistanceM = previous.distanceTo(location).coerceAtLeast(0f)
        val prevAccuracy = if (previous.hasAccuracy()) previous.accuracy else 25f
        val currAccuracy = if (location.hasAccuracy()) location.accuracy else 25f
        val noiseFloorM = ((prevAccuracy + currAccuracy) * 0.25f).coerceIn(0.5f, 10f)
        val adjustedDistanceM = (rawDistanceM - noiseFloorM).coerceAtLeast(0f)
        val estimatedMps = adjustedDistanceM / (deltaTimeMs / 1000f)
        val validEstimatedMps = estimatedMps.takeIf {
            it.isFinite() && it in 0f..MAX_REASONABLE_SPEED_MPS && ageOk && accuracyOk
        }
        return SpeedSnapshot(
            speedMps = validEstimatedMps,
            qualityOk = validEstimatedMps != null,
            timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }

    private fun notifySpeedSnapshotChanged(snapshot: SpeedSnapshot? = cachedSpeedSnapshot) {
        snapshot ?: return
        onSpeedSnapshotChanged?.invoke(snapshot)
    }

    private fun updateLocationCacheForRefresh(location: Location) {
        cachedLat = location.latitude
        cachedLon = location.longitude
        val speedSnapshot = resolveSpeedSnapshot(location)
        cachedSpeedSnapshot = speedSnapshot
        cachedSpeedMps = if (speedSnapshot.qualityOk) speedSnapshot.speedMps else null
        notifySpeedSnapshotChanged(speedSnapshot)
    }

    private fun geocodeLocation(lat: Double, lon: Double) {
        WeatherDataProvider.resolveLocationName(lat, lon, GEOCODE_MAPS_CO_API_KEY)
            .onSuccess { locationName: LocationName ->
                cachedTown = locationName.town
                cachedCountryCode = locationName.countryCode
                payloadTimestamp = System.currentTimeMillis()
                fileLog("Geocoded: $cachedTown, countryCode=$cachedCountryCode, timestamp=$payloadTimestamp")
                notifySnapshotChanged()
            }
            .onFailure { e ->
                fileLog("Geocoding failed: ${e.message}")
            }
    }

    private fun fetchWeather(lat: Double, lon: Double, sendImmediately: Boolean = false) {
        WeatherDataProvider.fetchWeatherAsync(lat, lon) { weatherData ->
            if (weatherData != null) {
                cachedWeatherData = weatherData
                // Update payload timestamp when weather data changes
                payloadTimestamp = System.currentTimeMillis()
                fileLog("Weather fetched: temp=${weatherData.tempCurrent}°C, desc=${weatherData.weatherDescCurrent}, timestamp=$payloadTimestamp")
                // Send to glasses after weather is updated, ensuring data is synchronized
                if (sendImmediately) {
                    notifySnapshotChanged()
                }
            } else {
                fileLog("Weather fetch failed - callback returned null")
            }
        }
    }

    fun forceUpdateDetailed(callback: (Result<WeatherSnapshot>) -> Unit) {
        fileLog("Detailed force update requested")
        val lat = cachedLat
        val lon = cachedLon
        if (lat != null && lon != null) {
            refreshCachedPayloadDetailed(lat, lon, callback)
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            callback(Result.failure(SecurityException("Location permission is not granted")))
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    updateLocationCacheForRefresh(location)
                    refreshCachedPayloadDetailed(location.latitude, location.longitude, callback)
                } else {
                    requestCurrentLocationForDetailedRefresh(callback)
                }
            }
            .addOnFailureListener { error ->
                fileLog("Detailed refresh lastLocation failed, requesting current location: ${error.message}")
                requestCurrentLocationForDetailedRefresh(callback)
            }
    }

    private fun requestCurrentLocationForDetailedRefresh(callback: (Result<WeatherSnapshot>) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            callback(Result.failure(SecurityException("Location permission is not granted")))
            return
        }

        fileLog("Detailed refresh requesting current phone location")
        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        )
            .addOnSuccessListener { location ->
                if (location == null) {
                    requestSingleLocationUpdateForDetailedRefresh(callback)
                    return@addOnSuccessListener
                }

                updateLocationCacheForRefresh(location)
                refreshCachedPayloadDetailed(location.latitude, location.longitude, callback)
            }
            .addOnFailureListener { error ->
                fileLog("Detailed refresh getCurrentLocation failed, requesting single update: ${error.message}")
                requestSingleLocationUpdateForDetailedRefresh(callback)
            }
    }

    private fun requestSingleLocationUpdateForDetailedRefresh(callback: (Result<WeatherSnapshot>) -> Unit) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            callback(Result.failure(SecurityException("Location permission is not granted")))
            return
        }

        fileLog("Detailed refresh waiting for one location update")
        val completed = AtomicBoolean(false)
        lateinit var singleUpdateCallback: LocationCallback

        fun finish(result: Result<WeatherSnapshot>) {
            if (!completed.compareAndSet(false, true)) return
            fusedLocationClient.removeLocationUpdates(singleUpdateCallback)
            callback(result)
        }

        singleUpdateCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location == null) {
                    return
                }
                updateLocationCacheForRefresh(location)
                refreshCachedPayloadDetailed(location.latitude, location.longitude) { result ->
                    finish(result)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                fileLog("Manual refresh location availability: ${availability.isLocationAvailable}")
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdates(1)
            .build()

        mainHandler.postDelayed(
            {
                finish(
                    Result.failure(
                        IllegalStateException(
                            "Unable to get current phone location after ${MANUAL_LOCATION_TIMEOUT_MS / 1000} seconds. " +
                                "Check that phone Location is enabled, Everyday has precise location permission, " +
                                "and the phone has a clear enough GPS/Wi-Fi/cell fix."
                        )
                    )
                )
            },
            MANUAL_LOCATION_TIMEOUT_MS
        )

        fusedLocationClient.requestLocationUpdates(request, singleUpdateCallback, Looper.getMainLooper())
            .addOnFailureListener { error ->
                finish(Result.failure(error))
            }
    }

    private fun refreshCachedPayloadDetailed(
        lat: Double,
        lon: Double,
        callback: (Result<WeatherSnapshot>) -> Unit
    ) {
        Thread {
            val failures = mutableListOf<Throwable>()
            val now = System.currentTimeMillis()

            WeatherDataProvider.resolveLocationName(lat, lon, GEOCODE_MAPS_CO_API_KEY)
                .onSuccess { locationName: LocationName ->
                    cachedTown = locationName.town
                    cachedCountryCode = locationName.countryCode
                    lastGeocodeFetch = now
                    fileLog("Manual geocode refreshed: $cachedTown, countryCode=$cachedCountryCode")
                }
                .onFailure { error ->
                    fileLog("Manual geocoding failed: ${error.message}")
                    failures += error
                }

            WeatherDataProvider.fetchWeather(lat, lon)
                .onSuccess { weatherData ->
                    cachedWeatherData = weatherData
                    lastWeatherFetch = now
                    fileLog("Manual weather refreshed: temp=${weatherData.tempCurrent}C, desc=${weatherData.weatherDescCurrent}")
                }
                .onFailure { error ->
                    fileLog("Manual weather fetch failed: ${error.message}")
                    failures += error
                }

            payloadTimestamp = System.currentTimeMillis()
            val snapshot = getCurrentLocationData()
            if (snapshot != null) {
                onSnapshotChanged?.invoke(snapshot)
            }

            if (failures.isEmpty()) {
                if (snapshot == null) {
                    callback(Result.failure(IllegalStateException("Location/weather refresh completed without a usable snapshot")))
                } else {
                    callback(Result.success(snapshot))
                }
            } else {
                val error = IllegalStateException("One or more location/weather calls failed")
                failures.forEach(error::addSuppressed)
                callback(Result.failure(error))
            }
        }.start()
    }

    private fun notifySnapshotChanged() {
        val town = cachedTown
        val countryCode = cachedCountryCode

        // Only send if we have valid data with a valid timestamp
        // payloadTimestamp == 0 means data hasn't been refreshed this session (stale from previous run)
        if (town != null && countryCode != null && payloadTimestamp > 0) {
            // Send full weather data with description and timestamp for proper icon display
            onSnapshotChanged?.invoke(
                WeatherSnapshot(
                    town = town,
                    countryCode = countryCode,
                    weather = cachedWeatherData,
                    timestampMs = payloadTimestamp,
                    speedMps = cachedSpeedMps
                )
            )
            fileLog("Location/weather snapshot changed: $town, countryCode=$countryCode, timestamp=$payloadTimestamp")
        } else if (payloadTimestamp == 0L) {
            fileLog("Skipping send - timestamp is 0 (data not yet refreshed this session)")
        }
    }

    /**
     * Get current cached location data. Used by RfcommServer for immediate sends.
     * Returns town, countryCode, full WeatherData, and timestamp for proper display.
     */
    fun getCurrentLocationData(): WeatherSnapshot? {
        val town = cachedTown ?: return null
        val countryCode = cachedCountryCode ?: return null
        return WeatherSnapshot(
            town = town,
            countryCode = countryCode,
            weather = cachedWeatherData,
            timestampMs = payloadTimestamp,
            speedMps = cachedSpeedMps
        )
    }

    fun getCurrentSpeedSnapshot(): SpeedSnapshot? = cachedSpeedSnapshot

    /**
     * Force an immediate weather/location update and send to glasses.
     * Called when glasses wake from sleep or reconnect.
     */
    fun forceUpdate() {
        fileLog("Force update requested")
        val lat = cachedLat
        val lon = cachedLon
        if (lat != null && lon != null) {
            fileLog("Force update using cached coordinates; preserving speed state")
            refreshCachedPayload(lat, lon, forceWeatherFetch = true)
        } else {
            // No cached location - try to get one
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        updateLocationCacheForRefresh(it)
                        refreshCachedPayload(it.latitude, it.longitude, forceWeatherFetch = true)
                    }
                }
            }
        }
    }

    /**
     * Invalidate all cached data and force a fresh fetch.
     * Called when the app is started to ensure we don't send stale data from a previous session.
     */
    fun invalidateCacheAndRefresh() {
        fileLog("Invalidating cache and forcing refresh")
        // Clear the timestamp to prevent sending stale data
        payloadTimestamp = 0
        cachedWeatherData = null
        cachedSpeedMps = null
        cachedSpeedSnapshot = null
        previousSpeedLocation = null
        lastWeatherFetch = 0
        lastGeocodeFetch = 0
        // Force a fresh location/weather fetch
        forceUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FORCE_REFRESH, false) == true) {
            invalidateCacheAndRefresh()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        wakeLock?.let { if (it.isHeld) it.release() }
        instance = null
        fileLog("LocationWeatherService destroyed")
    }
}
