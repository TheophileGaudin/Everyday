package com.everyday.everyday_glasses.settings

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.os.Build
import java.util.Locale
import androidx.core.content.edit

enum class UnitSystemMode(val code: String) {
    METRIC("metric"),
    AUTO("auto"),
    IMPERIAL("imperial");

    companion object {
        fun fromCode(code: String?): UnitSystemMode =
            UnitSystemMode.entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
    }
}

enum class TemperatureUnit(val code: String, val label: String) {
    CELSIUS("celsius", "°C"),
    FAHRENHEIT("fahrenheit", "°F");

    companion object {
        fun fromCode(code: String?): TemperatureUnit? =
            TemperatureUnit.entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

internal enum class LocationMeasurementSystem { SI, US, UK }

internal object UnitResolver {
    fun speedUnit(
        mode: UnitSystemMode,
        measurementSystem: LocationMeasurementSystem?,
        override: SpeedUnit?
    ): SpeedUnit = override ?: when (mode) {
        UnitSystemMode.METRIC -> SpeedUnit.KMH
        UnitSystemMode.IMPERIAL -> SpeedUnit.MPH
        UnitSystemMode.AUTO -> when (measurementSystem) {
            LocationMeasurementSystem.US, LocationMeasurementSystem.UK -> SpeedUnit.MPH
            LocationMeasurementSystem.SI, null -> SpeedUnit.KMH
        }
    }

    fun temperatureUnit(
        mode: UnitSystemMode,
        measurementSystem: LocationMeasurementSystem?,
        override: TemperatureUnit?
    ): TemperatureUnit = override ?: when (mode) {
        UnitSystemMode.METRIC -> TemperatureUnit.CELSIUS
        UnitSystemMode.IMPERIAL -> TemperatureUnit.FAHRENHEIT
        UnitSystemMode.AUTO -> when (measurementSystem) {
            LocationMeasurementSystem.US -> TemperatureUnit.FAHRENHEIT
            LocationMeasurementSystem.UK, LocationMeasurementSystem.SI, null -> TemperatureUnit.CELSIUS
        }
    }
}

internal class SessionLocationCountry {
    var countryCode: String? = null
        private set

    fun observe(rawCountryCode: String?): Boolean {
        val normalized = UnitSettings.normalizeCountryCode(rawCountryCode) ?: return false
        if (countryCode == normalized) return false
        countryCode = normalized
        return true
    }
}

class UnitSettings(context: Context) {
    companion object {
        private const val PREFS_NAME = "everyday_settings"
        private const val KEY_UNIT_SYSTEM_MODE = "unit_system_mode"
        private const val KEY_SPEED_OVERRIDE = "speed_unit_override"
        private const val KEY_TEMPERATURE_OVERRIDE = "temperature_unit_override"

        fun normalizeCountryCode(raw: String?): String? {
            val normalized = raw?.trim()?.uppercase(Locale.US) ?: return null
            return normalized.takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var onChanged: (() -> Unit)? = null

    var globalMode: UnitSystemMode = UnitSystemMode.AUTO
        private set
    private var speedOverride: SpeedUnit? = null
    private var temperatureOverride: TemperatureUnit? = null
    private val sessionLocationCountry = SessionLocationCountry()
    val sessionLocationCountryCode: String?
        get() = sessionLocationCountry.countryCode

    init {
        globalMode = UnitSystemMode.fromCode(prefs.getString(KEY_UNIT_SYSTEM_MODE, null))
        speedOverride = prefs.getString(KEY_SPEED_OVERRIDE, null)?.let(SpeedUnit::fromCode)
        temperatureOverride = TemperatureUnit.fromCode(prefs.getString(KEY_TEMPERATURE_OVERRIDE, null))
    }

    fun effectiveSpeedUnit(): SpeedUnit =
        UnitResolver.speedUnit(globalMode, currentMeasurementSystem(), speedOverride)

    fun effectiveTemperatureUnit(): TemperatureUnit =
        UnitResolver.temperatureUnit(globalMode, currentMeasurementSystem(), temperatureOverride)

    fun setGlobalMode(mode: UnitSystemMode) {
        globalMode = mode
        speedOverride = null
        temperatureOverride = null
        prefs.edit {
            putString(KEY_UNIT_SYSTEM_MODE, mode.code)
                .remove(KEY_SPEED_OVERRIDE)
                .remove(KEY_TEMPERATURE_OVERRIDE)
        }
        onChanged?.invoke()
    }

    fun toggleSpeedOverride() {
        speedOverride = if (effectiveSpeedUnit() == SpeedUnit.KMH) {
            SpeedUnit.MPH
        } else {
            SpeedUnit.KMH
        }
        prefs.edit { putString(KEY_SPEED_OVERRIDE, speedOverride!!.code) }
        onChanged?.invoke()
    }

    fun toggleTemperatureOverride() {
        temperatureOverride = if (effectiveTemperatureUnit() == TemperatureUnit.CELSIUS) {
            TemperatureUnit.FAHRENHEIT
        } else {
            TemperatureUnit.CELSIUS
        }
        prefs.edit { putString(KEY_TEMPERATURE_OVERRIDE, temperatureOverride!!.code) }
        onChanged?.invoke()
    }

    fun observeLiveCountry(rawCountryCode: String?): Boolean {
        if (!sessionLocationCountry.observe(rawCountryCode)) return false
        onChanged?.invoke()
        return true
    }

    private fun currentMeasurementSystem(): LocationMeasurementSystem? {
        val country = sessionLocationCountryCode ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return when (country.uppercase(Locale.ROOT)) {
                "US", "LR", "MM" -> LocationMeasurementSystem.US
                "GB" -> LocationMeasurementSystem.UK
                else -> LocationMeasurementSystem.SI
            }
        }
        return runCatching {
            val locale = Locale.Builder().setRegion(country).build()
            when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
                LocaleData.MeasurementSystem.US -> LocationMeasurementSystem.US
                LocaleData.MeasurementSystem.UK -> LocationMeasurementSystem.UK
                else -> LocationMeasurementSystem.SI
            }
        }.getOrDefault(LocationMeasurementSystem.SI)
    }

}

internal object UnitDisplayFormatter {
    private const val KMH_TO_MPH = 0.621371f

    fun speedFromKmh(speedKmh: Float, unit: SpeedUnit): Float =
        if (unit == SpeedUnit.KMH) speedKmh else speedKmh * KMH_TO_MPH

    fun temperatureFromCelsius(celsius: Double, unit: TemperatureUnit): Double =
        if (unit == TemperatureUnit.CELSIUS) celsius else celsius * 9.0 / 5.0 + 32.0

    fun formatTemperature(celsius: Double, unit: TemperatureUnit): String {
        val converted = temperatureFromCelsius(celsius, unit)
        val number = if (converted % 1.0 == 0.0) {
            String.format(Locale.ENGLISH, "%.0f", converted)
        } else {
            String.format(Locale.ENGLISH, "%.1f", converted)
        }
        return "$number${unit.label}"
    }

    fun formatSpeedThreshold(thresholdKmh: Float, unit: SpeedUnit): String =
        String.format(Locale.ENGLISH, "%.2f %s", speedFromKmh(thresholdKmh, unit), unit.label)


}

/** The speedometer's unit; [UnitSettings] chooses it. */
enum class SpeedUnit(val code: String, val label: String) {
    KMH("kmh", "km/h"),
    MPH("mph", "mph");

    companion object {
        fun fromCode(code: String?): SpeedUnit {
            return SpeedUnit.entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: KMH
        }
    }
}
