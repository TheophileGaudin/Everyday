package com.everyday.everyday_glasses

import android.content.Context
import android.os.Environment
import android.util.Log
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceTimeRange
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
    const val DEFAULT_LAYOUT_NAME = ShortcutAction.BUILTIN_LAYOUT_STANDARD
    private const val LEGACY_DEFAULT_LAYOUT_NAME = "Default"

    // JSON keys
    private const val KEY_TEXT_WIDGETS = "text_widgets"
    private const val KEY_BROWSER_WIDGETS = "browser_widgets"
    private const val KEY_IMAGE_WIDGETS = "image_widgets"
    private const val KEY_STATUS_BAR = "status_bar"
    private const val KEY_LOCATION_WIDGET = "location_widget"
    private const val KEY_CALENDAR_WIDGET = "calendar_widget"
    private const val KEY_MIRROR_WIDGET = "mirror_widget"
    private const val KEY_FINANCE_WIDGET = "finance_widget"
    private const val KEY_NEWS_WIDGET = "news_widget"
    private const val KEY_SPEEDOMETER_WIDGET = "speedometer_widget"
    private const val KEY_SUBTITLE_WIDGET = "subtitle_widget"
    private const val KEY_CLOSED_TEMPLATES = "closed_templates"
    private const val KEY_CLOSED_TEXT_WIDGET = "text_widget"
    private const val KEY_CLOSED_BROWSER_WIDGET = "browser_widget"
    private const val KEY_CLOSED_STATUS_BAR = "status_bar"
    private const val KEY_CLOSED_LOCATION_WIDGET = "location_widget"
    private const val KEY_CLOSED_CALENDAR_WIDGET = "calendar_widget"
    private const val KEY_CLOSED_MIRROR_WIDGET = "mirror_widget"
    private const val KEY_CLOSED_FINANCE_WIDGET = "finance_widget"
    private const val KEY_CLOSED_NEWS_WIDGET = "news_widget"
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
        val calendar: CalendarWidgetState?,
        val finance: FinanceWidgetState?,
        val news: NewsWidgetState?,
        val speedometer: SpeedometerWidgetState?,
        val subtitle: SubtitleWidgetState?,
        val mirror: MirrorWidgetState?,
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

    data class ClosedWidgetTemplates(
        val text: TextWidgetState? = null,
        val browser: BrowserWidgetState? = null,
        val status: StatusBarState? = null,
        val location: LocationWidgetState? = null,
        val calendar: CalendarWidgetState? = null,
        val finance: FinanceWidgetState? = null,
        val news: NewsWidgetState? = null,
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
        val showLocation: Boolean = true,
        val showCountry: Boolean = true,
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
        calendarWidgetState: CalendarWidgetState? = null,
        mirrorWidgetState: MirrorWidgetState? = null,
        financeWidgetState: FinanceWidgetState? = null,
        newsWidgetState: NewsWidgetState? = null,
        speedometerWidgetState: SpeedometerWidgetState? = null,
        subtitleWidgetState: SubtitleWidgetState? = null,
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
        calendar = calendarWidgetState,
        finance = financeWidgetState,
        news = newsWidgetState,
        speedometer = speedometerWidgetState,
        subtitle = subtitleWidgetState,
        mirror = mirrorWidgetState,
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

        state.status?.let { rootJson.put(KEY_STATUS_BAR, statusBarToJson(it)) }
        state.location?.let { rootJson.put(KEY_LOCATION_WIDGET, locationWidgetToJson(it)) }
        state.calendar?.let { rootJson.put(KEY_CALENDAR_WIDGET, calendarWidgetToJson(it)) }
        state.mirror?.let { rootJson.put(KEY_MIRROR_WIDGET, mirrorWidgetToJson(it)) }
        state.finance?.let { rootJson.put(KEY_FINANCE_WIDGET, financeWidgetToJson(it)) }
        state.news?.let { rootJson.put(KEY_NEWS_WIDGET, newsWidgetToJson(it)) }
        state.speedometer?.let { rootJson.put(KEY_SPEEDOMETER_WIDGET, speedometerWidgetToJson(it)) }
        state.subtitle?.let { rootJson.put(KEY_SUBTITLE_WIDGET, subtitleWidgetToJson(it)) }

        val closedTemplatesJson = JSONObject()
        state.closedTemplates.text?.let { closedTemplatesJson.put(KEY_CLOSED_TEXT_WIDGET, textWidgetToJson(it)) }
        state.closedTemplates.browser?.let { closedTemplatesJson.put(KEY_CLOSED_BROWSER_WIDGET, browserWidgetToJson(it)) }
        state.closedTemplates.status?.let { closedTemplatesJson.put(KEY_CLOSED_STATUS_BAR, statusBarToJson(it)) }
        state.closedTemplates.location?.let { closedTemplatesJson.put(KEY_CLOSED_LOCATION_WIDGET, locationWidgetToJson(it)) }
        state.closedTemplates.calendar?.let { closedTemplatesJson.put(KEY_CLOSED_CALENDAR_WIDGET, calendarWidgetToJson(it)) }
        state.closedTemplates.mirror?.let { closedTemplatesJson.put(KEY_CLOSED_MIRROR_WIDGET, mirrorWidgetToJson(it)) }
        state.closedTemplates.finance?.let { closedTemplatesJson.put(KEY_CLOSED_FINANCE_WIDGET, financeWidgetToJson(it)) }
        state.closedTemplates.news?.let { closedTemplatesJson.put(KEY_CLOSED_NEWS_WIDGET, newsWidgetToJson(it)) }
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
            calendar = parseCalendar(rootJson),
            finance = parseFinance(rootJson),
            news = parseNews(rootJson),
            speedometer = parseSpeedometer(rootJson),
            subtitle = parseSubtitle(rootJson),
            mirror = parseMirror(rootJson),
            closedTemplates = parseClosedTemplates(rootJson),
            hoverControls = parseHoverControls(rootJson),
            isFirstRun = isFirstRun,
            activeLayoutName = sanitizeLayoutName(rootJson.optString(KEY_ACTIVE_LAYOUT_NAME, "")),
            isLayoutLocked = rootJson.optBoolean(KEY_LAYOUT_LOCKED, false)
        )

    /**
     * Save all widget states to file.
     */
    fun saveWidgets(
        context: Context,
        textWidgets: List<TextWidgetState>,
        browserWidgets: List<BrowserWidgetState>,
        imageWidgets: List<ImageWidgetState>,
        statusBarState: StatusBarState?,
        locationWidgetState: LocationWidgetState? = null,
        calendarWidgetState: CalendarWidgetState? = null,
        mirrorWidgetState: MirrorWidgetState? = null,
        financeWidgetState: FinanceWidgetState? = null,
        newsWidgetState: NewsWidgetState? = null,
        speedometerWidgetState: SpeedometerWidgetState? = null,
        subtitleWidgetState: SubtitleWidgetState? = null,
        closedTemplates: ClosedWidgetTemplates = ClosedWidgetTemplates(),
        hoverControls: List<HoverControlPlacementState>? = null,
        activeLayoutName: String? = null,
        isLayoutLocked: Boolean = false
    ): Boolean = saveState(
        context,
        createPersistedState(
            textWidgets = textWidgets,
            browserWidgets = browserWidgets,
            imageWidgets = imageWidgets,
            statusBarState = statusBarState,
            locationWidgetState = locationWidgetState,
            calendarWidgetState = calendarWidgetState,
            mirrorWidgetState = mirrorWidgetState,
            financeWidgetState = financeWidgetState,
            newsWidgetState = newsWidgetState,
            speedometerWidgetState = speedometerWidgetState,
            subtitleWidgetState = subtitleWidgetState,
            closedTemplates = closedTemplates,
            hoverControls = hoverControls,
            isFirstRun = false,
            activeLayoutName = activeLayoutName,
            isLayoutLocked = isLayoutLocked
        )
    )

    fun saveState(context: Context, state: PersistedState): Boolean {
        try {
            writeTextAtomic(getStorageFile(context), stateToJson(state.copy(isFirstRun = false)).toString(2))
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
        val rootJson = loadRootJson(context)
            ?: return PersistedState(
                emptyList(),
                emptyList(),
                emptyList(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                closedTemplates = ClosedWidgetTemplates(),
                hoverControls = null,
                isFirstRun = true,
                activeLayoutName = DEFAULT_LAYOUT_NAME,
                isLayoutLocked = false
            )

        return stateFromJson(rootJson, isFirstRun = false)
    }

    fun listLayouts(context: Context): List<WidgetLayoutRecord> {
        val file = getLayoutsStorageFile(context)
        if (!file.exists()) return emptyList()

        return try {
            val jsonText = file.readText()
            if (jsonText.isBlank()) return emptyList()
            val rootJson = JSONObject(jsonText)
            parseLayoutRecords(rootJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading widget layouts", e)
            emptyList()
        }
    }

    fun saveLayout(context: Context, name: String, state: PersistedState): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || isDefaultLayoutName(trimmedName)) return false

        return try {
            val now = System.currentTimeMillis()
            val existing = listLayouts(context).toMutableList()
            val existingIndex = existing.indexOfFirst { it.name.equals(trimmedName, ignoreCase = true) }
            val createdAt = existing.getOrNull(existingIndex)?.createdAt ?: now
            val record = WidgetLayoutRecord(
                name = trimmedName,
                createdAt = createdAt,
                updatedAt = now,
                state = state.copy(isFirstRun = false, activeLayoutName = trimmedName)
            )

            if (existingIndex >= 0) {
                existing[existingIndex] = record
            } else {
                existing.add(record)
            }

            writeLayoutRecords(context, existing.sortedBy { it.createdAt })
        } catch (e: Exception) {
            Log.e(TAG, "Error saving widget layout '$trimmedName'", e)
            false
        }
    }

    fun loadLayout(context: Context, name: String): PersistedState? {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return null
        return listLayouts(context)
            .firstOrNull { it.name.equals(trimmedName, ignoreCase = true) }
            ?.state
            ?.copy(isFirstRun = false, activeLayoutName = trimmedName)
    }

    fun deleteLayout(context: Context, name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || isDefaultLayoutName(trimmedName)) return false

        return try {
            val remaining = listLayouts(context)
                .filterNot { it.name.equals(trimmedName, ignoreCase = true) }
            writeLayoutRecords(context, remaining)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting widget layout '$trimmedName'", e)
            false
        }
    }

    fun isDefaultLayoutName(name: String?): Boolean {
        val trimmed = name?.trim() ?: return false
        return trimmed.equals(DEFAULT_LAYOUT_NAME, ignoreCase = true) ||
                trimmed.equals(LEGACY_DEFAULT_LAYOUT_NAME, ignoreCase = true)
    }

    private fun sanitizeLayoutName(name: String?): String? =
        name?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Clear all saved widget data.
     */
    fun clearAll(context: Context) {
        try {
            val file = getStorageFile(context)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Cleared saved widget data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing widget data", e)
        }
    }

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

    private fun writeLayoutRecords(context: Context, records: List<WidgetLayoutRecord>): Boolean {
        return try {
            val rootJson = JSONObject().apply {
                put(KEY_LAYOUTS, JSONArray().apply {
                    records.forEach { record ->
                        put(JSONObject().apply {
                            put(KEY_LAYOUT_NAME, record.name)
                            put(KEY_LAYOUT_CREATED_AT, record.createdAt)
                            put(KEY_LAYOUT_UPDATED_AT, record.updatedAt)
                            put(KEY_LAYOUT_STATE, stateToJson(record.state))
                        })
                    }
                })
            }
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
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(text)
        if (!tempFile.renameTo(file)) {
            if (file.exists() && !file.delete()) {
                tempFile.delete()
                throw IOException("Failed to replace ${file.absolutePath}")
            }
            if (!tempFile.renameTo(file)) {
                tempFile.delete()
                throw IOException("Failed to commit ${file.absolutePath}")
            }
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
            put("selectedIndex", state.selectedIndex)
            put("fontScale", state.fontScale)
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
                        showLocation = item.optBoolean("showLocation", true),
                        showCountry = item.optBoolean("showCountry", true),
                        isMinimized = c.isMinimized,
                        isFullscreen = c.isFullscreen,
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
                showLocation = obj.optBoolean("showLocation", true),
                showCountry = obj.optBoolean("showCountry", true),
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
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

    private fun parseNews(rootJson: JSONObject): NewsWidgetState? =
        parseSizedWidget(rootJson, KEY_NEWS_WIDGET) { obj, c, w, h ->
            NewsWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                countryCode = obj.optString("countryCode", "US"),
                selectedIndex = obj.optInt("selectedIndex", 0),
                fontScale = obj.optDouble("fontScale", 1.0).toFloat(),
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
}
