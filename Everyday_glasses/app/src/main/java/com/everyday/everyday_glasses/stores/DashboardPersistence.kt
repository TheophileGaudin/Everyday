package com.everyday.everyday_glasses.stores

import android.content.Context
import android.os.Environment
import android.util.AtomicFile
import android.util.Log
import com.everyday.everyday_glasses.ui.widgets.FinanceWidget
import com.everyday.everyday_glasses.map.MapViewMode
import com.everyday.everyday_glasses.map.MapZoom
import com.everyday.everyday_glasses.map.PoiDisplayConfig
import com.everyday.everyday_glasses.map.PoiDisplayConfigState
import com.everyday.everyday_glasses.map.PoiDisplayMode
import com.everyday.everyday_glasses.data_models.WidgetDocument
import com.everyday.everyday_glasses.data_models.WidgetTypeId
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceTimeRange
import com.everyday.shared.sync.NewsSourceSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.collections.forEach

/**
 * Defines saveable dashboard/widget descriptions and stores them in durable app storage.
 *
 * This object owns JSON encoding/decoding, active-dashboard and named-layout files, and storage
 * failures. It does not inspect, construct, register or destroy live widget instances.
 *
 * App-specific external storage does not survive uninstall.
 */
object DashboardPersistence {
    private const val TAG = "DashboardPersistence"
    private const val FILENAME = "everyday_active_dashboard.json"
    private const val LAYOUTS_FILENAME = "everyday_layouts.json"
    const val DEFAULT_LAYOUT_NAME = BuiltInLayouts.STANDARD

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
        val poiConfig: PoiDisplayConfig = PoiDisplayConfig.Companion.DEFAULT,
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

    fun saveDashboard(context: Context, dashboard: WidgetDocument.PersistedDashboard): Boolean {
        try {
            writeTextAtomic(
                getStorageFile(context),
                WidgetDocument.dashboardToJson(dashboard.copy(isFirstRun = false)).toString(2)
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
     * Loads everything in one pass (single file read).
     *
     * Returns isFirstRun=true when there is nothing usable to restore, which makes
     * the app build its default layout. That covers a fresh install and also a save
     * file this version cannot read — documents older than the current schema are not
     * migrated, they are ignored. The file itself is left untouched, so an unreadable
     * document is only replaced when the user saves a layout of their own.
     */
    fun loadDashboard(context: Context): WidgetDocument.PersistedDashboard {
        val rootJson = loadRootJson(context)
        if (rootJson == null || !WidgetDocument.isCurrentSchema(rootJson)) {
            if (rootJson != null) {
                Log.w(TAG, "Ignoring widget document written by an older schema; starting from defaults")
            }
            return WidgetDocument.PersistedDashboard(
                widgets = emptyList(),
                isFirstRun = true,
                activeLayoutName = DEFAULT_LAYOUT_NAME
            )
        }
        return WidgetDocument.dashboardFromJson(rootJson).copy(isFirstRun = false)
    }

    /**
     * Deletes image files no stored dashboard still references.
     *
     * Images are the only widget payload kept outside the documents, so they are the
     * only thing that can be orphaned. Reachability spans everything stored — the
     * active dashboard and every named layout — which is why this lives here rather
     * than with either caller.
     *
     * Called at startup and after deleting a layout: the two moments a reference can
     * disappear. It is deliberately not run on every dashboard save, which would scan
     * storage every time the user finishes dragging a widget.
     */
    fun pruneUnreferencedImages(context: Context) {
        val active = loadDashboard(context)
        if (active.isFirstRun) {
            // Nothing readable was stored, so reachability cannot be established. An
            // ignored document still names the images it used, and unlike widget
            // geometry those cannot be recreated by saving a new layout, so leave them.
            Log.d(TAG, "Skipping image prune: no readable active dashboard")
            return
        }
        val referenced = buildList {
            addAll(imagePaths(active))
            listDashboardLayouts(context).forEach { addAll(imagePaths(it.dashboard)) }
        }
        ImageWidgetStorage.pruneUnreferenced(context, referenced)
    }

    private fun imagePaths(dashboard: WidgetDocument.PersistedDashboard): List<String> =
        dashboard.widgets.mapNotNull { record ->
            if (record.type != WidgetTypeId.IMAGE) return@mapNotNull null
            (WidgetDocument.codecs[WidgetTypeId.IMAGE]?.decode(record.state)
                as? ImageWidgetState)
                ?.imagePath
        }

    fun listDashboardLayouts(context: Context): List<WidgetDocument.PersistedDashboardLayoutRecord> {
        val file = getLayoutsStorageFile(context)
        if (!file.exists()) return emptyList()

        return try {
            val jsonText = file.readText()
            if (jsonText.isBlank()) return emptyList()
            val rootJson = JSONObject(jsonText)
            if (!WidgetDocument.isCurrentSchema(rootJson)) {
                // Same rule as the active dashboard: unreadable layouts are ignored
                // rather than migrated, and the file is left for the user to overwrite.
                Log.w(TAG, "Ignoring layouts written by an older schema")
                return emptyList()
            }
            WidgetDocument.dashboardLayoutRecordsFromJson(rootJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading widget layouts", e)
            emptyList()
        }
    }

    fun saveDashboardLayout(
        context: Context,
        name: String,
        dashboard: WidgetDocument.PersistedDashboard
    ): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isBlank() || isDefaultLayoutName(trimmedName)) return false

        return try {
            val now = System.currentTimeMillis()
            val existing = listDashboardLayouts(context).toMutableList()
            val existingIndex = existing.indexOfFirst { it.name.equals(trimmedName, ignoreCase = true) }
            val createdAt = existing.getOrNull(existingIndex)?.createdAt ?: now
            val record = WidgetDocument.PersistedDashboardLayoutRecord(
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

    fun loadDashboardLayout(context: Context, name: String): WidgetDocument.PersistedDashboard? {
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


    private fun writeDashboardLayoutRecords(
        context: Context,
        records: List<WidgetDocument.PersistedDashboardLayoutRecord>
    ): Boolean {
        return try {
            val rootJson = WidgetDocument.dashboardLayoutRecordsToJson(records)
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

    private fun parseImageWidget(obj: JSONObject): ImageWidgetState? = runCatching {
        val c = parseCommonFields(obj)
        val imagePath = obj.optString("imagePath", "")
        if (imagePath.isBlank()) return null

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
    }.getOrNull()

    private fun parseVideoWidget(obj: JSONObject): VideoWidgetState? = runCatching {
        val c = parseCommonFields(obj)
        val uri = obj.optString("uri", "")
        if (uri.isBlank()) return null
        VideoWidgetState(
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
    }.getOrNull()

    private fun parsePdfWidget(obj: JSONObject): PdfWidgetState? = runCatching {
        val c = parseCommonFields(obj)
        val uri = obj.optString("uri", "")
        if (uri.isBlank()) return null
        PdfWidgetState(
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
    }.getOrNull()

    private fun parseLocationPayload(payload: JSONObject): LocationWidgetState? =
        parseSizedWidgetObject(payload) { obj, c, w, h ->
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

    private fun parseMapPayload(payload: JSONObject): MapWidgetState? =
        parseSizedWidgetObject(payload) { obj, c, w, h ->
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
        if (obj == null) return PoiDisplayConfig.Companion.DEFAULT
        return runCatching {
            val state = runCatching {
                PoiDisplayConfigState.valueOf(obj.optString("state", PoiDisplayConfigState.CUSTOM.name))
            }.getOrDefault(PoiDisplayConfigState.CUSTOM)
            // Legacy Default records have no mode/profile fields and can be restored directly.
            // New records are parsed below so a caller that copied Default and changed a single
            // variable cannot have that change discarded merely because its old state flag stayed
            // DEFAULT.
            if (state == PoiDisplayConfigState.DEFAULT && !obj.has("mode")) {
                return@runCatching PoiDisplayConfig.Companion.DEFAULT
            }
            val categoriesJson = obj.optJSONArray("categories") ?: error("POI categories missing")
            val categories = buildSet {
                for (index in 0 until categoriesJson.length()) add(categoriesJson.getString(index))
            }
            // Files written before the two-mode menu only contain the nearest-profile fields.
            // Recreate them through the compatibility factory so the previous voice behavior
            // remains stable.
            if (!obj.has("mode")) {
                return@runCatching PoiDisplayConfig.Companion.custom(
                    categories = categories,
                    radiusM = obj.optInt("radiusM", PoiDisplayConfig.Companion.DEFAULT_RADIUS_M),
                    maxResults = obj.optInt("maxResults", PoiDisplayConfig.Companion.DEFAULT_MAX_RESULTS)
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
                PoiDisplayConfig.Companion.DEFAULT_IMPORTANT_CATEGORIES
            )
            val importantRadii = buildMap {
                val json = obj.optJSONObject("importantRadiiM")
                if (json == null) {
                    putAll(PoiDisplayConfig.Companion.DEFAULT_IMPORTANT_RADII_M)
                } else {
                    json.keys().forEach { category -> put(category, json.getInt(category)) }
                }
            }
            val normalCategories = stringSet(
                "normalCategories",
                PoiDisplayConfig.Companion.DEFAULT_NORMAL_CATEGORIES
            )
            val candidate = PoiDisplayConfig(
                categories = categories,
                radiusM = obj.optInt("radiusM", PoiDisplayConfig.Companion.DEFAULT_RADIUS_M),
                maxResults = obj.optInt(
                    "maxResults",
                    PoiDisplayConfig.Companion.DEFAULT_MAX_RESULTS
                ),
                state = PoiDisplayConfigState.CUSTOM,
                mode = mode,
                importantCategories = importantCategories,
                importantRadiiM = importantRadii,
                normalCategories = normalCategories,
                normalMaxResults = obj.optInt(
                    "normalMaxResults",
                    PoiDisplayConfig.Companion.DEFAULT_NORMAL_MAX_RESULTS
                ),
                showClosest = obj.optBoolean(
                    "showClosest",
                    PoiDisplayConfig.Companion.DEFAULT_SHOW_CLOSEST
                ),
                closestRadiusM = obj.optInt(
                    "closestRadiusM",
                    PoiDisplayConfig.Companion.DEFAULT_CLOSEST_RADIUS_M
                ),
                balanceCategories = obj.optBoolean("balanceCategories", false)
            )
            if (
                candidate.mode == PoiDisplayMode.DEFAULT &&
                candidate.categories == PoiDisplayConfig.Companion.DEFAULT.categories &&
                candidate.radiusM == PoiDisplayConfig.Companion.DEFAULT.radiusM &&
                candidate.maxResults == PoiDisplayConfig.Companion.DEFAULT.maxResults &&
                candidate.importantCategories == PoiDisplayConfig.Companion.DEFAULT.importantCategories &&
                candidate.importantRadiiM == PoiDisplayConfig.Companion.DEFAULT.importantRadiiM &&
                candidate.normalCategories == PoiDisplayConfig.Companion.DEFAULT.normalCategories &&
                candidate.normalMaxResults == PoiDisplayConfig.Companion.DEFAULT.normalMaxResults &&
                candidate.showClosest == PoiDisplayConfig.Companion.DEFAULT.showClosest &&
                candidate.closestRadiusM == PoiDisplayConfig.Companion.DEFAULT.closestRadiusM &&
                !candidate.balanceCategories
            ) {
                PoiDisplayConfig.Companion.DEFAULT
            } else {
                candidate
            }
        }.getOrDefault(PoiDisplayConfig.Companion.DEFAULT)
    }

    private fun parseCalendarPayload(payload: JSONObject): CalendarWidgetState? =
        parseSizedWidgetObject(payload) { obj, c, w, h ->
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

    private fun parseMirrorPayload(payload: JSONObject): MirrorWidgetState? =
        parseSizedWidgetObject(payload) { _, c, w, h ->
            MirrorWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseFinancePayload(payload: JSONObject): FinanceWidgetState? =
        parseSizedWidgetObject(payload) { obj, c, w, h ->
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
        if (parsed.isNotEmpty()) return parsed.take(FinanceWidget.Companion.MAX_TILES)
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

    private fun parseNewsPayload(payload: JSONObject): NewsWidgetState? =
        parseSizedWidgetObject(payload) { obj, c, w, h ->
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

    private fun parseNotificationsPayload(payload: JSONObject): NotificationsWidgetState? =
        parseSizedWidgetObject(payload) { obj, c, w, h ->
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

    private fun parseSpeedometerPayload(payload: JSONObject): SpeedometerWidgetState? =
        parseSizedWidgetObject(payload) { _, c, w, h ->
            SpeedometerWidgetState(
                x = c.x, y = c.y, width = w, height = h,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned
            )
        }

    private fun parseSubtitlePayload(payload: JSONObject): SubtitleWidgetState? =
        parseSizedWidgetObject(payload) { obj, c, w, h ->
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
