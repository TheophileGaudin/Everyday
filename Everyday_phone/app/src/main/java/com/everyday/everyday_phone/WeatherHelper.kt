package com.everyday.everyday_phone

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class WeatherData(
    val tempCurrent: Double,
    val weatherCodeCurrent: Int,
    val weatherDescCurrent: String,
    val tempMaxToday: Double,
    val tempMinToday: Double,
    val weatherDescToday: String,
    val sunriseEpochMs: Long? = null,
    val sunsetEpochMs: Long? = null
)

object WeatherHelper {
    private const val TAG = "WeatherHelper"

    fun fetchWeather(lat: Double, lon: Double, callback: (WeatherData?) -> Unit) {
        Thread {
            try {
                // Fetching current weather and daily forecast (min/max/code for today)
                val urlString = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=auto"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val current = json.getJSONObject("current")
                    val daily = json.getJSONObject("daily")
                    val timezoneId = json.optString("timezone", TimeZone.getDefault().id)

                    val tempCurrent = current.getDouble("temperature_2m")
                    val codeCurrent = current.getInt("weather_code")
                    
                    // Arrays for daily values, index 0 is today
                    val tempMax = daily.getJSONArray("temperature_2m_max").getDouble(0)
                    val tempMin = daily.getJSONArray("temperature_2m_min").getDouble(0)
                    val codeDaily = daily.getJSONArray("weather_code").getInt(0)
                    val sunriseEpochMs = parseLocalDateTimeToEpochMillis(
                        daily.optJSONArray("sunrise")?.optString(0),
                        timezoneId
                    )
                    val sunsetEpochMs = parseLocalDateTimeToEpochMillis(
                        daily.optJSONArray("sunset")?.optString(0),
                        timezoneId
                    )

                    val data = WeatherData(
                        tempCurrent = tempCurrent,
                        weatherCodeCurrent = codeCurrent,
                        weatherDescCurrent = getWeatherDescription(codeCurrent),
                        tempMaxToday = tempMax,
                        tempMinToday = tempMin,
                        weatherDescToday = getWeatherDescription(codeDaily),
                        sunriseEpochMs = sunriseEpochMs,
                        sunsetEpochMs = sunsetEpochMs
                    )
                    
                    fileLog("Weather fetched: $data")
                    callback(data)
                } else {
                    fileLog("Error fetching weather: ${connection.responseCode}")
                    callback(null)
                }
            } catch (e: Exception) {
                fileLog("Exception fetching weather: ${e.message}")
                callback(null)
            }
        }.start()
    }

    private fun getWeatherDescription(code: Int): String {
        return when (code) {
            0 -> "sunny.png"                    // Clear sky
            1 -> "light_clouds.png"             // Mainly clear
            2 -> "medium_clouds.png"            // Partly cloudy
            3 -> "cloudy.png"                   // Overcast
            45, 48 -> "fog.png"                 // Fog
            51, 53, 55 -> "light_rain.png"      // Drizzle
            56, 57 -> "unsettled.png"           // Freezing drizzle
            61, 63 -> "rainy.png"               // Slight/moderate rain
            65 -> "heavy_rain.png"              // Heavy rain
            66, 67 -> "unsettled.png"           // Freezing rain
            71, 73, 75, 77 -> "snow.png"        // Snow
            80, 81 -> "rainy.png"               // Rain showers
            82 -> "heavy_rain.png"              // Violent rain showers
            85, 86 -> "unsettled_snowy.png"     // Snow showers
            95, 96, 99 -> "storm.png"           // Thunderstorm
            else -> "unsettled.png"             // Unknown
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
            if (parsed != null) {
                return parsed
            }
        }

        return null
    }
}
