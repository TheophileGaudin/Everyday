package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutPersistenceTest {
    @Test
    fun `default layout name is reserved case-insensitively`() {
        assertTrue(WidgetPersistence.isDefaultLayoutName("Standard"))
        assertTrue(WidgetPersistence.isDefaultLayoutName(" standard "))
        assertTrue(WidgetPersistence.isDefaultLayoutName("Default"))
        assertTrue(WidgetPersistence.isDefaultLayoutName(" default "))
        assertFalse(WidgetPersistence.isDefaultLayoutName("Work"))
    }

    @Test
    fun `persisted state survives layout json round trip`() {
        val original = WidgetPersistence.PersistedState(
            text = listOf(
                WidgetPersistence.TextWidgetState(
                    x = 10f,
                    y = 20f,
                    width = 180f,
                    height = 90f,
                    text = "plain",
                    html = "<b>rich</b>",
                    fontSize = 30f,
                    isTextWrap = false,
                    columnCount = 2,
                    isMinimized = true,
                    savedMinX = 11f,
                    savedMinY = 22f,
                    savedMinWidth = 181f,
                    savedMinHeight = 91f,
                    isPinned = true
                )
            ),
            browser = listOf(
                WidgetPersistence.BrowserWidgetState(
                    x = 30f,
                    y = 40f,
                    width = 220f,
                    height = 160f,
                    url = "https://example.com",
                    isFullscreen = true
                )
            ),
            image = listOf(
                WidgetPersistence.ImageWidgetState(
                    x = 1f,
                    y = 2f,
                    width = 100f,
                    height = 120f,
                    imagePath = "/tmp/image.png"
                )
            ),
            status = WidgetPersistence.StatusBarState(
                x = 100f,
                y = 200f,
                showTime = true,
                showDate = false,
                showPhoneBattery = true,
                showGlassesBattery = false
            ),
            location = WidgetPersistence.LocationWidgetState(
                x = 3f,
                y = 4f,
                width = 150f,
                height = 80f,
                showWeather = false,
                showTemperature = true,
                showLocation = false,
                showCountry = true
            ),
            calendar = WidgetPersistence.CalendarWidgetState(
                x = 5f,
                y = 6f,
                width = 200f,
                height = 100f,
                fontScale = 1.25f
            ),
            finance = WidgetPersistence.FinanceWidgetState(
                x = 7f,
                y = 8f,
                width = 210f,
                height = 120f,
                selectedSymbol = "AAPL",
                selectedRange = "1M"
            ),
            news = WidgetPersistence.NewsWidgetState(
                x = 9f,
                y = 10f,
                width = 230f,
                height = 150f,
                countryCode = "GB",
                selectedIndex = 3,
                fontScale = 0.9f
            ),
            speedometer = WidgetPersistence.SpeedometerWidgetState(
                x = 11f,
                y = 12f,
                width = 160f,
                height = 80f
            ),
            subtitle = WidgetPersistence.SubtitleWidgetState(
                x = 13f,
                y = 14f,
                width = 250f,
                height = 90f,
                phonePlaybackEnabled = false,
                microphoneEnabled = true,
                translationEnabled = true
            ),
            mirror = WidgetPersistence.MirrorWidgetState(
                x = 15f,
                y = 16f,
                width = 120f,
                height = 180f,
                isPinned = true
            ),
            closedTemplates = WidgetPersistence.ClosedWidgetTemplates(
                text = WidgetPersistence.TextWidgetState(
                    x = 17f,
                    y = 18f,
                    width = 110f,
                    height = 70f,
                    text = "closed"
                ),
                browser = WidgetPersistence.BrowserWidgetState(
                    x = 19f,
                    y = 20f,
                    width = 210f,
                    height = 130f,
                    url = "https://closed.example"
                )
            ),
            hoverControls = listOf(
                WidgetPersistence.HoverControlPlacementState(
                    controlId = CloseAppHoverControl.ID,
                    col = 2,
                    row = 1
                ),
                WidgetPersistence.HoverControlPlacementState(
                    controlId = YouTubeHistoryHoverControl.ID,
                    col = 7,
                    row = 4
                )
            ),
            isFirstRun = false,
            activeLayoutName = "Work",
            isLayoutLocked = true
        )

        val restored = WidgetPersistence.stateFromJson(WidgetPersistence.stateToJson(original))

        assertEquals(1, restored.text.size)
        assertEquals("<b>rich</b>", restored.text.single().html)
        assertEquals(2, restored.text.single().columnCount)
        assertTrue(restored.text.single().isMinimized)
        assertTrue(restored.text.single().isPinned)
        assertEquals("https://example.com", restored.browser.single().url)
        assertTrue(restored.browser.single().isFullscreen)
        assertEquals("/tmp/image.png", restored.image.single().imagePath)
        assertFalse(restored.status!!.showDate)
        assertFalse(restored.location!!.showWeather)
        assertEquals(1.25f, restored.calendar!!.fontScale, 0.001f)
        assertEquals("AAPL", restored.finance!!.selectedSymbol)
        assertEquals("1M", restored.finance!!.selectedRange)
        assertEquals("GB", restored.news!!.countryCode)
        assertEquals(3, restored.news!!.selectedIndex)
        assertEquals(160f, restored.speedometer!!.width, 0.001f)
        assertFalse(restored.subtitle!!.phonePlaybackEnabled)
        assertTrue(restored.subtitle!!.microphoneEnabled)
        assertTrue(restored.mirror!!.isPinned)
        assertEquals("closed", restored.closedTemplates.text!!.text)
        assertEquals("https://closed.example", restored.closedTemplates.browser!!.url)
        assertEquals(2, restored.hoverControls!!.size)
        assertEquals(CloseAppHoverControl.ID, restored.hoverControls!![0].controlId)
        assertEquals(2, restored.hoverControls!![0].col)
        assertEquals(1, restored.hoverControls!![0].row)
        assertEquals(YouTubeHistoryHoverControl.ID, restored.hoverControls!![1].controlId)
        assertEquals(7, restored.hoverControls!![1].col)
        assertEquals(4, restored.hoverControls!![1].row)
        assertEquals("Work", restored.activeLayoutName)
        assertTrue(restored.isLayoutLocked)
        assertNull(restored.closedTemplates.status)
    }
}
