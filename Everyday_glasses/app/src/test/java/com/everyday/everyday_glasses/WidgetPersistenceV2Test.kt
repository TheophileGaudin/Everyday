package com.everyday.everyday_glasses

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPersistenceV2Test {
    @Test
    fun `v1 state migrates to versioned records and round trips through the compatibility adapter`() {
        val legacy = sampleState()

        val dashboard = WidgetPersistenceV2.fromLegacy(legacy)
        val json = WidgetPersistenceV2.dashboardToJson(dashboard)
        val restored = WidgetPersistenceV2.toLegacy(WidgetPersistenceV2.dashboardFromJson(json))

        assertEquals(WidgetPersistenceV2.SCHEMA_VERSION, json.getInt("schemaVersion"))
        assertFalse(json.has("text_widgets"))
        assertEquals("text#1", dashboard.widgets.first { it.type == WidgetTypeId.TEXT }.instanceId)
        assertEquals("browser#1", dashboard.widgets.first { it.type == WidgetTypeId.BROWSER }.instanceId)
        assertEquals("status", dashboard.widgets.first { it.type == WidgetTypeId.STATUS }.instanceId)
        assertEquals("text#template", dashboard.closedTemplates.first { it.type == WidgetTypeId.TEXT }.instanceId)
        assertEquals(legacy.text, restored.text)
        assertEquals(legacy.browser, restored.browser)
        assertEquals(legacy.image, restored.image)
        assertEquals(legacy.video, restored.video)
        assertEquals(legacy.pdf, restored.pdf)
        assertEquals(legacy.status, restored.status)
        assertEquals(legacy.map, restored.map)
        assertEquals(legacy.subtitle, restored.subtitle)
        assertEquals(legacy.closedTemplates, restored.closedTemplates)
        assertEquals(legacy.hoverControls, restored.hoverControls)
        assertEquals(legacy.activeLayoutName, restored.activeLayoutName)
        assertTrue(restored.isLayoutLocked)
    }

    @Test
    fun `codec catalog covers every currently persisted widget type`() {
        val expected = WidgetTypeId.values().toSet() - WidgetTypeId.FILES
        assertEquals(expected, WidgetPersistenceV2.codecs.keys)
    }

    @Test
    fun `v2 preserves runtime instance ids`() {
        val state = sampleState().copy(
            instanceIds = mapOf(
                WidgetTypeId.TEXT to listOf("text#41"),
                WidgetTypeId.BROWSER to listOf("browser#9")
            )
        )

        val restored = WidgetPersistenceV2.toLegacy(
            WidgetPersistenceV2.dashboardFromJson(
                WidgetPersistenceV2.dashboardToJson(WidgetPersistenceV2.fromLegacy(state))
            )
        )

        assertEquals(listOf("text#41"), restored.instanceIds[WidgetTypeId.TEXT])
        assertEquals(listOf("browser#9"), restored.instanceIds[WidgetTypeId.BROWSER])
    }

    @Test
    fun `unknown and malformed records are ignored without dropping valid records`() {
        val valid = WidgetPersistenceV2.fromLegacy(sampleState())
            .widgets
            .first { it.type == WidgetTypeId.TEXT }
        val root = JSONObject().apply {
            put("schemaVersion", WidgetPersistenceV2.SCHEMA_VERSION)
            put("widgets", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "not-a-widget")
                    put("instanceId", "unknown#1")
                    put("state", JSONObject())
                })
                put(JSONObject().apply {
                    put("type", valid.type.persistedId)
                    put("instanceId", valid.instanceId)
                    put("state", valid.state)
                })
            })
        }

        val parsed = WidgetPersistenceV2.dashboardFromJson(root)
        assertEquals(listOf(valid.type), parsed.widgets.map { it.type })
        assertNotNull(WidgetPersistenceV2.toLegacy(parsed).text.singleOrNull())
    }

    private fun sampleState(): WidgetPersistence.PersistedState = WidgetPersistence.PersistedState(
        text = listOf(WidgetPersistence.TextWidgetState(1f, 2f, 100f, 80f, "text", html = "<b>text</b>")),
        browser = listOf(WidgetPersistence.BrowserWidgetState(3f, 4f, 120f, 90f, "https://example.com")),
        image = listOf(WidgetPersistence.ImageWidgetState(5f, 6f, 110f, 100f, "/tmp/image.png")),
        video = listOf(WidgetPersistence.VideoWidgetState(7f, 8f, 140f, 110f, "content://video", "video.mp4")),
        pdf = listOf(WidgetPersistence.PdfWidgetState(9f, 10f, 150f, 120f, "content://pdf", "report.pdf", page = 2, zoom = 1.5f)),
        status = WidgetPersistence.StatusBarState(11f, 12f, showDate = false),
        location = WidgetPersistence.LocationWidgetState(13f, 14f, 160f, 130f, showCountry = true),
        map = WidgetPersistence.MapWidgetState(15f, 16f, 170f, 140f, mode = MapViewMode.PROJECTED.name),
        calendar = WidgetPersistence.CalendarWidgetState(17f, 18f, 180f, 150f, fontScale = 1.1f),
        finance = WidgetPersistence.FinanceWidgetState(19f, 20f, 190f, 160f, selectedSymbol = "AAPL"),
        news = WidgetPersistence.NewsWidgetState(21f, 22f, 200f, 170f, countryCode = "GB"),
        notifications = WidgetPersistence.NotificationsWidgetState(23f, 24f, 210f, 180f, scrollIndex = 3),
        speedometer = WidgetPersistence.SpeedometerWidgetState(25f, 26f, 220f, 190f),
        subtitle = WidgetPersistence.SubtitleWidgetState(27f, 28f, 230f, 200f, microphoneEnabled = true),
        mirror = WidgetPersistence.MirrorWidgetState(29f, 30f, 240f, 210f),
        closedTemplates = WidgetPersistence.ClosedWidgetTemplates(
            text = WidgetPersistence.TextWidgetState(31f, 32f, 250f, 220f, "closed"),
            browser = WidgetPersistence.BrowserWidgetState(33f, 34f, 260f, 230f, "https://closed.example"),
            map = WidgetPersistence.MapWidgetState(35f, 36f, 270f, 240f)
        ),
        hoverControls = listOf(WidgetPersistence.HoverControlPlacementState("close_app", 2, 3)),
        isFirstRun = false,
        activeLayoutName = "Work",
        isLayoutLocked = true
    )
}
