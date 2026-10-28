package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.WidgetPersistenceTestSupport.dashboardOf
import com.everyday.everyday_glasses.WidgetPersistenceTestSupport.stateOf
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.widgets.text_editor.TextWidgetState
import com.everyday.everyday_glasses.widgets.browser.BrowserWidgetState
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.MirrorWidgetState
import com.everyday.everyday_glasses.WidgetPersistenceTestSupport.roundTrip
import com.everyday.everyday_glasses.runtime.WidgetDescriptors
import com.everyday.everyday_glasses.widgets.browser.BrowserPlugin
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserPlugin
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserWidget
import com.everyday.everyday_glasses.widgets.file_browser.FileBrowserWidgetState
import com.everyday.everyday_glasses.widgets.phone_screen_mirror.MirrorPlugin
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxPlugin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDocumentTest {

    @Test
    fun `codec catalog covers every widget type`() {
        assertEquals(WidgetDescriptors.all.map { it.type }.toSet(), WidgetDocument.codecs.keys)
    }

    @Test
    fun `file picker keeps its filter and folder, and an unknown filter falls back to media`() {
        val picker = FileBrowserWidgetState(
            x = 70f, y = 72f, width = 500f, height = 336f,
            filter = FileBrowserWidget.FileFilter.PDF,
            directory = "/storage/emulated/0/Download",
            isPinned = true,
        )
        assertEquals(picker, roundTrip(FileBrowserPlugin.TYPE, picker))

        val unknown = FileBrowserWidgetState.toJson(picker).put("filter", "SPREADSHEETS")
        assertEquals(FileBrowserWidget.FileFilter.MEDIA, FileBrowserWidgetState.fromJson(unknown)?.filter)
    }

    @Test
    fun `documents are stamped with the schema version and rejected without it`() {
        val json = WidgetDocument.dashboardToJson(
            WidgetDocument.PersistedDashboard(widgets = emptyList())
        )
        assertEquals(WidgetDocument.SCHEMA_VERSION, json.getInt("schemaVersion"))
        assertTrue(WidgetDocument.isCurrentSchema(json))

        val unversioned = JSONObject().put("widgets", JSONArray())
        assertTrue(!WidgetDocument.isCurrentSchema(unversioned))
        assertTrue(
            runCatching { WidgetDocument.dashboardFromJson(unversioned) }
                .exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `runtime instance ids survive a round trip`() {
        val dashboard = WidgetDocument.PersistedDashboard(
            widgets = listOf(
                WidgetDocument.PersistedWidgetRecord(
                    type = TextBoxPlugin.TYPE,
                    instanceId = "text#41",
                    state = WidgetDocument.codecs.getValue(TextBoxPlugin.TYPE).encode(
                        TextWidgetState(1f, 2f, 100f, 80f, "text")
                    )
                ),
                WidgetDocument.PersistedWidgetRecord(
                    type = BrowserPlugin.TYPE,
                    instanceId = "browser#9",
                    state = WidgetDocument.codecs.getValue(BrowserPlugin.TYPE).encode(
                        BrowserWidgetState(3f, 4f, 120f, 90f, "https://example.com")
                    )
                )
            )
        )

        val restored = WidgetDocument.dashboardFromJson(
            WidgetDocument.dashboardToJson(dashboard)
        )

        assertEquals("text#41", restored.widgets.first { it.type == TextBoxPlugin.TYPE }.instanceId)
        assertEquals("browser#9", restored.widgets.first { it.type == BrowserPlugin.TYPE }.instanceId)
    }

    @Test
    fun `a text box keeps its frame, and one saved before frames existed has none`() {
        val black = 0xFF000000.toInt()
        val framed = TextWidgetState(1f, 2f, 100f, 80f, "text", frameBackgroundColor = black, frameBorderWidth = 2f)
        assertEquals(framed, TextWidgetState.Codec.fromJson(TextWidgetState.Codec.toJson(framed)))

        val older = TextWidgetState.Codec.toJson(TextWidgetState(1f, 2f, 100f, 80f, "text"))
        assertEquals(false, older.has("frameBackgroundColor") || older.has("frameBorderWidth"))
        val restored = TextWidgetState.Codec.fromJson(older)
        assertEquals(null, restored?.frameBackgroundColor)
        assertEquals(0f, restored?.frameBorderWidth)
    }

    @Test
    fun `unknown and malformed records are ignored without dropping valid records`() {
        val valid = dashboardOf(
            TextBoxPlugin.TYPE,
            TextWidgetState(1f, 2f, 100f, 80f, "text", html = "<b>text</b>"),
            instanceId = "text#1"
        ).widgets.single()

        val root = JSONObject().apply {
            put("schemaVersion", WidgetDocument.SCHEMA_VERSION)
            put("widgets", JSONArray().apply {
                // Unknown type.
                put(JSONObject().apply {
                    put("type", "not-a-widget")
                    put("instanceId", "unknown#1")
                    put("state", JSONObject())
                })
                // Missing instance id.
                put(JSONObject().apply {
                    put("type", valid.type.persistedId)
                    put("instanceId", "")
                    put("state", valid.state)
                })
                // Missing payload.
                put(JSONObject().apply {
                    put("type", valid.type.persistedId)
                    put("instanceId", "text#2")
                })
                put(JSONObject().apply {
                    put("type", valid.type.persistedId)
                    put("instanceId", valid.instanceId)
                    put("state", valid.state)
                })
            })
        }

        val parsed = WidgetDocument.dashboardFromJson(root)

        assertEquals(listOf(valid.type), parsed.widgets.map { it.type })
        assertEquals(listOf("text#1"), parsed.widgets.map { it.instanceId })
        assertNotNull(parsed.stateOf<TextWidgetState>(TextBoxPlugin.TYPE))
    }

    @Test
    fun `a layout whose dashboard is not current schema is skipped`() {
        val root = JSONObject().apply {
            put("schemaVersion", WidgetDocument.SCHEMA_VERSION)
            put("layouts", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "Stale")
                    put("createdAt", 1L)
                    put("updatedAt", 2L)
                    put("dashboard", JSONObject().put("widgets", JSONArray()))
                })
            })
        }

        assertEquals(emptyList<Any>(), WidgetDocument.dashboardLayoutRecordsFromJson(root))
    }

    @Test
    fun `layout records carry their dashboard and metadata through json`() {
        val records = listOf(
            WidgetDocument.PersistedDashboardLayoutRecord(
                name = "Work",
                createdAt = 100L,
                updatedAt = 200L,
                dashboard = dashboardOf(
                    MirrorPlugin.TYPE,
                    MirrorWidgetState(0f, 0f, 640f, 480f)
                )
            )
        )

        val restored = WidgetDocument.dashboardLayoutRecordsFromJson(
            WidgetDocument.dashboardLayoutRecordsToJson(records)
        )

        val record = restored.single()
        assertEquals("Work", record.name)
        assertEquals(100L, record.createdAt)
        assertEquals(200L, record.updatedAt)
        // A stored layout always reopens under its own name.
        assertEquals("Work", record.dashboard.activeLayoutName)
        assertNull(record.dashboard.widgets.firstOrNull { it.type != MirrorPlugin.TYPE })
    }
}
