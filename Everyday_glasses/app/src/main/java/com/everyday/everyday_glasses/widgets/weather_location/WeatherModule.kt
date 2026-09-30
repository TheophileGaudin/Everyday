package com.everyday.everyday_glasses.widgets.weather_location
import android.content.Context
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.settings.TemperatureUnit
import com.everyday.everyday_glasses.settings.UnitDisplayFormatter

/** Puts the location and weather widget on the dashboard and feeds it place and weather data. */
internal class WeatherModule(
    private val services: DashboardServices,
    private val context: Context,
    private val currentWidget: () -> WeatherWidget?,
    private val temperatureUnit: () -> TemperatureUnit,
    private val toggleTemperatureUnit: () -> Unit,
) {
    private var address: String? = null
    private var weatherText: String? = null
    private var sunriseEpochMs: Long? = null
    private var sunsetEpochMs: Long? = null

    val lifecycle = WidgetLifecycleRegistration.singleton(
        WidgetTypeId.LOCATION, WeatherWidget::class, DashboardPersistence.LocationWidgetState::class,
        current = currentWidget,
        create = ::create,
        restore = ::restore,
        capture = { services.captureRestore.getLocationWidgetState(it) },
    )

    init {
        services.appData.subscribe(AppChannels.place) { renderLocationWeather() }
        services.appData.subscribe(AppChannels.weather) { renderLocationWeather() }
        services.appData.subscribe(AppChannels.locationDenied) { setLocationPermissionDenied() }
    }

    private fun create(): WeatherWidget {
        val widget = WeatherWidget(context, 20f, services.viewportHeight - HEIGHT - 20f, WIDTH, HEIGHT)
        configure(widget)
        return widget
    }

    private fun restore(state: DashboardPersistence.LocationWidgetState): WeatherWidget {
        val widget = WeatherWidget(context, state.x, state.y, state.width, state.height)
        configure(widget)
        services.captureRestore.restoreLocationWidgetPosition(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
    }

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

        services.notifyTransientChange()
    }

    private fun setLocationPermissionDenied() {
        address = "-----"
        weatherText = "-"
        sunriseEpochMs = null
        sunsetEpochMs = null

        currentWidget()?.setPermissionDenied()
        services.notifyTransientChange()
    }

    private fun renderLocationWeather() {
        val place = services.appData.latest(AppChannels.place) ?: return
        val currentTown = place.town
        val currentCountryCode = place.countryCode
        val weather = services.appData.latest(AppChannels.weather)

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

    private fun configure(widget: WeatherWidget) {
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

    private companion object {
        const val WIDTH = 300f
        const val HEIGHT = 120f
    }
}