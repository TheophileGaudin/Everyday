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
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Locale


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
        private const val LOCATION_INTERVAL_MS = 5000L   // Request location every 5 seconds (for speedometer)
        private const val WEATHER_INTERVAL_MS = 600000L  // Fetch weather every 10 minutes
        private const val GEOCODE_INTERVAL_MS = 180000L  // Reverse-geocode every 3 minutes max
        private const val MIN_SPEED_ESTIMATE_INTERVAL_MS = 500L
        private const val MAX_SPEED_ESTIMATE_INTERVAL_MS = 120000L
        private const val MAX_REASONABLE_SPEED_MPS = 60f

        //This is my free API Key. If too many people access this service it will saturate.
        private const val GEOCODE_MAPS_CO_API_KEY = "6974a768992ec642181119eby0e50e7"

        var instance: LocationWeatherService? = null
            private set

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
    private var wakeLock: PowerManager.WakeLock? = null
    // Cached data - Single source of truth for location/weather
    private var cachedTown: String? = null
    private var cachedCountry: String? = null
    private var cachedCountryCode: String? = null
    private var cachedWeatherData: WeatherData? = null  // Full weather data including description
    private var cachedLat: Double? = null
    private var cachedLon: Double? = null
    private var cachedSpeedMps: Float? = null
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
        cachedSpeedMps = resolveSpeedMps(location)
        fileLog("Processing location: $lat, $lon speedMps=${cachedSpeedMps ?: "na"}")

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
        Thread {
            val now = System.currentTimeMillis()
            val needsGeocode = cachedTown == null ||
                    cachedCountryCode == null ||
                    (now - lastGeocodeFetch > GEOCODE_INTERVAL_MS)
            if (needsGeocode) {
                geocodeLocation(lat, lon)
                lastGeocodeFetch = now
            }

            // Fetch weather if needed
            val needsWeatherFetch = forceWeatherFetch || (now - lastWeatherFetch > WEATHER_INTERVAL_MS)
            if (needsWeatherFetch) {
                lastWeatherFetch = now
                // Explicit force-refreshes can push immediately when fresh data arrives.
                fetchWeather(lat, lon, sendImmediately = forceWeatherFetch)
            } else {
                // Normal background refreshes only update cache; transport is driven elsewhere.
                if (forceWeatherFetch) {
                    sendToGlassesIfConnected()
                }
            }
        }.start()
    }

    private fun resolveSpeedMps(location: Location): Float? {
        val sensorMps = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else Float.NaN
        val validSensorMps = sensorMps.takeIf { it.isFinite() && it in 0f..MAX_REASONABLE_SPEED_MPS }
        val previous = previousSpeedLocation
        previousSpeedLocation = Location(location)
        if (validSensorMps != null) {
            return validSensorMps
        }
        if (previous == null) {
            return cachedSpeedMps
        }

        val deltaTimeMs = location.time - previous.time
        if (deltaTimeMs !in MIN_SPEED_ESTIMATE_INTERVAL_MS..MAX_SPEED_ESTIMATE_INTERVAL_MS) {
            return cachedSpeedMps
        }

        val rawDistanceM = previous.distanceTo(location).coerceAtLeast(0f)
        val prevAccuracy = if (previous.hasAccuracy()) previous.accuracy else 25f
        val currAccuracy = if (location.hasAccuracy()) location.accuracy else 25f
        val noiseFloorM = ((prevAccuracy + currAccuracy) * 0.25f).coerceIn(0.5f, 10f)
        val adjustedDistanceM = (rawDistanceM - noiseFloorM).coerceAtLeast(0f)
        val estimatedMps = adjustedDistanceM / (deltaTimeMs / 1000f)
        return estimatedMps.takeIf { it.isFinite() && it in 0f..MAX_REASONABLE_SPEED_MPS } ?: cachedSpeedMps
    }

    private fun geocodeLocation(lat: Double, lon: Double) {
        try {
            // geocode.maps.co now requires an API key
            val apiKey = URLEncoder.encode(GEOCODE_MAPS_CO_API_KEY, "UTF-8")
            val url = "https://geocode.maps.co/reverse?lat=$lat&lon=$lon&api_key=$apiKey"
            val response = URL(url).readText()
            val json = JSONObject(response)
            val address = json.getJSONObject("address")

            cachedTown = address.optString("city", null)
                ?: address.optString("town", null)
                ?: address.optString("village", null)
                ?: address.optString("municipality", null)
                ?: "Unknown"

            // Prefer raw country code so glasses can localize however they want.
            // geocode.maps.co typically returns lowercase like "gb" -> normalize to "GB".
            cachedCountryCode = address.optString("country_code", null)
                ?.takeIf { it.isNotBlank() }
                ?.uppercase(Locale.ROOT)
                ?: "Unknown"

            // Update payload timestamp when location data changes
            payloadTimestamp = System.currentTimeMillis()
            fileLog("Geocoded: $cachedTown, countryCode=$cachedCountryCode, timestamp=$payloadTimestamp")

        } catch (e: Exception) {
            fileLog("Geocoding failed: ${e.message}")
        }
    }

    private fun fetchWeather(lat: Double, lon: Double, sendImmediately: Boolean = false) {
        // Use WeatherHelper for consistent weather fetching with description
        WeatherHelper.fetchWeather(lat, lon) { weatherData ->
            if (weatherData != null) {
                cachedWeatherData = weatherData
                // Update payload timestamp when weather data changes
                payloadTimestamp = System.currentTimeMillis()
                fileLog("Weather fetched: temp=${weatherData.tempCurrent}°C, desc=${weatherData.weatherDescCurrent}, timestamp=$payloadTimestamp")
                // Send to glasses after weather is updated, ensuring data is synchronized
                if (sendImmediately) {
                    sendToGlassesIfConnected()
                }
            } else {
                fileLog("Weather fetch failed - callback returned null")
            }
        }
    }

    private fun sendToGlassesIfConnected() {
        val server = RfcommServer.instance ?: return
        if (!server.isConnected()) return

        val town = cachedTown
        val countryCode = cachedCountryCode

        // Only send if we have valid data with a valid timestamp
        // payloadTimestamp == 0 means data hasn't been refreshed this session (stale from previous run)
        if (town != null && countryCode != null && payloadTimestamp > 0) {
            // Send full weather data with description and timestamp for proper icon display
            server.sendLocationUpdate(town, countryCode, cachedWeatherData, payloadTimestamp, cachedSpeedMps)
            fileLog("Location speed payload: speedMps=${cachedSpeedMps ?: "na"}")
            fileLog("Sent to glasses: $town, countryCode=$countryCode, weather=${cachedWeatherData?.tempCurrent}°C/${cachedWeatherData?.weatherDescCurrent}, timestamp=$payloadTimestamp")
        } else if (payloadTimestamp == 0L) {
            fileLog("Skipping send - timestamp is 0 (data not yet refreshed this session)")
        }
    }

    /**
     * Push the current cached payload to the glasses without forcing a provider refresh.
     * Returns true when there was a valid payload to send.
     */
    fun pushCachedPayloadToGlasses(): Boolean {
        val hasPayload = cachedTown != null && cachedCountryCode != null && payloadTimestamp > 0L
        if (hasPayload) {
            sendToGlassesIfConnected()
        } else if (payloadTimestamp == 0L) {
            fileLog("Skipping cached payload push - timestamp is 0 (data not yet refreshed this session)")
        }
        return hasPayload
    }

    /**
     * Get current cached location data. Used by RfcommServer for immediate sends.
     * Returns town, countryCode, full WeatherData, and timestamp for proper display.
     */
    fun getCurrentLocationData(): LocationWeatherData? {
        val town = cachedTown ?: return null
        val countryCode = cachedCountryCode ?: return null
        return LocationWeatherData(town, countryCode, cachedWeatherData, payloadTimestamp, cachedSpeedMps)
    }

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
                        cachedLat = it.latitude
                        cachedLon = it.longitude
                        cachedSpeedMps = resolveSpeedMps(it)
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
        previousSpeedLocation = null
        lastWeatherFetch = 0
        lastGeocodeFetch = 0
        // Force a fresh location/weather fetch
        forceUpdate()
    }

    data class LocationWeatherData(
        val town: String,
        val countryCode: String,
        val weather: WeatherData?,
        val timestamp: Long,  // When this data was last updated
        val speedMps: Float?
    )

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
