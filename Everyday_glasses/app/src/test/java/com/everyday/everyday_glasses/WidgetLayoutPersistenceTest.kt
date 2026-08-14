package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutPersistenceTest {
    @Test
    fun `standard layout includes compact weather map and speedometer cluster`() {
        val state = BuiltInLayouts.build(WidgetPersistence.DEFAULT_LAYOUT_NAME, 640f, 480f)!!
        val map = state.map!!
        val weather = state.location!!
        val speed = state.speedometer!!
        assertTrue(map.x > 400f)
        assertTrue(weather.width < 200f)
        assertEquals(134.9f, speed.width, 0.1f)
        assertEquals(0.75f, state.news!!.fontScale, 0.001f)
        assertFalse(state.location!!.showLocation)
        assertFalse(state.location!!.showCountry)
        assertTrue(map.showLocation)
        assertTrue(map.showCountry)
    }

    @Test
    fun `default layout name is reserved case-insensitively`() {
        assertTrue(WidgetPersistence.isDefaultLayoutName("Standard"))
        assertTrue(WidgetPersistence.isDefaultLayoutName(" standard "))
        assertFalse(WidgetPersistence.isDefaultLayoutName("Default"))
        assertFalse(WidgetPersistence.isDefaultLayoutName(" default "))
        assertFalse(WidgetPersistence.isDefaultLayoutName("Work"))
    }

    @Test
    fun `all news source kinds survive widget persistence`() {
        val baseline = BuiltInLayouts.build(WidgetPersistence.DEFAULT_LAYOUT_NAME, 640f, 480f)!!
        val sources = listOf(
            com.everyday.shared.sync.NewsSourceSpec.DEFAULT_LOCATION,
            com.everyday.shared.sync.NewsSourceSpec.QUERY("local technology"),
            com.everyday.shared.sync.NewsSourceSpec.CUSTOM_RSS("https://example.com/feed.xml")
        )

        sources.forEach { source ->
            val state = baseline.copy(news = baseline.news!!.copy(sourceSpec = source))
            val restored = WidgetPersistence.stateFromJson(WidgetPersistence.stateToJson(state))
            assertEquals(source, restored.news!!.sourceSpec)
        }
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
            video = listOf(
                WidgetPersistence.VideoWidgetState(
                    uri = "content://media/video/1",
                    displayName = "clip.mp4",
                    x = 21f,
                    y = 22f,
                    width = 260f,
                    height = 180f
                )
            ),
            pdf = listOf(
                WidgetPersistence.PdfWidgetState(
                    uri = "content://documents/report",
                    displayName = "report.pdf",
                    x = 23f,
                    y = 24f,
                    width = 280f,
                    height = 200f,
                    page = 4,
                    zoom = 1.5f
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
            map = WidgetPersistence.MapWidgetState(
                x = 410f, y = 12f, width = 176f, height = 176f,
                mode = MapViewMode.PROJECTED.name,
                zoom = MapZoom.FAR.name,
                isFullscreen = true,
                isPinned = true
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
            notifications = WidgetPersistence.NotificationsWidgetState(
                x = 10f,
                y = 11f,
                width = 240f,
                height = 160f,
                widgetFontScale = 1.2f,
                toastFontScale = 1.3f,
                scrollIndex = 2
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
                ),
                map = WidgetPersistence.MapWidgetState(
                    x = 420f, y = 20f, width = 150f, height = 150f,
                    mode = MapViewMode.TOP_DOWN.name,
                    zoom = MapZoom.NEAR.name
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
        assertEquals("clip.mp4", restored.video.single().displayName)
        assertEquals(4, restored.pdf.single().page)
        assertEquals(1.5f, restored.pdf.single().zoom, 0.001f)
        assertFalse(restored.status!!.showDate)
        assertFalse(restored.location!!.showWeather)
        assertEquals(MapViewMode.PROJECTED.name, restored.map!!.mode)
        assertEquals(MapZoom.FAR.name, restored.map!!.zoom)
        assertTrue(restored.map!!.isFullscreen)
        assertEquals(1.25f, restored.calendar!!.fontScale, 0.001f)
        assertEquals("AAPL", restored.finance!!.selectedSymbol)
        assertEquals("1M", restored.finance!!.selectedRange)
        assertEquals("GB", restored.news!!.countryCode)
        assertEquals(3, restored.news!!.selectedIndex)
        assertEquals(1.2f, restored.notifications!!.widgetFontScale, 0.001f)
        assertEquals(1.3f, restored.notifications!!.toastFontScale, 0.001f)
        assertEquals(2, restored.notifications!!.scrollIndex)
        assertEquals(160f, restored.speedometer!!.width, 0.001f)
        assertFalse(restored.subtitle!!.phonePlaybackEnabled)
        assertTrue(restored.subtitle!!.microphoneEnabled)
        assertTrue(restored.mirror!!.isPinned)
        assertEquals("closed", restored.closedTemplates.text!!.text)
        assertEquals("https://closed.example", restored.closedTemplates.browser!!.url)
        assertEquals(MapZoom.NEAR.name, restored.closedTemplates.map!!.zoom)
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
