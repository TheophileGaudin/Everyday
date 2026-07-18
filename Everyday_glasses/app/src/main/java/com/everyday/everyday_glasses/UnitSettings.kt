package com.everyday.everyday_glasses

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import java.util.Locale

enum class UnitSystemMode(val code: String) {
    METRIC("metric"),
    AUTO("auto"),
    IMPERIAL("imperial");

    companion object {
        fun fromCode(code: String?): UnitSystemMode =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
    }
}

enum class TemperatureUnit(val code: String, val label: String) {
    CELSIUS("celsius", "°C"),
    FAHRENHEIT("fahrenheit", "°F");

    companion object {
        fun fromCode(code: String?): TemperatureUnit? =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

internal enum class LocationMeasurementSystem { SI, US, UK }

internal object UnitResolver {
    fun speedUnit(
        mode: UnitSystemMode,
        measurementSystem: LocationMeasurementSystem?,
        override: SpeedometerWidget.SpeedUnit?
    ): SpeedometerWidget.SpeedUnit = override ?: when (mode) {
        UnitSystemMode.METRIC -> SpeedometerWidget.SpeedUnit.KMH
        UnitSystemMode.IMPERIAL -> SpeedometerWidget.SpeedUnit.MPH
        UnitSystemMode.AUTO -> when (measurementSystem) {
            LocationMeasurementSystem.US, LocationMeasurementSystem.UK -> SpeedometerWidget.SpeedUnit.MPH
            LocationMeasurementSystem.SI, null -> SpeedometerWidget.SpeedUnit.KMH
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
        private const val KEY_MIGRATION_COMPLETE = "unit_settings_migration_complete"
        private const val LEGACY_SPEED_UNIT = "speed_unit"

        fun normalizeCountryCode(raw: String?): String? {
            val normalized = raw?.trim()?.uppercase(Locale.US) ?: return null
            return normalized.takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var onChanged: (() -> Unit)? = null

    var globalMode: UnitSystemMode = UnitSystemMode.AUTO
        private set
    private var speedOverride: SpeedometerWidget.SpeedUnit? = null
    private var temperatureOverride: TemperatureUnit? = null
    private val sessionLocationCountry = SessionLocationCountry()
    val sessionLocationCountryCode: String?
        get() = sessionLocationCountry.countryCode

    init {
        migrateLegacySpeedPreference()
        globalMode = UnitSystemMode.fromCode(prefs.getString(KEY_UNIT_SYSTEM_MODE, null))
        speedOverride = prefs.getString(KEY_SPEED_OVERRIDE, null)?.let(SpeedometerWidget.SpeedUnit::fromCode)
        temperatureOverride = TemperatureUnit.fromCode(prefs.getString(KEY_TEMPERATURE_OVERRIDE, null))
    }

    fun effectiveSpeedUnit(): SpeedometerWidget.SpeedUnit =
        UnitResolver.speedUnit(globalMode, currentMeasurementSystem(), speedOverride)

    fun effectiveTemperatureUnit(): TemperatureUnit =
        UnitResolver.temperatureUnit(globalMode, currentMeasurementSystem(), temperatureOverride)

    fun setGlobalMode(mode: UnitSystemMode) {
        globalMode = mode
        speedOverride = null
        temperatureOverride = null
        prefs.edit()
            .putString(KEY_UNIT_SYSTEM_MODE, mode.code)
            .remove(KEY_SPEED_OVERRIDE)
            .remove(KEY_TEMPERATURE_OVERRIDE)
            .apply()
        onChanged?.invoke()
    }

    fun toggleSpeedOverride() {
        speedOverride = if (effectiveSpeedUnit() == SpeedometerWidget.SpeedUnit.KMH) {
            SpeedometerWidget.SpeedUnit.MPH
        } else {
            SpeedometerWidget.SpeedUnit.KMH
        }
        prefs.edit().putString(KEY_SPEED_OVERRIDE, speedOverride!!.code).apply()
        onChanged?.invoke()
    }

    fun toggleTemperatureOverride() {
        temperatureOverride = if (effectiveTemperatureUnit() == TemperatureUnit.CELSIUS) {
            TemperatureUnit.FAHRENHEIT
        } else {
            TemperatureUnit.CELSIUS
        }
        prefs.edit().putString(KEY_TEMPERATURE_OVERRIDE, temperatureOverride!!.code).apply()
        onChanged?.invoke()
    }

    fun observeLiveCountry(rawCountryCode: String?): Boolean {
        if (!sessionLocationCountry.observe(rawCountryCode)) return false
        onChanged?.invoke()
        return true
    }

    private fun currentMeasurementSystem(): LocationMeasurementSystem? {
        val country = sessionLocationCountryCode ?: return null
        return runCatching {
            val locale = Locale.Builder().setRegion(country).build()
            when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
                LocaleData.MeasurementSystem.US -> LocationMeasurementSystem.US
                LocaleData.MeasurementSystem.UK -> LocationMeasurementSystem.UK
                else -> LocationMeasurementSystem.SI
            }
        }.getOrDefault(LocationMeasurementSystem.SI)
    }

    private fun migrateLegacySpeedPreference() {
        if (prefs.getBoolean(KEY_MIGRATION_COMPLETE, false)) return
        val editor = prefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true)
        if (!prefs.contains(KEY_SPEED_OVERRIDE) && prefs.contains(LEGACY_SPEED_UNIT)) {
            val legacy = SpeedometerWidget.SpeedUnit.fromCode(prefs.getString(LEGACY_SPEED_UNIT, null))
            editor.putString(KEY_SPEED_OVERRIDE, legacy.code)
        }
        editor.apply()
    }
}

internal object UnitDisplayFormatter {
    private const val KMH_TO_MPH = 0.621371f

    fun speedFromKmh(speedKmh: Float, unit: SpeedometerWidget.SpeedUnit): Float =
        if (unit == SpeedometerWidget.SpeedUnit.KMH) speedKmh else speedKmh * KMH_TO_MPH

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

    fun formatSpeedThreshold(thresholdKmh: Float, unit: SpeedometerWidget.SpeedUnit): String =
        String.format(Locale.ENGLISH, "%.2f %s", speedFromKmh(thresholdKmh, unit), unit.label)
}
