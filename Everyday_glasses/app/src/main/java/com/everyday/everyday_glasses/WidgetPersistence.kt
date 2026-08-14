package com.everyday.everyday_glasses

import android.content.Context
import android.os.Environment
import android.util.AtomicFile
import android.util.Log
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceTimeRange
import com.everyday.shared.sync.NewsSourceSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Handles persistence of widget state.
 *
 * Uses app-specific storage (no special permissions required):
 * - context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) if available
 * - falls back to context.filesDir
 *
 * Note: app-specific external storage does NOT survive uninstall (unlike public Documents).
 */
object WidgetPersistence {
    private const val TAG = "WidgetPersistence"
    private const val FILENAME = "everyday_widgets.json"
    private const val LAYOUTS_FILENAME = "everyday_widget_layouts.json"
    const val DEFAULT_LAYOUT_NAME = BuiltInLayouts.STANDARD

    // JSON keys
    private const val KEY_TEXT_WIDGETS = "text_widgets"
    private const val KEY_BROWSER_WIDGETS = "browser_widgets"
    private const val KEY_IMAGE_WIDGETS = "image_widgets"
    private const val KEY_VIDEO_WIDGETS = "video_widgets"
    private const val KEY_PDF_WIDGETS = "pdf_widgets"
    private const val KEY_STATUS_BAR = "status_bar"
    private const val KEY_LOCATION_WIDGET = "location_widget"
    private const val KEY_MAP_WIDGET = "map_widget"
    private const val KEY_CALENDAR_WIDGET = "calendar_widget"
    private const val KEY_MIRROR_WIDGET = "mirror_widget"
    private const val KEY_FINANCE_WIDGET = "finance_widget"
    private const val KEY_NEWS_WIDGET = "news_widget"
    private const val KEY_NOTIFICATIONS_WIDGET = "notifications_widget"
    private const val KEY_SPEEDOMETER_WIDGET = "speedometer_widget"
    private const val KEY_SUBTITLE_WIDGET = "subtitle_widget"
    private const val KEY_CLOSED_TEMPLATES = "closed_templates"
    private const val KEY_CLOSED_TEXT_WIDGET = "text_widget"
    private const val KEY_CLOSED_BROWSER_WIDGET = "browser_widget"
    private const val KEY_CLOSED_STATUS_BAR = "status_bar"
    private const val KEY_CLOSED_LOCATION_WIDGET = "location_widget"
    private const val KEY_CLOSED_MAP_WIDGET = "map_widget"
    private const val KEY_CLOSED_CALENDAR_WIDGET = "calendar_widget"
    private const val KEY_CLOSED_MIRROR_WIDGET = "mirror_widget"
    private const val KEY_CLOSED_FINANCE_WIDGET = "finance_widget"
    private const val KEY_CLOSED_NEWS_WIDGET = "news_widget"
    private const val KEY_CLOSED_NOTIFICATIONS_WIDGET = "notifications_widget"
    private const val KEY_CLOSED_SPEEDOMETER_WIDGET = "speedometer_widget"
    private const val KEY_CLOSED_SUBTITLE_WIDGET = "subtitle_widget"
    private const val KEY_HOVER_CONTROLS = "hover_controls"
    private const val KEY_HOVER_CONTROL_ID = "id"
    private const val KEY_HOVER_CONTROL_COL = "col"
    private const val KEY_HOVER_CONTROL_ROW = "row"
    private const val KEY_LAYOUT_LOCKED = "layoutLocked"
    private const val KEY_LAYOUTS = "layouts"
    private const val KEY_LAYOUT_NAME = "name"
    private const val KEY_LAYOUT_CREATED_AT = "createdAt"
    private const val KEY_LAYOUT_UPDATED_AT = "updatedAt"
    private const val KEY_LAYOUT_STATE = "state"
    private const val KEY_ACTIVE_LAYOUT_NAME = "activeLayoutName"

    // Avoid spamming logcat if something goes wrong during load.
    @Volatile private var hasLoggedLoadError = false
    @Volatile private var hasLoggedSaveError = false

    // ==================== Common-field interfaces ====================

    /** Fields shared by every persisted widget state (position + minimize/pin). */
    interface CommonWidgetFields {
        val x: Float
        val y: Float
        val isMinimized: Boolean
        val isFullscreen: Boolean
        val savedMinX: Float
        val savedMinY: Float
        val savedMinWidth: Float
        val savedMinHeight: Float
        val isPinned: Boolean
    }

    /** CommonWidgetFields plus explicit width/height (everything except StatusBar). */
    interface SizedWidgetFields : CommonWidgetFields {
        val width: Float
        val height: Float
    }

    // ==================== Common-field mappers ====================

    /** Write the common fields shared by every widget to a JSONObject. */
    fun JSONObject.putCommonFields(state: CommonWidgetFields, includeSize: Boolean = true) {
        put("x", state.x.toDouble())
        put("y", state.y.toDouble())
        if (includeSize && state is SizedWidgetFields) {
            put("width", (state as SizedWidgetFields).width.toDouble())
            put("height", (state as SizedWidgetFields).height.toDouble())
        }
        put("isMinimized", state.isMinimized)
        put("isFullscreen", state.isFullscreen)
        if (state.isMinimized) {
            put("savedMinX", state.savedMinX.toDouble())
            put("savedMinY", state.savedMinY.toDouble())
            put("savedMinWidth", state.savedMinWidth.toDouble())
            put("savedMinHeight", state.savedMinHeight.toDouble())
        }
        put("isPinned", state.isPinned)
    }

    /** Parsed common fields returned by [parseCommonFields]. */
    data class ParsedCommonFields(
        val x: Float,
        val y: Float,
        val isMinimized: Boolean,
        val isFullscreen: Boolean,
        val savedMinX: Float,
        val savedMinY: Float,
        val savedMinWidth: Float,
        val savedMinHeight: Float,
        val isPinned: Boolean
    )

    /** Read the common fields shared by every widget from a JSONObject. */
    fun parseCommonFields(obj: JSONObject): ParsedCommonFields {
        return ParsedCommonFields(
            x = obj.getDouble("x").toFloat(),
            y = obj.getDouble("y").toFloat(),
            isMinimized = obj.optBoolean("isMinimized", false),
            isFullscreen = obj.optBoolean("isFullscreen", false),
            savedMinX = obj.optDouble("savedMinX", 0.0).toFloat(),
            savedMinY = obj.optDouble("savedMinY", 0.0).toFloat(),
            savedMinWidth = obj.optDouble("savedMinWidth", 0.0).toFloat(),
            savedMinHeight = obj.optDouble("savedMinHeight", 0.0).toFloat(),
            isPinned = obj.optBoolean("isPinned", false)
        )
    }

    // ==================== Persisted state ====================

    data class PersistedState(
        val text: List<TextWidgetState>,
        val browser: List<BrowserWidgetState>,
        val image: List<ImageWidgetState>,
        val status: StatusBarState?,
        val location: LocationWidgetState?,
        val map: MapWidgetState? = null,
        val calendar: CalendarWidgetState?,
        val finance: FinanceWidgetState?,
        val news: NewsWidgetState?,
        val notifications: NotificationsWidgetState?,
        val speedometer: SpeedometerWidgetState?,
        val subtitle: SubtitleWidgetState?,
        val mirror: MirrorWidgetState?,
        val video: List<VideoWidgetState> = emptyList(),
        val pdf: List<PdfWidgetState> = emptyList(),
        /** Runtime IDs carried by v2 records; absent for an unversioned v1 document. */
        val instanceIds: Map<WidgetTypeId, List<String>> = emptyMap(),
        val closedTemplates: ClosedWidgetTemplates = ClosedWidgetTemplates(),
        val hoverControls: List<HoverControlPlacementState>? = null,
        val isFirstRun: Boolean = true,  // True if no saved state exists (create default widgets)
        val activeLayoutName: String? = null,
        val isLayoutLocked: Boolean = false
    )

    data class WidgetLayoutRecord(
        val name: String,
        val createdAt: Long,
        val updatedAt: Long,
        val state: PersistedState
    )

    /** Versioned layout record used by the current runtime persistence path. */
    data class PersistedDashboardLayoutRecord(
        val name: String,
        val createdAt: Long,
        val updatedAt: Long,
        val dashboard: WidgetPersistenceV2.PersistedDashboard
    )

    data class ClosedWidgetTemplates(
        val text: TextWidgetState? = null,
        val browser: BrowserWidgetState? = null,
        val status: StatusBarState? = null,
        val location: LocationWidgetState? = null,
        val map: MapWidgetState? = null,
        val calendar: CalendarWidgetState? = null,
        val finance: FinanceWidgetState? = null,
        val news: NewsWidgetState? = null,
        val notifications: NotificationsWidgetState? = null,
        val speedometer: SpeedometerWidgetState? = null,
        val subtitle: SubtitleWidgetState? = null,
        val mirror: MirrorWidgetState? = null
    )

    data class HoverControlPlacementState(
        val controlId: String,
        val col: Int,
        val row: Int
    )

    // ==================== Widget state DTOs ====================

    data class TextWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val text: String,
        val html: String? = null,  // Rich text content as HTML (takes precedence if present)
        val fontSize: Float = 28f,
        val isTextWrap: Boolean = true,
        val columnCount: Int = 1,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class ImageWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val imagePath: String,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class BrowserWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val url: String,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class StatusBarState(
        override val x: Float,
        override val y: Float,
        val showTime: Boolean = true,
        val showDate: Boolean = true,
        val showPhoneBattery: Boolean = true,
        val showGlassesBattery: Boolean = true,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : CommonWidgetFields

    data class LocationWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val showWeather: Boolean = true,
        val showTemperature: Boolean = true,
        val showLocation: Boolean = false,
        val showCountry: Boolean = false,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class VideoWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val uri: String,
        val displayName: String,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false,
    ) : SizedWidgetFields

    data class PdfWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val uri: String,
        val displayName: String,
        val page: Int = 0,
        val zoom: Float = 1f,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false,
    ) : SizedWidgetFields

    data class MapWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val mode: String = MapViewMode.TOP_DOWN.name,
        val zoom: String = MapZoom.MEDIUM.name,
        val showLocation: Boolean = true,
        val showCountry: Boolean = true,
        val poiConfig: PoiDisplayConfig = PoiDisplayConfig.DEFAULT,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class MirrorWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class CalendarWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val fontScale: Float = 1f,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class FinanceWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val selectedSymbol: String = "^GSPC",
        val selectedRange: String = "1D",
        val tiles: List<FinanceDashboardTileConfig> = emptyList(),
        val navigationIndex: Int = 0,
        val tilingSpan: Int = 3,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class NewsWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val countryCode: String = "US",
        val sourceSpec: NewsSourceSpec = NewsSourceSpec.DEFAULT_LOCATION,
        val selectedIndex: Int = 0,
        val fontScale: Float = 1f,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class NotificationsWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val widgetFontScale: Float = 1f,
        val toastFontScale: Float = 1f,
        val scrollIndex: Int = 0,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class SpeedometerWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    data class SubtitleWidgetState(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val phonePlaybackEnabled: Boolean = true,
        val microphoneEnabled: Boolean = false,
        val translationEnabled: Boolean = false,
        override val isMinimized: Boolean = false,
        override val isFullscreen: Boolean = false,
        override val savedMinX: Float = 0f,
        override val savedMinY: Float = 0f,
        override val savedMinWidth: Float = 0f,
        override val savedMinHeight: Float = 0f,
        override val isPinned: Boolean = false
    ) : SizedWidgetFields

    private fun getStorageFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val file = File(dir, FILENAME)
        file.parentFile?.mkdirs()
        return file
    }

    private fun getLayoutsStorageFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val file = File(dir, LAYOUTS_FILENAME)
        file.parentFile?.mkdirs()
        return file
    }

    fun createPersistedState(
        textWidgets: List<TextWidgetState>,
        browserWidgets: List<BrowserWidgetState>,
        imageWidgets: List<ImageWidgetState>,
        statusBarState: StatusBarState?,
        locationWidgetState: LocationWidgetState? = null,
        mapWidgetState: MapWidgetState? = null,
        calendarWidgetState: CalendarWidgetState? = null,
        mirrorWidgetState: MirrorWidgetState? = null,
        financeWidgetState: FinanceWidgetState? = null,
        newsWidgetState: NewsWidgetState? = null,
        notificationsWidgetState: NotificationsWidgetState? = null,
        speedometerWidgetState: SpeedometerWidgetState? = null,
        subtitleWidgetState: SubtitleWidgetState? = null,
        videoWidgets: List<VideoWidgetState> = emptyList(),
        pdfWidgets: List<PdfWidgetState> = emptyList(),
        instanceIds: Map<WidgetTypeId, List<String>> = emptyMap(),
        closedTemplates: ClosedWidgetTemplates = ClosedWidgetTemplates(),
        hoverControls: List<HoverControlPlacementState>? = null,
        isFirstRun: Boolean = false,
        activeLayoutName: String? = null,
        isLayoutLocked: Boolean = false
    ): PersistedState = PersistedState(
        text = textWidgets,
        browser = browserWidgets,
        image = imageWidgets,
        status = statusBarState,
        location = locationWidgetState,
        map = mapWidgetState,
        calendar = calendarWidgetState,
        finance = financeWidgetState,
        news = newsWidgetState,
        notifications = notificationsWidgetState,
        speedometer = speedometerWidgetState,
        subtitle = subtitleWidgetState,
        mirror = mirrorWidgetState,
        video = videoWidgets,
        pdf = pdfWidgets,
        instanceIds = instanceIds,
        closedTemplates = closedTemplates,
        hoverControls = hoverControls,
        isFirstRun = isFirstRun,
        activeLayoutName = sanitizeLayoutName(activeLayoutName),
        isLayoutLocked = isLayoutLocked
    )

    fun stateToJson(state: PersistedState): JSONObject {
        val rootJson = JSONObject()

        rootJson.put(KEY_TEXT_WIDGETS, JSONArray().apply {
            state.text.forEach { put(textWidgetToJson(it)) }
        })
        rootJson.put(KEY_BROWSER_WIDGETS, JSONArray().apply {
            state.browser.forEach { put(browserWidgetToJson(it)) }
        })
        rootJson.put(KEY_IMAGE_WIDGETS, JSONArray().apply {
            state.image.forEach {
                put(JSONObject().apply {
                    putCommonFields(it)
                    put("imagePath", it.imagePath)
                })
            }
        })
        rootJson.put(KEY_VIDEO_WIDGETS, JSONArray().apply {
            state.video.forEach {
                put(JSONObject().apply {
                    putCommonFields(it)
                    put("uri", it.uri)
                    put("displayName", it.displayName)
                })
            }
        })
        rootJson.put(KEY_PDF_WIDGETS, JSONArray().apply {
            state.pdf.forEach {
                put(JSONObject().apply {
                    putCommonFields(it)
                    put("uri", it.uri)
                    put("displayName", it.displayName)
                    put("page", it.page)
                    put("zoom", it.zoom)
                })
            }
        })

        state.status?.let { rootJson.put(KEY_STATUS_BAR, statusBarToJson(it)) }
        state.location?.let { rootJson.put(KEY_LOCATION_WIDGET, locationWidgetToJson(it)) }
        state.map?.let { rootJson.put(KEY_MAP_WIDGET, mapWidgetToJson(it)) }
        state.calendar?.let { rootJson.put(KEY_CALENDAR_WIDGET, calendarWidgetToJson(it)) }
        state.mirror?.let { rootJson.put(KEY_MIRROR_WIDGET, mirrorWidgetToJson(it)) }
        state.finance?.let { rootJson.put(KEY_FINANCE_WIDGET, financeWidgetToJson(it)) }
        state.news?.let { rootJson.put(KEY_NEWS_WIDGET, newsWidgetToJson(it)) }
        state.notifications?.let { rootJson.put(KEY_NOTIFICATIONS_WIDGET, notificationsWidgetToJson(it)) }
        state.speedometer?.let { rootJson.put(KEY_SPEEDOMETER_WIDGET, speedometerWidgetToJson(it)) }
        state.subtitle?.let { rootJson.put(KEY_SUBTITLE_WIDGET, subtitleWidgetToJson(it)) }

        val closedTemplatesJson = JSONObject()
        state.closedTemplates.text?.let { closedTemplatesJson.put(KEY_CLOSED_TEXT_WIDGET, textWidgetToJson(it)) }
        state.closedTemplates.browser?.let { closedTemplatesJson.put(KEY_CLOSED_BROWSER_WIDGET, browserWidgetToJson(it)) }
        state.closedTemplates.status?.let { closedTemplatesJson.put(KEY_CLOSED_STATUS_BAR, statusBarToJson(it)) }
        state.closedTemplates.location?.let { closedTemplatesJson.put(KEY_CLOSED_LOCATION_WIDGET, locationWidgetToJson(it)) }
        state.closedTemplates.map?.let { closedTemplatesJson.put(KEY_CLOSED_MAP_WIDGET, mapWidgetToJson(it)) }
        state.closedTemplates.calendar?.let { closedTemplatesJson.put(KEY_CLOSED_CALENDAR_WIDGET, calendarWidgetToJson(it)) }
        state.closedTemplates.mirror?.let { closedTemplatesJson.put(KEY_CLOSED_MIRROR_WIDGET, mirrorWidgetToJson(it)) }
        state.closedTemplates.finance?.let { closedTemplatesJson.put(KEY_CLOSED_FINANCE_WIDGET, financeWidgetToJson(it)) }
        state.closedTemplates.news?.let { closedTemplatesJson.put(KEY_CLOSED_NEWS_WIDGET, newsWidgetToJson(it)) }
        state.closedTemplates.notifications?.let { closedTemplatesJson.put(KEY_CLOSED_NOTIFICATIONS_WIDGET, notificationsWidgetToJson(it)) }
        state.closedTemplates.speedometer?.let { closedTemplatesJson.put(KEY_CLOSED_SPEEDOMETER_WIDGET, speedometerWidgetToJson(it)) }
        state.closedTemplates.subtitle?.let { closedTemplatesJson.put(KEY_CLOSED_SUBTITLE_WIDGET, subtitleWidgetToJson(it)) }
        if (closedTemplatesJson.length() > 0) {
            rootJson.put(KEY_CLOSED_TEMPLATES, closedTemplatesJson)
        }
        state.hoverControls?.let { placements ->
            rootJson.put(KEY_HOVER_CONTROLS, JSONArray().apply {
                placements.forEach { placement ->
                    put(JSONObject().apply {
                        put(KEY_HOVER_CONTROL_ID, placement.controlId)
                        put(KEY_HOVER_CONTROL_COL, placement.col)
                        put(KEY_HOVER_CONTROL_ROW, placement.row)
                    })
                }
            })
        }
        sanitizeLayoutName(state.activeLayoutName)?.let {
            rootJson.put(KEY_ACTIVE_LAYOUT_NAME, it)
        }
        rootJson.put(KEY_LAYOUT_LOCKED, state.isLayoutLocked)

        return rootJson
    }

    fun stateFromJson(rootJson: JSONObject, isFirstRun: Boolean = false): PersistedState =
        PersistedState(
            text = parseTextWidgets(rootJson),
            browser = parseBrowserWidgets(rootJson),
            image = parseImageWidgets(rootJson),
            status = parseStatusBar(rootJson),
            location = parseLocation(rootJson),
            map = parseMap(rootJson),
            calendar = parseCalendar(rootJson),
            finance = parseFinance(rootJson),
            news = parseNews(rootJson),
            notifications = parseNotifications(rootJson),
            speedometer = parseSpeedometer(rootJson),
            subtitle = parseSubtitle(rootJson),
            mirror = parseMirror(rootJson),
            video = parseVideoWidgets(rootJson),
            pdf = parsePdfWidgets(rootJson),
            closedTemplates = parseClosedTemplates(rootJson),
            hoverControls = parseHoverControls(rootJson),
            isFirstRun = isFirstRun,
            activeLayoutName = sanitizeLayoutName(rootJson.optString(KEY_ACTIVE_LAYOUT_NAME, "")),
            isLayoutLocked = rootJson.optBoolean(KEY_LAYOUT_LOCKED, false)
        )

    /**
     * Encodes one state object using the established v1 field representation.
     *
     * The v2 envelope keeps these payloads deliberately unchanged.  Keeping the
     * field-level codecs here means both schemas share one authoritative parser
     * while the runtime migration is in progress.
     */
    internal fun encodeWidgetStatePayload(type: WidgetTypeId, state: Any): JSONObject = when (type) {
        WidgetTypeId.TEXT -> textWidgetToJson(state as TextWidgetState)
        WidgetTypeId.BROWSER -> browserWidgetToJson(state as BrowserWidgetState)
        WidgetTypeId.IMAGE -> (state as ImageWidgetState).let { widget -> JSONObject().apply {
            putCommonFields(widget)
            put("imagePath", widget.imagePath)
        } }
        WidgetTypeId.VIDEO -> (state as VideoWidgetState).let { widget -> JSONObject().apply {
            putCommonFields(widget)
            put("uri", widget.uri)
            put("displayName", widget.displayName)
        } }
        WidgetTypeId.PDF -> (state as PdfWidgetState).let { widget -> JSONObject().apply {
            putCommonFields(widget)
            put("uri", widget.uri)
            put("displayName", widget.displayName)
            put("page", widget.page)
            put("zoom", widget.zoom)
        } }
        WidgetTypeId.STATUS -> statusBarToJson(state as StatusBarState)
        WidgetTypeId.LOCATION -> locationWidgetToJson(state as LocationWidgetState)
        WidgetTypeId.MAP -> mapWidgetToJson(state as MapWidgetState)
        WidgetTypeId.CALENDAR -> calendarWidgetToJson(state as CalendarWidgetState)
        WidgetTypeId.FINANCE -> financeWidgetToJson(state as FinanceWidgetState)
        WidgetTypeId.NEWS -> newsWidgetToJson(state as NewsWidgetState)
        WidgetTypeId.NOTIFICATIONS -> notificationsWidgetToJson(state as NotificationsWidgetState)
        WidgetTypeId.SPEEDOMETER -> speedometerWidgetToJson(state as SpeedometerWidgetState)
        WidgetTypeId.SUBTITLE -> subtitleWidgetToJson(state as SubtitleWidgetState)
        WidgetTypeId.MIRROR -> mirrorWidgetToJson(state as MirrorWidgetState)
        // File browser state has not been persisted by the v1 schema.
        WidgetTypeId.FILES -> error("${type.persistedId} has no persisted state codec")
    }

    /** Decodes one v2 payload through the same field-level parser as v1. */
    internal fun decodeWidgetStatePayload(type: WidgetTypeId, payload: JSONObject): Any? = when (type) {
        WidgetTypeId.TEXT -> parseTextWidget(payload)
        WidgetTypeId.BROWSER -> parseBrowserWidget(payload)
        WidgetTypeId.IMAGE -> parseImageWidget(payload)
        WidgetTypeId.VIDEO -> parseVideoWidget(payload)
        WidgetTypeId.PDF -> parsePdfWidget(payload)
        WidgetTypeId.STATUS -> parseStatusBarObject(payload)
        WidgetTypeId.LOCATION -> parseLocationPayload(payload)
        WidgetTypeId.MAP -> parseMapPayload(payload)
        WidgetTypeId.CALENDAR -> parseCalendarPayload(payload)
        WidgetTypeId.FINANCE -> parseFinancePayload(payload)
        WidgetTypeId.NEWS -> parseNewsPayload(payload)
        WidgetTypeId.NOTIFICATIONS -> parseNotificationsPayload(payload)
        WidgetTypeId.SPEEDOMETER -> parseSpeedometerPayload(payload)
        WidgetTypeId.SUBTITLE -> parseSubtitlePayload(payload)
        WidgetTypeId.MIRROR -> parseMirrorPayload(payload)
        WidgetTypeId.FILES -> null
    }

    fun saveState(context: Context, state: PersistedState): Boolean {
        return saveDashboard(context, WidgetPersistenceV2.fromLegacy(state))
    }

    /** Saves the runtime record envelope without first converting to fixed v1 fields. */
    fun saveDashboard(context: Context, dashboard: WidgetPersistenceV2.PersistedDashboard): Boolean {
        try {
            writeTextAtomic(
                getStorageFile(context),
                WidgetPersistenceV2.dashboardToJson(dashboard.copy(isFirstRun = false)).toString(2)
            )
            hasLoggedSaveError = false
            return true
        } catch (e: Exception) {
            if (!hasLoggedSaveError) {
                hasLoggedSaveError = true
                Log.e(TAG, "Error saving widgets", e)
            }
            return false
        }
    }

    /**
     * Public API: load everything in one pass (single file read).
     * Returns isFirstRun=true if no save file exists (fresh install),
     * isFirstRun=false if a save file was loaded (returning user).
     */
    fun loadAll(context: Context): PersistedState {
        return WidgetPersistenceV2.toLegacy(loadDashboard(context))
    }

    /** Loads the v2 record envelope; unversioned documents are migrated once on read. */
    fun loadDashboard(context: Context): WidgetPersistenceV2.PersistedDashboard {
        val rootJson = loadRootJson(context)
            ?: return WidgetPersistenceV2.PersistedDashboard(
                widgets = emptyList(),
                isFirstRun = true,
                activeLayoutName = DEFAULT_LAYOUT_NAME
            )

        return when {
            WidgetPersistenceV2.isCurrentSchema(rootJson) ->
                WidgetPersistenceV2.dashboardFromJson(rootJson).copy(isFirstRun = false)
            WidgetPersistenceV2.isLegacySchema(rootJson) -> {
                val migrated = LegacyWidgetStateV1Migrator.migrateDashboard(rootJson)
                // Successful v1 loads are immediately committed as v2. A failed
                // commit leaves the original file intact because writes are atomic.
                saveDashboard(context, migrated)
                migrated.copy(isFirstRun = false)
            }
            else -> {
                Log.e(TAG, "Unsupported widget persistence schema; leaving file untouched")
                WidgetPersistenceV2.PersistedDashboard(
                    widgets = emptyList(),
                    isFirstRun = false,
                    activeLayoutName = DEFAULT_LAYOUT_NAME
                )
            }
        }
    }

    fun listLayouts(context: Context): List<WidgetLayoutRecord> {
        return listDashboardLayouts(context).map { record ->
            WidgetLayoutRecord(record.name, record.createdAt, record.updatedAt, WidgetPersistenceV2.toLegacy(record.dashboard))
        }
    }

    fun listDashboardLayouts(context: Context): List<PersistedDashboardLayoutRecord> {
        val file = getLayoutsStorageFile(context)
        if (!file.exists()) return emptyList()

        return try {
            val jsonText = file.readText()
            if (jsonText.isBlank()) return emptyList()
            val rootJson = JSONObject(jsonText)
            when {
                WidgetPersistenceV2.isCurrentSchema(rootJson) -> WidgetPersistenceV2.dashboardLayoutRecordsFromJson(rootJson).map {
                    PersistedDashboardLayoutRecord(it.name, it.createdAt, it.updatedAt, it.dashboard)
                }
                WidgetPersistenceV2.isLegacySchema(rootJson) -> LegacyWidgetStateV1Migrator.migrateLayoutRecords(rootJson).map { legacy ->
                    PersistedDashboardLayoutRecord(legacy.name, legacy.createdAt, legacy.updatedAt, WidgetPersistenceV2.fromLegacy(legacy.state))
                }.also { migratedRecords ->
                    // Named layouts follow the same one-time migration rule as the
                    // active dashboard.
                    writeDashboardLayoutRecords(context, migratedRecords)
                }
                else -> {
                    Log.e(TAG, "Unsupported widget layout schema; leaving file untouched")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading widget layouts", e)
            emptyList()
        }
    }

    fun saveLayout(context: Context, name: String, state: PersistedState): Boolean {
        return saveDashboardLayout(context, name, WidgetPersistenceV2.fromLegacy(state))
    }

    fun saveDashboardLayout(
        context: Context,
        name: String,
        dashboard: WidgetPersistenceV2.PersistedDashboard
    ): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || isDefaultLayoutName(trimmedName)) return false

        return try {
            val now = System.currentTimeMillis()
            val existing = listDashboardLayouts(context).toMutableList()
            val existingIndex = existing.indexOfFirst { it.name.equals(trimmedName, ignoreCase = true) }
            val createdAt = existing.getOrNull(existingIndex)?.createdAt ?: now
            val record = PersistedDashboardLayoutRecord(
                name = trimmedName,
                createdAt = createdAt,
                updatedAt = now,
                dashboard = dashboard.copy(isFirstRun = false, activeLayoutName = trimmedName)
            )

            if (existingIndex >= 0) {
                existing[existingIndex] = record
            } else {
                existing.add(record)
            }

            writeDashboardLayoutRecords(context, existing.sortedBy { it.createdAt })
        } catch (e: Exception) {
            Log.e(TAG, "Error saving widget layout '$trimmedName'", e)
            false
        }
    }

    fun loadLayout(context: Context, name: String): PersistedState? {
        return loadDashboardLayout(context, name)?.let(WidgetPersistenceV2::toLegacy)
    }

    fun loadDashboardLayout(context: Context, name: String): WidgetPersistenceV2.PersistedDashboard? {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return null
        return listDashboardLayouts(context)
            .firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }
            ?.dashboard
            ?.copy(isFirstRun = false, activeLayoutName = trimmedName)
    }

    fun deleteLayout(context: Context, name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || isDefaultLayoutName(trimmedName)) return false

        return try {
            val remaining = listDashboardLayouts(context)
                .filterNot { it.name.equals(trimmedName, ignoreCase = true) }
            writeDashboardLayoutRecords(context, remaining)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting widget layout '$trimmedName'", e)
            false
        }
    }

    fun isDefaultLayoutName(name: String?): Boolean {
        val trimmed = name?.trim() ?: return false
        return trimmed.equals(DEFAULT_LAYOUT_NAME, ignoreCase = true)
    }

    private fun sanitizeLayoutName(name: String?): String? =
        name?.trim()?.takeIf { it.isNotBlank() }


    private fun parseLayoutRecords(rootJson: JSONObject): List<WidgetLayoutRecord> {
        val layoutsJson = rootJson.optJSONArray(KEY_LAYOUTS) ?: return emptyList()
        val records = mutableListOf<WidgetLayoutRecord>()

        for (i in 0 until layoutsJson.length()) {
            val obj = layoutsJson.optJSONObject(i) ?: continue
            val name = obj.optString(KEY_LAYOUT_NAME, "").trim()
            val stateJson = obj.optJSONObject(KEY_LAYOUT_STATE)
            if (name.isBlank() || stateJson == null) continue

            val createdAt = obj.optLong(KEY_LAYOUT_CREATED_AT, 0L)
            val updatedAt = obj.optLong(KEY_LAYOUT_UPDATED_AT, createdAt)
            records.add(
                WidgetLayoutRecord(
                    name = name,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    state = stateFromJson(stateJson, isFirstRun = false)
                )
            )
        }

        return records.sortedBy { it.createdAt }
    }

    internal fun legacyLayoutRecordsFromJson(rootJson: JSONObject): List<WidgetLayoutRecord> =
        parseLayoutRecords(rootJson)

    private fun writeLayoutRecords(context: Context, records: List<WidgetLayoutRecord>): Boolean {
        return writeDashboardLayoutRecords(context, records.map { record ->
            PersistedDashboardLayoutRecord(record.name, record.createdAt, record.updatedAt, WidgetPersistenceV2.fromLegacy(record.state))
        })
    }

    private fun writeDashboardLayoutRecords(
        context: Context,
        records: List<PersistedDashboardLayoutRecord>
    ): Boolean {
        return try {
            val rootJson = WidgetPersistenceV2.dashboardLayoutRecordsToJson(records.map { record ->
                WidgetPersistenceV2.PersistedDashboardLayoutRecord(
                    record.name,
                    record.createdAt,
                    record.updatedAt,
                    record.dashboard
                )
            })
            writeTextAtomic(getLayoutsStorageFile(context), rootJson.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing widget layouts", e)
            false
        }
    }

    // ----------------------------
    // Internals: load + parse
    // ----------------------------

    private fun loadRootJson(context: Context): JSONObject? {
        val file = getStorageFile(context)

        if (!file.exists()) {
            return null
        }

        val jsonString = runCatching { file.readText() }.getOrElse { e ->
            if (!hasLoggedLoadError) {
                hasLoggedLoadError = true
                Log.e(TAG, "Error reading widget state from ${file.absolutePath}", e)
            }
            return null
        }

        if (jsonString.isBlank()) return null

        return runCatching { JSONObject(jsonString) }.getOrElse { e ->
            if (!hasLoggedLoadError) {
                hasLoggedLoadError = true
                Log.e(TAG, "Error parsing widget JSON", e)
            }
            null
        }
    }

    private fun writeTextAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun textWidgetToJson(widget: TextWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(widget)
            put("text", widget.text)
            widget.html?.let { put("html", it) }
            put("fontSize", widget.fontSize.toDouble())
            put("isTextWrap", widget.isTextWrap)
            put("columnCount", widget.columnCount)
        }

    private fun browserWidgetToJson(widget: BrowserWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(widget)
            put("url", widget.url)
        }

    private fun statusBarToJson(state: StatusBarState): JSONObject =
        JSONObject().apply {
            putCommonFields(state, includeSize = false)
            put("showTime", state.showTime)
            put("showDate", state.showDate)
            put("showPhoneBattery", state.showPhoneBattery)
            put("showGlassesBattery", state.showGlassesBattery)
        }

    private fun locationWidgetToJson(state: LocationWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("showWeather", state.showWeather)
            put("showTemperature", state.showTemperature)
            put("showLocation", state.showLocation)
            put("showCountry", state.showCountry)
        }

    private fun mapWidgetToJson(state: MapWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("mode", state.mode)
            put("zoom", state.zoom)
            put("showLocation", state.showLocation)
            put("showCountry", state.showCountry)
            put("poiConfig", poiConfigToJson(state.poiConfig))
        }

    private fun poiConfigToJson(config: PoiDisplayConfig): JSONObject = JSONObject().apply {
        put("state", config.state.name)
        put("mode", config.mode.name)
        put("categories", JSONArray().apply { config.categories.sorted().forEach(::put) })
        put("radiusM", config.radiusM)
        put("maxResults", config.maxResults)
        put("importantCategories", JSONArray().apply {
            config.importantCategories.sorted().forEach(::put)
        })
        put("importantRadiiM", JSONObject().apply {
            config.importantRadiiM.toSortedMap().forEach { (category, radius) -> put(category, radius) }
        })
        put("normalCategories", JSONArray().apply {
            config.normalCategories.sorted().forEach(::put)
        })
        put("normalMaxResults", config.normalMaxResults)
        put("showClosest", config.showClosest)
        put("closestRadiusM", config.closestRadiusM)
        put("balanceCategories", config.balanceCategories)
    }

    private fun calendarWidgetToJson(state: CalendarWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("fontScale", state.fontScale)
        }

    private fun mirrorWidgetToJson(state: MirrorWidgetState): JSONObject =
        JSONObject().apply { putCommonFields(state) }

    private fun financeWidgetToJson(state: FinanceWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("selectedSymbol", state.selectedSymbol)
            put("selectedRange", state.selectedRange)
            put("navigationIndex", state.navigationIndex)
            put("tilingSpan", state.tilingSpan)
            put("tiles", JSONArray().apply {
                state.tiles.forEach { put(financeTileToJson(it)) }
            })
        }

    private fun financeTileToJson(tile: FinanceDashboardTileConfig): JSONObject =
        JSONObject().apply {
            put("id", tile.id)
            put("assetType", tile.assetType.name)
            put("symbol", tile.symbol)
            put("range", tile.range)
            put("chartType", tile.chartType.name)
            put("slot", tile.slot)
        }

    private fun newsWidgetToJson(state: NewsWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("countryCode", state.countryCode)
            put("sourceKind", state.sourceSpec.kind.name)
            state.sourceSpec.value?.let { put("sourceValue", it) }
            put("selectedIndex", state.selectedIndex)
            put("fontScale", state.fontScale)
        }

    private fun notificationsWidgetToJson(state: NotificationsWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("widgetFontScale", state.widgetFontScale)
            put("toastFontScale", state.toastFontScale)
            put("scrollIndex", state.scrollIndex)
        }

    private fun speedometerWidgetToJson(state: SpeedometerWidgetState): JSONObject =
        JSONObject().apply { putCommonFields(state) }

    private fun subtitleWidgetToJson(state: SubtitleWidgetState): JSONObject =
        JSONObject().apply {
            putCommonFields(state)
            put("phonePlaybackEnabled", state.phonePlaybackEnabled)
            put("microphoneEnabled", state.microphoneEnabled)
            put("translationEnabled", state.translationEnabled)
        }

    private fun parseTextWidget(obj: JSONObject): TextWidgetState? {
        val html: String? =
            if (obj.has("html") && !obj.isNull("html")) obj.optString("html") else null

        return try {
            val c = parseCommonFields(obj)
            TextWidgetState(
                x = c.x, y = c.y,
                width = obj.getDouble("width").toFloat(),
                height = obj.getDouble("height").toFloat(),
                text = obj.optString("text", ""),
                html = html,
                fontSize = obj.optDouble("fontSize", 28.0).toFloat(),
                isTextWrap = obj.optBoolean("isTextWrap", true),
                columnCount = obj.optInt("columnCount", 1).coerceIn(1, 3),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBrowserWidget(obj: JSONObject): BrowserWidgetState? {
        return try {
            val c = parseCommonFields(obj)
            BrowserWidgetState(
                x = c.x, y = c.y,
                width = obj.getDouble("width").toFloat(),
                height = obj.getDouble("height").toFloat(),
                url = obj.optString("url", "https://www.google.com"),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStatusBarObject(obj: JSONObject): StatusBarState? {
        return try {
            val c = parseCommonFields(obj)
            if (c.x < 0 || c.y < 0) return null

            StatusBarState(
                x = c.x, y = c.y,
                showTime = obj.optBoolean("showTime", true),
                showDate = obj.optBoolean("showDate", true),
                showPhoneBattery = obj.optBoolean("showPhoneBattery", true),
                showGlassesBattery = obj.optBoolean("showGlassesBattery", true),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <T> parseSizedWidgetObject(
        obj: JSONObject,
        crossinline builder: (obj: JSONObject, c: ParsedCommonFields, width: Float, height: Float) -> T
    ): T? {
        return try {
            val c = parseCommonFields(obj)
            val width = obj.getDouble("width").toFloat()
            val height = obj.getDouble("height").toFloat()

            if (c.x < 0 || c.y < 0 || width <= 0 || height <= 0) return null

            builder(obj, c, width, height)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseClosedTemplates(rootJson: JSONObject): ClosedWidgetTemplates {
        val obj = rootJson.optJSONObject(KEY_CLOSED_TEMPLATES) ?: return ClosedWidgetTemplates()

        return ClosedWidgetTemplates(
            text = obj.optJSONObject(KEY_CLOSED_TEXT_WIDGET)?.let(::parseTextWidget),
            browser = obj.optJSONObject(KEY_CLOSED_BROWSER_WIDGET)?.let(::parseBrowserWidget),
            status = obj.optJSONObject(KEY_CLOSED_STATUS_BAR)?.let(::parseStatusBarObject),
            location = obj.optJSONObject(KEY_CLOSED_LOCATION_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    LocationWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        showWeather = item.optBoolean("showWeather", true),
                        showTemperature = item.optBoolean("showTemperature", true),
                        showLocation = item.optBoolean("showLocation", false),
                        showCountry = item.optBoolean("showCountry", false),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            map = obj.optJSONObject(KEY_CLOSED_MAP_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    MapWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        mode = item.optString("mode", MapViewMode.TOP_DOWN.name),
                        zoom = item.optString("zoom", MapZoom.MEDIUM.name),
                        showLocation = item.optBoolean("showLocation", true),
                        showCountry = item.optBoolean("showCountry", true),
                        poiConfig = parsePoiConfig(item.optJSONObject("poiConfig")),
                        isMinimized = c.isMinimized, isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            calendar = obj.optJSONObject(KEY_CLOSED_CALENDAR_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    CalendarWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        fontScale = item.optDouble("fontScale", 1.0).toFloat(),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            finance = obj.optJSONObject(KEY_CLOSED_FINANCE_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    FinanceWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        selectedSymbol = item.optString("selectedSymbol", "^GSPC"),
                        selectedRange = item.optString("selectedRange", "1D"),
                        tiles = parseFinanceTiles(item, item.optString("selectedSymbol", "^GSPC"), item.optString("selectedRange", "1D")),
                        navigationIndex = item.optInt("navigationIndex", 0),
                        tilingSpan = item.optInt("tilingSpan", 3).coerceIn(1, 3),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            news = obj.optJSONObject(KEY_CLOSED_NEWS_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    NewsWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        countryCode = item.optString("countryCode", "US"),
                        sourceSpec = parseNewsSource(item),
                        selectedIndex = item.optInt("selectedIndex", 0),
                        fontScale = item.optDouble("fontScale", 1.0).toFloat(),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            notifications = obj.optJSONObject(KEY_CLOSED_NOTIFICATIONS_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    NotificationsWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        widgetFontScale = item.optDouble("widgetFontScale", 1.0).toFloat(),
                        toastFontScale = item.optDouble("toastFontScale", 1.0).toFloat(),
                        scrollIndex = item.optInt("scrollIndex", 0).coerceAtLeast(0),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            speedometer = obj.optJSONObject(KEY_CLOSED_SPEEDOMETER_WIDGET)?.let {
                parseSizedWidgetObject(it) { _, c, w, h ->
                    SpeedometerWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            subtitle = obj.optJSONObject(KEY_CLOSED_SUBTITLE_WIDGET)?.let {
                parseSizedWidgetObject(it) { item, c, w, h ->
                    SubtitleWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        phonePlaybackEnabled = item.optBoolean("phonePlaybackEnabled", true),
                        microphoneEnabled = item.optBoolean("microphoneEnabled", false),
                        translationEnabled = item.optBoolean("translationEnabled", false),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            },
            mirror = obj.optJSONObject(KEY_CLOSED_MIRROR_WIDGET)?.let {
                parseSizedWidgetObject(it) { _, c, w, h ->
                    MirrorWidgetState(
                        x = c.x, y = c.y, width = w, height = h,
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                }
            }
        )
    }

    private fun parseHoverControls(rootJson: JSONObject): List<HoverControlPlacementState>? {
        val jsonArray = rootJson.optJSONArray(KEY_HOVER_CONTROLS) ?: return null
        val placements = mutableListOf<HoverControlPlacementState>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val id = obj.optString(KEY_HOVER_CONTROL_ID, "").trim()
            if (id.isBlank()) continue
            placements.add(
                HoverControlPlacementState(
                    controlId = id,
                    col = obj.optInt(KEY_HOVER_CONTROL_COL, 0),
                    row = obj.optInt(KEY_HOVER_CONTROL_ROW, 0)
                )
            )
        }

        return placements
    }

    private fun parseTextWidgets(rootJson: JSONObject): List<TextWidgetState> {
        val jsonArray = rootJson.optJSONArray(KEY_TEXT_WIDGETS) ?: return emptyList()
        val widgets = mutableListOf<TextWidgetState>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            parseTextWidget(obj)?.let(widgets::add)
        }

        return widgets
    }

    private fun parseImageWidgets(rootJson: JSONObject): List<ImageWidgetState> {
        val jsonArray = rootJson.optJSONArray(KEY_IMAGE_WIDGETS) ?: return emptyList()
        val widgets = mutableListOf<ImageWidgetState>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            try {
                val c = parseCommonFields(obj)
                val imagePath = obj.optString("imagePath", "")
                if (imagePath.isBlank()) continue

                widgets.add(
                    ImageWidgetState(
                        x = c.x, y = c.y,
                        width = obj.getDouble("width").toFloat(),
                        height = obj.getDouble("height").toFloat(),
                        imagePath = imagePath,
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
                        savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                        savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                        isPinned = c.isPinned
                    )
                )
            } catch (_: Exception) {
                // ignore bad entry
            }
        }

        return widgets
    }

    private fun parseVideoWidgets(rootJson: JSONObject): List<VideoWidgetState> {
        val jsonArray = rootJson.optJSONArray(KEY_VIDEO_WIDGETS) ?: return emptyList()
        val widgets = mutableListOf<VideoWidgetState>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            runCatching {
                val c = parseCommonFields(obj)
                val uri = obj.optString("uri", "")
                if (uri.isBlank()) return@runCatching
                widgets += VideoWidgetState(
                    x = c.x,
                    y = c.y,
                    width = obj.getDouble("width").toFloat(),
                    height = obj.getDouble("height").toFloat(),
                    uri = uri,
                    displayName = obj.optString("displayName", "Video"),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX,
                    savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth,
                    savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned,
                )
            }
        }
        return widgets
    }

    private fun parsePdfWidgets(rootJson: JSONObject): List<PdfWidgetState> {
        val jsonArray = rootJson.optJSONArray(KEY_PDF_WIDGETS) ?: return emptyList()
        val widgets = mutableListOf<PdfWidgetState>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            runCatching {
                val c = parseCommonFields(obj)
                val uri = obj.optString("uri", "")
                if (uri.isBlank()) return@runCatching
                widgets += PdfWidgetState(
                    x = c.x,
                    y = c.y,
                    width = obj.getDouble("width").toFloat(),
                    height = obj.getDouble("height").toFloat(),
                    uri = uri,
                    displayName = obj.optString("displayName", "PDF"),
                    page = obj.optInt("page", 0).coerceAtLeast(0),
                    zoom = obj.optDouble("zoom", 1.0).toFloat(),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX,
                    savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth,
                    savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned,
                )
            }
        }
        return widgets
    }

    private fun parseBrowserWidgets(rootJson: JSONObject): List<BrowserWidgetState> {
        val jsonArray = rootJson.optJSONArray(KEY_BROWSER_WIDGETS) ?: return emptyList()
        val widgets = mutableListOf<BrowserWidgetState>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            parseBrowserWidget(obj)?.let(widgets::add)
        }

        return widgets
    }

    private fun parseStatusBar(rootJson: JSONObject): StatusBarState? {
        val statusBarJson = rootJson.optJSONObject(KEY_STATUS_BAR) ?: return null
        return parseStatusBarObject(statusBarJson)
    }

    /** Parse a sized widget (has x/y/width/height + common state fields). */
    private inline fun <T> parseSizedWidget(
        rootJson: JSONObject,
        key: String,
        crossinline builder: (obj: JSONObject, c: ParsedCommonFields, width: Float, height: Float) -> T
    ): T? {
        val obj = rootJson.optJSONObject(key) ?: return null
        return parseSizedWidgetObject(obj, builder)
    }

    private fun parseLocation(rootJson: JSONObject): LocationWidgetState? =
        parseSizedWidget(rootJson, KEY_LOCATION_WIDGET) { obj, c, w, h ->
            LocationWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                showWeather = obj.optBoolean("showWeather", true),
                showTemperature = obj.optBoolean("showTemperature", true),
                showLocation = obj.optBoolean("showLocation", false),
                showCountry = obj.optBoolean("showCountry", false),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseMap(rootJson: JSONObject): MapWidgetState? =
        parseSizedWidget(rootJson, KEY_MAP_WIDGET) { obj, c, w, h ->
            MapWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                mode = obj.optString("mode", MapViewMode.TOP_DOWN.name),
                zoom = obj.optString("zoom", MapZoom.MEDIUM.name),
                showLocation = obj.optBoolean("showLocation", true),
                showCountry = obj.optBoolean("showCountry", true),
                poiConfig = parsePoiConfig(obj.optJSONObject("poiConfig")),
                isMinimized = c.isMinimized, isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parsePoiConfig(obj: JSONObject?): PoiDisplayConfig {
        if (obj == null) return PoiDisplayConfig.DEFAULT
        return runCatching {
            val state = runCatching {
                PoiDisplayConfigState.valueOf(obj.optString("state", PoiDisplayConfigState.CUSTOM.name))
            }.getOrDefault(PoiDisplayConfigState.CUSTOM)
            // Legacy Default records have no mode/profile fields and can be restored directly.
            // New records are parsed below so a caller that copied Default and changed a single
            // variable cannot have that change discarded merely because its old state flag stayed
            // DEFAULT.
            if (state == PoiDisplayConfigState.DEFAULT && !obj.has("mode")) {
                return@runCatching PoiDisplayConfig.DEFAULT
            }
            val categoriesJson = obj.optJSONArray("categories") ?: error("POI categories missing")
            val categories = buildSet {
                for (index in 0 until categoriesJson.length()) add(categoriesJson.getString(index))
            }
            // Files written before the two-mode menu only contain the nearest-profile fields.
            // Recreate them through the compatibility factory so the previous voice behavior
            // remains stable.
            if (!obj.has("mode")) {
                return@runCatching PoiDisplayConfig.custom(
                    categories = categories,
                    radiusM = obj.optInt("radiusM", PoiDisplayConfig.DEFAULT_RADIUS_M),
                    maxResults = obj.optInt("maxResults", PoiDisplayConfig.DEFAULT_MAX_RESULTS)
                )
            }
            val mode = runCatching {
                PoiDisplayMode.valueOf(obj.optString("mode", PoiDisplayMode.NEAREST.name))
            }.getOrDefault(PoiDisplayMode.NEAREST)
            fun stringSet(key: String, fallback: Set<String>): Set<String> {
                val json = obj.optJSONArray(key) ?: return fallback
                return buildSet {
                    for (index in 0 until json.length()) add(json.getString(index))
                }
            }
            val importantCategories = stringSet(
                "importantCategories",
                PoiDisplayConfig.DEFAULT_IMPORTANT_CATEGORIES
            )
            val importantRadii = buildMap {
                val json = obj.optJSONObject("importantRadiiM")
                if (json == null) {
                    putAll(PoiDisplayConfig.DEFAULT_IMPORTANT_RADII_M)
                } else {
                    json.keys().forEach { category -> put(category, json.getInt(category)) }
                }
            }
            val normalCategories = stringSet(
                "normalCategories",
                PoiDisplayConfig.DEFAULT_NORMAL_CATEGORIES
            )
            val candidate = PoiDisplayConfig(
                categories = categories,
                radiusM = obj.optInt("radiusM", PoiDisplayConfig.DEFAULT_RADIUS_M),
                maxResults = obj.optInt("maxResults", PoiDisplayConfig.DEFAULT_MAX_RESULTS),
                state = PoiDisplayConfigState.CUSTOM,
                mode = mode,
                importantCategories = importantCategories,
                importantRadiiM = importantRadii,
                normalCategories = normalCategories,
                normalMaxResults = obj.optInt("normalMaxResults", PoiDisplayConfig.DEFAULT_NORMAL_MAX_RESULTS),
                showClosest = obj.optBoolean("showClosest", PoiDisplayConfig.DEFAULT_SHOW_CLOSEST),
                closestRadiusM = obj.optInt("closestRadiusM", PoiDisplayConfig.DEFAULT_CLOSEST_RADIUS_M),
                balanceCategories = obj.optBoolean("balanceCategories", false)
            )
            if (
                candidate.mode == PoiDisplayMode.DEFAULT &&
                candidate.categories == PoiDisplayConfig.DEFAULT.categories &&
                candidate.radiusM == PoiDisplayConfig.DEFAULT.radiusM &&
                candidate.maxResults == PoiDisplayConfig.DEFAULT.maxResults &&
                candidate.importantCategories == PoiDisplayConfig.DEFAULT.importantCategories &&
                candidate.importantRadiiM == PoiDisplayConfig.DEFAULT.importantRadiiM &&
                candidate.normalCategories == PoiDisplayConfig.DEFAULT.normalCategories &&
                candidate.normalMaxResults == PoiDisplayConfig.DEFAULT.normalMaxResults &&
                candidate.showClosest == PoiDisplayConfig.DEFAULT.showClosest &&
                candidate.closestRadiusM == PoiDisplayConfig.DEFAULT.closestRadiusM &&
                !candidate.balanceCategories
            ) {
                PoiDisplayConfig.DEFAULT
            } else {
                candidate
            }
        }.getOrDefault(PoiDisplayConfig.DEFAULT)
    }

    private fun parseCalendar(rootJson: JSONObject): CalendarWidgetState? =
        parseSizedWidget(rootJson, KEY_CALENDAR_WIDGET) { obj, c, w, h ->
            CalendarWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                fontScale = obj.optDouble("fontScale", 1.0).toFloat(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseMirror(rootJson: JSONObject): MirrorWidgetState? =
        parseSizedWidget(rootJson, KEY_MIRROR_WIDGET) { _, c, w, h ->
            MirrorWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseFinance(rootJson: JSONObject): FinanceWidgetState? =
        parseSizedWidget(rootJson, KEY_FINANCE_WIDGET) { obj, c, w, h ->
            FinanceWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                selectedSymbol = obj.optString("selectedSymbol", "^GSPC"),
                selectedRange = obj.optString("selectedRange", "1D"),
                tiles = parseFinanceTiles(obj, obj.optString("selectedSymbol", "^GSPC"), obj.optString("selectedRange", "1D")),
                navigationIndex = obj.optInt("navigationIndex", 0),
                tilingSpan = obj.optInt("tilingSpan", 3).coerceIn(1, 3),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseFinanceTiles(obj: JSONObject, fallbackSymbol: String, fallbackRange: String): List<FinanceDashboardTileConfig> {
        val items = obj.optJSONArray("tiles") ?: JSONArray()
        val parsed = buildList {
            for (index in 0 until items.length()) {
                val tile = items.optJSONObject(index) ?: continue
                val assetType = FinanceAssetType.fromWireName(tile.optString("assetType"))
                val symbol = tile.optString("symbol").takeIf { it.isNotBlank() } ?: continue
                val range = FinanceTimeRange.fromRange(tile.optString("range", "1d")).range
                add(
                    FinanceDashboardTileConfig(
                        id = tile.optString("id", "${assetType.name}_${symbol}_$range"),
                        assetType = assetType,
                        symbol = symbol,
                        range = range,
                        chartType = FinanceChartType.fromWireName(tile.optString("chartType")),
                        slot = tile.optInt("slot", index)
                    )
                )
            }
        }
        if (parsed.isNotEmpty()) return parsed.take(FinanceWidget.MAX_TILES)
        return listOf(
            FinanceDashboardTileConfig(
                id = "index_${fallbackSymbol}_${fallbackRange}",
                assetType = FinanceAssetType.INDEX,
                symbol = fallbackSymbol,
                range = FinanceTimeRange.fromRange(fallbackRange).range,
                chartType = FinanceChartType.LINE,
                slot = 0
            )
        )
    }

    private fun parseNewsSource(obj: JSONObject): NewsSourceSpec =
        NewsSourceSpec.fromWire(
            obj.optString("sourceKind").takeIf { it.isNotBlank() },
            obj.optString("sourceValue").takeIf { it.isNotBlank() }
        ) ?: NewsSourceSpec.DEFAULT_LOCATION

    private fun parseNews(rootJson: JSONObject): NewsWidgetState? =
        parseSizedWidget(rootJson, KEY_NEWS_WIDGET) { obj, c, w, h ->
            NewsWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                countryCode = obj.optString("countryCode", "US"),
                sourceSpec = parseNewsSource(obj),
                selectedIndex = obj.optInt("selectedIndex", 0),
                fontScale = obj.optDouble("fontScale", 1.0).toFloat(),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseNotifications(rootJson: JSONObject): NotificationsWidgetState? =
        parseSizedWidget(rootJson, KEY_NOTIFICATIONS_WIDGET) { obj, c, w, h ->
            NotificationsWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                widgetFontScale = obj.optDouble("widgetFontScale", 1.0).toFloat(),
                toastFontScale = obj.optDouble("toastFontScale", 1.0).toFloat(),
                scrollIndex = obj.optInt("scrollIndex", 0).coerceAtLeast(0),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseSpeedometer(rootJson: JSONObject): SpeedometerWidgetState? =
        parseSizedWidget(rootJson, KEY_SPEEDOMETER_WIDGET) { _, c, w, h ->
            SpeedometerWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseSubtitle(rootJson: JSONObject): SubtitleWidgetState? =
        parseSizedWidget(rootJson, KEY_SUBTITLE_WIDGET) { obj, c, w, h ->
            SubtitleWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                phonePlaybackEnabled = obj.optBoolean("phonePlaybackEnabled", true),
                microphoneEnabled = obj.optBoolean("microphoneEnabled", false),
                translationEnabled = obj.optBoolean("translationEnabled", false),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    // Payload adapters keep the v2 record codecs on the exact parsing path used
    // by the legacy document, without duplicating validation/default logic.
    private fun payloadRoot(key: String, payload: JSONObject): JSONObject =
        JSONObject().put(key, payload)

    private fun parseImageWidget(payload: JSONObject): ImageWidgetState? =
        parseImageWidgets(JSONObject().put(KEY_IMAGE_WIDGETS, JSONArray().put(payload))).singleOrNull()

    private fun parseVideoWidget(payload: JSONObject): VideoWidgetState? =
        parseVideoWidgets(JSONObject().put(KEY_VIDEO_WIDGETS, JSONArray().put(payload))).singleOrNull()

    private fun parsePdfWidget(payload: JSONObject): PdfWidgetState? =
        parsePdfWidgets(JSONObject().put(KEY_PDF_WIDGETS, JSONArray().put(payload))).singleOrNull()

    private fun parseLocationPayload(payload: JSONObject): LocationWidgetState? =
        parseLocation(payloadRoot(KEY_LOCATION_WIDGET, payload))

    private fun parseMapPayload(payload: JSONObject): MapWidgetState? =
        parseMap(payloadRoot(KEY_MAP_WIDGET, payload))

    private fun parseCalendarPayload(payload: JSONObject): CalendarWidgetState? =
        parseCalendar(payloadRoot(KEY_CALENDAR_WIDGET, payload))

    private fun parseFinancePayload(payload: JSONObject): FinanceWidgetState? =
        parseFinance(payloadRoot(KEY_FINANCE_WIDGET, payload))

    private fun parseNewsPayload(payload: JSONObject): NewsWidgetState? =
        parseNews(payloadRoot(KEY_NEWS_WIDGET, payload))

    private fun parseNotificationsPayload(payload: JSONObject): NotificationsWidgetState? =
        parseNotifications(payloadRoot(KEY_NOTIFICATIONS_WIDGET, payload))

    private fun parseSpeedometerPayload(payload: JSONObject): SpeedometerWidgetState? =
        parseSpeedometer(payloadRoot(KEY_SPEEDOMETER_WIDGET, payload))

    private fun parseSubtitlePayload(payload: JSONObject): SubtitleWidgetState? =
        parseSubtitle(payloadRoot(KEY_SUBTITLE_WIDGET, payload))

    private fun parseMirrorPayload(payload: JSONObject): MirrorWidgetState? =
        parseMirror(payloadRoot(KEY_MIRROR_WIDGET, payload))
}
