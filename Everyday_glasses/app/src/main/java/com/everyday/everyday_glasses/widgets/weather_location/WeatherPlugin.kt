package com.everyday.everyday_glasses.widgets.weather_location
import android.content.Context
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.settings.UnitDisplayFormatter
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseSizedWidgetObject
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import org.json.JSONObject

/** Puts the location and weather widget on the dashboard and feeds it place and weather data. */
internal class WeatherPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val context: Context get() = services.androidContext
    private val currentWidget: () -> WeatherWidget? = { services.singleton(TYPE) as? WeatherWidget }
    private var address: String? = null
    private var weatherText: String? = null
    private var sunriseEpochMs: Long? = null
    private var sunsetEpochMs: Long? = null

    override val lifecycle = WidgetLifecycleRegistration.singleton(
        WeatherWidget::class, LocationWidgetState.Codec,
        current = currentWidget,
        create = ::create,
        restore = ::restore,
        capture = ::capture,
    )

    init {
        services.appData.subscribe(AppChannels.place) { renderLocationWeather() }
        services.appData.subscribe(AppChannels.weather) { renderLocationWeather() }
        services.appData.subscribe(AppChannels.locationDenied) { setLocationPermissionDenied() }
        services.appData.subscribe(AppChannels.unitsChanged) { applyUnitSettings() }
    }

    private fun create(): WeatherWidget {
        val widget = WeatherWidget(context, 20f, services.viewportHeight - HEIGHT - 20f, WIDTH, HEIGHT)
        configure(widget)
        return widget
    }

    private fun restore(state: LocationWidgetState): WeatherWidget {
        val widget = WeatherWidget(context, state.x, state.y, state.width, state.height)
        configure(widget)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight) {
            widget.setElementVisibility(
                showWeather = state.showWeather,
                showTemperature = state.showTemperature,
                showLocation = state.showLocation,
                showCountry = state.showCountry
            )
        }
        return widget
    }

    private fun capture(widget: WeatherWidget): LocationWidgetState {
        val c = services.captureRestore.extractCommonFields(widget)
        return LocationWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            showWeather = widget.isWeatherVisible(),
            showTemperature = widget.isTemperatureVisible(),
            showLocation = widget.isLocationVisible(),
            showCountry = widget.isCountryVisible(),
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
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
            services.units.effectiveTemperatureUnit()
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
        widget.onTemperatureUnitChanged = { services.units.toggleTemperatureOverride() }
        widget.setTemperatureUnit(services.units.effectiveTemperatureUnit())
        if (address != null || weatherText != null) {
            widget.setLocationData(address ?: "Loading...", weatherText ?: "Checking weather...")
        }
        widget.setSunTimes(sunriseEpochMs, sunsetEpochMs)
    }

    private fun applyUnitSettings() {
        currentWidget()?.setTemperatureUnit(services.units.effectiveTemperatureUnit())
        renderLocationWeather()
    }

    /** The location and weather widget as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("location")
        override val descriptor = WidgetDescriptor(TYPE, "Location", WidgetInstancePolicy.SINGLETON, order = 1, category = "information", storesClosedTemplate = true)
        override val codec get() = LocationWidgetState.Codec
        override fun create(context: WidgetContext) = WeatherPlugin(context)
        private const val WIDTH = 300f
        private const val HEIGHT = 120f
    }
}

/** What a saved dashboard keeps of the location and weather widget. */
data class LocationWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val showWeather: Boolean = true,
    val showTemperature: Boolean = true,
    val showLocation: Boolean = false,
    val showCountry: Boolean = false,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<LocationWidgetState>(WeatherPlugin.TYPE, LocationWidgetState::class) {
        override fun toJson(state: LocationWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("showWeather", state.showWeather)
            put("showTemperature", state.showTemperature)
            put("showLocation", state.showLocation)
            put("showCountry", state.showCountry)
        }

        override fun fromJson(payload: JSONObject): LocationWidgetState? =
            parseSizedWidgetObject(payload) { obj, c, w, h ->
                LocationWidgetState(
                    x = c.x, y = c.y, width = w, height = h,
                    showWeather = obj.optBoolean("showWeather", true),
                    showTemperature = obj.optBoolean("showTemperature", true),
                    showLocation = obj.optBoolean("showLocation", false),
                    showCountry = obj.optBoolean("showCountry", false),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }
    }
}