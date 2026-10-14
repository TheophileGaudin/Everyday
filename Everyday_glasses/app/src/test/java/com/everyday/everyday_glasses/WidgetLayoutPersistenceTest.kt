package com.everyday.everyday_glasses

import android.graphics.PointF
import com.everyday.everyday_glasses.runtime.WidgetHost
import com.everyday.everyday_glasses.WidgetPersistenceTestSupport.stateOf
import com.everyday.everyday_glasses.widgets.map.backend.MapViewMode
import com.everyday.everyday_glasses.widgets.map.backend.MapZoom
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.AppData
import com.everyday.everyday_glasses.persistence.LayoutLibrary
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.persistence.WidgetLayoutManager
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.settings.UnitSettings
import com.everyday.everyday_glasses.runtime.WidgetLifecycleController
import com.everyday.everyday_glasses.runtime.WidgetRegistry
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import android.content.ContextWrapper
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.ui.components.BuiltInLayouts
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.ui.hover_controls.CloseAppHoverControl
import com.everyday.everyday_glasses.ui.hover_controls.YouTubeHistoryHoverControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutPersistenceTest {
    @Test
    fun `standard layout includes compact weather map and speedometer cluster`() {
        val dashboard = BuiltInLayouts.buildDashboard(DashboardPersistence.DEFAULT_LAYOUT_NAME, 640f, 480f)!!
        val map: DashboardPersistence.MapWidgetState = dashboard.stateOf(WidgetTypeId.MAP)
        val weather: DashboardPersistence.LocationWidgetState = dashboard.stateOf(WidgetTypeId.LOCATION)
        val speed: DashboardPersistence.SpeedometerWidgetState = dashboard.stateOf(WidgetTypeId.SPEEDOMETER)
        val news: DashboardPersistence.NewsWidgetState = dashboard.stateOf(WidgetTypeId.NEWS)
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
        assertTrue(DashboardPersistence.isDefaultLayoutName("Standard"))
        assertTrue(DashboardPersistence.isDefaultLayoutName(" standard "))
        assertFalse(DashboardPersistence.isDefaultLayoutName("Default"))
        assertFalse(DashboardPersistence.isDefaultLayoutName(" default "))
        assertFalse(DashboardPersistence.isDefaultLayoutName("Work"))
    }

    @Test
    fun `all news source kinds survive widget persistence`() {
        val dashboard = BuiltInLayouts.buildDashboard(DashboardPersistence.DEFAULT_LAYOUT_NAME, 640f, 480f)!!
        val baseline: DashboardPersistence.NewsWidgetState = dashboard.stateOf(WidgetTypeId.NEWS)
        val codec = WidgetDocument.codecs.getValue(WidgetTypeId.NEWS)
        val sources = listOf(
            com.everyday.shared.sync.NewsSourceSpec.DEFAULT_LOCATION,
            com.everyday.shared.sync.NewsSourceSpec.QUERY("local technology"),
            com.everyday.shared.sync.NewsSourceSpec.CUSTOM_RSS("https://example.com/feed.xml")
        )

        sources.forEach { source ->
            val state = baseline.copy(sourceSpec = source)
            val restored = codec.decode(codec.encode(state)) as DashboardPersistence.NewsWidgetState
            assertEquals(source, restored.sourceSpec)
        }
    }

    @Test
    fun `a full dashboard survives a json round trip`() {
        val text = DashboardPersistence.TextWidgetState(
            x = 10f, y = 20f, width = 180f, height = 90f,
            text = "plain", html = "<b>rich</b>", fontSize = 30f,
            isTextWrap = false, columnCount = 2, isMinimized = true,
            savedMinX = 11f, savedMinY = 22f, savedMinWidth = 181f, savedMinHeight = 91f,
            isPinned = true
        )
        val browser = DashboardPersistence.BrowserWidgetState(
            x = 30f, y = 40f, width = 220f, height = 160f,
            url = "https://example.com", isFullscreen = true
        )
        val image = DashboardPersistence.ImageWidgetState(
            x = 1f, y = 2f, width = 100f, height = 120f, imagePath = "/tmp/image.png"
        )
        val video = DashboardPersistence.VideoWidgetState(
            uri = "content://media/video/1", displayName = "clip.mp4",
            x = 21f, y = 22f, width = 260f, height = 180f
        )
        val pdf = DashboardPersistence.PdfWidgetState(
            uri = "content://documents/report", displayName = "report.pdf",
            x = 23f, y = 24f, width = 280f, height = 200f, page = 4, zoom = 1.5f
        )
        val status = DashboardPersistence.StatusBarState(
            x = 100f, y = 200f, showTime = true, showDate = false,
            showPhoneBattery = true, showGlassesBattery = false
        )
        val location = DashboardPersistence.LocationWidgetState(
            x = 3f, y = 4f, width = 150f, height = 80f,
            showWeather = false, showTemperature = true,
            showLocation = false, showCountry = true
        )
        val map = DashboardPersistence.MapWidgetState(
            x = 410f, y = 12f, width = 176f, height = 176f,
            mode = MapViewMode.PROJECTED.name, zoom = MapZoom.FAR.name,
            isFullscreen = true, isPinned = true
        )
        val calendar = DashboardPersistence.CalendarWidgetState(
            x = 5f, y = 6f, width = 200f, height = 100f, fontScale = 1.25f
        )
        val finance = DashboardPersistence.FinanceWidgetState(
            x = 7f, y = 8f, width = 210f, height = 120f,
            selectedSymbol = "AAPL", selectedRange = "1M"
        )
        val news = DashboardPersistence.NewsWidgetState(
            x = 9f, y = 10f, width = 230f, height = 150f,
            countryCode = "GB", selectedIndex = 3, fontScale = 0.9f
        )
        val notifications = DashboardPersistence.NotificationsWidgetState(
            x = 10f, y = 11f, width = 240f, height = 160f,
            widgetFontScale = 1.2f, toastFontScale = 1.3f, scrollIndex = 2
        )
        val speedometer = DashboardPersistence.SpeedometerWidgetState(
            x = 11f, y = 12f, width = 160f, height = 80f
        )
        val subtitle = DashboardPersistence.SubtitleWidgetState(
            x = 13f, y = 14f, width = 250f, height = 90f,
            phonePlaybackEnabled = false, microphoneEnabled = true, translationEnabled = true
        )
        val mirror = DashboardPersistence.MirrorWidgetState(
            x = 15f, y = 16f, width = 120f, height = 180f, isPinned = true
        )
        val closedText = DashboardPersistence.TextWidgetState(
            x = 17f, y = 18f, width = 110f, height = 70f, text = "closed"
        )
        val closedBrowser = DashboardPersistence.BrowserWidgetState(
            x = 19f, y = 20f, width = 210f, height = 130f, url = "https://closed.example"
        )
        val closedMap = DashboardPersistence.MapWidgetState(
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
                DashboardPersistence.HoverControlPlacementState(CloseAppHoverControl.ID, 2, 1),
                DashboardPersistence.HoverControlPlacementState(YouTubeHistoryHoverControl.ID, 7, 4)
            ),
            activeLayoutName = "Work",
            isLayoutLocked = true,
            isFirstRun = false
        )

        val restored = WidgetDocument.dashboardFromJson(
            WidgetDocument.dashboardToJson(original)
        )

        // Every widget comes back with its payload intact.
        assertEquals(text, restored.stateOf<DashboardPersistence.TextWidgetState>(WidgetTypeId.TEXT))
        assertEquals(browser, restored.stateOf<DashboardPersistence.BrowserWidgetState>(WidgetTypeId.BROWSER))
        assertEquals(image, restored.stateOf<DashboardPersistence.ImageWidgetState>(WidgetTypeId.IMAGE))
        assertEquals(video, restored.stateOf<DashboardPersistence.VideoWidgetState>(WidgetTypeId.VIDEO))
        assertEquals(pdf, restored.stateOf<DashboardPersistence.PdfWidgetState>(WidgetTypeId.PDF))
        assertEquals(status, restored.stateOf<DashboardPersistence.StatusBarState>(WidgetTypeId.STATUS))
        assertEquals(location, restored.stateOf<DashboardPersistence.LocationWidgetState>(
            WidgetTypeId.LOCATION))
        assertEquals(map, restored.stateOf<DashboardPersistence.MapWidgetState>(WidgetTypeId.MAP))
        assertEquals(calendar, restored.stateOf<DashboardPersistence.CalendarWidgetState>(
            WidgetTypeId.CALENDAR))
        // Finance is the one type that does not round trip identically: a widget saved
        // with no tiles is rehydrated with one derived from its symbol and range.
        val restoredFinance = restored.stateOf<DashboardPersistence.FinanceWidgetState>(WidgetTypeId.FINANCE)
        assertEquals(finance.selectedSymbol, restoredFinance.selectedSymbol)
        assertEquals(finance.selectedRange, restoredFinance.selectedRange)
        assertEquals(finance.copy(tiles = restoredFinance.tiles), restoredFinance)
        assertEquals("AAPL", restoredFinance.tiles.single().symbol)
        assertEquals(news, restored.stateOf<DashboardPersistence.NewsWidgetState>(WidgetTypeId.NEWS))
        assertEquals(
            notifications,
            restored.stateOf<DashboardPersistence.NotificationsWidgetState>(WidgetTypeId.NOTIFICATIONS)
        )
        assertEquals(
            speedometer,
            restored.stateOf<DashboardPersistence.SpeedometerWidgetState>(WidgetTypeId.SPEEDOMETER)
        )
        assertEquals(subtitle, restored.stateOf<DashboardPersistence.SubtitleWidgetState>(
            WidgetTypeId.SUBTITLE))
        assertEquals(mirror, restored.stateOf<DashboardPersistence.MirrorWidgetState>(WidgetTypeId.MIRROR))

        // Instance ids identify repeatable widgets across a reload.
        assertEquals(
            listOf("text#1", "browser#1", "image#1", "video#1", "pdf#1"),
            restored.widgets.map { it.instanceId }.filter { id -> id.contains("#") }
        )

        // Closed templates and dashboard-level settings survive too.
        assertEquals(closedText, restored.templateOf<DashboardPersistence.TextWidgetState>(
            WidgetTypeId.TEXT))
        assertEquals(closedBrowser, restored.templateOf<DashboardPersistence.BrowserWidgetState>(
            WidgetTypeId.BROWSER))
        assertEquals(closedMap, restored.templateOf<DashboardPersistence.MapWidgetState>(
            WidgetTypeId.MAP))
        assertNull(restored.closedTemplates.firstOrNull { it.type == WidgetTypeId.STATUS })
        val restoredHoverControls = requireNotNull(restored.hoverControls)

        assertEquals(2, restoredHoverControls.size)
        assertEquals(CloseAppHoverControl.ID, restoredHoverControls[0].controlId)
        assertEquals(2, restoredHoverControls[0].col)
        assertEquals(1, restoredHoverControls[0].row)
        assertEquals(YouTubeHistoryHoverControl.ID, restoredHoverControls[1].controlId)
        assertEquals(7, restoredHoverControls[1].col)
        assertEquals(4, restoredHoverControls[1].row)
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
    @Test
    fun `a returning user's dashboard gets no default layout and no save until it is restored`() {
        val host = FakeLayoutHost()
        val manager = layoutManager(host)

        manager.expectSavedDashboard()
        manager.createDefaultLayoutIfFirstRun()

        assertTrue(manager.isAwaitingRestore)
        assertEquals(0, host.clears)

        manager.replaceDashboard(WidgetDocument.PersistedDashboard(widgets = emptyList(), activeLayoutName = "Work"))

        assertFalse(manager.isAwaitingRestore)
        assertEquals(1, host.restores)
        assertEquals("Work", manager.activeLayoutName)
    }

    @Test
    fun `the default layout is created on first run only`() {
        val host = FakeLayoutHost()
        val manager = layoutManager(host)

        manager.createDefaultLayoutIfFirstRun()
        manager.createDefaultLayoutIfFirstRun()

        assertEquals(1, host.clears)
        assertEquals(1, host.hoverResets)
    }

    private fun layoutManager(host: FakeLayoutHost) = WidgetLayoutManager(
        layouts = LayoutLibrary(ContextWrapper(null)),
        services = FakeServices(),
        lifecycle = WidgetLifecycleController({ action -> action() }, {}, WidgetRegistry()),
        host = host,
    )

    private class FakeLayoutHost : WidgetLayoutManager.Host {
        var clears = 0
        var restores = 0
        var hoverResets = 0
        override fun captureDashboard() = WidgetDocument.PersistedDashboard(widgets = emptyList())
        override fun notifyPersistentChange() = Unit
        override fun notifyRestored() { restores++ }
        override fun reportKeyFocus() = Unit
        override fun dismissMenus() = Unit
        override fun dismissTransientOverlays() = Unit
        override fun <T> suppressingChanges(action: () -> T): T = action()
        override fun clearWidgets() { clears++ }
        override fun applyHoverControls(placements: List<DashboardPersistence.HoverControlPlacementState>?) = Unit
        override fun resetHoverControls() { hoverResets++ }
        override fun restoreLayoutLock(locked: Boolean) = Unit
        override fun onLayoutSwitch() = Unit
    }

    private class FakeServices : DashboardServices {
        override val viewportWidth = 640
        override val viewportHeight = 480
        override val isDisplayOff = false
        override val captureRestore = WidgetCaptureRestore()
        override val appData = AppData()
        override val units: UnitSettings get() = error("No unit settings in this test")
        override val host: WidgetHost? = null
        override val creationPosition: PointF? = null
        override fun dismissPresentationOf(widget: BaseWidget) = Unit
        override fun onKeyFocusChanged(widget: BaseWidget, focused: Boolean) = Unit
        override fun notifyTransientChange() = Unit
        override fun <T> withTransientChanges(action: () -> T): T = action()
        override fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean) = action()
        override fun <T> runPersistentMutation(changed: (T) -> Boolean, action: () -> T): T = action()
        override fun <W : BaseWidget> open(type: WidgetTypeId, create: () -> W?): W? = create()
        override fun openClosedTemplate(type: WidgetTypeId): BaseWidget? = null
        override fun close(widget: BaseWidget) = Unit
    }


}
