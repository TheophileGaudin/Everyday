package com.everyday.everyday_glasses

import org.json.JSONArray
import org.json.JSONObject
import kotlin.reflect.KClass

/**
 * Version 2 of the on-disk widget format.
 *
 * Unlike v1's fixed top-level fields, each widget occupies a record.  The
 * record is deliberately independent of Android widgets: lifecycle
 * registrations can consume [PersistedWidgetRecord] directly once the runtime
 * migration is complete, while the current container can use [toLegacy].
 */
object WidgetPersistenceV2 {
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
        val hoverControls: List<WidgetPersistence.HoverControlPlacementState>? = null,
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

    private class LegacyPayloadCodec<S : Any>(
        override val type: WidgetTypeId,
        override val stateClass: KClass<S>
    ) : WidgetStateCodec {
        override fun encode(state: Any): JSONObject {
            require(stateClass.isInstance(state)) {
                "Expected ${stateClass.simpleName} for ${type.persistedId}, got ${state::class.simpleName}"
            }
            return WidgetPersistence.encodeWidgetStatePayload(type, state)
        }

        override fun decode(payload: JSONObject): Any? =
            WidgetPersistence.decodeWidgetStatePayload(type, payload)
    }

    /**
     * The persistence catalog. Runtime widget definitions can reuse this map
     * instead of adding another per-type persistence switch.
     */
    val codecs: Map<WidgetTypeId, WidgetStateCodec> = listOf(
        LegacyPayloadCodec(WidgetTypeId.STATUS, WidgetPersistence.StatusBarState::class),
        LegacyPayloadCodec(WidgetTypeId.LOCATION, WidgetPersistence.LocationWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.MAP, WidgetPersistence.MapWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.CALENDAR, WidgetPersistence.CalendarWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.FINANCE, WidgetPersistence.FinanceWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.NEWS, WidgetPersistence.NewsWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.NOTIFICATIONS, WidgetPersistence.NotificationsWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.SPEEDOMETER, WidgetPersistence.SpeedometerWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.SUBTITLE, WidgetPersistence.SubtitleWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.MIRROR, WidgetPersistence.MirrorWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.BROWSER, WidgetPersistence.BrowserWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.TEXT, WidgetPersistence.TextWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.IMAGE, WidgetPersistence.ImageWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.VIDEO, WidgetPersistence.VideoWidgetState::class),
        LegacyPayloadCodec(WidgetTypeId.PDF, WidgetPersistence.PdfWidgetState::class)
    ).associateBy(WidgetStateCodec::type)

    fun isCurrentSchema(root: JSONObject): Boolean =
        root.optInt(KEY_SCHEMA_VERSION, -1) == SCHEMA_VERSION

    /** True only for the unmarked document produced by the previous app version. */
    fun isLegacySchema(root: JSONObject): Boolean = !root.has(KEY_SCHEMA_VERSION)

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

    /** Compatibility-only bridge for callers that still expose the fixed v1 DTO. */
    fun layoutRecordsToJson(records: List<WidgetPersistence.WidgetLayoutRecord>): JSONObject =
        dashboardLayoutRecordsToJson(records.map { record ->
            PersistedDashboardLayoutRecord(
                name = record.name,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
                dashboard = fromLegacy(record.state)
            )
        })

    /** Compatibility-only bridge for legacy public APIs. */
    fun layoutRecordsFromJson(root: JSONObject): List<WidgetPersistence.WidgetLayoutRecord> =
        dashboardLayoutRecordsFromJson(root).map { record ->
            WidgetPersistence.WidgetLayoutRecord(
                name = record.name,
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
                state = toLegacy(record.dashboard)
            )
        }

    /** Converts the current fixed-field DTO into v2 records for one-time migration. */
    fun fromLegacy(state: WidgetPersistence.PersistedState): PersistedDashboard {
        val widgets = mutableListOf<PersistedWidgetRecord>()
        fun add(type: WidgetTypeId, widgetState: Any, instanceId: String) {
            val codec = codecs[type] ?: return
            widgets += PersistedWidgetRecord(type, instanceId, codec.encode(widgetState))
        }
        fun <S : Any> addMultiple(type: WidgetTypeId, states: List<S>) {
            states.forEachIndexed { index, widgetState ->
                val instanceId = state.instanceIds[type]?.getOrNull(index)
                    ?: "${type.persistedId}#${index + 1}"
                add(type, widgetState, instanceId)
            }
        }

        state.status?.let { add(WidgetTypeId.STATUS, it, WidgetTypeId.STATUS.persistedId) }
        state.location?.let { add(WidgetTypeId.LOCATION, it, WidgetTypeId.LOCATION.persistedId) }
        state.map?.let { add(WidgetTypeId.MAP, it, WidgetTypeId.MAP.persistedId) }
        state.calendar?.let { add(WidgetTypeId.CALENDAR, it, WidgetTypeId.CALENDAR.persistedId) }
        state.finance?.let { add(WidgetTypeId.FINANCE, it, WidgetTypeId.FINANCE.persistedId) }
        state.news?.let { add(WidgetTypeId.NEWS, it, WidgetTypeId.NEWS.persistedId) }
        state.notifications?.let { add(WidgetTypeId.NOTIFICATIONS, it, WidgetTypeId.NOTIFICATIONS.persistedId) }
        state.speedometer?.let { add(WidgetTypeId.SPEEDOMETER, it, WidgetTypeId.SPEEDOMETER.persistedId) }
        state.subtitle?.let { add(WidgetTypeId.SUBTITLE, it, WidgetTypeId.SUBTITLE.persistedId) }
        state.mirror?.let { add(WidgetTypeId.MIRROR, it, WidgetTypeId.MIRROR.persistedId) }
        addMultiple(WidgetTypeId.BROWSER, state.browser)
        addMultiple(WidgetTypeId.TEXT, state.text)
        addMultiple(WidgetTypeId.IMAGE, state.image)
        addMultiple(WidgetTypeId.VIDEO, state.video)
        addMultiple(WidgetTypeId.PDF, state.pdf)

        val templates = mutableListOf<PersistedWidgetRecord>()
        fun addTemplate(type: WidgetTypeId, widgetState: Any?) {
            if (widgetState == null) return
            val codec = codecs[type] ?: return
            templates += PersistedWidgetRecord(type, "${type.persistedId}#template", codec.encode(widgetState))
        }
        state.closedTemplates.run {
            addTemplate(WidgetTypeId.TEXT, text)
            addTemplate(WidgetTypeId.BROWSER, browser)
            addTemplate(WidgetTypeId.STATUS, status)
            addTemplate(WidgetTypeId.LOCATION, location)
            addTemplate(WidgetTypeId.MAP, map)
            addTemplate(WidgetTypeId.CALENDAR, calendar)
            addTemplate(WidgetTypeId.FINANCE, finance)
            addTemplate(WidgetTypeId.NEWS, news)
            addTemplate(WidgetTypeId.NOTIFICATIONS, notifications)
            addTemplate(WidgetTypeId.SPEEDOMETER, speedometer)
            addTemplate(WidgetTypeId.SUBTITLE, subtitle)
            addTemplate(WidgetTypeId.MIRROR, mirror)
        }

        return PersistedDashboard(
            widgets = widgets,
            closedTemplates = templates,
            hoverControls = state.hoverControls,
            activeLayoutName = state.activeLayoutName,
            isLayoutLocked = state.isLayoutLocked,
            isFirstRun = state.isFirstRun
        )
    }

    /**
     * Compatibility adapter for the current WidgetContainer.  It deliberately
     * collapses malformed duplicate singleton records to their first value;
     * the future registry will reject them before reaching this boundary.
     */
    fun toLegacy(dashboard: PersistedDashboard): WidgetPersistence.PersistedState {
        val text = mutableListOf<WidgetPersistence.TextWidgetState>()
        val browser = mutableListOf<WidgetPersistence.BrowserWidgetState>()
        val image = mutableListOf<WidgetPersistence.ImageWidgetState>()
        val video = mutableListOf<WidgetPersistence.VideoWidgetState>()
        val pdf = mutableListOf<WidgetPersistence.PdfWidgetState>()
        var status: WidgetPersistence.StatusBarState? = null
        var location: WidgetPersistence.LocationWidgetState? = null
        var map: WidgetPersistence.MapWidgetState? = null
        var calendar: WidgetPersistence.CalendarWidgetState? = null
        var finance: WidgetPersistence.FinanceWidgetState? = null
        var news: WidgetPersistence.NewsWidgetState? = null
        var notifications: WidgetPersistence.NotificationsWidgetState? = null
        var speedometer: WidgetPersistence.SpeedometerWidgetState? = null
        var subtitle: WidgetPersistence.SubtitleWidgetState? = null
        var mirror: WidgetPersistence.MirrorWidgetState? = null

        fun addRecord(record: PersistedWidgetRecord) {
            val decoded = codecs[record.type]?.decode(record.state) ?: return
            when (decoded) {
                is WidgetPersistence.TextWidgetState -> text += decoded
                is WidgetPersistence.BrowserWidgetState -> browser += decoded
                is WidgetPersistence.ImageWidgetState -> image += decoded
                is WidgetPersistence.VideoWidgetState -> video += decoded
                is WidgetPersistence.PdfWidgetState -> pdf += decoded
                is WidgetPersistence.StatusBarState -> if (status == null) status = decoded
                is WidgetPersistence.LocationWidgetState -> if (location == null) location = decoded
                is WidgetPersistence.MapWidgetState -> if (map == null) map = decoded
                is WidgetPersistence.CalendarWidgetState -> if (calendar == null) calendar = decoded
                is WidgetPersistence.FinanceWidgetState -> if (finance == null) finance = decoded
                is WidgetPersistence.NewsWidgetState -> if (news == null) news = decoded
                is WidgetPersistence.NotificationsWidgetState -> if (notifications == null) notifications = decoded
                is WidgetPersistence.SpeedometerWidgetState -> if (speedometer == null) speedometer = decoded
                is WidgetPersistence.SubtitleWidgetState -> if (subtitle == null) subtitle = decoded
                is WidgetPersistence.MirrorWidgetState -> if (mirror == null) mirror = decoded
            }
        }
        dashboard.widgets.forEach(::addRecord)

        var closedText: WidgetPersistence.TextWidgetState? = null
        var closedBrowser: WidgetPersistence.BrowserWidgetState? = null
        var closedStatus: WidgetPersistence.StatusBarState? = null
        var closedLocation: WidgetPersistence.LocationWidgetState? = null
        var closedMap: WidgetPersistence.MapWidgetState? = null
        var closedCalendar: WidgetPersistence.CalendarWidgetState? = null
        var closedFinance: WidgetPersistence.FinanceWidgetState? = null
        var closedNews: WidgetPersistence.NewsWidgetState? = null
        var closedNotifications: WidgetPersistence.NotificationsWidgetState? = null
        var closedSpeedometer: WidgetPersistence.SpeedometerWidgetState? = null
        var closedSubtitle: WidgetPersistence.SubtitleWidgetState? = null
        var closedMirror: WidgetPersistence.MirrorWidgetState? = null
        dashboard.closedTemplates.forEach { record ->
            when (val decoded = codecs[record.type]?.decode(record.state)) {
                is WidgetPersistence.TextWidgetState -> if (closedText == null) closedText = decoded
                is WidgetPersistence.BrowserWidgetState -> if (closedBrowser == null) closedBrowser = decoded
                is WidgetPersistence.StatusBarState -> if (closedStatus == null) closedStatus = decoded
                is WidgetPersistence.LocationWidgetState -> if (closedLocation == null) closedLocation = decoded
                is WidgetPersistence.MapWidgetState -> if (closedMap == null) closedMap = decoded
                is WidgetPersistence.CalendarWidgetState -> if (closedCalendar == null) closedCalendar = decoded
                is WidgetPersistence.FinanceWidgetState -> if (closedFinance == null) closedFinance = decoded
                is WidgetPersistence.NewsWidgetState -> if (closedNews == null) closedNews = decoded
                is WidgetPersistence.NotificationsWidgetState -> if (closedNotifications == null) closedNotifications = decoded
                is WidgetPersistence.SpeedometerWidgetState -> if (closedSpeedometer == null) closedSpeedometer = decoded
                is WidgetPersistence.SubtitleWidgetState -> if (closedSubtitle == null) closedSubtitle = decoded
                is WidgetPersistence.MirrorWidgetState -> if (closedMirror == null) closedMirror = decoded
            }
        }

        val idsByType = dashboard.widgets
            .filter { WidgetDescriptors[it.type].policy == WidgetInstancePolicy.MULTIPLE }
            .groupBy(PersistedWidgetRecord::type, PersistedWidgetRecord::instanceId)
        val canonicalIds = idsByType.all { (type, ids) ->
            ids == ids.indices.map { index -> "${type.persistedId}#${index + 1}" }
        }

        return WidgetPersistence.PersistedState(
            text = text,
            browser = browser,
            image = image,
            status = status,
            location = location,
            map = map,
            calendar = calendar,
            finance = finance,
            news = news,
            notifications = notifications,
            speedometer = speedometer,
            subtitle = subtitle,
            mirror = mirror,
            video = video,
            pdf = pdf,
            instanceIds = if (canonicalIds) emptyMap() else idsByType,
            closedTemplates = WidgetPersistence.ClosedWidgetTemplates(
                text = closedText,
                browser = closedBrowser,
                status = closedStatus,
                location = closedLocation,
                map = closedMap,
                calendar = closedCalendar,
                finance = closedFinance,
                news = closedNews,
                notifications = closedNotifications,
                speedometer = closedSpeedometer,
                subtitle = closedSubtitle,
                mirror = closedMirror
            ),
            hoverControls = dashboard.hoverControls,
            isFirstRun = dashboard.isFirstRun,
            activeLayoutName = dashboard.activeLayoutName,
            isLayoutLocked = dashboard.isLayoutLocked
        )
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
                val type = WidgetTypeId.values().firstOrNull {
                    it.persistedId == record.optString(KEY_TYPE)
                } ?: continue
                val instanceId = record.optString(KEY_INSTANCE_ID).trim()
                val payload = record.optJSONObject(KEY_STATE) ?: continue
                if (instanceId.isBlank() || type !in codecs) continue
                add(PersistedWidgetRecord(type, instanceId, payload))
            }
        }
    }

    private fun parseHoverControls(array: JSONArray?): List<WidgetPersistence.HoverControlPlacementState>? {
        if (array == null) return null
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isNotBlank()) {
                    add(
                        WidgetPersistence.HoverControlPlacementState(
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
