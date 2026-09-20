package com.everyday.everyday_glasses.widgets.weather_location

import com.everyday.everyday_glasses.settings.TemperatureUnit
import com.everyday.everyday_glasses.settings.UnitDisplayFormatter
import com.everyday.shared.sync.WeatherData


internal class LocationWeatherBinding(
    private val currentWidget: () -> LocationWidget?,
    private val temperatureUnit: () -> TemperatureUnit,
    private val toggleTemperatureUnit: () -> Unit,
    private val notifyTransientChange: () -> Unit,
) {
    private var town: String? = null
    private var countryCode: String? = null
    private var weatherData: WeatherData? = null
    private var address: String? = null
    private var weatherText: String? = null
    private var sunriseEpochMs: Long? = null
    private var sunsetEpochMs: Long? = null

    fun updateData(
        address: String,
        weather: String,
        sunriseEpochMs: Long? = null,
        sunsetEpochMs: Long? = null
    ) {
        this.address = address
        weatherText = weather
        this.sunriseEpochMs = sunriseEpochMs
        this.sunsetEpochMs = sunsetEpochMs

        val widget = currentWidget()
        widget?.setLocationData(address, weather)
        widget?.setSunTimes(sunriseEpochMs, sunsetEpochMs)

        notifyTransientChange()
    }

    fun setLocationPermissionDenied() {
        address = "-----"
        weatherText = "-"
        sunriseEpochMs = null
        sunsetEpochMs = null

        currentWidget()?.setPermissionDenied()
        notifyTransientChange()
    }

    fun applyLocationMetadata(
        town: String,
        countryCode: String
    ) {
        this.town = town
        this.countryCode = countryCode
        renderLocationWeather()
    }

    fun applyWeatherData(weather: WeatherData?) {
        weatherData = weather
        renderLocationWeather()
    }

    private fun renderLocationWeather() {
        val currentTown = town ?: return
        val currentCountryCode = countryCode.orEmpty()
        val weather = weatherData

        val addressText =
            if (currentCountryCode.isNotEmpty()) {
                "$currentTown, $currentCountryCode"
            } else {
                currentTown
            }

        if (weather == null) {
            updateData(
                address = addressText,
                weather = "Checking weather..."
            )
            return
        }

        val temperatureText = UnitDisplayFormatter.formatTemperature(
            weather.tempCurrent,
            temperatureUnit()
        )

        val displayWeatherText =
            if (weather.weatherDescCurrent.isNotEmpty()) {
                "${weather.weatherDescCurrent} $temperatureText"
            } else {
                temperatureText
            }

        updateData(
            address = addressText,
            weather = displayWeatherText,
            sunriseEpochMs = weather.sunriseEpochMs,
            sunsetEpochMs = weather.sunsetEpochMs
        )
    }

    fun configure(widget: LocationWidget) {
        widget.onTemperatureUnitChanged = toggleTemperatureUnit
        widget.setTemperatureUnit(temperatureUnit())
        if (address != null || weatherText != null) {
            widget.setLocationData(address ?: "Loading...", weatherText ?: "Checking weather...")
        }
        widget.setSunTimes(sunriseEpochMs, sunsetEpochMs)
    }

    fun applyUnitSettings() {
        currentWidget()?.setTemperatureUnit(temperatureUnit())
        renderLocationWeather()
    }
}