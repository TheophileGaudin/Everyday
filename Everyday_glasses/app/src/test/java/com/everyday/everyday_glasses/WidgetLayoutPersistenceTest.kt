package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.WidgetPersistenceTestSupport.stateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutPersistenceTest {
    @Test
    fun `standard layout includes compact weather map and speedometer cluster`() {
        val dashboard = BuiltInLayouts.buildDashboard(WidgetPersistence.DEFAULT_LAYOUT_NAME, 640f, 480f)!!
        val map: WidgetPersistence.MapWidgetState = dashboard.stateOf(WidgetTypeId.MAP)
        val weather: WidgetPersistence.LocationWidgetState = dashboard.stateOf(WidgetTypeId.LOCATION)
        val speed: WidgetPersistence.SpeedometerWidgetState = dashboard.stateOf(WidgetTypeId.SPEEDOMETER)
        val news: WidgetPersistence.NewsWidgetState = dashboard.stateOf(WidgetTypeId.NEWS)
        assertTrue(map.x > 400f)
        assertTrue(weather.width < 200f)
        assertEquals(134.9f, speed.width, 0.1f)
        assertEquals(0.75f, news.fontScale, 0.001f)
        assertFalse(weather.showLocation)
        assertFalse(weather.showCountry)
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
        val dashboard = BuiltInLayouts.buildDashboard(WidgetPersistence.DEFAULT_LAYOUT_NAME, 640f, 480f)!!
        val baseline: WidgetPersistence.NewsWidgetState = dashboard.stateOf(WidgetTypeId.NEWS)
        val codec = WidgetDocument.codecs.getValue(WidgetTypeId.NEWS)
        val sources = listOf(
            com.everyday.shared.sync.NewsSourceSpec.DEFAULT_LOCATION,
            com.everyday.shared.sync.NewsSourceSpec.QUERY("local technology"),
            com.everyday.shared.sync.NewsSourceSpec.CUSTOM_RSS("https://example.com/feed.xml")
        )

        sources.forEach { source ->
            val state = baseline.copy(sourceSpec = source)
            val restored = codec.decode(codec.encode(state)) as WidgetPersistence.NewsWidgetState
            assertEquals(source, restored.sourceSpec)
        }
    }

    @Test
    fun `a full dashboard survives a json round trip`() {
        val text = WidgetPersistence.TextWidgetState(
            x = 10f, y = 20f, width = 180f, height = 90f,
            text = "plain", html = "<b>rich</b>", fontSize = 30f,
            isTextWrap = false, columnCount = 2, isMinimized = true,
            savedMinX = 11f, savedMinY = 22f, savedMinWidth = 181f, savedMinHeight = 91f,
            isPinned = true
        )
        val browser = WidgetPersistence.BrowserWidgetState(
            x = 30f, y = 40f, width = 220f, height = 160f,
            url = "https://example.com", isFullscreen = true
        )
        val image = WidgetPersistence.ImageWidgetState(
            x = 1f, y = 2f, width = 100f, height = 120f, imagePath = "/tmp/image.png"
        )
        val video = WidgetPersistence.VideoWidgetState(
            uri = "content://media/video/1", displayName = "clip.mp4",
            x = 21f, y = 22f, width = 260f, height = 180f
        )
        val pdf = WidgetPersistence.PdfWidgetState(
            uri = "content://documents/report", displayName = "report.pdf",
            x = 23f, y = 24f, width = 280f, height = 200f, page = 4, zoom = 1.5f
        )
        val status = WidgetPersistence.StatusBarState(
            x = 100f, y = 200f, showTime = true, showDate = false,
            showPhoneBattery = true, showGlassesBattery = false
        )
        val location = WidgetPersistence.LocationWidgetState(
            x = 3f, y = 4f, width = 150f, height = 80f,
            showWeather = false, showTemperature = true,
            showLocation = false, showCountry = true
        )
        val map = WidgetPersistence.MapWidgetState(
            x = 410f, y = 12f, width = 176f, height = 176f,
            mode = MapViewMode.PROJECTED.name, zoom = MapZoom.FAR.name,
            isFullscreen = true, isPinned = true
        )
        val calendar = WidgetPersistence.CalendarWidgetState(
            x = 5f, y = 6f, width = 200f, height = 100f, fontScale = 1.25f
        )
        val finance = WidgetPersistence.FinanceWidgetState(
            x = 7f, y = 8f, width = 210f, height = 120f,
            selectedSymbol = "AAPL", selectedRange = "1M"
        )
        val news = WidgetPersistence.NewsWidgetState(
            x = 9f, y = 10f, width = 230f, height = 150f,
            countryCode = "GB", selectedIndex = 3, fontScale = 0.9f
        )
        val notifications = WidgetPersistence.NotificationsWidgetState(
            x = 10f, y = 11f, width = 240f, height = 160f,
            widgetFontScale = 1.2f, toastFontScale = 1.3f, scrollIndex = 2
        )
        val speedometer = WidgetPersistence.SpeedometerWidgetState(
            x = 11f, y = 12f, width = 160f, height = 80f
        )
        val subtitle = WidgetPersistence.SubtitleWidgetState(
            x = 13f, y = 14f, width = 250f, height = 90f,
            phonePlaybackEnabled = false, microphoneEnabled = true, translationEnabled = true
        )
        val mirror = WidgetPersistence.MirrorWidgetState(
            x = 15f, y = 16f, width = 120f, height = 180f, isPinned = true
        )
        val closedText = WidgetPersistence.TextWidgetState(
            x = 17f, y = 18f, width = 110f, height = 70f, text = "closed"
        )
        val closedBrowser = WidgetPersistence.BrowserWidgetState(
            x = 19f, y = 20f, width = 210f, height = 130f, url = "https://closed.example"
        )
        val closedMap = WidgetPersistence.MapWidgetState(
            x = 420f, y = 20f, width = 150f, height = 150f,
            mode = MapViewMode.TOP_DOWN.name, zoom = MapZoom.NEAR.name
        )

        val original = WidgetDocument.PersistedDashboard(
            widgets = listOf(
                record(WidgetTypeId.TEXT, text, "text#1"),
                record(WidgetTypeId.BROWSER, browser, "browser#1"),
                record(WidgetTypeId.IMAGE, image, "image#1"),
                record(WidgetTypeId.VIDEO, video, "video#1"),
                record(WidgetTypeId.PDF, pdf, "pdf#1"),
                record(WidgetTypeId.STATUS, status),
                record(WidgetTypeId.LOCATION, location),
                record(WidgetTypeId.MAP, map),
                record(WidgetTypeId.CALENDAR, calendar),
                record(WidgetTypeId.FINANCE, finance),
                record(WidgetTypeId.NEWS, news),
                record(WidgetTypeId.NOTIFICATIONS, notifications),
                record(WidgetTypeId.SPEEDOMETER, speedometer),
                record(WidgetTypeId.SUBTITLE, subtitle),
                record(WidgetTypeId.MIRROR, mirror)
            ),
            closedTemplates = listOf(
                record(WidgetTypeId.TEXT, closedText, "text#template"),
                record(WidgetTypeId.BROWSER, closedBrowser, "browser#template"),
                record(WidgetTypeId.MAP, closedMap, "map#template")
            ),
            hoverControls = listOf(
                WidgetPersistence.HoverControlPlacementState(CloseAppHoverControl.ID, 2, 1),
                WidgetPersistence.HoverControlPlacementState(YouTubeHistoryHoverControl.ID, 7, 4)
            ),
            activeLayoutName = "Work",
            isLayoutLocked = true,
            isFirstRun = false
        )

        val restored = WidgetDocument.dashboardFromJson(
            WidgetDocument.dashboardToJson(original)
        )

        // Every widget comes back with its payload intact.
        assertEquals(text, restored.stateOf<WidgetPersistence.TextWidgetState>(WidgetTypeId.TEXT))
        assertEquals(browser, restored.stateOf<WidgetPersistence.BrowserWidgetState>(WidgetTypeId.BROWSER))
        assertEquals(image, restored.stateOf<WidgetPersistence.ImageWidgetState>(WidgetTypeId.IMAGE))
        assertEquals(video, restored.stateOf<WidgetPersistence.VideoWidgetState>(WidgetTypeId.VIDEO))
        assertEquals(pdf, restored.stateOf<WidgetPersistence.PdfWidgetState>(WidgetTypeId.PDF))
        assertEquals(status, restored.stateOf<WidgetPersistence.StatusBarState>(WidgetTypeId.STATUS))
        assertEquals(location, restored.stateOf<WidgetPersistence.LocationWidgetState>(WidgetTypeId.LOCATION))
        assertEquals(map, restored.stateOf<WidgetPersistence.MapWidgetState>(WidgetTypeId.MAP))
        assertEquals(calendar, restored.stateOf<WidgetPersistence.CalendarWidgetState>(WidgetTypeId.CALENDAR))
        // Finance is the one type that does not round trip identically: a widget saved
        // with no tiles is rehydrated with one derived from its symbol and range.
        val restoredFinance = restored.stateOf<WidgetPersistence.FinanceWidgetState>(WidgetTypeId.FINANCE)
        assertEquals(finance.selectedSymbol, restoredFinance.selectedSymbol)
        assertEquals(finance.selectedRange, restoredFinance.selectedRange)
        assertEquals(finance.copy(tiles = restoredFinance.tiles), restoredFinance)
        assertEquals("AAPL", restoredFinance.tiles.single().symbol)
        assertEquals(news, restored.stateOf<WidgetPersistence.NewsWidgetState>(WidgetTypeId.NEWS))
        assertEquals(
            notifications,
            restored.stateOf<WidgetPersistence.NotificationsWidgetState>(WidgetTypeId.NOTIFICATIONS)
        )
        assertEquals(
            speedometer,
            restored.stateOf<WidgetPersistence.SpeedometerWidgetState>(WidgetTypeId.SPEEDOMETER)
        )
        assertEquals(subtitle, restored.stateOf<WidgetPersistence.SubtitleWidgetState>(WidgetTypeId.SUBTITLE))
        assertEquals(mirror, restored.stateOf<WidgetPersistence.MirrorWidgetState>(WidgetTypeId.MIRROR))

        // Instance ids identify repeatable widgets across a reload.
        assertEquals(
            listOf("text#1", "browser#1", "image#1", "video#1", "pdf#1"),
            restored.widgets.map { it.instanceId }.filter { id -> id.contains("#") }
        )

        // Closed templates and dashboard-level settings survive too.
        assertEquals(closedText, restored.templateOf<WidgetPersistence.TextWidgetState>(WidgetTypeId.TEXT))
        assertEquals(closedBrowser, restored.templateOf<WidgetPersistence.BrowserWidgetState>(WidgetTypeId.BROWSER))
        assertEquals(closedMap, restored.templateOf<WidgetPersistence.MapWidgetState>(WidgetTypeId.MAP))
        assertNull(restored.closedTemplates.firstOrNull { it.type == WidgetTypeId.STATUS })
        assertEquals(2, restored.hoverControls!!.size)
        assertEquals(CloseAppHoverControl.ID, restored.hoverControls!![0].controlId)
        assertEquals(2, restored.hoverControls!![0].col)
        assertEquals(1, restored.hoverControls!![0].row)
        assertEquals(YouTubeHistoryHoverControl.ID, restored.hoverControls!![1].controlId)
        assertEquals(7, restored.hoverControls!![1].col)
        assertEquals(4, restored.hoverControls!![1].row)
        assertEquals("Work", restored.activeLayoutName)
        assertTrue(restored.isLayoutLocked)
        assertFalse(restored.isFirstRun)
    }

    private fun record(
        type: WidgetTypeId,
        state: Any,
        instanceId: String = type.persistedId
    ): WidgetDocument.PersistedWidgetRecord = WidgetDocument.PersistedWidgetRecord(
        type = type,
        instanceId = instanceId,
        state = WidgetDocument.codecs.getValue(type).encode(state)
    )

    private inline fun <reified S : Any> WidgetDocument.PersistedDashboard.templateOf(
        type: WidgetTypeId
    ): S {
        val record = requireNotNull(closedTemplates.firstOrNull { it.type == type }) {
            "Dashboard has no " + type.persistedId + " closed template"
        }
        return WidgetDocument.codecs.getValue(type).decode(record.state) as S
    }
}
