package com.everyday.everyday_glasses.persistence

import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets.WidgetPlugins
import org.json.JSONArray
import org.json.JSONObject
import kotlin.reflect.KClass

/**
 * The on-disk widget document: the schema, the per-type payload codecs, and the
 * JSON encoding of a saved dashboard.
 *
 * Each widget occupies its own record rather than a fixed top-level field, and a
 * record is deliberately independent of Android widgets, so lifecycle registrations
 * consume [PersistedWidgetRecord] directly.
 *
 * [SCHEMA_VERSION] is a compatibility gate, not a migration hook: a document that does
 * not carry the current version is ignored and the app starts from its default layout.
 * Bump it whenever a payload change would make an older document decode incorrectly.
 */
object WidgetDocument {
    const val SCHEMA_VERSION = 2

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_WIDGETS = "widgets"
    private const val KEY_CLOSED_TEMPLATES = "closedTemplates"
    private const val KEY_TYPE = "type"
    private const val KEY_INSTANCE_ID = "instanceId"
    private const val KEY_STATE = "state"
    private const val KEY_HOVER_CONTROLS = "hover_controls"
    private const val KEY_ACTIVE_LAYOUT_NAME = "activeLayoutName"
    private const val KEY_LAYOUT_LOCKED = "layoutLocked"
    private const val KEY_FIRST_RUN = "isFirstRun"
    private const val KEY_LAYOUTS = "layouts"
    private const val KEY_LAYOUT_NAME = "name"
    private const val KEY_LAYOUT_CREATED_AT = "createdAt"
    private const val KEY_LAYOUT_UPDATED_AT = "updatedAt"
    private const val KEY_LAYOUT_DASHBOARD = "dashboard"

    data class PersistedWidgetRecord(
        val type: WidgetTypeId,
        val instanceId: String,
        val state: JSONObject
    )

    data class PersistedDashboard(
        val widgets: List<PersistedWidgetRecord>,
        val closedTemplates: List<PersistedWidgetRecord> = emptyList(),
        val hoverControls: List<DashboardPersistence.HoverControlPlacementState>? = null,
        val activeLayoutName: String? = null,
        val isLayoutLocked: Boolean = false,
        val isFirstRun: Boolean = false
    )

    data class PersistedDashboardLayoutRecord(
        val name: String,
        val createdAt: Long,
        val updatedAt: Long,
        val dashboard: PersistedDashboard
    )

    /** Codec contract for a single widget type's persisted payload. */
    interface WidgetStateCodec {
        val type: WidgetTypeId
        val stateClass: KClass<out Any>
        fun encode(state: Any): JSONObject
        fun decode(payload: JSONObject): Any?
    }

    /**
     * A widget type's codec, written against its own state class: [toJson] and [fromJson] only see
     * that class. Each widget type that saves its widgets declares one next to its saved state.
     */
    abstract class StateCodec<S : Any>(
        final override val type: WidgetTypeId,
        final override val stateClass: KClass<S>
    ) : WidgetStateCodec {
        abstract fun toJson(state: S): JSONObject
        abstract fun fromJson(payload: JSONObject): S?

        final override fun encode(state: Any): JSONObject {
            require(stateClass.isInstance(state)) {
                "Expected ${stateClass.simpleName} for ${type.persistedId}, got ${state::class.simpleName}"
            }
            @Suppress("UNCHECKED_CAST")
            return toJson(state as S)
        }

        final override fun decode(payload: JSONObject): S? = fromJson(payload)
    }

    /**
     * The persistence catalog: every widget type a saved dashboard can hold. Each type declares
     * its codec in its widget package; [WidgetPlugins.codecs] lists them.
     */
    val codecs: Map<WidgetTypeId, WidgetStateCodec> = WidgetPlugins.codecs.associateBy(WidgetStateCodec::type)

    fun isCurrentSchema(root: JSONObject): Boolean =
        root.optInt(KEY_SCHEMA_VERSION, -1) == SCHEMA_VERSION

    fun dashboardToJson(dashboard: PersistedDashboard): JSONObject = JSONObject().apply {
        put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        put(KEY_WIDGETS, recordsToJson(dashboard.widgets))
        put(KEY_CLOSED_TEMPLATES, recordsToJson(dashboard.closedTemplates))
        dashboard.hoverControls?.let { controls ->
            put(KEY_HOVER_CONTROLS, JSONArray().apply {
                controls.forEach { control ->
                    put(JSONObject().apply {
                        put("id", control.controlId)
                        put("col", control.col)
                        put("row", control.row)
                    })
                }
            })
        }
        dashboard.activeLayoutName?.trim()?.takeIf(String::isNotBlank)?.let {
            put(KEY_ACTIVE_LAYOUT_NAME, it)
        }
        put(KEY_LAYOUT_LOCKED, dashboard.isLayoutLocked)
        put(KEY_FIRST_RUN, dashboard.isFirstRun)
    }

    fun dashboardFromJson(root: JSONObject): PersistedDashboard {
        require(isCurrentSchema(root)) { "Unsupported widget persistence schema" }
        return PersistedDashboard(
            widgets = recordsFromJson(root.optJSONArray(KEY_WIDGETS)),
            closedTemplates = recordsFromJson(root.optJSONArray(KEY_CLOSED_TEMPLATES)),
            hoverControls = parseHoverControls(root.optJSONArray(KEY_HOVER_CONTROLS)),
            activeLayoutName = root.optString(KEY_ACTIVE_LAYOUT_NAME, "").trim().ifBlank { null },
            isLayoutLocked = root.optBoolean(KEY_LAYOUT_LOCKED, false),
            isFirstRun = root.optBoolean(KEY_FIRST_RUN, false)
        )
    }

    fun dashboardLayoutRecordsToJson(records: List<PersistedDashboardLayoutRecord>): JSONObject =
        JSONObject().apply {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_LAYOUTS, JSONArray().apply {
                records.forEach { record ->
                    put(JSONObject().apply {
                        put(KEY_LAYOUT_NAME, record.name)
                        put(KEY_LAYOUT_CREATED_AT, record.createdAt)
                        put(KEY_LAYOUT_UPDATED_AT, record.updatedAt)
                        put(KEY_LAYOUT_DASHBOARD, dashboardToJson(record.dashboard))
                    })
                }
            })
        }

    fun dashboardLayoutRecordsFromJson(root: JSONObject): List<PersistedDashboardLayoutRecord> {
        require(isCurrentSchema(root)) { "Unsupported widget layout schema" }
        val layouts = root.optJSONArray(KEY_LAYOUTS) ?: return emptyList()
        return buildList {
            for (index in 0 until layouts.length()) {
                val item = layouts.optJSONObject(index) ?: continue
                val name = item.optString(KEY_LAYOUT_NAME, "").trim()
                val dashboard = item.optJSONObject(KEY_LAYOUT_DASHBOARD) ?: continue
                if (name.isBlank() || !isCurrentSchema(dashboard)) continue
                add(
                    PersistedDashboardLayoutRecord(
                        name = name,
                        createdAt = item.optLong(KEY_LAYOUT_CREATED_AT, 0L),
                        updatedAt = item.optLong(KEY_LAYOUT_UPDATED_AT, item.optLong(KEY_LAYOUT_CREATED_AT, 0L)),
                        dashboard = dashboardFromJson(dashboard).copy(isFirstRun = false, activeLayoutName = name)
                    )
                )
            }
        }.sortedBy(PersistedDashboardLayoutRecord::createdAt)
    }

    private fun recordsToJson(records: List<PersistedWidgetRecord>): JSONArray = JSONArray().apply {
        records.forEach { record ->
            put(JSONObject().apply {
                put(KEY_TYPE, record.type.persistedId)
                put(KEY_INSTANCE_ID, record.instanceId)
                put(KEY_STATE, record.state)
            })
        }
    }

    private fun recordsFromJson(records: JSONArray?): List<PersistedWidgetRecord> {
        if (records == null) return emptyList()
        return buildList {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val type = WidgetTypeId.entries.firstOrNull {
                    it.persistedId == record.optString(KEY_TYPE)
                } ?: continue
                val instanceId = record.optString(KEY_INSTANCE_ID).trim()
                val payload = record.optJSONObject(KEY_STATE) ?: continue
                if (instanceId.isBlank() || type !in codecs) continue
                add(PersistedWidgetRecord(type, instanceId, payload))
            }
        }
    }

    private fun parseHoverControls(array: JSONArray?): List<DashboardPersistence.HoverControlPlacementState>? {
        if (array == null) return null
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isNotBlank()) {
                    add(
                        DashboardPersistence.HoverControlPlacementState(
                            controlId = id,
                            col = item.optInt("col", 0),
                            row = item.optInt("row", 0)
                        )
                    )
                }
            }
        }
    }
}