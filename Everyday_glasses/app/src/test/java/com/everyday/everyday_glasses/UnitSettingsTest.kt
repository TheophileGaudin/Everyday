package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.settings.LocationMeasurementSystem
import com.everyday.everyday_glasses.settings.SessionLocationCountry
import com.everyday.everyday_glasses.settings.TemperatureUnit
import com.everyday.everyday_glasses.settings.SpeedUnit
import com.everyday.everyday_glasses.settings.UnitDisplayFormatter
import com.everyday.everyday_glasses.settings.UnitResolver
import com.everyday.everyday_glasses.settings.UnitSettings
import com.everyday.everyday_glasses.settings.UnitSystemMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitSettingsTest {
    @Test
    fun `auto defaults to SI without a location country`() {
        assertEquals(
            SpeedUnit.KMH,
            UnitResolver.speedUnit(UnitSystemMode.AUTO, null, null)
        )
        assertEquals(
            TemperatureUnit.CELSIUS,
            UnitResolver.temperatureUnit(UnitSystemMode.AUTO, null, null)
        )
    }

    @Test
    fun `auto uses mixed UK conventions`() {
        assertEquals(
            SpeedUnit.MPH,
            UnitResolver.speedUnit(UnitSystemMode.AUTO, LocationMeasurementSystem.UK, null)
        )
        assertEquals(
            TemperatureUnit.CELSIUS,
            UnitResolver.temperatureUnit(UnitSystemMode.AUTO, LocationMeasurementSystem.UK, null)
        )
    }

    @Test
    fun `auto uses US conventions`() {
        assertEquals(
            SpeedUnit.MPH,
            UnitResolver.speedUnit(UnitSystemMode.AUTO, LocationMeasurementSystem.US, null)
        )
        assertEquals(
            TemperatureUnit.FAHRENHEIT,
            UnitResolver.temperatureUnit(UnitSystemMode.AUTO, LocationMeasurementSystem.US, null)
        )
    }

    @Test
    fun `display overrides take precedence over the global mode`() {
        assertEquals(
            SpeedUnit.KMH,
            UnitResolver.speedUnit(
                UnitSystemMode.IMPERIAL,
                LocationMeasurementSystem.US,
                SpeedUnit.KMH
            )
        )
        assertEquals(
            TemperatureUnit.CELSIUS,
            UnitResolver.temperatureUnit(
                UnitSystemMode.IMPERIAL,
                LocationMeasurementSystem.US,
                TemperatureUnit.CELSIUS
            )
        )
    }

    @Test
    fun `display conversions preserve canonical inputs`() {
        assertEquals(62.1371f, UnitDisplayFormatter.speedFromKmh(100f, SpeedUnit.MPH), 0.0001f)
        assertEquals(32.0, UnitDisplayFormatter.temperatureFromCelsius(0.0, TemperatureUnit.FAHRENHEIT), 0.0001)
        assertEquals("-4°F", UnitDisplayFormatter.formatTemperature(-20.0, TemperatureUnit.FAHRENHEIT))
        assertEquals("0.62 mph", UnitDisplayFormatter.formatSpeedThreshold(1f, SpeedUnit.MPH))
    }

    @Test
    fun `country normalization rejects missing and malformed values`() {
        assertEquals("GB", UnitSettings.normalizeCountryCode(" gb "))
        assertNull(UnitSettings.normalizeCountryCode("Unknown"))
        assertNull(UnitSettings.normalizeCountryCode("1A"))
        assertNull(UnitSettings.normalizeCountryCode(null))
    }

    @Test
    fun `session country is sticky until another valid country arrives`() {
        val session = SessionLocationCountry()

        assertNull(session.countryCode)
        session.observe("US")
        session.observe("Unknown")
        assertEquals("US", session.countryCode)
        session.observe("GB")
        assertEquals("GB", session.countryCode)
    }
}
