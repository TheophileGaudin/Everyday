package com.everyday.shared.sync

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object WeatherDataProvider {
    fun fetchWeatherAsync(lat: Double, lon: Double, callback: (WeatherData?) -> Unit) {
        Thread {
            callback(fetchWeather(lat, lon).getOrNull())
        }.start()
    }

    fun fetchWeather(lat: Double, lon: Double): Result<WeatherData> {
        return runCatching {
            val urlString =
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                    "&timezone=auto"
            val connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Weather HTTP ${connection.responseCode}")
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.use { it.readText() }
                val json = JSONObject(response)
                val current = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")
                val timezoneId = json.optString("timezone", TimeZone.getDefault().id)

                val currentCode = current.getInt("weather_code")
                val dailyCode = daily.getJSONArray("weather_code").getInt(0)
                WeatherData(
                    tempCurrent = current.getDouble("temperature_2m"),
                    weatherCodeCurrent = currentCode,
                    weatherDescCurrent = getWeatherDescription(currentCode),
                    tempMaxToday = daily.getJSONArray("temperature_2m_max").getDouble(0),
                    tempMinToday = daily.getJSONArray("temperature_2m_min").getDouble(0),
                    weatherDescToday = getWeatherDescription(dailyCode),
                    sunriseEpochMs = parseLocalDateTimeToEpochMillis(
                        daily.optJSONArray("sunrise")?.optString(0),
                        timezoneId
                    ),
                    sunsetEpochMs = parseLocalDateTimeToEpochMillis(
                        daily.optJSONArray("sunset")?.optString(0),
                        timezoneId
                    )
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    fun resolveLocationName(lat: Double, lon: Double, geocodeApiKey: String): Result<LocationName> {
        return runCatching {
            val apiKey = URLEncoder.encode(geocodeApiKey, "UTF-8")
            val url = "https://geocode.maps.co/reverse?lat=$lat&lon=$lon&api_key=$apiKey"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    throw IllegalStateException("Geocode HTTP $responseCode${errorText?.let { ": $it" } ?: ""}")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val address = JSONObject(response).getJSONObject("address")
                val town = address.optString("city").takeIf { it.isNotBlank() }
                    ?: address.optString("town").takeIf { it.isNotBlank() }
                    ?: address.optString("village").takeIf { it.isNotBlank() }
                    ?: address.optString("municipality").takeIf { it.isNotBlank() }
                    ?: "Unknown"
                val countryCode = address.optString("country_code")
                    .takeIf { it.isNotBlank() }
                    ?.uppercase(Locale.ROOT)
                    ?: "Unknown"
                LocationName(town, countryCode)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun getWeatherDescription(code: Int): String {
        return when (code) {
            0 -> "sunny.png"
            1 -> "light_clouds.png"
            2 -> "medium_clouds.png"
            3 -> "cloudy.png"
            45, 48 -> "fog.png"
            51, 53, 55 -> "light_rain.png"
            56, 57 -> "unsettled.png"
            61, 63 -> "rainy.png"
            65 -> "heavy_rain.png"
            66, 67 -> "unsettled.png"
            71, 73, 75, 77 -> "snow.png"
            80, 81 -> "rainy.png"
            82 -> "heavy_rain.png"
            85, 86 -> "unsettled_snowy.png"
            95, 96, 99 -> "storm.png"
            else -> "unsettled.png"
        }
    }

    private fun parseLocalDateTimeToEpochMillis(raw: String?, timezoneId: String): Long? {
        if (raw.isNullOrBlank()) return null
        val zone = TimeZone.getTimeZone(timezoneId)
        val patterns = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss")
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = zone
                    isLenient = false
                }.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
